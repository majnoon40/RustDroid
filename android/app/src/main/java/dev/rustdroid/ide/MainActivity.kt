package dev.rustdroid.ide

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import dev.rustdroid.ide.ui.AppRoot
import dev.rustdroid.ide.ui.theme.RustDroidTheme

class MainActivity : ComponentActivity() {

    /** Set from ACTION_VIEW .rs intents until AppRoot consumes it (navigates to the imported file). */
    private val pendingOpenRs = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        captureRsIntent(intent)
        val container = (application as RustDroidApp).container
        setContent {
            RustDroidTheme {
                AppRoot(
                    container,
                    pendingOpenRs = pendingOpenRs,
                    onPendingRsConsumed = { pendingOpenRs.value = null },
                )
            }
        }
    }

    // singleTask launch mode: a running instance receives VIEW intents here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        captureRsIntent(intent)
    }

    private fun captureRsIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val uri = intent.data ?: return
            if (uri.toString().endsWith(".rs") || intent.type == "text/x-rust") {
                pendingOpenRs.value = uri
            }
        }
    }
}
