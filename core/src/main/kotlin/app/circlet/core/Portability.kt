package app.circlet.core

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.StateFlow

/** A Health Connect type this app can write. Values are measured, not estimated, except where noted by the writer. */
enum class HealthKind {
    SLEEP,
    RESTING_HEART_RATE,
    HRV,
    RESPIRATORY_RATE,
    ACTIVE_ENERGY,
    WORKOUTS,
    STEPS,
}

/**
 * Whether Health Connect can be used.
 * [available] is false when the provider is not on the device.
 * [granted] is the subset of [HealthKind] the user has allowed.
 */
data class HealthConnectStatus(
    val available: Boolean,
    val enabled: Boolean,
    val granted: Set<HealthKind>,
)

/**
 * Scheduled local backup.
 * [interval] is how often a backup is due.
 * [folderUri] is the content URI of the user-chosen folder, or null if they have not picked one.
 */
data class BackupSettings(
    val enabled: Boolean,
    val interval: Duration,
    val folderUri: String?,
    val lastBackup: Instant?,
)

/**
 * A database export the user can keep.
 * [uri] is a content URI. [byteCount] is the file size in bytes.
 */
data class ExportResult(
    val displayName: String,
    val uri: String,
    val byteCount: Long,
)

/**
 * Export, backup, restore, and the Health Connect toggle.
 * [restore] reads a content URI previously produced by [exportDatabase] or chosen by the user.
 * Turning Health Connect on requests every [HealthKind]. Turning it off clears the grants.
 */
/**
 * What an import of another app's history added.
 * [skipped] counts days left alone because this phone already had its own data for them.
 */
data class ImportResult(
    val imported: Int,
    val skipped: Int,
    val firstDay: java.time.LocalDate?,
    val lastDay: java.time.LocalDate?,
)

interface DataPortability {
    val healthConnect: StateFlow<HealthConnectStatus>
    val backup: StateFlow<BackupSettings>

    suspend fun exportDatabase(): Outcome<ExportResult>

    /** Writes a zip of days, workouts and the last 30 days of heart rate as CSV to the content URI [uri]. */
    suspend fun exportCsv(uri: String): Outcome<Unit>

    suspend fun updateBackup(backup: BackupSettings): Outcome<Unit>

    suspend fun restore(uri: String): Outcome<Unit>

    /**
     * Adds the days from a WHOOP data export (the export zip or its
     * `physiological_cycles.csv`) so trends and baselines can use that history.
     * Days this phone already computed from the strap are kept as they are.
     */
    suspend fun importWhoopExport(uri: String): Outcome<ImportResult>

    suspend fun setHealthConnectEnabled(enabled: Boolean): Outcome<Unit>
}
