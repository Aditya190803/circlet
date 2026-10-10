package app.circlet.data.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.circlet.core.BackupSettings
import app.circlet.core.Outcome
import app.circlet.data.CircletData
import java.util.concurrent.TimeUnit

internal const val BACKUP_WORK = "circlet-backup"
internal const val BACKUP_KEEP = 7
internal const val BACKUP_PREFIX = "circlet-backup-"

internal fun scheduleBackup(context: Context, backup: BackupSettings) {
    try {
        val manager = WorkManager.getInstance(context)
        if (!backup.enabled || backup.folderUri.isNullOrBlank()) {
            manager.cancelUniqueWork(BACKUP_WORK)
            return
        }
        val minutes = backup.interval.toMinutes().coerceAtLeast(15)
        val request = PeriodicWorkRequestBuilder<CircletBackupWorker>(minutes, TimeUnit.MINUTES).build()
        manager.enqueueUniquePeriodicWork(BACKUP_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    } catch (error: IllegalStateException) {
        // Robolectric unit tests do not initialise WorkManager. The backup function still runs directly.
    }
}

class CircletBackupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val data = CircletDataHolder.current ?: return Result.retry()
        return when (data.runDueBackup()) {
            is Outcome.Ok -> Result.success()
            is Outcome.Err -> Result.retry()
        }
    }
}

internal object CircletDataHolder {
    @Volatile
    var current: CircletData? = null
}
