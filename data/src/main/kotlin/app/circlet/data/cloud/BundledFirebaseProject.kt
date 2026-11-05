package app.circlet.data.cloud

import android.content.Context
import android.content.SharedPreferences
import app.circlet.core.CloudConfig
import java.io.FileNotFoundException
import org.json.JSONObject

internal object BundledFirebaseProject {
    fun read(context: Context): CloudConfig? {
        val json = try {
            context.assets.open("firebase-project.json").bufferedReader().use { JSONObject(it.readText()) }
        } catch (_: FileNotFoundException) { return null }
        if (!json.has("url")) return null
        return CloudConfig(json.getString("url"), json.getString("api_key")).validated()
    }

    /** An APK update must never move an existing account or its queued data to another project. */
    fun install(preferences: SharedPreferences, config: CloudConfig?, pending: Boolean): Boolean {
        if (config == null) return false
        val bundled = config.validated()
        val previous = preferences.getString("url", null)?.trimEnd('/')
        val protected = pending || preferences.contains("uid") || preferences.getBoolean("session_saved", false)
        if (previous != null && previous != bundled.databaseUrl && protected) return false
        check(preferences.edit().putString("url", bundled.databaseUrl).putString("api_key", bundled.apiKey).commit())
        return true
    }
}
