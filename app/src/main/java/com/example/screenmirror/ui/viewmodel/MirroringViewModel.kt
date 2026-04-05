package com.example.screenmirror.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.screenmirror.data.network.miracast.MiracastDiscoveryManager
import com.example.screenmirror.data.network.MirroringService
import com.example.screenmirror.data.network.nsd.NetworkDiscoveryManager
import com.example.screenmirror.data.network.dlna.DlnaDiscoveryManager
import com.example.screenmirror.domain.models.DeviceProtocol
import com.example.screenmirror.domain.models.MirrorRoute
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * ViewModel for the main screen.
 *
 * Device discovery is fully local — no external SDKs or servers:
 *  • AirPlay   → NSD (_airplay._tcp)
 *  • Chromecast → NSD (_googlecast._tcp)
 *  • Miracast  → Wi-Fi Direct (WifiP2pManager)
 *  • DLNA      → SSDP
 */
class MirroringViewModel(context: Context) : ViewModel() {

    companion object {
        private const val TAG = "MirroringVM"
    }

    // Use applicationContext to avoid Activity memory leaks inside the ViewModel
    private val appContext = context.applicationContext

    private val nsdDiscovery      = NetworkDiscoveryManager(appContext)
    private val miracastDiscovery = MiracastDiscoveryManager(appContext)
    private val dlnaDiscovery     = DlnaDiscoveryManager(appContext)

    private val _availableRoutes = MutableStateFlow<List<MirrorRoute>>(emptyList())
    val availableRoutes: StateFlow<List<MirrorRoute>> = _availableRoutes.asStateFlow()

    private val _isMirroring = MutableStateFlow(false)
    val isMirroring: StateFlow<Boolean> = _isMirroring.asStateFlow()

    private val _selectedRoute = MutableStateFlow<MirrorRoute?>(null)
    val selectedRoute: StateFlow<MirrorRoute?> = _selectedRoute.asStateFlow()

    private var nsdJob      : Job? = null
    private var miracastJob : Job? = null
    private var dlnaJob     : Job? = null

    init {
        // Give the OS a moment to settle permissions and network state
        viewModelScope.launch {
            delay(1000)
            startDiscovery()
        }
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    fun startDiscovery() {
        Log.d(TAG, "Starting local-only device discovery")
        startNsdDiscovery()
        startMiracastDiscovery()
        startDlnaDiscovery()
    }

    /** Re-launch all discovery flows (called by the Refresh button). */
    fun refreshDiscovery() {
        Log.d(TAG, "Refreshing discovery...")
        // Clear only non-persisted routes; restart all scanners
        _availableRoutes.value = emptyList()
        nsdJob?.cancel()
        miracastJob?.cancel()
        dlnaJob?.cancel()
        
        viewModelScope.launch {
            delay(500) // Small delay to let sockets close
            startDiscovery()
        }
    }

    fun onRouteSelected(route: MirrorRoute, onPermissionRequired: (Intent) -> Unit) {
        _selectedRoute.value = route
        Log.d(TAG, "Route selected: ${route.name} (${route.protocol.displayName})")

        // Request screen-capture permission regardless of protocol —
        // actual connection logic will live in MirroringService
        val projectionManager = appContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as android.media.projection.MediaProjectionManager
        onPermissionRequired(projectionManager.createScreenCaptureIntent())
    }

    fun startMirroring(resultCode: Int, data: Intent) {
        val route = _selectedRoute.value ?: return
        _isMirroring.value = true
        Log.d(TAG, "Starting Mirroring to ${route.name} (${route.protocol.displayName})")

        // 1. Start Mirroring Service
        val serviceIntent = Intent(appContext, MirroringService::class.java).apply {
            putExtra("RESULT_CODE", resultCode)
            putExtra("DATA", data)
        }
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            appContext.startForegroundService(serviceIntent)
        } else {
            appContext.startService(serviceIntent)
        }

        // 2. If it's a DLNA device, command it to play our local stream
        if (route.protocol == DeviceProtocol.DLNA) {
            viewModelScope.launch {
                delay(1500) // Give service/server a moment to start
                val localIp = getLocalIpAddress()
                if (localIp != null) {
                    val streamUrl = "http://$localIp:8080/live.ts"
                    dlnaDiscovery.castToDevice(route.id, streamUrl)
                } else {
                    Log.e(TAG, "Could not determine local IP for DLNA cast")
                }
            }
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val en = NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val intf = en.nextElement()
                val enumIpAddr = intf.inetAddresses
                while (enumIpAddr.hasMoreElements()) {
                    val inetAddress = enumIpAddr.nextElement()
                    if (!inetAddress.isLoopbackAddress && inetAddress is java.net.Inet4Address) {
                        return inetAddress.hostAddress
                    }
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "Error getting local IP: ${ex.message}")
        }
        return null
    }

    fun stopMirroring() {
        _isMirroring.value = false
        _selectedRoute.value = null
        appContext.stopService(Intent(appContext, MirroringService::class.java))
        Log.d(TAG, "Mirroring stopped")
    }

    override fun onCleared() {
        nsdJob?.cancel()
        miracastJob?.cancel()
        dlnaJob?.cancel()
        super.onCleared()
    }

    // ─── Private ─────────────────────────────────────────────────────────────

    /**
     * Collects AirPlay + Chromecast devices from NSD.
     * Each emission is a single new device → we add it if not already present.
     */
    private fun startNsdDiscovery() {
        nsdJob = viewModelScope.launch {
            nsdDiscovery.discoverAllDevices()
                .catch { e ->
                    Log.e(TAG, "NSD discovery error: ${e.message}")
                    e.printStackTrace()
                }
                .collect { route ->
                    Log.d(TAG, "NSD device found: ${route.name} (${route.protocol.displayName}) @ ${route.ipAddress}:${route.port}")
                    _availableRoutes.update { current ->
                        if (current.none { it.id == route.id }) current + route else current
                    }
                }
        }
    }

    /**
     * Collects Miracast peers from Wi-Fi Direct.
     * Each emission is the FULL current peer list → replaces previous Miracast entries.
     */
    private fun startMiracastDiscovery() {
        miracastJob = viewModelScope.launch {
            miracastDiscovery.discoverMiracastDevices()
                .catch { e ->
                    Log.e(TAG, "Miracast discovery error: ${e.message}")
                    e.printStackTrace()
                }
                .collect { miracastRoutes ->
                    Log.d(TAG, "Miracast peers updated: ${miracastRoutes.size}")
                    _availableRoutes.update { current ->
                        // Keep AirPlay + Chromecast; replace all Miracast entries
                        current.filter { it.protocol != DeviceProtocol.MIRACAST } + miracastRoutes
                    }
                }
        }
    }

    private fun startDlnaDiscovery() {
        dlnaJob = viewModelScope.launch {
            dlnaDiscovery.discoverDevices()
                .catch { e -> Log.e(TAG, "DLNA error: ${e.message}") }
                .collect { route ->
                    _availableRoutes.update { current ->
                        if (current.none { it.id == route.id }) current + route else current
                    }
                }
        }
    }
}
