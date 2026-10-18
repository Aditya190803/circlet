package app.circlet.strap

import app.circlet.strap.link.GattCallbackKey
import app.circlet.strap.link.GattOperation
import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GattCallbackKeyTest {
    @Test fun rejectsOldConnectionsAndUnrelatedCallbacks() {
        val connection = Any()
        val characteristic = UUID.randomUUID()
        val key = GattCallbackKey(connection, GattOperation.WRITE, characteristic)
        assertTrue(key.matches(connection, GattOperation.WRITE, characteristic))
        assertFalse(key.matches(Any(), GattOperation.WRITE, characteristic))
        assertFalse(key.matches(connection, GattOperation.MTU, null))
        assertFalse(key.matches(connection, GattOperation.DESCRIPTOR, characteristic))
        assertFalse(key.matches(connection, GattOperation.WRITE, UUID.randomUUID()))
    }
}
