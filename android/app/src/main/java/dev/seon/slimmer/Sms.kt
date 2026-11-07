package dev.seon.slimmer

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads conversations from the system SMS/MMS provider and sends SMS.
 * MMS is included because Korean carriers deliver long messages (LMS) as MMS.
 * Sending goes through [SmsManager]; for non-default SMS apps Android itself records
 * the sent message in the provider, so it shows up in the phone's Messages app too.
 */
class SmsRepo(private val ctx: Context) {
    private val cr = ctx.contentResolver
    private val nameCache = HashMap<String, String?>()

    fun canRead() = granted(Manifest.permission.READ_SMS)
    fun canSend() = granted(Manifest.permission.SEND_SMS)
    private fun canContacts() = granted(Manifest.permission.READ_CONTACTS)
    private fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    private class Latest(val date: Long, val snippet: String, val address: String, val group: Boolean)

    fun threads(limit: Int): JSONArray {
        val latest = LinkedHashMap<Long, Latest>()

        cr.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            null, null, "${Telephony.Sms.DATE} DESC",
        )?.use { c ->
            while (c.moveToNext() && latest.size < limit) {
                val tid = c.getLong(0)
                if (latest.containsKey(tid)) continue
                latest[tid] = Latest(c.getLong(3), c.getString(2) ?: "", c.getString(1) ?: "", false)
            }
        }

        // MMS / LMS: newer than the latest SMS of the thread → it becomes the snippet.
        val mmsSeen = HashSet<Long>()
        cr.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(Telephony.Mms._ID, Telephony.Mms.THREAD_ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX),
            null, null, "${Telephony.Mms.DATE} DESC",
        )?.use { c ->
            while (c.moveToNext() && mmsSeen.size < limit) {
                val tid = c.getLong(1)
                if (!mmsSeen.add(tid)) continue
                val date = c.getLong(2) * 1000
                val cur = latest[tid]
                if (cur != null && cur.date >= date) continue
                val mid = c.getLong(0)
                val addrs = mmsAddresses(mid, c.getInt(3))
                latest[tid] = Latest(
                    date,
                    mmsText(mid).ifEmpty { "(멀티미디어 메시지)" },
                    addrs.joinToString(", ").ifEmpty { cur?.address ?: "" },
                    addrs.size > 1,
                )
            }
        }

        val unread = HashMap<Long, Int>()
        cr.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms.THREAD_ID), "${Telephony.Sms.READ}=0", null, null)
            ?.use { c -> while (c.moveToNext()) { val t = c.getLong(0); unread[t] = (unread[t] ?: 0) + 1 } }
        cr.query(Telephony.Mms.Inbox.CONTENT_URI, arrayOf(Telephony.Mms.THREAD_ID), "${Telephony.Mms.READ}=0", null, null)
            ?.use { c -> while (c.moveToNext()) { val t = c.getLong(0); unread[t] = (unread[t] ?: 0) + 1 } }

        val out = JSONArray()
        latest.entries.sortedByDescending { it.value.date }.take(limit).forEach { (tid, l) ->
            out.put(
                JSONObject()
                    .put("id", tid)
                    .put("address", l.address)
                    .put("name", if (l.group) groupName(l.address) else contactName(l.address) ?: JSONObject.NULL)
                    .put("snippet", l.snippet.take(200))
                    .put("date", l.date)
                    .put("unread", unread[tid] ?: 0)
                    .put("group", l.group)
            )
        }
        return out
    }

    fun messages(threadId: Long, limit: Int): JSONArray {
        data class Row(val id: String, val address: String, val body: String, val date: Long, val box: Int, val mms: Boolean)
        val rows = ArrayList<Row>()

        cr.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
            "${Telephony.Sms.THREAD_ID}=?", arrayOf(threadId.toString()), "${Telephony.Sms.DATE} DESC",
        )?.use { c ->
            while (c.moveToNext() && rows.size < limit) {
                rows += Row("s${c.getLong(0)}", c.getString(1) ?: "", c.getString(2) ?: "", c.getLong(3), c.getInt(4), false)
            }
        }
        var mmsCount = 0
        cr.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX),
            "${Telephony.Mms.THREAD_ID}=?", arrayOf(threadId.toString()), "${Telephony.Mms.DATE} DESC",
        )?.use { c ->
            while (c.moveToNext() && mmsCount < limit) {
                mmsCount++
                val mid = c.getLong(0)
                val box = c.getInt(2)
                val from = mmsAddresses(mid, box).firstOrNull() ?: ""
                rows += Row("m$mid", from, mmsText(mid).ifEmpty { "(멀티미디어 메시지)" }, c.getLong(1) * 1000, box, true)
            }
        }

        val out = JSONArray()
        rows.sortedBy { it.date }.takeLast(limit).forEach {
            out.put(
                JSONObject()
                    .put("id", it.id)
                    .put("threadId", threadId)
                    .put("address", it.address)
                    .put("body", it.body)
                    .put("date", it.date)
                    .put("box", it.box)
                    .put("mms", it.mms)
            )
        }
        return out
    }

    private fun mmsText(mid: Long): String {
        val sb = StringBuilder()
        cr.query(
            Uri.parse("content://mms/part"),
            arrayOf("_id", "ct", "text"),
            "mid=?", arrayOf(mid.toString()), null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == "text/plain") {
                    val t = c.getString(2)
                    if (t != null) {
                        if (sb.isNotEmpty()) sb.append('\n')
                        sb.append(t)
                    }
                }
            }
        }
        return sb.toString()
    }

    /** Sender for received MMS, recipients for sent MMS. */
    private fun mmsAddresses(mid: Long, box: Int): List<String> {
        val inbox = box == Telephony.Mms.MESSAGE_BOX_INBOX
        val out = ArrayList<String>()
        cr.query(
            Uri.parse("content://mms/$mid/addr"),
            arrayOf("address", "type"),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val a = c.getString(0) ?: continue
                if (a == "insert-address-token") continue
                val type = c.getInt(1)
                // 137 = FROM, 151 = TO, 130 = CC
                if (inbox && type == 137) out.add(0, a)
                else if (!inbox && (type == 151 || type == 130)) out.add(a)
            }
        }
        return out.distinct()
    }

    private fun groupName(addresses: String): String =
        addresses.split(", ").joinToString(", ") { contactName(it) ?: it }

    fun contactName(address: String): String? {
        if (address.isBlank() || !canContacts()) return null
        nameCache[address]?.let { return it }
        if (nameCache.containsKey(address)) return null
        val name = runCatching {
            cr.query(
                Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address)),
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        nameCache[address] = name
        return name
    }

    fun clearNameCache() = nameCache.clear()

    /**
     * Sends [body] to [address]. Returns an error message, or null when queued; the final
     * result arrives as an [ACTION_SENT] broadcast carrying `req`.
     */
    fun send(address: String, body: String, req: String): String? {
        if (!canSend()) return "SMS 보내기 권한이 없습니다"
        val sms = if (Build.VERSION.SDK_INT >= 31) {
            ctx.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        } ?: return "이 기기는 SMS를 보낼 수 없습니다"
        return try {
            val parts = sms.divideMessage(body)
            val sent = ArrayList<PendingIntent?>(parts.size)
            for (i in parts.indices) sent.add(null)
            sent[parts.size - 1] = PendingIntent.getBroadcast(
                ctx, req.hashCode(),
                Intent(ACTION_SENT).setPackage(ctx.packageName).putExtra("req", req).putExtra("address", address),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            sms.sendMultipartTextMessage(address, null, parts, sent, null)
            null
        } catch (e: Exception) {
            e.message ?: "전송 실패"
        }
    }

    fun threadIdFor(address: String): Long? =
        runCatching { Telephony.Threads.getOrCreateThreadId(ctx, address) }.getOrNull()

    companion object {
        const val ACTION_SENT = "dev.seon.slimmer.SMS_SENT"
    }
}

/** Calls [onChange] (debounced) whenever the SMS/MMS database changes. */
class SmsWatcher(private val ctx: Context, private val onChange: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val fire = Runnable { onChange() }
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            handler.removeCallbacks(fire)
            handler.postDelayed(fire, 700)
        }
    }
    private var registered = false

    fun start() {
        if (registered) return
        runCatching {
            ctx.contentResolver.registerContentObserver(Uri.parse("content://mms-sms/"), true, observer)
            registered = true
        }
    }

    fun stop() {
        if (!registered) return
        ctx.contentResolver.unregisterContentObserver(observer)
        handler.removeCallbacks(fire)
        registered = false
    }
}
