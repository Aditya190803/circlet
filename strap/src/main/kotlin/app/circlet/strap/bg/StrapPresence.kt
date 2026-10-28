package app.circlet.strap.bg

import android.app.Activity
import android.bluetooth.le.ScanFilter
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.util.Log
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference

// Ported from OpenStrap edge/android CompanionBridge.kt — associate one strap,
// then let the OS bind [StrapPresenceService] when that strap comes into range.
object StrapCompanion {
    private const val TAG = "StrapCompanion"
    const val REQUEST_CODE: Int = 0x431E

    private val activityRef = AtomicReference<WeakReference<Activity>?>(null)
    private val pendingMac = AtomicReference<String?>(null)

    /** The activity that can show the system association dialog. Cleared in [detach]. */
    fun attach(activity: Activity) {
        activityRef.set(WeakReference(activity))
        PairedFlag.address(activity)?.let { associateIfAttached(activity, it) }
    }

    fun detach(activity: Activity) {
        if (activityRef.get()?.get() === activity) activityRef.set(null)
    }

    fun associate(activity: Activity, address: String) {
        attach(activity)
        request(activity, address)
    }

    fun associateIfAttached(context: Context, address: String) {
        if (isAssociated(context, address)) {
            observe(context, address)
            return
        }
        val activity = activityRef.get()?.get() ?: return
        request(activity, address)
    }

    fun observeIfAssociated(context: Context, address: String) {
        if (isAssociated(context, address)) observe(context, address)
    }

    /**
     * Returns true when [requestCode] belongs to the association dialog.
     * A granted result starts presence observation for the address that was requested.
     */
    fun handleActivityResult(context: Context, requestCode: Int, resultCode: Int): Boolean {
        if (requestCode != REQUEST_CODE) return false
        val mac = pendingMac.getAndSet(null)
        if (resultCode == Activity.RESULT_OK && !mac.isNullOrBlank()) observe(context, mac)
        return true
    }

    fun isAssociated(context: Context, address: String): Boolean {
        val manager = manager(context) ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                manager.myAssociations.any { info ->
                    info.deviceMacAddress?.toString()?.equals(address, ignoreCase = true) == true
                }
            } else {
                @Suppress("DEPRECATION")
                manager.associations.any { it.equals(address, ignoreCase = true) }
            }
        } catch (failed: Exception) {
            Log.w(TAG, "associations: $failed")
            false
        }
    }

    fun observe(context: Context, address: String) {
        val manager = manager(context) ?: return
        try {
            manager.startObservingDevicePresence(address)
        } catch (failed: Exception) {
            Log.w(TAG, "observe: $failed")
        }
    }

    fun stopObserving(context: Context, address: String) {
        val manager = manager(context) ?: return
        try {
            manager.stopObservingDevicePresence(address)
        } catch (failed: Exception) {
            Log.w(TAG, "stopObserving: $failed")
        }
    }

    private fun request(activity: Activity, address: String) {
        val manager = manager(activity) ?: return
        if (isAssociated(activity, address)) {
            observe(activity, address)
            return
        }
        if (pendingMac.get() == address) return
        pendingMac.set(address)
        val filter = ScanFilter.Builder()
            .setDeviceAddress(address)
            .build()
        val device = BluetoothLeDeviceFilter.Builder().setScanFilter(filter).build()
        val association = AssociationRequest.Builder()
            .addDeviceFilter(device)
            .setSingleDevice(true)
            .build()
        val callback = object : CompanionDeviceManager.Callback() {
            override fun onDeviceFound(chooser: IntentSender) = launch(activity, chooser)

            override fun onAssociationPending(chooser: IntentSender) = launch(activity, chooser)

            override fun onAssociationCreated(info: AssociationInfo) {
                pendingMac.set(null)
                observe(activity, address)
            }

            override fun onFailure(error: CharSequence?) {
                pendingMac.set(null)
                Log.w(TAG, "association failed: $error")
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                manager.associate(association, activity.mainExecutor, callback)
            } else {
                @Suppress("DEPRECATION")
                manager.associate(association, callback, null)
            }
        } catch (failed: Exception) {
            pendingMac.set(null)
            Log.w(TAG, "associate: $failed")
        }
    }

    private fun launch(activity: Activity, chooser: IntentSender) {
        try {
            activity.startIntentSenderForResult(chooser, REQUEST_CODE, null, 0, 0, 0)
        } catch (failed: Exception) {
            pendingMac.set(null)
            Log.w(TAG, "chooser: $failed")
        }
    }

    private fun manager(context: Context): CompanionDeviceManager? =
        context.getSystemService(CompanionDeviceManager::class.java)
}

/** Bound by the OS when an associated strap appears. Restarts the foreground service. */
class StrapPresenceService : CompanionDeviceService() {
    @Deprecated("Deprecated in API 33")
    override fun onDeviceAppeared(address: String) {
        if (Build.VERSION.SDK_INT < 33) wake(address)
    }

    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        associationInfo.deviceMacAddress?.toString()?.let(::wake)
    }

    private fun wake(address: String) {
        if (PairedFlag.address(this)?.equals(address, ignoreCase = true) == true) {
            StrapService.start(this, reconnect = true)
        }
    }
}
