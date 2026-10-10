package app.circlet.ui

import app.circlet.core.AbsentReason
import app.circlet.core.LinkState
import app.circlet.core.MassUnit
import app.circlet.core.Sex
import app.circlet.core.StrapError
import app.circlet.core.SyncProgress
import app.circlet.core.SystemTimeSource
import app.circlet.core.fake.FakeCirclet
import app.circlet.core.fake.FakeScenario
import app.circlet.ui.design.words
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
    fun calibratingAndEmptyDaysSaySo() {
        val calibrating = ready(FakeScenario.CALIBRATING)
        assertEquals(Notice.Calibrating(calibrating.nightsRecorded), calibrating.notice)
        assertEquals(Notice.EmptyDay(today = true, reason = AbsentReason.NOT_ENOUGH_DATA), ready(FakeScenario.NO_DATA_TODAY).notice)
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
