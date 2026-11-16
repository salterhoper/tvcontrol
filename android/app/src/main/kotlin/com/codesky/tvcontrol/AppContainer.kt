package com.codesky.tvcontrol

import android.content.Context
import com.codesky.tvcontrol.ai.AssistantEngine
import com.codesky.tvcontrol.config.SettingsStore
import com.codesky.tvcontrol.control.AccessibilityBridge
import com.codesky.tvcontrol.control.AppLauncher
import com.codesky.tvcontrol.control.ControlHub
import com.codesky.tvcontrol.control.DeepLinks
import com.codesky.tvcontrol.control.MediaKeys
import com.codesky.tvcontrol.core.EventBus
import com.codesky.tvcontrol.security.AuthStore
import com.codesky.tvcontrol.security.SecureStore
import com.codesky.tvcontrol.server.WsHub

/**
 * Single wiring point for everything that lives as long as the process.
 *
 * The app is small enough that a hand rolled container beats a DI framework:
 * one place lists every long lived dependency and its collaborators.
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    val events = EventBus()

    private val secureStore = SecureStore()
    val settings = SettingsStore(appContext, secureStore)
    val auth = AuthStore(appContext, secureStore)

    /** Saved SOCKS5 servers for routing the TV's traffic; see proxy/ProxyTunnel. */
    val proxies = com.codesky.tvcontrol.proxy.ProxyStore(appContext, secureStore)

    val bridge = AccessibilityBridge()
    val media = MediaKeys(appContext)
    val launcher = AppLauncher(appContext)
    val deepLinks = DeepLinks(appContext)
    val control = ControlHub(bridge, media, launcher, deepLinks)

    /** Websocket fan-out hub; started lazily on the first dashboard connection. */
    val ws: WsHub by lazy { WsHub(this) }

    val assistant = AssistantEngine(this)

    val bluetooth: com.codesky.tvcontrol.bluetooth.TvBluetoothManager by lazy {
        com.codesky.tvcontrol.bluetooth.TvBluetoothManager(appContext, events)
    }

    val files: com.codesky.tvcontrol.files.TvFileManager by lazy {
        com.codesky.tvcontrol.files.TvFileManager(appContext, events)
    }

    val nowPlaying: com.codesky.tvcontrol.media.TvMediaManager by lazy {
        com.codesky.tvcontrol.media.TvMediaManager(appContext, events, media)
    }
}
