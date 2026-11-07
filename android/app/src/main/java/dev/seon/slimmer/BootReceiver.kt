package dev.seon.slimmer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // SlimmerApp.onCreate already starts the service when paired; this receiver
        // exists so the process is launched at boot / after an update.
        if (Prefs.pairing != null) LinkService.start(context)
    }
}
