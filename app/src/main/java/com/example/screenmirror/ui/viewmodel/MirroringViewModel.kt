package com.example.screenmirror.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.screenmirror.data.network.miracast.MiracastDiscoveryManager
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import androidx.mediarouter.media.MediaRouter
import androidx.mediarouter.media.MediaRouteSelector
import com.google.android.gms.cast.CastMediaControlIntent
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
import kotlinx.coroutines.flow.collectLatest
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

    // El CastContext debe inicializarse en el hilo principal
    private var castContext: CastContext? = null
    private val mediaRouter = MediaRouter.getInstance(appContext)
    private val castSelector = MediaRouteSelector.Builder()
        .addControlCategory(CastMediaControlIntent.categoryForCast("CC1AD845"))
        .build()

    // Store RouteInfo to select it later
    private val castRoutesMap = mutableMapOf<String, MediaRouter.RouteInfo>()

    // Callback that will be triggered when the session is started
    private var pendingPermissionRequest: ((Intent) -> Unit)? = null

    private val castSessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            Log.d(TAG, "CastSession started: $sessionId")
            pendingPermissionRequest?.let { request ->
                val projectionManager = appContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                        as android.media.projection.MediaProjectionManager
                request(projectionManager.createScreenCaptureIntent())
            }
            pendingPermissionRequest = null
        }

        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            Log.e(TAG, "CastSession failed to start: $error")
            pendingPermissionRequest = null
            _selectedRoute.value = null
        }
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionEnded(session: CastSession, error: Int) {
            stopMirroring()
        }
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    private val mediaRouterCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) {
            if (route.matchesSelector(castSelector)) {
                val mirrorRoute = MirrorRoute(
                    id = "cast_${route.id}",
                    name = route.name,
                    description = "Chromecast",
                    protocol = DeviceProtocol.CHROMECAST
                )
                castRoutesMap[mirrorRoute.id] = route
                _availableRoutes.update { current ->
                    if (current.none { it.id == mirrorRoute.id }) current + mirrorRoute else current
                }
            }
        }

        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) {
            _availableRoutes.update { current ->
                current.filter { it.id != "cast_${route.id}" }
            }
        }
    }

    private val _availableRoutes = MutableStateFlow<List<MirrorRoute>>(emptyList())
    val availableRoutes: StateFlow<List<MirrorRoute>> = _availableRoutes.asStateFlow()

    private val _isMirroring = MutableStateFlow(false)
    val isMirroring: StateFlow<Boolean> = _isMirroring.asStateFlow()

    private val _selectedRoute = MutableStateFlow<MirrorRoute?>(null)
    val selectedRoute: StateFlow<MirrorRoute?> = _selectedRoute.asStateFlow()

    private val _isReceiverConnected = MutableStateFlow(false)
    val isReceiverConnected: StateFlow<Boolean> = _isReceiverConnected.asStateFlow()

    private var nsdJob      : Job? = null
    private var miracastJob : Job? = null
    private var dlnaJob     : Job? = null

    init {
        try {
            castContext = CastContext.getSharedInstance(appContext)
            castContext?.sessionManager?.addSessionManagerListener(castSessionListener, CastSession::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Error inicializando CastContext: ${e.message}")
        }
        
        // Registrar MediaRouter inmediatamente en el hilo principal (Main Thread)
        mediaRouter.addCallback(castSelector, mediaRouterCallback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        
        viewModelScope.launch {
            delay(1000)
            startDiscovery()
        }
        observeReceiverStatus()
    }

    private fun observeReceiverStatus() {
        viewModelScope.launch {
            MirroringService.activeClients.collectLatest { count ->
                _isReceiverConnected.value = count > 0
            }
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

        if (route.protocol == DeviceProtocol.CHROMECAST) {
            // Intentamos buscar la ruta por ID o por Nombre (para sincronizar NSD con el SDK)
            var castRoute = castRoutesMap[route.id]
            
            if (castRoute == null) {
                // Búsqueda de emergencia por nombre en todas las rutas del MediaRouter
                castRoute = mediaRouter.routes.find { it.name == route.name }
                if (castRoute != null) {
                    Log.d(TAG, "Found matching MediaRouter route by name: ${route.name}")
                    castRoutesMap[route.id] = castRoute
                }
            }

            if (castRoute != null) {
                pendingPermissionRequest = onPermissionRequired
                mediaRouter.selectRoute(castRoute)
                Log.d(TAG, "Chromecast route selected in MediaRouter... waiting for session")
                return 
            } else {
                Log.w(TAG, "Could not find MediaRouter.RouteInfo for ${route.name}. Falling back to basic projection.")
            }
        }
        
        // Default (DLNA, AirPlay, or Chromecast fallback)
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

        // 2. Load stream URL in the receiver
        viewModelScope.launch {
            delay(3000) // Aumentamos el tiempo para asegurar que el servidor Ktor esté listo
            val localIp = getLocalIpAddress()
            if (localIp == null) {
                Log.e(TAG, "No se pudo obtener IP local para el cast")
                return@launch
            }
            val streamUrl = "http://$localIp:8080/live.ts"
            Log.d(TAG, "Stream URL preparado: $streamUrl")

            if (route.protocol == DeviceProtocol.DLNA) {
                dlnaDiscovery.castToDevice(route.id, streamUrl)
            } else if (route.protocol == DeviceProtocol.CHROMECAST) {
                val castSession = castContext?.sessionManager?.currentCastSession
                if (castSession == null) {
                    Log.e(TAG, "No hay sesión Cast activa")
                    return@launch
                }

                val mediaInfo = com.google.android.gms.cast.MediaInfo.Builder(streamUrl)
                    .setStreamType(com.google.android.gms.cast.MediaInfo.STREAM_TYPE_LIVE)
                    .setContentType("video/mp2t")
                    .build()

                val loadRequest = com.google.android.gms.cast.MediaLoadRequestData.Builder()
                    .setMediaInfo(mediaInfo)
                    .build()

                castSession.remoteMediaClient?.load(loadRequest)
                    ?.setResultCallback { result ->
                        if (!result.status.isSuccess) {
                            Log.e(TAG, "Cast load FAILED: ${result.status.statusMessage} (code ${result.status.statusCode})")
                        } else {
                            Log.d(TAG, "Cast load SUCCESS")
                        }
                    }
                Log.d(TAG, "Cargando stream en Chromecast: $streamUrl")
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
        mediaRouter.removeCallback(mediaRouterCallback)
        castContext?.sessionManager?.removeSessionManagerListener(castSessionListener, CastSession::class.java)
        super.onCleared()
    }

    // ─── Private ─────────────────────────────────────────────────────────────

    /**
     * Collects AirPlay devices from NSD. (Chromecast now handled by MediaRouter)
     */
    private fun startNsdDiscovery() {
        nsdJob = viewModelScope.launch {
            nsdDiscovery.discoverAllDevices()
                .catch { e ->
                    Log.e(TAG, "NSD discovery error: ${e.message}")
                    e.printStackTrace()
                }
                .collect { route ->
                    // YA NO IGNORAMOS CHROMECAST de NSD. Se mostrarán si el SDK no los encuentra.
                    // Si el SDK ya los tiene (prefijo 'cast_'), evitamos duplicados.
                    
                    Log.d(TAG, "NSD device found: ${route.name} (${route.protocol.displayName}) @ ${route.ipAddress}:${route.port}")
                    _availableRoutes.update { current ->
                        if (current.none { it.id == route.id || it.name == route.name }) current + route else current
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
