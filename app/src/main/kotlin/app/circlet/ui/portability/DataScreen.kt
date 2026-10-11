package app.circlet.ui.portability

import android.content.Context
import android.content.Intent
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.circlet.core.ExportResult
import app.circlet.core.HealthKind
import app.circlet.ui.LocalMessenger
import app.circlet.ui.day.DetailHeader
import app.circlet.ui.day.ScreenColumn
import app.circlet.ui.design.Card
import app.circlet.ui.design.ListRow
import app.circlet.ui.design.LocalTimeFormat
import app.circlet.ui.design.Palette
import app.circlet.ui.design.PrimaryButton
import app.circlet.ui.design.QuietButton
import app.circlet.ui.design.SecondaryButton
import app.circlet.ui.design.Segmented
import app.circlet.ui.design.TimeFormat
import app.circlet.ui.design.Toggle
import app.circlet.ui.design.body
import app.circlet.ui.design.dateTitle
import app.circlet.ui.design.label
import app.circlet.ui.state.containerViewModel
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Health Connect, export, backup and restore, with the privacy line that applies to all of them. */
@Composable
fun DataScreen(onBack: () -> Unit) {
    val vm = containerViewModel { container, _ -> DataViewModel(container.portability) }
    val messenger = LocalMessenger.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val timeFormat = LocalTimeFormat.current

    LaunchedEffect(vm) { vm.messages.collect { messenger.show(it) } }

    val health by vm.healthConnect.collectAsState()
    val backup by vm.backup.collectAsState()
    val export by vm.export.collectAsState()
    val exporting by vm.exporting.collectAsState()
    var pendingRestore by rememberSaveable { mutableStateOf<String?>(null) }

    val saveCopy = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        val source = export
        if (source == null || uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val copied = withContext(Dispatchers.IO) { copyDocument(context, source.uri, uri) }
            messenger.show(if (copied) "Saved ${source.displayName}." else "Could not save the export there.")
        }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            messenger.show("Could not keep access to that folder. Choose it again.")
            return@rememberLauncherForActivityResult
        }
        vm.setBackupFolder(uri.toString())
    }
    val pickRestore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) pendingRestore = uri.toString()
    }
    val pickWhoopExport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.importWhoop(uri.toString())
    }
    val importing by vm.importing.collectAsState()

    Column(Modifier.fillMaxSize()) {
        DetailHeader("DATA", onBack = onBack)
        ScreenColumn {
            Card("HEALTH CONNECT") {
                Text(
                    when {
                        !health.available -> "Health Connect is not installed on this phone."
                        health.enabled -> "Circlet writes measured values to Health Connect."
                        else -> "Off. Circlet does not write to Health Connect."
                    },
                    style = body(14.sp, Palette.Dim),
                )
                Spacer(Modifier.height(8.dp))
                Toggle(
                    checked = health.enabled,
                    description = "Write measured values to Health Connect",
                    enabled = health.available,
                ) { vm.setHealthConnect(it) }
                Spacer(Modifier.height(8.dp))
                Text("WRITTEN", style = label(12.sp, Palette.Dim))
                Text(
                    if (health.granted.isEmpty()) {
                        "NOTHING YET"
                    } else {
                        health.granted.sortedBy { it.ordinal }.joinToString(", ") { it.words() }
                    },
                    style = label(13.sp, Palette.White),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Only measured values are exported, never scores.",
                    style = body(12.sp, Palette.Faint),
                )
            }

            Card("EXPORT") {
                Text(
                    "Makes one file with everything in Circlet. The file is made on this phone and " +
                        "leaves it only when you save a copy.",
                    style = body(14.sp, Palette.Dim),
                )
                Spacer(Modifier.height(10.dp))
                val made = export
                if (made != null) {
                    ListRow(
                        title = made.displayName,
                        subtitle = "${"%,d".format(maxOf(1L, made.byteCount / 1024))} KB",
                    )
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton("SAVE A COPY") { saveCopy.launch(made.displayName) }
                    Spacer(Modifier.height(8.dp))
                }
                SecondaryButton("CREATE EXPORT", enabled = !exporting) { vm.export() }
            }

            Card("BACKUP") {
                Toggle(
                    checked = backup.enabled,
                    description = "Scheduled backup to a folder",
                ) { vm.setBackupEnabled(it) }
                Spacer(Modifier.height(8.dp))
                ListRow(
                    title = "Folder",
                    value = if (backup.folderUri == null) "NOT CHOSEN" else "CHOSEN",
                    onClick = { pickFolder.launch(null) },
                )
                Spacer(Modifier.height(8.dp))
                Text("HOW OFTEN", style = label(12.sp, Palette.Dim))
                Spacer(Modifier.height(6.dp))
                Segmented(
                    options = BACKUP_INTERVAL_LABELS,
                    selected = BACKUP_INTERVALS.indexOf(backup.interval),
                    onSelect = { vm.setBackupInterval(BACKUP_INTERVALS[it]) },
                )
                Spacer(Modifier.height(8.dp))
                ListRow(
                    title = "Last backup",
                    value = lastBackupText(backup.lastBackup, timeFormat),
                )
            }

            Card("IMPORT WHOOP HISTORY") {
                Text(
                    "Bring in your past days from the official app, so trends and your baselines start with that history. " +
                        "In the WHOOP app, open More, App Settings, Data Export, and choose the zip it sends you. " +
                        "Days this phone has already recorded from the strap are kept.",
                    style = body(14.sp, Palette.Dim),
                )
                Spacer(Modifier.height(10.dp))
                SecondaryButton(if (importing) "IMPORTING" else "CHOOSE THE EXPORT FILE", enabled = !importing) {
                    pickWhoopExport.launch(arrayOf("application/zip", "text/csv", "text/comma-separated-values", "application/octet-stream", "*/*"))
                }
            }

            Card("RESTORE") {
                val pending = pendingRestore
                if (pending == null) {
                    Text(
                        "Restore a file made by Circlet's export or backup.",
                        style = body(14.sp, Palette.Dim),
                    )
                    Spacer(Modifier.height(10.dp))
                    SecondaryButton("CHOOSE A FILE") { pickRestore.launch(arrayOf("*/*")) }
                } else {
                    Text(
                        "Restoring replaces all data on this phone with the data in the file. " +
                            "Data on this phone that is not in the file will be lost.",
                        style = body(14.sp, Palette.Dim),
                    )
                    Spacer(Modifier.height(10.dp))
                    PrimaryButton("REPLACE DATA ON THIS PHONE", danger = true) {
                        vm.restore(pending)
                        pendingRestore = null
                    }
                    Spacer(Modifier.height(4.dp))
                    QuietButton("CANCEL") { pendingRestore = null }
                }
            }

            Card("PRIVACY") {
                Text(
                    "Nothing leaves this phone unless you export it. Circlet has no internet permission.",
                    style = body(13.sp, Palette.Faint),
                )
            }
        }
    }
}

private fun HealthKind.words(): String = name.replace('_', ' ')

private fun lastBackupText(instant: Instant?, format: TimeFormat): String {
    if (instant == null) return "NEVER"
    val day = instant.atZone(format.zone).toLocalDate()
    return dateTitle(day, LocalDate.now(format.zone)).uppercase() + " " + format.time(instant)
}

/** Copies the exported file to a location the user chose. Returns false when either side cannot be opened. */
private fun copyDocument(context: Context, from: String, to: Uri): Boolean {
    val resolver = context.contentResolver
    return try {
        val input = resolver.openInputStream(Uri.parse(from)) ?: return false
        input.use { source ->
            val output = resolver.openOutputStream(to) ?: return false
            output.use { sink -> source.copyTo(sink) }
        }
        true
    } catch (e: IOException) {
        false
    }
}
