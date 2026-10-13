package app.circlet.strap.notify

import app.circlet.core.LinkState
import app.circlet.core.StrapState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OffWristAlertPolicyTest {
    @Test
    fun episodeBecomesEligibleAfterFiveMinutes() {
        val start = 1_000_000L
        val state = StrapState(link = LinkState.CONNECTED, onWrist = false)
        val episode = OffWristAlertPolicy.update(null, state, start)!!

        assertEquals(1_000L, OffWristAlertPolicy.remainingMillis(episode, start + OFF_WRIST_ALERT_DELAY_MS - 1_000))
        assertEquals(0L, OffWristAlertPolicy.remainingMillis(episode, start + OFF_WRIST_ALERT_DELAY_MS))
        assertTrue(OffWristAlertPolicy.isEligible(state))
    }

    @Test
    fun wristReturnResetsTheAlertAndAllowsANewEpisode() {
        val start = 1_000_000L
        val offWrist = StrapState(link = LinkState.CONNECTED, onWrist = false)
        val alerted = OffWristAlertPolicy.update(null, offWrist, start)!!.copy(alerted = true)
        assertEquals(alerted, OffWristAlertPolicy.update(alerted, offWrist, start + OFF_WRIST_ALERT_DELAY_MS))

        assertNull(
            OffWristAlertPolicy.update(
                alerted,
                StrapState(link = LinkState.CONNECTED, onWrist = true),
                start + OFF_WRIST_ALERT_DELAY_MS,
            ),
        )
        assertEquals(
            start + OFF_WRIST_ALERT_DELAY_MS + 1,
            OffWristAlertPolicy.update(null, offWrist, start + OFF_WRIST_ALERT_DELAY_MS + 1)!!.startedAtMillis,
        )
    }

    @Test
    fun disconnectKeepsTheOffWristEpisodeButUnpairedClearsIt() {
        val start = 1_000_000L
        val episode = OffWristEpisode(start, alerted = true)

        assertEquals(
            episode,
            OffWristAlertPolicy.update(
                episode,
                StrapState(link = LinkState.RECONNECTING, onWrist = false),
                start + 10_000,
            ),
        )
        assertNull(OffWristAlertPolicy.update(episode, StrapState(), start + 10_000))
    }
}
