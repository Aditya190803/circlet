package app.circlet.ui.portability

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.circlet.core.CloudConfig
import app.circlet.core.CloudSyncController
import app.circlet.core.Outcome
import app.circlet.ui.design.Card
import app.circlet.ui.design.Glyph
import app.circlet.ui.design.Icon
import app.circlet.ui.design.Palette
import app.circlet.ui.design.PrimaryButton
import app.circlet.ui.design.QuietButton
import app.circlet.ui.design.SecondaryButton
import app.circlet.ui.design.Segmented
import app.circlet.ui.design.Shapes
import app.circlet.ui.design.Space
import app.circlet.ui.design.StatRow
import app.circlet.ui.design.agoWords
import app.circlet.ui.design.body
import app.circlet.ui.design.label
import app.circlet.ui.design.title
import java.time.Instant
import kotlinx.coroutines.launch

/** Offered once at start. Circlet works without it; More › Data can connect later. */
@Composable
fun CloudSetupScreen(cloud: CloudSyncController, onSkip: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.page),
    ) {
        Spacer(Modifier.height(40.dp))
        Box(
            Modifier.size(56.dp).clip(Shapes.pill).background(Palette.CardRaised),
            contentAlignment = Alignment.Center,
        ) { Icon(Glyph.Shield, Palette.Teal, 28.dp) }
        Spacer(Modifier.height(24.dp))
        Text("Keep a copy of\nyour history.", style = title(32.sp))
        Spacer(Modifier.height(12.dp))
        Text(
            "Everything stays on this phone. A compressed backup goes to your own account, so a new phone can pick up where this one left off.",
            style = body(16.sp),
        )
        Spacer(Modifier.height(20.dp))
        Benefit("Works offline; backs up when you are online")
        Benefit("Runs in the background, no need to open the app")
        Benefit("Only your account can read it")
        Spacer(Modifier.height(28.dp))
        SignInForm(cloud)
        Spacer(Modifier.height(20.dp))
        QuietButton("NOT NOW", onClick = onSkip)
        Text(
            "You can connect later in More › Data.",
            style = body(13.sp, Palette.Faint),
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(24.dp))
    }
}

/** The cloud card on the Data screen: sign in, or the backup's state once connected. */
@Composable
fun CloudAccountPanel(cloud: CloudSyncController) {
    val state by cloud.state.collectAsState()
    val scope = rememberCoroutineScope()
    var renewSignIn by remember { mutableStateOf(false) }
    Card("CLOUD BACKUP") {
        when {
            !state.configured -> ProjectForm(cloud)
            !state.signedIn || renewSignIn -> SignInForm(cloud, renewing = renewSignIn) { renewSignIn = false }
            else -> {
                Text("SIGNED IN AS", style = label(11.sp, Palette.Faint))
                Spacer(Modifier.height(4.dp))
                Text(state.email.orEmpty(), style = body(16.sp, Palette.White))
                Spacer(Modifier.height(6.dp))
                StatRow("STATUS", if (state.pending == 0) "UP TO DATE" else "${state.pending} WAITING")
                StatRow("LAST BACKUP", state.lastUpload?.let { agoWords(it, Instant.now()) } ?: "NOT YET")
                Spacer(Modifier.height(10.dp))
                Text(
                    "Your history stays on this phone. New readings back up on their own in the background.",
                    style = body(14.sp),
                )
                state.message?.let { Problem(it) }
                Spacer(Modifier.height(14.dp))
                SecondaryButton("BACK UP NOW", enabled = !state.busy) { scope.launch { cloud.syncNow() } }
                QuietButton("SIGN IN AGAIN", enabled = !state.busy) { renewSignIn = true }
            }
        }
    }
}

@Composable
private fun SignInForm(cloud: CloudSyncController, renewing: Boolean = false, onDone: () -> Unit = {}) {
    val state by cloud.state.collectAsState()
    val scope = rememberCoroutineScope()
    var mode by remember { mutableIntStateOf(0) }
    var email by remember { mutableStateOf(state.email.orEmpty()) }
    // Passwords are intentionally excluded from saved instance state and disk.
    var password by remember { mutableStateOf("") }
    val creating = mode == 1 && !renewing
    val valid = email.trim().contains('@') && if (creating) password.length >= 6 else password.isNotEmpty()

    if (!renewing) {
        Segmented(listOf("SIGN IN", "CREATE ACCOUNT"), mode, enabled = !state.busy) { mode = it }
        Spacer(Modifier.height(18.dp))
    }
    CloudInput("EMAIL", email, "you@example.com", enabled = !state.busy, keyboard = KeyboardType.Email, ime = ImeAction.Next) { email = it }
    Spacer(Modifier.height(14.dp))
    CloudInput(
        "PASSWORD", password, if (creating) "At least 6 characters" else "Your password",
        enabled = !state.busy, secret = true, ime = ImeAction.Done,
    ) { password = it }
    state.message?.let { Problem(it) }
    Spacer(Modifier.height(20.dp))
    PrimaryButton(
        if (creating) "CREATE ACCOUNT" else "SIGN IN",
        enabled = valid,
        loading = state.busy,
    ) {
        scope.launch {
            if (cloud.signIn(email, password, creating) is Outcome.Ok) {
                password = ""
                onDone()
            }
        }
    }
    if (!creating) {
        QuietButton("FORGOT PASSWORD?", enabled = !state.busy && email.contains('@')) {
            scope.launch { cloud.resetPassword(email) }
        }
    }
}

/** Only for a build without a bundled project: the user enters their own Firebase details. */
@Composable
private fun ProjectForm(cloud: CloudSyncController) {
    val state by cloud.state.collectAsState()
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    Text(
        "Use a free Firebase project with Email/Password sign-in and a Realtime Database, then deploy Circlet's database rules.",
        style = body(14.sp),
    )
    Spacer(Modifier.height(16.dp))
    CloudInput("DATABASE URL", url, "https://your-project-default-rtdb.firebaseio.com", enabled = !state.busy, keyboard = KeyboardType.Uri) { url = it }
    Spacer(Modifier.height(14.dp))
    CloudInput("API KEY", apiKey, "From Project settings", enabled = !state.busy, keyboard = KeyboardType.Ascii) { apiKey = it }
    state.message?.let { Problem(it) }
    Spacer(Modifier.height(20.dp))
    PrimaryButton("SAVE", enabled = url.isNotBlank() && apiKey.isNotBlank(), loading = state.busy) {
        scope.launch { if (cloud.configure(CloudConfig(url, apiKey)) is Outcome.Ok) apiKey = "" }
    }
}

@Composable
private fun Benefit(text: String) {
    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(Shapes.pill).background(Palette.Teal))
        Spacer(Modifier.width(12.dp))
        Text(text, style = body(14.sp, Palette.Dim))
    }
}

@Composable
private fun Problem(text: String) {
    Spacer(Modifier.height(12.dp))
    Text(text, style = body(13.sp, Palette.Danger))
}

/** A labelled entry box in the app's own style; [secret] adds a show/hide switch. */
@Composable
private fun CloudInput(
    name: String,
    value: String,
    placeholder: String,
    enabled: Boolean,
    secret: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    ime: ImeAction = ImeAction.Next,
    onChange: (String) -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    Text(name, style = label(11.sp, Palette.Faint))
    Spacer(Modifier.height(6.dp))
    BasicTextField(
        value = value,
        onValueChange = { if (it.length <= 4096) onChange(it) },
        enabled = enabled,
        singleLine = true,
        textStyle = body(16.sp, Palette.White),
        cursorBrush = SolidColor(Palette.Teal),
        visualTransformation = if (secret && !shown) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = if (secret) KeyboardType.Password else keyboard,
            imeAction = ime,
        ),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = name },
        decorationBox = { inner ->
            Row(
                Modifier
                    .heightIn(min = 52.dp)
                    .clip(Shapes.control)
                    .background(Palette.CardLine)
                    .padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
                    if (value.isEmpty()) Text(placeholder, style = body(16.sp, Palette.Ghost))
                    inner()
                }
                if (secret) {
                    Box(
                        Modifier
                            .heightIn(min = Space.touch)
                            .clip(Shapes.chip)
                            .clickable(role = Role.Button) { shown = !shown }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(if (shown) "HIDE" else "SHOW", style = label(11.sp, Palette.Dim)) }
                }
            }
        },
    )
}
