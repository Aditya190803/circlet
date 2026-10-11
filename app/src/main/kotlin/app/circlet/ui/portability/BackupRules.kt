package app.circlet.ui.portability

import app.circlet.core.BackupSettings
import java.time.Duration

/** Why a backup setting cannot be saved. */
enum class BackupProblem {
    FOLDER_NOT_CHOSEN,
    INTERVAL_NOT_POSITIVE,
}

/** Null when [settings] can be saved. A backup that is on needs a folder, and the interval must be positive. */
fun validateBackup(settings: BackupSettings): BackupProblem? = when {
    settings.enabled && settings.folderUri.isNullOrBlank() -> BackupProblem.FOLDER_NOT_CHOSEN
    settings.interval <= Duration.ZERO -> BackupProblem.INTERVAL_NOT_POSITIVE
    else -> null
}

fun BackupProblem.words(): String = when (this) {
    BackupProblem.FOLDER_NOT_CHOSEN -> "Choose a folder before turning on backup."
    BackupProblem.INTERVAL_NOT_POSITIVE -> "The backup interval must be more than zero."
}

/** The intervals the screen offers, in the order they are shown. */
val BACKUP_INTERVALS: List<Duration> = listOf(Duration.ofDays(1), Duration.ofDays(7))

/** Labels for [BACKUP_INTERVALS], index for index. */
val BACKUP_INTERVAL_LABELS: List<String> = listOf("DAILY", "WEEKLY")
