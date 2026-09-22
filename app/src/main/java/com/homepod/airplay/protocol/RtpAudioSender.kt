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
    private val aesKey: ByteArray? = null,
    private val aesIv: ByteArray? = null,
    private val network: android.net.Network? = null,
    @Volatile var latencyMs: Int = 1000
) {
    companion object {
        private const val TAG = "RtpAudioSender"
        const val FRAMES_PER_PACKET = 352
        const val BYTES_PER_FRAME = 4 // 16-bit stereo
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

    private var serverPort = 0
    private var serverCtrlPort = 0

    private val audioQueue = ArrayBlockingQueue<ByteArray>(128)
    private val accumulatorLock = Any()
    private val accumulator = ByteArray(CHUNK_SIZE * 4)
    private var accumulatorCount = 0
    private var streamJob: Job? = null
    private var timingJob: Job? = null

    @Volatile
    private var isStreaming = false
    @Volatile
    private var isTimingListening = false

    private val aesCipher: Cipher? by lazy {
        if (aesKey != null && aesIv != null) {
            AirPlayCrypto.createAesCipher(aesKey, aesIv)
        } else {
            null
        }
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
        controlSocket = DatagramSocket().apply {
            try {
                sendBufferSize = 64 * 1024
                receiveBufferSize = 64 * 1024
            } catch (_: Exception) {}
        }
        network?.let { NetworkUtils.bindSocketToWifi(it, controlSocket) }
        timingSocket = DatagramSocket().apply {
            try {
                sendBufferSize = 64 * 1024
                receiveBufferSize = 64 * 1024
            } catch (_: Exception) {}
        }
        network?.let { NetworkUtils.bindSocketToWifi(it, timingSocket) }
        audioSocket = DatagramSocket().apply {
            try {
                sendBufferSize = 256 * 1024
            } catch (_: Exception) {}
        }
        network?.let { NetworkUtils.bindSocketToWifi(it, audioSocket) }
    }

    /**
     * Start the timing listener socket BEFORE RTSP SETUP is sent,
     * so HomePod's setup ping is received and replied to immediately.
     */
    fun startTimingListener(scope: CoroutineScope) {
        if (isTimingListening) return
        isTimingListening = true

        timingJob = scope.launch(Dispatchers.IO) {
            val socket = timingSocket ?: return@launch
            val recvBuffer = ByteArray(128)
            val recvPacket = DatagramPacket(recvBuffer, recvBuffer.size)

            val replyBuffer = ByteArray(32)
            val replyPacket = DatagramPacket(replyBuffer, replyBuffer.size)

            Log.d(TAG, "Timing listener listening on UDP port ${socket.localPort}")

            while (isActive && isTimingListening) {
                try {
                    recvPacket.length = recvBuffer.size
                    socket.receive(recvPacket)
                    val len = recvPacket.length
                    if (len >= 32) {
                        val recvTime = getNtpTimestamp()

                        // Extract remote NTP timestamp from offset 24..32
                        val remSec = ByteBuffer.wrap(recvBuffer, 24, 4).int
                        val remFrac = ByteBuffer.wrap(recvBuffer, 28, 4).int
                        val transTime = getNtpTimestamp()

                        // Build RTP PT 83 timing reply (32 bytes)
                        val bb = ByteBuffer.wrap(replyBuffer).order(ByteOrder.BIG_ENDIAN)
                        bb.clear()
                        bb.put(0x80.toByte()) // v=2
                        bb.put(0xD3.toByte()) // m=1, PT=83
                        bb.putShort(0)
                        bb.putInt(0) // timestamp

                        // Remote time
                        bb.putInt(remSec)
                        bb.putInt(remFrac)

                        // Received time
                        bb.putInt((recvTime ushr 32).toInt())
                        bb.putInt((recvTime and 0xFFFFFFFFL).toInt())

                        // Transmit time
                        bb.putInt((transTime ushr 32).toInt())
                        bb.putInt((transTime and 0xFFFFFFFFL).toInt())

                        replyPacket.address = recvPacket.address
                        replyPacket.port = recvPacket.port
                        replyPacket.length = 32

                        socket.send(replyPacket)
                    }
                } catch (e: Exception) {
                    if (isTimingListening) {
                        Log.w(TAG, "Timing socket receive: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * Start the audio data streaming loop once SETUP has provided remote server and control ports.
     */
    fun startAudioStream(scope: CoroutineScope, remoteServerPort: Int, remoteCtrlPort: Int) {
        if (isStreaming) return
        isStreaming = true
        serverPort = remoteServerPort
        serverCtrlPort = remoteCtrlPort

        startAudioStreamer(scope)
    }

    fun stop() {
        isStreaming = false
        isTimingListening = false
        streamJob?.cancel()
        timingJob?.cancel()
        audioQueue.clear()
        synchronized(accumulatorLock) {
            accumulatorCount = 0
        }

        try { audioSocket?.close() } catch (_: Exception) {}
        try { controlSocket?.close() } catch (_: Exception) {}
        try { timingSocket?.close() } catch (_: Exception) {}

        audioSocket = null
        controlSocket = null
        timingSocket = null
    }

    fun queueAudio(pcmData: ByteArray, length: Int) {
        if (!isStreaming || length <= 0) return

        synchronized(accumulatorLock) {
            var srcOffset = 0
            var remaining = length

            while (remaining > 0) {
                val needed = CHUNK_SIZE - accumulatorCount
                val toCopy = Math.min(remaining, needed)
                System.arraycopy(pcmData, srcOffset, accumulator, accumulatorCount, toCopy)
                accumulatorCount += toCopy
                srcOffset += toCopy
                remaining -= toCopy

                if (accumulatorCount == CHUNK_SIZE) {
                    val chunk = ByteArray(CHUNK_SIZE)
                    System.arraycopy(accumulator, 0, chunk, 0, CHUNK_SIZE)
                    accumulatorCount = 0

                    if (!audioQueue.offer(chunk)) {
                        // Queue full: drop oldest chunk to maintain real-time low latency
                        audioQueue.poll()
                        audioQueue.offer(chunk)
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

            // Pre-buffer a few chunks adaptively based on latency setting (e.g. 4..12 chunks)
            val prebufferTarget = (latencyMs / 80).coerceIn(4, 12)
            var prebufferWait = 0
            while (isActive && isStreaming && audioQueue.size < prebufferTarget && prebufferWait < 40) {
                kotlinx.coroutines.delay(10)
                prebufferWait++
            }

            val frameDurationNs = (1_000_000_000L * FRAMES_PER_PACKET / 44100L)
            var nextPacketTimeNs = System.nanoTime()

            Log.d(TAG, "Audio streaming loop started to $targetIp:$serverPort (initial queue size: ${audioQueue.size})")

            val silenceChunk = ByteArray(CHUNK_SIZE)

            while (isActive && isStreaming) {
                // Non-blocking poll first, very brief wait if needed to avoid CPU clock stalling
                val chunk = audioQueue.poll() ?: audioQueue.poll(2, TimeUnit.MILLISECONDS) ?: silenceChunk

                encodeAndSendChunk(socket, audioPacket, chunk)

                if (++packetCount % 100 == 0) {
                    sendSyncPacket(destAddress, isFirst = false)
                }

                nextPacketTimeNs += frameDurationNs
                val sleepNs = nextPacketTimeNs - System.nanoTime()
                if (sleepNs > 1_000_000) {
                    TimeUnit.NANOSECONDS.sleep(sleepNs)
                } else if (sleepNs < -50_000_000) {
                    // Clock slipped more than 50ms, re-align
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

        // Encrypt only if cipher is configured (for HomePod mini it is unencrypted)
        aesCipher?.let { cipher ->
            val encryptableLen = payloadLen and 0xF.inv()
            if (encryptableLen > 0) {
                cipher.update(alacPayload, 0, encryptableLen, alacPayload, 0)
            }
        }

        // Build 12-byte RTP header
        val bb = ByteBuffer.wrap(packetBuffer).order(ByteOrder.BIG_ENDIAN)
        bb.clear()
        bb.put(0x80.toByte()) // v=2
        bb.put(96.toByte())   // Payload type 96
        bb.putShort((sequenceNumber++ and 0xFFFF).toShort())
        bb.putInt((rtpTimestamp and 0xFFFFFFFFL).toInt())
        bb.putInt(ssrc)

        System.arraycopy(alacPayload, 0, packetBuffer, 12, payloadLen)

        val totalPacketSize = 12 + payloadLen
        packet.length = totalPacketSize

        socket.send(packet)
        rtpTimestamp += FRAMES_PER_PACKET
    }

    private fun sendSyncPacket(destAddress: InetAddress, isFirst: Boolean) {
        val socket = controlSocket ?: return
        if (serverCtrlPort == 0) return
        try {
            val syncBuffer = ByteArray(20)
            val bb = ByteBuffer.wrap(syncBuffer).order(ByteOrder.BIG_ENDIAN)

            val vByte = if (isFirst) 0x90.toByte() else 0x80.toByte()
            bb.put(vByte)
            bb.put(0xD4.toByte()) // m=1, PT=84
            bb.putShort(7)

            val latencyFrames = (44100L * latencyMs / 1000L).toInt()
            val currentRtp = (rtpTimestamp - latencyFrames).toInt()
            bb.putInt(currentRtp)

            val ntp = getNtpTimestamp()
            bb.putInt((ntp ushr 32).toInt())
            bb.putInt((ntp and 0xFFFFFFFFL).toInt())
            bb.putInt((rtpTimestamp and 0xFFFFFFFFL).toInt())

            val syncPacket = DatagramPacket(syncBuffer, syncBuffer.size, destAddress, serverCtrlPort)
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
