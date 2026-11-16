package com.codesky.tvcontrol.proxy

import java.io.ByteArrayOutputStream

/**
 * DNS for the tunnel, answered locally with "fake" addresses.
 *
 * Every A query gets a unique address from 198.18.0.0/15 (reserved for benchmarking,
 * so it never collides with a real host). When an app then connects to that address
 * the relay looks the name back up and asks the proxy for the *name*: the proxy does
 * the real resolution, so lookups never leak around it, geo-DNS answers match the
 * proxy's location, and the TV needs no working DNS of its own.
 *
 * AAAA gets an empty answer so apps stay on IPv4 (the tunnel does not carry IPv6).
 * Other query types are forwarded through the proxy by the caller.
 *
 * The table outlives a single tunnel session on purpose: apps cache answers for
 * [TTL_SECONDS], and switching proxies must not orphan addresses they still hold.
 */
internal object FakeDns {

    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    const val TTL_SECONDS = 30

    /** 198.18.0.0/15 */
    const val POOL_BASE = (198 shl 24) or (18 shl 16)
    private const val POOL_SIZE = 1 shl 17
    private const val POOL_MASK = -0x20000 // 255.254.0.0

    private val byName = HashMap<String, Int>()
    private val byIp = HashMap<Int, String>()
    private var next = 1

    fun isFake(ip: Int): Boolean = (ip and POOL_MASK) == POOL_BASE

    @Synchronized
    fun addressFor(name: String): Int {
        val key = name.lowercase().trimEnd('.')
        byName[key]?.let { return it }
        // Skip .0 and .255 of each /24; some stacks treat those oddly.
        while ((next and 0xFF) == 0 || (next and 0xFF) == 0xFF) next = (next + 1) % POOL_SIZE
        val ip = POOL_BASE or next
        next = (next + 1) % POOL_SIZE
        // The pool wrapped: the address is being reused, forget its old owner.
        byIp.remove(ip)?.let { byName.remove(it) }
        byName[key] = ip
        byIp[ip] = key
        return ip
    }

    @Synchronized
    fun nameFor(ip: Int): String? = byIp[ip]

    // ------------------------------------------------------------------ messages

    class Query(val id: Int, val flags: Int, val name: String, val type: Int, val questionEnd: Int)

    /** Parses the first question of a query; null if this is not something to answer. */
    fun parse(message: ByteArray): Query? {
        if (message.size < 12) return null
        val flags = Packets.u16(message, 2)
        if (flags and 0x8000 != 0) return null // a response, not a query
        if (Packets.u16(message, 4) < 1) return null
        val labels = ArrayList<String>()
        var at = 12
        while (true) {
            if (at >= message.size) return null
            val length = Packets.u8(message, at)
            if (length == 0) {
                at++
                break
            }
            if (length and 0xC0 != 0) return null // compression in a question: not from a sane client
            if (at + 1 + length > message.size) return null
            labels += String(message, at + 1, length, Charsets.US_ASCII)
            at += 1 + length
        }
        if (at + 4 > message.size) return null
        val type = Packets.u16(message, at)
        return Query(Packets.u16(message, 0), flags, labels.joinToString("."), type, at + 4)
    }

    /** A reply to [query] with zero or one A record. rcode 0 = NOERROR, 2 = SERVFAIL, 3 = NXDOMAIN. */
    fun answer(message: ByteArray, query: Query, address: Int?, rcode: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        val header = ByteArray(12)
        Packets.put16(header, 0, query.id)
        // QR + opcode copied + AA off + RD copied + RA + rcode
        val flags = 0x8000 or (query.flags and 0x7900) or 0x0080 or (rcode and 0x0F)
        Packets.put16(header, 2, flags)
        Packets.put16(header, 4, 1)
        Packets.put16(header, 6, if (address != null) 1 else 0)
        out.write(header)
        out.write(message, 12, query.questionEnd - 12)
        if (address != null) {
            val record = ByteArray(16)
            Packets.put16(record, 0, 0xC00C) // pointer to the question's name
            Packets.put16(record, 2, TYPE_A)
            Packets.put16(record, 4, 1) // IN
            Packets.put32(record, 6, TTL_SECONDS)
            Packets.put16(record, 10, 4)
            Packets.put32(record, 12, address)
            out.write(record)
        }
        return out.toByteArray()
    }

    /** A plain A query for [name], used to resolve the proxy's own hostname directly. */
    fun buildQuery(id: Int, name: String): ByteArray {
        val out = ByteArrayOutputStream()
        val header = ByteArray(12)
        Packets.put16(header, 0, id)
        Packets.put16(header, 2, 0x0100) // RD
        Packets.put16(header, 4, 1)
        out.write(header)
        for (label in name.trimEnd('.').split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        out.write(byteArrayOf(0, TYPE_A.toByte(), 0, 1))
        return out.toByteArray()
    }

    /** The first A record in a response, or null. */
    fun firstA(message: ByteArray): Int? {
        if (message.size < 12) return null
        val questions = Packets.u16(message, 4)
        val answers = Packets.u16(message, 6)
        var at = 12
        repeat(questions) {
            at = skipName(message, at) + 4
        }
        repeat(answers) {
            at = skipName(message, at)
            if (at + 10 > message.size) return null
            val type = Packets.u16(message, at)
            val length = Packets.u16(message, at + 8)
            at += 10
            if (type == TYPE_A && length == 4 && at + 4 <= message.size) return Packets.i32(message, at)
            at += length
        }
        return null
    }

    private fun skipName(message: ByteArray, start: Int): Int {
        var at = start
        while (at < message.size) {
            val length = Packets.u8(message, at)
            if (length == 0) return at + 1
            if (length and 0xC0 == 0xC0) return at + 2
            at += 1 + length
        }
        return at
    }
}
