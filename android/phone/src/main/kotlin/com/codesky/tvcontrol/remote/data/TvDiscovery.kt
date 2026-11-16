package com.codesky.tvcontrol.remote.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Finds TVs running the server on the LAN via mDNS (`_tvcontrol._tcp.`, the type
 * the TV's NsdAdvertiser registers). The browser dashboard cannot do this - it is
 * the whole reason a native client is nicer: no typing an IP.
 */
class TvDiscovery(context: Context) {

    private val appContext = context.applicationContext

    fun discover(): Flow<List<DiscoveredTv>> = callbackFlow {
        val nsd = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsd == null) {
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }

        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val multicastLock = wifi?.createMulticastLock("tvremote-mdns")?.apply {
            setReferenceCounted(true)
            runCatching { acquire() }
        }

        val found = LinkedHashMap<String, DiscoveredTv>()
        val pending = ArrayDeque<NsdServiceInfo>()
        var resolving = false
        val lock = Any()

        fun publish() {
            synchronized(lock) { trySend(found.values.sortedBy { it.name.lowercase() }.toList()) }
        }

        fun friendlyName(raw: String?): String {
            val n = raw ?: "Android TV"
            // The advertiser uses "TV Control (Living Room)"; show the inner name.
            val match = Regex("\\((.+)\\)").find(n)
            return match?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: n
        }

        lateinit var pump: () -> Unit

        fun onResolved(info: NsdServiceInfo) {
            @Suppress("DEPRECATION")
            val address = info.host?.hostAddress
            val port = info.port
            if (address != null && port > 0) {
                val tv = DiscoveredTv(friendlyName(info.serviceName), address, port)
                synchronized(lock) { found[tv.address] = tv }
                publish()
            }
            synchronized(lock) { resolving = false }
            pump()
        }

        pump = {
            val next: NsdServiceInfo? = synchronized(lock) {
                if (resolving) null else pending.removeFirstOrNull()?.also { resolving = true }
            }
            if (next != null) {
                val resolveListener = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        synchronized(lock) { resolving = false }
                        pump()
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        onResolved(serviceInfo)
                    }
                }
                runCatching {
                    @Suppress("DEPRECATION")
                    nsd.resolveService(next, resolveListener)
                }.onFailure {
                    synchronized(lock) { resolving = false }
                    pump()
                }
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                runCatching { nsd.stopServiceDiscovery(this) }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onDiscoveryStarted(serviceType: String) {}

            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType?.contains("tvcontrol") == true ||
                    serviceInfo.serviceType?.contains(SERVICE_TYPE.trimEnd('.')) == true
                ) {
                    synchronized(lock) { pending.addLast(serviceInfo) }
                    pump()
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val name = friendlyName(serviceInfo.serviceName)
                synchronized(lock) {
                    val gone = found.values.filter { it.name == name }
                    gone.forEach { found.remove(it.address) }
                }
                publish()
            }
        }

        runCatching {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }.onFailure { trySend(emptyList()) }

        awaitClose {
            runCatching { nsd.stopServiceDiscovery(discoveryListener) }
            runCatching { if (multicastLock?.isHeld == true) multicastLock.release() }
        }
    }

    private companion object {
        const val SERVICE_TYPE = "_tvcontrol._tcp."
    }
}
