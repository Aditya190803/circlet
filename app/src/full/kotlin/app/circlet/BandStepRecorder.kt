package app.circlet

import android.content.Context
import app.circlet.analytics.motion.bandPedometer
import app.circlet.core.BandStepDay
import app.circlet.core.BandSteps
import app.circlet.core.StrapController
import app.circlet.core.StrapEvent
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Counts steps from the band's live motion stream while the user has it turned on and the
 * app is on screen. Seconds of motion are joined into contiguous runs of at most a minute,
 * the run the detector was tuned on; a gap or a new day ends a run early. Each day keeps its
 * step count and the seconds streamed, so the screen can say how much of the day was seen.
 */
class BandStepRecorder(
    context: Context,
    private val strap: StrapController,
    private val zone: () -> ZoneId,
    scope: CoroutineScope,
) : BandSteps {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val enabledFlow = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    private val foreground = MutableStateFlow(false)
    private val daysFlow = MutableStateFlow(decode(prefs.getString(KEY_DAYS, null)))
    override val enabled: StateFlow<Boolean> = enabledFlow.asStateFlow()
    override val days: StateFlow<Map<LocalDate, BandStepDay>> = daysFlow.asStateFlow()

    private val lock = Mutex()
    private var run = DoubleArray(0)
    private var runRate = 0
    private var runDate: LocalDate? = null
    private var lastAt: Instant? = null

    init {
        scope.launch {
            combine(enabledFlow, foreground) { on, shown -> on && shown }.distinctUntilChanged().collect { stream ->
                if (!stream) lock.withLock { flush() }
                strap.setMotionStream(stream)
            }
        }
        scope.launch {
            strap.events.filterIsInstance<StrapEvent.Motion>().collect { lock.withLock { add(it) } }
        }
    }

    override fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        enabledFlow.value = enabled
    }

    override fun setForeground(foreground: Boolean) {
        this.foreground.value = foreground
    }

    private fun add(event: StrapEvent.Motion) {
        if (!enabledFlow.value || event.magnitudesG.isEmpty()) return
        val date = event.at.atZone(zone()).toLocalDate()
        val gap = lastAt?.let { Duration.between(it, event.at) }
        if (date != runDate || event.sampleRateHz != runRate || gap == null || gap > MAX_GAP || gap.isNegative) flush()
        runDate = date
        runRate = event.sampleRateHz
        lastAt = event.at
        run += event.magnitudesG
        if (run.size >= runRate * RUN_SECONDS) flush()
    }

    private fun flush() {
        val date = runDate
        if (date != null && run.isNotEmpty() && runRate > 0) {
            val steps = bandPedometer(run, runRate.toDouble())
            val seconds = run.size / runRate
            val before = daysFlow.value[date] ?: BandStepDay(0, 0)
            val updated = (daysFlow.value + (date to BandStepDay(before.steps + steps, before.streamedSeconds + seconds)))
                .toSortedMap().entries.toList().takeLast(KEEP_DAYS).associate { it.key to it.value }
            daysFlow.value = updated
            prefs.edit().putString(KEY_DAYS, encode(updated)).apply()
        }
        run = DoubleArray(0)
        lastAt = null
    }

    private companion object {
        const val PREFS = "band_steps"
        const val KEY_ENABLED = "enabled"
        const val KEY_DAYS = "days"
        const val RUN_SECONDS = 60
        const val KEEP_DAYS = 90
        val MAX_GAP: Duration = Duration.ofSeconds(3)

        fun encode(days: Map<LocalDate, BandStepDay>): String =
            days.entries.joinToString(";") { (date, day) -> "$date=${day.steps},${day.streamedSeconds}" }

        fun decode(text: String?): Map<LocalDate, BandStepDay> =
            text.orEmpty().split(';').mapNotNull { entry ->
                val (date, values) = entry.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
                val (steps, seconds) = values.split(',').mapNotNull(String::toIntOrNull).takeIf { it.size == 2 } ?: return@mapNotNull null
                runCatching { LocalDate.parse(date) }.getOrNull()?.let { it to BandStepDay(steps, seconds) }
            }.toMap()
    }
}
