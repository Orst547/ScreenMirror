package com.example.screenmirror.data.network.miracast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import android.util.Log
import com.example.screenmirror.domain.models.DeviceProtocol
import com.example.screenmirror.domain.models.MirrorRoute
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Discovers Miracast-capable devices using Wi-Fi Direct (WifiP2pManager).
 * 100% local — no internet, no external SDKs required.
 *
 * Emits the full current list of nearby peers each time the peer set changes.
 */
class MiracastDiscoveryManager(private val context: Context) {

    companion object {
        private const val TAG = "MiracastDiscovery"
    }

    private val wifiP2pManager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager

    fun discoverMiracastDevices(): Flow<List<MirrorRoute>> = callbackFlow {
        if (wifiP2pManager == null) {
            Log.w(TAG, "Wi-Fi Direct not supported on this device — skipping Miracast")
            trySend(emptyList())
            awaitClose {}
            return@callbackFlow
        }

        // Channel links the app to the Wi-Fi P2P framework
        val channel = wifiP2pManager.initialize(context, Looper.getMainLooper(), null)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {

                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                            Log.w(TAG, "Wi-Fi Direct disabled — sending empty list")
                            trySend(emptyList())
                        }
                    }

                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        // Request the current peer list from the framework
                        @Suppress("DEPRECATION")
                        wifiP2pManager.requestPeers(channel) { peerList ->
                            val routes = peerList.deviceList
                                .filter { it.status == WifiP2pDevice.AVAILABLE }
                                .map { device ->
                                    MirrorRoute(
                                        id          = "miracast_${device.deviceAddress}",
                                        name        = device.deviceName.ifBlank { device.deviceAddress },
                                        description = "Miracast \u2022 ${device.deviceAddress}",
                                        protocol    = DeviceProtocol.MIRACAST,
                                        ipAddress   = null   // IP assigned only after P2P connection
                                    )
                                }
                            Log.d(TAG, "Miracast peers updated: ${routes.size} devices")
                            trySend(routes)
                        }
                    }
                }
            }
        }

        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        }

        context.registerReceiver(receiver, intentFilter)
        Log.d(TAG, "BroadcastReceiver registered")

        // Kick off peer discovery — results arrive via WIFI_P2P_PEERS_CHANGED_ACTION
        wifiP2pManager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Miracast peer discovery started successfully")
            }
            override fun onFailure(reason: Int) {
                // reason: ERROR=0, P2P_UNSUPPORTED=1, BUSY=2
                Log.e(TAG, "Miracast peer discovery failed: reason=$reason")
            }
        })

        awaitClose {
            Log.d(TAG, "Stopping Miracast discovery")
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
            wifiP2pManager.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {}
                override fun onFailure(reason: Int) {}
            })
            channel.close()
        }
    }
}
