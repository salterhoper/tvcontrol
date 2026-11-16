package com.codesky.tvcontrol.proxy

/**
 * The IPv4 routes the tunnel claims: everything except local, private and multicast
 * space. Expressed as a positive list (rather than excludeRoute, which needs API 33)
 * so the same code works on every TV this app supports.
 */
internal object Routes {

    private val EXCLUDED: List<Pair<String, Int>> = listOf(
        "0.0.0.0" to 8,
        "10.0.0.0" to 8, // home LANs; the tun's own /24 is added by the address itself
        "100.64.0.0" to 10, // carrier-grade NAT, Tailscale
        "127.0.0.0" to 8,
        "169.254.0.0" to 16,
        "172.16.0.0" to 12,
        "192.168.0.0" to 16,
        "224.0.0.0" to 4, // multicast: mDNS, SSDP, casting discovery
        "240.0.0.0" to 4, // reserved + broadcast
    )

    /** (network, prefix) pairs covering every public IPv4 address. */
    fun publicIpv4(): List<Pair<Int, Int>> {
        val excluded = EXCLUDED.map { (ip, prefix) ->
            val start = Packets.ipFromString(ip).toLong() and 0xFFFFFFFFL
            start to start + (1L shl (32 - prefix)) - 1
        }
        val out = ArrayList<Pair<Int, Int>>()
        fun walk(start: Long, prefix: Int) {
            val end = start + (1L shl (32 - prefix)) - 1
            if (excluded.any { (a, b) -> a <= start && end <= b }) return
            if (excluded.none { (a, b) -> a <= end && start <= b }) {
                out += start.toInt() to prefix
                return
            }
            walk(start, prefix + 1)
            walk(start + (1L shl (31 - prefix)), prefix + 1)
        }
        walk(0L, 0)
        return out
    }
}
