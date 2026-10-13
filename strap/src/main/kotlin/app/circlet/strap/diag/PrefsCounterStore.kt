package app.circlet.strap.diag

import android.content.Context
import java.io.File

internal class PrefsCounterStore(context: Context) : CounterStore {
    private val prefs = context.getSharedPreferences("circlet_sync_diag", Context.MODE_PRIVATE)

    @Synchronized
    override fun get(key: String): Long = prefs.getLong(key, 0)

    @Synchronized
    override fun add(key: String, delta: Long) {
        prefs.edit().putLong(key, prefs.getLong(key, 0) + delta).apply()
    }
}

internal fun openSyncDiagnostics(context: Context): StoredSyncDiagnostics =
    StoredSyncDiagnostics(PrefsCounterStore(context), RotatingLog(File(context.filesDir, "diag")))
