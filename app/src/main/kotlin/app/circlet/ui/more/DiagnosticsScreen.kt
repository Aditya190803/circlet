package app.circlet.ui.more

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import app.circlet.core.StrapController
import app.circlet.core.StrapState
import app.circlet.core.SyncCounters
import app.circlet.core.SyncDiagnostics
import app.circlet.core.SyncOutcome
import app.circlet.ui.LocalMessenger
import app.circlet.ui.day.DetailHeader
import app.circlet.ui.day.ScreenColumn
import app.circlet.ui.design.Card
import app.circlet.ui.design.Divider
import app.circlet.ui.design.Palette
import app.circlet.ui.design.SecondaryButton
import app.circlet.ui.design.Space
import app.circlet.ui.design.StatRow
import app.circlet.ui.design.agoWords
import app.circlet.ui.design.body
import app.circlet.ui.state.containerViewModel
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SHOWN_LINES = 100

/** Counters and the newest log lines, read off the main thread. */
class DiagnosticsViewModel(
    strap: StrapController,
    private val diagnostics: SyncDiagnostics,
) : ViewModel() {
    val strapState: StateFlow<StrapState> = strap.state
    val counters = MutableStateFlow(SyncCounters())
    val lines = MutableStateFlow<List<String>>(emptyList())

    suspend fun refresh() {
        val (newCounters, newLines) = withContext(Dispatchers.IO) {
            diagnostics.counters() to diagnostics.recentLines(SHOWN_LINES)
        }
        counters.value = newCounters
        lines.value = newLines
    }

    suspend fun exportText(): String = withContext(Dispatchers.IO) { diagnostics.exportText() }
}

/** How the last history sync ended, how often each ending has happened, and the log behind it. */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val vm = containerViewModel { c, _ -> DiagnosticsViewModel(c.strap, c.diagnostics) }
    val strap by vm.strapState.collectAsState()
    val counters by vm.counters.collectAsState()
    val lines by vm.lines.collectAsState()
    val context = LocalContext.current
    val messenger = LocalMessenger.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(strap.sync.lastOutcomeAt, strap.sync.draining) { vm.refresh() }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = vm.exportText()
            val saved = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } != null
                } catch (_: IOException) {
                    false
                }
            }
            messenger.show(if (saved) "Saved the sync log." else "Could not save the log there.")
        }
    }

    Column(Modifier.fillMaxSize()) {
        DetailHeader("SYNC DIAGNOSTICS", onBack = onBack)
        ScreenColumn {
            Card("LAST SYNC") {
                val outcome = strap.sync.lastOutcome
                val at = strap.sync.lastOutcomeAt
                StatRow(
                    "OUTCOME",
                    outcome?.words() ?: "NONE YET",
                    color = if (outcome == null || outcome == SyncOutcome.COMPLETED) Palette.White else Palette.Warn,
                    note = at?.let { agoWords(it, Instant.now()) },
                )
            }
            Card("COUNTS") {
                StatRow("COMPLETED", counters.completed.toString())
                Divider()
                StatRow("STALLED", counters.stalled.toString())
                Divider()
                StatRow("DISCONNECTED", counters.disconnected.toString())
                Divider()
                StatRow("ACK FAILED", counters.ackFailed.toString())
                Divider()
                StatRow("DECODE FAILED", counters.decodeFailed.toString())
                Divider()
                StatRow("SAMPLES RECEIVED", counters.samplesReceived.toString())
            }
            Card("LOG") {
                if (lines.isEmpty()) {
                    Text("Nothing logged yet.", style = body(14.sp, Palette.Dim))
                } else {
                    Text(
                        lines.joinToString("\n"),
                        style = body(10.sp, Palette.Dim).copy(fontFamily = FontFamily.Monospace),
                    )
                }
                Spacer(Modifier.height(Space.sm))
                SecondaryButton("SAVE LOG", enabled = lines.isNotEmpty()) { save.launch("circlet-sync-log.txt") }
            }
        }
    }
}

private fun SyncOutcome.words(): String = when (this) {
    SyncOutcome.COMPLETED -> "COMPLETED"
    SyncOutcome.STALLED -> "STALLED"
    SyncOutcome.DISCONNECTED -> "DISCONNECTED"
    SyncOutcome.ACK_FAILED -> "ACK FAILED"
    SyncOutcome.DECODE_FAILED -> "DECODE FAILED"
}
