package com.codesky.tvcontrol.proxy

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket

/**
 * The client half of SOCKS5 (RFC 1928) with username/password auth (RFC 1929),
 * CONNECT only. Destinations go over as hostnames whenever one is known, so the proxy
 * resolves names and the TV's DNS never leaves through its own connection.
 */
object Socks5 {

    class Failure(message: String) : IOException(message)

    /**
     * Runs the handshake on an already-connected [socket] and asks for [host]:[port].
     * [host] may be a name or an IPv4 literal. Throws [Failure] with a readable reason.
     */
    fun connect(socket: Socket, proxy: ProxyStore.Resolved, host: String, port: Int) {
        val out = socket.getOutputStream()
        val input = DataInputStream(socket.getInputStream())

        // Greeting: offer "no auth", and user/pass when we have credentials.
        if (proxy.hasAuth) out.write(byteArrayOf(5, 2, 0, 2)) else out.write(byteArrayOf(5, 1, 0))
        out.flush()
        val version = input.readUnsignedByte()
        val method = input.readUnsignedByte()
        if (version != 5) throw Failure("That server does not speak SOCKS5 (answered version $version).")
        when (method) {
            0 -> Unit
            2 -> authenticate(out, input, proxy)
            0xFF -> throw Failure(
                if (proxy.hasAuth) "The proxy refused both login and anonymous access."
                else "The proxy needs a username and password.",
            )
            else -> throw Failure("The proxy asked for an auth method this app does not support ($method).")
        }

        // CONNECT request.
        val request = java.io.ByteArrayOutputStream()
        request.write(byteArrayOf(5, 1, 0))
        val literal = ipv4Literal(host)
        if (literal != null) {
            request.write(1)
            request.write(literal)
        } else {
            val name = host.toByteArray(Charsets.US_ASCII)
            if (name.size > 255) throw Failure("Hostname too long for SOCKS5.")
            request.write(3)
            request.write(name.size)
            request.write(name)
        }
        request.write(port ushr 8 and 0xFF)
        request.write(port and 0xFF)
        out.write(request.toByteArray())
        out.flush()

        if (input.readUnsignedByte() != 5) throw Failure("Bad reply from the proxy.")
        val reply = input.readUnsignedByte()
        input.readUnsignedByte() // reserved
        skipAddress(input)
        if (reply != 0) throw Failure(replyText(reply, host, port))
    }

    private fun authenticate(out: java.io.OutputStream, input: DataInputStream, proxy: ProxyStore.Resolved) {
        val user = (proxy.username ?: "").toByteArray(Charsets.UTF_8)
        val pass = (proxy.password ?: "").toByteArray(Charsets.UTF_8)
        if (user.size > 255 || pass.size > 255) throw Failure("Username or password too long for SOCKS5.")
        val packet = java.io.ByteArrayOutputStream()
        packet.write(1)
        packet.write(user.size)
        packet.write(user)
        packet.write(pass.size)
        packet.write(pass)
        out.write(packet.toByteArray())
        out.flush()
        input.readUnsignedByte()
        if (input.readUnsignedByte() != 0) throw Failure("The proxy rejected the username or password.")
    }

    private fun skipAddress(input: DataInputStream) {
        val length = when (input.readUnsignedByte()) {
            1 -> 4
            4 -> 16
            3 -> input.readUnsignedByte()
            else -> throw Failure("Bad address in the proxy's reply.")
        }
        input.readFully(ByteArray(length + 2))
    }

    private fun replyText(code: Int, host: String, port: Int): String = when (code) {
        1 -> "The proxy failed to reach $host:$port."
        2 -> "The proxy's rules do not allow $host:$port."
        3 -> "The proxy says the network is unreachable."
        4 -> "The proxy says $host is unreachable."
        5 -> "$host refused the connection on port $port."
        6 -> "The proxy timed out reaching $host."
        7 -> "The proxy does not support CONNECT."
        8 -> "The proxy does not support that address type."
        else -> "The proxy refused the request (code $code)."
    }

    /** Four bytes for a dotted IPv4 literal, null for anything else (never does a lookup). */
    fun ipv4Literal(host: String): ByteArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for (i in 0 until 4) {
            val value = parts[i].toIntOrNull() ?: return null
            if (value !in 0..255 || parts[i].isEmpty() || parts[i].length > 3) return null
            bytes[i] = value.toByte()
        }
        return bytes
    }

    fun ipv4Address(host: String): InetAddress? = ipv4Literal(host)?.let(InetAddress::getByAddress)

    /** Reads one HTTP response head + body (small, for the exit-IP probe). */
    fun readSmallResponse(input: InputStream, limit: Int = 8192): String {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(1024)
        while (buffer.size() < limit) {
            val n = input.read(chunk)
            if (n <= 0) break
            buffer.write(chunk, 0, n)
        }
        return buffer.toString(Charsets.UTF_8.name())
    }
}
