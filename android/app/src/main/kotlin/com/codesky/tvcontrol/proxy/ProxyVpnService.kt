package com.codesky.tvcontrol.proxy

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.codesky.tvcontrol.TvControlApp
import com.codesky.tvcontrol.core.Logging
import com.codesky.tvcontrol.ui.MainActivity
import java.io.DataInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Sends every app's IPv4 traffic on the TV through the active SOCKS5 server.
 *
 * How, without a native tun2socks: the kernel's own TCP stack does the TCP work.
 * A packet an app sends to D:dp arrives here from the tun device; it is rewritten to
 * look like D:sp -> tun:relayPort and written back, so the kernel delivers it to the
 * [TcpRelay]'s listening socket as an ordinary connection from D:sp. The relay looks
 * up the original D:dp, opens a SOCKS5 CONNECT, and pipes bytes. The relay's replies
 * come back out of the tun and are rewritten the other way. Only addresses, ports and
 * checksums are touched - no segmentation, retransmission or windowing of our own.
 *
 *  - DNS (UDP 53, to any server) is answered by [FakeDns]; the proxy resolves names.
 *  - Other UDP (QUIC, mostly) is dropped: apps fall back to TCP, which is proxied.
 *  - IPv6 is routed in and dropped, so nothing leaks around the proxy over v6.
 *  - LAN and multicast ranges are not routed in at all, so the dashboard, casting and
 *    discovery keep working while the tunnel is up.
 *
 * Our own sockets to the proxy are [protect]ed so they skip the tunnel.
 */
class ProxyVpnService : VpnService() {

    internal class Session(val remotePort: Int) {
        @Volatile var lastSeen: Long = System.currentTimeMillis()
        val pinned = AtomicInteger()
    }

    internal val nat = ConcurrentHashMap<Long, Session>()

    internal val bytesUp = AtomicLong()
    internal val bytesDown = AtomicLong()
    internal val dnsQueries = AtomicLong()
    @Volatile var startedAt: Long = 0L
        private set

    private var tun: ParcelFileDescriptor? = null
    private var output: FileOutputStream? = null
    private var relay: TcpRelay? = null
    private var reader: Thread? = null
    private val dnsPool = Executors.newFixedThreadPool(4) { r -> Thread(r, "proxy-dns").apply { isDaemon = true } }

    @Volatile var running = false
        private set

    @Volatile private var endpoint: InetSocketAddress? = null
    @Volatile private var endpointFor: String? = null

    private val store get() = (application as TvControlApp).container.proxies

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startTunnel()
        }
        return START_STICKY
    }

    @Synchronized
    private fun startTunnel() {
        if (running) return
        val proxy = store.active()
        if (proxy == null) {
            store.lastNote = "No SOCKS5 server is selected."
            stopSelf()
            return
        }

        val builder = Builder()
            .setSession("TV Control proxy")
            .setMtu(MTU)
            .addAddress(TUN_ADDRESS, 24)
            .addDnsServer(DNS_ADDRESS)
            // IPv6 is caught and dropped, see the class comment.
            .addAddress(TUN_ADDRESS_V6, 64)
            .addRoute("2000::", 3)
            .setBlocking(true)
            .setConfigureIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        Routes.publicIpv4().forEach { (address, prefix) -> builder.addRoute(Packets.ipToString(address), prefix) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

        val fd = runCatching { builder.establish() }
            .onFailure { Logging.w("VPN establish failed", it) }
            .getOrNull()
        if (fd == null) {
            store.lastNote = "Android did not hand over the VPN. Accept the prompt on the TV, then try again."
            stopSelf()
            return
        }

        val relay = runCatching { TcpRelay(this).also { it.start() } }
            .onFailure { Logging.w("Relay did not start", it) }
            .getOrNull()
        if (relay == null) {
            runCatching { fd.close() }
            store.lastNote = "The local relay could not start."
            stopSelf()
            return
        }

        tun = fd
        output = FileOutputStream(fd.fileDescriptor)
        this.relay = relay
        nat.clear()
        bytesUp.set(0)
        bytesDown.set(0)
        dnsQueries.set(0)
        startedAt = System.currentTimeMillis()
        running = true
        endpoint = null
        reader = Thread({ readLoop(FileInputStream(fd.fileDescriptor), relay.port) }, "proxy-tun").apply {
            isDaemon = true
            start()
        }
        store.lastNote = "Routing the TV through ${proxy.label}."
        Logging.i("Proxy tunnel up via ${proxy.host}:${proxy.port}, relay on ${relay.port}")
    }

    @Synchronized
    fun stopTunnel() {
        if (!running) return
        running = false
        relay?.close()
        relay = null
        // A blocked read on a tun fd is not woken by close(); one packet into the
        // tunnel makes it return so the thread sees running == false.
        runCatching {
            DatagramSocket().use { it.send(DatagramPacket(ByteArray(1), 1, InetAddress.getByName(WAKE_ADDRESS), 9)) }
        }
        reader?.join(500)
        reader = null
        runCatching { tun?.close() }
        tun = null
        output = null
        nat.clear()
        startedAt = 0L
        Logging.i("Proxy tunnel down")
    }

    /** The owner picked another server: new connections use it, old ones are cut so they reconnect. */
    fun onProxyChanged() {
        endpoint = null
        relay?.closeAll()
    }

    override fun onRevoke() {
        // Another VPN took over, or the owner switched ours off in Android's settings.
        // Respect that instead of fighting for the tunnel every minute.
        store.desired = false
        store.lastNote = "Android took the VPN away (another VPN app, or it was turned off in Settings)."
        stopTunnel()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        dnsPool.shutdownNow()
        if (instance === this) instance = null
        super.onDestroy()
    }

    val activeConnections: Int get() = relay?.active ?: 0
    val totalConnections: Long get() = relay?.total ?: 0L

    // ---------------------------------------------------------------- packets

    private fun readLoop(input: FileInputStream, relayPort: Int) {
        val buffer = ByteArray(MAX_PACKET)
        var lastPurge = System.currentTimeMillis()
        while (running) {
            val n = try {
                input.read(buffer)
            } catch (e: IOException) {
                if (running) Logging.w("tun read failed", e)
                break
            }
            if (!running) break
            if (n <= 0) continue
            runCatching { handle(buffer, n, relayPort) }
                .onFailure { Logging.w("Dropped a malformed packet", it) }

            val now = System.currentTimeMillis()
            if (now - lastPurge > PURGE_EVERY_MS) {
                lastPurge = now
                nat.entries.removeIf { (_, s) -> s.pinned.get() <= 0 && now - s.lastSeen > SESSION_IDLE_MS }
            }
        }
    }

    private fun handle(p: ByteArray, n: Int, relayPort: Int) {
        if (n < 20 || Packets.version(p) != 4) return
        val total = Packets.totalLength(p)
        val ihl = Packets.headerLength(p)
        if (total > n || ihl < 20 || total < ihl + 8 || Packets.isFragment(p)) return
        if (Packets.source(p) != TUN_IP) return
        when (Packets.protocol(p)) {
            Packets.PROTO_TCP -> tcp(p, total, ihl, relayPort)
            Packets.PROTO_UDP -> udp(p, total, ihl)
        }
    }

    private fun tcp(p: ByteArray, total: Int, ihl: Int, relayPort: Int) {
        if (total < ihl + 20) return
        val dst = Packets.destination(p)
        val srcPort = Packets.u16(p, ihl)
        val dstPort = Packets.u16(p, ihl + 2)

        if (srcPort == relayPort) {
            // relay -> app: from tun:relay to D:sp, becomes D:dp -> tun:sp
            val session = nat[key(dst, dstPort)] ?: return
            session.lastSeen = System.currentTimeMillis()
            Packets.setSource(p, dst)
            Packets.put16(p, ihl, session.remotePort)
            Packets.setDestination(p, TUN_IP)
        } else {
            // app -> remote: tun:sp to D:dp, becomes D:sp -> tun:relay
            if ((dst and SUBNET_MASK) == (TUN_IP and SUBNET_MASK)) return
            val k = key(dst, srcPort)
            var session = nat[k]
            if (session == null || session.remotePort != dstPort) {
                session = Session(dstPort)
                nat[k] = session
            }
            session.lastSeen = System.currentTimeMillis()
            Packets.setSource(p, dst)
            Packets.setDestination(p, TUN_IP)
            Packets.put16(p, ihl + 2, relayPort)
        }
        Packets.fixChecksums(p)
        write(p, total)
    }

    private fun udp(p: ByteArray, total: Int, ihl: Int) {
        val dst = Packets.destination(p)
        val srcPort = Packets.u16(p, ihl)
        val dstPort = Packets.u16(p, ihl + 2)
        if (dstPort != 53) return // QUIC and friends: dropped, apps fall back to TCP

        val payload = p.copyOfRange(ihl + 8, total)
        val query = FakeDns.parse(payload) ?: return
        dnsQueries.incrementAndGet()

        fun reply(message: ByteArray) {
            write(Packets.udp(dst, 53, TUN_IP, srcPort, message))
        }

        when (query.type) {
            FakeDns.TYPE_A -> reply(FakeDns.answer(payload, query, FakeDns.addressFor(query.name)))
            FakeDns.TYPE_AAAA -> reply(FakeDns.answer(payload, query, null))
            else -> dnsPool.execute {
                val answer = runCatching { forwardDns(payload) }.getOrNull()
                if (running) reply(answer ?: FakeDns.answer(payload, query, null, rcode = 2))
            }
        }
    }

    private fun write(packet: ByteArray, length: Int = packet.size) {
        val out = output ?: return
        synchronized(out) {
            runCatching { out.write(packet, 0, length) }
        }
    }

    /** Non-A/AAAA lookups (TXT, SRV, HTTPS, PTR...) go to a public resolver, through the proxy, over TCP. */
    private fun forwardDns(query: ByteArray): ByteArray? {
        val proxy = store.active() ?: return null
        openThroughProxy(proxy, UPSTREAM_DNS, 53, timeoutMs = 5000).use { socket ->
            val out = socket.getOutputStream()
            out.write(byteArrayOf((query.size ushr 8).toByte(), query.size.toByte()))
            out.write(query)
            out.flush()
            val input = DataInputStream(socket.getInputStream())
            val length = input.readUnsignedShort()
            return ByteArray(length).also { input.readFully(it) }
        }
    }

    // ------------------------------------------------------------- upstream

    /** A connected, handshaken SOCKS5 socket to [host]:[port] that bypasses the tunnel. */
    internal fun openThroughProxy(proxy: ProxyStore.Resolved, host: String, port: Int, timeoutMs: Int = 10_000): Socket {
        val socket = protectedSocket()
        try {
            socket.connect(proxyEndpoint(proxy), timeoutMs)
            socket.soTimeout = timeoutMs
            Socks5.connect(socket, proxy, host, port)
            socket.soTimeout = 0
            return socket
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }

    /** A not-yet-connected TCP socket that bypasses the tunnel. */
    internal fun protectedSocket(): Socket {
        val socket = Socket()
        try {
            // java.net.Socket only creates its OS socket on first use; protect() on a
            // socket with no file descriptor yet is a silent false. Setting an option
            // forces the descriptor into existence first.
            socket.tcpNoDelay = true
            if (!protect(socket)) {
                // Some builds still hand back no descriptor until the socket is bound.
                socket.bind(null)
                if (!protect(socket)) throw IOException("Android would not let the proxy socket skip the tunnel.")
            }
            return socket
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }

    internal fun proxyEndpoint(proxy: ProxyStore.Resolved): InetSocketAddress {
        val id = "${proxy.host}:${proxy.port}"
        val cached = endpoint
        if (cached != null && endpointFor == id) return cached
        val address = Socks5.ipv4Address(proxy.host) ?: resolveDirect(proxy.host)
        return InetSocketAddress(address, proxy.port).also {
            endpoint = it
            endpointFor = id
        }
    }

    /**
     * Resolves the proxy's own hostname while the tunnel is up. The system resolver
     * would ask our fake DNS and get a 198.18.x.x address back, so this sends a plain
     * query, on a protected socket, to the DNS servers of the real network.
     */
    private fun resolveDirect(host: String): InetAddress {
        val servers = underlyingDnsServers() + listOf("1.1.1.1", "8.8.8.8").map { InetAddress.getByName(it) }
        val query = FakeDns.buildQuery((System.nanoTime() and 0xFFFF).toInt(), host)
        DatagramSocket().use { socket ->
            protect(socket)
            socket.soTimeout = 2000
            for (server in servers.distinct()) {
                val answer = runCatching {
                    socket.send(DatagramPacket(query, query.size, server, 53))
                    val buffer = ByteArray(1500)
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    FakeDns.firstA(buffer.copyOf(packet.length))
                }.getOrNull()
                if (answer != null) return InetAddress.getByAddress(Packets.ipToBytes(answer))
            }
        }
        throw IOException("Could not resolve the proxy host $host.")
    }

    private fun underlyingDnsServers(): List<InetAddress> {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        return runCatching {
            @Suppress("DEPRECATION")
            cm.allNetworks
                .filter { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == false }
                .flatMap { cm.getLinkProperties(it)?.dnsServers.orEmpty() }
                .filterIsInstance<Inet4Address>()
        }.getOrDefault(emptyList())
    }

    companion object {
        const val ACTION_START = "com.codesky.tvcontrol.action.PROXY_START"
        const val ACTION_STOP = "com.codesky.tvcontrol.action.PROXY_STOP"

        private const val TUN_ADDRESS = "10.233.0.1"
        private const val DNS_ADDRESS = "10.233.0.2"
        private const val WAKE_ADDRESS = "10.233.0.3"
        private const val TUN_ADDRESS_V6 = "fd74:7663:7072::1"
        private const val UPSTREAM_DNS = "1.1.1.1"
        internal val TUN_IP = Packets.ipFromString(TUN_ADDRESS)
        private const val SUBNET_MASK = -0x100 // /24
        private const val MTU = 1500
        private const val MAX_PACKET = 32767
        private const val PURGE_EVERY_MS = 60_000L
        private const val SESSION_IDLE_MS = 5 * 60_000L

        @Volatile
        var instance: ProxyVpnService? = null
            private set

        fun running(): Boolean = instance?.running == true

        internal fun key(remoteIp: Int, appPort: Int): Long = ((remoteIp.toLong() and 0xFFFFFFFFL) shl 16) or appPort.toLong()

        fun start(context: Context) {
            runCatching { context.startService(Intent(context, ProxyVpnService::class.java).setAction(ACTION_START)) }
                .onFailure { Logging.w("Could not start the proxy tunnel", it) }
        }

        fun stop(context: Context) {
            instance?.stopTunnel()
            runCatching { context.startService(Intent(context, ProxyVpnService::class.java).setAction(ACTION_STOP)) }
        }
    }
}
