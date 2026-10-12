package app.circlet.core

import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Where the BLE link is. [UNPAIRED] means no strap is stored. */
enum class LinkState {
    UNPAIRED,
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
}

/**
 * History drain progress.
 * [samplesPulled] is the number of 1 Hz samples committed by the current or latest drain.
 * [draining] is true only while a drain is running.
 * [samplesBuffered] is samples received in the open batch and not committed yet.
 * [strapStoredSeconds] is newest minus oldest from the strap's data-range reply:
 * how much history the strap is holding, not how much of this drain is left, and not a percentage.
 * Null until that reply has been read.
 */
data class SyncProgress(
    val samplesPulled: Long,
    val draining: Boolean,
    val samplesBuffered: Long = 0,
    val strapStoredSeconds: Long? = null,
)

/** A typed strap failure. [detail] on [Rejected] is safe to show. */
sealed interface StrapError {
    data object BluetoothOff : StrapError
    data object PermissionMissing : StrapError
    data object BondFailed : StrapError
    data object LinkLost : StrapError
    data object TimedOut : StrapError
    data class Rejected(val detail: String) : StrapError
}

/**
 * What the device screen shows.
 *
 * [batteryPercent] is 0–100. [liveHeartRateBpm] is beats per minute while streaming,
 * or null when live heart rate is off. Zero would mean off-wrist.
 * [firmware] and [serial] come from the hello payload. [error] is null when the link is fine.
 */
data class StrapState(
    val link: LinkState = LinkState.UNPAIRED,
    val name: String? = null,
    val serial: String? = null,
    val firmware: String? = null,
    val batteryPercent: Double? = null,
    val charging: Boolean? = null,
    val onWrist: Boolean? = null,
    val liveHeartRateBpm: Int? = null,
    val sync: SyncProgress = SyncProgress(samplesPulled = 0, draining = false),
    val lastSync: Instant? = null,
    val error: StrapError? = null,
)

/**
 * A strap seen during a scan.
 * [address] is the Bluetooth address. [rssiDbm] is received signal strength in dBm.
 */
data class FoundStrap(
    val address: String,
    val name: String,
    val rssiDbm: Int,
)

/** Which haptic pattern [StrapController.buzz] should run. */
enum class Haptic {
    TEST,
    FIND,
}

/**
 * Scan, pair, sync, live heart rate, haptics, broadcast, and rename.
 * [events] carries wrist, battery, charging, double tap, and the high-frequency sync prompt.
 * [scanning] is true only while a scan is running. An empty [scanResults] list can mean
 * "none found" as well as "not scanning", so the UI reads both.
 */
interface StrapController {
    val state: StateFlow<StrapState>
    val scanning: StateFlow<Boolean>
    val scanResults: StateFlow<List<FoundStrap>>
    val events: Flow<StrapEvent>

    suspend fun startScan(): Outcome<Unit>

    suspend fun stopScan(): Outcome<Unit>

    suspend fun pair(strap: FoundStrap): Outcome<Unit>

    suspend fun forget(): Outcome<Unit>

    suspend fun syncNow(): Outcome<Unit>

    suspend fun setLiveHeartRate(enabled: Boolean): Outcome<Unit>

    suspend fun buzz(haptic: Haptic): Outcome<Unit>

    suspend fun setBroadcast(enabled: Boolean): Outcome<Unit>

    suspend fun rename(name: String): Outcome<Unit>
}
