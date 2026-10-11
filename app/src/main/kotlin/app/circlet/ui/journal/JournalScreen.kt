package app.circlet.ui.journal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.circlet.core.JournalTag
import app.circlet.ui.LocalMessenger
import app.circlet.ui.day.DetailHeader
import app.circlet.ui.day.ScreenColumn
import app.circlet.ui.design.Card
import app.circlet.ui.design.Chip
import app.circlet.ui.design.EmptyState
import app.circlet.ui.design.Glyph
import app.circlet.ui.design.IconButton
import app.circlet.ui.design.ListRow
import app.circlet.ui.design.LocalTimeFormat
import app.circlet.ui.design.Palette
import app.circlet.ui.design.PrimaryButton
import app.circlet.ui.design.QuietButton
import app.circlet.ui.design.SecondaryButton
import app.circlet.ui.design.Sheet
import app.circlet.ui.design.Skeleton
import app.circlet.ui.design.TextEntry
import app.circlet.ui.design.body
import app.circlet.ui.design.dateTitle
import app.circlet.ui.design.label
import app.circlet.ui.state.containerViewModel
import java.time.LocalDate

private val DateSaver = Saver<LocalDate, String>(save = { it.toString() }, restore = { LocalDate.parse(it) })

/** One day's tags, note and tag management. [date] is the day shown first; the user can step back from today. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JournalScreen(date: LocalDate, onBack: () -> Unit, onOpenInsights: () -> Unit) {
    val today = LocalDate.now(LocalTimeFormat.current.zone)
    var shown by rememberSaveable(date, stateSaver = DateSaver) { mutableStateOf(clampToToday(date, today)) }
    val vm = containerViewModel { container, _ -> JournalViewModel(container.journal) }
    val messenger = LocalMessenger.current

    LaunchedEffect(shown) { vm.open(shown) }
    LaunchedEffect(vm) { vm.messages.collect { messenger.show(it) } }

    val tags by vm.tags.collectAsState()
    val day by vm.day.collectAsState()
    val noteDraft by vm.noteDraft.collectAsState()
    var managing by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        DetailHeader("JOURNAL", onBack = onBack)
        ScreenColumn {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        glyph = Glyph.ChevronLeft,
                        description = "Previous day",
                        onClick = { shown = shiftDay(shown, -1, today) },
                    )
                    Text(
                        dateTitle(shown, today),
                        style = label(13.sp, Palette.White),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        glyph = Glyph.ChevronRight,
                        description = "Next day",
                        enabled = shown < today,
                        onClick = { shown = shiftDay(shown, 1, today) },
                    )
                }
            }

            val current = day
            if (current == null) {
                Skeleton()
                Skeleton()
            } else {
                Card("TAGS") {
                    if (tags.isEmpty()) {
                        EmptyState(Glyph.Journal, "NO TAGS YET", "Add a tag to log what shaped the day.")
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            tags.forEach { tag ->
                                Chip(
                                    text = tag.name,
                                    selected = tag.id in current.tagIds,
                                    onClick = { vm.toggle(tag.id) },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    QuietButton("MANAGE TAGS") { managing = true }
                }

                Card("NOTE") {
                    TextEntry(
                        value = noteDraft,
                        description = "Note for this day",
                        placeholder = "How did the day go?",
                        maxLength = MAX_NOTE,
                        onChange = vm::onNoteChange,
                    )
                    Spacer(Modifier.height(12.dp))
                    val dirty = noteDraft.trim() != current.savedNote
                    SecondaryButton("SAVE NOTE", enabled = dirty) { vm.saveNote() }
                }

                Card("INSIGHTS") {
                    ListRow(
                        title = "What moves your numbers",
                        glyph = Glyph.Trends,
                        subtitle = "How each tag lines up with recovery, HRV and sleep",
                        onClick = onOpenInsights,
                    )
                }
            }
        }
    }

    if (managing) {
        TagManager(
            tags = tags,
            onAdd = vm::addTag,
            onRemove = vm::removeTag,
            onDismiss = { managing = false },
        )
    }
}

@Composable
private fun TagManager(
    tags: List<JournalTag>,
    onAdd: (String) -> Unit,
    onRemove: (JournalTag) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    var pendingRemoval by remember { mutableStateOf<JournalTag?>(null) }

    Sheet(title = "TAGS", onDismiss = onDismiss) {
        tags.forEach { tag ->
            val removable = tag.canRemove()
            ListRow(
                title = tag.name,
                subtitle = if (tag.builtIn) "BUILT IN" else "CUSTOM",
                trailing = if (removable) {
                    @Composable { RemoveTagButton(tag) { pendingRemoval = tag } }
                } else {
                    null
                },
            )
        }

        val removing = pendingRemoval
        if (removing != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Remove ${removing.name}? It is taken off every day it is on.",
                style = body(14.sp, Palette.Dim),
            )
            Spacer(Modifier.height(10.dp))
            PrimaryButton("REMOVE TAG", danger = true) {
                onRemove(removing)
                pendingRemoval = null
            }
            QuietButton("KEEP TAG") { pendingRemoval = null }
        }

        Spacer(Modifier.height(16.dp))
        Text("ADD A TAG", style = label(12.sp, Palette.Dim))
        Spacer(Modifier.height(8.dp))
        TextEntry(
            value = newName,
            description = "New tag name",
            placeholder = "New tag",
            maxLength = MAX_TAG_NAME,
            onChange = { newName = it },
        )
        val problem = if (newName.isBlank()) null else tagNameProblem(newName, tags)
        if (problem != null) {
            Text(problem, style = body(12.sp, Palette.Warn), modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(10.dp))
        PrimaryButton("ADD TAG", enabled = newName.isNotBlank() && problem == null) {
            onAdd(newName)
            newName = ""
        }
    }
}

@Composable
private fun RemoveTagButton(tag: JournalTag, onRequest: () -> Unit) {
    IconButton(
        glyph = Glyph.Trash,
        description = "Remove ${tag.name}",
        tint = Palette.Danger,
        onClick = onRequest,
    )
}
