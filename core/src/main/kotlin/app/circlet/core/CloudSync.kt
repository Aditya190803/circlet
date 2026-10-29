package app.circlet.core

import java.net.URI
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class CloudConfig(val databaseUrl: String, val apiKey: String) {
    fun validated(): CloudConfig {
        val uri = try { URI(databaseUrl.trim().trimEnd('/')) } catch (_: java.net.URISyntaxException) {
            throw IllegalArgumentException("Enter a valid Firebase Realtime Database URL.")
        }
        require(uri.scheme == "https" && uri.port == -1 && uri.userInfo == null &&
            uri.query == null && uri.fragment == null && uri.path.isNullOrEmpty() &&
            uri.host?.let { it.endsWith(".firebaseio.com") || it.endsWith(".firebasedatabase.app") } == true
        ) { "Enter the HTTPS Realtime Database URL from your Firebase project." }
        require(apiKey.trim().matches(Regex("AIza[A-Za-z0-9_-]{35}"))) { "Enter your project's Firebase API key." }
        return CloudConfig(uri.toString(), apiKey.trim())
    }
}

data class CloudSyncState(
    val supported: Boolean = true,
    val configured: Boolean = false,
    val bundledProject: Boolean = false,
    val signedIn: Boolean = false,
    val email: String? = null,
    val busy: Boolean = false,
    val pending: Int = 0,
    val lastUpload: Instant? = null,
    val message: String? = null,
)

interface CloudSyncController {
    val state: StateFlow<CloudSyncState>
    suspend fun configure(config: CloudConfig): Outcome<Unit>
    suspend fun signIn(email: String, password: String, createAccount: Boolean): Outcome<Unit>
    suspend fun resetPassword(email: String): Outcome<Unit>
    suspend fun syncNow(): Outcome<Unit>
}

object NoCloudSync : CloudSyncController {
    override val state = MutableStateFlow(CloudSyncState(supported = false))
    override suspend fun configure(config: CloudConfig): Outcome<Unit> = Outcome.Err(CoreError.Invalid("Cloud is unavailable in the demo build."))
    override suspend fun signIn(email: String, password: String, createAccount: Boolean) = configure(CloudConfig("", ""))
    override suspend fun resetPassword(email: String) = configure(CloudConfig("", ""))
    override suspend fun syncNow() = configure(CloudConfig("", ""))
}
