package app.circlet.ui.debug

import android.content.Intent
import androidx.compose.runtime.Composable
import app.circlet.core.fake.FakeScenario

/** Release builds carry no gallery and ignore demo requests. The debug source set holds the real one. */
object DebugHooks {
    val gallery: (@Composable (onBack: () -> Unit) -> Unit)? = null

    @Suppress("UNUSED_PARAMETER")
    fun scenario(intent: Intent?): FakeScenario? = null
}
