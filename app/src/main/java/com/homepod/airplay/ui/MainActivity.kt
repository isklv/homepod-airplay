package com.homepod.airplay.ui

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.homepod.airplay.data.model.AirPlayDevice
import com.homepod.airplay.data.model.AudioSourceType
import com.homepod.airplay.data.model.AudioStreamQuality
import com.homepod.airplay.data.model.StreamState
import com.homepod.airplay.discovery.AirPlayDiscovery
import com.homepod.airplay.service.AirPlayAudioService
import com.homepod.airplay.ui.screens.HomeScreen
import com.homepod.airplay.ui.theme.HomePodAirPlayTheme

class MainActivity : ComponentActivity() {

    private var audioService by mutableStateOf<AirPlayAudioService?>(null)
    private var isBound = false

    private lateinit var airPlayDiscovery: AirPlayDiscovery

    private var pendingDeviceToConnect: AirPlayDevice? = null
    private var selectedSourceType by mutableStateOf(AudioSourceType.SYSTEM_CAPTURE)

    private var pendingConnectIntent: Intent? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AirPlayAudioService.LocalBinder
            audioService = binder.service
            isBound = true
            pendingConnectIntent?.let {
                handleIncomingIntent(it)
                pendingConnectIntent = null
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            audioService = null
            isBound = false
        }
    }

    // Permission launcher for Record Audio & Post Notifications
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (!recordAudioGranted) {
            Toast.makeText(this, "Audio recording permission is required to capture system audio", Toast.LENGTH_LONG).show()
        }
    }

    // MediaProjection launcher for AudioPlaybackCapture
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val device = pendingDeviceToConnect
            if (device != null) {
                startServiceStreaming(device, selectedSourceType, result.resultCode, result.data)
            }
        } else {
            Toast.makeText(this, "System audio capture was not allowed", Toast.LENGTH_SHORT).show()
        }
        pendingDeviceToConnect = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        airPlayDiscovery = AirPlayDiscovery(this)

        checkAndRequestPermissions()

        val serviceIntent = Intent(this, AirPlayAudioService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        setContent {
            HomePodAirPlayTheme {
                val streamState by (audioService?.streamState ?: kotlinx.coroutines.flow.emptyFlow())
                    .collectAsState(initial = StreamState.Disconnected)
                val streamQuality by (audioService?.streamQuality ?: kotlinx.coroutines.flow.emptyFlow())
                    .collectAsState(initial = AudioStreamQuality())

                val discoveredDevices by airPlayDiscovery.discoveredDevices.collectAsState()
                val isScanning by airPlayDiscovery.isScanning.collectAsState()
                val scanRemainingSeconds by airPlayDiscovery.scanRemainingSeconds.collectAsState()
                val currentVolume by (audioService?.currentVolume ?: kotlinx.coroutines.flow.emptyFlow())
                    .collectAsState(initial = 50f)
                val mutePhoneSpeaker by (audioService?.mutePhoneSpeaker ?: kotlinx.coroutines.flow.emptyFlow())
                    .collectAsState(initial = true)
                val latencyMs by (audioService?.latencyMs ?: kotlinx.coroutines.flow.emptyFlow())
                    .collectAsState(initial = 500)

                HomeScreen(
                    streamState = streamState,
                    streamQuality = streamQuality,
                    discoveredDevices = discoveredDevices,
                    isScanning = isScanning,
                    scanRemainingSeconds = scanRemainingSeconds,
                    currentVolume = currentVolume,
                    selectedSource = selectedSourceType,
                    selectedLatencyMs = latencyMs,
                    mutePhoneSpeaker = mutePhoneSpeaker,
                    onToggleMutePhoneSpeaker = { audioService?.setMutePhoneSpeaker(it) },
                    onLatencySelected = { audioService?.setLatencyMs(it) },
                    onSourceSelected = { selectedSourceType = it },
                    onRefreshScan = { airPlayDiscovery.startDiscovery(10) },
                    onStopScan = { airPlayDiscovery.stopDiscovery() },
                    onRemoveDevice = { airPlayDiscovery.removeDevice(it) },
                    onConnectDevice = { device -> handleConnectDevice(device) },
                    onStopStreaming = { audioService?.stopStreaming() },
                    onVolumeChanged = { newVolume -> audioService?.setVolume(newVolume) },
                    onAddManualDevice = { name, ip, port -> airPlayDiscovery.addManualDevice(name, ip, port) }
                )
            }
        }

        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == AirPlayAudioService.ACTION_STOP_STREAM) {
            audioService?.stopStreaming()
            return
        }
        if (intent.action == AirPlayAudioService.ACTION_START_STREAM) {
            val service = audioService
            if (!isBound || service == null) {
                pendingConnectIntent = intent
                return
            }
            val ip = intent.getStringExtra(AirPlayAudioService.EXTRA_IP) ?: return
            val port = intent.getIntExtra(AirPlayAudioService.EXTRA_PORT, 5000)
            val name = intent.getStringExtra(AirPlayAudioService.EXTRA_NAME) ?: "HomePod"
            val isTone = intent.getBooleanExtra(AirPlayAudioService.EXTRA_IS_TONE, true)
            selectedSourceType = if (isTone) AudioSourceType.TEST_TONE else AudioSourceType.SYSTEM_CAPTURE
            val device = AirPlayDevice(
                id = name,
                name = name,
                ip = ip,
                port = port,
                model = "HomePod",
                isHomePod = true
            )
            handleConnectDevice(device)
        }
    }

    override fun onResume() {
        super.onResume()
        if (airPlayDiscovery.discoveredDevices.value.isEmpty()) {
            airPlayDiscovery.startDiscovery(10)
        }
    }

    override fun onPause() {
        super.onPause()
        airPlayDiscovery.stopDiscovery()
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }

    private fun handleConnectDevice(device: AirPlayDevice) {
        if (selectedSourceType == AudioSourceType.SYSTEM_CAPTURE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                pendingDeviceToConnect = device
                val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
            } else {
                Toast.makeText(this, "System audio capture requires Android 10+", Toast.LENGTH_SHORT).show()
            }
        } else {
            // Test tone mode (no screen/audio projection needed)
            startServiceStreaming(device, AudioSourceType.TEST_TONE, 0, null)
        }
    }

    private fun startServiceStreaming(
        device: AirPlayDevice,
        sourceType: AudioSourceType,
        resultCode: Int,
        projectionData: Intent?
    ) {
        val serviceIntent = Intent(this, AirPlayAudioService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        audioService?.startStreaming(device, sourceType, resultCode, projectionData)
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needsRequest = permissions.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needsRequest) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val service = audioService
        if (service != null && service.streamState.value is StreamState.Streaming) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    val nextVol = (service.currentVolume.value + 5f).coerceAtMost(100f)
                    service.setVolume(nextVol)
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    val nextVol = (service.currentVolume.value - 5f).coerceAtLeast(0f)
                    service.setVolume(nextVol)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
