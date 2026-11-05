package app.circlet.data.cloud

import android.content.Context
import app.circlet.core.CloudConfig
import app.circlet.core.CloudSyncController
import app.circlet.core.CloudSyncState
import app.circlet.core.CoreError
import app.circlet.core.Outcome
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

object FirebaseCloud {
    @Volatile private var instance: FirebaseCloudSession? = null
    fun get(context: Context): CloudSyncController = session(context)
    internal fun session(context: Context): FirebaseCloudSession = instance ?: synchronized(this) {
        instance ?: FirebaseCloudSession(context.applicationContext).also { instance = it }
    }
}

internal class FirebaseCloudSession(
    val context: Context,
    private val scheduleUpload: () -> Unit = { CloudUploadWork.enqueue(context) },
) : CloudSyncController {
    internal val preferences = context.getSharedPreferences("personal-firebase", Context.MODE_PRIVATE)
    internal val outbox = CloudOutbox(context)
    private val secrets = CloudSecrets(context)
    private val http = AndroidFirebaseHttp(context)
    private val gate = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var idToken: String? = null
    @Volatile private var tokenExpiry = 0L
    @Volatile internal var mirror: CloudMirror? = null
    private var workerStarted = false
    private val bundledProject = BundledFirebaseProject.install(preferences, BundledFirebaseProject.read(context), outbox.count() > 0)

    internal val uid: String? get() = preferences.getString("uid", null)
    override val state = MutableStateFlow(CloudSyncState(
        configured = preferences.contains("url"),
        bundledProject = bundledProject,
        signedIn = preferences.getBoolean("session_saved", false),
        email = preferences.getString("email", null),
        lastUpload = preferences.getLong("last_upload", 0).takeIf { it > 0 }?.let(Instant::ofEpochMilli),
    ))

    internal fun config(): CloudConfig = CloudConfig(
        preferences.getString("url", null) ?: throw CloudFailure("Add your Firebase project details first."),
        preferences.getString("api_key", null) ?: throw CloudFailure("Add your Firebase API key first."),
    ).validated()

    override suspend fun configure(config: CloudConfig): Outcome<Unit> = action {
        val valid = config.validated()
        check(!state.value.bundledProject) { "This APK already contains your Firebase project configuration." }
        check(!state.value.signedIn) { "This app is already connected to a Firebase account." }
        check(!preferences.contains("url") || outbox.count() == 0 || valid == this.config()) {
            "Unsent changes belong to the existing Firebase project. Keep that project until they upload."
        }
        check(preferences.edit().putString("url", valid.databaseUrl).putString("api_key", valid.apiKey).commit())
        state.update { it.copy(configured = true) }
    }

    override suspend fun signIn(email: String, password: String, createAccount: Boolean): Outcome<Unit> = action {
        require(email.trim().contains('@') && email.length < 255) { "Enter your email address." }
        require(password.length in 6..4096) { "Use a password with at least six characters." }
        val operation = if (createAccount) "signUp" else "signInWithPassword"
        val request = JSONObject().put("email", email.trim()).put("password", password).put("returnSecureToken", true)
        val auth = authRequest(operation, request)
        val userId = auth.getString("localId")
        check(uid == null || uid == userId) { "Sign in to the original account to keep this phone's queued data separate." }
        secrets.write(auth.getString("refreshToken"))
        check(preferences.edit().putString("uid", userId).putString("email", email.trim()).putBoolean("session_saved", true).commit())
        idToken = auth.getString("idToken")
        tokenExpiry = System.currentTimeMillis() + auth.getLong("expiresIn") * 1000 - 60_000
        state.update { it.copy(signedIn = true, email = email.trim()) }
        mirror?.activate()
        CloudUploadWork.schedule(context)
        scheduleUpload()
    }

    override suspend fun resetPassword(email: String): Outcome<Unit> = action {
        require(email.trim().contains('@')) { "Enter your email address first." }
        authRequest("sendOobCode", JSONObject().put("requestType", "PASSWORD_RESET").put("email", email.trim()))
        state.update { it.copy(message = "If that account exists, Firebase will send a password reset email.") }
    }

    private fun authRequest(operation: String, body: JSONObject): JSONObject {
        val result = http.request("https://identitytoolkit.googleapis.com/v1/accounts:$operation?key=${urlEncode(config().apiKey)}", "POST", body.toString())
        if (result.status !in 200..299) throw CloudFailure("Firebase sign-in failed. Check your email and password, and enable Email/Password in Firebase Authentication.")
        return JSONObject(result.body)
    }

    @Synchronized private fun token(force: Boolean): String {
        val existing = idToken
        if (!force && existing != null && System.currentTimeMillis() < tokenExpiry) return existing
        val refresh = secrets.read() ?: throw CloudFailure("Sign in to your Firebase account.")
        val result = http.request("https://securetoken.googleapis.com/v1/token?key=${urlEncode(config().apiKey)}", "POST",
            "grant_type=refresh_token&refresh_token=${urlEncode(refresh)}")
        if (result.status !in 200..299) throw CloudFailure("Firebase could not renew your session. Check connectivity or sign in again.")
        val json = JSONObject(result.body)
        check(json.getString("user_id") == uid) { "Firebase returned a different account." }
        secrets.write(json.getString("refresh_token"))
        tokenExpiry = System.currentTimeMillis() + json.getLong("expires_in") * 1000 - 60_000
        return json.getString("id_token").also { idToken = it }
    }

    internal fun remote(): CloudRemote = FirebaseRest(config(), uid ?: throw CloudFailure("Sign in first."), ::token, http)

    /** Rows and sample hours not yet confirmed by Firebase. */
    internal fun pendingCount(): Int = outbox.count() + (mirror?.pendingHours() ?: 0)

    internal fun queued() {
        val wasEmpty = state.value.pending == 0
        val pending = pendingCount()
        state.update { it.copy(pending = pending) }
        wake.trySend(Unit)
        if (state.value.signedIn && pending > 0 && wasEmpty) scheduleUpload()
    }

    internal fun problem(error: Exception) {
        val message = if (error is CloudFailure) error.message else "Cloud is unavailable. Unsent changes remain on this phone until Firebase confirms them."
        state.update { it.copy(message = message, busy = false, pending = pendingCount()) }
    }

    @Synchronized internal fun startWorker() {
        if (workerStarted) return
        workerStarted = true
        CloudUploadWork.schedule(context)
        if (pendingCount() > 0) scheduleUpload()
        scope.launch {
            while (isActive) {
                withTimeoutOrNull(15_000) { wake.receive() }
                delay(1_000)
                if (state.value.signedIn && (pendingCount() > 0 || mirror?.needsRestore == true)) syncNow()
            }
        }
        queued()
    }

    override suspend fun syncNow(): Outcome<Unit> = action {
        if (mirror?.retryRestore() == true) mirror?.onReload?.invoke()
        uploadBatches(remote())
    }

    /** Workers upload the durable queue; sample hours need the database, which the app process opens at start. */
    internal suspend fun uploadPending(remote: CloudRemote = remote(), maxPages: Int = 4): Outcome<Unit> = action {
        uploadBatches(remote, maxPages)
    }

    /** Small rows first, then changed sample hours, oldest first. Each page is confirmed before the next. */
    private suspend fun uploadBatches(remote: CloudRemote, maxPages: Int = Int.MAX_VALUE) {
        var pages = 0
        while (pages < maxPages) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val page = outbox.page()
            if (page.isEmpty()) break
            remote.upload(page.map { it.row })
            outbox.acknowledge(page)
            uploaded()
            pages++
        }
        while (pages < maxPages) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if ((mirror?.uploadHours(remote) ?: 0) == 0) break
            uploaded()
            pages++
        }
    }

    private fun uploaded() {
        state.update { it.copy(pending = pendingCount(), lastUpload = Instant.now()) }
        check(preferences.edit().putLong("last_upload", System.currentTimeMillis()).commit())
    }

    private suspend fun action(block: suspend () -> Unit): Outcome<Unit> = withContext(Dispatchers.IO) {
        gate.withLock {
            state.update { it.copy(busy = true, message = null) }
            try {
                block()
                Outcome.Ok(Unit)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (invalid: IllegalArgumentException) {
                state.update { it.copy(message = invalid.message) }
                Outcome.Err(CoreError.Invalid(invalid.message ?: "Check the entered details."))
            } catch (failed: Exception) {
                problem(failed)
                Outcome.Err(CoreError.Storage(state.value.message ?: "Cloud request failed."))
            } finally { state.update { it.copy(busy = false) } }
        }
    }
}
