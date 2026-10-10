package app.circlet.analytics

import app.circlet.core.Confidence
import org.junit.Assert.assertEquals
import org.junit.Test

class AnalyticsModuleTest {
    @Test
    fun seesTheCoreConfidenceTiers() {
        assertEquals(3, Confidence.entries.size)
    }
}
