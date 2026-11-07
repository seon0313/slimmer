package dev.seon.slimmer

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Foreground service (type `remoteMessaging`) that keeps the link to the Mac alive,
 * forwards notifications and serves SMS requests.
 */
class LinkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** All message handling runs on one thread, in order. */
    private val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private lateinit var link: Link
    private lateinit var sms: SmsRepo
    private lateinit var watcher: SmsWatcher
    private val iconsSent = HashSet<String>()
    private var lastBattery = -1
    private var lastCharging = false

    private val sentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val req = intent.getStringExtra("req") ?: return
            val address = intent.getStringExtra("address") ?: ""
            val code = resultCode
            val ok = code == Activity.RESULT_OK
            scope.launch(worker) {
                val body = JSONObject().put("req", req).put("ok", ok)
                if (ok) sms.threadIdFor(address)?.let { body.put("threadId", it) }
                else body.put("error", smsError(code))
                link.send("sms.result", body)
            }
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            if (pct == lastBattery && charging == lastCharging) return
            lastBattery = pct
            lastCharging = charging
            scope.launch(worker) { link.send("status", JSONObject().put("battery", pct).put("charging", charging)) }
        }
    }

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        private var current: Network? = null
        override fun onAvailable(network: Network) {
            if (current != null && current != network) link.reconnectNow()
            current = network
        }
        override fun onLost(network: Network) {
            if (current == network) current = null
            link.reconnectNow()
        }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground("Mac 연결 준비 중")
        Hub.service = this
        sms = SmsRepo(this)
        watcher = SmsWatcher(this) {
            if (Prefs.smsEnabled.value) scope.launch(worker) { link.send("sms.changed") }
        }
        link = Link(this, scope, ::handle) { scope.launch(worker) { sendHello() } }

        ContextCompat.registerReceiver(this, sentReceiver, IntentFilter(SmsRepo.ACTION_SENT), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(netCallback)

        scope.launch {
            Prefs.pairingFlow.collectLatest { p ->
                if (p == null) {
                    link.stop()
                    stopSelf()
                } else {
                    link.start(p)
                }
            }
        }
        scope.launch {
            Hub.link.collectLatest { s ->
                updateNotification(
                    when {
                        s.macOnline -> "${Prefs.pairing?.macName ?: "Mac"}에 연결됨 · ${s.route.label}"
                        s.route == Route.RELAY -> "릴레이 연결됨 · Mac 대기 중"
                        else -> "Mac 연결 대기 중"
                    }
                )
            }
        }
        // Re-announce when the user flips a toggle so the Mac refreshes its state.
        scope.launch { Prefs.smsEnabled.drop(1).collectLatest { launch(worker) { sendHello() } } }
        scope.launch { Prefs.forwardNotifs.drop(1).collectLatest { launch(worker) { sendHello(); sendSnapshot() } } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Prefs.pairing == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RECONNECT) link.reconnectNow()
        return START_STICKY
    }

    override fun onDestroy() {
        if (Hub.service === this) Hub.service = null
        link.stop()
        watcher.stop()
        runCatching { unregisterReceiver(sentReceiver) }
        runCatching { unregisterReceiver(batteryReceiver) }
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netCallback) }
        scope.cancel()
        worker.close()
        super.onDestroy()
    }

    // region Outbound

    private fun sendHello() {
        iconsSent.clear()
        sms.clearNameCache()
        if (sms.canRead() && Prefs.smsEnabled.value) watcher.start() else watcher.stop()
        val perms = JSONObject()
            .put("notif", NotifListener.isEnabled(this) && Prefs.forwardNotifs.value)
            .put("sms", sms.canRead() && Prefs.smsEnabled.value)
        val body = JSONObject()
            .put("device", deviceName())
            .put("perms", perms)
            .put("app", BuildConfigCompat.versionName(this))
        if (lastBattery >= 0) body.put("battery", lastBattery)
        link.send("hello", body)
    }

    private fun deviceName(): String =
        android.provider.Settings.Global.getString(contentResolver, android.provider.Settings.Global.DEVICE_NAME)
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"

    private fun sendIconIfNeeded(pkg: String) {
        if (!iconsSent.add(pkg)) return
        val png = NotifListener.iconPng(this, pkg) ?: return
        link.send("icon", JSONObject().put("pkg", pkg).put("png", png))
    }

    private fun sendSnapshot() {
        val listener = Hub.listener
        val items = JSONArray()
        if (listener != null && Prefs.forwardNotifs.value) {
            listener.active()
                .mapNotNull { sbn -> NotifListener.toJson(this, sbn)?.also { sendIconIfNeeded(sbn.packageName) } }
                .forEach { items.put(it) }
        }
        link.send("notif.snapshot", JSONObject().put("items", items))
    }

    fun onListenerReady() {
        scope.launch(worker) { if (link.isReady) sendSnapshot() }
    }

    fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!Prefs.forwardNotifs.value) return
        scope.launch(worker) {
            if (!link.isReady) return@launch
            val json = NotifListener.toJson(this@LinkService, sbn) ?: return@launch
            sendIconIfNeeded(sbn.packageName)
            link.send("notif.posted", JSONObject().put("n", json))
        }
    }

    fun onNotificationRemoved(key: String) {
        if (!Prefs.forwardNotifs.value) return
        scope.launch(worker) { link.send("notif.removed", JSONObject().put("key", key)) }
    }

    // endregion

    // region Inbound

    private fun handle(type: String, msg: JSONObject) {
        scope.launch(worker) {
            try {
                dispatch(type, msg)
            } catch (e: Exception) {
                Log.w(TAG, "handling $type", e)
                msg.optString("req").takeIf { it.isNotEmpty() }?.let { req ->
                    val resultType = if (type.startsWith("sms")) "sms.result" else "notif.result"
                    link.send(resultType, JSONObject().put("req", req).put("ok", false).put("error", e.message ?: "오류"))
                }
            }
        }
    }

    private fun dispatch(type: String, msg: JSONObject) {
        when (type) {
            "notif.sync" -> sendSnapshot()

            "notif.action" -> {
                val req = msg.optString("req")
                val text = if (msg.has("text")) msg.optString("text") else null
                val err = Hub.listener?.runAction(msg.getString("key"), msg.getInt("action"), text)
                    ?: if (Hub.listener == null) "알림 접근 권한이 꺼져 있습니다" else null
                link.send("notif.result", result(req, err))
            }

            "notif.open" -> {
                Hub.listener?.open(msg.getString("key"))
            }

            "notif.dismiss" -> Hub.listener?.dismiss(msg.getString("key"))

            "sms.threads.get" -> {
                if (!smsAllowed()) return
                link.send("sms.threads", JSONObject().put("threads", sms.threads(msg.optInt("limit", 80))))
            }

            "sms.messages.get" -> {
                if (!smsAllowed()) return
                val tid = msg.getLong("threadId")
                link.send(
                    "sms.messages",
                    JSONObject().put("threadId", tid).put("messages", sms.messages(tid, msg.optInt("limit", 150))),
                )
            }

            "sms.send" -> {
                val req = msg.optString("req")
                if (!Prefs.smsEnabled.value) {
                    link.send("sms.result", result(req, "폰에서 문자 연동이 꺼져 있습니다"))
                    return
                }
                val err = sms.send(msg.getString("address"), msg.getString("body"), req)
                if (err != null) link.send("sms.result", result(req, err))
                // On success the result arrives later through sentReceiver.
            }
        }
    }

    private fun smsAllowed(): Boolean {
        if (Prefs.smsEnabled.value && sms.canRead()) return true
        link.send("sms.threads", JSONObject().put("threads", JSONArray()))
        return false
    }

    private fun smsError(code: Int) = when (code) {
        android.telephony.SmsManager.RESULT_ERROR_NO_SERVICE -> "서비스 지역이 아닙니다"
        android.telephony.SmsManager.RESULT_ERROR_RADIO_OFF -> "비행기 모드이거나 무선이 꺼져 있습니다"
        android.telephony.SmsManager.RESULT_ERROR_NULL_PDU -> "메시지를 만들 수 없습니다"
        else -> "전송 실패 (코드 $code)"
    }

    private fun result(req: String, error: String?) =
        JSONObject().put("req", req).put("ok", error == null).apply { if (error != null) put("error", error) }

    // endregion

    // region Foreground notification

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle("Slimmer")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun startInForeground(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun updateNotification(text: String) {
        runCatching {
            getSystemService(android.app.NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    // endregion

    companion object {
        const val CHANNEL_ID = "link"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "LinkService"
        const val ACTION_RECONNECT = "dev.seon.slimmer.RECONNECT"

        fun start(ctx: Context, action: String? = null) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, LinkService::class.java).setAction(action))
            } catch (e: Exception) {
                // Android 12+ refuses foreground-service starts from the background in some
                // states; the service will be started next time the app or boot receiver runs.
                Log.w(TAG, "start", e)
            }
        }
    }
}

object BuildConfigCompat {
    fun versionName(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
}
