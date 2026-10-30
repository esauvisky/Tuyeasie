package io.github.emi.tuyaonboarding.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

data class TuyaHotspot(
    val ssid: String,
    val signalDbm: Int,
    val signalLevel: Int
)

data class NearbyNetwork(
    val ssid: String,
    val signalDbm: Int,
    val signalLevel: Int,
    val has24Ghz: Boolean
)

class HotspotRepository(context: Context) {
    interface Listener {
        fun onScanResults(hotspots: List<TuyaHotspot>)
        fun onScanFailed(message: String)
        fun onConnected(ssid: String)
        fun onConnectionFailed(message: String)
    }

    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var generation = 0
    private var scanReceiver: BroadcastReceiver? = null
    private var scanTimeout: Runnable? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var connectedNetwork: Network? = null
    private var boundNetwork: Network? = null
    private var connectionNotified = false
    private var activationStarted = false
    private var closed = false

    var listener: Listener? = null

    fun scan() {
        disconnect()
        val scanGeneration = generation
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = onCurrent(scanGeneration) {
                completeScan(scanGeneration)
            }
        }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        scanReceiver = receiver
        val timeout = Runnable {
            if (!closed && scanGeneration == generation) {
                val cached = readHotspots()
                if (cached.isNotEmpty()) {
                    completeScan(scanGeneration)
                } else {
                    failScan(scanGeneration, "Hotspot scanning timed out. Keep AP mode enabled and retry.")
                }
            }
        }
        scanTimeout = timeout
        mainHandler.postDelayed(timeout, SCAN_TIMEOUT_MILLIS)
        if (!wifiManager.startScan()) {
            val cached = readHotspots()
            if (cached.isNotEmpty()) {
                completeScan(scanGeneration)
            } else {
                failScan(
                    scanGeneration,
                    "Hotspot scanning is unavailable. Turn on Wi-Fi and Location Services, then retry."
                )
            }
        }
    }

    // Android throttles startScan (returns false) by design; callers still read the system's cached results.
    fun requestNearbyScan() {
        wifiManager.startScan()
    }

    fun readNearbyNetworks(): List<NearbyNetwork> = wifiManager.scanResults
        .filter { it.SSID.isNotBlank() }
        .groupBy { it.SSID }
        .map { (ssid, results) ->
            val strongest = results.maxBy { it.level }
            NearbyNetwork(
                ssid = ssid,
                signalDbm = strongest.level,
                signalLevel = WifiManager.calculateSignalLevel(strongest.level, SIGNAL_LEVELS),
                has24Ghz = results.any { it.frequency < FIVE_GHZ_MIN_MHZ }
            )
        }
        .sortedWith(compareByDescending<NearbyNetwork> { it.signalDbm }.thenBy { it.ssid })

    @RequiresApi(Build.VERSION_CODES.Q)
    fun connect(ssid: String) {
        disconnect()
        val currentGeneration = generation
        val specifier = WifiNetworkSpecifier.Builder().setSsid(ssid).build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onCurrent(currentGeneration) {
                if (connectionNotified) return@onCurrent
                if (!connectivityManager.bindProcessToNetwork(network)) {
                    fail("Android could not bind this app to the selected hotspot.")
                    return@onCurrent
                }
                connectedNetwork = network
                boundNetwork = network
                connectionNotified = true
                listener?.onConnected(ssid)
            }

            override fun onUnavailable() = onCurrent(currentGeneration) {
                fail("Hotspot connection timed out or was unavailable. Keep AP mode enabled and retry.")
            }

            override fun onLost(network: Network) = onCurrent(currentGeneration) {
                if (network == connectedNetwork) {
                    if (activationStarted) {
                        disconnect()
                    } else {
                        fail("The hotspot connection was lost before activation. Reconnect and retry.")
                    }
                }
            }
        }
        callback = networkCallback
        connectivityManager.requestNetwork(request, networkCallback, NETWORK_TIMEOUT_MILLIS)
    }

    fun onActivationStarted() {
        activationStarted = true
    }

    fun disconnect() {
        generation++
        cancelScan()
        val oldCallback = callback
        val oldBoundNetwork = boundNetwork
        callback = null
        connectedNetwork = null
        boundNetwork = null
        connectionNotified = false
        activationStarted = false
        if (oldCallback != null) connectivityManager.unregisterNetworkCallback(oldCallback)
        if (oldBoundNetwork != null) connectivityManager.bindProcessToNetwork(null)
    }

    fun close() {
        closed = true
        disconnect()
        listener = null
    }

    private fun completeScan(expectedGeneration: Int) {
        val matches = readHotspots()
        finishScan(expectedGeneration)
        listener?.onScanResults(matches)
    }

    private fun failScan(expectedGeneration: Int, message: String) {
        finishScan(expectedGeneration)
        listener?.onScanFailed(message)
    }

    private fun finishScan(expectedGeneration: Int) {
        if (expectedGeneration != generation) return
        generation++
        cancelScan()
    }

    private fun cancelScan() {
        val oldReceiver = scanReceiver
        val oldTimeout = scanTimeout
        scanReceiver = null
        scanTimeout = null
        if (oldTimeout != null) mainHandler.removeCallbacks(oldTimeout)
        if (oldReceiver != null) appContext.unregisterReceiver(oldReceiver)
    }

    private fun readHotspots(): List<TuyaHotspot> = wifiManager.scanResults
        .filter { result ->
            val open = !listOf("PSK", "WEP", "EAP", "SAE").any {
                result.capabilities.contains(it, ignoreCase = true)
            }
            open && HOTSPOT_PREFIXES.any { result.SSID.startsWith(it, ignoreCase = true) }
        }
        .groupBy { it.SSID }
        .map { (ssid, results) ->
            val strongest = results.maxBy { it.level }
            TuyaHotspot(
                ssid = ssid,
                signalDbm = strongest.level,
                signalLevel = WifiManager.calculateSignalLevel(strongest.level, SIGNAL_LEVELS)
            )
        }
        .sortedWith(compareByDescending<TuyaHotspot> { it.signalDbm }.thenBy { it.ssid })

    private fun fail(message: String) {
        disconnect()
        listener?.onConnectionFailed(message)
    }

    private fun onCurrent(expectedGeneration: Int, action: () -> Unit) {
        mainHandler.post {
            if (!closed && expectedGeneration == generation) action()
        }
    }

    private companion object {
        val HOTSPOT_PREFIXES = listOf("SmartLife", "Tuya", "SL-")
        const val SIGNAL_LEVELS = 4
        const val FIVE_GHZ_MIN_MHZ = 4900
        const val SCAN_TIMEOUT_MILLIS = 15_000L
        const val NETWORK_TIMEOUT_MILLIS = 30_000
    }
}
