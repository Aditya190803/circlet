package app.circlet.core

/**
 * A closed numeric band in the same unit as the metric it describes.
 * [low] and [high] are both inclusive.
 */
data class ReferenceRange(
    val low: Double,
    val high: Double,
)

/**
 * What to draw next to one headline metric.
 *
 * [personal] is this user's own normal band over the trailing window. It is null
 * until there is enough history. [personalDays] is how many days that band is
 * built from, including when [personal] is still null.
 * [ideal] is a published population range for the user's age and sex where one
 * exists, and null where there is no defensible published range.
 * [idealLabel] is a short human label such as "Good for men 30-39".
 * [idealSource] is the citation for [ideal], or for [idealLabel] when the label
 * is a placement against a published table rather than a closed band.
 * [higherIsBetter] is null when neither direction is better.
 */
data class MetricReference(
    val kind: MetricKind,
    val personal: ReferenceRange?,
    val personalDays: Int,
    val ideal: ReferenceRange?,
    val idealLabel: String?,
    val idealSource: String?,
    val higherIsBetter: Boolean?,
)
