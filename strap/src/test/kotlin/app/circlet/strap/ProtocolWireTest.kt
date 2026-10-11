package app.circlet.strap

import app.circlet.core.LinkState
import app.circlet.core.StrapState
import app.circlet.core.SyncProgress
import app.circlet.protocol.Frames
import app.circlet.strap.link.NotifyRole
import app.circlet.strap.notify.statusLine
import app.circlet.strap.wire.Inbound
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
    fun historicalRecordCarriesTheRawSkinTempCount() {
        val inner = ByteArray(96)
        inner[0] = 0x2F
        inner[1] = 24
        inner[17] = 60
        putF32(inner, 36, 0.0f)
        putF32(inner, 40, 0.0f)
        putF32(inner, 44, 1.0f)
        inner[68] = 0xA7.toByte()
        inner[69] = 0x03
        val inbound = ProtocolWire().feed(NotifyRole.DATA, Frames.wrap(inner))
        val sampled = inbound.single() as Inbound.Sampled
        assertEquals(935, sampled.sample!!.skinTempCount)
    }

    private fun putF32(into: ByteArray, offset: Int, value: Float) {
        val bits = value.toBits()
        into[offset] = (bits and 0xFF).toByte()
        into[offset + 1] = ((bits ushr 8) and 0xFF).toByte()
        into[offset + 2] = ((bits ushr 16) and 0xFF).toByte()
        into[offset + 3] = ((bits ushr 24) and 0xFF).toByte()
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
