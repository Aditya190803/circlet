package app.circlet

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.circlet.ui.CircletRoot
import app.circlet.ui.debug.DebugHooks

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        DebugHooks.scenario(intent)?.let { AppContainer.demo = it }
        AppBootstrap.attach(this)
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        setContent { CircletRoot() }
    }

    @Deprecated("CompanionDeviceManager delivers the association through onActivityResult")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!AppBootstrap.onActivityResult(this, requestCode, resultCode)) {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onDestroy() {
        AppBootstrap.detach(this)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        AppBootstrap.onResume(this)
    }
}
