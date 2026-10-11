package app.circlet

import android.app.Activity
import android.app.Application
import android.content.Context
import android.util.Log
import app.circlet.core.SystemTimeSource
import app.circlet.core.fake.FakeCirclet
import kotlin.concurrent.thread

/**
 * Entry point the application and its activities call to reach the real backend.
 *
 * This file lives in `main` so it must compile in `-Pcirclet.coreOnly` builds,
 * where `:data`, `:strap` and `:analytics` are not dependencies. Everything real
 * lives in [RealBackend] (`src/full`), which only full builds compile; here it is
 * reached through one reflective lookup. When that class is absent this object
 * behaves as the fake backend, which is also what [AppContainer.bind] falls back to.
 */
object AppBootstrap {
    private const val TAG = "AppBootstrap"
    private const val REAL = "app.circlet.RealBackend"

    /** False in core-only builds, where the real backend is not on the class path. */
    val bindsInApplication: Boolean = try {
        Class.forName(REAL)
        true
    } catch (_: ClassNotFoundException) {
        false
    }

    private val backend: Any? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        if (!bindsInApplication) null
        else runCatching { Class.forName(REAL).getField("INSTANCE").get(null) }.getOrNull()
    }

    /** Opens storage and the strap link off the main thread, then seeds demo data. */
    fun onApplicationCreate(app: Application) {
        if (backend == null) return
        thread(name = "circlet-open", isDaemon = true) {
            try {
                backend!!.javaClass.getMethod("create", Application::class.java).invoke(backend, app)
            } catch (error: Exception) {
                Log.w(TAG, "backend open failed", error)
            }
        }
    }

    /**
     * The real container. Opening is cheap (Room builds lazily, the pipeline only
     * launches a coroutine); demo seeding continues on a worker and the UI observes
     * the days as the recompute finishes. Falls back to the fake if the real
     * backend failed to load, so wiring can never crash launch.
     */
    fun openReal(context: Context): AppContainer {
        val real = backend
        if (real == null) return AppContainer.of(FakeCirclet(SystemTimeSource, AppContainer.demo), SystemTimeSource)
        try {
            return real.javaClass.getMethod("open", Context::class.java)
                .invoke(real, context.applicationContext) as AppContainer
        } catch (error: Exception) {
            Log.w(TAG, "real backend failed, using fake", error)
            return AppContainer.of(FakeCirclet(SystemTimeSource, AppContainer.demo), SystemTimeSource)
        }
    }

    /** Gives the CompanionDeviceManager association dialog an activity to show on. */
    fun attach(activity: Activity) {
        call("attach", Activity::class.java, activity)
    }

    fun detach(activity: Activity) {
        call("detach", Activity::class.java, activity)
    }

    /** True when the request code belonged to the strap association dialog. */
    fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int): Boolean {
        val real = backend ?: return false
        return try {
            real.javaClass.getMethod(
                "onActivityResult",
                Activity::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).invoke(real, activity, requestCode, resultCode) as Boolean
        } catch (error: Exception) {
            Log.w(TAG, "association result failed", error)
            false
        }
    }

    /**
     * Recomputes stored days in the new zone. The caller ([ZoneChangeReceiver])
     * already runs off the main thread, and this blocks until the dirty marks land.
     */
    fun onZoneChanged(app: Context) {
        call("onZoneChanged", Context::class.java, app.applicationContext)
    }

    private fun call(name: String, parameter: Class<*>, argument: Any) {
        val real = backend ?: return
        try {
            real.javaClass.getMethod(name, parameter).invoke(real, argument)
        } catch (error: Exception) {
            Log.w(TAG, "$name failed", error)
        }
    }
}
