package dev.rustdroid.ide

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.rustdroid.ide.ui.AppRoot
import dev.rustdroid.ide.ui.theme.RustDroidTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as RustDroidApp).container
        setContent {
            RustDroidTheme {
                AppRoot(container)
            }
        }
    }
}
