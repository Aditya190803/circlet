package app.circlet

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.core.content.ContextCompat
import app.circlet.core.StepCsv
import app.circlet.core.StepReading
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private const val NUDGE_INTERVAL_MS = 5 * 60_000L
private const val BATCH_LATENCY_US = 5_000_000

/** Lets the first call through, then one call per [intervalMs]. */
internal class NudgeGate(private val intervalMs: Long) {
    private var last: Long? = null

    @Synchronized
    fun tryPass(nowMs: Long): Boolean {
        val before = last
        if (before != null && nowMs - before < intervalMs) return false
        last = nowMs
        return true
    }

    @Synchronized
    fun remainingMs(nowMs: Long): Long = last?.let { (intervalMs - (nowMs - it)).coerceAtLeast(0) } ?: 0
}

/**
 * Appends the phone's cumulative step counter to `filesDir/steps/<date>.csv` while the process lives.
 *
 * Registering with the sensor hub delivers the current value at once, which is the reading at start.
 * Refreshes are batched, with a trailing refresh even if the sensor stops sending readings.
 */
class PhoneStepRecorder(
    private val context: Context,
    private val onReadings: (LocalDate) -> Unit,
) : SensorEventListener {
    private val dir = File(context.filesDir, "steps")
    private val gate = NudgeGate(NUDGE_INTERVAL_MS)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val dirtyDates = linkedSetOf<LocalDate>()
    private var refreshPending = false
    private val refresh = Runnable {
        synchronized(this) {
            refreshPending = false
            gate.tryPass(SystemClock.elapsedRealtime())
            val dates = dirtyDates.toList()
            dirtyDates.clear()
            dates.forEach(onReadings)
        }
    }

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        if (enabled == (thread != null)) return
        val sensors = context.getSystemService(SensorManager::class.java)
        if (!enabled) {
            sensors?.unregisterListener(this)
            handler?.removeCallbacks(refresh)
            refresh.run()
            thread?.quitSafely()
            thread = null
            handler = null
            return
        }
        val counter = sensors?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return
        val worker = HandlerThread("circlet-steps").also { it.start() }
        val workerHandler = Handler(worker.looper)
        val registered = try {
            sensors.registerListener(this, counter, SensorManager.SENSOR_DELAY_NORMAL, BATCH_LATENCY_US, workerHandler)
        } catch (_: SecurityException) {
            false
        }
        if (registered) {
            handler = workerHandler
            thread = worker
        } else {
            worker.quitSafely()
        }
    }

    @Synchronized
    override fun onSensorChanged(event: SensorEvent) {
        if (thread == null) return
        if (event.values.isEmpty() || !event.values[0].isFinite() || event.values[0] < 0) return
        val ageMs = (SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1_000_000
        val reading = StepReading(System.currentTimeMillis() - ageMs, event.values[0].toLong())
        val zone = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(reading.epochMs).atZone(zone).toLocalDate()
        try {
            app.circlet.core.RecordingFiles.locked {
                dir.mkdirs()
                File(dir, StepCsv.fileName(date)).appendText(StepCsv.line(reading) + "\n")
            }
        } catch (error: IOException) {
            return
        }
        dirtyDates += date
        if (!refreshPending) {
            refreshPending = true
            handler?.postDelayed(refresh, gate.remainingMs(SystemClock.elapsedRealtime()))
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
}
