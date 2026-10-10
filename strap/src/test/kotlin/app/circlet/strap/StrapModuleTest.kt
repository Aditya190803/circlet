package app.circlet.strap

import app.circlet.core.LinkState
import org.junit.Assert.assertEquals
import org.junit.Test

class StrapModuleTest {
    @Test
    fun seesTheLinkContract() {
        assertEquals(LinkState.UNPAIRED, LinkState.valueOf("UNPAIRED"))
    }
}
