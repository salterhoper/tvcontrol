package com.codesky.tvcontrol.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.codesky.tvcontrol.R
import com.codesky.tvcontrol.TvControlApp
import com.codesky.tvcontrol.core.NetInfo
import com.codesky.tvcontrol.service.ControlServerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Standard, modern 10-foot UI for the TV screen:
 * Displays connection QR & PIN, connected devices, and ambient artwork with live animation.
 */
class MainActivity : Activity() {

    private val container get() = (application as TvControlApp).container

    private lateinit var status: TextView
    private lateinit var engineBadge: TextView
    private lateinit var url: TextView
    private lateinit var pin: TextView
    private lateinit var qr: ImageView
    private lateinit var rotatePin: Button

    private lateinit var connectedBadge: TextView
    private lateinit var llConnectedDevices: LinearLayout
    private lateinit var tvNoDevices: TextView

    private lateinit var viewAmbientGlow: View

    private var ambientAnimator: ValueAnimator? = null
    private var lastQrUrl: String? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.tvStatus)
        engineBadge = findViewById(R.id.tvEngineBadge)
        url = findViewById(R.id.tvUrl)
        pin = findViewById(R.id.tvPin)
        qr = findViewById(R.id.imgQr)
        rotatePin = findViewById(R.id.btnRotatePin)

        connectedBadge = findViewById(R.id.tvConnectedBadge)
        llConnectedDevices = findViewById(R.id.llConnectedDevices)
        tvNoDevices = findViewById(R.id.tvNoDevices)

        viewAmbientGlow = findViewById(R.id.viewAmbientGlow)

        rotatePin.setOnClickListener {
            container.auth.rotatePin()
            render()
        }

        // Auto-start server if not already running
        if (!ControlServerService.running()) {
            ControlServerService.start(this)
        }

        // Request Bluetooth permissions on Android 12+ if needed
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val needed = listOf(
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_SCAN,
            ).filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
            if (needed.isNotEmpty()) {
                requestPermissions(needed.toTypedArray(), 102)
            }
        }

        // Ambient breathing animation
        startAmbientAnimation()

        // Observe active connected devices
        observeConnectedDevices()
    }

    private fun startAmbientAnimation() {
        ambientAnimator = ObjectAnimator.ofFloat(viewAmbientGlow, "alpha", 0.15f, 0.5f).apply {
            duration = 7500L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun observeConnectedDevices() {
        scope.launch {
            container.events.activeDevices.collect {
                renderConnectedDevices()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!ControlServerService.running()) {
            ControlServerService.start(this)
        }
        handler.post(ticker)
        render()
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        ambientAnimator?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun render() {
        val running = ControlServerService.running()
        val port = ControlServerService.instance?.port ?: container.settings.port
        val address = NetInfo.baseUrl(port)
        val currentPin = container.auth.pin()

        status.text = if (running) "Server: Online :$port" else "Server: Stopped"
        status.setTextColor(ContextCompat.getColor(this, if (running) R.color.tv_accent else R.color.tv_warn))

        val accessibilityActive = container.bridge.connected
        engineBadge.text = if (accessibilityActive) "Engine: Active" else "Engine: Inactive"
        engineBadge.setTextColor(ContextCompat.getColor(this, if (accessibilityActive) R.color.tv_ok else R.color.tv_warn))

        url.text = address
        pin.text = currentPin.chunked(3).joinToString(" ")

        // Generate QR code if address or PIN changed
        val qrTarget = "$address/#/pair?pin=$currentPin"
        if (qrTarget != lastQrUrl) {
            lastQrUrl = qrTarget
            QrCode.bitmap(qrTarget, 220)?.let { qr.setImageBitmap(it) }
        }

        renderConnectedDevices()
    }

    private fun renderConnectedDevices() {
        val active = container.events.activeDevices.value
        val paired = container.auth.devices()

        llConnectedDevices.removeAllViews()

        if (active.isEmpty() && paired.isEmpty()) {
            tvNoDevices.visibility = View.VISIBLE
            llConnectedDevices.addView(tvNoDevices)
            connectedBadge.text = "0 Active"
            connectedBadge.setTextColor(ContextCompat.getColor(this, R.color.tv_text_dim))
            return
        }

        tvNoDevices.visibility = View.GONE
        connectedBadge.text = "${active.size} Active"
        connectedBadge.setTextColor(ContextCompat.getColor(this, if (active.isNotEmpty()) R.color.tv_ok else R.color.tv_accent))

        // Render Active Online Devices first
        active.forEach { dev ->
            val row = buildDeviceRow(dev, isActive = true)
            llConnectedDevices.addView(row)
        }

        // Render remaining paired devices (standby, max 3)
        val standby = paired.filter { it !in active }.distinct()
        standby.take(3).forEach { dev ->
            val row = buildDeviceRow(dev, isActive = false)
            llConnectedDevices.addView(row)
        }
        if (standby.size > 3) {
            val more = TextView(this).apply {
                text = "+ ${standby.size - 3} more paired devices"
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.tv_text_dim))
                setPadding(8, 4, 8, 4)
            }
            llConnectedDevices.addView(more)
        }
    }

    private fun buildDeviceRow(name: String, isActive: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_device_item)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = 8
            }
        }

        val dot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(18, 18).apply {
                rightMargin = 16
            }
            setBackgroundResource(if (isActive) R.drawable.dot_online else R.drawable.bg_tv_badge)
        }

        val nameView = TextView(this).apply {
            text = name
            textSize = 14f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val statusView = TextView(this).apply {
            text = if (isActive) "Active Now" else "Paired / Standby"
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@MainActivity, if (isActive) R.color.tv_ok else R.color.tv_text_dim))
        }

        row.addView(dot)
        row.addView(nameView)
        row.addView(statusView)
        return row
    }

    companion object {
        private const val REFRESH_MS = 2500L
    }
}
