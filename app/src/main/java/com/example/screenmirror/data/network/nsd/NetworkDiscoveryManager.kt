package com.example.screenmirror.data.network.nsd

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.example.screenmirror.domain.models.DeviceProtocol
import com.example.screenmirror.domain.models.MirrorRoute
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.merge
import java.util.concurrent.Executors

/**
 * Discovers AirPlay and Chromecast devices on the local network using NSD (mDNS/Bonjour).
 * No external SDKs or servers required — everything runs over the local WiFi.
 */
class NetworkDiscoveryManager(private val context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    companion object {
        private const val TAG = "NsdDiscovery"
        private const val AIRPLAY_SERVICE_TYPE   = "_airplay._tcp."
        private const val CHROMECAST_SERVICE_TYPE = "_googlecast._tcp."
        const val MULTICAST_LOCK_TAG = "ScreenMirror:DiscoveryLock"
        
        // NsdManager can be buggy if we resolve multiple services at once.
        // We use a dedicated single-thread executor for all resolutions.
        private val resolutionExecutor = Executors.newSingleThreadExecutor()
    }

    /** Unified flow that emits discovered devices of both AirPlay and Chromecast. */
    fun discoverAllDevices(): Flow<MirrorRoute> =
        merge(
            discoverServiceType(AIRPLAY_SERVICE_TYPE, DeviceProtocol.AIRPLAY, delayMs = 0),
            discoverServiceType(CHROMECAST_SERVICE_TYPE, DeviceProtocol.CHROMECAST, delayMs = 1500)
        )

    private fun discoverServiceType(
        serviceType: String,
        protocol: DeviceProtocol,
        delayMs: Long = 0
    ): Flow<MirrorRoute> = callbackFlow {
        if (delayMs > 0) delay(delayMs)

        val multicastLock = wifiManager.createMulticastLock(MULTICAST_LOCK_TAG).apply {
            setReferenceCounted(true)
            acquire()
        }
        Log.d(TAG, "[$protocol] MulticastLock acquired for $serviceType")

        val listener = object : NsdManager.DiscoveryListener {

            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "[$protocol] Discovery started: $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "[$protocol] Found: ${service.serviceName}")
                
                if (Build.VERSION.SDK_INT >= 35) {
                    // Modern API 35+ Resolution
                    nsdManager.registerServiceInfoCallback(
                        service,
                        resolutionExecutor,
                        object : NsdManager.ServiceInfoCallback {
                            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                                Log.e(TAG, "[$protocol] Callback registration failed: $errorCode")
                            }

                            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                                handleResolvedService(serviceInfo, protocol)
                            }

                            override fun onServiceLost() {
                                Log.d(TAG, "[$protocol] Service lost: ${service.serviceName}")
                            }

                            override fun onServiceInfoCallbackUnregistered() {
                                Log.d(TAG, "[$protocol] Callback unregistered for ${service.serviceName}")
                            }
                        }
                    )
                } else {
                    // Legacy resolveService (Stable for API < 35)
                    // Note: Parallel resolve calls on the SAME listener often fail,
                    // so we use a new listener per service.
                    nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            Log.e(TAG, "[$protocol] Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            handleResolvedService(serviceInfo, protocol)
                        }
                    })
                }
            }

            private fun handleResolvedService(serviceInfo: NsdServiceInfo, protocol: DeviceProtocol) {
                val ip = serviceInfo.hostAddresses.firstOrNull()?.hostAddress
                if (ip != null) {
                    Log.d(TAG, "[$protocol] Resolved '${serviceInfo.serviceName}' @ $ip:${serviceInfo.port}")
                    val route = MirrorRoute(
                        id          = "${protocol.name.lowercase()}_${serviceInfo.serviceName}",
                        name        = serviceInfo.serviceName,
                        description = "${protocol.displayName} • $ip",
                        protocol    = protocol,
                        ipAddress   = ip,
                        port        = serviceInfo.port
                    )
                    trySend(route)
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d(TAG, "[$protocol] Lost: ${service.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "[$protocol] Discovery stopped")
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
            Log.e(TAG, "[$protocol] Error initiating discovery: ${e.message}")
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
