package app.circlet.ui.journal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.circlet.core.CorrelationMetric
import app.circlet.core.JournalEntry
import app.circlet.core.JournalRepository
import app.circlet.core.JournalTag
import app.circlet.core.Outcome
import app.circlet.core.TagCorrelation
import app.circlet.ui.design.words
import java.time.LocalDate
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** The saved state of one day. [savedNote] is what the repository holds, not what is typed. */
data class JournalDay(val date: LocalDate, val tagIds: Set<String>, val savedNote: String)

/** Tags and the note for one day, with tag management. */
class JournalViewModel(private val journal: JournalRepository) : ViewModel() {
    val tags: StateFlow<List<JournalTag>> = journal.tags

    private val dayState = MutableStateFlow<JournalDay?>(null)

    /** Null while the day is loading. */
    val day: StateFlow<JournalDay?> = dayState.asStateFlow()

    /** The note as typed. It follows the saved note until the user edits it. */
    val noteDraft = MutableStateFlow("")

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    private var shownDate: LocalDate? = null
    private var dateJob: Job? = null
    private var noteEdited = false

    /** Follows the entry for [date]. Does nothing when that day is already shown. */
    fun open(date: LocalDate) {
        if (date == shownDate) return
        shownDate = date
        noteEdited = false
        dayState.value = null
        noteDraft.value = ""
        dateJob?.cancel()
        dateJob = viewModelScope.launch {
            journal.observeEntry(date).collect { entry: JournalEntry? ->
                val saved = JournalDay(date, entry?.tagIds.orEmpty(), entry?.note.orEmpty())
                dayState.value = saved
                if (!noteEdited) noteDraft.value = saved.savedNote
            }
        }
    }

    fun toggle(tagId: String) {
        val current = dayState.value ?: return
        save(JournalEntry(current.date, toggleTag(current.tagIds, tagId), current.savedNote))
    }

    fun onNoteChange(text: String) {
        noteEdited = true
        noteDraft.value = text
    }

    fun saveNote() {
        val current = dayState.value ?: return
        val note = noteDraft.value.trim()
        save(JournalEntry(current.date, current.tagIds, note)) { noteEdited = false }
    }

    fun addTag(name: String) {
        viewModelScope.launch {
            when (val result = journal.saveTag(JournalTag(newCustomTagId(), name.trim(), builtIn = false))) {
                is Outcome.Ok -> Unit
                is Outcome.Err -> messageChannel.send(result.error.words())
            }
        }
    }

    fun removeTag(tag: JournalTag) {
        if (!tag.canRemove()) return
        viewModelScope.launch {
            when (val result = journal.deleteTag(tag.id)) {
                is Outcome.Ok -> Unit
                is Outcome.Err -> messageChannel.send(result.error.words())
            }
        }
    }

    private fun save(entry: JournalEntry, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            when (val result = journal.saveEntry(entry)) {
                is Outcome.Ok -> onSaved()
                is Outcome.Err -> messageChannel.send(result.error.words())
            }
        }
    }
}

sealed interface InsightsState {
    data object Loading : InsightsState
    data class Failed(val message: String) : InsightsState
    data class Ready(val tags: List<JournalTag>, val correlations: List<TagCorrelation>) : InsightsState
}

/** Correlations for every tag against the chosen metric. */
class JournalInsightsViewModel(private val journal: JournalRepository) : ViewModel() {
    val metric = MutableStateFlow(CorrelationMetric.RECOVERY)

    private val state = MutableStateFlow<InsightsState>(InsightsState.Loading)
    val uiState: StateFlow<InsightsState> = state.asStateFlow()

    init {
        load()
    }

    fun load() {
        state.value = InsightsState.Loading
        viewModelScope.launch {
            state.value = when (val result = journal.correlations()) {
                is Outcome.Ok -> InsightsState.Ready(journal.tags.value, result.value)
                is Outcome.Err -> InsightsState.Failed(result.error.words())
            }
        }
    }

    fun select(metric: CorrelationMetric) {
        this.metric.value = metric
    }
}
