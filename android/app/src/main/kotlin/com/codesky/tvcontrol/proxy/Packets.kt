package com.codesky.tvcontrol.proxy

/**
 * Just enough IPv4/TCP/UDP to rewrite addresses in place and build small UDP replies.
 * Everything works on the raw packet buffer read from the tun device; offsets are
 * from the start of the IP header.
 */
internal object Packets {

    const val PROTO_TCP = 6
    const val PROTO_UDP = 17

    fun u8(b: ByteArray, at: Int): Int = b[at].toInt() and 0xFF
    fun u16(b: ByteArray, at: Int): Int = (u8(b, at) shl 8) or u8(b, at + 1)
    fun i32(b: ByteArray, at: Int): Int =
        (u8(b, at) shl 24) or (u8(b, at + 1) shl 16) or (u8(b, at + 2) shl 8) or u8(b, at + 3)

    fun put16(b: ByteArray, at: Int, value: Int) {
        b[at] = (value ushr 8).toByte()
        b[at + 1] = value.toByte()
    }

    fun put32(b: ByteArray, at: Int, value: Int) {
        b[at] = (value ushr 24).toByte()
        b[at + 1] = (value ushr 16).toByte()
        b[at + 2] = (value ushr 8).toByte()
        b[at + 3] = value.toByte()
    }

    fun version(b: ByteArray): Int = u8(b, 0) ushr 4
    fun headerLength(b: ByteArray): Int = (u8(b, 0) and 0x0F) * 4
    fun totalLength(b: ByteArray): Int = u16(b, 2)
    fun protocol(b: ByteArray): Int = u8(b, 9)
    fun source(b: ByteArray): Int = i32(b, 12)
    fun destination(b: ByteArray): Int = i32(b, 16)
    fun setSource(b: ByteArray, ip: Int) = put32(b, 12, ip)
    fun setDestination(b: ByteArray, ip: Int) = put32(b, 16, ip)

    /** More-fragments set or a non-zero offset: not a whole datagram. */
    fun isFragment(b: ByteArray): Boolean = (u16(b, 6) and 0x3FFF) != 0

    /** Recomputes the IP header checksum and the TCP or UDP checksum of a whole packet. */
    fun fixChecksums(b: ByteArray) {
        val ihl = headerLength(b)
        val total = totalLength(b)
        put16(b, 10, 0)
        put16(b, 10, fold(sum(b, 0, ihl, 0L)))

        val l4 = total - ihl
        val checksumAt = when (protocol(b)) {
            PROTO_TCP -> ihl + 16
            PROTO_UDP -> ihl + 6
            else -> return
        }
        put16(b, checksumAt, 0)
        var pseudo = 0L
        pseudo += u16(b, 12) + u16(b, 14) + u16(b, 16) + u16(b, 18)
        pseudo += protocol(b)
        pseudo += l4
        var checksum = fold(sum(b, ihl, l4, pseudo))
        if (protocol(b) == PROTO_UDP && checksum == 0) checksum = 0xFFFF
        put16(b, checksumAt, checksum)
    }

    private fun sum(b: ByteArray, from: Int, length: Int, initial: Long): Long {
        var total = initial
        var i = from
        val end = from + length
        while (i + 1 < end) {
            total += u16(b, i)
            i += 2
        }
        if (i < end) total += u8(b, i) shl 8
        return total
    }

    private fun fold(value: Long): Int {
        var total = value
        while (total ushr 16 != 0L) total = (total and 0xFFFF) + (total ushr 16)
        return total.toInt().inv() and 0xFFFF
    }

    /** Builds a complete IPv4/UDP packet carrying [payload]. */
    fun udp(srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val total = 20 + 8 + payload.size
        val p = ByteArray(total)
        p[0] = 0x45
        put16(p, 2, total)
        put16(p, 6, 0x4000) // don't fragment
        p[8] = 64
        p[9] = PROTO_UDP.toByte()
        put32(p, 12, srcIp)
        put32(p, 16, dstIp)
        put16(p, 20, srcPort)
        put16(p, 22, dstPort)
        put16(p, 24, 8 + payload.size)
        System.arraycopy(payload, 0, p, 28, payload.size)
        fixChecksums(p)
        return p
    }

    fun ipToString(ip: Int): String =
        "${ip ushr 24 and 0xFF}.${ip ushr 16 and 0xFF}.${ip ushr 8 and 0xFF}.${ip and 0xFF}"

    fun ipFromString(text: String): Int {
        val parts = text.split('.').map { it.toInt() }
        return (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
    }

    fun ipFromBytes(b: ByteArray): Int = i32(b, 0)

    fun ipToBytes(ip: Int): ByteArray = ByteArray(4).also { put32(it, 0, ip) }
}
