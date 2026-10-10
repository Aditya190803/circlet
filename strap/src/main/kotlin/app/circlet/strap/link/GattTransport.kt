package app.circlet.strap.link

import app.circlet.core.CoreError
import app.circlet.core.FoundStrap
import app.circlet.core.Outcome
import app.circlet.core.StrapError
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/** Which notify characteristic a chunk arrived on. Each one has its own reassembler. */
enum class NotifyRole {
    COMMANDS,
    EVENTS,
    DATA,
}

data class GattNotice(val role: NotifyRole, val bytes: ByteArray)

enum class BondStatus {
    BONDED,
    FAILED,
    HELD,
}

sealed interface ConnectStatus {
    data object Up : ConnectStatus
    data object Off : ConnectStatus
    data object Denied : ConnectStatus
    data class Down(val status: Int) : ConnectStatus
}

sealed interface ConnectionEvent {
    data object AdapterOn : ConnectionEvent
    data object AdapterOff : ConnectionEvent
    data class Down(val status: Int) : ConnectionEvent
}

/**
 * The Bluetooth calls. Production uses [app.circlet.strap.link.AndroidGattTransport].
 * Tests use a fake, so the session and the queue never touch Android Bluetooth.
 */
interface GattTransport {
    val notifications: Flow<GattNotice>
    val connections: Flow<ConnectionEvent>
    val scanHits: Flow<FoundStrap>

    fun bluetoothEnabled(): Boolean

    fun hasScanPermission(): Boolean

    fun hasConnectPermission(): Boolean

    suspend fun startScan()

    suspend fun stopScan()

    suspend fun connect(address: String, autoConnect: Boolean): ConnectStatus

    suspend fun disconnect()

    suspend fun requestMtu(mtu: Int)

    /** True when the Gen 4 service and its four characteristics are present. */
    suspend fun discover(): Boolean

    suspend fun subscribe(role: NotifyRole)

    /** Write with response. Completes when the stack acknowledges the write. */
    suspend fun write(frame: ByteArray)

    fun bonded(address: String): Boolean

    suspend fun bond(address: String): BondStatus
}

/** 133 is the Android GATT failure WHOOP links return when another app already holds the strap. */
const val GATT_HELD_BY_OTHER = 133

fun connectFailure(status: ConnectStatus): Outcome<Unit>? = when (status) {
    ConnectStatus.Up -> null
    ConnectStatus.Off -> Outcome.Err(CoreError.Strap(StrapError.BluetoothOff))
    ConnectStatus.Denied -> Outcome.Err(CoreError.Strap(StrapError.PermissionMissing))
    is ConnectStatus.Down -> Outcome.Err(
        CoreError.Strap(
            if (status.status == GATT_HELD_BY_OTHER) {
                StrapError.Rejected("The strap is connected to another app")
            } else {
                StrapError.LinkLost
            },
        ),
    )
}

/** Spacing OpenStrap leaves between the session-open writes. */
val SESSION_GAP: Duration = Duration.ofMillis(120)

/** Clock instant the session writes. Tests pass the value; production passes [Instant] from the time source. */
fun epochSecond(now: Instant): Long = now.epochSecond
