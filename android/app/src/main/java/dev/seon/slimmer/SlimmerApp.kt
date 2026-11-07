package dev.seon.slimmer

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SlimmerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(LinkService.CHANNEL_ID, "Mac 연결 상태", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Mac과의 연결을 유지하는 동안 표시됩니다"
                setShowBadge(false)
            }
        )
        if (Prefs.pairing != null) LinkService.start(this)
    }
}

/** Persistent settings. */
object Prefs {
    private lateinit var ctx: Context
    private val sp get() = ctx.getSharedPreferences("slimmer", Context.MODE_PRIVATE)

    private val _pairing = MutableStateFlow<Pairing?>(null)
    val pairingFlow: StateFlow<Pairing?> = _pairing
    val pairing: Pairing? get() = _pairing.value

    private val _forwardNotifs = MutableStateFlow(true)
    val forwardNotifs: StateFlow<Boolean> = _forwardNotifs
    private val _smsEnabled = MutableStateFlow(true)
    val smsEnabled: StateFlow<Boolean> = _smsEnabled

    fun init(context: Context) {
        ctx = context.applicationContext
        _pairing.value = sp.getString("pairing", null)?.let(Pairing::parse)
        _forwardNotifs.value = sp.getBoolean("forwardNotifs", true)
        _smsEnabled.value = sp.getBoolean("smsEnabled", true)
    }

    fun setPairing(p: Pairing?) {
        sp.edit().putString("pairing", p?.raw).apply()
        _pairing.value = p
    }

    fun setForwardNotifs(on: Boolean) {
        sp.edit().putBoolean("forwardNotifs", on).apply()
        _forwardNotifs.value = on
    }

    fun setSmsEnabled(on: Boolean) {
        sp.edit().putBoolean("smsEnabled", on).apply()
        _smsEnabled.value = on
    }
}

enum class Route(val label: String) { NONE("끊김"), LAN("같은 네트워크"), DIRECT("직접 주소"), RELAY("릴레이") }

data class LinkState(
    val running: Boolean = false,
    val route: Route = Route.NONE,
    val connecting: Boolean = false,
    val macOnline: Boolean = false,
    val detail: String = "",
)

/** Glue between the always-bound notification listener, the link service and the UI. */
object Hub {
    val link = MutableStateFlow(LinkState())
    val listenerConnected = MutableStateFlow(false)

    @Volatile var service: LinkService? = null
    @Volatile var listener: NotifListener? = null
}
