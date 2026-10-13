package app.circlet.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

/** Recovery, sleep and strain for today. Tapping opens the app. */
class TodayWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        WidgetUpdater.start(context)
        WidgetUpdater.renderLast(context)
    }
}

/** Strap battery and connection. Tapping opens the app. */
class BatteryWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        WidgetUpdater.start(context)
        WidgetUpdater.renderLast(context)
    }
}
