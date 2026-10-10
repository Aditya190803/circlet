package app.circlet.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SampleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(rows: List<SampleEntity>)

    /** Primary-key range scan. [start] and [endInclusive] are epoch seconds. */
    @Query("SELECT * FROM samples WHERE epochSecond BETWEEN :start AND :endInclusive ORDER BY epochSecond")
    fun range(start: Long, endInclusive: Long): List<SampleEntity>

    @Query("SELECT COUNT(*) FROM samples WHERE epochSecond BETWEEN :start AND :endInclusive")
    fun count(start: Long, endInclusive: Long): Long

    @Query("DELETE FROM samples WHERE epochSecond < :cutoff")
    fun deleteBefore(cutoff: Long)
}

@Dao
interface RawRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(rows: List<RawRecordEntity>)

    @Query("SELECT bytes FROM raw_records WHERE epochSecond = :epochSecond")
    fun bytes(epochSecond: Long): ByteArray?

    @Query("DELETE FROM raw_records WHERE epochSecond < :cutoff")
    fun deleteBefore(cutoff: Long)
}

@Dao
interface StrapEventDao {
    @Insert
    fun insert(row: StrapEventEntity)

    @Query("SELECT * FROM strap_events ORDER BY atMillis")
    fun all(): List<StrapEventEntity>
}

@Dao
interface DayDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(row: DaySummaryEntity)

    @Query("SELECT * FROM day_summaries WHERE date = :date")
    fun get(date: String): DaySummaryEntity?

    @Query("SELECT * FROM day_summaries WHERE date = :date")
    fun observe(date: String): Flow<DaySummaryEntity?>

    @Query("SELECT * FROM day_summaries WHERE date >= :fromDate AND date <= :toDate ORDER BY date")
    fun range(fromDate: String, toDate: String): List<DaySummaryEntity>

    @Query("SELECT * FROM day_summaries ORDER BY date")
    fun all(): List<DaySummaryEntity>

    @Query("SELECT date FROM day_summaries")
    fun dates(): List<String>
}

@Dao
interface ActivityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(rows: List<ActivityEntity>)

    @Query("SELECT * FROM activities WHERE localDate = :date ORDER BY startMillis")
    fun forDate(date: String): List<ActivityEntity>

    @Query("SELECT * FROM activities WHERE localDate = :date ORDER BY startMillis")
    fun observe(date: String): Flow<List<ActivityEntity>>

    @Query("SELECT * FROM activities WHERE localDate >= :fromDate AND localDate <= :toDate ORDER BY startMillis")
    fun range(fromDate: String, toDate: String): List<ActivityEntity>

    @Query("SELECT * FROM activities WHERE id = :id")
    fun get(id: String): ActivityEntity?

    @Query("DELETE FROM activities WHERE localDate = :date AND suppressed = 0 AND userEdited = 0 AND source = 'AUTO'")
    fun deleteUnlockedAutos(date: String)

    @Query("DELETE FROM activities WHERE id = :id")
    fun delete(id: String)

    @Query("SELECT * FROM activities")
    fun all(): List<ActivityEntity>
}

@Dao
interface JournalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertTag(row: JournalTagEntity)

    @Query("SELECT * FROM journal_tags ORDER BY name")
    fun tags(): List<JournalTagEntity>

    @Query("SELECT * FROM journal_tags WHERE id = :id")
    fun tag(id: String): JournalTagEntity?

    @Query("DELETE FROM journal_tags WHERE id = :id")
    fun deleteTag(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertEntry(row: JournalEntryEntity)

    @Query("SELECT * FROM journal_entries WHERE date = :date")
    fun entry(date: String): JournalEntryEntity?

    @Query("SELECT * FROM journal_entries WHERE date = :date")
    fun observeEntry(date: String): Flow<JournalEntryEntity?>

    @Query("SELECT * FROM journal_entries")
    fun entries(): List<JournalEntryEntity>
}

@Dao
interface AlarmDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(row: AlarmEntity)

    @Query("SELECT * FROM alarms")
    fun all(): List<AlarmEntity>

    @Query("SELECT * FROM alarms WHERE id = :id")
    fun get(id: String): AlarmEntity?

    @Query("UPDATE alarms SET enabled = 0 WHERE id = :id")
    fun disable(id: String)

    @Query("UPDATE alarms SET smartWakeMillis = :windowMillis WHERE id = :id")
    fun smartWake(id: String, windowMillis: Long)

    @Query("DELETE FROM alarms")
    fun deleteAll()
}

@Dao
interface KvDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(row: KvEntity)

    @Query("SELECT value FROM kv WHERE key = :key")
    fun get(key: String): String?

    @Query("DELETE FROM kv WHERE key = :key")
    fun delete(key: String)
}

@Dao
interface ProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(row: ProfileEntity)

    @Query("SELECT * FROM profile WHERE id = :id")
    fun get(id: Int = PROFILE_ROW): ProfileEntity?
}

@Dao
interface SpotCheckDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(row: SpotCheckEntity)

    @Query("SELECT * FROM spot_checks WHERE localDate = :date ORDER BY id")
    fun observe(date: String): Flow<List<SpotCheckEntity>>
}

@Dao
interface DirtyDao {
    @Query(
        "INSERT OR REPLACE INTO dirty_days(date, generation) VALUES (" +
            ":date, COALESCE((SELECT generation FROM dirty_days WHERE date = :date), 0) + 1)",
    )
    fun mark(date: String)

    @Query("SELECT generation FROM dirty_days WHERE date = :date")
    fun generation(date: String): Long?

    @Query("SELECT date FROM dirty_days ORDER BY date")
    fun dates(): List<String>

    @Query("DELETE FROM dirty_days WHERE date = :date AND generation = :generation")
    fun clear(date: String, generation: Long)
}

@Dao
interface LiveWorkoutDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(row: LiveWorkoutEntity)

    @Query("SELECT * FROM live_workout WHERE id = :id")
    fun get(id: Int = LIVE_ROW): LiveWorkoutEntity?

    @Query("DELETE FROM live_workout WHERE id = :id")
    fun clear(id: Int = LIVE_ROW)
}
