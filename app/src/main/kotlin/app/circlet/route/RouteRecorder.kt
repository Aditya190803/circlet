package app.circlet.route

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.HandlerThread
import app.circlet.core.LiveWorkout
import app.circlet.core.Route
import app.circlet.core.RoutePoint
import app.circlet.core.routeKind
import app.circlet.strap.bg.StrapService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Records GPS fixes for the running workout. One instance per process, so the recording
 * keeps going when the live screen is left; the foreground service keeps the process alive.
 */
class RouteRecorder private constructor(private val context: Context) {
    private val store = RouteStore(context)
    private val pointsState = MutableStateFlow<List<RoutePoint>>(emptyList())
    private val activeState = MutableStateFlow(false)
    private var session: String? = null
    private var live: StateFlow<LiveWorkout?>? = null
    private var thread: HandlerThread? = null
    private var listener: LocationListener? = null

    /** Every fix kept so far for the running workout. */
    val points: StateFlow<List<RoutePoint>> = pointsState.asStateFlow()
    val active: StateFlow<Boolean> = activeState.asStateFlow()

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Starts recording the workout in [flow]. False when the sport has no route, permission is missing or GPS is unavailable. */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(flow: StateFlow<LiveWorkout?>): Boolean {
        val workout = flow.value ?: return false
        if (workout.sport.routeKind() == null || !hasPermission()) return false
        if (activeState.value) return true
        val manager = context.getSystemService(LocationManager::class.java)
        if (!manager.allProviders.contains(LocationManager.GPS_PROVIDER)) return false
        val id = "live-${workout.startedAt.toEpochMilli()}"
        val looper = HandlerThread("circlet-route").also { it.start() }
        val fixes = object : LocationListener {
            override fun onLocationChanged(location: Location) = accept(location)

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
        }
        try {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, fixes, looper.looper)
        } catch (failed: RuntimeException) {
            looper.quitSafely()
            return false
        }
        session = id
        live = flow
        thread = looper
        listener = fixes
        pointsState.value = store.read(id)
        activeState.value = true
        StrapService.setLocationWanted(context, true)
        return true
    }

    /** Stops recording. The route is kept under [activityId], or discarded when it is null. */
    @Synchronized
    fun stop(activityId: String?) {
        val id = session ?: return
        listener?.let { context.getSystemService(LocationManager::class.java).removeUpdates(it) }
        thread?.quitSafely()
        listener = null
        thread = null
        session = null
        live = null
        activeState.value = false
        pointsState.value = emptyList()
        if (activityId != null) store.rename(id, activityId) else store.delete(id)
        StrapService.setLocationWanted(context, false)
    }

    @Synchronized
    private fun accept(location: Location) {
        val id = session ?: return
        val workout = live?.value
        if (workout == null) {
            stop(null)
            return
        }
        if (workout.paused) return
        val accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else Double.MAX_VALUE
        val point = RoutePoint(location.time, location.latitude, location.longitude, accuracy)
        if (point.accuracyM > Route.MAX_ACCURACY_M) return
        store.append(id, point)
        pointsState.value = pointsState.value + point
    }

    companion object {
        @Volatile
        private var instance: RouteRecorder? = null

        fun get(context: Context): RouteRecorder =
            instance ?: synchronized(this) {
                instance ?: RouteRecorder(context.applicationContext).also { instance = it }
            }
    }
}
