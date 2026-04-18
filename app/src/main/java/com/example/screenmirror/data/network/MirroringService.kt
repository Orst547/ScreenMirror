package com.example.screenmirror.data.network

import android.app.*
import android.util.Log
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.screenmirror.data.network.streaming.MpegTsMuxer
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import java.io.OutputStream
import java.nio.ByteBuffer

class MirroringService : Service() {
    
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    
    private val NOTIFICATION_ID = 101
    private val CHANNEL_ID = "mirroring_channel"
    
    private var isStreaming = false
    private var encoderThread: HandlerThread? = null
    private var encoderHandler: Handler? = null
    
    companion object {
        private val _activeClients = MutableStateFlow(0)
        val activeClients: StateFlow<Int> = _activeClients.asStateFlow()
    }
    
    // Ktor Server & Muxing
    private var ktorServer: EmbeddedServer<*, *>? = null
    // Flujo compartido para que múltiples clientes reciban el mismo video sin "robar" paquetes
    private val tsPacketFlow = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 128, 
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val tsPacketCount = java.util.concurrent.atomic.AtomicInteger(0)
    private var muxer: MpegTsMuxer? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupMuxer()
        startKtorServer()
    }

    private fun setupMuxer() {
        // El Muxer escribe a un OutputStream que envía directamente al Canal
        val broadcastStream = object : OutputStream() {
            override fun write(b: Int) {
                // Fallback byte-a-byte (protección ante JVM interna)
                tsPacketFlow.tryEmit(byteArrayOf(b.toByte()))
            }
            override fun write(b: ByteArray, off: Int, len: Int) {
                val chunk = b.copyOfRange(off, off + len)
                // Emitimos al flujo compartido; si no hay suscriptores, se descarta (ahorra CPU)
                tsPacketFlow.tryEmit(chunk)
                tsPacketCount.incrementAndGet() // Contador real de paquetes emitidos
            }
        }
        muxer = MpegTsMuxer(broadcastStream)
    }

    private fun startKtorServer() {
        val server = embeddedServer(CIO, port = 8080) {
            routing {
                get("/live.ts") {
                    _activeClients.value += 1
                    
                    // Forzamos un frame de sincronización en el encoder y headers en el muxer
                    requestSyncFrame()
                    muxer?.forceNextHeaders()
                    Log.d("MirroringService", "Cliente conectado al stream. Solicitando I-Frame...")

                    // Cabeceras de compatibilidad y CORS manual
                    call.response.header("Access-Control-Allow-Origin", "*")
                    call.response.header("Cache-Control", "no-cache, no-store, must-revalidate")
                    call.response.header("Connection", "keep-alive")

                    call.respondBytesWriter(contentType = io.ktor.http.ContentType("video", "mp2t")) {
                        try {
                            tsPacketFlow.collect { chunk ->
                                writeFully(chunk)
                                flush()
                            }
                        } catch (e: Exception) {
                            Log.d("MirroringService", "Cliente desconectado del stream: ${e.message}")
                        } finally {
                            _activeClients.update { (it - 1).coerceAtLeast(0) }
                        }
                    }
                }
            }
        }
        ktorServer = server
        server.start(wait = false)
        Log.d("MirroringService", "Ktor server started on port 8080")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("DATA")
        }

        if (data != null) {
            startForegroundService()
            setupMirroring(resultCode, data)
        } else {
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startForegroundService() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Mirroring Active")
            .setContentText("Transmitting your screen to the device...")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun setupMirroring(resultCode: Int, data: Intent) {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(metrics)
        }

        // 1. Setup MediaCodec (Encoder) con posible escalado a 720p dinámico
        val useScaling = true // Forzamos escalado para mayor fluidez en redes Wi-Fi
        val targetWidth = if (useScaling) 720 else metrics.widthPixels
        val targetHeight = if (useScaling) (metrics.heightPixels * 720 / metrics.widthPixels) else metrics.heightPixels

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, targetWidth, targetHeight)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 2500000) // Bajamos a 2.5Mbps iniciales
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // Enviamos I-Frame cada segundo para mayor rapidez
        
        // Perfil Baseline en la configuración inicial (sin Level para evitar BAD_INDEX)
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)

        mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        
        mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = mediaCodec?.createInputSurface()
        mediaCodec?.start()

        // 2. Setup MediaProjection
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        // Android 14+ requires registering a callback before creating VirtualDisplay
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                this@MirroringService.stopSelf()
            }
        }, Handler(Looper.getMainLooper()))

        // 3. Create VirtualDisplay using the Encoder's Surface
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "MirroringDisplay",
            targetWidth,
            targetHeight,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface,
            null,
            null
        )

        startStreamingLoop()
    }

    private fun startStreamingLoop() {
        isStreaming = true
        encoderThread = HandlerThread("EncoderThread")
        encoderThread?.start()
        encoderHandler = Handler(encoderThread!!.looper)

        encoderHandler?.post(object : Runnable {
            override fun run() {
                if (!isStreaming) return

                try {
                    val bufferInfo = MediaCodec.BufferInfo()
                    val outputBufferIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, 10000) ?: -1
                    
                    if (outputBufferIndex >= 0) {
                        val outputBuffer = mediaCodec?.getOutputBuffer(outputBufferIndex)
                        
                        if (outputBuffer != null) {
                            sendDataToRemoteDevice(outputBuffer, bufferInfo)
                        }
                        
                        mediaCodec?.releaseOutputBuffer(outputBufferIndex, false)
                    }
                } catch (e: IllegalStateException) {
                    // El codec se cerró mientras esperábamos, detenemos el loop pacíficamente
                    isStreaming = false
                }
                
                if (isStreaming) {
                    // Ajuste de bitrate dinámico basado en la congestión de la cola del Canal
                    adjustBitrateDynamically()
                    encoderHandler?.post(this)
                }
            }
        })
    }

    private var lastBitrateUpdateTime = 0L
    private var currentBitrate = 4000000
    private fun adjustBitrateDynamically() {
        val now = System.currentTimeMillis()
        if (now - lastBitrateUpdateTime < 1000) return

        val queueSize = tsPacketCount.get()
        val targetBitrate = if (queueSize > 80) {
             1000000 // Muy congestionado
        } else if (queueSize > 40) {
             2500000 // Algo congestionado
        } else {
             4000000 // Fluido
        }
        
        if (targetBitrate != currentBitrate) {
            currentBitrate = targetBitrate
            lastBitrateUpdateTime = now
            val params = android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, currentBitrate)
            }
            mediaCodec?.setParameters(params)
        }
    }

    private fun requestSyncFrame() {
        try {
            val params = android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            mediaCodec?.setParameters(params)
        } catch (e: Exception) {
            Log.e("MirroringService", "Error solicitando Sync Frame: ${e.message}")
        }
    }

    private fun sendDataToRemoteDevice(buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
        val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        muxer?.writeVideoFrame(buffer, bufferInfo.presentationTimeUs, isConfig, isKeyFrame)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Mirroring Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isStreaming = false
        encoderThread?.quitSafely()
        virtualDisplay?.release()
        inputSurface?.release()
        mediaCodec?.stop()
        mediaCodec?.release()
        mediaProjection?.stop()
        
        ktorServer?.stop(1000, 2000)
        serviceScope.cancel()
        
        super.onDestroy()
    }
}
