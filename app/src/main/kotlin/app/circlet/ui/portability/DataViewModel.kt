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

    fun restore(uri: String) {
        viewModelScope.launch {
            when (val result = data.restore(uri)) {
                is Outcome.Ok -> messageChannel.send("Data restored from the file.")
                is Outcome.Err -> messageChannel.send(result.error.words())
            }
        }
    }
}
