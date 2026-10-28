package app.circlet.data.cloud

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.circlet.core.Outcome
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

internal object CloudUploadWork {
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<CloudUploadWorker>()
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("circlet_cloud_upload", ExistingWorkPolicy.KEEP, request)
    }

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<CloudUploadWorker>(15, TimeUnit.MINUTES)
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "circlet_cloud_upload_periodic", ExistingPeriodicWorkPolicy.KEEP, request,
        )
    }
}

class CloudUploadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val session = FirebaseCloud.session(applicationContext)
        if (!session.state.value.signedIn) return Result.success()
        if (session.pendingCount() == 0) return Result.success()
        return try {
            // A first backup is hundreds of hours; keep going within WorkManager's ten-minute window.
            val deadline = System.nanoTime() + 8 * 60_000_000_000L
            while (session.pendingCount() > 0 && System.nanoTime() < deadline) {
                if (session.uploadPending() !is Outcome.Ok) return Result.retry()
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            session.problem(failed)
            Result.retry()
        }
    }
}
