package app.circlet

import android.app.Application

class CircletApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppBootstrap.onApplicationCreate(this)
    }
}
