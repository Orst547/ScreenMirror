package com.example.screenmirror.data.network.streaming

import android.util.Log
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * A minimal MPEG-TS Muxer that wraps H.264 NAL units (AnnexB) into TS packets.
 * This is a lightweight implementation for local network mirroring.
 * 
 * Flow:
 * 1. Receive H.264 NAL unit from MediaCodec.
 * 2. Wrap into PES (Packetized Elementary Stream) header with PTS.
 * 3. Split into 188-byte TS packets.
 * 4. Periodically insert PAT and PMT packets.
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
        
        // Stream types
        private const val STREAM_TYPE_H264 = 0x1B.toByte()
    }

    private var videoCc: Int = 0 // Continuity Counter for Video
    private var patPmtCc: Int = 0 // Continuity Counter for Tables
    private var frameCount: Int = 0

    /**
     * Called when a new H.264 NAL unit is ready.
     * ptsUs: Presentation timestamp in microseconds.
     */
    fun writeVideoFrame(data: ByteBuffer, ptsUs: Long, isConfigFrame: Boolean = false) {
        if (isConfigFrame || frameCount % 30 == 0) {
            writePat()
            writePmt()
        }

        val frameData = ByteArray(data.remaining())
        data.get(frameData)
        
        // 1. Create PES packet
        val pesPacket = createVideoPesPacket(frameData, ptsUs)
        
        // 2. Fragment into TS packets
        fragmentIntoTsPackets(pesPacket, PID_VIDEO)
        
        frameCount++
    }

    private fun writePat() {
        val packet = ByteArray(TS_PACKET_SIZE) { 0xFF.toByte() }
        var pos = 0
        
        // TS Header (4 bytes)
        packet[pos++] = SYNC_BYTE
        packet[pos++] = (0x40 or (PID_PAT shr 8)).toByte() // Payload start indicator + PID
        packet[pos++] = (PID_PAT and 0xFF).toByte()
        packet[pos++] = (0x10 or (patPmtCc % 16)).toByte() // Adaption field=0, payload=1 + CC

        // PAT Payload (Pointer field + Table)
        packet[pos++] = 0x00 // Pointer field
        
        // Minimal PAT: Table ID 0, Section Length 13, Transport Stream ID 1, Version 0, Current 1
        val pat = byteArrayOf(
            0x00, (0xB0).toByte(), 0x0D, 0x00, 0x01, (0xC1).toByte(), 0x00, 0x00, 
            0x00, 0x01, (0xE0 or (PID_PMT shr 8)).toByte(), (PID_PMT and 0xFF).toByte(),
            0x00, 0x00, 0x00, 0x00 // CRC placeholder (Not strictly needed for some naive players but good for spec)
        )
        System.arraycopy(pat, 0, packet, pos, pat.size)
        
        output.write(packet)
    }

    private fun writePmt() {
        val packet = ByteArray(TS_PACKET_SIZE) { 0xFF.toByte() }
        var pos = 0
        
        // TS Header (4 bytes)
        packet[pos++] = SYNC_BYTE
        packet[pos++] = (0x40 or (PID_PMT shr 8)).toByte()
        packet[pos++] = (PID_PMT and 0xFF).toByte()
        packet[pos++] = (0x10 or (patPmtCc % 16)).toByte()
        patPmtCc++

        packet[pos++] = 0x00 // Pointer field
        
        // Minimal PMT: Table ID 2, Video Stream Type 0x1B
        val pmt = byteArrayOf(
            0x02, (0xB0).toByte(), 0x12, 0x00, 0x01, (0xC1).toByte(), 0x00, 0x00, 
            (0xE0 or (PID_VIDEO shr 8)).toByte(), (PID_VIDEO and 0xFF).toByte(), // PCR PID
            (0xF0).toByte(), 0x00, // Program Info length
            STREAM_TYPE_H264, (0xE0 or (PID_VIDEO shr 8)).toByte(), (PID_VIDEO and 0xFF).toByte(),
            (0xF0).toByte(), 0x00, // ES Info length
            0x00, 0x00, 0x00, 0x00 // CRC placeholder
        )
        System.arraycopy(pmt, 0, packet, pos, pmt.size)
        
        output.write(packet)
    }

    private fun createVideoPesPacket(frame: ByteArray, ptsUs: Long): ByteArray {
        val pts = (ptsUs * 9 / 100) // Convert to 90kHz clock
        
        // PES Header (at least 14 bytes for video with PTS)
        // 00 00 01 
        // Stream ID (E0 for Video)
        // PES Packet Length (00 00 for unbounded or length)
        // Flags (80 80 for PTS only)
        // Header Data Length (05 for PTS)
        // PTS (5 bytes)
        
        val header = ByteArray(14)
        header[0] = 0x00
        header[1] = 0x00
        header[2] = 0x01
        header[3] = 0xE0.toByte() // Video stream 0
        
        val length = frame.size + 14 - 6
        header[4] = (length shr 8).toByte()
        header[5] = (length and 0xFF).toByte()
        
        header[6] = 0x80.toByte() // 10xxxxxx
        header[7] = 0x80.toByte() // PTS only
        header[8] = 0x05 // Header data length
        
        // PTS coding (33 bits)
        header[9] = (0x21 or ((pts shr 29) and 0x0E).toInt()).toByte()
        header[10] = ((pts shr 22) and 0xFF).toByte()
        header[11] = (((pts shr 14) and 0xFE) or 1).toByte()
        header[12] = ((pts shr 7) and 0xFF).toByte()
        header[13] = (((pts shl 1) and 0xFE) or 1).toByte()
        
        val pes = ByteArray(header.size + frame.size)
        System.arraycopy(header, 0, pes, 0, header.size)
        System.arraycopy(frame, 0, pes, header.size, frame.size)
        return pes
    }

    private fun fragmentIntoTsPackets(pes: ByteArray, pid: Int) {
        var offset = 0
        while (offset < pes.size) {
            val packet = ByteArray(TS_PACKET_SIZE) { 0xFF.toByte() }
            var pos = 0
            
            // Header
            packet[pos++] = SYNC_BYTE
            var flags = (pid shr 8)
            if (offset == 0) flags = flags or 0x40 // Payload Unit Start Indicator
            packet[pos++] = flags.toByte()
            packet[pos++] = (pid and 0xFF).toByte()
            packet[pos++] = (0x10 or (videoCc % 16)).toByte()
            videoCc++
            
            val remaining = pes.size - offset
            val payloadToCopy = Math.min(remaining, TS_PAYLOAD_SIZE)
            
            // If we have less than 184 bytes, we need an adaptation field for padding
            if (payloadToCopy < TS_PAYLOAD_SIZE) {
                // Adjust header to include adaptation field
                packet[3] = (packet[3].toInt() or 0x20).toByte() // Adaptation field flag
                
                val paddingSize = TS_PAYLOAD_SIZE - payloadToCopy
                packet[pos++] = (paddingSize - 1).toByte() // Adaptation length
                if (paddingSize > 1) {
                    packet[pos++] = 0x00 // No flags
                    // Rest is filled with FF by default initialization
                }
                pos = TS_PACKET_SIZE - payloadToCopy
            }
            
            System.arraycopy(pes, offset, packet, pos, payloadToCopy)
            output.write(packet)
            offset += payloadToCopy
        }
    }
}
