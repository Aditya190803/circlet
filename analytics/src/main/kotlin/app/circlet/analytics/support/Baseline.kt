package app.circlet.analytics.support

import app.circlet.analytics.foundations.RobustBaseline

// The definitions live in foundations. These names stay so the wellness and
// insight call sites do not grow a second baseline.

internal fun robustBaseline(window: List<Double>, minValid: Int = 3): RobustBaseline =
    app.circlet.analytics.foundations.robustBaseline(window, minValid)

internal fun dispersionBelowQuantum(base: List<Double>, quantum: Double): Boolean =
    app.circlet.analytics.foundations.dispersionBelowQuantum(base, quantum)

internal fun mdc(baseline: RobustBaseline, typicalError: Double? = null): Double? =
    app.circlet.analytics.foundations.mdc(baseline, typicalError)
