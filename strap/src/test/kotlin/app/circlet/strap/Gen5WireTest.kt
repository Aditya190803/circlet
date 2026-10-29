// Ported from OpenStrap protocol/test/gen5_historical_test.dart — real v18/v26 captures.
package app.circlet.strap

import app.circlet.core.StrapGeneration
import app.circlet.strap.link.NotifyRole
import app.circlet.strap.wire.Inbound
import app.circlet.strap.wire.ProtocolWire
import org.junit.Assert.*
import org.junit.Test

class Gen5WireTest {
    @Test fun realSummaryKeepsItsUnitsAndWaveformStaysRaw() {
        val wire = ProtocolWire(StrapGeneration.GEN5)
        val summary = wire.feed(NotifyRole.DATA, fixture("v18")).single() as Inbound.Sampled
        val sample = summary.sample!!
        assertEquals(StrapGeneration.GEN5, sample.generation)
        assertEquals(102, sample.heartRateBpm)
        assertEquals(listOf(602, 613), sample.rrIntervalsMs)
        assertEquals(3057, sample.skinTemperatureCentiC)
        assertEquals(0, sample.bandState)
        assertNull(sample.skinTempCount)
        assertNull(sample.optical)
        assertEquals(StrapGeneration.GEN5, summary.raw.generation)
        val waveform = wire.feed(NotifyRole.DATA, fixture("v26")).single() as Inbound.Sampled
        assertNull(waveform.sample)
        assertEquals(StrapGeneration.GEN5, waveform.raw.generation)
        assertEquals(26, waveform.raw.bytes[1].toInt())
        assertTrue(waveform.raw.epochSecond > 0)
    }

    private fun fixture(name: String) = javaClass.getResource("/gen5/$name.hex")!!.readText().trim()
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
