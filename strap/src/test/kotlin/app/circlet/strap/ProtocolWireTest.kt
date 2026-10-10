package app.circlet.strap

import app.circlet.core.LinkState
import app.circlet.core.StrapState
import app.circlet.core.SyncProgress
import app.circlet.protocol.Frames
import app.circlet.strap.notify.statusLine
import app.circlet.strap.wire.ProtocolWire
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ProtocolWireTest {
    @Test
    fun harmfulOpcodesCannotBeWritten() {
        val wire = ProtocolWire()
        for (opcode in listOf(0x19, 0x99, 0x9A, 0x1D, 0x20, 0x2D, 0x24, 0x8E)) {
            val frame = Frames.wrap(byteArrayOf(0x23, 0x01, opcode.toByte(), 0x00))
            try {
                wire.vetted(frame)
                fail("opcode 0x${opcode.toString(16)} was accepted")
            } catch (refused: IllegalStateException) {
                assertEquals(true, refused.message?.contains("0x${opcode.toString(16)}") == true)
            }
        }
        wire.setClock(0xA0, 1_700_000_000L)
    }

    @Test
    fun theStatusLineFollowsTheLink() {
        assertEquals("No strap paired", statusLine(StrapState()))
        assertEquals("Looking for your strap", statusLine(StrapState(link = LinkState.RECONNECTING)))
        assertEquals("Connected", statusLine(StrapState(link = LinkState.CONNECTED)))
        assertEquals(
            "Connected · 81% battery · charging",
            statusLine(StrapState(link = LinkState.CONNECTED, batteryPercent = 80.6, charging = true)),
        )
        assertEquals(
            "Syncing history",
            statusLine(StrapState(link = LinkState.CONNECTED, sync = SyncProgress(3, draining = true))),
        )
    }
}
