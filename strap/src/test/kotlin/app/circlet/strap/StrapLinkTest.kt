@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.circlet.strap

import app.circlet.core.CoreError
import app.circlet.core.FoundStrap
import app.circlet.core.LinkState
import app.circlet.core.Outcome
import app.circlet.core.StrapError
import app.circlet.core.SyncOutcome
import app.circlet.protocol.Frames
import app.circlet.strap.diag.SyncRecorder
import app.circlet.strap.link.GattOperationQueue
import app.circlet.strap.link.NotifyRole
import app.circlet.strap.link.StrapLink
import app.circlet.strap.sync.HISTORY_FLUSH_EVERY
import app.circlet.strap.support.FakeHost
import app.circlet.strap.support.FakeTransport
import app.circlet.strap.support.MemoryPrefs
import app.circlet.strap.support.RecordingSink
import app.circlet.strap.support.innerOf
import app.circlet.strap.support.metaFrame
import app.circlet.strap.support.opcodeOf
import app.circlet.strap.support.recordFrame
import app.circlet.strap.support.testPorts
import app.circlet.core.ManualTimeSource
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrapLinkTest {
    private val strap = FoundStrap("AA:BB:CC:DD:EE:01", "WHOOP 4.0", -55)
    private val token = byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2)

    @Test
    fun aSystemWakeRecoversPendingAutoConnectWithoutOpeningTheApp() = runTest {
        val rig = rig()
        rig.link.pair(strap)
        runCurrent()
        rig.transport.autoConnectGate = CompletableDeferred()
        rig.transport.down()
        runCurrent()
        rig.link.resumeBackground()
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf(false, true, false), rig.transport.connects)
        assertEquals(LinkState.CONNECTED, rig.link.state.value.link)
    }

    @Test
    fun aStalledPassiveConnectionFallsBackToDirectWithoutAnyWake() = runTest {
        val rig = rig()
        rig.link.pair(strap)
        runCurrent()
        rig.transport.autoConnectGate = CompletableDeferred()
        rig.transport.down()
        runCurrent()
        advanceTimeBy(95_000)
        runCurrent()
        assertEquals(listOf(false, true, false), rig.transport.connects)
        assertEquals(LinkState.CONNECTED, rig.link.state.value.link)
    }

    @Test
    fun aSystemWakeDoesNotDuplicateAnActiveHistoryDrain() = runTest {
        val rig = rig()
        rig.link.pair(strap)
        runCurrent()
        repeat(3) { rig.link.resumeBackground() }
        runCurrent()
        assertEquals(1, rig.transport.writes.count { opcodeOf(it) == 0x16 })
        assertEquals(listOf(false), rig.transport.connects)
    }

    @Test
    fun pairingRestoredLaterFromCloudStartsTheLink() = runTest {
        val rig = rig()
        rig.link.start()
        assertEquals(LinkState.UNPAIRED, rig.link.state.value.link)
        rig.prefs.address = strap.address
        rig.link.configurationReady()
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(LinkState.CONNECTED, rig.link.state.value.link)
    }

    @Test
    fun restoredConfigurationWaitsUntilTheHistoryDrainFinishes() = runTest {
        val rig = rig()
        var configurationApplied = 0
        rig.link.onSessionReady = { configurationApplied++ }
        rig.link.pair(strap)
        runCurrent()
        rig.link.configurationReady()
        assertEquals(0, configurationApplied)
        rig.transport.emit(NotifyRole.DATA, metaFrame(3))
        runCurrent()
        assertEquals(1, configurationApplied)
    }

    @Test
    fun aManualRetryInterruptsBackgroundAutoConnectAndConnectsDirectly() = runTest {
        val rig = rig()
        rig.link.pair(strap)
        runCurrent()
        rig.transport.autoConnectGate = CompletableDeferred()
        rig.transport.down()
        runCurrent()
        assertEquals(listOf(false, true), rig.transport.connects)
        assertEquals(LinkState.RECONNECTING, rig.link.state.value.link)
        assertTrue(rig.link.syncNow() is Outcome.Ok)
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf(false, true, false), rig.transport.connects)
        assertEquals(LinkState.CONNECTED, rig.link.state.value.link)
        assertEquals(2, rig.transport.writes.count { opcodeOf(it) == 0x16 })
    }

    @Test
    fun aRetryAfterAbortAcceptsGen4RecordsWithoutAnotherStart() = runTest {
        val rig = rig()
        rig.link.pair(strap)
        runCurrent()
        rig.transport.emit(NotifyRole.DATA, heartFrame())
        runCurrent()
        // A batch that goes silent mid-stream is retried after 15 seconds, then the abort settles for 3.
        advanceTimeBy(18_001)
        runCurrent()
        rig.transport.emit(NotifyRole.DATA, heartFrame())
        rig.transport.emit(NotifyRole.DATA, metaFrame(3))
        runCurrent()
        assertEquals(SyncOutcome.COMPLETED, rig.link.state.value.sync.lastOutcome)
        assertEquals(1L, rig.link.state.value.sync.samplesPulled)
        assertEquals(1, rig.sink.batches.single().first.size)
    }

    @Test
    fun foregroundSessionsSendLinkValidHeartbeats() = runTest {
        val rig = rig()
        rig.link.pair(strap)
        runCurrent()
        rig.transport.emit(NotifyRole.DATA, metaFrame(3))
        runCurrent()
        rig.link.setLiveHeartRate(true)
        advanceTimeBy(10_001)
        runCurrent()
        val heartbeat = rig.transport.writes.single { opcodeOf(it) == 0x01 }
        assertEquals(0, innerOf(heartbeat)[3].toInt())
    }

    @Test
    fun pairWritesTheClockBeforeItDrains() = runTest {
        val rig = rig()
        val outcome = rig.link.pair(strap)
        assertTrue(outcome is Outcome.Ok)
        assertEquals(LinkState.CONNECTED, rig.link.state.value.link)
        val codes = rig.transport.writes.map { opcodeOf(it) }
        assertTrue(codes.indexOf(0x0A) < codes.indexOf(0x16))
        assertEquals(listOf(false), rig.transport.connects)
        assertEquals(strap.address to true, rig.host.paired.single())
    }

    @Test
    fun anEndMarkerIsCommittedBeforeItIsAcknowledged() = runTest {
        val rig = rig()
        val gate = CompletableDeferred<Unit>()
        rig.sink.gate = gate
        rig.link.pair(strap)
        runCurrent()
        rig.transport.emit(NotifyRole.DATA, metaFrame(1))
        rig.transport.emit(NotifyRole.DATA, recordFrame())
        rig.transport.emit(NotifyRole.DATA, metaFrame(2, token))
        runCurrent()
        assertTrue(rig.sink.batches.isEmpty())
        assertTrue(acks(rig.transport).isEmpty())
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, rig.sink.batches.size)
        assertEquals(0x2F, rig.sink.batches.single().second.single().bytes[0].toInt() and 0xFF)
        val ack = innerOf(acks(rig.transport).single())
        assertEquals(0x17, ack[2].toInt() and 0xFF)
        assertEquals(0x01, ack[3].toInt() and 0xFF)
        assertTrue(ack.copyOfRange(4, 12).contentEquals(token))
        assertNull(rig.prefs.synced)
        assertNull(rig.link.state.value.sync.lastOutcome)
    }

    @Test
    fun aTransientAckFailureRetriesTheSameCommittedBatchWithoutAborting() = runTest {
        val rig = rig()
        rig.link.pair(strap)
        runCurrent()
        rig.transport.failOpcode = 0x17
        rig.transport.failuresRemaining = 1
        rig.transport.emit(NotifyRole.DATA, metaFrame(1))
        rig.transport.emit(NotifyRole.DATA, recordFrame())
        rig.transport.emit(NotifyRole.DATA, metaFrame(2, token))
        runCurrent()
        assertEquals(1, rig.sink.batches.size)
        assertNull(rig.link.state.value.sync.lastOutcome)
        assertNull(rig.prefs.synced)
        advanceTimeBy(201)
        runCurrent()
        assertEquals(1, rig.sink.batches.size)
        assertEquals(1, acks(rig.transport).size)
        assertEquals(0, rig.transport.writes.count { opcodeOf(it) == 0x14 })
    }

    @Test
    fun aCorruptHistoryPacketDropsTheBatchAndTheLateEndIsNotAcknowledged() = runTest {
        val rig = rig()
        rig.link.pair(strap)
        runCurrent()
        rig.transport.emit(NotifyRole.DATA, metaFrame(1))
        rig.transport.emit(NotifyRole.DATA, recordFrame())
        runCurrent()
        val corrupt = recordFrame()
        corrupt[corrupt.size - 1] = (corrupt[corrupt.size - 1].toInt() xor 0x55).toByte()
        rig.transport.emit(NotifyRole.DATA, corrupt)
        rig.transport.emit(NotifyRole.DATA, metaFrame(2, token))
        runCurrent()
        assertEquals(SyncOutcome.DECODE_FAILED, rig.link.state.value.sync.lastOutcome)
        assertTrue(rig.sink.batches.isEmpty())
        assertTrue(acks(rig.transport).isEmpty())
    }

    @Test
    fun aDuplicateEndMarkerDoesNotCommitTwice() = runTest {
        val rig = rig()
        rig.link.pair(strap)
        runCurrent()
        rig.transport.emit(NotifyRole.DATA, metaFrame(1))
        rig.transport.emit(NotifyRole.DATA, metaFrame(2, token))
        rig.transport.emit(NotifyRole.DATA, metaFrame(2, token))
        runCurrent()
        assertEquals(1, rig.sink.batches.size)
        assertEquals(2, acks(rig.transport).size)
    }

    @Test
    fun aCommitFinishingAfterReconnectNeverWritesTheOldTokenToTheNewLink() = runTest {
        val rig = rig()
        rig.link.pair(strap)
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        rig.sink.gate = gate
        rig.transport.emit(NotifyRole.DATA, metaFrame(1))
        rig.transport.emit(NotifyRole.DATA, recordFrame())
        rig.transport.emit(NotifyRole.DATA, metaFrame(2, token))
        runCurrent()
        rig.transport.emit(NotifyRole.DATA, metaFrame(3))
        rig.transport.down()
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertTrue(acks(rig.transport).isEmpty())
        assertEquals(0L, rig.link.state.value.sync.samplesPulled)
        assertNull(rig.prefs.synced)
        assertTrue(rig.link.state.value.sync.draining)
    }

    @Test
    fun repeatedCheckpointsAreReacknowledgedWithoutPrematureAbort() = runTest {
        val rig = rig()
        rig.link.pair(strap)
        runCurrent()
        rig.transport.emit(NotifyRole.DATA, metaFrame(1))
        rig.transport.emit(NotifyRole.DATA, recordFrame())
        rig.transport.emit(NotifyRole.DATA, metaFrame(2, token))
        runCurrent()
        repeat(8) {
            rig.transport.emit(NotifyRole.DATA, metaFrame(2, token))
            runCurrent()
        }
        advanceTimeBy(2_301)
        runCurrent()
        assertEquals(1, rig.sink.batches.size)
        assertEquals(9, acks(rig.transport).size)
        assertEquals(1, rig.transport.writes.count { opcodeOf(it) == 0x16 })
        assertEquals(0, rig.transport.writes.count { opcodeOf(it) == 0x14 })
        assertNull(rig.prefs.synced)
    }

    @Test
    fun gen4EndBeforeStartSatisfiesTheFirstResponseWatchdog() = runTest {
        val rig = rig()
        rig.link.pair(strap)
        runCurrent()
        rig.transport.emit(NotifyRole.DATA, metaFrame(2, token))
        runCurrent()
        advanceTimeBy(12_301)
        runCurrent()
        assertEquals(1, rig.transport.writes.count { opcodeOf(it) == 0x16 })
        assertEquals(1, acks(rig.transport).size)
        assertTrue(rig.sink.batches.single().first.isEmpty())
    }

    @Test
    fun aRejectedHistoryRequestRetriesWithoutWaitingForTheStartTimeout() = runTest {
        val rig = rig()
        rig.link.pair(strap)
        runCurrent()
        rig.transport.emit(NotifyRole.COMMANDS, Frames.wrap(byteArrayOf(0x24, 0, 0x16, 4, 0)))
        runCurrent()
        advanceTimeBy(3_001)
        runCurrent()
        assertEquals(2, rig.transport.writes.count { opcodeOf(it) == 0x16 })
        assertNull(rig.prefs.synced)
    }

    @Test
    fun aDeferredHistoryResponseKeepsTheRequestAndAcceptsTheBatch() = runTest {
        val rig = rig()
        rig.link.pair(strap)
        runCurrent()
        rig.transport.emit(NotifyRole.COMMANDS, Frames.wrap(byteArrayOf(0x24, 0, 0x16, 4, 2)))
        runCurrent()
        advanceTimeBy(3_001)
        runCurrent()
        assertEquals(0, rig.transport.writes.count { opcodeOf(it) == 0x14 })
        rig.transport.emit(NotifyRole.DATA, metaFrame(1))
        rig.transport.emit(NotifyRole.DATA, recordFrame())
        rig.transport.emit(NotifyRole.DATA, metaFrame(2, token))
        runCurrent()
        assertEquals(1, rig.transport.writes.count { opcodeOf(it) == 0x16 })
        assertEquals(1, acks(rig.transport).size)
    }

    @Test
    fun anUnrelatedHistoryResponseDoesNotRestartTheCurrentRequest() = runTest {
        val rig = rig()
        rig.link.pair(strap)
        runCurrent()
        rig.transport.emit(NotifyRole.COMMANDS, Frames.wrap(byteArrayOf(0x24, 0, 0x16, 3, 0)))
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(1, rig.transport.writes.count { opcodeOf(it) == 0x16 })
    }

    @Test
    fun aCompleteMarkerIsNotAcknowledged() = runTest {
        val diag = CountingRecorder()
        val rig = rig(diag)
        rig.link.pair(strap)
        runCurrent()
        rig.transport.emit(NotifyRole.DATA, metaFrame(1))
        rig.transport.emit(NotifyRole.DATA, metaFrame(3))
        runCurrent()
        assertTrue(rig.sink.batches.isEmpty())
        assertTrue(acks(rig.transport).isEmpty())
        assertEquals(false, rig.link.state.value.sync.draining)
        assertEquals(rig.time.now(), rig.prefs.synced)
        assertEquals(rig.time.now(), rig.link.state.value.lastSync)
        assertEquals(SyncOutcome.COMPLETED, rig.link.state.value.sync.lastOutcome)
        assertEquals(rig.time.now(), rig.link.state.value.sync.lastOutcomeAt)
        rig.transport.emit(NotifyRole.DATA, metaFrame(3))
        runCurrent()
        assertEquals(listOf(SyncOutcome.COMPLETED), diag.outcomes)
    }

    @Test
    fun aDropMidBatchDoesNotCommitAndTheNextConnectIsBackground() = runTest {
        val rig = rig()
        rig.link.pair(strap)
        runCurrent()
        rig.transport.emit(NotifyRole.DATA, metaFrame(1))
        rig.transport.emit(NotifyRole.DATA, recordFrame())
        runCurrent()
        rig.transport.down()
        runCurrent()
        assertTrue(rig.sink.batches.isEmpty())
        assertTrue(acks(rig.transport).isEmpty())
        assertEquals(SyncOutcome.DISCONNECTED, rig.link.state.value.sync.lastOutcome)
        assertNull(rig.link.state.value.lastSync)
        assertEquals(listOf(false, true), rig.transport.connects)
        assertEquals(LinkState.RECONNECTING, rig.link.state.value.link)
    }

    @Test
    fun silenceBeforeTheFirstStartRetriesTheDrain() = runTest {
        val rig = rig()
        rig.link.pair(strap)
        val historical = rig.transport.writes.count { opcodeOf(it) == 0x16 }
        advanceTimeBy(10_000)
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(historical, rig.transport.writes.count { opcodeOf(it) == 0x16 })
        assertEquals(1, rig.transport.writes.count { opcodeOf(it) == 0x14 })
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(historical + 1, rig.transport.writes.count { opcodeOf(it) == 0x16 })
        assertTrue(rig.sink.batches.isEmpty())
    }

    @Test
    fun theFirstStartDeadlineBeginsAfterTheHistoricalWriteCompletes() = runTest {
        val rig = rig()
        rig.transport.delayedOpcode = 0x16
        rig.transport.writeDelayMs = 7_500
        rig.link.pair(strap)
        runCurrent()
        advanceTimeBy(9_000)
        runCurrent()
        assertEquals(0, rig.transport.writes.count { opcodeOf(it) == 0x14 })
        assertTrue(rig.link.state.value.sync.draining)
    }

    @Test
    fun aManualSyncDoesNotInterruptADrainThatHasNotStarted() = runTest {
        val rig = rig()
        rig.link.pair(strap)
        runCurrent()
        val historical = rig.transport.writes.count { opcodeOf(it) == 0x16 }
        assertEquals(0, rig.transport.writes.count { opcodeOf(it) == 0x14 })
        repeat(5) { assertTrue(rig.link.syncNow() is Outcome.Ok) }
        runCurrent()
        assertEquals(0, rig.transport.writes.count { opcodeOf(it) == 0x14 })
        assertEquals(historical, rig.transport.writes.count { opcodeOf(it) == 0x16 })
        assertEquals(true, rig.link.state.value.sync.draining)
        assertEquals(0L, rig.link.state.value.sync.samplesPulled)
    }

    @Test
    fun aRetryAlsoReceivesAFullStartWindowAfterItsWriteCompletes() = runTest {
        val rig = rig()
        rig.transport.delayedOpcode = 0x16
        rig.transport.writeDelayMs = 7_500
        rig.link.pair(strap)
        runCurrent()
        advanceTimeBy(10_001)
        runCurrent()
        advanceTimeBy(2_301)
        runCurrent()
        advanceTimeBy(7_500)
        runCurrent()
        advanceTimeBy(9_000)
        runCurrent()
        assertEquals(1, rig.transport.writes.count { opcodeOf(it) == 0x14 })
        assertEquals(2, rig.transport.writes.count { opcodeOf(it) == 0x16 })
    }

    @Test
    fun aStartAllowsSixtySecondsForTheFirstRecords() = runTest {
        val rig = rig()
        rig.link.pair(strap)
        runCurrent()
        rig.transport.emit(NotifyRole.DATA, metaFrame(1))
        runCurrent()
        assertTrue(rig.link.state.value.sync.draining)

        advanceTimeBy(59_999)
        runCurrent()
        assertTrue(rig.link.state.value.sync.draining)
        assertEquals(0, rig.transport.writes.count { opcodeOf(it) == 0x14 })
        advanceTimeBy(2_301)
        runCurrent()
        advanceTimeBy(300)
        runCurrent()
        assertEquals(1, rig.transport.writes.count { opcodeOf(it) == 0x14 })
        assertNull(rig.link.state.value.lastSync)
    }

    @Test
    fun unansweredHistoryStopsAfterOneRetryAndLeavesTheLinkAvailableForManualRetry() = runTest {
        val rig = rig()
        rig.link.pair(strap)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertNull(rig.prefs.synced)
        assertNull(rig.link.state.value.lastSync)
        assertTrue(rig.link.state.value.sync.lastOutcome != SyncOutcome.COMPLETED)
        assertEquals(listOf(false), rig.transport.connects)
        assertEquals(LinkState.CONNECTED, rig.link.state.value.link)
        assertEquals(SyncOutcome.STALLED, rig.link.state.value.sync.lastOutcome)
        assertEquals(false, rig.link.state.value.sync.draining)
        assertEquals(2, rig.transport.writes.count { opcodeOf(it) == 0x16 })
        assertTrue(rig.link.syncNow() is Outcome.Ok)
        assertEquals(3, rig.transport.writes.count { opcodeOf(it) == 0x16 })
    }

    @Test
    fun aSlowDatabaseCommitIsNotAbortedByTheRadioIdleTimer() = runTest {
        val rig = rig()
        rig.link.pair(strap)
        runCurrent()
        rig.sink.gate = CompletableDeferred()
        rig.transport.emit(NotifyRole.DATA, metaFrame(1))
        rig.transport.emit(NotifyRole.DATA, recordFrame())
        rig.transport.emit(NotifyRole.DATA, metaFrame(2, token))
        runCurrent()
        advanceTimeBy(65_000)
        runCurrent()
        assertTrue(acks(rig.transport).isEmpty())
        assertEquals(0, rig.transport.writes.count { opcodeOf(it) == 0x14 })
        rig.sink.gate!!.complete(Unit)
        runCurrent()
        assertEquals(1, rig.sink.batches.size)
        assertEquals(1, acks(rig.transport).size)
    }

    @Test
    fun aFailedAckAutomaticallyRequestsTheUnacknowledgedHistoryAgain() = runTest {
        val rig = rig()
        rig.link.pair(strap)
        runCurrent()
        rig.transport.failOpcode = 0x17
        rig.transport.emit(NotifyRole.DATA, metaFrame(1))
        rig.transport.emit(NotifyRole.DATA, recordFrame())
        rig.transport.emit(NotifyRole.DATA, metaFrame(2, token))
        runCurrent()
        advanceTimeBy(3_601)
        runCurrent()
        assertEquals(1, rig.sink.batches.size)
        assertEquals(2, rig.transport.writes.count { opcodeOf(it) == 0x16 })
        assertNull(rig.prefs.synced)
    }

    @Test
    fun theStoredWindowIsPublishedBeforeAnySample() = runTest {
        val rig = rig()
        rig.link.pair(strap)
        runCurrent()
        rig.transport.emit(NotifyRole.COMMANDS, dataRangeFrame(1_750_000_000L, 1_750_003_600L))
        runCurrent()
        assertEquals(3_600L, rig.link.state.value.sync.strapStoredSeconds)
        assertEquals(0L, rig.link.state.value.sync.samplesPulled)
        assertEquals(true, rig.link.state.value.sync.draining)
    }

    @Test
    fun samplesCountAsSoonAsAChunkIsCommitted() = runTest {
        val rig = rig()
        rig.link.pair(strap)
        runCurrent()
        rig.transport.emit(NotifyRole.DATA, metaFrame(1))
        rig.transport.emit(NotifyRole.DATA, heartFrame())
        runCurrent()
        assertEquals(1L, rig.link.state.value.sync.samplesBuffered)
        assertEquals(0L, rig.link.state.value.sync.samplesPulled)
        repeat(HISTORY_FLUSH_EVERY - 1) { index ->
            rig.transport.emit(NotifyRole.DATA, heartFrame())
            if (index % 50 == 49) runCurrent()
        }
        runCurrent()
        assertEquals(HISTORY_FLUSH_EVERY.toLong(), rig.link.state.value.sync.samplesPulled)
        assertEquals(0L, rig.link.state.value.sync.samplesBuffered)
        assertEquals(true, rig.link.state.value.sync.draining)
    }

    @Test
    fun openingTheAppAsksForHistoryAgainAfterAMinute() = runTest {
        val rig = rig()
        rig.link.pair(strap)
        runCurrent()
        rig.transport.emit(NotifyRole.DATA, metaFrame(3))
        runCurrent()
        assertEquals(false, rig.link.state.value.sync.draining)
        val historical = rig.transport.writes.count { opcodeOf(it) == 0x16 }
        rig.time.advance(Duration.ofSeconds(60))
        assertTrue(rig.link.setLiveHeartRate(true) is Outcome.Ok)
        runCurrent()
        assertEquals(historical + 1, rig.transport.writes.count { opcodeOf(it) == 0x16 })
        assertEquals(true, rig.link.state.value.sync.draining)
    }

    @Test
    fun liveHeartRateTurnsOffWhenNobodyNeedsIt() = runTest {
        val rig = rig()
        rig.link.pair(strap)
        runCurrent()
        // Held while the connect drain streams, then a new session forces live heart rate off.
        assertEquals(emptyList<Int>(), realtimeBodies(rig))
        rig.transport.emit(NotifyRole.DATA, metaFrame(3))
        runCurrent()
        assertEquals(listOf(0x00), realtimeBodies(rig))
        assertTrue(rig.link.setLiveHeartRate(true) is Outcome.Ok)
        runCurrent()
        assertTrue(rig.link.setLiveHeartRate(false) is Outcome.Ok)
        runCurrent()
        assertEquals(listOf(0x00, 0x01, 0x00), realtimeBodies(rig))
    }

    @Test
    fun scanPublishesHitsAndRejectsARadioThatIsOff() = runTest {
        val rig = rig()
        runCurrent()
        assertTrue(rig.link.startScan() is Outcome.Ok)
        rig.transport.hit(strap)
        runCurrent()
        assertEquals(listOf(strap), rig.link.scanResults.value)
        assertTrue(rig.link.scanning.value)
        rig.transport.bluetooth = false
        val denied = rig.link.startScan()
        assertTrue(denied is Outcome.Err)
        assertEquals(StrapError.BluetoothOff, ((denied as Outcome.Err).error as CoreError.Strap).error)
    }

    @Test
    fun pairWithoutPermissionDoesNotConnect() = runTest {
        val rig = rig()
        rig.transport.connectPermission = false
        val outcome = rig.link.pair(strap)
        assertTrue(outcome is Outcome.Err)
        assertEquals(StrapError.PermissionMissing, ((outcome as Outcome.Err).error as CoreError.Strap).error)
        assertTrue(rig.transport.connects.isEmpty())
        assertNull(rig.prefs.address)
    }

    private class CountingRecorder : SyncRecorder {
        val outcomes = mutableListOf<SyncOutcome>()
        override fun log(message: String) = Unit
        override fun outcome(outcome: SyncOutcome, pulled: Long) {
            outcomes += outcome
        }
        override fun samples(count: Int) = Unit
    }

    @Test
    fun completeDoesNotPublishSuccessUntilTheTailIsDurable() = runTest {
        val rig = rig()
        rig.link.pair(strap)
        runCurrent()
        rig.sink.gate = CompletableDeferred()
        rig.transport.emit(NotifyRole.DATA, heartFrame())
        rig.transport.emit(NotifyRole.DATA, metaFrame(3))
        runCurrent()
        advanceTimeBy(65_000)
        runCurrent()
        assertNull(rig.prefs.synced)
        assertTrue(acks(rig.transport).isEmpty())
        assertEquals(0, rig.transport.writes.count { opcodeOf(it) == 0x14 })
        rig.sink.gate!!.complete(Unit)
        runCurrent()
        assertEquals(1L, rig.link.state.value.sync.samplesPulled)
        assertEquals(SyncOutcome.COMPLETED, rig.link.state.value.sync.lastOutcome)
        assertEquals(rig.time.now(), rig.prefs.synced)
    }

    @Test
    fun gen4RecordsBeforeAnyStartAreSavedAndCompleteCommitsTheTail() = runTest {
        val rig = rig()
        rig.link.pair(strap)
        runCurrent()
        repeat(5) { rig.transport.emit(NotifyRole.DATA, heartFrame()) }
        runCurrent()
        // Buffered progress publishes the first record and then every ten records.
        assertEquals(1L, rig.link.state.value.sync.samplesBuffered)
        advanceTimeBy(11_000)
        runCurrent()
        assertEquals(0, rig.transport.writes.count { opcodeOf(it) == 0x14 })
        rig.transport.emit(NotifyRole.DATA, metaFrame(3))
        runCurrent()
        assertEquals(5L, rig.link.state.value.sync.samplesPulled)
        assertEquals(5, rig.sink.batches.single().first.size)
        assertEquals(SyncOutcome.COMPLETED, rig.link.state.value.sync.lastOutcome)
        assertEquals(rig.time.now(), rig.prefs.synced)
        assertTrue(acks(rig.transport).isEmpty())
    }

    @Test
    fun gen4CanSendConsecutiveBatchesWithoutStartMarkers() = runTest {
        val rig = rig()
        rig.link.pair(strap)
        runCurrent()
        repeat(2) { batch ->
            repeat(3) { rig.transport.emit(NotifyRole.DATA, heartFrame()) }
            rig.transport.emit(NotifyRole.DATA, metaFrame(2, token.copyOf().also { it[0] = batch.toByte() }))
            runCurrent()
        }
        rig.transport.emit(NotifyRole.DATA, metaFrame(3))
        runCurrent()
        assertEquals(6L, rig.link.state.value.sync.samplesPulled)
        assertEquals(2, acks(rig.transport).size)
        assertEquals(SyncOutcome.COMPLETED, rig.link.state.value.sync.lastOutcome)
    }

    private fun TestScope.rig(diag: SyncRecorder = SyncRecorder.None): Rig {
        val time = ManualTimeSource(Instant.parse("2026-10-12T00:00:00Z"), ZoneId.of("Pacific/Auckland"))
        val transport = FakeTransport()
        val sink = RecordingSink()
        val prefs = MemoryPrefs()
        val host = FakeHost()
        val link = StrapLink(
            transport = transport,
            queue = GattOperationQueue(Duration.ofSeconds(8)),
            ports = testPorts(sink, prefs),
            time = time,
            scope = backgroundScope,
            host = host,
            diag = diag,
        )
        return Rig(link, transport, sink, prefs, host, time)
    }

    private fun acks(transport: FakeTransport): List<ByteArray> =
        transport.writes.filter { opcodeOf(it) == 0x17 }

    private fun realtimeBodies(rig: Rig): List<Int> =
        rig.transport.writes.filter { opcodeOf(it) == 0x03 }.map { innerOf(it)[3].toInt() and 0xFF }

    private fun dataRangeFrame(oldest: Long, newest: Long): ByteArray {
        val body = ByteArray(65)
        body[0] = 1
        putU32(body, 33, oldest)
        putU32(body, 57, newest)
        val inner = ByteArray(5 + body.size)
        inner[0] = 0x24
        inner[2] = 0x22
        inner[4] = 1
        body.copyInto(inner, 5)
        return Frames.wrap(inner)
    }

    private fun heartFrame(): ByteArray {
        val inner = ByteArray(96)
        inner[0] = 0x2F
        inner[1] = 24
        inner[17] = 60
        return Frames.wrap(inner)
    }

    private fun putU32(into: ByteArray, offset: Int, value: Long) {
        into[offset] = (value and 0xFF).toByte()
        into[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        into[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        into[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private class Rig(
        val link: StrapLink,
        val transport: FakeTransport,
        val sink: RecordingSink,
        val prefs: MemoryPrefs,
        val host: FakeHost,
        val time: ManualTimeSource,
    )
}
