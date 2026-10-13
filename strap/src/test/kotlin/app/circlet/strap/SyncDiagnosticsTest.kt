package app.circlet.strap

import app.circlet.core.SyncOutcome
import app.circlet.strap.diag.CounterStore
import app.circlet.strap.diag.RotatingLog
import app.circlet.strap.diag.StoredSyncDiagnostics
import java.io.File
import java.nio.file.Files
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncDiagnosticsTest {
    private class MemoryStore : CounterStore {
        val values = HashMap<String, Long>()
        override fun get(key: String) = values[key] ?: 0L
        override fun add(key: String, delta: Long) {
            values[key] = get(key) + delta
        }
    }

    private fun dir(): File = Files.createTempDirectory("diag").toFile().also { it.deleteOnExit() }

    @Test
    fun logRotatesAtTheLimitAndKeepsOneOlderFile() {
        val dir = dir()
        val log = RotatingLog(dir, maxBytes = 100)
        repeat(30) { log.append("line %02d xxxxxxxxxx".format(it)) }
        assertTrue(File(dir, "sync.log.1").exists())
        assertTrue(File(dir, "sync.log").length() <= 100 + 25)
        val lines = log.lines()
        assertEquals("line 29 xxxxxxxxxx", lines.last())
        assertEquals(lines.sorted(), lines)
        assertTrue(lines.size < 30)
    }

    @Test
    fun outcomesAndSamplesAreCountedAndLogged() {
        val store = MemoryStore()
        val diag = StoredSyncDiagnostics(store, RotatingLog(dir()), now = { Instant.parse("2026-10-13T10:00:00Z") })
        diag.outcome(SyncOutcome.COMPLETED, 120)
        diag.outcome(SyncOutcome.STALLED, 0)
        diag.outcome(SyncOutcome.STALLED, 0)
        diag.samples(120)
        diag.samples(0)
        diag.swallowed("notification", IllegalStateException("secret detail"))

        val counters = diag.counters()
        assertEquals(1, counters.completed)
        assertEquals(2, counters.stalled)
        assertEquals(0, counters.ackFailed)
        assertEquals(120, counters.samplesReceived)
        val lines = diag.recentLines(100)
        assertEquals("2026-10-13T10:00:00Z drain outcome=COMPLETED pulled=120", lines.first())
        assertEquals("2026-10-13T10:00:00Z swallowed where=notification type=IllegalStateException", lines.last())
        assertTrue(diag.exportText().endsWith("\n"))
        assertEquals(2, diag.recentLines(2).size)
    }
}
