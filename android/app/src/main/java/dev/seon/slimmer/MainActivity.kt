package dev.seon.slimmer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf

class MainActivity : ComponentActivity() {
    /** Bumped on every resume so the UI re-checks permissions granted in Settings. */
    private val resumeTick = mutableIntStateOf(0)
    private val incomingCode = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            SlimmerTheme {
                MainScreen(
                    resumeTick = resumeTick.intValue,
                    incomingCode = incomingCode.value,
                    onIncomingCodeHandled = { incomingCode.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
        if (Prefs.pairing != null) LinkService.start(this)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "slimmer") incomingCode.value = data.toString()
    }
}
