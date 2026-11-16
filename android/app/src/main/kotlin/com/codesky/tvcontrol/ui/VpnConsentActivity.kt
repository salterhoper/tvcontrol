package com.codesky.tvcontrol.ui

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import com.codesky.tvcontrol.TvControlApp
import com.codesky.tvcontrol.proxy.ProxyVpnService

/**
 * Invisible: shows Android's one-time "allow this app to set up a VPN" prompt on the
 * TV, then starts the proxy tunnel. Opened by the dashboard when routing is switched
 * on before the owner has ever agreed.
 */
class VpnConsentActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prompt = runCatching { VpnService.prepare(this) }.getOrNull()
        if (prompt == null) {
            ProxyVpnService.start(this)
            finish()
            return
        }
        runCatching { startActivityForResult(prompt, REQUEST) }.onFailure { finish() }
    }

    @Deprecated("Activity result API is not worth a dependency here")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST) {
            val store = (application as TvControlApp).container.proxies
            if (resultCode == RESULT_OK) {
                ProxyVpnService.start(this)
            } else {
                store.desired = false
                store.lastNote = "The VPN prompt was declined on the TV, so routing stayed off."
            }
        }
        finish()
    }

    private companion object {
        const val REQUEST = 7301
    }
}
