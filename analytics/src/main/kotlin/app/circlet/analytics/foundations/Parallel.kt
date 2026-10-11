// Ordered data-parallel loops for the day pipeline.
// Every index is computed by the same function as the sequential loop and the
// results are assembled in index order, so the numbers are bit-identical to a
// plain loop. Only the wall time changes. The sequential path stays for small
// inputs, where pool hand-off would cost more than the work itself.

package app.circlet.analytics.foundations

import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Minimum items per worker before a parallel loop pays for itself. */
const val PARALLEL_MIN_CHUNK = 32

/**
 * The pool the day pipeline parallels onto. Deliberately smaller than the
 * machine: a fresh JVM spends the first day compiling the pipeline itself,
 * and twelve workers starve those compiler threads. Six leaves the compilers
 * and the collector room while keeping the wall time down. Daemon threads so
 * an idle pool never holds a process open.
 */
internal val workerPool: ExecutorService by lazy {
    val threads = minOf(6, maxOf(2, Runtime.getRuntime().availableProcessors()))
    Executors.newFixedThreadPool(threads) { work ->
        Thread(work, "circlet-comp").apply { isDaemon = true }
    }
}

/**
 * [make] applied to every index in `0 until count`, results in order.
 * Sequential below [minTotal]; above it the range is split into at most one
 * chunk per core on the common pool.
 */
fun <T> orderedMap(count: Int, minTotal: Int = PARALLEL_MIN_CHUNK * 2, make: (Int) -> T): List<T> {
    if (count <= 0) return emptyList()
    val cores = Runtime.getRuntime().availableProcessors()
    if (cores < 2 || count < minTotal) return List(count, make)
    val chunks = minOf(cores, (count + minTotal - 1) / minTotal).coerceAtLeast(2)
    val size = (count + chunks - 1) / chunks
    val pool = workerPool
    val futures = ArrayList<Future<List<T>>>(chunks)
    var start = 0
    while (start < count) {
        val s = start
        val e = minOf(start + size, count)
        futures.add(pool.submit<List<T>> { List(e - s) { make(s + it) } })
        start = e
    }
    val out = ArrayList<T>(count)
    for (future in futures) out.addAll(future.get())
    return out
}

/**
 * Like [orderedMap] but fills caller-owned [out] arrays by index instead of
 * boxing one result object per index. The block must write only `out[*][i]`.
 */
fun fillOrdered(count: Int, minTotal: Int = PARALLEL_MIN_CHUNK * 2, block: (Int) -> Unit) {
    if (count <= 0) return
    val cores = Runtime.getRuntime().availableProcessors()
    if (cores < 2 || count < minTotal) {
        for (i in 0 until count) block(i)
        return
    }
    val chunks = minOf(cores, (count + minTotal - 1) / minTotal).coerceAtLeast(2)
    val size = (count + chunks - 1) / chunks
    val pool = workerPool
    val futures = ArrayList<Future<*>>(chunks)
    var start = 0
    while (start < count) {
        val s = start
        val e = minOf(start + size, count)
        futures.add(
            pool.submit {
                for (i in s until e) block(i)
            },
        )
        start = e
    }
    for (future in futures) future.get()
}

/**
 * Waits for one common-pool task, rethrowing its failure unwrapped so a
 * parallel section reports the same exception the sequential code would.
 */
internal fun <T> Future<T>.joinSame(): T {
    try {
        return get()
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw e
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }
}
