package com.example.screenmirror.data.network.nsd

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.example.screenmirror.domain.models.DeviceProtocol
import com.example.screenmirror.domain.models.MirrorRoute
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.merge
import java.util.concurrent.Executors

/**
 * Discovers AirPlay and Chromecast devices on the local network using NSD (mDNS/Bonjour).
 * No external SDKs or servers required — everything runs over the local WiFi.
 *
 * AirPlay  → _airplay._tcp  (Apple TV, HomePod, AirPlay-compatible TVs)
 * Chromecast → _googlecast._tcp  (Chromecast dongles, Google TV, Cast-enabled TVs)
 */
class NetworkDiscoveryManager(private val context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    companion object {
        private const val TAG = "NsdDiscovery"
        private const val AIRPLAY_SERVICE_TYPE   = "_airplay._tcp."
        private const val CHROMECAST_SERVICE_TYPE = "_googlecast._tcp."
        private const val MULTICAST_LOCK_TAG     = "ScreenMirror:MulticastLock"
    }

    /** Unified flow that emits discovered devices of both AirPlay and Chromecast. */
    fun discoverAllDevices(): Flow<MirrorRoute> =
        merge(
            discoverServiceType(AIRPLAY_SERVICE_TYPE, DeviceProtocol.AIRPLAY),
            discoverServiceType(CHROMECAST_SERVICE_TYPE, DeviceProtocol.CHROMECAST)
        )

    // ─── Internal ────────────────────────────────────────────────────────────

    private fun discoverServiceType(
        serviceType: String,
        protocol: DeviceProtocol
    ): Flow<MirrorRoute> = callbackFlow {

        // CRITICAL: Android drops mDNS multicast packets unless a MulticastLock is held.
        // Without this, NsdManager never receives Bonjour/mDNS service announcements.
        val multicastLock = wifiManager.createMulticastLock(MULTICAST_LOCK_TAG).apply {
            setReferenceCounted(true)
            acquire()
        }
        Log.d(TAG, "[$protocol] MulticastLock acquired")

        val listener = object : NsdManager.DiscoveryListener {

            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "[$protocol] Discovery started: $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "[$protocol] Found: ${service.serviceName}")
                // API 35+ (minSdk): Use ServiceInfoCallback — the modern, non-deprecated resolve API.
                val executor = Executors.newSingleThreadExecutor()
                nsdManager.registerServiceInfoCallback(
                    service,
                    executor,
                    object : NsdManager.ServiceInfoCallback {
                        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                            Log.e(TAG, "[$protocol] ServiceInfoCallback registration failed: $errorCode")
                        }
                        override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                            val ip = serviceInfo.hostAddresses.firstOrNull()?.hostAddress
                            Log.d(TAG, "[$protocol] Resolved '${serviceInfo.serviceName}' @ $ip:${serviceInfo.port}")
                            val route = MirrorRoute(
                                id          = "${protocol.name.lowercase()}_${serviceInfo.serviceName}",
                                name        = serviceInfo.serviceName,
                                description = "${protocol.displayName} • ${ip ?: ""}",
                                protocol    = protocol,
                                ipAddress   = ip,
                                port        = serviceInfo.port
                            )
                            trySend(route)
                            // Unregister after first successful resolve to avoid duplicate emissions
                            try { nsdManager.unregisterServiceInfoCallback(this) } catch (_: Exception) {}
                        }
                        override fun onServiceLost() {
                            Log.d(TAG, "[$protocol] ServiceInfoCallback: service lost")
                        }
                        override fun onServiceInfoCallbackUnregistered() {
                            Log.d(TAG, "[$protocol] ServiceInfoCallback unregistered")
                        }
                    }
                )
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d(TAG, "[$protocol] Lost: ${service.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "[$protocol] Discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "[$protocol] Start failed: errorCode=$errorCode")
                try { nsdManager.stopServiceDiscovery(this) } catch (_: Exception) {}
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "[$protocol] Stop failed: errorCode=$errorCode")
            }
        }

        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "[$protocol] Error starting discovery: ${e.message}")
            if (multicastLock.isHeld) multicastLock.release()
            close(e)
        }

        awaitClose {
            try { nsdManager.stopServiceDiscovery(listener) } catch (_: Exception) {}
            if (multicastLock.isHeld) {
                multicastLock.release()
                Log.d(TAG, "[$protocol] MulticastLock released")
            }
        }
    }

}
