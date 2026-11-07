package dev.seon.slimmer

import android.net.Uri
import android.util.Base64
import java.security.MessageDigest

/**
 * Shared secret with the Mac, read from the pairing QR / code:
 * `slimmer://pair?v=1&k=<base64url 32 bytes>&r=<relay>&n=<mac name>&d=<host:port>`
 *
 * Derivations (identical on the Mac):
 *  - room id = hex(SHA256("slimmer/room" ‖ K))[0..32)
 *  - lan id  = hex(SHA256("slimmer/lan"  ‖ K))[0..16)
 *  - enc key = SHA256("slimmer/enc" ‖ K)
 */
class Pairing private constructor(
    private val secret: ByteArray,
    val relay: String?,
    val macName: String,
    val direct: String?,
    val raw: String,
) {
    val encKey: ByteArray = derive("slimmer/enc")
    val roomId: String = derive("slimmer/room").toHex().take(32)
    val lanId: String = derive("slimmer/lan").toHex().take(16)
    val lanServiceName: String get() = "slimmer-$lanId"
    val fingerprint: String = derive("slimmer/fp").toHex().uppercase().let { "${it.take(4)}-${it.substring(4, 8)}" }

    val relayRoomUrl: String? get() = relay?.let { "$it/v1/room/$roomId?role=phone" }
    val directUrl: String? get() = direct?.let { d -> if (d.contains(':')) "ws://$d/" else "ws://$d:47900/" }
    val relayHost: String? get() = relay?.let { Uri.parse(it).host }

    private fun derive(label: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(label.toByteArray() + secret)

    companion object {
        fun parse(text: String): Pairing? {
            val s = text.trim()
            val uri = runCatching { Uri.parse(s) }.getOrNull() ?: return null
            if (uri.scheme != "slimmer" || uri.host != "pair") return null
            val k = uri.getQueryParameter("k") ?: return null
            val key = runCatching {
                Base64.decode(k, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            }.getOrNull() ?: return null
            if (key.size != 32) return null
            return Pairing(
                secret = key,
                relay = uri.getQueryParameter("r")?.let(::normalizeRelay),
                macName = uri.getQueryParameter("n")?.takeIf { it.isNotBlank() } ?: "Mac",
                direct = uri.getQueryParameter("d")?.trim()?.takeIf { it.isNotEmpty() },
                raw = s,
            )
        }

        fun normalizeRelay(raw: String): String? {
            var s = raw.trim()
            if (s.isEmpty()) return null
            s = when {
                s.startsWith("https://") -> "wss://" + s.removePrefix("https://")
                s.startsWith("http://") -> "ws://" + s.removePrefix("http://")
                s.startsWith("wss://") || s.startsWith("ws://") -> s
                else -> "wss://$s"
            }
            return s.trimEnd('/')
        }
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
