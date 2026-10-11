package app.circlet

import android.content.Context
import app.circlet.core.StrapPorts
import app.circlet.data.DemoData
import java.time.LocalDate
import java.time.ZoneId

/**
 * Debug builds only: 21 days of 1 Hz history on first launch when no strap is
 * paired, so every screen can be exercised in an emulator. Seeding runs on a
 * worker after storage is ready; the pipeline recomputes the days behind it.
 * The release source set holds a no-op with the same signature. This directory
 * is compiled only in full debug builds (see `app/build.gradle.kts`), where the
 * debug-only [DemoData] is on the class path.
 */
object DemoSeed {
    private const val SEED = 0xC12C1E7L
    private const val KEY_SEEDED = "demo_seeded"

    suspend fun seedIfFresh(context: Context, ports: StrapPorts) {
        val prefs = context.getSharedPreferences(BackendSwitch.PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SEEDED, false)) return
        if (ports.prefs.pairedAddress() != null) return
        val zone = ZoneId.systemDefault()
        DemoData.seed(ports.samples, SEED, zone, LocalDate.now(zone), System.currentTimeMillis() / 1000)
        prefs.edit().putBoolean(KEY_SEEDED, true).apply()
    }
}
