package dev.seon.slimmer

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address

/** Finds the paired Mac on the local network via Bonjour (`_slimmer._tcp`, name `slimmer-<lanId>`). */
class LanDiscovery(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val multicastLock = context.applicationContext.getSystemService(WifiManager::class.java)
        .createMulticastLock("slimmer-mdns").apply { setReferenceCounted(false) }

    private val _found = MutableStateFlow<String?>(null)
    /** `ws://host:port/` of the Mac, or null. */
    val found: StateFlow<String?> = _found

    var onFound: (() -> Unit)? = null
    private var listener: NsdManager.DiscoveryListener? = null
    private var target = ""

    @Synchronized
    fun start(serviceName: String) {
        stop()
        target = serviceName
        runCatching { multicastLock.acquire() }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery failed $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceName == target) resolve(info)
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                if (info.serviceName == target) _found.value = null
            }
        }
        listener = l
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            Log.w(TAG, "discoverServices", e)
            listener = null
        }
    }

    @Synchronized
    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        _found.value = null
        runCatching { multicastLock.release() }
    }

    fun restart() {
        val t = target
        if (t.isNotEmpty()) start(t)
    }

    private fun resolve(info: NsdServiceInfo) {
        @Suppress("DEPRECATION")
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "resolve failed $errorCode")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val addrs = if (Build.VERSION.SDK_INT >= 34) {
                    serviceInfo.hostAddresses
                } else {
                    @Suppress("DEPRECATION")
                    listOfNotNull(serviceInfo.host)
                }
                val a = (addrs.firstOrNull { it is Inet4Address } ?: addrs.firstOrNull()) ?: return
                val host = a.hostAddress?.substringBefore('%') ?: return
                val h = if (a is Inet4Address) host else "[$host]"
                _found.value = "ws://$h:${serviceInfo.port}/"
                onFound?.invoke()
            }
        })
    }

    companion object {
        const val SERVICE_TYPE = "_slimmer._tcp"
        private const val TAG = "LanDiscovery"
    }
}
