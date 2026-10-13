package app.circlet.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Identity row stored in [Keys.SCHEMA]. Restore rejects a file without it. */
internal const val SCHEMA_IDENTITY = "circlet"

internal object Keys {
    const val SCHEMA = "schema_identity"
    const val MASS = "mass"
    const val CLOCK = "clock"
    const val DOUBLE_TAP = "double_tap"
    const val HR_BROADCAST = "hr_broadcast"
    const val ONBOARDING = "onboarding_complete"
    const val SAMPLE_RETENTION = "sample_retention_days"
    const val RAW_RETENTION = "raw_retention_days"
    const val PAIRED_ADDRESS = "paired_address"
    const val PAIRED_NAME = "paired_name"
    const val LAST_SYNC = "last_sync_epoch_ms"
    const val BACKUP_ENABLED = "backup_enabled"
    const val BACKUP_INTERVAL = "backup_interval_ms"
    const val BACKUP_FOLDER = "backup_folder_uri"
    const val LAST_BACKUP = "last_backup_epoch_ms"
    const val HEALTH_ENABLED = "health_connect_enabled"
    const val EXPORT_DOCUMENT = "export_document_uri"
    const val ZONE = "time_zone"
    const val PHONE_STEPS = "count_phone_steps"
}

/** User tables copied by restore. Room's master table is not one of them. */
internal val USER_TABLES = listOf(
    "samples",
    "raw_records",
    "strap_events",
    "day_summaries",
    "activities",
    "journal_tags",
    "journal_entries",
    "alarms",
    "kv",
    "profile",
    "spot_checks",
    "dirty_days",
    "live_workout",
)

/**
 * Migrations between published schemas.
 * Version 2 adds the skin-temperature count column on samples.
 * [missingSteps] fails the unit test until the chain from 1 is continuous.
 */
internal object CircletMigrations {
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE samples ADD COLUMN skinTempCount INTEGER")
        }
    }

    val ALL: List<Migration> = listOf(MIGRATION_1_2)

    fun missingSteps(from: Int = 1, to: Int = CircletDatabase.VERSION): List<Pair<Int, Int>> {
        val have = ALL.map { it.startVersion to it.endVersion }.toSet()
        return (from until to).map { it to (it + 1) }.filter { it !in have }
    }
}
