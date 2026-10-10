package app.circlet.ui.debug

import android.content.Intent
import androidx.compose.runtime.Composable
import app.circlet.core.fake.FakeScenario

/** What only debug builds have: the screen gallery, and a launch extra that picks the demo. */
object DebugHooks {
    val gallery: (@Composable (onBack: () -> Unit) -> Unit)? = { onBack -> Gallery(onBack) }

    /** `adb shell am start -n app.circlet/.MainActivity --es scenario UNPAIRED` */
    fun scenario(intent: Intent?): FakeScenario? =
        intent?.getStringExtra("scenario")?.let { name -> FakeScenario.entries.find { it.name == name } }
}
