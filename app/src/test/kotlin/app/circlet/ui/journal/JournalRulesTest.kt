package app.circlet.ui.journal

import app.circlet.core.AbsentReason
import app.circlet.core.Confidence
import app.circlet.core.CorrelationMetric
import app.circlet.core.JournalTag
import app.circlet.core.Metric
import app.circlet.core.TagCorrelation
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalRulesTest {

    private val today = LocalDate.of(2026, 10, 10)

    private fun tag(id: String, name: String = id, builtIn: Boolean = false) = JournalTag(id, name, builtIn)

    private fun correlation(
        tagId: String,
        metric: CorrelationMetric = CorrelationMetric.RECOVERY,
        effect: Double? = 3.0,
        daysWith: Int = 5,
        daysWithout: Int = 5,
    ) = TagCorrelation(
        tagId = tagId,
        metric = metric,
        effect = if (effect == null) {
            Metric.absent(AbsentReason.NOT_ENOUGH_DATA)
        } else {
            Metric.of(effect, Confidence.MEDIUM)
        },
        daysWith = daysWith,
        daysWithout = daysWithout,
    )

    // Tag toggling

    @Test
    fun toggleAddsAnAbsentTag() {
        assertEquals(setOf("alcohol", "travel"), toggleTag(setOf("alcohol"), "travel"))
    }

    @Test
    fun toggleRemovesAPresentTag() {
        assertEquals(setOf("alcohol"), toggleTag(setOf("alcohol", "travel"), "travel"))
    }

    @Test
    fun toggleLeavesTheInputUnchanged() {
        val input = setOf("alcohol")
        toggleTag(input, "travel")
        toggleTag(input, "alcohol")
        assertEquals(setOf("alcohol"), input)
    }

    @Test
    fun toggleTwiceReturnsToTheStart() {
        val start = setOf("alcohol")
        assertEquals(start, toggleTag(toggleTag(start, "travel"), "travel"))
    }

    @Test
    fun builtInTagsCannotBeRemoved() {
        assertFalse(tag("sleep-late", builtIn = true).canRemove())
        assertTrue(tag("custom-1", builtIn = false).canRemove())
    }

    // Enough data

    @Test
    fun twoDaysOnEachSideHasEnoughData() {
        assertTrue(correlation("a", daysWith = 2, daysWithout = 2).hasEnoughData())
    }

    @Test
    fun oneDayOnEitherSideIsNotEnough() {
        assertFalse(correlation("a", daysWith = 1, daysWithout = 9).hasEnoughData())
        assertFalse(correlation("a", daysWith = 9, daysWithout = 1).hasEnoughData())
    }

    @Test
    fun anAbsentEffectIsNotEnoughEvenWithManyDays() {
        assertFalse(correlation("a", effect = null, daysWith = 20, daysWithout = 20).hasEnoughData())
    }

    @Test
    fun splitSendsThinRowsToNotEnough() {
        val rows = listOf(
            correlation("a", daysWith = 3, daysWithout = 3),
            correlation("b", daysWith = 1, daysWithout = 3),
            correlation("c", effect = null, daysWith = 4, daysWithout = 4),
        )
        val split = splitCorrelations(rows, CorrelationMetric.RECOVERY, listOf(tag("a"), tag("b"), tag("c")))
        assertEquals(listOf("a"), split.measured.map { it.correlation.tagId })
        assertEquals(listOf("b", "c"), split.notEnough.map { it.tagId })
    }

    // Split by metric and tag order

    @Test
    fun splitKeepsOnlyTheRequestedMetric() {
        val rows = listOf(
            correlation("a", metric = CorrelationMetric.RECOVERY),
            correlation("a", metric = CorrelationMetric.HRV, effect = 9.0),
            correlation("b", metric = CorrelationMetric.HRV, effect = -4.0),
        )
        val split = splitCorrelations(rows, CorrelationMetric.HRV, listOf(tag("a"), tag("b")))
        assertEquals(listOf("a", "b"), split.measured.map { it.correlation.tagId })
        assertEquals(listOf(9.0, -4.0), split.measured.map { it.effect })
    }

    @Test
    fun splitFollowsTheTagListOrder() {
        val rows = listOf(
            correlation("a"),
            correlation("b"),
            correlation("c"),
        )
        val split = splitCorrelations(rows, CorrelationMetric.RECOVERY, listOf(tag("c"), tag("a"), tag("b")))
        assertEquals(listOf("c", "a", "b"), split.measured.map { it.correlation.tagId })
    }

    @Test
    fun splitOfNothingIsEmpty() {
        val split = splitCorrelations(emptyList(), CorrelationMetric.RECOVERY, emptyList())
        assertTrue(split.measured.isEmpty())
        assertTrue(split.notEnough.isEmpty())
    }

    // Bar scaling

    @Test
    fun barIsNullWhenNothingIsMeasured() {
        assertNull(barFraction(2.0, emptyList()))
    }

    @Test
    fun barIsZeroWhenTheLargestEffectIsZero() {
        val measured = listOf(MeasuredEffect(correlation("a", effect = 0.0), 0.0))
        assertEquals(0f, barFraction(0.0, measured)!!, 0f)
    }

    @Test
    fun barIsScaledToTheLargestAbsoluteEffect() {
        val measured = listOf(
            MeasuredEffect(correlation("a", effect = 4.0), 4.0),
            MeasuredEffect(correlation("b", effect = -2.0), -2.0),
        )
        assertEquals(1f, barFraction(4.0, measured)!!, 1e-6f)
        assertEquals(-0.5f, barFraction(-2.0, measured)!!, 1e-6f)
    }

    // Tag names

    @Test
    fun blankNameIsRejected() {
        assertEquals("Give the tag a name.", tagNameProblem("   ", emptyList()))
    }

    @Test
    fun nameOverTheLimitIsRejected() {
        assertNotNull(tagNameProblem("x".repeat(MAX_TAG_NAME + 1), emptyList()))
    }

    @Test
    fun nameAtTheLimitIsAccepted() {
        assertNull(tagNameProblem("x".repeat(MAX_TAG_NAME), emptyList()))
    }

    @Test
    fun duplicateNameIgnoresCase() {
        assertNotNull(tagNameProblem("  alcohol ", listOf(tag("alcohol", "Alcohol"))))
    }

    @Test
    fun validNameIsAccepted() {
        assertNull(tagNameProblem("Caffeine after noon", listOf(tag("alcohol", "Alcohol"))))
    }

    // Day navigation

    @Test
    fun shiftingBackOneDayGoesToYesterday() {
        assertEquals(LocalDate.of(2026, 10, 9), shiftDay(today, -1, today))
    }

    @Test
    fun shiftingForwardPastTodayStopsAtToday() {
        assertEquals(today, shiftDay(today, 1, today))
        assertEquals(today, shiftDay(today, 7, today))
    }

    @Test
    fun clampLeavesPastDatesAlone() {
        val past = LocalDate.of(2026, 9, 1)
        assertEquals(past, clampToToday(past, today))
    }

    @Test
    fun clampPullsFutureDatesBackToToday() {
        assertEquals(today, clampToToday(today.plusDays(3), today))
    }
}
