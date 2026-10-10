package app.circlet.strap.bg

import android.content.Context

/** Process-death copy of "a strap address is stored", for the boot receiver and the watchdog. */
internal object PairedFlag {
    private const val FILE = "circlet_strap"
    private const val KEY = "paired"
    private const val ADDRESS = "address"

    fun set(context: Context, address: String?) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY, !address.isNullOrBlank())
            .putString(ADDRESS, address)
            .apply()
    }

    fun isPaired(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun address(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(ADDRESS, null)
}
