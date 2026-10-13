package app.circlet.data.repo

import app.circlet.core.ImportResult
import app.circlet.data.codec.DayCodec
import app.circlet.data.db.DaySummaryEntity
import app.circlet.data.importer.IMPORTED_ALGORITHM_VERSION
import app.circlet.data.importer.cyclesCsvFrom
import app.circlet.data.importer.parseWhoopCycles
import android.content.Context
import android.net.Uri
import app.circlet.data.export.csvZip
import java.io.IOException
import app.circlet.core.BackupSettings
import app.circlet.core.CoreError
import app.circlet.core.DataPortability
import app.circlet.core.ExportResult
import app.circlet.core.HealthConnectStatus
import app.circlet.core.HealthKind
import app.circlet.core.Outcome
import app.circlet.data.backup.DocumentStore
import app.circlet.data.backup.BACKUP_KEEP
import app.circlet.data.backup.BACKUP_PREFIX
import app.circlet.data.backup.restoreSnapshot
import app.circlet.data.backup.rotate
import app.circlet.data.backup.scheduleBackup
import app.circlet.data.backup.snapshotBytes
import app.circlet.data.backup.validateSnapshot
import app.circlet.data.db.Keys
import app.circlet.data.db.KvEntity
import app.circlet.data.health.HealthConnectGateway
import app.circlet.data.pipeline.RecomputePipeline
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.withLock

internal class DataPortabilityImpl(
    private val store: Store,
    private val memory: Memory,
    private val health: HealthConnectGateway,
    private val documents: DocumentStore,
    private val pipeline: RecomputePipeline,
) : DataPortability {
    override val healthConnect: StateFlow<HealthConnectStatus> = memory.health
    override val backup: StateFlow<BackupSettings> = memory.backup

    fun writePermissions(): Set<String> = health.writePermissions()

    override suspend fun exportDatabase(): Outcome<ExportResult> = catchStorage {
        val bytes = store.lock.withLock { store.io { snapshotBytes(store.database, store.context) } }
        val name = "circlet-${stamp()}.db"
        val uri = store.io { documents.write(store.context.cacheDir.absolutePath, name, bytes) }
        ExportResult(displayName = name, uri = uri, byteCount = bytes.size.toLong())
    }

    override suspend fun exportCsv(uri: String): Outcome<Unit> = catchStorage {
        val zone = store.time.zone()
        val today = store.time.now().atZone(zone).toLocalDate()
        store.io {
            val days = store.database.days().all().map { DayCodec.decode(it.payload) }
            val bytes = csvZip(days, today, zone)
            val output = store.context.contentResolver.openOutputStream(Uri.parse(uri), "wt")
                ?: throw IOException("cannot write there")
            output.use { it.write(bytes) }
        }
    }

    override suspend fun updateBackup(backup: BackupSettings): Outcome<Unit> {
        if (backup.enabled && backup.folderUri.isNullOrBlank()) {
            return Outcome.Err(CoreError.Invalid("backup needs a folder"))
        }
        if (backup.interval.toMinutes() < 15) {
            return Outcome.Err(CoreError.Invalid("backup interval is at least 15 minutes"))
        }
        store.ready.await()
        store.lock.withLock {
            store.io {
                val kv = store.database.kv()
                kv.put(KvEntity(Keys.BACKUP_ENABLED, backup.enabled.toString()))
                kv.put(KvEntity(Keys.BACKUP_INTERVAL, backup.interval.toMillis().toString()))
                val folder = backup.folderUri
                if (folder.isNullOrBlank()) kv.delete(Keys.BACKUP_FOLDER)
                else kv.put(KvEntity(Keys.BACKUP_FOLDER, folder))
                val last = backup.lastBackup
                if (last == null) kv.delete(Keys.LAST_BACKUP)
                else kv.put(KvEntity(Keys.LAST_BACKUP, last.toEpochMilli().toString()))
            }
        }
        backup.folderUri?.let { documents.rememberPermission(it) }
        memory.backup.value = backup
        scheduleBackup(store.context, backup)
        return Outcome.Ok(Unit)
    }

    override suspend fun restore(uri: String): Outcome<Unit> {
        val bytes = try {
            store.io { documents.read(uri) }
        } catch (error: Exception) {
            return Outcome.Err(CoreError.Storage(error.message ?: "unreadable"))
        }
        val problem = validateSnapshot(bytes)
        if (problem != null) return Outcome.Err(CoreError.Storage(problem))
        return catchStorage {
            store.lock.withLock {
                store.io { restoreSnapshot(store.database, bytes) }
                memory.readIntoMemory()
            }
            store.generation.value = store.generation.value + 1
            refreshHealth()
            scheduleBackup(store.context, memory.backup.value)
            pipeline.nudge()
        }
    }

    override suspend fun importWhoopExport(uri: String): Outcome<ImportResult> {
        val bytes = try {
            store.io { documents.read(uri) }
        } catch (error: Exception) {
            return Outcome.Err(CoreError.Storage(error.message ?: "unreadable"))
        }
        val csv = cyclesCsvFrom(bytes)
            ?: return Outcome.Err(CoreError.Invalid("That file is not a WHOOP data export"))
        val days = parseWhoopCycles(csv)
        if (days.isEmpty()) return Outcome.Err(CoreError.Invalid("The export has no days to import"))
        return catchStorage {
            var imported = 0
            var skipped = 0
            store.lock.withLock {
                store.io {
                    store.database.runInTransaction {
                        for (day in days) {
                            val iso = day.date.toString()
                            // A day this phone computed from the strap's own samples stays as it is.
                            val existing = store.database.days().get(iso)
                            if (existing != null && existing.algorithmVersion != IMPORTED_ALGORITHM_VERSION) {
                                skipped++
                                continue
                            }
                            store.database.days().upsert(
                                DaySummaryEntity(
                                    date = iso,
                                    algorithmVersion = IMPORTED_ALGORITHM_VERSION,
                                    computedAtMillis = store.time.now().toEpochMilli(),
                                    payload = DayCodec.encode(day),
                                ),
                            )
                            imported++
                        }
                        // Days scored from the strap lean on this history for their baselines.
                        store.database.days().all()
                            .filter { it.algorithmVersion != IMPORTED_ALGORITHM_VERSION }
                            .forEach { store.database.dirty().mark(it.date) }
                    }
                }
            }
            store.generation.value = store.generation.value + 1
            // Later days lean on the imported history for their baselines, so they are scored again.
            pipeline.nudge()
            ImportResult(imported, skipped, days.first().date, days.last().date)
        }
    }

    override suspend fun setHealthConnectEnabled(enabled: Boolean): Outcome<Unit> {
        if (!enabled) {
            store.io { store.database.kv().put(KvEntity(Keys.HEALTH_ENABLED, "false")) }
            try {
                health.revokeGranted()
            } catch (error: Exception) {
                // The grant set in our status is cleared either way. The platform may keep its own.
            }
            memory.health.value = memory.health.value.copy(enabled = false, granted = emptySet())
            return Outcome.Ok(Unit)
        }
        val status = try {
            health.status(enabled = true)
        } catch (error: Exception) {
            return Outcome.Err(CoreError.Storage(error.message ?: "Health Connect is not available"))
        }
        if (!status.available) return Outcome.Err(CoreError.Storage("Health Connect is not available"))
        memory.health.value = status.copy(enabled = false)
        if (status.granted.isEmpty()) {
            return Outcome.Err(CoreError.Invalid("Health Connect write permission missing"))
        }
        store.io { store.database.kv().put(KvEntity(Keys.HEALTH_ENABLED, "true")) }
        memory.health.value = status
        return Outcome.Ok(Unit)
    }

    suspend fun refreshHealth() {
        val enabled = memory.health.value.enabled
        val status = try {
            health.status(enabled)
        } catch (error: Exception) {
            HealthConnectStatus(available = false, enabled = enabled, granted = emptySet())
        }
        memory.health.value = if (enabled) status else status.copy(enabled = false, granted = emptySet())
    }

    /** Writes a snapshot when the schedule says one is due. The worker calls this. */
    suspend fun runDueBackup(): Outcome<Unit> {
        val settings = memory.backup.value
        val folder = settings.folderUri
        if (!settings.enabled || folder.isNullOrBlank()) {
            return Outcome.Err(CoreError.Invalid("backup is off"))
        }
        val last = settings.lastBackup
        if (last != null && last.plus(settings.interval).isAfter(store.time.now())) return Outcome.Ok(Unit)
        return writeBackup(folder)
    }

    private suspend fun writeBackup(folder: String): Outcome<Unit> = catchStorage {
        val bytes = store.lock.withLock { store.io { snapshotBytes(store.database, store.context) } }
        val at = store.time.now()
        val name = "$BACKUP_PREFIX${at.toEpochMilli().toString().padStart(13, '0')}.db"
        store.io {
            documents.write(folder, name, bytes)
            rotate(documents, folder, BACKUP_PREFIX, BACKUP_KEEP)
        }
        store.io { store.database.kv().put(KvEntity(Keys.LAST_BACKUP, at.toEpochMilli().toString())) }
        memory.backup.value = memory.backup.value.copy(lastBackup = at)
    }

    private fun stamp(): String = STAMP.format(store.time.now())
}

internal fun documentStore(context: Context): DocumentStore = DocumentStore(context)

private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").withZone(ZoneOffset.UTC)

private suspend inline fun <T> catchStorage(block: suspend () -> T): Outcome<T> = try {
    Outcome.Ok(block())
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    Outcome.Err(CoreError.Storage(error.message ?: "storage failed"))
}
