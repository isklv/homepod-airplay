package com.homepod.airplay.protocol

import android.util.Log
import com.homepod.airplay.crypto.AirPlayCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale

class RTSPClient(
    val host: String,
    val port: Int = 5000,
    val network: android.net.Network? = null
) {
    companion object {
        private const val TAG = "RTSPClient"
        private const val USER_AGENT = "AirPlay/320.20"
    }

    private val mutex = Mutex()
    private var socket: Socket? = null
    private var writer: OutputStream? = null
    private var reader: BufferedReader? = null

    private var cSeq = 1
    var localIp: String = "0.0.0.0"
        private set
    var sessionId: String = generateNumericSessionId()
        private set
    var serverSessionId: String? = null
        private set

    val baseUrl: String
        get() = "rtsp://$localIp/$sessionId"

    private val clientInstance = generateHexId()
    private val dacpId = generateHexId()

    data class SetupResult(
        val serverPort: Int,
        val controlPort: Int,
        val timingPort: Int
    )

    private fun generateHexId(): String {
        val bytes = AirPlayCrypto.generateRandomBytes(8)
        return bytes.joinToString("") { "%02X".format(it) }
    }

    private fun generateNumericSessionId(): String {
        return (10000000..99999999).random().toString()
    }

    suspend fun connect(timeoutMs: Int = 5000) = withContext(Dispatchers.IO) {
        Log.d(TAG, "Connecting RTSP socket to $host:$port...")
        val s = if (network != null) {
            try {
                network.socketFactory.createSocket().also {
                    Log.d(TAG, "Created socket via Network.socketFactory")
                }
            } catch (e: Exception) {
                Log.w(TAG, "SocketFactory failed, falling back to manual bind: ${e.message}")
                Socket().also { NetworkUtils.bindSocketToWifi(network, it) }
            }
        } else {
            Socket()
        }
        s.connect(InetSocketAddress(host, port), timeoutMs)
        s.soTimeout = timeoutMs
        socket = s
        localIp = s.localAddress?.hostAddress ?: "0.0.0.0"
        writer = s.getOutputStream()
        reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
        Log.d(TAG, "RTSP connected. localIp=$localIp, baseUrl=$baseUrl")
    }

    suspend fun sendOptions(): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val request = buildString {
                append("OPTIONS * RTSP/1.0\r\n")
                append("CSeq: ${cSeq++}\r\n")
                append("User-Agent: $USER_AGENT\r\n")
                append("Client-Instance: $clientInstance\r\n")
                append("DACP-ID: $dacpId\r\n")
                append("\r\n")
            }
            val response = sendAndReceive(request)
            response.statusCode == 200
        }
    }

    suspend fun sendAnnounce(
        encrypted: Boolean = false,
        aesKey: ByteArray? = null,
        aesIv: ByteArray? = null
    ): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val sdp = buildString {
                append("v=0\r\n")
                append("o=iTunes $sessionId 0 IN IP4 $localIp\r\n")
                append("s=iTunes\r\n")
                append("c=IN IP4 $host\r\n")
                append("t=0 0\r\n")
                append("m=audio 0 RTP/AVP 96\r\n")
                append("a=rtpmap:96 AppleLossless\r\n")
                append("a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100\r\n")
                if (encrypted && aesKey != null && aesIv != null) {
                    val encryptedKey = AirPlayCrypto.encryptAesKey(aesKey)
                    val rsaAesKeyB64 = AirPlayCrypto.toBase64(encryptedKey)
                    val aesIvB64 = AirPlayCrypto.toBase64(aesIv)
                    append("a=rsaaeskey:$rsaAesKeyB64\r\n")
                    append("a=aesiv:$aesIvB64\r\n")
                }
            }

            val request = buildString {
                append("ANNOUNCE $baseUrl RTSP/1.0\r\n")
                append("CSeq: ${cSeq++}\r\n")
                append("Content-Type: application/sdp\r\n")
                append("Content-Length: ${sdp.toByteArray(Charsets.UTF_8).size}\r\n")
                append("User-Agent: $USER_AGENT\r\n")
                append("Client-Instance: $clientInstance\r\n")
                append("DACP-ID: $dacpId\r\n")
                append("\r\n")
                append(sdp)
            }

            val response = sendAndReceive(request)
            response.statusCode == 200
        }
    }

    suspend fun sendSetup(
        localControlPort: Int,
        localTimingPort: Int
    ): SetupResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val transportHeader = "RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;" +
                    "control_port=$localControlPort;timing_port=$localTimingPort"

            val request = buildString {
                append("SETUP $baseUrl RTSP/1.0\r\n")
                append("CSeq: ${cSeq++}\r\n")
                append("Transport: $transportHeader\r\n")
                append("User-Agent: $USER_AGENT\r\n")
                append("Client-Instance: $clientInstance\r\n")
                append("DACP-ID: $dacpId\r\n")
                append("\r\n")
            }

            val response = sendAndReceive(request)
            if (response.statusCode != 200) {
                throw IllegalStateException("SETUP failed with status ${response.statusCode}")
            }

            response.headers["Session"]?.let {
                serverSessionId = it.split(";").firstOrNull()?.trim()
            }

            val transport = response.headers["Transport"]
                ?: throw IllegalStateException("No Transport header in SETUP response")

            var serverPort = 0
            var controlPort = 0
            var timingPort = 0

            for (param in transport.split(";")) {
                val trimmed = param.trim()
                if (trimmed.startsWith("server_port=")) {
                    serverPort = trimmed.substringAfter("server_port=").toIntOrNull() ?: 0
                } else if (trimmed.startsWith("control_port=")) {
                    controlPort = trimmed.substringAfter("control_port=").toIntOrNull() ?: 0
                } else if (trimmed.startsWith("timing_port=")) {
                    timingPort = trimmed.substringAfter("timing_port=").toIntOrNull() ?: 0
                }
            }

            if (serverPort == 0) {
                throw IllegalStateException("Failed to parse server_port from Transport: $transport")
            }

            Log.d(TAG, "SETUP parsed ports -> server: $serverPort, control: $controlPort, timing: $timingPort")
            SetupResult(serverPort, controlPort, timingPort)
        }
    }

    suspend fun sendRecord(startSeq: Int, startRtpTime: Long): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val sessionHeader = serverSessionId ?: sessionId
            val request = buildString {
                append("RECORD $baseUrl RTSP/1.0\r\n")
                append("CSeq: ${cSeq++}\r\n")
                append("Session: $sessionHeader\r\n")
                append("Range: npt=0-\r\n")
                append("RTP-Info: seq=$startSeq;rtptime=$startRtpTime\r\n")
                append("User-Agent: $USER_AGENT\r\n")
                append("\r\n")
            }

            val response = sendAndReceive(request)
            response.statusCode == 200
        }
    }

    suspend fun sendVolume(percent: Float): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val volumeDb = if (percent <= 0f) {
                -144.0f
            } else {
                val clamped = percent.coerceIn(0f, 100f) / 100f
                val linear = Math.cbrt(clamped.toDouble()).toFloat()
                (linear * 30.0f - 30.0f).coerceIn(-30.0f, 0.0f)
            }

            val body = String.format(Locale.US, "volume: %.6f\r\n", volumeDb)
            val sessionHeader = serverSessionId ?: sessionId
            val request = buildString {
                append("SET_PARAMETER $baseUrl RTSP/1.0\r\n")
                append("CSeq: ${cSeq++}\r\n")
                append("Session: $sessionHeader\r\n")
                append("Content-Type: text/parameters\r\n")
                append("Content-Length: ${body.length}\r\n")
                append("User-Agent: $USER_AGENT\r\n")
                append("\r\n")
                append(body)
            }

            val response = sendAndReceive(request)
            response.statusCode == 200
        }
    }

    suspend fun sendKeepAlive(): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val s = socket
            if (s == null || s.isClosed || !s.isConnected) {
                Log.w(TAG, "Cannot send keepalive: socket not connected")
                return@withContext false
            }

            val sessionHeader = serverSessionId ?: sessionId
            val request = buildString {
                append("OPTIONS * RTSP/1.0\r\n")
                append("CSeq: ${cSeq++}\r\n")
                append("Session: $sessionHeader\r\n")
                append("User-Agent: $USER_AGENT\r\n")
                append("Client-Instance: $clientInstance\r\n")
                append("DACP-ID: $dacpId\r\n")
                append("\r\n")
            }

            try {
                val response = sendAndReceive(request)
                if (response.statusCode in 200..299) {
                    true
                } else {
                    Log.w(TAG, "Keep-alive OPTIONS returned ${response.statusCode}, fallback to GET_PARAMETER")
                    val getReq = buildString {
                        append("GET_PARAMETER $baseUrl RTSP/1.0\r\n")
                        append("CSeq: ${cSeq++}\r\n")
                        append("Session: $sessionHeader\r\n")
                        append("User-Agent: $USER_AGENT\r\n")
                        append("\r\n")
                    }
                    val getResp = sendAndReceive(getReq)
                    getResp.statusCode in 200..299
                }
            } catch (e: Exception) {
                Log.w(TAG, "RTSP keep-alive exception: ${e.message}")
                false
            }
        }
    }

    suspend fun sendTeardown(): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val sessionHeader = serverSessionId ?: sessionId
                val request = buildString {
                    append("TEARDOWN $baseUrl RTSP/1.0\r\n")
                    append("CSeq: ${cSeq++}\r\n")
                    append("Session: $sessionHeader\r\n")
                    append("User-Agent: $USER_AGENT\r\n")
                    append("\r\n")
                }
                val response = sendAndReceive(request)
                response.statusCode in 200..299 || response.statusCode == 501
            } catch (e: Exception) {
                Log.w(TAG, "Teardown error: ${e.message}")
                false
            } finally {
                close()
            }
        }
    }

    fun close() {
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        writer = null
        reader = null
    }

    private data class RTSPResponse(
        val statusCode: Int,
        val statusMessage: String,
        val headers: Map<String, String>,
        val body: String
    )

    private fun sendAndReceive(request: String): RTSPResponse {
        val out = writer ?: throw IllegalStateException("RTSP writer is null")
        val inp = reader ?: throw IllegalStateException("RTSP reader is null")

        Log.d(TAG, ">>> RTSP SEND:\n$request")
        out.write(request.toByteArray(Charsets.ISO_8859_1))
        out.flush()

        val statusLine = inp.readLine() ?: throw IllegalStateException("Connection closed by server")
        Log.d(TAG, "<<< RTSP STATUS: $statusLine")

        val parts = statusLine.split(" ", limit = 3)
        val statusCode = parts.getOrNull(1)?.toIntOrNull() ?: 500
        val statusMsg = parts.getOrNull(2) ?: ""

        val headers = mutableMapOf<String, String>()
        var contentLength = 0

        while (true) {
            val line = inp.readLine() ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                val key = line.substring(0, colon).trim()
                val value = line.substring(colon + 1).trim()
                headers[key] = value
                if (key.equals("Content-Length", ignoreCase = true)) {
                    contentLength = value.toIntOrNull() ?: 0
                }
            }
        }

        val body = if (contentLength > 0) {
            val buffer = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = inp.read(buffer, read, contentLength - read)
                if (n == -1) break
                read += n
            }
            String(buffer, 0, read)
        } else {
            ""
        }

        return RTSPResponse(statusCode, statusMsg, headers, body)
    }
}
