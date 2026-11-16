package com.codesky.tvcontrol.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.codesky.tvcontrol.core.AppJson
import com.codesky.tvcontrol.core.EventBus
import com.codesky.tvcontrol.core.Logging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class BluetoothDeviceDto(
    val name: String,
    val address: String,
    val bonded: Boolean,
    val connected: Boolean,
    val deviceType: String, // "audio", "input", "phone", "computer", "wearable", "other"
    val majorClass: Int = 0,
    val rssi: Int? = null,
    val bondState: String, // "bonded", "bonding", "none"
)

@Serializable
data class BluetoothStatusDto(
    val supported: Boolean,
    val enabled: Boolean,
    val scanning: Boolean,
    val name: String? = null,
    val address: String? = null,
    val pairedDevices: List<BluetoothDeviceDto>,
    val discoveredDevices: List<BluetoothDeviceDto>,
)

@Serializable
data class BluetoothActionResult(
    val ok: Boolean,
    val message: String? = null,
    val error: String? = null,
)

/**
 * Manages Bluetooth adapter state, device discovery, bonding (pairing),
 * and connecting/disconnecting audio, gamepad, and peripheral profiles.
 */
@SuppressLint("MissingPermission")
class TvBluetoothManager(
    private val context: Context,
    private val events: EventBus,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()

    private val discovered = ConcurrentHashMap<String, BluetoothDeviceDto>()
    private var scanTimeoutJob: Job? = null

    // Profile proxies for connecting & querying status of audio and input devices
    private var a2dpProfile: BluetoothProfile? = null
    private var headsetProfile: BluetoothProfile? = null
    private var hidProfile: BluetoothProfile? = null

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            when (profile) {
                BluetoothProfile.A2DP -> a2dpProfile = proxy
                BluetoothProfile.HEADSET -> headsetProfile = proxy
                4 /* HID_HOST */ -> hidProfile = proxy
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            when (profile) {
                BluetoothProfile.A2DP -> a2dpProfile = null
                BluetoothProfile.HEADSET -> headsetProfile = null
                4 /* HID_HOST */ -> hidProfile = null
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (device == null || device.address.isNullOrBlank()) return

                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    val btClass: BluetoothClass? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_CLASS, BluetoothClass::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_CLASS)
                    }

                    val dto = toDto(device, btClass, if (rssi != Short.MIN_VALUE.toInt()) rssi else null)
                    discovered[device.address] = dto
                    events.emit("bluetooth_device_found", AppJson.encodeToJsonElement(dto))
                }

                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    events.emit("bluetooth_discovery", buildJsonObject { put("scanning", true) })
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    scanTimeoutJob?.cancel()
                    events.emit("bluetooth_discovery", buildJsonObject { put("scanning", false) })
                }

                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    val prevBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)

                    if (device != null) {
                        val stateStr = when (bondState) {
                            BluetoothDevice.BOND_BONDED -> "bonded"
                            BluetoothDevice.BOND_BONDING -> "bonding"
                            else -> "none"
                        }
                        events.emit("bluetooth_bond_changed", buildJsonObject {
                            put("address", device.address)
                            put("name", getDeviceName(device))
                            put("bondState", stateStr)
                            put("bonded", bondState == BluetoothDevice.BOND_BONDED)
                        })

                        // Auto-connect once bonding succeeds
                        if (bondState == BluetoothDevice.BOND_BONDED && prevBondState == BluetoothDevice.BOND_BONDING) {
                            scope.launch {
                                delay(600)
                                connect(device.address)
                            }
                        }
                    }
                }

                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (device != null) {
                        events.emit("bluetooth_connection_changed", buildJsonObject {
                            put("address", device.address)
                            put("name", getDeviceName(device))
                            put("connected", true)
                        })
                    }
                }

                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (device != null) {
                        events.emit("bluetooth_connection_changed", buildJsonObject {
                            put("address", device.address)
                            put("name", getDeviceName(device))
                            put("connected", false)
                        })
                    }
                }

                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    val isEnabled = state == BluetoothAdapter.STATE_ON
                    events.emit("bluetooth_state_changed", buildJsonObject {
                        put("enabled", isEnabled)
                    })
                }
            }
        }
    }

    init {
        try {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            }
            context.registerReceiver(receiver, filter)
        } catch (e: Exception) {
            Logging.e("Failed to register Bluetooth receiver: ${e.message}")
        }

        initProfileProxies()
    }

    private fun initProfileProxies() {
        val btAdapter = adapter ?: return
        try {
            btAdapter.getProfileProxy(context, profileListener, BluetoothProfile.A2DP)
            btAdapter.getProfileProxy(context, profileListener, BluetoothProfile.HEADSET)
            btAdapter.getProfileProxy(context, profileListener, 4 /* HID_HOST */)
        } catch (e: Exception) {
            Logging.d("Could not acquire Bluetooth profile proxies: ${e.message}")
        }
    }

    fun hasBluetoothPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val connectGranted = ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            val scanGranted = ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            return connectGranted && scanGranted
        }
        return true
    }

    fun getStatus(): BluetoothStatusDto {
        val btAdapter = adapter
        if (btAdapter == null) {
            return BluetoothStatusDto(
                supported = false,
                enabled = false,
                scanning = false,
                pairedDevices = emptyList(),
                discoveredDevices = emptyList(),
            )
        }

        val isEnabled = btAdapter.isEnabled
        val isScanning = btAdapter.isDiscovering

        val paired = if (isEnabled) {
            try {
                btAdapter.bondedDevices?.map { toDto(it) } ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        // Return discovered devices that are not already bonded
        val pairedAddresses = paired.map { it.address }.toSet()
        val discList = discovered.values.filter { it.address !in pairedAddresses }

        return BluetoothStatusDto(
            supported = true,
            enabled = isEnabled,
            scanning = isScanning,
            name = runCatching { btAdapter.name }.getOrNull(),
            address = runCatching { btAdapter.address }.getOrNull(),
            pairedDevices = paired,
            discoveredDevices = discList,
        )
    }

    fun startScan(): BluetoothActionResult {
        val btAdapter = adapter ?: return BluetoothActionResult(false, error = "Bluetooth not supported on this TV")
        if (!btAdapter.isEnabled) return BluetoothActionResult(false, error = "Bluetooth is currently turned off")

        discovered.clear()

        if (btAdapter.isDiscovering) {
            btAdapter.cancelDiscovery()
        }

        val started = btAdapter.startDiscovery()
        if (started) {
            scanTimeoutJob?.cancel()
            scanTimeoutJob = scope.launch {
                delay(20_000)
                if (btAdapter.isDiscovering) {
                    btAdapter.cancelDiscovery()
                }
            }
            return BluetoothActionResult(true, message = "Bluetooth scanning started")
        }
        return BluetoothActionResult(false, error = "Could not start Bluetooth discovery")
    }

    fun stopScan(): BluetoothActionResult {
        val btAdapter = adapter ?: return BluetoothActionResult(false, error = "Bluetooth not supported")
        scanTimeoutJob?.cancel()
        val stopped = btAdapter.cancelDiscovery()
        return BluetoothActionResult(stopped, message = if (stopped) "Scanning stopped" else "Could not stop scan")
    }

    fun pair(address: String): BluetoothActionResult {
        val btAdapter = adapter ?: return BluetoothActionResult(false, error = "Bluetooth not supported")
        if (!btAdapter.isEnabled) return BluetoothActionResult(false, error = "Bluetooth is turned off")

        val device = runCatching { btAdapter.getRemoteDevice(address) }.getOrNull()
            ?: return BluetoothActionResult(false, error = "Device with address $address not found")

        // Stop scanning to improve pairing reliability
        if (btAdapter.isDiscovering) {
            btAdapter.cancelDiscovery()
        }

        return try {
            val initiated = device.createBond()
            if (initiated) {
                BluetoothActionResult(true, message = "Pairing request sent to ${getDeviceName(device)}")
            } else {
                BluetoothActionResult(false, error = "Failed to initiate pairing with ${getDeviceName(device)}")
            }
        } catch (e: Exception) {
            BluetoothActionResult(false, error = e.message ?: "Pairing failed")
        }
    }

    fun unpair(address: String): BluetoothActionResult {
        val btAdapter = adapter ?: return BluetoothActionResult(false, error = "Bluetooth not supported")
        val device = runCatching { btAdapter.getRemoteDevice(address) }.getOrNull()
            ?: return BluetoothActionResult(false, error = "Device not found")

        return try {
            val method = device.javaClass.getMethod("removeBond")
            method.isAccessible = true
            val removed = method.invoke(device) as? Boolean ?: false
            if (removed) {
                BluetoothActionResult(true, message = "Unpaired ${getDeviceName(device)}")
            } else {
                BluetoothActionResult(false, error = "Failed to remove pairing for ${getDeviceName(device)}")
            }
        } catch (e: Exception) {
            BluetoothActionResult(false, error = e.message ?: "Could not unpair device")
        }
    }

    fun connect(address: String): BluetoothActionResult {
        val btAdapter = adapter ?: return BluetoothActionResult(false, error = "Bluetooth not supported")
        if (!btAdapter.isEnabled) return BluetoothActionResult(false, error = "Bluetooth is turned off")

        val device = runCatching { btAdapter.getRemoteDevice(address) }.getOrNull()
            ?: return BluetoothActionResult(false, error = "Device not found")

        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            // Trigger bond first if not bonded
            val bonded = device.createBond()
            return BluetoothActionResult(bonded, message = "Pairing initiated with ${getDeviceName(device)}")
        }

        var connectedAny = false

        // 1. Try A2DP audio profile
        a2dpProfile?.let { proxy ->
            try {
                val method = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                method.isAccessible = true
                val res = method.invoke(proxy, device) as? Boolean ?: false
                if (res) connectedAny = true
            } catch (_: Exception) {}
        }

        // 2. Try Headset profile
        headsetProfile?.let { proxy ->
            try {
                val method = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                method.isAccessible = true
                val res = method.invoke(proxy, device) as? Boolean ?: false
                if (res) connectedAny = true
            } catch (_: Exception) {}
        }

        // 3. Try HID (gamepad, keyboard, mouse) profile
        hidProfile?.let { proxy ->
            try {
                val method = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                method.isAccessible = true
                val res = method.invoke(proxy, device) as? Boolean ?: false
                if (res) connectedAny = true
            } catch (_: Exception) {}
        }

        // 4. Try generic reflection device.connect()
        try {
            val method = device.javaClass.getMethod("connect")
            method.isAccessible = true
            val res = method.invoke(device) as? Boolean ?: false
            if (res) connectedAny = true
        } catch (_: Exception) {}

        return if (connectedAny) {
            BluetoothActionResult(true, message = "Connecting to ${getDeviceName(device)}…")
        } else {
            BluetoothActionResult(true, message = "Connection signal dispatched to ${getDeviceName(device)}")
        }
    }

    fun disconnect(address: String): BluetoothActionResult {
        val btAdapter = adapter ?: return BluetoothActionResult(false, error = "Bluetooth not supported")
        val device = runCatching { btAdapter.getRemoteDevice(address) }.getOrNull()
            ?: return BluetoothActionResult(false, error = "Device not found")

        var disconnectedAny = false

        a2dpProfile?.let { proxy ->
            try {
                val method = proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                method.isAccessible = true
                val res = method.invoke(proxy, device) as? Boolean ?: false
                if (res) disconnectedAny = true
            } catch (_: Exception) {}
        }

        headsetProfile?.let { proxy ->
            try {
                val method = proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                method.isAccessible = true
                val res = method.invoke(proxy, device) as? Boolean ?: false
                if (res) disconnectedAny = true
            } catch (_: Exception) {}
        }

        hidProfile?.let { proxy ->
            try {
                val method = proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                method.isAccessible = true
                val res = method.invoke(proxy, device) as? Boolean ?: false
                if (res) disconnectedAny = true
            } catch (_: Exception) {}
        }

        try {
            val method = device.javaClass.getMethod("disconnect")
            method.isAccessible = true
            val res = method.invoke(device) as? Boolean ?: false
            if (res) disconnectedAny = true
        } catch (_: Exception) {}

        return BluetoothActionResult(true, message = "Disconnected ${getDeviceName(device)}")
    }

    fun setState(enable: Boolean): BluetoothActionResult {
        val btAdapter = adapter ?: return BluetoothActionResult(false, error = "Bluetooth not supported")
        val success = if (enable) btAdapter.enable() else btAdapter.disable()
        return BluetoothActionResult(success, message = if (enable) "Bluetooth turning on" else "Bluetooth turning off")
    }

    fun launchPairingActivity(): BluetoothActionResult {
        return try {
            val intent = Intent().apply {
                component = ComponentName("com.android.tv.settings", "com.android.tv.settings.accessories.AddAccessoryActivity")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            BluetoothActionResult(true, message = "Opened TV accessory pairing screen")
        } catch (e: Exception) {
            try {
                val fallback = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallback)
                BluetoothActionResult(true, message = "Opened TV Bluetooth settings")
            } catch (ex: Exception) {
                BluetoothActionResult(false, error = ex.message ?: "Could not open TV pairing screen")
            }
        }
    }

    private fun isDeviceConnected(device: BluetoothDevice): Boolean {
        // Hidden API method
        try {
            val method = device.javaClass.getMethod("isConnected")
            method.isAccessible = true
            val connected = method.invoke(device) as? Boolean
            if (connected == true) return true
        } catch (_: Exception) {}

        // Profile connection checks
        try {
            if (a2dpProfile?.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED) return true
            if (headsetProfile?.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED) return true
            if (hidProfile?.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED) return true
        } catch (_: Exception) {}

        return false
    }

    private fun toDto(device: BluetoothDevice, btClass: BluetoothClass? = null, rssi: Int? = null): BluetoothDeviceDto {
        val actualClass = btClass ?: runCatching { device.bluetoothClass }.getOrNull()
        val major = actualClass?.majorDeviceClass ?: 0
        val name = getDeviceName(device)
        val isBonded = device.bondState == BluetoothDevice.BOND_BONDED
        val bondStr = when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> "bonded"
            BluetoothDevice.BOND_BONDING -> "bonding"
            else -> "none"
        }

        return BluetoothDeviceDto(
            name = name,
            address = device.address,
            bonded = isBonded,
            connected = if (isBonded) isDeviceConnected(device) else false,
            deviceType = resolveCategory(name, major),
            majorClass = major,
            rssi = rssi,
            bondState = bondStr,
        )
    }

    private fun getDeviceName(device: BluetoothDevice): String {
        return runCatching { device.name }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: runCatching {
                val aliasMethod = device.javaClass.getMethod("getAlias")
                aliasMethod.isAccessible = true
                aliasMethod.invoke(device) as? String
            }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "Unknown Device (${device.address.takeLast(5)})"
    }

    private fun resolveCategory(name: String, majorClass: Int): String {
        val lower = name.lowercase()
        return when {
            majorClass == BluetoothClass.Device.Major.AUDIO_VIDEO ||
                lower.contains("buds") || lower.contains("earphone") || lower.contains("headset") ||
                lower.contains("headphones") || lower.contains("airpod") || lower.contains("soundbar") ||
                lower.contains("speaker") -> "audio"

            majorClass == BluetoothClass.Device.Major.PERIPHERAL ||
                lower.contains("gamepad") || lower.contains("controller") || lower.contains("remote") ||
                lower.contains("keyboard") || lower.contains("mouse") -> "input"

            majorClass == BluetoothClass.Device.Major.PHONE || lower.contains("phone") -> "phone"
            majorClass == BluetoothClass.Device.Major.COMPUTER || lower.contains("macbook") || lower.contains("pc") -> "computer"
            majorClass == BluetoothClass.Device.Major.WEARABLE || lower.contains("watch") -> "wearable"
            else -> "other"
        }
    }
}
