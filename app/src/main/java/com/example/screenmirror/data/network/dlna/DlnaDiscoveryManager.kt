package com.example.screenmirror.data.network.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.example.screenmirror.data.network.nsd.NetworkDiscoveryManager
import com.example.screenmirror.domain.models.DeviceProtocol
import com.example.screenmirror.domain.models.MirrorRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.Collections

/**
 * Discovers DLNA/UPnP compatible TVs and media renderers on the local network using SSDP.
 * This implementation is 100% native and does not rely on external libraries.
 */
class DlnaDiscoveryManager(private val context: Context) {

    companion object {
        private const val TAG = "DlnaDiscovery"
        private const val SSDP_MULTICAST_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900
    }

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    fun discoverDevices(): Flow<MirrorRoute> = flow {
        Log.d(TAG, "Starting manual SSDP discovery...")
        
        val multicastLock = wifiManager.createMulticastLock(NetworkDiscoveryManager.MULTICAST_LOCK_TAG).apply {
            setReferenceCounted(true)
            acquire()
        }

        try {
            while (true) {
                val devices = performSsdpSearch()
                devices.forEach { emit(it) }
                delay(15000) // Poll every 15 seconds
            }
        } finally {
            if (multicastLock.isHeld) multicastLock.release()
        }
    }

    private suspend fun performSsdpSearch(): List<MirrorRoute> = withContext(Dispatchers.IO) {
        val found = mutableListOf<MirrorRoute>()
        // Use MulticastSocket for better compatibility with SSDP
        val socket = try { MulticastSocket() } catch (e: Exception) { 
            Log.e(TAG, "Could not create MulticastSocket: ${e.message}")
            return@withContext emptyList()
        }
        
        socket.soTimeout = 3000 // 3-second timeout

        try {
            // Encontrar la interfaz de red activa que soporte Multicast (Wi-Fi preferentemente)
            val interfaces = NetworkInterface.getNetworkInterfaces()
            var wifiInterface: NetworkInterface? = null
            var localAddress: InetAddress? = null

            for (networkInterface in Collections.list(interfaces)) {
                if (networkInterface.isUp && networkInterface.supportsMulticast() && !networkInterface.isLoopback) {
                    val addresses = networkInterface.inetAddresses
                    for (addr in Collections.list(addresses)) {
                        if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                            wifiInterface = networkInterface
                            localAddress = addr
                            break
                        }
                    }
                }
                if (wifiInterface != null) break
            }
            
            if (wifiInterface != null && localAddress != null) {
                // Vincular el socket a la dirección local para evitar errores EPERM en Android 14+
                socket.networkInterface = wifiInterface
            } else {
                Log.w(TAG, "No se encontró una interfaz Wi-Fi compatible con Multicast")
            }

            // M-SEARCH Message
            val mSearch = (
                "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: $SSDP_MULTICAST_ADDRESS:$SSDP_PORT\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 2\r\n" +
                "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
                "\r\n"
            ).toByteArray()

            val group = InetAddress.getByName(SSDP_MULTICAST_ADDRESS)
            val packet = DatagramPacket(mSearch, mSearch.size, group, SSDP_PORT)
            
            // Join group (some devices require this to send/receive correctly)
            try {
                if (wifiInterface != null) {
                    socket.joinGroup(java.net.InetSocketAddress(group, 0), wifiInterface)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Join group failed, continuing: ${e.message}")
            }

            socket.send(packet)

            // Listen for responses
            val receiveBuf = ByteArray(2048)
            while (true) {
                try {
                    val receivePacket = DatagramPacket(receiveBuf, receiveBuf.size)
                    socket.receive(receivePacket)
                    val response = String(receivePacket.data, 0, receivePacket.length)
                    
                    val remoteIp = receivePacket.address.hostAddress
                    if (remoteIp != null) {
                        val route = parseSsdpResponse(response, remoteIp)
                        if (route != null) {
                            found.add(route)
                        }
                    }
                } catch (e: Exception) {
                    // Socket timeout - no more devices
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "SSDP Search failed: ${e.message}")
        } finally {
            try { socket.close() } catch (e: Exception) { /* ignore */ }
        }
        found.distinctBy { it.id }
    }

    private fun parseSsdpResponse(response: String, ip: String): MirrorRoute? {
        // Find LOCATION or SERVER headers to extract some info
        val location = response.lines().firstOrNull { it.uppercase().startsWith("LOCATION:") }
            ?.substringAfter(":")?.trim()
        
        // In a real app, we'd fetch the XML at 'location' for 'friendlyName'.
        // For this minimal implementation, we'll use the IP as the name for now.
        return if (location != null) {
            MirrorRoute(
                id = "dlna_${ip.replace(".", "_")}",
                name = "Smart TV ($ip)",
                description = "Universal Media Player • $ip",
                protocol = DeviceProtocol.DLNA,
                ipAddress = ip,
                port = 80 // Default for DLNA, but usually specified in LOCATION XML
            )
        } else null
    }

    fun castToDevice(deviceId: String, streamUrl: String) {
        // To implement this, we'd need to send a SOAP POST request to the device.
        // It's a bit more complex, but possible with simple HTTP POST.
        Log.d(TAG, "Commanding $deviceId to play $streamUrl via DLNA...")
    }
}
