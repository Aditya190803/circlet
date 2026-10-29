package app.circlet.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import app.circlet.AppContainer
import app.circlet.R
import app.circlet.core.LinkState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive

private const val MIN_GAP_MS = 60_000L

/**
 * Keeps the home-screen widgets current. [start] watches today's day and the strap battery
 * and link, and redraws at most once a minute. The providers call [renderLast] for the
 * periodic update and when a widget is first placed.
 */
object WidgetUpdater {
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var today: TodayReading? = null

    @Volatile
    private var battery: BatteryReading? = null

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        scope.launch {
            while (isActive) {
                try {
                    watch(app)
                    return@launch
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) {
                    // A widget must not terminate the app if opening its data fails.
                    delay(MIN_GAP_MS)
                }
            }
        }
    }

    private suspend fun watch(app: Context) {
        val container = AppContainer.get(app)
        val zone = { container.time.zone() }
        val date = flow {
            while (true) {
                emit(container.time.now().atZone(zone()).toLocalDate())
                delay(MIN_GAP_MS)
            }
        }.distinctUntilChanged()
        @OptIn(ExperimentalCoroutinesApi::class)
        val reading = date.flatMapLatest { container.metrics.observeDay(it) }.map(TodayReading::of)
        val strap = container.strap.state.map(BatteryReading::of)
        combine(reading, strap) { day, power -> day to power }
            .distinctUntilChanged()
            .conflate()
            .collect { (day, power) ->
                today = day
                battery = power
                renderLast(app)
                delay(MIN_GAP_MS)
            }
    }

    fun renderLast(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val day = today ?: TodayReading(recovery = null, sleep = null, strain = null)
        val todayViews = RemoteViews(context.packageName, R.layout.widget_today).apply {
            setTextViewText(R.id.widget_recovery, day.recoveryText)
            setTextViewText(R.id.widget_sleep, day.sleepText)
            setTextViewText(R.id.widget_strain, day.strainText)
            setOnClickPendingIntent(R.id.widget_root, launchIntent(context))
        }
        manager.updateAppWidget(ComponentName(context, TodayWidgetProvider::class.java), todayViews)

        val power = battery ?: BatteryReading(percent = null, charging = null, link = LinkState.UNPAIRED)
        val batteryViews = RemoteViews(context.packageName, R.layout.widget_battery).apply {
            setTextViewText(R.id.widget_battery_percent, power.percentText)
            setTextViewText(R.id.widget_battery_status, power.statusText)
            setOnClickPendingIntent(R.id.widget_root, launchIntent(context))
        }
        manager.updateAppWidget(ComponentName(context, BatteryWidgetProvider::class.java), batteryViews)
    }

    internal fun launchIntent(context: Context): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
