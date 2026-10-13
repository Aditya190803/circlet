package app.circlet.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One decoded 1 Hz sample.
 *
 * [epochSecond] is an INTEGER PRIMARY KEY, so SQLite stores it as the rowid.
 * That is the compact single-key layout Room can express. WITHOUT ROWID is not
 * a Room option, and it would not shrink a table whose only key is already the rowid.
 *
 * [rr] is little-endian unsigned 16-bit milliseconds, empty when there were no beats.
 * [gravity] is three little-endian floats (x, y, z), or null when the record had no vector.
 * Optical columns are ADC counts. Null means that layout did not carry the channel.
 * [skinTempCount] is the uncalibrated skin-temperature count from the record.
 * Null means that layout did not carry the word. Never degrees.
 * The untouched record bytes live in [RawRecordEntity], not here, so a range read of a
 * full day does not drag the archive along.
 */
@Entity(tableName = "samples")
data class SampleEntity(
    @PrimaryKey val epochSecond: Long,
    val heartRate: Int?,
    val rr: ByteArray?,
    val gravity: ByteArray?,
    val green: Int?,
    val red: Int?,
    val infrared: Int?,
    val ambient: Int?,
    val skinTempCount: Int? = null,
)

/** Untouched historical record body, keyed by the same epoch second as [SampleEntity]. */
@Entity(tableName = "raw_records")
data class RawRecordEntity(
    @PrimaryKey val epochSecond: Long,
    val bytes: ByteArray,
)

@Entity(tableName = "strap_events", indices = [Index("atMillis")])
data class StrapEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val atMillis: Long,
    val kind: String,
    val number: Double?,
    val flag: Boolean?,
)

/** One derived local day. [payload] is the [app.circlet.core.DaySummary] JSON. */
@Entity(tableName = "day_summaries")
data class DaySummaryEntity(
    @PrimaryKey val date: String,
    val algorithmVersion: Int,
    val computedAtMillis: Long,
    val payload: String,
)

/**
 * One workout. User edits and manual rows survive recomputation.
 * A suppressed row is a deleted auto-detection; its times stop the same detection returning.
 * [payload] is the activity JSON, ignored while [suppressed] is true.
 */
@Entity(tableName = "activities", indices = [Index("localDate")])
data class ActivityEntity(
    @PrimaryKey val id: String,
    val localDate: String,
    val source: String,
    val userEdited: Boolean,
    val suppressed: Boolean,
    val startMillis: Long,
    val endMillis: Long,
    val payload: String,
)

@Entity(tableName = "journal_tags")
data class JournalTagEntity(
    @PrimaryKey val id: String,
    val name: String,
    val builtIn: Boolean,
)

@Entity(tableName = "journal_entries")
data class JournalEntryEntity(
    @PrimaryKey val date: String,
    val note: String,
    val tagIds: String,
)

@Entity(tableName = "alarms")
data class AlarmEntity(
    @PrimaryKey val id: String,
    val time: String,
    val weekdays: String,
    val smartWakeMillis: Long,
    val enabled: Boolean,
)

@Entity(tableName = "kv")
data class KvEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey val id: Int = PROFILE_ROW,
    val payload: String,
)

@Entity(tableName = "spot_checks", indices = [Index("localDate")])
data class SpotCheckEntity(
    @PrimaryKey val id: String,
    val localDate: String,
    val payload: String,
)

/** Bumped on every sample commit that touches the date, so a slow recompute cannot clear a newer dirty mark. */
@Entity(tableName = "dirty_days")
data class DirtyDayEntity(
    @PrimaryKey val date: String,
    val generation: Long,
)

@Entity(tableName = "live_workout")
data class LiveWorkoutEntity(
    @PrimaryKey val id: Int = LIVE_ROW,
    val payload: String,
)

/**
 * User override of the main sleep window for [date]. [startEpoch]/[endEpoch] are epoch seconds.
 * [deleted] removes the main sleep for the date. [rejectedNaps] is a comma-separated list of nap start epochs.
 */
@Entity(tableName = "sleep_edits")
data class SleepEditEntity(
    @PrimaryKey val date: String,
    @ColumnInfo(name = "start_epoch") val startEpoch: Long?,
    @ColumnInfo(name = "end_epoch") val endEpoch: Long?,
    val deleted: Boolean,
    @ColumnInfo(name = "rejected_naps") val rejectedNaps: String,
)

internal const val PROFILE_ROW = 1
internal const val LIVE_ROW = 1
