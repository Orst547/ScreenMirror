package com.example.screenmirror.data.network.streaming

import android.util.Log
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * A minimal MPEG-TS Muxer that wraps H.264 NAL units into TS packets.
 * This implementation enforces the strict MPEG-TS standard required by Chromecast (CRC32, CC, PTS).
 */
class MpegTsMuxer(private val output: OutputStream) {

    companion object {
        private const val TAG = "MpegTsMuxer"
        private const val TS_PACKET_SIZE = 188
        private const val TS_PAYLOAD_SIZE = 184
        private const val SYNC_BYTE = 0x47.toByte()

        private const val PID_PAT = 0x0000
        private const val PID_PMT = 0x1000
        private const val PID_VIDEO = 0x0100
        private const val STREAM_TYPE_H264 = 0x1B.toByte()
    }

    private var videoCc = 0
    private var patCc = 0
    private var pmtCc = 0
    private var frameCount = 0
    private var forceHeaders = false
    
    // Buffer para SPS/PPS (Codec Config)
    private var configBuffer: ByteArray? = null

    fun forceNextHeaders() {
        forceHeaders = true
    }

    /**
     * Called when a new H.264 NAL unit is ready.
     * ptsUs: Presentation timestamp in microseconds.
     */
    fun writeVideoFrame(data: ByteBuffer, ptsUs: Long, isConfigFrame: Boolean = false, isKeyFrame: Boolean = false) {
        if (isConfigFrame) {
            val config = ByteArray(data.remaining())
            data.get(config)
            configBuffer = config
            Log.d(TAG, "Codec Config (SPS/PPS) buffered")
            return // No enviamos la config sola, esperamos al siguiente frame
        }

        if (forceHeaders || frameCount % 30 == 0 || isKeyFrame) {
            writePat()
            writePmt()
            forceHeaders = false
        }

        var frameData = ByteArray(data.remaining())
        data.get(frameData)
        
        // Si es un Key Frame, le pegamos la configuración (SPS/PPS) al inicio (Annex B)
        if (isKeyFrame && configBuffer != null) {
            frameData = configBuffer!! + frameData
            Log.d(TAG, "Prepending SPS/PPS to Key Frame")
        }

        val pesPacket = createVideoPesPacket(frameData, ptsUs)
        fragmentIntoTsPackets(pesPacket, PID_VIDEO)
        frameCount++
    }

    private fun writePat() {
        val packet = ByteArray(TS_PACKET_SIZE) { 0xFF.toByte() }
        var pos = 0
        
        // TS Header (4 bytes)
        packet[pos++] = SYNC_BYTE
        packet[pos++] = (0x40 or (PID_PAT shr 8)).toByte() // PUSI + PID
        packet[pos++] = (PID_PAT and 0xFF).toByte()
        packet[pos++] = (0x10 or (patCc % 16)).toByte() // Adaption=0, Payload=1 + CC
        patCc++

        packet[pos++] = 0x00 // Pointer field
        
        // PAT table section (13 bytes before CRC)
        val patSection = byteArrayOf(
            0x00,                      // Table ID
            (0xB0).toByte(), 0x0D,     // Section syntax indicator + Length (13)
            0x00, 0x01,                // Transport Stream ID
            (0xC1).toByte(),           // Version 0, Current=1
            0x00, 0x00,                // Section Number / Last
            0x00, 0x01,                // Program Number 1
            (0xE0 or (PID_PMT shr 8)).toByte(), (PID_PMT and 0xFF).toByte()
        )
        
        val crc = calculateCrc32(patSection)
        System.arraycopy(patSection, 0, packet, pos, patSection.size)
        pos += patSection.size
        
        packet[pos++] = (crc shr 24).toByte()
        packet[pos++] = (crc shr 16).toByte()
        packet[pos++] = (crc shr 8).toByte()
        packet[pos++] = (crc and 0xFF).toByte()
        
        output.write(packet)
    }

    private fun writePmt() {
        val packet = ByteArray(TS_PACKET_SIZE) { 0xFF.toByte() }
        var pos = 0
        
        packet[pos++] = SYNC_BYTE
        packet[pos++] = (0x40 or (PID_PMT shr 8)).toByte()
        packet[pos++] = (PID_PMT and 0xFF).toByte()
        packet[pos++] = (0x10 or (pmtCc % 16)).toByte()
        pmtCc++

        packet[pos++] = 0x00 // Pointer field
        
        // PMT table section (18 bytes before CRC)
        val pmtSection = byteArrayOf(
            0x02,                      // Table ID
            (0xB0).toByte(), 0x12,     // Section syntax indicator + Length (18)
            0x00, 0x01,                // Program Number
            (0xC1).toByte(),           // Version 0, Current=1
            0x00, 0x00,                // Section Number / Last
            (0xE0 or (PID_VIDEO shr 8)).toByte(), (PID_VIDEO and 0xFF).toByte(), // PCR PID
            (0xF0).toByte(), 0x00,     // Program Info Length=0
            STREAM_TYPE_H264, (0xE0 or (PID_VIDEO shr 8)).toByte(), (PID_VIDEO and 0xFF).toByte(),
            (0xF0).toByte(), 0x00      // ES Info Length=0
        )
        
        val crc = calculateCrc32(pmtSection)
        System.arraycopy(pmtSection, 0, packet, pos, pmtSection.size)
        pos += pmtSection.size
        
        packet[pos++] = (crc shr 24).toByte()
        packet[pos++] = (crc shr 16).toByte()
        packet[pos++] = (crc shr 8).toByte()
        packet[pos++] = (crc and 0xFF).toByte()
        
        output.write(packet)
    }

    private fun createVideoPesPacket(frame: ByteArray, ptsUs: Long): ByteArray {
        val pts = ptsUs * 90L / 1000L // Microseconds to 90kHz without overflow
        
        val header = ByteArray(14)
        header[0] = 0x00
        header[1] = 0x00
        header[2] = 0x01
        header[3] = 0xE0.toByte() // Video stream id
        
        // Para video en vivo, pesPacketLength se suele poner a 0 (unbounded)
        // Esto evita que la TV busque el final de un paquete que es infinito.
        header[4] = 0x00
        header[5] = 0x00
        
        header[6] = 0x80.toByte() // 10xxxxxx
        header[7] = 0x80.toByte() // PTS only
        header[8] = 0x05 // Header data length
        
        // PTS coding (33 bits) — ISO 13818-1 Table 2-22
        // Bits de marcador (marker_bit=1) OBLIGATORIOS en posiciones pares
        header[9]  = (0x21 or ((pts shr 29) and 0x0E).toInt()).toByte()  // '0010' + PTS[32..30] + marker
        header[10] = ((pts shr 22) and 0xFF).toByte()                    // PTS[29..22]
        header[11] = (((pts shr 14) and 0xFE) or 0x01).toByte()         // PTS[21..15] + marker
        header[12] = ((pts shr 7) and 0xFF).toByte()                     // PTS[14..7]
        header[13] = (((pts shl 1) and 0xFE) or 0x01).toByte()          // PTS[6..0] + marker
        
        return header + frame
    }

    private fun fragmentIntoTsPackets(pes: ByteArray, pid: Int) {
        var offset = 0
        while (offset < pes.size) {
            val packet = ByteArray(TS_PACKET_SIZE) { 0xFF.toByte() }
            var pos = 0
            
            packet[pos++] = SYNC_BYTE
            var high = (pid shr 8)
            if (offset == 0) high = high or 0x40 
            packet[pos++] = high.toByte()
            packet[pos++] = (pid and 0xFF).toByte()
            
            val remaining = pes.size - offset
            val payloadToCopy = minOf(remaining, TS_PAYLOAD_SIZE)
            
            if (payloadToCopy < TS_PAYLOAD_SIZE) {
                // Adaptation field for padding
                val paddingSize = TS_PAYLOAD_SIZE - payloadToCopy
                packet[pos++] = (0x30 or (videoCc % 16)).toByte() // Adaptation + Payload
                videoCc++
                packet[pos++] = (paddingSize - 1).toByte() // Length
                if (paddingSize > 1) {
                    packet[pos++] = 0x00 // Flags
                }
                pos = TS_PACKET_SIZE - payloadToCopy
            } else {
                packet[pos++] = (0x10 or (videoCc % 16)).toByte() // Payload only
                videoCc++
            }
            
            System.arraycopy(pes, offset, packet, pos, payloadToCopy)
            output.write(packet)
            offset += payloadToCopy
        }
    }

    /**
     * Bitwise implementation of bitwise CRC32 for MPEG-TS (MPEG-2 Annex B).
     * Polynomial: 0x04C11DB7, Init: 0xFFFFFFFF, No Ref, No XOR out.
     */
    private fun calculateCrc32(data: ByteArray): Int {
        var crc = 0xFFFFFFFF.toInt()
        for (b in data) {
            crc = crc xor (b.toInt() shl 24)
            repeat(8) {
                crc = if (crc and 0x80000000.toInt() != 0)
                    (crc shl 1) xor 0x04C11DB7
                else
                    crc shl 1
            }
        }
        return crc
    }
}