package app.circlet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTest {
    private fun at(seconds: Long, lat: Double, lon: Double = 0.0, accuracy: Double = 5.0) =
        RoutePoint(1_700_000_000_000L + seconds * 1000, lat, lon, accuracy)

    @Test
    fun oneDegreeOfLatitudeIsAboutOneHundredElevenKilometres() {
        val metres = Route.haversine(at(0, 0.0), at(1, 1.0))
        assertEquals(111_195.0, metres, 50.0)
    }

    @Test
    fun coarseFixesAreDropped() {
        val kept = Route.filter(listOf(at(0, 0.0), at(1, 0.00001, accuracy = 26.0), at(2, 0.00002)), RouteKind.FOOT)
        assertEquals(2, kept.size)
        assertEquals(0.00002, kept.last().lat, 0.0)
    }

    @Test
    fun aJumpFasterThanARunnerCanMoveIsDroppedButCyclingKeepsIt() {
        // 0.0002 degrees is about 22 m in one second: 22 m/s.
        val points = listOf(at(0, 0.0), at(1, 0.0002))
        assertEquals(1, Route.filter(points, RouteKind.FOOT).size)
        assertEquals(2, Route.filter(points, RouteKind.CYCLING).size)
    }

    @Test
    fun speedIsMeasuredFromTheLastKeptFixSoAJumpDoesNotPoisonTheTrack() {
        val points = listOf(at(0, 0.0), at(1, 0.01), at(2, 0.00002))
        val kept = Route.filter(points, RouteKind.FOOT)
        assertEquals(listOf(0.0, 0.00002), kept.map { it.lat })
    }

    @Test
    fun fixesThatDoNotAdvanceInTimeAreDropped() {
        assertEquals(1, Route.filter(listOf(at(5, 0.0), at(5, 0.00001), at(4, 0.00002)), RouteKind.FOOT).size)
    }

    @Test
    fun distanceAddsEverySegment() {
        val points = listOf(at(0, 0.0), at(60, 0.001), at(120, 0.002))
        assertEquals(222.4, Route.distanceM(points), 0.5)
        assertEquals(0.0, Route.distanceM(emptyList()), 0.0)
    }

    @Test
    fun paceAndSpeedFollowDistanceAndTime() {
        assertEquals(5.0, Route.paceMinPerKm(1000.0, 300)!!, 1e-9)
        assertEquals(36.0, Route.speedKmh(10_000.0, 1000)!!, 1e-9)
        assertNull(Route.paceMinPerKm(5.0, 60))
        assertNull(Route.speedKmh(1000.0, 0))
    }

    @Test
    fun onlyOutdoorFootAndCyclingSportsHaveARouteKind() {
        assertEquals(RouteKind.FOOT, Sport.RUNNING.routeKind())
        assertEquals(RouteKind.CYCLING, Sport.CYCLING.routeKind())
        assertNull(Sport.SWIMMING.routeKind())
        assertNull(Sport.STRENGTH.routeKind())
    }

    @Test
    fun gpxHasHeaderTrackPointsAndEscapedName() {
        val gpx = Route.gpx("Run & <go>", listOf(at(0, 51.5, -0.12)))
        assertTrue(gpx.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(gpx.contains("<gpx version=\"1.1\" creator=\"Circlet\" xmlns=\"http://www.topografix.com/GPX/1/1\">"))
        assertTrue(gpx.contains("<name>Run &amp; &lt;go&gt;</name>"))
        assertTrue(gpx.contains("<trkpt lat=\"51.5000000\" lon=\"-0.1200000\"><time>2023-11-14T22:13:20Z</time></trkpt>"))
    }
}
