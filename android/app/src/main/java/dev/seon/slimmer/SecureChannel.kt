package dev.seon.slimmer

import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * End-to-end encryption shared with the Mac (see macos SecureChannel.swift).
 * Frame = nonce(12) ‖ ciphertext ‖ tag(16), AES-256-GCM, AAD "p2m" (phone → Mac) / "m2p".
 * Plaintext is JSON `{"type", "id", "ts", ...}`; stale (>10 min) or repeated ids are dropped.
 */
class SecureChannel(encKey: ByteArray) {
    private val key = SecretKeySpec(encKey, "AES")
    private val rng = SecureRandom()
    private val sendAad = "p2m".toByteArray()
    private val recvAad = "m2p".toByteArray()
    private val seen = object : LinkedHashMap<String, Unit>(512, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > 4000
    }

    fun seal(type: String, body: JSONObject): ByteArray {
        body.put("type", type)
        body.put("id", UUID.randomUUID().toString())
        body.put("ts", System.currentTimeMillis())
        val nonce = ByteArray(12).also(rng::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        c.updateAAD(sendAad)
        return nonce + c.doFinal(body.toString().toByteArray())
    }

    @Synchronized
    fun open(frame: ByteArray): Pair<String, JSONObject>? {
        if (frame.size < 28) return null
        val plain = try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, frame, 0, 12))
            c.updateAAD(recvAad)
            c.doFinal(frame, 12, frame.size - 12)
        } catch (e: Exception) {
            return null
        }
        val obj = runCatching { JSONObject(String(plain)) }.getOrNull() ?: return null
        val id = obj.optString("id")
        val ts = obj.optLong("ts")
        if (id.isEmpty() || abs(System.currentTimeMillis() - ts) > 10 * 60 * 1000L) return null
        if (seen.containsKey(id)) return null
        seen[id] = Unit
        return obj.optString("type") to obj
    }
}
