package app.circlet.strap.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import app.circlet.core.FoundStrap
import app.circlet.strap.diag.SyncRecorder
import app.circlet.strap.wire.StrapUuids
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers

// Ported from OpenStrap edge/lib/ble/ble_engine.dart — gen4 connect order
// (explicit bond, MTU 247, discover, subscribe commands then events then data,
// writes with response). Android BluetoothGatt is the only place those calls are made.
/**
 * One live GATT connection.
 * Callbacks arrive on the main looper. The operation queue is what keeps
 * writes, discovery, and descriptor writes from overlapping.
 */
@SuppressLint("MissingPermission")
internal class AndroidGattTransport(
    private val context: Context,
    private val diag: SyncRecorder = SyncRecorder.None,
) : GattTransport {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hits = MutableSharedFlow<FoundStrap>(extraBufferCapacity = 32)
    private val connectionEvents = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 16)
    private val notices = Channel<GattNotice>(Channel.UNLIMITED)

    override val scanHits: Flow<FoundStrap> = hits
    override val connections: Flow<ConnectionEvent> = connectionEvents
    override val notifications: Flow<GattNotice> = notices.receiveAsFlow()

    private var gatt: BluetoothGatt? = null
    @Volatile
    override var connectionGeneration: Long = 0
        private set
    private var wasUp = false
    private var connectWaiter: CompletableDeferred<ConnectStatus>? = null
    private var pending: kotlinx.coroutines.CancellableContinuation<Int>? = null
    private var pendingKey: GattCallbackKey? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (gatt !== this@AndroidGattTransport.gatt) return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        wasUp = true
                        finishConnect(ConnectStatus.Up)
                    } else {
                        finishConnect(ConnectStatus.Down(status))
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val waiting = connectWaiter
                    if (waiting != null && !waiting.isCompleted) {
                        finishConnect(ConnectStatus.Down(status))
                    }
                    if (wasUp) {
                        wasUp = false
                        connectionEvents.tryEmit(ConnectionEvent.Down(status))
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) = resume(gatt, GattOperation.MTU, null, status)

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) = resume(gatt, GattOperation.DISCOVER, null, status)

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) =
            resume(gatt, GattOperation.DESCRIPTOR, descriptor.characteristic.uuid, status)

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) =
            resume(gatt, GattOperation.WRITE, characteristic.uuid, status)

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (gatt !== this@AndroidGattTransport.gatt) return
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            emit(characteristic.uuid, value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (gatt !== this@AndroidGattTransport.gatt) return
            emit(characteristic.uuid, value)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val address = device.address ?: return
            val advertised = result.scanRecord?.deviceName?.takeIf { it.isNotBlank() }
                ?: device.name?.takeIf { it.isNotBlank() }
                ?: "WHOOP 4.0"
            hits.tryEmit(FoundStrap(address, advertised, result.rssi))
        }
    }

    init {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> connectionEvents.tryEmit(ConnectionEvent.AdapterOn)
                    BluetoothAdapter.STATE_OFF,
                    BluetoothAdapter.STATE_TURNING_OFF,
                    -> connectionEvents.tryEmit(ConnectionEvent.AdapterOff)
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    override fun bluetoothEnabled(): Boolean = adapter()?.isEnabled == true

    override fun hasScanPermission(): Boolean = granted(android.Manifest.permission.BLUETOOTH_SCAN)

    override fun hasConnectPermission(): Boolean = granted(android.Manifest.permission.BLUETOOTH_CONNECT)

    override suspend fun startScan() {
        val scanner = adapter()?.bluetoothLeScanner ?: error("Bluetooth is off")
        withContext(Dispatchers.Main.immediate) {
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(StrapUuids.SERVICE)).build()
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            scanner.startScan(listOf(filter), settings, scanCallback)
        }
    }

    override suspend fun stopScan() {
        val scanner = adapter()?.bluetoothLeScanner ?: return
        withContext(Dispatchers.Main.immediate) {
            try {
                scanner.stopScan(scanCallback)
            } catch (failed: SecurityException) {
                diag.swallowed("transport", failed)
            }
        }
    }

    override suspend fun connect(address: String, autoConnect: Boolean): ConnectStatus {
        if (!hasConnectPermission()) return ConnectStatus.Denied
        if (!bluetoothEnabled()) return ConnectStatus.Off
        return withContext(Dispatchers.Main.immediate) {
            shutdown(emitDown = false)
            connectionGeneration++
            val deferred = CompletableDeferred<ConnectStatus>()
            connectWaiter = deferred
            try {
                val radio = adapter() ?: return@withContext ConnectStatus.Off.also { connectWaiter = null }
                val device = try {
                    radio.getRemoteDevice(address)
                } catch (failed: IllegalArgumentException) {
                    diag.swallowed("transport", failed)
                    connectWaiter = null
                    return@withContext ConnectStatus.Down(0)
                }
                gatt = device.connectGatt(
                    context,
                    autoConnect,
                    callback,
                    BluetoothDevice.TRANSPORT_LE,
                    BluetoothDevice.PHY_LE_1M_MASK,
                    mainHandler,
                )
                if (gatt == null) {
                    connectWaiter = null
                    return@withContext ConnectStatus.Down(0)
                }
                val result = if (autoConnect) {
                    deferred.await()
                } else {
                    withTimeoutOrNull(DIRECT_CONNECT_MS) { deferred.await() }
                }
                if (result == null) {
                    shutdown(emitDown = false)
                    ConnectStatus.Down(8)
                } else {
                    result
                }
            } catch (failed: SecurityException) {
                diag.swallowed("transport", failed)
                shutdown(emitDown = false)
                ConnectStatus.Denied
            } finally {
                if (!coroutineContext.isActive) shutdown(emitDown = false)
            }
        }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.Main.immediate) { shutdown(emitDown = true) }
    }

    override suspend fun requestMtu(mtu: Int) {
        // A missing callback must not hold the queue. The session continues at the default MTU.
        withTimeoutOrNull(4_000) {
            val status = awaitStatus(GattOperation.MTU) { gatt?.requestMtu(mtu) == true }
            if (status != BluetoothGatt.GATT_SUCCESS) error("mtu $status")
        }
    }

    override suspend fun discover(): Boolean {
        val status = awaitStatus(GattOperation.DISCOVER) { gatt?.discoverServices() == true }
        if (status != BluetoothGatt.GATT_SUCCESS) return false
        val service = gatt?.getService(StrapUuids.SERVICE) ?: return false
        gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        return listOf(StrapUuids.CMD_TO, StrapUuids.CMD_FROM, StrapUuids.EVENTS, StrapUuids.DATA)
            .all { service.getCharacteristic(it) != null }
    }

    override suspend fun subscribe(role: NotifyRole) {
        val characteristic = characteristic(uuid(role)) ?: error("missing ${role.name}")
        if (gatt?.setCharacteristicNotification(characteristic, true) != true) error("notify ${role.name}")
        val descriptor = characteristic.getDescriptor(StrapUuids.CCCD) ?: error("missing cccd")
        val status = writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        if (status != BluetoothGatt.GATT_SUCCESS) error("cccd $status")
    }

    override suspend fun write(frame: ByteArray) {
        val characteristic = characteristic(StrapUuids.CMD_TO) ?: error("missing command characteristic")
        val status = writeCharacteristic(characteristic, frame)
        if (status != BluetoothGatt.GATT_SUCCESS) error("write $status")
    }

    override fun bonded(address: String): Boolean {
        val device = try {
            adapter()?.getRemoteDevice(address)
        } catch (failed: IllegalArgumentException) {
            diag.swallowed("transport", failed)
            null
        } catch (failed: SecurityException) {
            diag.swallowed("transport", failed)
            null
        }
        return device?.bondState == BluetoothDevice.BOND_BONDED
    }

    override suspend fun bond(address: String): BondStatus {
        if (!hasConnectPermission()) return BondStatus.FAILED
        val device = try {
            adapter()?.getRemoteDevice(address)
        } catch (failed: IllegalArgumentException) {
            diag.swallowed("transport", failed)
            null
        } ?: return BondStatus.FAILED
        if (device.bondState == BluetoothDevice.BOND_BONDED) return BondStatus.BONDED
        val result = CompletableDeferred<BondStatus>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val extra = bondedDevice(intent) ?: return
                if (!extra.address.equals(device.address, ignoreCase = true)) return
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                    BluetoothDevice.BOND_BONDED -> result.complete(BondStatus.BONDED)
                    BluetoothDevice.BOND_NONE -> {
                        val previous = intent.getIntExtra(
                            BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,
                            BluetoothDevice.ERROR,
                        )
                        if (previous == BluetoothDevice.BOND_BONDING) result.complete(BondStatus.FAILED)
                    }
                }
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        return try {
            val started = device.createBond()
            if (!started && device.bondState != BluetoothDevice.BOND_BONDING) {
                BondStatus.FAILED
            } else {
                withTimeoutOrNull(20_000) { result.await() } ?: BondStatus.FAILED
            }
        } catch (failed: SecurityException) {
            diag.swallowed("transport", failed)
            BondStatus.FAILED
        } finally {
            try {
                context.unregisterReceiver(receiver)
            } catch (failed: IllegalArgumentException) {
                diag.swallowed("transport", failed)
            }
        }
    }

    private fun finishConnect(status: ConnectStatus) {
        val waiter = connectWaiter
        connectWaiter = null
        if (waiter != null && !waiter.isCompleted) waiter.complete(status)
    }

    private fun shutdown(emitDown: Boolean) {
        val waiter = pending
        pending = null
        pendingKey = null
        if (waiter?.isActive == true) waiter.resumeWith(Result.failure(IllegalStateException("Bluetooth disconnected")))
        val up = wasUp
        wasUp = false
        finishConnect(ConnectStatus.Down(0))
        val current = gatt
        gatt = null
        if (current != null) {
            try {
                current.disconnect()
            } catch (failed: SecurityException) {
                diag.swallowed("transport", failed)
            }
            current.close()
        }
        if (emitDown && up) connectionEvents.tryEmit(ConnectionEvent.Down(0))
    }

    private suspend fun writeCharacteristic(characteristic: BluetoothGattCharacteristic, value: ByteArray): Int =
        awaitStatus(GattOperation.WRITE, characteristic.uuid) {
            val current = gatt ?: return@awaitStatus false
            if (Build.VERSION.SDK_INT >= 33) {
                current.writeCharacteristic(
                    characteristic,
                    value,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                characteristic.value = value
                @Suppress("DEPRECATION")
                current.writeCharacteristic(characteristic)
            }
        }

    private suspend fun writeDescriptor(descriptor: BluetoothGattDescriptor, value: ByteArray): Int =
        awaitStatus(GattOperation.DESCRIPTOR, descriptor.characteristic.uuid) {
            val current = gatt ?: return@awaitStatus false
            if (Build.VERSION.SDK_INT >= 33) {
                current.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = value
                @Suppress("DEPRECATION")
                current.writeDescriptor(descriptor)
            }
        }

    private suspend fun awaitStatus(operation: GattOperation, uuid: UUID? = null, start: () -> Boolean): Int = withContext(Dispatchers.Main.immediate) {
        val connection = gatt ?: error("Bluetooth disconnected")
        suspendCancellableCoroutine { continuation ->
            pending = continuation
            pendingKey = GattCallbackKey(connection, operation, uuid)
            continuation.invokeOnCancellation {
                mainHandler.post {
                    if (pending === continuation) {
                        pending = null
                        pendingKey = null
                    }
                    // A timed-out write can still complete later; never reuse that connection.
                    if (operation != GattOperation.MTU && gatt === connection) shutdown(emitDown = true)
                }
            }
            val started = try {
                start()
            } catch (failed: SecurityException) {
                pending = null
                pendingKey = null
                continuation.resumeWith(Result.failure(failed))
                return@suspendCancellableCoroutine
            }
            if (!started && continuation.isActive) {
                pending = null
                pendingKey = null
                continuation.resumeWith(Result.failure(IllegalStateException("Bluetooth did not start the operation")))
            }
        }
    }

    private fun resume(connection: BluetoothGatt, operation: GattOperation, uuid: UUID?, status: Int) {
        if (pendingKey?.matches(connection, operation, uuid) != true) return
        val continuation = pending ?: return
        pending = null
        pendingKey = null
        if (continuation.isActive) continuation.resumeWith(Result.success(status))
    }

    private fun emit(uuid: UUID, value: ByteArray) {
        val role = roleOf(uuid) ?: return
        notices.trySend(GattNotice(role, value.copyOf(), connectionGeneration))
    }

    private fun roleOf(uuid: UUID): NotifyRole? = when (uuid) {
        StrapUuids.CMD_FROM -> NotifyRole.COMMANDS
        StrapUuids.EVENTS -> NotifyRole.EVENTS
        StrapUuids.DATA -> NotifyRole.DATA
        else -> null
    }

    private fun uuid(role: NotifyRole): UUID = when (role) {
        NotifyRole.COMMANDS -> StrapUuids.CMD_FROM
        NotifyRole.EVENTS -> StrapUuids.EVENTS
        NotifyRole.DATA -> StrapUuids.DATA
    }

    private fun characteristic(uuid: UUID): BluetoothGattCharacteristic? =
        gatt?.getService(StrapUuids.SERVICE)?.getCharacteristic(uuid)

    private fun adapter() = context.getSystemService(BluetoothManager::class.java)?.adapter

    private fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun bondedDevice(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private companion object {
        const val DIRECT_CONNECT_MS = 20_000L
    }
}
