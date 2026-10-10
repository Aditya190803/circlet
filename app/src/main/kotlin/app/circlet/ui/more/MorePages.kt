package app.circlet.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.circlet.core.ClockStyle
import app.circlet.core.FoundStrap
import app.circlet.core.MassUnit
import app.circlet.core.Profile
import app.circlet.core.UserSettings
import app.circlet.ui.day.DetailHeader
import app.circlet.ui.day.ScreenColumn
import app.circlet.ui.design.Card
import app.circlet.ui.design.ErrorState
import app.circlet.ui.design.LocalTimeFormat
import app.circlet.ui.design.Palette
import app.circlet.ui.design.PrimaryButton
import app.circlet.ui.design.Segmented
import app.circlet.ui.design.Shapes
import app.circlet.ui.design.Skeleton
import app.circlet.ui.design.StatRow
import app.circlet.ui.design.body
import app.circlet.ui.state.PairingPhase
import app.circlet.ui.strap.PairingFix
import app.circlet.ui.strap.StrapFinder
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ProfileScreen(profile: Profile, unit: MassUnit, error: String?, onSave: (Profile, () -> Unit) -> Unit, onBack: () -> Unit) {
    val today = LocalDate.now(LocalTimeFormat.current.zone)
    val draft = rememberProfileDraft(profile, unit, today)
    var saved by remember { mutableStateOf(false) }
    val parsed = draft.toProfile(profile, unit, today)
    Column(Modifier.fillMaxSize().imePadding()) {
        DetailHeader("PROFILE", onBack = onBack)
        ScreenColumn {
            Card {
                ProfileFields(draft, unit, showMaxHeartRate = true) { saved = false }
            }
            Text(
                "These set your heart rate zones, calories and strain. They are stored on this phone only.",
                style = body(13.sp, Palette.Faint),
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            if (error != null) Text(error, style = body(13.sp, Palette.Warn), modifier = Modifier.padding(horizontal = 4.dp))
            PrimaryButton(if (saved) "SAVED" else "SAVE PROFILE", enabled = !saved && parsed != null) {
                parsed?.let { onSave(it) { saved = true } }
            }
        }
    }
}

@Composable
fun UnitsScreen(settings: UserSettings, onChange: (UserSettings) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        DetailHeader("UNITS", onBack = onBack)
        ScreenColumn {
            Card("WEIGHT") {
                val units = listOf(MassUnit.KILOGRAM, MassUnit.POUND)
                Segmented(listOf("KILOGRAMS", "POUNDS"), units.indexOf(settings.mass)) { onChange(settings.copy(mass = units[it])) }
            }
            Card("CLOCK") {
                val styles = listOf(ClockStyle.MATCH_SYSTEM, ClockStyle.H12, ClockStyle.H24)
                Segmented(listOf("MATCH PHONE", "12-HOUR", "24-HOUR"), styles.indexOf(settings.clock)) { onChange(settings.copy(clock = styles[it])) }
                Spacer(Modifier.height(10.dp))
                Text("Times across the app follow this, including charts.", style = body(13.sp, Palette.Faint))
            }
        }
    }
}

@Composable
fun PairScreen(
    phase: PairingPhase,
    scanning: Boolean,
    found: List<FoundStrap>,
    onScan: () -> Unit,
    onPair: (FoundStrap) -> Unit,
    onFix: (PairingFix) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        DetailHeader("PAIR A STRAP", onBack = onBack)
        ScreenColumn {
            Text(
                "Remove the strap from the official app and from Android Bluetooth settings first. Then keep it within arm's reach and pick it below.",
                style = body(15.sp),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
            Card { StrapFinder(phase, scanning, found, onScan, onPair, onFix) }
        }
    }
}

/** One `##` section of NOTICE.md: prose paragraphs and fenced licence text, in order. */
data class NoticeSection(val heading: String?, val blocks: List<NoticeBlock>)

data class NoticeBlock(val text: String, val fenced: Boolean)

/** Reads the small part of Markdown that NOTICE.md uses: `#` headings, paragraphs and fences. */
fun parseNotice(markdown: String): List<NoticeSection> {
    val sections = ArrayList<NoticeSection>()
    var heading: String? = null
    var blocks = ArrayList<NoticeBlock>()
    val pending = ArrayList<String>()
    var fenced = false
    fun flush() {
        if (pending.isNotEmpty()) blocks += NoticeBlock(pending.joinToString(if (fenced) "\n" else " ").trim('\n'), fenced)
        pending.clear()
    }
    fun close() {
        flush()
        if (heading != null || blocks.isNotEmpty()) sections += NoticeSection(heading, blocks)
        blocks = ArrayList()
    }
    for (line in markdown.lines()) {
        when {
            line.startsWith("```") -> {
                flush()
                fenced = !fenced
            }
            fenced -> pending += line
            line.startsWith("## ") -> {
                close()
                heading = line.removePrefix("## ").trim()
            }
            line.startsWith("# ") -> Unit
            line.isBlank() -> flush()
            else -> pending += line.trim()
        }
    }
    close()
    return sections
}

@Composable
fun AboutScreen(version: String, notice: String?, noticeMissing: Boolean, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        DetailHeader("ABOUT", onBack = onBack)
        ScreenColumn {
            Card("CIRCLET") {
                StatRow("VERSION", version)
                Spacer(Modifier.height(6.dp))
                Text(
                    "A local companion for the WHOOP 4.0 strap. It is not made by, or affiliated with, WHOOP.",
                    style = body(14.sp),
                )
            }
            Card("HOW SCORES ARE MADE") {
                Text(
                    "Sleep, recovery and strain are calculated on this phone from the heart rate, beat-to-beat and movement data your strap records. " +
                        "Each number carries a confidence mark, and a number that cannot be supported is left empty with the reason. " +
                        "They are independent estimates and not medical advice.",
                    style = body(14.sp),
                )
            }
            Card("PRIVACY") {
                Text(
                    "Circlet has no internet permission. Nothing leaves this phone unless you export it yourself.",
                    style = body(14.sp),
                )
            }
            when {
                noticeMissing -> ErrorState("The licence text is missing from this build.")
                notice == null -> Card("LICENCES") {
                    Skeleton(Modifier.fillMaxWidth().height(14.dp))
                    Spacer(Modifier.height(10.dp))
                    Skeleton(Modifier.fillMaxWidth(0.7f).height(14.dp))
                }
                else -> for (section in remember(notice) { parseNotice(notice) }) {
                    Card(section.heading?.uppercase() ?: "LICENCES") {
                        section.blocks.forEachIndexed { i, block ->
                            if (i > 0) Spacer(Modifier.height(10.dp))
                            if (block.fenced) {
                                Text(
                                    block.text,
                                    style = body(11.sp, Palette.Dim),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(Shapes.chip)
                                        .background(Palette.CardRaised)
                                        .padding(12.dp),
                                )
                            } else {
                                Text(block.text, style = body(14.sp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** NOTICE.md as packaged into the app's assets. Null while reading, [Result.failure] if absent. */
@Composable
fun rememberNotice(): Result<String>? {
    val context = LocalContext.current
    val notice by produceState<Result<String>?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open("NOTICE.md").bufferedReader().use { it.readText() } }
        }
    }
    return notice
}
