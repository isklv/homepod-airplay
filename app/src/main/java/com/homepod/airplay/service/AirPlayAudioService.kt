package com.homepod.airplay.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.homepod.airplay.R
import com.homepod.airplay.audio.AudioCaptureManager
import com.homepod.airplay.audio.TestToneGenerator
import com.homepod.airplay.crypto.AirPlayCrypto
import com.homepod.airplay.data.model.AirPlayDevice
import com.homepod.airplay.data.model.AudioSourceType
import com.homepod.airplay.data.model.StreamState
import com.homepod.airplay.protocol.NetworkUtils
import com.homepod.airplay.protocol.RTSPClient
import com.homepod.airplay.protocol.RtpAudioSender
import com.homepod.airplay.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.NetworkInterface

class AirPlayAudioService : Service() {

    companion object {
        private const val TAG = "AirPlayAudioService"
        private const val NOTIFICATION_CHANNEL_ID = "airplay_stream_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_STOP_STREAM = "com.homepod.airplay.action.STOP"
        const val ACTION_START_STREAM = "com.homepod.airplay.action.START"
        const val EXTRA_IP = "extra_ip"
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_IS_TONE = "extra_is_tone"
    }

    inner class LocalBinder : Binder() {
        val service: AirPlayAudioService get() = this@AirPlayAudioService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _streamState = MutableStateFlow<StreamState>(StreamState.Disconnected)
    val streamState: StateFlow<StreamState> = _streamState.asStateFlow()

    private val _currentVolume = MutableStateFlow(50f)
    val currentVolume: StateFlow<Float> = _currentVolume.asStateFlow()

    private val _mutePhoneSpeaker = MutableStateFlow(true)
    val mutePhoneSpeaker: StateFlow<Boolean> = _mutePhoneSpeaker.asStateFlow()
    private var savedMediaVolume = -1
    private var isPhoneSpeakerMuted = false

    private var rtspClient: RTSPClient? = null
    private var rtpSender: RtpAudioSender? = null
    private var audioCapture: AudioCaptureManager? = null
    private var testToneGenerator: TestToneGenerator? = null
    private var mediaProjection: MediaProjection? = null
    private var keepAliveJob: Job? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    fun setMutePhoneSpeaker(enabled: Boolean) {
        _mutePhoneSpeaker.value = enabled
        if (_streamState.value is StreamState.Streaming) {
            if (enabled) {
                mutePhone()
            } else {
                restorePhoneVolume()
            }
        }
    }

    private fun mutePhone() {
        if (isPhoneSpeakerMuted) return
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (current > 0) {
                savedMediaVolume = current
            }
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            isPhoneSpeakerMuted = true
            Log.d(TAG, "Muted phone speaker (saved volume: $savedMediaVolume)")
        } catch (e: Exception) {
            Log.w(TAG, "Error muting phone speaker: ${e.message}")
        }
    }

    private fun restorePhoneVolume() {
        if (!isPhoneSpeakerMuted) return
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (savedMediaVolume >= 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedMediaVolume, 0)
                Log.d(TAG, "Restored phone speaker volume to $savedMediaVolume")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error restoring phone speaker volume: ${e.message}")
        } finally {
            isPhoneSpeakerMuted = false
            savedMediaVolume = -1
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireLocks()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_STREAM -> stopStreaming()
            ACTION_START_STREAM -> {
                val ip = intent.getStringExtra(EXTRA_IP)
                if (ip != null) {
                    val port = intent.getIntExtra(EXTRA_PORT, 5000)
                    val name = intent.getStringExtra(EXTRA_NAME) ?: "HomePod"
                    val isTone = intent.getBooleanExtra(EXTRA_IS_TONE, true)
                    val device = AirPlayDevice(
                        id = name,
                        name = name,
                        ip = ip,
                        port = port,
                        model = "HomePod",
                        isHomePod = true
                    )
                    startStreaming(
                        device = device,
                        sourceType = if (isTone) AudioSourceType.TEST_TONE else AudioSourceType.SYSTEM_CAPTURE
                    )
                }
            }
        }
        return START_NOT_STICKY
    }

    fun startStreaming(
        device: AirPlayDevice,
        sourceType: AudioSourceType,
        resultCode: Int = 0,
        projectionData: Intent? = null
    ) {
        // Critical for Android 14 / Samsung: Start foreground BEFORE media projection and network operations
        startForegroundWithNotification(device)
        _streamState.value = StreamState.Connecting(device)

        serviceScope.launch(Dispatchers.IO) {
            try {
                // Step 0: Get Wi-Fi network to bypass active VPN if needed
                val wifiNetwork = NetworkUtils.getWifiNetwork(this@AirPlayAudioService)
                Log.d(TAG, "Wi-Fi network bound for AirPlay: $wifiNetwork")
                NetworkUtils.bindProcessToWifi(this@AirPlayAudioService)

                // Step 1: Initialize RTP Sender and start timing listener BEFORE sending SETUP
                // HomePod pings timing_port during SETUP to verify the client!
                val sender = RtpAudioSender(device.ip, network = wifiNetwork)
                rtpSender = sender
                sender.startTimingListener(serviceScope)

                // Step 2: Connect RTSP socket
                val client = RTSPClient(device.ip, device.port, network = wifiNetwork)
                rtspClient = client
                client.connect(timeoutMs = 5000)

                // Step 3: Send OPTIONS
                if (!client.sendOptions()) {
                    throw IllegalStateException("OPTIONS handshake failed")
                }

                // Step 4: Send ANNOUNCE (unencrypted for HomePod mini)
                if (!client.sendAnnounce(encrypted = false)) {
                    throw IllegalStateException("ANNOUNCE handshake failed")
                }

                // Step 5: Send SETUP (HomePod probes timing port and receives immediate response)
                val setupResult = client.sendSetup(
                    localControlPort = sender.localControlPort,
                    localTimingPort = sender.localTimingPort
                )

                // Step 6: Send RECORD
                if (!client.sendRecord(startSeq = 0, startRtpTime = 0)) {
                    throw IllegalStateException("RECORD request failed")
                }

                // Step 7: Set volume
                client.sendVolume(_currentVolume.value)

                // Step 8: Start audio streaming loop to negotiated server ports
                sender.startAudioStream(serviceScope, setupResult.serverPort, setupResult.controlPort)

                // Step 9: Start Audio Source (System Audio Capture or Test Tone)
                if (sourceType == AudioSourceType.SYSTEM_CAPTURE && projectionData != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        val projection = projectionManager.getMediaProjection(resultCode, projectionData)
                        mediaProjection = projection

                        val capture = AudioCaptureManager(projection) { buffer, length ->
                            sender.queueAudio(buffer, length)
                        }
                        audioCapture = capture
                        capture.start(serviceScope)
                    } else {
                        throw IllegalStateException("System audio capture requires Android 10+")
                    }
                } else {
                    val toneGen = TestToneGenerator { buffer, length ->
                        sender.queueAudio(buffer, length)
                    }
                    testToneGenerator = toneGen
                    toneGen.start(serviceScope)
                }

                _streamState.value = StreamState.Streaming(device, sourceType)
                updateNotification("Streaming to ${device.name}")
                Log.d(TAG, "Streaming to ${device.name} successfully established!")

                if (sourceType == AudioSourceType.SYSTEM_CAPTURE && _mutePhoneSpeaker.value) {
                    mutePhone()
                }

                startKeepAliveLoop()

            } catch (e: Exception) {
                Log.e(TAG, "Streaming error: ${e.message}", e)
                cleanup()
                val isVpn = NetworkUtils.isVpnActive(this@AirPlayAudioService)
                val userMsg = if (isVpn && (e is java.net.SocketTimeoutException || e is java.net.SocketException || e.message?.contains("10.8.") == true || e.message?.contains("EPERM") == true)) {
                    "Блокировка VPN: на телефоне активен VPN, который блокирует доступ к локальной сети (${device.ip}). Добавьте HomePod Streamer в исключения VPN (Раздельное туннелирование) или временно отключите VPN."
                } else {
                    e.message ?: "Не удалось подключиться к ${device.name}"
                }
                _streamState.value = StreamState.Error(userMsg)
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
    }

    private fun startKeepAliveLoop() {
        keepAliveJob?.cancel()
        keepAliveJob = serviceScope.launch(Dispatchers.IO) {
            Log.d(TAG, "Starting RTSP keep-alive loop (interval: 15s)")
            while (isActive && _streamState.value is StreamState.Streaming) {
                delay(15_000)
                if (!isActive || _streamState.value !is StreamState.Streaming) break
                val client = rtspClient
                if (client == null) break

                val ok = client.sendKeepAlive()
                if (!ok) {
                    Log.e(TAG, "RTSP keep-alive failed! Connection to HomePod lost.")
                    withContext(Dispatchers.Main) {
                        _streamState.value = StreamState.Error("Связь с HomePod потеряна (таймаут соединения)")
                        stopStreaming()
                    }
                    break
                } else {
                    Log.d(TAG, "RTSP keep-alive OK")
                }
            }
        }
    }

    fun setVolume(percent: Float) {
        val clamped = percent.coerceIn(0f, 100f)
        _currentVolume.value = clamped
        serviceScope.launch(Dispatchers.IO) {
            try {
                rtspClient?.sendVolume(clamped)
            } catch (e: Exception) {
                Log.w(TAG, "Error updating volume: ${e.message}")
            }
        }
    }

    fun stopStreaming() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                rtspClient?.sendTeardown()
            } catch (_: Exception) {}
            cleanup()
            _streamState.value = StreamState.Disconnected
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun cleanup() {
        keepAliveJob?.cancel()
        keepAliveJob = null

        restorePhoneVolume()

        audioCapture?.stop()
        audioCapture = null

        testToneGenerator?.stop()
        testToneGenerator = null

        mediaProjection?.stop()
        mediaProjection = null

        rtpSender?.stop()
        rtpSender = null

        rtspClient?.close()
        rtspClient = null
    }

    private fun startForegroundWithNotification(device: AirPlayDevice) {
        val notification = buildNotification("Streaming to ${device.name}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(content: String) {
        val notification = buildNotification(content)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AirPlayAudioService::class.java).apply { action = ACTION_STOP_STREAM },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("HomePod AirPlay")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "HomePod AirPlay Stream",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active AirPlay streaming notification"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun acquireLocks() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HomePodAirPlay:WakeLock").apply {
                acquire(24 * 60 * 60 * 1000L) // 24 hours max
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error acquiring wake lock: ${e.message}")
        }

        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifiManager.createWifiLock(lockMode, "HomePodAirPlay:WifiLock").apply {
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error acquiring wifi lock: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {}
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr.hostAddress?.contains(":") == false) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    override fun onDestroy() {
        cleanup()
        releaseLocks()
        serviceScope.cancel()
        super.onDestroy()
    }
}
