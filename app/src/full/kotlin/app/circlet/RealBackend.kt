package app.circlet

import android.app.Activity
import android.app.Application
import android.content.Context
import android.util.Log
import app.circlet.analytics.engine.CircletDayEngine
import app.circlet.analytics.engine.circletInsightEngine
import app.circlet.analytics.strain.defaultZoneBounds
import app.circlet.core.SystemTimeSource
import app.circlet.core.ZoneDefaults
import app.circlet.data.CircletData
import app.circlet.data.DataModule
import app.circlet.strap.StrapBindings
import app.circlet.strap.StrapModule
import app.circlet.strap.bg.StrapCompanion
import app.circlet.strap.bg.StrapService
import app.circlet.route.RouteRecorder
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The real backend. This file is compiled only in full builds (`src/full` is not
 * a source directory when `-Pcirclet.coreOnly` is set), so it may import `:data`,
 * `:strap` and `:analytics` directly. [AppBootstrap] in `main` reaches it by name.
 *
 * Open order matters: storage first (the backup worker looks it up through
 * [CircletDataHolder][app.circlet.data.backup.CircletDataHolder] before WorkManager
 * can run), then the strap link with the storage ports. The strap module's
 * [AlarmController][app.circlet.core.AlarmController] and
 * [WorkoutController][app.circlet.core.WorkoutController] go on the container:
 * they arm the strap and stream heart rate, while the storage-only copies only
 * persist rows.
 */
object RealBackend {
    private const val TAG = "RealBackend"

    private val gate = Any()

    @Volatile
    private var data: CircletData? = null

    @Volatile
    private var strap: StrapBindings? = null

    @Volatile
    private var seedLaunched = false

    @Volatile
    private var stepRecorder: PhoneStepRecorder? = null

    private val workers = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Fast: Room builds lazily and the pipeline only launches a coroutine. */
    fun open(context: Context): AppContainer {
        ensure(context.applicationContext)
        runBlocking { data!!.awaitReady() }
        launchSeed(context.applicationContext)
        return container()
    }

    /**
     * Blocking: opens, waits for storage to be ready, then seeds demo data.
     * Call off the main thread; [AppBootstrap.onApplicationCreate] spawns one.
     */
    fun create(app: Application) {
        val context = app.applicationContext
        ensure(context)
        try {
            runBlocking {
                data!!.awaitReady()
                DemoSeed.seedIfFresh(context, data!!.ports)
            }
        } catch (error: Exception) {
            Log.w(TAG, "demo seed failed", error)
        }
    }

    fun attach(activity: Activity) = StrapCompanion.attach(activity)

    fun refreshStepRecording(context: Context) {
        ensure(context.applicationContext)
        val opened = data ?: return
        stepRecorder?.setEnabled(opened.settings.settings.value.countPhoneSteps)
    }

    fun detach(activity: Activity) = StrapCompanion.detach(activity)

    fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int): Boolean =
        StrapCompanion.handleActivityResult(activity, requestCode, resultCode)

    /**
     * Blocking. The caller ([ZoneChangeReceiver]) already runs off the main thread.
     * Re-marks stored days dirty in the new zone and nudges the pipeline, which
     * recomputes off the main thread.
     */
    fun onZoneChanged(app: Context) {
        ensure(app.applicationContext)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        try {
            runBlocking {
                data!!.noteZone(zone)
                data!!.refreshDates(listOf(today.minusDays(1), today))
            }
        } catch (error: Exception) {
            Log.w(TAG, "zone change failed", error)
        }
    }

    private fun ensure(context: Context) {
        data?.let { return }
        synchronized(gate) {
            if (data != null) return
            val opened = DataModule.open(context, CircletDayEngine(), circletInsightEngine(), SystemTimeSource)
            strap = StrapModule.open(context, opened.ports, SystemTimeSource)
            data = opened
            recordPhoneSteps(context, opened)
            RouteRecorder.locationService = { ctx, wanted -> StrapService.setLocationWanted(ctx, wanted) }
        }
    }

    /** The recorder lives as long as the process, the foreground service included. */
    private fun recordPhoneSteps(context: Context, opened: CircletData) {
        val recorder = PhoneStepRecorder(context) { date ->
            workers.launch { opened.refreshDates(listOf(date)) }
        }
        stepRecorder = recorder
        workers.launch {
            opened.settings.settings.map { it.countPhoneSteps }.distinctUntilChanged().collect(recorder::setEnabled)
        }
    }

    private fun launchSeed(context: Context) {
        if (seedLaunched) return
        synchronized(gate) {
            if (seedLaunched) return
            seedLaunched = true
        }
        workers.launch {
            try {
                data!!.awaitReady()
                DemoSeed.seedIfFresh(context, data!!.ports)
            } catch (error: Exception) {
                Log.w(TAG, "demo seed failed", error)
            }
        }
    }

    private fun container(): AppContainer {
        val data = data!!
        val strap = strap!!
        return AppContainer(
            time = SystemTimeSource,
            metrics = data.metrics,
            strap = strap.strap,
            alarms = strap.alarms,
            workouts = strap.workouts,
            journal = data.journal,
            profile = data.profile,
            settings = data.settings,
            portability = data.portability,
            onboarding = data.onboarding,
            zoneDefaults = ZoneDefaults { profile, on, resting -> defaultZoneBounds(profile, on, resting) },
            diagnostics = strap.diagnostics,
        )
    }
}
