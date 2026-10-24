package app.circlet.ui.design

import app.circlet.core.SleepEpoch
import app.circlet.core.SleepStage
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HypnogramTest {
    private val night = Instant.parse("2026-10-23T23:00:00Z")

    private fun epoch(minute: Long, stage: SleepStage, minutes: Long = 1) =
        SleepEpoch(night.plusSeconds(minute * 60), stage, Duration.ofMinutes(minutes))

    private fun at(minute: Long) = night.plusSeconds(minute * 60)

    @Test
    fun touchingEpochsOfOneStageMergeIntoOneRun() {
        val runs = stageRuns(
            listOf(
                epoch(0, SleepStage.LIGHT), epoch(1, SleepStage.LIGHT), epoch(2, SleepStage.DEEP, 3),
                epoch(5, SleepStage.LIGHT),
            ),
        )
        assertEquals(
            listOf(
                StageRun(SleepStage.LIGHT, at(0), at(2)),
                StageRun(SleepStage.DEEP, at(2), at(5)),
                StageRun(SleepStage.LIGHT, at(5), at(6)),
            ),
            runs,
        )
    }

    @Test
    fun aGapKeepsSameStageEpochsApart() {
        val runs = stageRuns(listOf(epoch(0, SleepStage.REM), epoch(10, SleepStage.REM)))
        assertEquals(2, runs.size)
    }

    @Test
    fun epochsAreOrderedBeforeMerging() {
        val runs = stageRuns(listOf(epoch(1, SleepStage.LIGHT), epoch(0, SleepStage.LIGHT)))
        assertEquals(listOf(StageRun(SleepStage.LIGHT, at(0), at(2))), runs)
    }

    @Test
    fun aTouchReadsTheRunHoldingIt() {
        val runs = stageRuns(listOf(epoch(0, SleepStage.LIGHT, 10), epoch(10, SleepStage.DEEP, 10)))
        assertEquals(SleepStage.DEEP, runAt(runs, at(12))?.stage)
        assertEquals(SleepStage.LIGHT, runAt(runs, at(9))?.stage)
    }

    @Test
    fun beforeTheFirstRunOrPastTheLastTheNearestRunIsHeld() {
        val runs = stageRuns(listOf(epoch(5, SleepStage.LIGHT, 10), epoch(15, SleepStage.AWAKE, 5)))
        assertEquals(SleepStage.LIGHT, runAt(runs, at(0))?.stage)
        assertEquals(SleepStage.AWAKE, runAt(runs, at(60))?.stage)
    }

    @Test
    fun aNightWithNoEpochsReadsNothing() {
        assertNull(runAt(emptyList(), at(0)))
    }
}
