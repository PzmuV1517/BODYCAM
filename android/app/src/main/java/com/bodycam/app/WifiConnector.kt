package com.bodycam.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Gets us onto the Pi's Wi-Fi and keeps us there, with no blocking system dialogs.
 *
 * - SSID set: registers it as a Wi-Fi *suggestion*. Android then auto-joins it whenever it's in
 *   range, in the background, forever (one-time "allow suggestions" notification on first use).
 *   While we're off Wi-Fi we also kick a scan every 20 s so it's found sooner.
 * - SSID blank: nothing is suggested; we simply use whatever Wi-Fi the phone is on (testing).
 *
 * Either way we hand out the Wi-Fi [Network] so sockets can be bound to it, Android otherwise
 * routes to mobile data because the Pi's network has no internet.
 */
class WifiConnector(
    context: Context,
    private val ssid: String,
    private val password: String,
    private val onNetwork: (Network?) -> Unit,
) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val wm = context.applicationContext.getSystemService(WifiManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var stopped = false
    private var current: Network? = null

    fun start() {
        stopped = false
        if (ssid.isNotBlank()) suggest()
        listen()
        main.post(scanKick)
    }

    fun stop() {
        stopped = true
        main.removeCallbacksAndMessages(null)
        callback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        callback = null
        current = null
    }

    private fun suggest() {
        try {
            wm.removeNetworkSuggestions(emptyList()) // empty = drop our old ones (e.g. previous SSID)
            val s = WifiNetworkSuggestion.Builder()
                .setSsid(ssid)
                .apply { if (password.isNotEmpty()) setWpa2Passphrase(password) }
                .setIsAppInteractionRequired(false)
                .build()
            val status = wm.addNetworkSuggestions(listOf(s))
            Log.i(TAG, "suggested '$ssid' -> status $status")
        } catch (e: Exception) {
            Log.e(TAG, "suggestion failed", e)
        }
    }

    private fun listen() {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.post {
                    if (stopped) return@post
                    Log.i(TAG, "Wi-Fi available: $network")
                    current = network
                    onNetwork(network)
                }
            }

            override fun onLost(network: Network) {
                main.post {
                    if (stopped || current != network) return@post
                    Log.i(TAG, "Wi-Fi lost: $network")
                    current = null
                    onNetwork(null)
                }
            }
        }
        callback = cb
        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        cm.registerNetworkCallback(req, cb)
    }

    /** While off Wi-Fi, nudge a scan so auto-join finds the Pi sooner (Android throttles this; fine). */
    private val scanKick = object : Runnable {
        override fun run() {
            if (stopped) return
            if (current == null && ssid.isNotBlank()) {
                @Suppress("DEPRECATION")
                try { wm.startScan() } catch (_: Exception) {}
            }
            main.postDelayed(this, 20_000)
        }
    }

    companion object {
        private const val TAG = "WifiConnector"
    }
}
