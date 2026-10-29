package app.circlet.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import app.circlet.core.JournalTag

@Database(
    entities = [
        SampleEntity::class,
        RawRecordEntity::class,
        StrapEventEntity::class,
        DaySummaryEntity::class,
        ActivityEntity::class,
        JournalTagEntity::class,
        JournalEntryEntity::class,
        AlarmEntity::class,
        KvEntity::class,
        ProfileEntity::class,
        SpotCheckEntity::class,
        DirtyDayEntity::class,
        LiveWorkoutEntity::class,
        SleepEditEntity::class,
    ],
    version = CircletDatabase.VERSION,
    exportSchema = true,
)
abstract class CircletDatabase : RoomDatabase() {
    abstract fun samples(): SampleDao
    abstract fun rawRecords(): RawRecordDao
    abstract fun strapEvents(): StrapEventDao
    abstract fun days(): DayDao
    abstract fun activities(): ActivityDao
    abstract fun journal(): JournalDao
    abstract fun alarms(): AlarmDao
    abstract fun kv(): KvDao
    abstract fun profile(): ProfileDao
    abstract fun spotChecks(): SpotCheckDao
    abstract fun dirty(): DirtyDao
    abstract fun liveWorkout(): LiveWorkoutDao
    abstract fun sleepEdits(): SleepEditDao

    companion object {
        const val VERSION = 4
        const val NAME = "circlet.db"
    }
}

internal val BUILT_IN_TAGS = listOf(
    JournalTag("alcohol", "Alcohol", builtIn = true),
    JournalTag("caffeine", "Caffeine", builtIn = true),
    JournalTag("late-meal", "Late meal", builtIn = true),
    JournalTag("stressed", "Stressed", builtIn = true),
    JournalTag("sick", "Sick", builtIn = true),
    JournalTag("travel", "Travel", builtIn = true),
    JournalTag("device-in-bed", "Device in bed", builtIn = true),
    JournalTag("hydrated", "Hydrated", builtIn = true),
)

private object SeedCallback : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        db.compileStatement("INSERT INTO kv(key, value) VALUES (?, ?)").use { statement ->
            statement.bindString(1, Keys.SCHEMA)
            statement.bindString(2, SCHEMA_IDENTITY)
            statement.executeInsert()
        }
        db.compileStatement("INSERT INTO kv(key, value) VALUES (?, ?)").use { statement ->
            statement.bindString(1, Keys.RAW_RETENTION)
            statement.bindString(2, "14")
            statement.executeInsert()
        }
        val insert = db.compileStatement("INSERT INTO journal_tags(id, name, builtIn) VALUES (?, ?, 1)")
        for (tag in BUILT_IN_TAGS) {
            insert.bindString(1, tag.id)
            insert.bindString(2, tag.name)
            insert.executeInsert()
            insert.clearBindings()
        }
        insert.close()
    }
}

internal fun openDatabase(context: Context): CircletDatabase {
    val builder = Room.databaseBuilder(context, CircletDatabase::class.java, CircletDatabase.NAME)
        .addCallback(SeedCallback)
    if (CircletMigrations.ALL.isNotEmpty()) {
        builder.addMigrations(*CircletMigrations.ALL.toTypedArray())
    }
    return builder.build()
}

internal fun openMemoryDatabase(context: Context): CircletDatabase =
    Room.inMemoryDatabaseBuilder(context, CircletDatabase::class.java)
        .allowMainThreadQueries()
        .addCallback(SeedCallback)
        .build()

/** Named file database for tests. Production [openDatabase] still refuses main-thread queries. */
internal fun openNamedDatabase(context: Context, name: String): CircletDatabase =
    Room.databaseBuilder(context, CircletDatabase::class.java, name)
        .allowMainThreadQueries()
        .addCallback(SeedCallback)
        .build()
