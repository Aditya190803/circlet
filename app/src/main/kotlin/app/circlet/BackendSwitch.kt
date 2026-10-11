package app.circlet

import android.content.Context

/**
 * Debug-only backend switch. The fake path stays available in debug builds
 * behind the `prefer_fake` flag, which defaults to off (the real backend).
 * It takes effect on the next process start: [AppContainer] binds once.
 */
object BackendSwitch {
    const val PREFS = "circlet_debug"
    private const val KEY_PREFER_FAKE = "prefer_fake"

    fun preferFake(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PREFER_FAKE, false)

    fun setPreferFake(context: Context, fake: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PREFER_FAKE, fake).apply()
    }
}
