package com.codesky.tvcontrol.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Base64
import com.codesky.tvcontrol.accessibility.TvControlAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.net.InetSocketAddress
import java.net.Socket

/**
 * What can actually be asked of this TV.
 *
 * The app has three possible control paths, and which of them a given TV allows is
 * guesswork until something measures it:
 *
 *  - the accessibility engine (blocked on Android 13+ for sideloaded apps unless the
 *    install-source restriction is lifted; some TV builds refuse it outright),
 *  - Google's remote-control service, the thing the Google TV phone app talks to
 *    (present on certified Android TV / Google TV devices, absent on most others),
 *  - platform privileges: developer builds tagged `test-keys` are signed with the
 *    public AOSP test certificate, and an app signed with the matching companion key
 *    runs with signature-level permissions on them. That last one allows pressing
 *    real remote keys through the system input manager with no accessibility at all.
 *
 * The report below is collected on demand and shown by the dashboard, so nobody has
 * to run adb to find out which door is open.
 */
object Diagnostics {

    private const val REMOTE_SERVICE = "com.google.android.tv.remote.service"
    private const val PLAY_STORE = "com.android.vending"
    private const val PLAY_SERVICES = "com.google.android.gms"
    private val REMOTE_PORTS = listOf(6466, 6467)
    private const val PROBE_TIMEOUT_MS = 700

    /**
     * The certificate of AOSP "test-keys" builds
     * (build/target/product/security/testkey.x509.pem, DER, base64). Devices built
     * with it accept apps signed by its public companion key as platform-signed.
     */
    private const val AOSP_TESTKEY_DER_B64 =
        "MIIEqDCCA5CgAwIBAgIJAJNurL4H8gHfMA0GCSqGSIb3DQEBBQUAMIGUMQswCQYDVQQGEwJVUzETMBEGA1UECBMKQ2FsaWZv" +
            "cm5pYTEWMBQGA1UEBxMNTW91bnRhaW4gVmlldzEQMA4GA1UEChMHQW5kcm9pZDEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UE" +
            "AxMHQW5kcm9pZDEiMCAGCSqGSIb3DQEJARYTYW5kcm9pZEBhbmRyb2lkLmNvbTAeFw0wODAyMjkwMTMzNDZaFw0zNTA3MTcw" +
            "MTMzNDZaMIGUMQswCQYDVQQGEwJVUzETMBEGA1UECBMKQ2FsaWZvcm5pYTEWMBQGA1UEBxMNTW91bnRhaW4gVmlldzEQMA4G" +
            "A1UEChMHQW5kcm9pZDEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMHQW5kcm9pZDEiMCAGCSqGSIb3DQEJARYTYW5kcm9p" +
            "ZEBhbmRyb2lkLmNvbTCCASAwDQYJKoZIhvcNAQEBBQADggENADCCAQgCggEBANaTGQTexgskse3HYuDZ2CU+Ps1s6x3i/waM" +
            "qOi8qM1r03hupwqnbOYOuw+ZNVn/2T53qUPn6D1LZLjk/qLT5lbx4meoG7+yMLV4wgRDvkxyGLhG9SEVhvA4oU6Jwr44f46+" +
            "z4/Kw9oe4zDJ6pPQp8PcSvNQIg1QCAcy4ICXF+5qBTNZ5qaU7Cyz8oSgpGbIepTYOzEJOmc3Li9kEsBubULxWBjf/gOBzAz" +
            "URNps3cO4JFgZSAGzJWQTT7/emMkod0jb9WdqVA2BVMi7yge54kdVMxHEa5r3b97szI5p58ii0I54JiCUP5lyfTwE/nKZHZn" +
            "fm644oLIXf6MdW2r+6R8CAQOjgfwwgfkwHQYDVR0OBBYEFEhZAFY9JyxGrhGGBaR0GawJyowRMIHJBgNVHSMEgcEwgb6AFEhZ" +
            "AFY9JyxGrhGGBaR0GawJyowRoYGapIGXMIGUMQswCQYDVQQGEwJVUzETMBEGA1UECBMKQ2FsaWZvcm5pYTEWMBQGA1UEBxMN" +
            "TW91bnRhaW4gVmlldzEQMA4GA1UEChMHQW5kcm9pZDEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMHQW5kcm9pZDEiMCAG" +
            "CSqGSIb3DQEJARYTYW5kcm9pZEBhbmRyb2lkLmNvbYIJAJNurL4H8gHfMAwGA1UdEwQFMAMBAf8wDQYJKoZIhvcNAQEFBQAD" +
            "ggEBAHqvlozrUMRBBVEY0NqrrwFbinZaJ6cVosK0TyIUFf/azgMJWr+kLfcHCHJsIGnlw27drgQAvilFLAhLwn62oX6snb4Y" +
            "LCBOsVMR9FXYJLZW2+TcIkCRLXWG/oiVHQGo/rWuWkJgU134NDEFJCJGjDbiLCpe+ZTWHdcwauTJ9pUbo8EvHRkU3cYfGmLa" +
            "Lfgn9gP+pWA7LFQNvXwBnDa6sppCccEX31I828XzgXpJ4O+mDL1/dBd+ek8ZPUP0IgdyZm5MTYPhvVqGCHzzTy3sIeJFymwr" +
            "sBbmg2OAUNLEMO6nwmocSdN2ClirfxqCzJOLSDE4QyS9BAH6EhY6UFcOaE0="

    @Serializable
    data class Report(
        val androidRelease: String,
        val sdk: Int,
        val model: String,
        val fingerprint: String,
        val buildType: String,
        val installer: String? = null,
        val playStoreInstalled: Boolean,
        val playServicesInstalled: Boolean,
        val googleRemoteServiceInstalled: Boolean,
        val remoteServicePortsOpen: List<Int>,
        val remoteServicePortsOpenOnLan: List<Int>,
        val adbDebuggingEnabled: Boolean,
        val developmentSettingsEnabled: Boolean,
        val restrictedSettings: Boolean,
        val accessibilityConnected: Boolean,
        val enabledAccessibilityServices: String? = null,
        val serviceListedAsEnabled: Boolean,
        val writeSecureSettingsGranted: Boolean,
        val platformSignedWithTestKeys: Boolean,
        val appSignedAsPlatform: Boolean,
        val verdict: String,
        val actions: List<String>,
    )

    suspend fun collect(context: Context, accessibilityConnected: Boolean): Report = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val lanIp = NetInfo.lanIp()

        val remoteInstalled = installed(pm, REMOTE_SERVICE)
        val playStore = installed(pm, PLAY_STORE)
        val playServices = installed(pm, PLAY_SERVICES)
        val ports = REMOTE_PORTS.filter { reachable("127.0.0.1", it) }
        val lanPorts = if (lanIp != null) REMOTE_PORTS.filter { reachable(lanIp, it) } else emptyList()
        val portsOpen = ports.isNotEmpty() || lanPorts.isNotEmpty()

        val adbEnabled = globalFlag(context, Settings.Global.ADB_ENABLED)
        val devSettings = globalFlag(context, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED)
        val restricted = EngineCheck.restrictedSettingsBlocked(context)

        val enabledServices = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrNull()
        val listed = enabledServices.orEmpty().split(':').any { entry ->
            val flat = entry.trim()
            flat.substringBefore('/') == context.packageName &&
                flat.substringAfter('/', "").endsWith(TvControlAccessibilityService::class.java.simpleName)
        }

        val writeSecure =
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        val platformTestKeys = platformUsesTestKeys(pm)
        val signedAsPlatform = runCatching {
            pm.checkSignatures("android", context.packageName) == PackageManager.SIGNATURE_MATCH
        }.getOrDefault(false)

        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                pm.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(context.packageName)
            }
        }.getOrNull()

        Report(
            androidRelease = Build.VERSION.RELEASE ?: "?",
            sdk = Build.VERSION.SDK_INT,
            model = Build.MODEL ?: "Android TV",
            fingerprint = Build.FINGERPRINT ?: "?",
            buildType = Build.TYPE ?: "?",
            installer = installer,
            playStoreInstalled = playStore,
            playServicesInstalled = playServices,
            googleRemoteServiceInstalled = remoteInstalled,
            remoteServicePortsOpen = ports,
            remoteServicePortsOpenOnLan = lanPorts,
            adbDebuggingEnabled = adbEnabled,
            developmentSettingsEnabled = devSettings,
            restrictedSettings = restricted,
            accessibilityConnected = accessibilityConnected,
            enabledAccessibilityServices = enabledServices,
            serviceListedAsEnabled = listed,
            writeSecureSettingsGranted = writeSecure,
            platformSignedWithTestKeys = platformTestKeys,
            appSignedAsPlatform = signedAsPlatform,
            verdict = verdictFor(
                platformTestKeys = platformTestKeys,
                portsOpen = portsOpen,
                ports = (ports + lanPorts).distinct(),
                remoteInstalled = remoteInstalled,
                connected = accessibilityConnected,
                listed = listed,
                restricted = restricted,
                playServices = playServices,
            ),
            actions = actionsFor(
                context = context,
                platformTestKeys = platformTestKeys,
                portsOpen = portsOpen,
                remoteInstalled = remoteInstalled,
                restricted = restricted,
                adbEnabled = adbEnabled,
                devSettings = devSettings,
                connected = accessibilityConnected,
                listed = listed,
                writeSecure = writeSecure,
            ),
        )
    }

    private fun verdictFor(
        platformTestKeys: Boolean,
        portsOpen: Boolean,
        ports: List<Int>,
        remoteInstalled: Boolean,
        connected: Boolean,
        listed: Boolean,
        restricted: Boolean,
        playServices: Boolean,
    ): String = when {
        platformTestKeys ->
            "This TV is a developer build whose platform is signed with the public AOSP test keys. An app signed " +
                "with the matching companion key gets platform privileges here: it can press real remote keys " +
                "through the system input manager, with no accessibility service involved."
        portsOpen ->
            "This TV answers on Google's remote-control ports (${ports.joinToString(", ")}), so it can be driven " +
                "without accessibility at all."
        remoteInstalled ->
            "The remote-control service is installed but is not listening right now. It often starts when the " +
                "Google TV phone app connects, so try that app once and check again."
        connected ->
            "The accessibility engine is running - navigation, OK and typing work."
        listed ->
            "Android has this app's service in its enabled list but it is not running, so something is stopping " +
                "it right after it starts."
        restricted && !playServices ->
            "Android is refusing accessibility because this app was not installed from this TV's own store, and " +
                "this TV exposes no remote-control service. Lifting that restriction is the only way in."
        restricted ->
            "Android is refusing accessibility because of the install-source rule, and no remote-control service " +
                "answered on this TV."
        else ->
            "No remote-control service on this TV, and the install-source restriction does not apply - so the " +
                "accessibility switch is allowed and should hold. If it still flips back, Android is stopping this " +
                "app's service while it starts."
    }

    private fun actionsFor(
        context: Context,
        platformTestKeys: Boolean,
        portsOpen: Boolean,
        remoteInstalled: Boolean,
        restricted: Boolean,
        adbEnabled: Boolean,
        devSettings: Boolean,
        connected: Boolean,
        listed: Boolean,
        writeSecure: Boolean,
    ): List<String> = buildList {
        if (platformTestKeys) {
            add(
                "Install the platform-signed build of the app: it presses remote keys through the system input " +
                    "manager, so neither the accessibility switch nor adb is needed again.",
            )
        }
        if (portsOpen) {
            add("Pair from the dashboard (Settings > TV remote pairing). No accessibility, no adb, no root needed.")
        }
        if (!connected) {
            add("On the TV open Settings > Accessibility and turn on \"TV Control remote\".")
        }
        if (listed && !connected) {
            add(
                "The service is still in Android's enabled list, so the system is stopping it while it starts - " +
                    "the dashboard's \"Turn the engine on\" button retries that safely.",
            )
        }
        if (restricted) {
            if (!devSettings) {
                add("Turn on Developer options on the TV: Settings > System > About > tap the build entry 7 times.")
            }
            if (!adbEnabled) {
                add("In Developer options, turn on ADB debugging (a.k.a. USB/Network debugging).")
            }
            add(
                "From any phone on the same Wi-Fi with an ADB shell (Termux: pkg install android-tools, then " +
                    "adb connect <tv-ip>:5555), run:  adb shell cmd appops set ${context.packageName} " +
                    "ACCESS_RESTRICTED_SETTINGS allow",
            )
        }
        if (!writeSecure && adbEnabled) {
            add(
                "Make the setup self-healing - from the same adb shell run:  pm grant ${context.packageName} " +
                    "android.permission.WRITE_SECURE_SETTINGS   - then this app can switch its own engine back on.",
            )
        }
        if (remoteInstalled && !portsOpen) {
            add("Open the Google TV phone app once and connect it to this TV, then check again.")
        }
    }

    private fun platformUsesTestKeys(pm: PackageManager): Boolean {
        if (Build.VERSION.SDK_INT < 28) return false
        val der = runCatching { Base64.decode(AOSP_TESTKEY_DER_B64, Base64.DEFAULT) }.getOrNull() ?: return false
        return runCatching {
            pm.hasSigningCertificate("android", der, PackageManager.CERT_INPUT_RAW_X509)
        }.getOrDefault(false)
    }

    private fun installed(pm: PackageManager, pkg: String): Boolean =
        runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    private fun reachable(host: String, port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS)
            true
        }
    }.getOrDefault(false)

    private fun globalFlag(context: Context, key: String): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, key, 0) == 1
    }.getOrDefault(false)
}
