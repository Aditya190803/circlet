package app.circlet.protocol

import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolModuleTest {
    @Test
    fun compilesForJvm17() {
        assertTrue(Runtime.version().feature() >= 17)
    }
}
