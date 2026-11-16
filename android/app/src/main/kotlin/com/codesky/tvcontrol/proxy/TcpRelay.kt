package com.codesky.tvcontrol.proxy

import com.codesky.tvcontrol.TvControlApp
import com.codesky.tvcontrol.core.Logging
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The listening end of the NAT trick in [ProxyVpnService]: every app TCP connection
 * lands here, looks like it comes from its real destination, and is piped through a
 * fresh SOCKS5 CONNECT.
 */
internal class TcpRelay(private val service: ProxyVpnService) {

    private val server = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getByAddress(Packets.ipToBytes(ProxyVpnService.TUN_IP)), 0), BACKLOG)
    }
    val port: Int = server.localPort

    private val threadId = AtomicInteger()
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "proxy-relay-${threadId.incrementAndGet()}").apply { isDaemon = true }
    }
    private val live = ConcurrentHashMap.newKeySet<Socket>()
    private val opened = AtomicLong()
    @Volatile private var closed = false

    val active: Int get() = live.size / 2
    val total: Long get() = opened.get()

    private val store get() = (service.application as TvControlApp).container.proxies

    fun start() {
        Thread({ acceptLoop() }, "proxy-accept").apply {
            isDaemon = true
            start()
        }
    }

    private fun acceptLoop() {
        while (!closed) {
            val client = try {
                server.accept()
            } catch (e: Exception) {
                if (!closed) Logging.w("Relay accept failed", e)
                break
            }
            pool.execute { handle(client) }
        }
    }

    private fun handle(client: Socket) {
        val remote = Packets.ipFromBytes(client.inetAddress.address)
        val session = service.nat[ProxyVpnService.key(remote, client.port)]
        if (session == null) {
            runCatching { client.close() }
            return
        }
        session.pinned.incrementAndGet()
        var upstream: Socket? = null
        var piping = false
        try {
            val host = if (FakeDns.isFake(remote)) {
                FakeDns.nameFor(remote) ?: return // a fake address from before the table was reset
            } else {
                Packets.ipToString(remote)
            }
            val proxy = store.active() ?: return
            client.tcpNoDelay = true
            live += client
            upstream = service.openThroughProxy(proxy, host, session.remotePort)
            live += upstream
            opened.incrementAndGet()
            if (closed) return
            piping = true

            val up = upstream
            val bothDone = AtomicInteger(2)
            val finish = {
                if (bothDone.decrementAndGet() == 0) {
                    runCatching { client.close() }
                    runCatching { up.close() }
                }
            }
            pool.execute {
                pipe(client.getInputStream(), up.getOutputStream(), service.bytesUp) {
                    runCatching { up.shutdownOutput() }
                }
                finish()
            }
            pipe(up.getInputStream(), client.getOutputStream(), service.bytesDown) {
                runCatching { client.shutdownOutput() }
            }
            finish()
        } catch (e: Exception) {
            Logging.d("Relay to port ${session.remotePort} failed: ${e.message}")
        } finally {
            if (!piping) {
                runCatching { client.close() }
                runCatching { upstream?.close() }
            }
            session.pinned.decrementAndGet()
            session.lastSeen = System.currentTimeMillis()
            live -= client
            upstream?.let { live -= it }
        }
    }

    private fun pipe(from: InputStream, to: OutputStream, counter: AtomicLong, onEof: () -> Unit) {
        val buffer = ByteArray(BUFFER)
        try {
            while (true) {
                val n = from.read(buffer)
                if (n < 0) break
                to.write(buffer, 0, n)
                counter.addAndGet(n.toLong())
            }
            onEof()
        } catch (_: Exception) {
            // Either side went away; [finish] closes both.
            onEof()
        }
    }

    /** Cuts every relayed connection; apps reconnect, through whatever proxy is active now. */
    fun closeAll() {
        live.toList().forEach { runCatching { it.close() } }
        live.clear()
    }

    fun close() {
        closed = true
        runCatching { server.close() }
        closeAll()
        pool.shutdownNow()
    }

    private companion object {
        const val BACKLOG = 512
        const val BUFFER = 32 * 1024
    }
}
