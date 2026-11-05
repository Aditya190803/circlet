package app.circlet.strap

import android.content.Context
import android.util.Log
import app.circlet.core.AlarmController
import app.circlet.core.Haptic
import app.circlet.core.StrapController
import app.circlet.core.StrapPorts
import app.circlet.core.SyncDiagnostics
import app.circlet.core.TimeSource
import app.circlet.core.WorkoutController
import app.circlet.strap.alarm.StrapAlarms
import app.circlet.strap.bg.AndroidHost
import app.circlet.strap.bg.StrapCompanion
import app.circlet.strap.bg.StrapService
import app.circlet.strap.diag.openSyncDiagnostics
import app.circlet.strap.link.AndroidGattTransport
import app.circlet.strap.link.GattOperationQueue
import app.circlet.strap.link.StrapLink
import app.circlet.strap.notify.StatusNote
import app.circlet.strap.workout.StrapWorkouts
import app.circlet.strap.workout.ZoneBuzzer
import java.time.Duration
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The controllers the application puts on its container.
 * [strap] is the Bluetooth link, [alarms] the on-strap wake alarm, [workouts] the live session.
 */
data class StrapBindings(
    val strap: StrapController,
    val alarms: AlarmController,
    val workouts: WorkoutController,
    val diagnostics: SyncDiagnostics,
)

/**
 * Opens the process-wide strap link.
 *
 * Call once from the application, with the storage ports and the clock.
 * A second call returns the same controllers. The foreground service, the
 * 15 minute watchdog, and presence observation start from here when a strap is paired.
 */
object StrapModule {
    private const val TAG = "StrapModule"
    private val gate = Any()

    @Volatile
    private var bindings: StrapBindings? = null

    fun open(context: Context, ports: StrapPorts, time: TimeSource): StrapBindings {
        bindings?.let { return it }
        return synchronized(gate) {
            bindings ?: boot(context.applicationContext, ports, time).also { bindings = it }
        }
    }

    private fun boot(context: Context, ports: StrapPorts, time: TimeSource): StrapBindings {
        val diagnostics = openSyncDiagnostics(context)
        // Serialize link state with UI commands and Android GATT callbacks; storage ports switch to IO.
        // This scope runs on the main thread, so a failure in one loop is logged instead of killing the app.
        val failures = CoroutineExceptionHandler { _, failure ->
            Log.e(TAG, "strap coroutine failed", failure)
            diagnostics.swallowed("strap", failure)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + failures)
        val note = StatusNote(context, ports.settings)
        StrapService.hooksNote = note
        val host = AndroidHost(context, note)
        val link = StrapLink(
            transport = AndroidGattTransport(context, diagnostics),
            queue = GattOperationQueue(Duration.ofSeconds(8)),
            ports = ports,
            time = time,
            scope = scope,
            host = host,
            diag = diagnostics,
        )
        val alarms = StrapAlarms(ports.prefs, time, ports.sleepStage, ports.doubleTap, link)
        val workouts = StrapWorkouts(
            ports.workouts, time, ports.liveLoad, link,
            ports.zoneBuzz,
            ZoneBuzzer { scope.launch { link.buzz(Haptic.TEST) } },
        )
        var configurationReady = false
        link.onSessionReady = {
            if (configurationReady) {
                alarms.onConnected()
                workouts.onConnected()
            }
        }
        link.onDoubleTap = { alarms.onDoubleTap() }
        link.onAlarmFired = { alarms.onFired() }
        scope.launch {
            ports.awaitConfiguration()
            alarms.load()
            workouts.restore()
            configurationReady = true
            alarms.watch(scope)
            link.configurationReady()
        }
        scope.launch {
            link.start()
            val address = ports.prefs.pairedAddress()
            if (!address.isNullOrBlank()) StrapCompanion.observeIfAssociated(context, address)
            // Retain a system wake that arrived before storage and the link were ready.
            StrapService.wakes.collect { if (it > 0) link.resumeBackground() }
        }
        return StrapBindings(link, alarms, workouts, diagnostics)
    }
}
