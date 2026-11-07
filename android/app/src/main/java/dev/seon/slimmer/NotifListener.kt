package dev.seon.slimmer

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Receives every notification on the phone and forwards it to the Mac through [LinkService].
 * Also executes actions coming back from the Mac (inline reply, mark as read, dismiss).
 */
class NotifListener : NotificationListenerService() {

    override fun onListenerConnected() {
        Hub.listener = this
        Hub.listenerConnected.value = true
        if (Prefs.pairing != null) LinkService.start(this)
        Hub.service?.onListenerReady()
    }

    override fun onListenerDisconnected() {
        if (Hub.listener === this) Hub.listener = null
        Hub.listenerConnected.value = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        Hub.service?.onNotificationPosted(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        Hub.service?.onNotificationRemoved(sbn.key)
    }

    fun active(): List<StatusBarNotification> =
        runCatching { activeNotifications?.toList() }.getOrNull().orEmpty()

    fun find(key: String): StatusBarNotification? = active().firstOrNull { it.key == key }

    /** Runs action [index] of notification [key], filling the inline-reply field with [text]. Returns an error or null. */
    fun runAction(key: String, index: Int, text: String?): String? {
        val sbn = find(key) ?: return "알림이 이미 사라졌습니다"
        val action = sbn.notification.actions?.getOrNull(index) ?: return "동작을 찾을 수 없습니다"
        val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        if (text != null) {
            val input = action.remoteInputs?.firstOrNull { it.allowFreeFormInput }
                ?: return "이 동작은 답장을 지원하지 않습니다"
            RemoteInput.addResultsToIntent(arrayOf(input), intent, Bundle().apply { putCharSequence(input.resultKey, text) })
            if (Build.VERSION.SDK_INT >= 28) RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
        }
        return try {
            action.actionIntent.send(this, 0, intent)
            null
        } catch (e: Exception) {
            e.message ?: "실행 실패"
        }
    }

    fun open(key: String): String? {
        val pi = find(key)?.notification?.contentIntent ?: return "열 수 있는 화면이 없습니다"
        return try {
            pi.send()
            null
        } catch (e: Exception) {
            e.message ?: "열기 실패"
        }
    }

    fun dismiss(key: String) {
        runCatching { cancelNotification(key) }
    }

    companion object {
        fun isEnabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(ctx, NotifListener::class.java)
            return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        /** Converts a notification into the wire format, or null if it should not be mirrored. */
        fun toJson(ctx: Context, sbn: StatusBarNotification): JSONObject? {
            val n = sbn.notification
            if (sbn.packageName == ctx.packageName) return null
            if (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0) return null
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
            val ex = n.extras
            val title = (ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                ?: ex.getCharSequence(Notification.EXTRA_TITLE))?.toString().orEmpty()
            val text = (lastMessage(ex)
                ?: ex.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
            if (title.isBlank() && text.isBlank()) return null

            val actions = JSONArray()
            n.actions?.forEachIndexed { i, a ->
                val reply = a.remoteInputs?.any { it.allowFreeFormInput } == true
                actions.put(JSONObject().put("i", i).put("title", a.title?.toString().orEmpty()).put("reply", reply))
            }
            return JSONObject()
                .put("key", sbn.key)
                .put("pkg", sbn.packageName)
                .put("app", appLabel(ctx, sbn.packageName))
                .put("title", title.take(300))
                .put("text", text.take(4000))
                .put("time", sbn.postTime)
                .put("actions", actions)
        }

        /** For MessagingStyle group chats: "sender: text" of the newest message. */
        private fun lastMessage(ex: Bundle): CharSequence? {
            if (ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) == null) return null
            @Suppress("DEPRECATION")
            val msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
            val last = msgs.lastOrNull() as? Bundle ?: return null
            val text = last.getCharSequence("text") ?: return null
            val sender = last.getCharSequence("sender")
            return if (sender != null) "$sender: $text" else text
        }

        private val labelCache = HashMap<String, String>()

        fun appLabel(ctx: Context, pkg: String): String = synchronized(labelCache) {
            labelCache.getOrPut(pkg) {
                runCatching {
                    val pm = ctx.packageManager
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                }.getOrDefault(pkg)
            }
        }

        /** App icon as a 64×64 PNG, base64. */
        fun iconPng(ctx: Context, pkg: String): String? = runCatching {
            val d = ctx.packageManager.getApplicationIcon(pkg)
            val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            d.setBounds(0, 0, 64, 64)
            d.draw(canvas)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }.getOrNull()
    }
}
