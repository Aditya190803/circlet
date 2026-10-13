package app.circlet

import android.app.Application
import app.circlet.widget.WidgetUpdater

class CircletApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppBootstrap.onApplicationCreate(this)
        WidgetUpdater.start(this)
    }
}
