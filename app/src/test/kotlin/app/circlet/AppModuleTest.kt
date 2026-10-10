package app.circlet

import org.junit.Assert.assertEquals
import org.junit.Test

class AppModuleTest {
    @Test
    fun namespaceIsTheApplicationId() {
        assertEquals("app.circlet", "app.circlet")
    }
}
