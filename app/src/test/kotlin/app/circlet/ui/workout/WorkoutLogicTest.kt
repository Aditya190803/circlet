package app.circlet.ui.workout

import app.circlet.core.AbsentReason
import app.circlet.core.Activity
import app.circlet.core.ActivitySource
import app.circlet.core.Confidence
import app.circlet.core.Metric
import app.circlet.core.Sport
import app.circlet.core.ZoneMinutes
import app.circlet.ui.design.TimeFormat
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutLogicTest {
    private val utc = ZoneId.of("UTC")
    private val base: Instant = Instant.parse("2026-10-11T09:00:00Z")

    private fun at(minutes: Long): Instant = base.plusSeconds(minutes * 60)

    private fun activity(id: String, startMin: Long, endMin: Long, sport: Sport = Sport.RUNNING): Activity =
        Activity(
            id = id,
            source = ActivitySource.MANUAL,
            sport = sport,
            sportConfidence = null,
            start = at(startMin),
            end = at(endMin),
            strain = Metric.of(4.0, Confidence.HIGH),
            averageHeartRateBpm = Metric.absent(AbsentReason.NOT_ENOUGH_DATA),
            maxHeartRateBpm = Metric.absent(AbsentReason.NOT_ENOUGH_DATA),
            calories = Metric.absent(AbsentReason.NOT_ENOUGH_DATA),
            zoneMinutes = ZoneMinutes(List(ZoneMinutes.ZONE_COUNT) { 0 }),
            heartRateRecovery = null,
        )

    private val h24 = TimeFormat(utc, twentyFourHour = true)
    private val h12 = TimeFormat(utc, twentyFourHour = false)

    @Test
    fun newestFirstOrdersByStartDescending() {
        val list = listOf(activity("a", 0, 10), activity("b", 60, 70), activity("c", 30, 40))
        assertEquals(listOf("b", "c", "a"), newestFirst(list).map { it.id })
    }

    @Test
    fun localDayUsesGivenZone() {
        val lateUtc = Instant.parse("2026-10-11T23:30:00Z")
        assertEquals("2026-10-11", localDay(lateUtc, utc).toString())
        assertEquals("2026-10-12", localDay(lateUtc, ZoneId.of("Asia/Tokyo")).toString())
    }

    @Test
    fun adjacentSelectedActivitiesCanMerge() {
        val day = listOf(activity("a", 0, 30), activity("b", 35, 60))
        assertTrue(canMerge(day, setOf("a", "b"), utc))
    }

    @Test
    fun singleActivityCannotMerge() {
        val day = listOf(activity("a", 0, 30), activity("b", 35, 60))
        assertFalse(canMerge(day, setOf("a"), utc))
    }

    @Test
    fun gapLongerThanLimitCannotMerge() {
        val day = listOf(activity("a", 0, 30), activity("b", 30 + 11, 60))
        assertFalse(canMerge(day, setOf("a", "b"), utc))
    }

    @Test
    fun gapExactlyAtLimitCanMerge() {
        val day = listOf(activity("a", 0, 30), activity("b", 30 + 10, 60))
        assertTrue(canMerge(day, setOf("a", "b"), utc))
    }

    @Test
    fun selectionWithUnknownIdCannotMerge() {
        val day = listOf(activity("a", 0, 30), activity("b", 35, 60))
        assertFalse(canMerge(day, setOf("a", "missing"), utc))
    }

    @Test
    fun unselectedActivityInsideRunBlocksMerge() {
        val day = listOf(activity("a", 0, 30), activity("c", 32, 34), activity("b", 35, 60))
        assertFalse(canMerge(day, setOf("a", "b"), utc))
    }

    @Test
    fun activitiesOnDifferentDaysCannotMerge() {
        val earlier = Activity(
            id = "a", source = ActivitySource.MANUAL, sport = Sport.RUNNING, sportConfidence = null,
            start = Instant.parse("2026-10-10T23:50:00Z"), end = Instant.parse("2026-10-11T00:00:00Z"),
            strain = Metric.of(1.0, Confidence.HIGH), averageHeartRateBpm = Metric.absent(AbsentReason.NOT_ENOUGH_DATA),
            maxHeartRateBpm = Metric.absent(AbsentReason.NOT_ENOUGH_DATA), calories = Metric.absent(AbsentReason.NOT_ENOUGH_DATA),
            zoneMinutes = ZoneMinutes(List(ZoneMinutes.ZONE_COUNT) { 0 }), heartRateRecovery = null,
        )
        val later = activity("b", 5, 40)
        assertFalse(canMerge(listOf(earlier, later), setOf("a", "b"), utc))
    }

    @Test
    fun durationUnderAnHourShowsMinutes() {
        assertEquals("42 MIN", durationText(at(0), at(42)))
    }

    @Test
    fun durationFromAnHourPadsMinutes() {
        assertEquals("1 H 05 MIN", durationText(at(0), at(65)))
    }

    @Test
    fun reversedDurationReadsAsZero() {
        assertEquals("0 MIN", durationText(at(30), at(0)))
    }

    @Test
    fun elapsedUnderAnHourUsesMinutesAndSeconds() {
        assertEquals("05:12", elapsedText(Duration.ofSeconds(312)))
    }

    @Test
    fun elapsedFromAnHourUsesHours() {
        assertEquals("1:02:05", elapsedText(Duration.ofSeconds(3725)))
    }

    @Test
    fun negativeElapsedReadsAsZero() {
        assertEquals("00:00", elapsedText(Duration.ofSeconds(-9)))
    }

    @Test
    fun clockTextTwentyFourHour() {
        assertEquals("13:05", clockText(Instant.parse("2026-10-11T13:05:00Z"), h24))
    }

    @Test
    fun clockTextTwelveHour() {
        assertEquals("1:05 PM", clockText(Instant.parse("2026-10-11T13:05:00Z"), h12))
    }

    @Test
    fun timeRangeJoinsStartAndEnd() {
        assertEquals("09:00 - 09:45", timeRange(at(0), at(45), h24))
    }

    @Test
    fun blankSportQueryKeepsEverySport() {
        assertEquals(Sport.entries.toList(), filterSports("   "))
    }

    @Test
    fun sportQueryMatchesIgnoringCase() {
        assertTrue(filterSports("RUN").contains(Sport.RUNNING))
    }

    @Test
    fun sportQueryWithNoMatchIsEmpty() {
        assertTrue(filterSports("zzzz").isEmpty())
    }
}
