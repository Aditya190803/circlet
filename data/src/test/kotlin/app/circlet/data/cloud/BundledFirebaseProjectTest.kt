package app.circlet.data.cloud

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.circlet.core.CloudConfig
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BundledFirebaseProjectTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val preferences get() = context.getSharedPreferences("bundled-project-test", 0)
    private val config = CloudConfig(
        "https://whoop-circlet-default-rtdb.asia-southeast1.firebasedatabase.app",
        "AIza" + "x".repeat(35),
    )

    @Before fun clear() { preferences.edit().clear().commit() }

    @Test fun aFreshInstallUsesTheBundledProjectWithoutManualEntry() {
        assertTrue(BundledFirebaseProject.install(preferences, config, pending = false))
        assertEquals(config.databaseUrl, preferences.getString("url", null))
        assertEquals(config.apiKey, preferences.getString("api_key", null))
        assertFalse(preferences.getBoolean("session_saved", false))
    }

    @Test fun anUpdateCanRefreshTheKeyForTheSameProjectWithoutLosingTheAccount() {
        preferences.edit().putString("url", config.databaseUrl).putString("api_key", "old-key")
            .putString("uid", "alice").putBoolean("session_saved", true).commit()
        assertTrue(BundledFirebaseProject.install(preferences, config, pending = true))
        assertEquals("alice", preferences.getString("uid", null))
        assertTrue(preferences.getBoolean("session_saved", false))
        assertEquals(config.apiKey, preferences.getString("api_key", null))
    }

    @Test fun queuedChangesCannotBeRedirectedToTheBundledProject() {
        val previous = "https://previous-default-rtdb.firebaseio.com"
        preferences.edit().putString("url", previous).putString("api_key", "previous-key").commit()
        assertFalse(BundledFirebaseProject.install(preferences, config, pending = true))
        assertEquals(previous, preferences.getString("url", null))
        assertEquals("previous-key", preferences.getString("api_key", null))
    }

    @Test fun anExistingAccountCannotBeRedirectedEvenWhenItsQueueIsEmpty() {
        preferences.edit().putString("url", "https://previous.firebaseio.com").putString("uid", "alice").commit()
        assertFalse(BundledFirebaseProject.install(preferences, config, pending = false))
    }

    @Test fun aBuildWithoutBundledConfigurationPreservesExistingSettings() {
        preferences.edit().putString("url", config.databaseUrl).commit()
        assertFalse(BundledFirebaseProject.install(preferences, null, pending = false))
        assertEquals(config.databaseUrl, preferences.getString("url", null))
    }

    @Test fun androidRestrictionsUseTheInstalledPackageAndCertificateSha1() {
        val headers = androidFirebaseHeaders("app.circlet", "abc".toByteArray())
        assertEquals("app.circlet", headers["X-Android-Package"])
        assertEquals("A9993E364706816ABA3E25717850C26C9CD0D89D", headers["X-Android-Cert"])
    }
}
