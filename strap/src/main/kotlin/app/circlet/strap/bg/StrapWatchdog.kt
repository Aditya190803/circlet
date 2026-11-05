package app.circlet.strap.bg

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

// Ported from OpenStrap edge/android KeepAliveWorker.kt — a 15 minute check that
// restores presence observation and nudges a paired strap's background connection.
class StrapWatchdog(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        if (!PairedFlag.isPaired(ctx)) return Result.success()
        Log.i(TAG, "checking paired strap background connection")
        PairedFlag.address(ctx)?.let { StrapCompanion.observeIfAssociated(ctx, it) }
        StrapService.start(ctx, reconnect = true)
        return Result.success()
    }

    companion object {
        private const val TAG = "StrapWatchdog"
        private const val NAME = "circlet_strap_watchdog"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<StrapWatchdog>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
