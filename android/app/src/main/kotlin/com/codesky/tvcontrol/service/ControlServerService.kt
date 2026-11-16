package com.codesky.tvcontrol.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.codesky.tvcontrol.TvControlApp
import com.codesky.tvcontrol.core.AdBlock
import com.codesky.tvcontrol.core.EngineAutoEnable
import com.codesky.tvcontrol.core.Logging
import com.codesky.tvcontrol.core.NetInfo
import com.codesky.tvcontrol.proxy.ProxyTunnel
import com.codesky.tvcontrol.server.LanServer
import com.codesky.tvcontrol.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps the dashboard reachable while the user is doing something else on the TV.
 * Runs in the foreground with a notification that doubles as the remote's home
 * screen tile: it shows the address and the PIN, and it can stop the server.
 */
class ControlServerService : Service() {

    private lateinit var server: LanServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var retryJob: Job? = null

    private val container get() = (application as TvControlApp).container

    val isRunning: Boolean
        get() = ::server.isInitialized && server.isRunning

    val port: Int
        get() = if (::server.isInitialized && server.boundPort != 0) server.boundPort else container.settings.port

    override fun onCreate() {
        super.onCreate()
        instance = this
        server = LanServer(container)
        createChannel()
        watchEngineListing()
        watchAdBlock()
        watchProxy()
    }

    /**
     * Keeps SOCKS5 routing the way the owner left it: brings the tunnel back after a reboot
     * or crash, and pauses it while the proxy is unreachable (unless the kill switch is on).
     * The first pass runs soon after start so a boot does not wait a whole minute.
     */
    private fun watchProxy() {
        scope.launch {
            delay(PROXY_FIRST_CHECK_MS)
            while (isActive) {
                runCatching { ProxyTunnel.watch(this@ControlServerService, container.proxies) }
                    .onFailure { Logging.w("Proxy watchdog failed", it) }
                delay(PROXY_CHECK_MS)
            }
        }
    }

    /** Keeps Private DNS ad blocking honest: re-applies it if the firmware resets it, backs out if the resolver dies. */
    private fun watchAdBlock() {
        scope.launch {
            while (isActive) {
                delay(ADBLOCK_CHECK_MS)
                runCatching { AdBlock.watch(this@ControlServerService) }
                    .onFailure { Logging.w("Ad block watchdog failed", it) }
            }
        }
    }

    /**
     * TV launchers happily rewrite Android's accessibility list while the box is running
     * and drop entries they do not recognise. The engine is the whole point of the app,
     * so while this service lives we quietly put our entry back. It needs the adb-granted
     * `WRITE_SECURE_SETTINGS`; without it this checks and does nothing.
     */
    private fun watchEngineListing() {
        scope.launch {
            while (isActive) {
                delay(ENGINE_CHECK_MS)
                if (!EngineAutoEnable.canWrite(this@ControlServerService)) continue
                if (EngineAutoEnable.isListed(this@ControlServerService)) continue
                val restored = EngineAutoEnable.tryEnable(this@ControlServerService)
                Logging.w("Android had dropped the accessibility entry; put it back (ok=$restored)")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            else -> startServer()
        }
        return START_STICKY
    }

    private fun startServer() {
        // Foreground first, so Android's five second rule is met no matter how slow
        // the machine is, then bind the socket off the main thread: Ktor's startup
        // class loading is heavy on a TV and this service shares the process with
        // the accessibility engine.
        promoteToForeground(container.settings.deviceName, container.auth.pin(), running = false)

        // Boot asks twice (the boot receiver and the accessibility engine) and a sticky
        // restart can arrive on top of that. One start at a time: a second engine racing
        // for the same port used to crash the process.
        if (retryJob?.isActive == true) {
            Logging.i("A server start is already in flight, not starting a second one")
            return
        }

        retryJob = scope.launch {
            var attempt = 0
            var result = server.start()

            // Restarts land in the previous socket's TIME_WAIT window (up to a minute
            // on Linux), and Ktor's bind failure inside its own coroutine would kill
            // the process - so a busy port is waited out instead of given up on.
            while (!result.ok && attempt < RETRY_LIMIT) {
                attempt++
                Logging.w("Server not up yet (${result.error}); retrying in ${RETRY_DELAY_MS / 1000}s")
                delay(RETRY_DELAY_MS)
                result = server.start()
            }

            if (result.ok) {
                Logging.i("Server up on port ${result.port}")
            } else {
                Logging.w("Server did not start: ${result.error}")
            }
            withContext(Dispatchers.Main) {
                promoteToForeground(container.settings.deviceName, container.auth.pin(), running = result.ok)
            }
        }
    }

    private fun promoteToForeground(name: String, pin: String, running: Boolean) {
        val notification = buildNotification(name, pin, running)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(name: String, pin: String, running: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ControlServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val text = if (running) {
            "${NetInfo.baseUrl(port)}  ·  PIN $pin"
        } else {
            "The server could not start. Open TV Control for details."
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TV Control is serving $name")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .addAction(0, "Stop", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Remote server",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while this TV can be controlled from a browser on your network"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        scope.cancel()
        instance = null
        if (::server.isInitialized) server.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.codesky.tvcontrol.action.START"
        const val ACTION_STOP = "com.codesky.tvcontrol.action.STOP"

        private const val CHANNEL_ID = "control-server"
        private const val NOTIFICATION_ID = 4711
        private const val RETRY_LIMIT = 8
        private const val RETRY_DELAY_MS = 10_000L
        private const val ENGINE_CHECK_MS = 60_000L
        private const val ADBLOCK_CHECK_MS = 60_000L
        private const val PROXY_FIRST_CHECK_MS = 5_000L
        private const val PROXY_CHECK_MS = 60_000L

        @Volatile
        var instance: ControlServerService? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, ControlServerService::class.java).setAction(ACTION_START)
            runCatching { context.startForegroundService(intent) }
                .onFailure { Logging.w("Could not start the control server", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ControlServerService::class.java)) }
        }

        fun running(): Boolean = instance?.isRunning == true
    }
}
