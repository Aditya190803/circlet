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
    const val OFF_WRIST_ALERTS = "alert_off_wrist"
    const val LOW_BATTERY_ALERTS = "alert_low_battery"
    const val LOW_BATTERY_THRESHOLD = "alert_low_battery_threshold"
    const val QUIET_START = "alert_quiet_start_minute"
    const val QUIET_END = "alert_quiet_end_minute"
    const val ZONE_BUZZ = "zone_buzz"
    const val ONBOARDING = "onboarding_complete"
    const val SAMPLE_RETENTION = "sample_retention_days"
    const val RAW_RETENTION = "raw_retention_days"
    const val PAIRED_ADDRESS = "paired_address"
    const val PAIRED_GENERATION = "paired_generation"
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
    "sleep_edits",
)

/**
 * Migrations between published schemas.
 * Version 2 adds the skin-temperature count column on samples.
 * Version 3 adds the sleep_edits table for user sleep window overrides.
 * [missingSteps] fails the unit test until the chain from 1 is continuous.
 */
internal object CircletMigrations {
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE samples ADD COLUMN skinTempCount INTEGER")
        }
    }

    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS sleep_edits (" +
                    "date TEXT PRIMARY KEY NOT NULL, " +
                    "start_epoch INTEGER, " +
                    "end_epoch INTEGER, " +
                    "deleted INTEGER NOT NULL, " +
                    "rejected_naps TEXT NOT NULL)",
            )
        }
    }

    val MIGRATION_3_4: Migration = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE samples ADD COLUMN generation TEXT NOT NULL DEFAULT 'GEN4'")
            db.execSQL("ALTER TABLE samples ADD COLUMN skinTemperatureCentiC INTEGER")
            db.execSQL("ALTER TABLE samples ADD COLUMN bandState INTEGER")
            db.execSQL("CREATE TABLE raw_records_new (epochSecond INTEGER NOT NULL, bytes BLOB NOT NULL, archiveKey TEXT NOT NULL PRIMARY KEY)")
            db.execSQL("INSERT INTO raw_records_new SELECT epochSecond, bytes, CAST(epochSecond AS TEXT) FROM raw_records")
            db.execSQL("DROP TABLE raw_records")
            db.execSQL("ALTER TABLE raw_records_new RENAME TO raw_records")
            db.execSQL("CREATE INDEX index_raw_records_epochSecond ON raw_records(epochSecond)")
        }
    }

    val ALL: List<Migration> = listOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

    fun missingSteps(from: Int = 1, to: Int = CircletDatabase.VERSION): List<Pair<Int, Int>> {
        val have = ALL.map { it.startVersion to it.endVersion }.toSet()
        return (from until to).map { it to (it + 1) }.filter { it !in have }
    }
}
