package app.circlet.ui

import app.circlet.core.AbsentReason
import app.circlet.core.CalibrationStatus
import app.circlet.core.Confidence
import app.circlet.core.LinkState
import app.circlet.core.MassUnit
import app.circlet.core.Metric
import app.circlet.core.Recovery
import app.circlet.core.Sex
import app.circlet.core.StrapError
import app.circlet.core.StrapState
import app.circlet.core.Strain
import app.circlet.core.SyncProgress
import app.circlet.core.SystemTimeSource
import app.circlet.core.ZoneMinutes
import app.circlet.core.emptyDay
import app.circlet.core.fake.FakeCirclet
import app.circlet.core.fake.FakeScenario
import app.circlet.ui.design.words
import app.circlet.ui.design.stalled
import app.circlet.ui.design.syncProgressLabel
import app.circlet.ui.more.ProfileDraft
import app.circlet.ui.more.parseNotice
import app.circlet.ui.state.DayUiState
import app.circlet.ui.state.Loadable
import app.circlet.ui.state.Notice
import app.circlet.ui.state.dayUiState
import app.circlet.ui.state.today
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiLogicTest {
    private fun ready(scenario: FakeScenario): DayUiState = runBlocking {
        val fake = FakeCirclet(SystemTimeSource, scenario)
        dayUiState(fake, fake, SystemTimeSource, flowOf(SystemTimeSource.today())).first { it.day is Loadable.Ready && it.history.isNotEmpty() }
    }

    @Test
    fun aHealthyPairedDayNeedsNoNotice() {
        val state = ready(FakeScenario.PAIRED)
        assertTrue(state.notice == null || state.notice is Notice.Wellness)
        assertEquals(7, state.week.size)
    }

    @Test
    fun theNoticeFollowsTheStrapBeforeTheData() {
        val paired = ready(FakeScenario.PAIRED)
        assertEquals(Notice.Unpaired, ready(FakeScenario.UNPAIRED).notice)
        assertEquals(Notice.Searching, paired.copy(strap = paired.strap.copy(link = LinkState.RECONNECTING)).notice)
        assertEquals(Notice.NeedsPermission, paired.copy(strap = paired.strap.copy(error = StrapError.PermissionMissing)).notice)
        assertEquals(Notice.CatchingUp(90), paired.copy(strap = paired.strap.copy(sync = SyncProgress(5_400, draining = true))).notice)
    }

    @Test
    fun syncStatusCountsBufferedSamplesBeforeTheyAreCommitted() {
        assertEquals(true, SyncProgress(0, draining = false, lastOutcome = app.circlet.core.SyncOutcome.STALLED).stalled())
        assertEquals(true, SyncProgress(0, draining = false, lastOutcome = app.circlet.core.SyncOutcome.DISCONNECTED).stalled())
        assertEquals(false, SyncProgress(0, draining = true, lastOutcome = app.circlet.core.SyncOutcome.STALLED).stalled())
        assertEquals(false, SyncProgress(0, draining = false, lastOutcome = app.circlet.core.SyncOutcome.ACK_FAILED).stalled())
        assertEquals("SYNCING · STARTING", syncProgressLabel(SyncProgress(0, draining = true)))
        assertEquals("SYNCING · 30 SEC RECEIVED", syncProgressLabel(SyncProgress(0, draining = true, samplesBuffered = 30)))
        assertEquals("SYNCING · 1 MIN RECEIVED", syncProgressLabel(SyncProgress(60, draining = true)))
    }

    @Test
    fun calibratingAndEmptyDaysSaySo() {
        val calibrating = ready(FakeScenario.CALIBRATING)
        assertEquals(Notice.Calibrating(calibrating.baselineNights, calibrating.baselineNightsLeft), calibrating.notice)
        assertEquals(14, calibrating.baselineNights + calibrating.baselineNightsLeft)
        assertEquals(Notice.EmptyDay(today = true, reason = AbsentReason.NOT_ENOUGH_DATA), ready(FakeScenario.NO_DATA_TODAY).notice)
    }

    @Test
    fun fourteenEarlierRecoveryNightsAreNotCalibrating() {
        val today = LocalDate.of(2026, 10, 12)
        val history = (14 downTo 0).map { ago ->
            val day = today.minusDays(ago.toLong())
            emptyDay(day, AbsentReason.NOT_ENOUGH_DATA, CalibrationStatus.READY).copy(
                recovery = Recovery(Metric.of(70, Confidence.HIGH), CalibrationStatus.READY, emptyList()),
                strain = Strain(Metric.of(8.0, Confidence.MEDIUM), ZoneMinutes.ZERO),
            )
        }
        val state = DayUiState(
            date = today,
            today = today,
            day = Loadable.Ready(history.last()),
            history = history,
            strap = StrapState(link = LinkState.CONNECTED),
        )
        assertEquals(14, state.baselineNights)
        assertNull(state.notice)
        assertTrue(state.notice !is Notice.Calibrating)
        assertTrue(state.notice !is Notice.FirstNight)
    }

    @Test
    fun aLoadingDayHasNoNotice() {
        assertNull(ready(FakeScenario.PAIRED).copy(day = Loadable.Loading).notice)
    }

    @Test
    fun everyAbsentReasonHasWords() {
        for (reason in AbsentReason.entries) assertTrue(reason.words().isNotBlank())
    }

    @Test
    fun theNoticeFileSplitsIntoSectionsWithFencedLicenceText() {
        val sections = parseNotice("# Notices\n\nIntro line\nwrapped.\n\n## OpenStrap\n\nPorted.\n\n```\nMIT License\n\nCopyright\n```\n\n## Barlow\n\nA font.\n")
        assertEquals(listOf(null, "OpenStrap", "Barlow"), sections.map { it.heading })
        assertEquals("Intro line wrapped.", sections[0].blocks.single().text)
        assertEquals(listOf(false, true), sections[1].blocks.map { it.fenced })
        assertEquals("MIT License\n\nCopyright", sections[1].blocks[1].text)
    }

    @Test
    fun theProfileFormKeepsABirthDateWhileTheAgeAgrees() {
        val today = LocalDate.of(2026, 10, 10)
        val base = FakeCirclet(SystemTimeSource).profile.value
        val same = ProfileDraft("32", "168", "64", "", Sex.FEMALE).toProfile(base, MassUnit.KILOGRAM, today)
        assertEquals(base.birthDate, same?.birthDate)
        val changed = ProfileDraft("40", "168", "141", "", Sex.FEMALE).toProfile(base, MassUnit.POUND, today)
        assertNull(changed?.birthDate)
        assertEquals(40, changed?.ageYears)
        assertEquals(63.96, changed?.weightKg ?: 0.0, 0.01)
        assertNull(ProfileDraft("40", "168", "64", "90", Sex.FEMALE).toProfile(base, MassUnit.KILOGRAM, today))
    }
}
