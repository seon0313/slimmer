package dev.seon.slimmer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Keeps one WebSocket to the Mac, trying in order:
 *  1. LAN (Bonjour) — same Wi-Fi / hotspot
 *  2. the Mac's direct address from the pairing code (Tailscale, port forward)
 *  3. the relay — works from any network (LTE/5G, other Wi-Fi)
 * and switches to LAN as soon as the Mac shows up locally.
 */
class Link(
    context: Context,
    private val scope: CoroutineScope,
    private val onMessage: (String, JSONObject) -> Unit,
    /** The Mac is reachable over a fresh connection: send `hello`. */
    private val onMacOnline: () -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val discovery = LanDiscovery(context)
    private val kick = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null
    private var channel: SecureChannel? = null

    @Volatile private var ws: WebSocket? = null
    @Volatile private var route = Route.NONE
    @Volatile private var macOnline = false

    val isReady: Boolean get() = ws != null && macOnline

    fun start(p: Pairing) {
        stop()
        channel = SecureChannel(p.encKey)
        discovery.onFound = { kick.trySend(Unit) }
        discovery.start(p.lanServiceName)
        job = scope.launch { loop(p) }
    }

    fun stop() {
        job?.cancel()
        job = null
        ws?.close(1000, "stop")
        ws = null
        discovery.stop()
        macOnline = false
        route = Route.NONE
        publish(running = false)
    }

    /** Network changed: drop the socket and retry immediately (LAN first). */
    fun reconnectNow() {
        discovery.restart()
        ws?.cancel()
        kick.trySend(Unit)
    }

    fun send(type: String, body: JSONObject = JSONObject()): Boolean {
        val c = channel ?: return false
        val w = ws ?: return false
        if (!macOnline) return false
        return w.send(c.seal(type, body).toByteString())
    }

    private suspend fun loop(p: Pairing) {
        var backoff = 1000L
        while (coroutineContext.isActive) {
            val candidates = buildList {
                discovery.found.value?.let { add(Route.LAN to it) }
                p.directUrl?.let { add(Route.DIRECT to it) }
                p.relayRoomUrl?.let { add(Route.RELAY to it) }
            }
            if (candidates.isEmpty()) {
                publish(detail = "Mac을 찾는 중 (같은 Wi-Fi 필요 — 릴레이 미설정)")
            }
            var connected = false
            for ((r, url) in candidates) {
                publish(connecting = true, detail = "${r.label} 연결 시도 중")
                val session = connect(r, url) ?: continue
                connected = true
                backoff = 1000L
                coroutineScope {
                    // While on a non-LAN route, hop over as soon as the Mac appears on the LAN.
                    val watcher = launch {
                        for (k in kick) {
                            if (r != Route.LAN && discovery.found.value != null) {
                                session.close(1000, "switching to lan")
                                break
                            }
                        }
                    }
                    session.closed.await()
                    watcher.cancel()
                }
                break
            }
            ws = null
            route = Route.NONE
            macOnline = false
            publish(detail = if (connected) "연결이 끊어졌습니다. 다시 연결 중…" else "Mac에 연결할 수 없습니다. ${backoff / 1000}초 후 재시도")
            if (!connected) {
                withTimeoutOrNull(backoff) { kick.receive() }
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    private class Session(val socket: WebSocket, val closed: CompletableDeferred<Unit>) {
        fun close(code: Int, reason: String) {
            socket.close(code, reason)
            // Don't wait for the peer's close frame forever.
            if (!closed.isCompleted) socket.cancel()
        }
    }

    private suspend fun connect(r: Route, url: String): Session? {
        val opened = CompletableDeferred<Boolean>()
        val closed = CompletableDeferred<Unit>()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.complete(true)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val msg = channel?.open(bytes.toByteArray()) ?: return
                onMessage(msg.first, msg.second)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Relay control frame: {"t":"peer","online":bool}
                val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
                if (obj.optString("t") != "peer" || webSocket !== ws) return
                val online = obj.optBoolean("online")
                val was = macOnline
                macOnline = online
                publish()
                if (online && !was) onMacOnline()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                opened.complete(false)
                closed.complete(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.d(TAG, "$r failed: ${t.message}")
                opened.complete(false)
                closed.complete(Unit)
            }
        }
        val socket = try {
            client.newWebSocket(Request.Builder().url(url).build(), listener)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "bad url $url", e)
            return null
        }
        val ok = withTimeoutOrNull(if (r == Route.LAN) 4000L else 10_000L) { opened.await() } ?: false
        if (!ok) {
            socket.cancel()
            return null
        }
        ws = socket
        route = r
        // On LAN / direct the other end *is* the Mac. On the relay we wait for a peer frame.
        if (r != Route.RELAY) {
            macOnline = true
            publish()
            onMacOnline()
        } else {
            publish()
        }
        return Session(socket, closed)
    }

    private fun publish(running: Boolean = true, connecting: Boolean = false, detail: String? = null) {
        val r = if (ws != null) route else Route.NONE
        Hub.link.value = LinkState(
            running = running,
            route = r,
            connecting = connecting,
            macOnline = macOnline && ws != null,
            detail = detail ?: when {
                ws == null -> ""
                macOnline -> "${r.label}로 연결됨"
                else -> "릴레이 연결됨 · Mac이 오프라인입니다"
            },
        )
    }

    companion object {
        private const val TAG = "Link"
    }
}
