package app.circlet.strap.diag

import app.circlet.core.SyncCounters
import app.circlet.core.SyncDiagnostics
import app.circlet.core.SyncOutcome
import java.time.Instant

/** Write side of the sync diagnostics, used by the link. */
internal interface SyncRecorder {
    fun log(message: String)

    /** A drain ended. [pulled] is how many samples that drain committed. */
    fun outcome(outcome: SyncOutcome, pulled: Long)

    /** [count] samples were committed to storage. */
    fun samples(count: Int)

    /** An exception was caught and not rethrown. Only its class name is kept. */
    fun swallowed(where: String, failure: Throwable) = log("swallowed where=$where type=${failure.javaClass.simpleName}")

    object None : SyncRecorder {
        override fun log(message: String) = Unit
        override fun outcome(outcome: SyncOutcome, pulled: Long) = Unit
        override fun samples(count: Int) = Unit
    }
}

/** Persistent long counters. */
internal interface CounterStore {
    fun get(key: String): Long
    fun add(key: String, delta: Long)
}

/** Counters in [store], lines in [log]. Every line starts with a UTC timestamp. */
internal class StoredSyncDiagnostics(
    private val store: CounterStore,
    private val log: RotatingLog,
    private val now: () -> Instant = Instant::now,
) : SyncRecorder, SyncDiagnostics {
    override fun log(message: String) = log.append("${now()} $message")

    override fun outcome(outcome: SyncOutcome, pulled: Long) {
        store.add(outcome.name, 1)
        log("drain outcome=${outcome.name} pulled=$pulled")
    }

    override fun samples(count: Int) {
        if (count > 0) store.add(SAMPLES, count.toLong())
    }

    override fun counters() = SyncCounters(
        completed = store.get(SyncOutcome.COMPLETED.name),
        stalled = store.get(SyncOutcome.STALLED.name),
        disconnected = store.get(SyncOutcome.DISCONNECTED.name),
        ackFailed = store.get(SyncOutcome.ACK_FAILED.name),
        decodeFailed = store.get(SyncOutcome.DECODE_FAILED.name),
        samplesReceived = store.get(SAMPLES),
    )

    override fun recentLines(max: Int): List<String> = log.lines().takeLast(max)

    override fun exportText(): String = log.lines().joinToString("\n", postfix = "\n")

    private companion object {
        const val SAMPLES = "samples_received"
    }
}
