package app.circlet.data.cloud

import android.app.Application
import app.circlet.core.CloudConfig
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FirebaseRestTest {
    private val config = CloudConfig("https://personal-default-rtdb.firebaseio.com", "AIza" + "x".repeat(35))

    @Test fun uploadIsOneAuthenticatedAtomicPatchWithDeterministicKeys() {
        var written: JSONObject? = null
        val api = FirebaseRest(config, "alice", { "id-token" }, CloudHttp { url, method, body ->
            assertTrue(url.startsWith(config.databaseUrl + "/users/alice.json?auth=id-token"))
            assertEquals("PATCH", method)
            written = JSONObject(body!!)
            CloudHttpResponse(204, "")
        })
        api.upload(listOf(CloudRow("samples", "31", "packed"), CloudRow("kv", "32", null)))
        assertEquals("packed", written!!.getJSONObject("v1/samples/31").getString("data"))
        assertTrue(written!!.getJSONObject("v1/kv/32").getBoolean("deleted"))
        assertFalse(written!!.getJSONObject("v1/kv/32").has("data"))
    }

    @Test fun anExpiredIdTokenIsRefreshedAndRetriedOnce() {
        val refreshes = mutableListOf<Boolean>()
        var calls = 0
        val api = FirebaseRest(config, "alice", { force -> refreshes += force; "token" }, CloudHttp { _, _, _ ->
            calls++
            if (calls == 1) CloudHttpResponse(401, "denied") else CloudHttpResponse(200, "1")
        })
        assertEquals(1, api.format())
        assertEquals(listOf(false, true), refreshes)
        assertEquals(2, calls)
    }

    @Test fun permissionFailuresDoNotMasqueradeAsAnEmptyDatabase() {
        val api = FirebaseRest(config, "alice", { "token" }, CloudHttp { _, _, _ -> CloudHttpResponse(403, "denied") })
        assertThrows(CloudFailure::class.java) { api.format() }
    }

    @Test fun nullIsAnEmptyDatabaseButOtherFormatsAreNotSilentlyDiscarded() {
        val empty = FirebaseRest(config, "alice", { "token" }, CloudHttp { _, _, _ -> CloudHttpResponse(200, "null") })
        assertNull(empty.format())
        val wrong = FirebaseRest(config, "alice", { "token" }, CloudHttp { _, _, _ -> CloudHttpResponse(200, "\"unknown\"") })
        assertThrows(CloudFailure::class.java) { wrong.format() }
    }

    @Test fun rangeReadsRequestOnlyTheSelectedCloudKeys() {
        val rows = mutableListOf<CloudRow>()
        val api = FirebaseRest(config, "alice", { "token" }, CloudHttp { url, method, _ ->
            assertEquals("GET", method)
            assertTrue(url.contains("startAt=%2231%22"))
            assertTrue(url.contains("endAt=%2239%22"))
            CloudHttpResponse(200, """{"31":{"deleted":false,"data":"packed"},"32":{"deleted":true}}""")
        })
        api.readRange("samples", "31", "39", rows::add)
        assertEquals(2, rows.size)
        assertEquals("packed", rows[0].payload)
        assertNull(rows[1].payload)
    }

    @Test fun projectValidationRejectsPlaintextAndNonFirebaseHosts() {
        assertEquals(config, config.validated())
        for (url in listOf("http://personal.firebaseio.com", "https://example.com", "https://personal.firebaseio.com.evil.example", "https://personal.firebaseio.com/other", "https://user@personal.firebaseio.com", "this is not a url")) {
            assertThrows(IllegalArgumentException::class.java) { config.copy(databaseUrl = url).validated() }
        }
    }
}
