package com.homepod.airplay.protocol

import android.util.Log
import com.homepod.airplay.crypto.AirPlayCrypto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher

class RtpAudioSender(
    private val targetIp: String,
    private val serverPort: Int,
    private val controlPort: Int,
    private val timingPort: Int,
    private val aesKey: ByteArray,
    private val aesIv: ByteArray
) {
    companion object {
        private const val TAG = "RtpAudioSender"
        const val FRAMES_PER_PACKET = 352
        const val BYTES_PER_FRAME = 4 // 16-bit stereo (2 bytes L + 2 bytes R)
        const val CHUNK_SIZE = FRAMES_PER_PACKET * BYTES_PER_FRAME // 1408 bytes
        private const val NTP_OFFSET = 0x83AA7E80L
    }

    private var audioSocket: DatagramSocket? = null
    var controlSocket: DatagramSocket? = null
        private set
    var timingSocket: DatagramSocket? = null
        private set

    val localControlPort: Int
        get() = controlSocket?.localPort ?: 0
    val localTimingPort: Int
        get() = timingSocket?.localPort ?: 0

    private val audioQueue = ArrayBlockingQueue<ByteArray>(64)
    private var streamJob: Job? = null
    private var timingJob: Job? = null

    @Volatile
    private var isStreaming = false

    private val aesCipher: Cipher by lazy {
        AirPlayCrypto.createAesCipher(aesKey, aesIv)
    }

    private val bitWriter = BitWriter(2048)
    private val packetBuffer = ByteArray(2048)

    private var sequenceNumber = 0
    private var rtpTimestamp = 0L
    private val ssrc = (AirPlayCrypto.generateRandomBytes(4).let {
        ByteBuffer.wrap(it).int
    })
    private var packetCount = 0

    init {
        controlSocket = DatagramSocket()
        timingSocket = DatagramSocket()
        audioSocket = DatagramSocket()
    }

    fun start(scope: CoroutineScope) {
        if (isStreaming) return
        isStreaming = true

        startTimingListener(scope)
        startAudioStreamer(scope)
    }

    fun stop() {
        isStreaming = false
        streamJob?.cancel()
        timingJob?.cancel()
        audioQueue.clear()

        try { audioSocket?.close() } catch (_: Exception) {}
        try { controlSocket?.close() } catch (_: Exception) {}
        try { timingSocket?.close() } catch (_: Exception) {}

        audioSocket = null
        controlSocket = null
        timingSocket = null
    }

    fun queueAudio(pcmData: ByteArray, length: Int) {
        if (!isStreaming) return

        var offset = 0
        while (offset + CHUNK_SIZE <= length) {
            val chunk = ByteArray(CHUNK_SIZE)
            System.arraycopy(pcmData, offset, chunk, 0, CHUNK_SIZE)
            if (!audioQueue.offer(chunk)) {
                // If queue is full, drop oldest frame to maintain low latency
                audioQueue.poll()
                audioQueue.offer(chunk)
            }
            offset += CHUNK_SIZE
        }
    }

    private fun startTimingListener(scope: CoroutineScope) {
        timingJob = scope.launch(Dispatchers.IO) {
            val socket = timingSocket ?: return@launch
            val recvBuffer = ByteArray(128)
            val recvPacket = DatagramPacket(recvBuffer, recvBuffer.size)

            val replyBuffer = ByteArray(32)
            val replyPacket = DatagramPacket(replyBuffer, replyBuffer.size)

            Log.d(TAG, "Timing listener started on UDP port ${socket.localPort}")

            while (isActive && isStreaming) {
                try {
                    socket.receive(recvPacket)
                    val len = recvPacket.length
                    if (len >= 32) {
                        // Received timing request from HomePod
                        val recvTime = getNtpTimestamp()

                        // Extract remote NTP timestamp from offset 24 (or 16)
                        val remoteTimeHigh = ByteBuffer.wrap(recvBuffer, 24, 4).int.toLong() and 0xFFFFFFFFL
                        val remoteTimeLow = ByteBuffer.wrap(recvBuffer, 28, 4).int.toLong() and 0xFFFFFFFFL

                        val replyTime = getNtpTimestamp()

                        // Build RTP PT 83 timing reply (32 bytes)
                        val bb = ByteBuffer.wrap(replyBuffer).order(ByteOrder.BIG_ENDIAN)
                        bb.clear()
                        bb.put(0x80.toByte()) // v=2
                        bb.put(0xD3.toByte()) // m=1, PT=83 (0x53 or 0xD3)
                        bb.putShort(0)
                        bb.putInt(0) // timestamp

                        // Remote time
                        bb.putInt((remoteTimeHigh and 0xFFFFFFFFL).toInt())
                        bb.putInt((remoteTimeLow and 0xFFFFFFFFL).toInt())

                        // Received time
                        bb.putInt((recvTime ushr 32).toInt())
                        bb.putInt((recvTime and 0xFFFFFFFFL).toInt())

                        // Transmit time
                        bb.putInt((replyTime ushr 32).toInt())
                        bb.putInt((replyTime and 0xFFFFFFFFL).toInt())

                        replyPacket.address = recvPacket.address
                        replyPacket.port = recvPacket.port
                        replyPacket.length = 32

                        socket.send(replyPacket)
                    }
                } catch (e: Exception) {
                    if (isStreaming) {
                        Log.w(TAG, "Timing socket error: ${e.message}")
                    }
                }
            }
        }
    }

    private fun startAudioStreamer(scope: CoroutineScope) {
        streamJob = scope.launch(Dispatchers.IO) {
            val socket = audioSocket ?: return@launch
            val destAddress = InetAddress.getByName(targetIp)

            val audioPacket = DatagramPacket(packetBuffer, 0, destAddress, serverPort)

            // Initial sync packet
            sendSyncPacket(destAddress, isFirst = true)

            val frameDurationNs = (1_000_000_000L * FRAMES_PER_PACKET / 44100L)
            var nextPacketTimeNs = System.nanoTime()

            Log.d(TAG, "Audio streaming loop started to $targetIp:$serverPort")

            while (isActive && isStreaming) {
                val chunk = audioQueue.poll(15, TimeUnit.MILLISECONDS) ?: ByteArray(CHUNK_SIZE)

                encodeAndSendChunk(socket, audioPacket, chunk)

                // Every 100 packets send sync packet on control socket
                if (++packetCount % 100 == 0) {
                    sendSyncPacket(destAddress, isFirst = false)
                }

                // Timing pace control (smooth 44.1kHz playback rate)
                nextPacketTimeNs += frameDurationNs
                val sleepNs = nextPacketTimeNs - System.nanoTime()
                if (sleepNs > 1_000_000) {
                    TimeUnit.NANOSECONDS.sleep(sleepNs)
                } else if (sleepNs < -50_000_000) {
                    // Fell too far behind, resync clock
                    nextPacketTimeNs = System.nanoTime()
                }
            }
        }
    }

    private fun encodeAndSendChunk(
        socket: DatagramSocket,
        packet: DatagramPacket,
        chunk: ByteArray
    ) {
        // Encode ALAC uncompressed PCM frame
        bitWriter.reset()
        bitWriter.write(1, 3) // channel=1, stereo
        bitWriter.write(0, 4)
        bitWriter.write(0, 8)
        bitWriter.write(0, 4)
        bitWriter.write(1, 1) // has-size
        bitWriter.write(0, 2)
        bitWriter.write(1, 1) // is-not-compressed

        bitWriter.write((FRAMES_PER_PACKET ushr 24) and 0xFF, 8)
        bitWriter.write((FRAMES_PER_PACKET ushr 16) and 0xFF, 8)
        bitWriter.write((FRAMES_PER_PACKET ushr 8) and 0xFF, 8)
        bitWriter.write(FRAMES_PER_PACKET and 0xFF, 8)

        // Write 352 frames (1408 bytes) swapping Little-Endian to Big-Endian
        var idx = 0
        for (i in 0 until FRAMES_PER_PACKET) {
            val lLow = chunk[idx].toInt() and 0xFF
            val lHigh = chunk[idx + 1].toInt() and 0xFF
            val rLow = chunk[idx + 2].toInt() and 0xFF
            val rHigh = chunk[idx + 3].toInt() and 0xFF
            idx += 4

            bitWriter.write(lHigh, 8)
            bitWriter.write(lLow, 8)
            bitWriter.write(rHigh, 8)
            bitWriter.write(rLow, 8)
        }

        bitWriter.write(7, 3) // end tag

        val payloadLen = bitWriter.totalBytes()
        val alacPayload = bitWriter.buffer

        // Encrypt 16-byte blocks using AES-128-CBC
        val encryptableLen = payloadLen and 0xF.inv() // round down to multiple of 16
        if (encryptableLen > 0) {
            aesCipher.update(alacPayload, 0, encryptableLen, alacPayload, 0)
        }

        // Build 12-byte RTP header
        val bb = ByteBuffer.wrap(packetBuffer).order(ByteOrder.BIG_ENDIAN)
        bb.clear()
        bb.put(0x80.toByte()) // v=2
        bb.put(96.toByte())   // Payload type 96
        bb.putShort((sequenceNumber++ and 0xFFFF).toShort())
        bb.putInt((rtpTimestamp and 0xFFFFFFFFL).toInt())
        bb.putInt(ssrc)

        // Append encrypted payload
        System.arraycopy(alacPayload, 0, packetBuffer, 12, payloadLen)

        val totalPacketSize = 12 + payloadLen
        packet.length = totalPacketSize

        socket.send(packet)
        rtpTimestamp += FRAMES_PER_PACKET
    }

    private fun sendSyncPacket(destAddress: InetAddress, isFirst: Boolean) {
        val socket = controlSocket ?: return
        try {
            val syncBuffer = ByteArray(20)
            val bb = ByteBuffer.wrap(syncBuffer).order(ByteOrder.BIG_ENDIAN)

            val vByte = if (isFirst) 0x90.toByte() else 0x80.toByte() // v=2, x=1 if first
            bb.put(vByte)
            bb.put(0xD4.toByte()) // m=1, PT=84 (0x54 or 0xD4)
            bb.putShort(7) // sequence number

            val latency = 44100 // 1 sec latency in samples
            val currentRtp = (rtpTimestamp - latency).toInt()
            bb.putInt(currentRtp)

            val ntp = getNtpTimestamp()
            bb.putInt((ntp ushr 32).toInt())
            bb.putInt((ntp and 0xFFFFFFFFL).toInt())
            bb.putInt((rtpTimestamp and 0xFFFFFFFFL).toInt())

            val syncPacket = DatagramPacket(syncBuffer, syncBuffer.size, destAddress, controlPort)
            socket.send(syncPacket)
        } catch (e: Exception) {
            Log.w(TAG, "Sync packet error: ${e.message}")
        }
    }

    private fun getNtpTimestamp(): Long {
        val nowMs = System.currentTimeMillis()
        val sec = nowMs / 1000L + NTP_OFFSET
        val frac = ((nowMs % 1000L) * 0xFFFFFFFFL) / 1000L
        return (sec shl 32) or (frac and 0xFFFFFFFFL)
    }
}
