package app.circlet.ui.portability

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.circlet.core.BackupSettings
import app.circlet.core.DataPortability
import app.circlet.core.ExportResult
import app.circlet.core.HealthConnectStatus
import app.circlet.core.Outcome
import app.circlet.ui.design.words
import java.time.Duration
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Health Connect, export, backup and restore. Results and problems go out through [messages]. */
class DataViewModel(private val data: DataPortability) : ViewModel() {
    val healthConnect: StateFlow<HealthConnectStatus> = data.healthConnect
    val backup: StateFlow<BackupSettings> = data.backup

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    private val exportState = MutableStateFlow<ExportResult?>(null)

    /** The last export made in this session, or null before the first one. */
    val export: StateFlow<ExportResult?> = exportState.asStateFlow()

    private val exportingState = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = exportingState.asStateFlow()
    private val importingState = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = importingState.asStateFlow()

    private val importResultState = MutableStateFlow<String?>(null)

    /** The line describing the last WHOOP import, or null before one has finished. */
    val importResult: StateFlow<String?> = importResultState.asStateFlow()

    fun setHealthConnect(enabled: Boolean) {
        viewModelScope.launch {
            val result = data.setHealthConnectEnabled(enabled)
            if (result is Outcome.Err) messageChannel.send(result.error.words())
        }
    }

    fun setBackupEnabled(enabled: Boolean) = updateBackup(backup.value.copy(enabled = enabled))

    fun setBackupInterval(interval: Duration) = updateBackup(backup.value.copy(interval = interval))

    fun setBackupFolder(uri: String) = updateBackup(backup.value.copy(folderUri = uri))

    /** Checks the new settings first, so an invalid change never reaches the core. */
    private fun updateBackup(next: BackupSettings) {
        val problem = validateBackup(next)
        if (problem != null) {
            messageChannel.trySend(problem.words())
            return
        }
        viewModelScope.launch {
            val result = data.updateBackup(next)
            if (result is Outcome.Err) messageChannel.send(result.error.words())
        }
    }

    /** Makes a whole-database export. Ignored while one is already running. */
    fun export() {
        if (exportingState.value) return
        exportingState.value = true
        viewModelScope.launch {
            try {
                when (val result = data.exportDatabase()) {
                    is Outcome.Ok -> exportState.value = result.value
                    is Outcome.Err -> messageChannel.send(result.error.words())
                }
            } finally {
                exportingState.value = false
            }
        }
    }

    /** Writes the CSV zip to [uri] and reports the outcome. */
    fun exportCsv(uri: String) {
        viewModelScope.launch {
            when (val result = data.exportCsv(uri)) {
                is Outcome.Ok -> messageChannel.send("CSV export saved.")
                is Outcome.Err -> messageChannel.send(result.error.words())
            }
        }
    }

    /** Adds the days from a WHOOP data export. Ignored while an import is already running. */
    fun importWhoop(uri: String) {
        if (importingState.value) return
        importingState.value = true
        importResultState.value = null
        viewModelScope.launch {
            try {
                when (val result = data.importWhoopExport(uri)) {
                    is Outcome.Ok -> {
                        val r = result.value
                        val kept = if (r.skipped > 0) " ${r.skipped} days already on this phone were kept." else ""
                        importResultState.value = "Imported ${r.imported} days, ${r.firstDay} to ${r.lastDay}.$kept"
                    }
                    is Outcome.Err -> importResultState.value = result.error.words()
                }
            } finally {
                importingState.value = false
            }
        }
    }

    fun restore(uri: String) {
        viewModelScope.launch {
            when (val result = data.restore(uri)) {
                is Outcome.Ok -> messageChannel.send("Data restored from the file.")
                is Outcome.Err -> messageChannel.send(result.error.words())
            }
        }
    }
}
