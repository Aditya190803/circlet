package app.circlet.core

import java.time.Instant
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One GPS fix. [accuracyM] is the horizontal accuracy radius in metres. */
data class RoutePoint(
    val epochMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Double,
)

/** How a sport moves, which sets the fastest speed a believable fix can imply. */
enum class RouteKind(val maxSpeedMps: Double) {
    FOOT(12.0),
    CYCLING(30.0),
}

/** The route kind of an outdoor sport that can be tracked, or null for any other sport. */
fun Sport.routeKind(): RouteKind? = when (this) {
    Sport.RUNNING, Sport.WALKING, Sport.HIKING, Sport.RUCKING -> RouteKind.FOOT
    Sport.CYCLING -> RouteKind.CYCLING
    else -> null
}

object Route {
    const val MAX_ACCURACY_M = 25.0
    private const val EARTH_RADIUS_M = 6_371_008.8
    private const val MIN_DISTANCE_FOR_PACE_M = 10.0

    /** Great-circle distance in metres. */
    fun haversine(a: RoutePoint, b: RoutePoint): Double {
        val p1 = Math.toRadians(a.lat)
        val p2 = Math.toRadians(b.lat)
        val dp = p2 - p1
        val dl = Math.toRadians(b.lon - a.lon)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /**
     * Drops fixes that are too coarse or that imply a speed above the [kind] limit
     * from the last fix kept. Fixes that do not move forward in time are dropped.
     */
    fun filter(points: List<RoutePoint>, kind: RouteKind): List<RoutePoint> {
        val kept = ArrayList<RoutePoint>(points.size)
        for (point in points) {
            if (point.accuracyM > MAX_ACCURACY_M) continue
            val last = kept.lastOrNull()
            if (last != null) {
                val seconds = (point.epochMs - last.epochMs) / 1000.0
                if (seconds <= 0.0 || haversine(last, point) / seconds > kind.maxSpeedMps) continue
            }
            kept += point
        }
        return kept
    }

    /** Metres along [points], which are taken as already filtered. */
    fun distanceM(points: List<RoutePoint>): Double =
        points.zipWithNext().sumOf { (a, b) -> haversine(a, b) }

    /** Minutes per kilometre, or null when the distance is too short to mean anything. */
    fun paceMinPerKm(distanceM: Double, seconds: Long): Double? {
        if (distanceM < MIN_DISTANCE_FOR_PACE_M || seconds <= 0) return null
        return seconds / 60.0 / (distanceM / 1000.0)
    }

    /** Kilometres per hour, or null when the distance is too short to mean anything. */
    fun speedKmh(distanceM: Double, seconds: Long): Double? {
        if (distanceM < MIN_DISTANCE_FOR_PACE_M || seconds <= 0) return null
        return distanceM / 1000.0 / (seconds / 3600.0)
    }

    /** A GPX 1.1 document with one track and one segment. */
    fun gpx(name: String, points: List<RoutePoint>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"Circlet\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        append("  <trk>\n    <name>").append(escape(name)).append("</name>\n    <trkseg>\n")
        for (p in points) {
            append("      <trkpt lat=\"").append(coordinate(p.lat)).append("\" lon=\"").append(coordinate(p.lon)).append("\">")
            append("<time>").append(Instant.ofEpochMilli(p.epochMs)).append("</time></trkpt>\n")
        }
        append("    </trkseg>\n  </trk>\n</gpx>\n")
    }

    private fun coordinate(value: Double): String = String.format(Locale.ROOT, "%.7f", value)

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
