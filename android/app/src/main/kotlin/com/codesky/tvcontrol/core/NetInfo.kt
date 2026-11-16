package com.codesky.tvcontrol.core

import java.net.Inet4Address
import java.net.NetworkInterface

object NetInfo {

    /** Best guess at the LAN address a phone on the same Wi-Fi can reach. */
    fun lanIp(): String? =
        NetworkInterface.getNetworkInterfaces()
            ?.toList()
            ?.filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual }.getOrDefault(false) }
            ?.flatMap { it.inetAddresses.toList() }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress

    fun baseUrl(port: Int): String {
        val ip = lanIp() ?: "0.0.0.0"
        return "http://$ip:$port"
    }
}
