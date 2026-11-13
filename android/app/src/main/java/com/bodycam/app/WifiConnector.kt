package com.bodycam.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Gets us onto the Pi's Wi-Fi and keeps us there.
 *
 * - SSID set: app-managed connection via WifiNetworkSpecifier (Android asks to approve once).
 *   On any loss or failure we immediately re-request, forever.
 * - SSID blank: use whatever Wi-Fi the phone is on (e.g. for testing on a home/office network).
 *
 * Either way we hand out the Wi-Fi [Network] so sockets can be bound to it — Android otherwise
 * routes to mobile data because the Pi's network has no internet.
 */
class WifiConnector(
    context: Context,
    private val ssid: String,
    private val password: String,
    private val onNetwork: (Network?) -> Unit,
) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var stopped = false
    private var current: Network? = null

    fun start() {
        stopped = false
        request()
    }

    fun stop() {
        stopped = true
        main.removeCallbacksAndMessages(null)
        unregister()
        current = null
    }

    private fun unregister() {
        callback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        callback = null
    }

    private fun retry(delayMs: Long) {
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ if (!stopped) request() }, delayMs)
    }

    private fun request() {
        unregister()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = main.post {
                if (stopped || callback !== this) return@post
                Log.i(TAG, "Wi-Fi available: $network")
                current = network
                onNetwork(network)
            }.let { }

            override fun onLost(network: Network) = main.post {
                if (stopped || callback !== this) return@post
                Log.i(TAG, "Wi-Fi lost: $network")
                if (current == network) {
                    current = null
                    onNetwork(null)
                }
                if (ssid.isNotBlank()) retry(300) // specifier requests don't come back by themselves
            }.let { }

            override fun onUnavailable() = main.post {
                if (stopped || callback !== this) return@post
                Log.i(TAG, "Wi-Fi '$ssid' not found / not approved, retrying")
                retry(1500)
            }.let { }
        }
        callback = cb
        try {
            if (ssid.isBlank()) {
                val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
                cm.registerNetworkCallback(req, cb)
            } else {
                val spec = WifiNetworkSpecifier.Builder().setSsid(ssid).apply {
                    if (password.isNotEmpty()) setWpa2Passphrase(password)
                }.build()
                val req = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(spec)
                    .build()
                cm.requestNetwork(req, cb, 15_000) // timeout -> onUnavailable -> retry
            }
        } catch (e: Exception) {
            Log.e(TAG, "network request failed", e)
            callback = null
            retry(2000)
        }
    }

    companion object {
        private const val TAG = "WifiConnector"
    }
}
