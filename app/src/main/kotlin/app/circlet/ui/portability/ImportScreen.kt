package app.circlet.ui.portability

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import app.circlet.ui.day.DetailHeader
import app.circlet.ui.day.ScreenColumn
import app.circlet.ui.design.Card
import app.circlet.ui.design.Palette
import app.circlet.ui.design.SecondaryButton
import app.circlet.ui.design.Space
import app.circlet.ui.design.body
import app.circlet.ui.state.containerViewModel

/** Adds the days from a WHOOP data export to this phone. Reached from More, not from onboarding. */
@Composable
fun ImportScreen(onBack: () -> Unit) {
    val vm = containerViewModel { container, _ -> DataViewModel(container.portability) }
    val importing by vm.importing.collectAsState()
    val result by vm.importResult.collectAsState()

    val pickWhoopExport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.importWhoop(uri.toString())
    }

    Column(Modifier.fillMaxSize()) {
        DetailHeader("IMPORT WHOOP HISTORY", onBack = onBack)
        ScreenColumn {
            Card("WHOOP EXPORT") {
                Text(
                    "Bring in your past days from the official app, so trends and your baselines start with that history.",
                    style = body(14.sp, Palette.Dim),
                )
                Spacer(Modifier.height(Space.sm))
                Text(
                    "In the WHOOP app, open More, App Settings, Data Export, and choose the zip it sends you. " +
                        "Days this phone has already recorded from the strap are kept.",
                    style = body(14.sp, Palette.Dim),
                )
                Spacer(Modifier.height(Space.md))
                if (importing) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = Palette.Teal,
                        trackColor = Palette.Track,
                    )
                    Spacer(Modifier.height(Space.sm))
                }
                SecondaryButton(
                    if (importing) "IMPORTING" else "CHOOSE THE EXPORT FILE",
                    enabled = !importing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    pickWhoopExport.launch(arrayOf("application/zip", "text/csv", "text/comma-separated-values", "application/octet-stream", "*/*"))
                }
                result?.let {
                    Spacer(Modifier.height(Space.sm))
                    Text(it, style = body(14.sp, Palette.White))
                }
            }
        }
    }
}
