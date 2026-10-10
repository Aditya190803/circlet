package app.circlet.data

import app.circlet.core.Confidence
import org.junit.Assert.assertEquals
import org.junit.Test

class DataModuleTest {
    @Test
    fun seesCoreTypes() {
        assertEquals(Confidence.HIGH, Confidence.valueOf("HIGH"))
    }
}
