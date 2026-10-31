package app.circlet.ui.design

import androidx.compose.runtime.staticCompositionLocalOf
import app.circlet.core.AbsentReason
import app.circlet.core.Confidence
import app.circlet.core.CoreError
import app.circlet.core.SkinTemperature
import app.circlet.core.Sport
import app.circlet.core.StrapError
import app.circlet.core.SyncOutcome
import app.circlet.core.SyncProgress
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** How clock times are written, resolved once from the user's setting and the device. */
data class TimeFormat(val zone: ZoneId, val twentyFourHour: Boolean) {
    private val clock = DateTimeFormatter.ofPattern(if (twentyFourHour) "HH:mm" else "h:mm a")

    fun time(instant: Instant): String = clock.format(instant.atZone(zone)).uppercase()

    fun range(start: Instant, end: Instant): String = "${time(start)} – ${time(end)}"

    /** Five labels for a midnight-to-midnight axis. */
    val dayAxis: List<String>
        get() = if (twentyFourHour) listOf("00", "06", "12", "18", "24") else listOf("12AM", "6AM", "12PM", "6PM", "12AM")
}

val LocalTimeFormat = staticCompositionLocalOf { TimeFormat(ZoneId.systemDefault(), twentyFourHour = false) }

fun hoursMinutes(minutes: Int): String = "%d:%02d".format(minutes / 60, minutes % 60)

fun Duration.hoursMinutes(): String = hoursMinutes(toMinutes().toInt())

/** Sync progress in words: "42 MIN" or "1 H 20 MIN". */
fun minutesWords(minutes: Long): String {
    val hours = minutes / 60
    val rest = minutes % 60
    return if (hours > 0) "%d H %02d MIN".format(hours, rest) else "$rest MIN"
}

/** Shows committed and in-flight one-hertz samples so a live drain doesn't look idle before its first flush. */
fun syncProgressLabel(progress: SyncProgress): String {
    val received = (progress.samplesPulled + progress.samplesBuffered).coerceAtLeast(0)
    if (!progress.draining) return "SYNC NOW"
    return when {
        received == 0L -> "SYNCING · STARTING"
        received < 60L -> "SYNCING · $received SEC RECEIVED"
        else -> "SYNCING · ${minutesWords(received / 60)} RECEIVED"
    }
}

/** True when the last drain ended without finishing and none is running now. */
fun SyncProgress.stalled(): Boolean =
    !draining && (lastOutcome == SyncOutcome.STALLED || lastOutcome == SyncOutcome.DISCONNECTED)

/** How long ago [since] was, in words: "JUST NOW", "12 MIN AGO", "3 H AGO", "2 D AGO". */
fun agoWords(since: Instant, now: Instant): String {
    val minutes = Duration.between(since, now).toMinutes().coerceAtLeast(0)
    return when {
        minutes < 1 -> "JUST NOW"
        minutes < 60 -> "$minutes MIN AGO"
        minutes < 24 * 60 -> "${minutes / 60} H AGO"
        else -> "${minutes / (24 * 60)} D AGO"
    }
}

fun Double.decimal(digits: Int = 1): String = "%.${digits}f".format(this)

fun Int.grouped(): String = "%,d".format(this)

fun dateTitle(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "TODAY"
    today.minusDays(1) -> "YESTERDAY"
    else -> date.format(DateTimeFormatter.ofPattern("EEE, MMM d")).uppercase()
}

fun LocalDate.narrowDay(): String = dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault())

/** The plain sentence shown where a value would be. */
fun AbsentReason.words(): String = when (this) {
    AbsentReason.NOT_WORN -> "Strap was off your wrist"
    AbsentReason.NOT_ENOUGH_DATA -> "Not enough data yet"
    AbsentReason.CALIBRATING -> "Still learning your baseline"
    AbsentReason.STRAP_NOT_SYNCED -> "Waiting for the strap to sync"
    AbsentReason.SIGNAL_TOO_NOISY -> "Signal was too noisy to trust"
    AbsentReason.IMPLAUSIBLE -> "Reading was out of range, so it was left out"
    AbsentReason.NO_SLEEP -> "No sleep was found"
}

fun Confidence.words(): String = when (this) {
    Confidence.HIGH -> "High confidence"
    Confidence.MEDIUM -> "Medium confidence"
    Confidence.LOW -> "Low confidence"
}

fun StrapError.words(): String = when (this) {
    StrapError.BluetoothOff -> "Bluetooth is turned off. Turn it on and try again."
    StrapError.PermissionMissing -> "Circlet does not have the Nearby devices permission."
    StrapError.BondFailed -> "The strap refused to pair. Remove it from the official app and from Android Bluetooth settings, then try again."
    StrapError.LinkLost -> "The connection dropped. Keep the strap close and try again."
    StrapError.TimedOut -> "The strap did not answer in time. Bring it closer and try again."
    StrapError.AccountLocked ->
        "This strap is locked to a WHOOP account, so it will not share its data. Remove it from your devices in the official app, then pair it here again."
    is StrapError.Rejected -> "The strap turned that down: $detail"
}

fun CoreError.words(): String = when (this) {
    CoreError.NotPaired -> "No strap is paired."
    CoreError.Busy -> "The strap is busy. Try again in a moment."
    is CoreError.Invalid -> detail.replaceFirstChar { it.uppercase() }.trimEnd('.') + "."
    is CoreError.Strap -> error.words()
    is CoreError.Storage -> "Storage problem: $detail"
}

fun Sport.label(): String = name.replace('_', ' ')

fun Sport.glyph(): Glyph = when (this) {
    Sport.RUNNING -> Glyph.Run
    Sport.WALKING, Sport.RUCKING -> Glyph.Walk
    Sport.CYCLING -> Glyph.Cycle
    Sport.SWIMMING, Sport.ROWING, Sport.SURFING -> Glyph.Swim
    Sport.STRENGTH, Sport.HIIT, Sport.BOXING, Sport.MARTIAL_ARTS -> Glyph.Strength
    Sport.YOGA, Sport.PILATES, Sport.DANCE -> Glyph.Yoga
    Sport.BASKETBALL, Sport.SOCCER, Sport.TENNIS, Sport.GOLF -> Glyph.Ball
    Sport.HIKING, Sport.CLIMBING, Sport.SKIING, Sport.SNOWBOARDING, Sport.SKATING -> Glyph.Mountain
    Sport.ELLIPTICAL, Sport.STAIR_CLIMBER, Sport.JUMP_ROPE, Sport.OTHER -> Glyph.Bolt
}
