package com.codesky.tvcontrol.server

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.codesky.tvcontrol.core.Logging

/**
 * Advertises `_tvcontrol._tcp` on the LAN so clients that speak mDNS can find the TV
 * without typing an IP. The browser dashboard cannot use this (no mDNS in JS), it
 * exists for the native clients and for tooling like `avahi-browse`.
 */
class NsdAdvertiser(private val context: Context) {

    private var manager: NsdManager? = null
    private var listener: NsdManager.RegistrationListener? = null

    fun start(name: String, port: Int) {
        stop()
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "TV Control ($name)"
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val nsdManager = context.getSystemService(NsdManager::class.java) ?: return
        manager = nsdManager

        val registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Logging.i("mDNS: registered ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Logging.w("mDNS registration failed ($errorCode)")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Logging.w("mDNS unregistration failed ($errorCode)")
            }
        }
        listener = registrationListener

        runCatching { nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener) }
            .onFailure { Logging.w("mDNS unavailable", it) }
    }

    fun stop() {
        val nsdManager = manager
        val registrationListener = listener
        if (nsdManager != null && registrationListener != null) {
            runCatching { nsdManager.unregisterService(registrationListener) }
        }
        manager = null
        listener = null
    }

    private companion object {
        const val SERVICE_TYPE = "_tvcontrol._tcp."
    }
}
