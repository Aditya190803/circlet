package app.circlet.analytics.workout

import app.circlet.analytics.support.Scored
import app.circlet.analytics.support.calendarDays
import app.circlet.analytics.support.mdc
import app.circlet.analytics.support.median
import app.circlet.analytics.support.robustBaseline

// Ported from OpenStrap lib/src/onehz/human/session_cost.dart.
// The next-morning move of one metric after a single session type, against
// that morning's own robust baseline. A day with more than one session is
// dropped. A type with fewer than 10 mornings is refused.

internal const val SESSION_COST_MIN_SESSIONS = 10

internal data class SessionMorningEffect(
    val sessionType: String,
    val metric: String,
    val n: Int,
    val medianDelta: Double,
    val mdc: Double?,
) {
    val exceedsMdc: Boolean get() = mdc != null && kotlin.math.abs(medianDelta) >= mdc
}

internal fun sessionMorningEffects(
    dates: List<String>,
    values: List<Double?>,
    metric: String,
    sessionTypesByDate: Map<String, List<String>>,
    coverage: List<Double?>? = null,
    minSessions: Int = SESSION_COST_MIN_SESSIONS,
    baselineDays: Int = 28,
    minBaseline: Int = 14,
    minCoverage: Double = 0.5,
): Scored<List<SessionMorningEffect>> {
    if (values.size != dates.size || (coverage != null && coverage.size != dates.size)) {
        return Scored.absent("dates/values/coverage must be index-aligned daily series")
    }
    val deltasByType = LinkedHashMap<String, MutableList<Double>>()
    val mdcsByType = LinkedHashMap<String, MutableList<Double>>()
    val day = calendarDays(dates)
    for (i in 0 until dates.size - 1) {
        val types = sessionTypesByDate[dates[i]]
        if (types == null || types.size != 1) continue
        val morning = i + 1
        if (day[morning] != day[i] + 1) continue
        val v = values[morning] ?: continue
        if (coverage != null) {
            val c = coverage[morning]
            if (c == null || c < minCoverage) continue
        }
        val window = ArrayList<Double>()
        var k = morning - 1
        while (k >= 0 && day[morning] - day[k] <= baselineDays) {
            values[k]?.let { window.add(it) }
            k--
        }
        if (window.size < minBaseline) continue
        val base = robustBaseline(window, minBaseline)
        val centre = base.center ?: continue
        val gate = mdc(base)
        deltasByType.getOrPut(types.single()) { ArrayList() }.add(v - centre)
        if (gate != null) mdcsByType.getOrPut(types.single()) { ArrayList() }.add(gate)
    }
    val out = ArrayList<SessionMorningEffect>()
    for ((type, deltas) in deltasByType) {
        if (deltas.size < minSessions) continue
        out.add(
            SessionMorningEffect(
                sessionType = type,
                metric = metric,
                n = deltas.size,
                medianDelta = median(deltas)!!,
                mdc = median(mdcsByType[type] ?: emptyList()),
            ),
        )
    }
    out.sortBy { it.sessionType }
    val note = "Your own history, per session type. ASSOCIATION ONLY."
    if (out.isEmpty()) return Scored.absent("need_sessions:need=$minSessions per type. $note")
    return Scored.of(out, 0.4, note)
}
