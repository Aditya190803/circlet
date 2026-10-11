package app.circlet.ui.trends

import app.circlet.core.TrendRange
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendMathTest {
    private val today = LocalDate.of(2026, 10, 10)

    @Test
    fun monthGridStartsWithLeadingBlanksUnderTheRightWeekday() {
        // 1 October 2026 is a Thursday, so Monday to Wednesday are blank.
        val grid = monthGrid(YearMonth.of(2026, 10))
        assertEquals(3, grid.takeWhile { it == null }.size)
        assertEquals(LocalDate.of(2026, 10, 1), grid[3])
        assertEquals(DayOfWeek.THURSDAY, grid[3]!!.dayOfWeek)
    }

    @Test
    fun monthGridIsMondayFirstAndPadsTheLastWeek() {
        val grid = monthGrid(YearMonth.of(2026, 10))
        assertEquals(35, grid.size)
        assertEquals(0, grid.size % 7)
        assertEquals(31, grid.count { it != null })
        assertNull(grid.last())
        assertEquals(LocalDate.of(2026, 10, 31), grid[33])
    }

    @Test
    fun monthGridWithoutLeadingOrTrailingBlanksWhenWeeksFillExactly() {
        // 1 February 2027 is a Monday and the month has 28 days, so every cell is a day.
        val grid = monthGrid(YearMonth.of(2027, 2))
        assertEquals(28, grid.size)
        assertTrue(grid.none { it == null })
    }

    @Test
    fun february2026HasSixLeadingBlanksAndOneTrailingBlank() {
        // 1 February 2026 is a Sunday.
        val grid = monthGrid(YearMonth.of(2026, 2))
        assertEquals(6, grid.takeWhile { it == null }.size)
        assertEquals(35, grid.size)
        assertNull(grid.last())
    }

    @Test
    fun everyDayLandsInTheColumnOfItsWeekday() {
        monthGrid(YearMonth.of(2026, 2)).forEachIndexed { index, day ->
            if (day != null) assertEquals(DayOfWeek.of(index % 7 + 1), day.dayOfWeek)
        }
    }

    @Test
    fun weekStartIsTheMondayOfTheSameIsoWeek() {
        assertEquals(LocalDate.of(2026, 10, 5), currentWeekStart(today))
        assertEquals(LocalDate.of(2026, 9, 28), currentWeekStart(LocalDate.of(2026, 10, 4)))
        assertEquals(LocalDate.of(2026, 10, 5), currentWeekStart(LocalDate.of(2026, 10, 5)))
    }

    @Test
    fun averageIgnoresMissingDays() {
        assertEquals(2.0, listOf(1.0, null, 3.0).averageOrNull()!!, 1e-9)
    }

    @Test
    fun averageIsNullWhenNoDayHasAValue() {
        assertNull(listOf<Double?>(null, null).averageOrNull())
        assertNull(emptyList<Double?>().averageOrNull())
    }

    @Test
    fun missingDayIsNotCountedAsZero() {
        assertEquals(10.0, listOf(10.0, null).averageOrNull()!!, 1e-9)
        assertEquals(0.0, listOf(0.0, null).averageOrNull()!!, 1e-9)
    }

    @Test
    fun seriesStatsReportMinAverageMaxFromRealPoints() {
        val stats = seriesStats(listOf(4.0, null, 10.0, 7.0))
        assertEquals(4.0, stats.min!!, 1e-9)
        assertEquals(7.0, stats.average!!, 1e-9)
        assertEquals(10.0, stats.max!!, 1e-9)
    }

    @Test
    fun periodChangeComparesAveragesOfEqualPeriods() {
        val change = periodChange(current = listOf(10.0, null, 20.0), previous = listOf(5.0, 5.0))!!
        assertEquals(15.0, change.current, 1e-9)
        assertEquals(5.0, change.previous, 1e-9)
        assertEquals(10.0, change.delta, 1e-9)
        assertEquals(200.0, change.percent!!, 1e-9)
    }

    @Test
    fun periodChangeIsNullWhenEitherPeriodHasNoData() {
        assertNull(periodChange(current = listOf(10.0), previous = listOf(null, null)))
        assertNull(periodChange(current = listOf(null), previous = listOf(3.0)))
    }

    @Test
    fun periodChangePercentIsNullWhenPreviousIsZero() {
        val change = periodChange(current = listOf(4.0), previous = listOf(0.0))!!
        assertEquals(4.0, change.delta, 1e-9)
        assertNull(change.percent)
    }

    @Test
    fun weekWindowsAreSevenDaysEndingToday() {
        assertEquals(DayWindow(LocalDate.of(2026, 10, 4), today), currentWindow(TrendRange.WEEK, today))
        assertEquals(DayWindow(LocalDate.of(2026, 9, 27), LocalDate.of(2026, 10, 3)), previousWindow(TrendRange.WEEK, today))
    }

    @Test
    fun monthWindowsAreThirtyDaysEndingToday() {
        assertEquals(DayWindow(LocalDate.of(2026, 9, 11), today), currentWindow(TrendRange.MONTH, today))
        assertEquals(DayWindow(LocalDate.of(2026, 8, 12), LocalDate.of(2026, 9, 10)), previousWindow(TrendRange.MONTH, today))
    }

    @Test
    fun cannotNavigateToAWeekAfterThisOne() {
        assertFalse(canShowWeekAfter(LocalDate.of(2026, 10, 5), today))
        assertTrue(canShowWeekAfter(LocalDate.of(2026, 9, 28), today))
    }

    @Test
    fun shiftingWeekForwardStopsAtTheCurrentWeek() {
        assertEquals(LocalDate.of(2026, 10, 5), shiftWeek(LocalDate.of(2026, 10, 5), 1, today))
        assertEquals(LocalDate.of(2026, 10, 5), shiftWeek(LocalDate.of(2026, 9, 28), 1, today))
    }

    @Test
    fun shiftingWeekBackwardGoesToThePreviousMonday() {
        assertEquals(LocalDate.of(2026, 9, 21), shiftWeek(LocalDate.of(2026, 9, 28), -1, today))
    }

    @Test
    fun shiftingWeekFromAMidWeekDateSnapsToItsMonday() {
        // 25 September 2026 is a Friday, so it sits in the week of Monday 21 September; one week back is 14 September.
        assertEquals(LocalDate.of(2026, 9, 14), shiftWeek(LocalDate.of(2026, 9, 25), -1, today))
    }

    @Test
    fun monthNavigationStopsAtTheCurrentMonth() {
        assertFalse(canShowMonthAfter(YearMonth.of(2026, 10), today))
        assertTrue(canShowMonthAfter(YearMonth.of(2026, 9), today))
        assertEquals(YearMonth.of(2026, 10), shiftMonth(YearMonth.of(2026, 9), 3, today))
        assertEquals(YearMonth.of(2026, 7), shiftMonth(YearMonth.of(2026, 10), -3, today))
    }
}
