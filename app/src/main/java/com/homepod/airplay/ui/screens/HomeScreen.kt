package com.homepod.airplay.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.platform.LocalContext
import com.homepod.airplay.protocol.NetworkUtils
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homepod.airplay.data.model.AirPlayDevice
import com.homepod.airplay.data.model.AudioSourceType
import com.homepod.airplay.data.model.StreamState
import com.homepod.airplay.ui.theme.AirPlayBlue
import com.homepod.airplay.ui.theme.ErrorRed
import com.homepod.airplay.ui.theme.SuccessGreen
import com.homepod.airplay.ui.theme.WarningOrange

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    streamState: StreamState,
    discoveredDevices: List<AirPlayDevice>,
    isScanning: Boolean,
    scanRemainingSeconds: Int = 0,
    currentVolume: Float,
    selectedSource: AudioSourceType,
    selectedLatencyMs: Int = 500,
    mutePhoneSpeaker: Boolean = true,
    onToggleMutePhoneSpeaker: (Boolean) -> Unit = {},
    onLatencySelected: (Int) -> Unit = {},
    onSourceSelected: (AudioSourceType) -> Unit,
    onRefreshScan: () -> Unit,
    onStopScan: () -> Unit = {},
    onRemoveDevice: (AirPlayDevice) -> Unit = {},
    onConnectDevice: (AirPlayDevice) -> Unit,
    onStopStreaming: () -> Unit,
    onVolumeChanged: (Float) -> Unit,
    onAddManualDevice: (String, String, Int) -> Unit
) {
    var showManualDialog by remember { mutableStateOf(false) }
    var showGuideDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val isVpnActive = remember(streamState) { NetworkUtils.isVpnActive(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Cast,
                            contentDescription = null,
                            tint = AirPlayBlue,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("HomePod Streamer", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(onClick = { showGuideDialog = true }) {
                        Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = "Setup Guide")
                    }
                    IconButton(onClick = {
                        if (isScanning) onStopScan() else onRefreshScan()
                    }) {
                        if (isScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = AirPlayBlue
                            )
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Поиск устройств")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(modifier = Modifier.height(4.dp)) }

            // Active Streaming Card
            item {
                ActiveStreamingCard(
                    streamState = streamState,
                    currentVolume = currentVolume,
                    selectedSource = selectedSource,
                    selectedLatencyMs = selectedLatencyMs,
                    mutePhoneSpeaker = mutePhoneSpeaker,
                    onToggleMutePhoneSpeaker = onToggleMutePhoneSpeaker,
                    onLatencySelected = onLatencySelected,
                    onSourceSelected = onSourceSelected,
                    onStopStreaming = onStopStreaming,
                    onVolumeChanged = onVolumeChanged
                )
            }

            // VPN Warning Banner
            if (isVpnActive) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = WarningOrange.copy(alpha = 0.18f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = WarningOrange)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Включён VPN (блокирует локальную сеть)",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Добавьте «HomePod Streamer» в исключения вашего VPN (Раздельное туннелирование / Split Tunneling) или отключите VPN на время стриминга.",
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }

            // Error Banner if any
            if (streamState is StreamState.Error) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = ErrorRed.copy(alpha = 0.15f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = ErrorRed)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = streamState.message,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }

            // Discovered Devices Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Колонки AirPlay",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isScanning) {
                                if (scanRemainingSeconds > 0) "Поиск в сети ($scanRemainingSeconds сек)..." else "Поиск в сети..."
                            } else {
                                if (discoveredDevices.isEmpty()) "Колонки не найдены" else "${discoveredDevices.size} найдено"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (isScanning) {
                            FilledTonalButton(
                                onClick = onStopScan,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Стоп${if (scanRemainingSeconds > 0) " (${scanRemainingSeconds}с)" else ""}", fontSize = 13.sp)
                            }
                        } else {
                            Button(
                                onClick = onRefreshScan,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Поиск (10с)", fontSize = 13.sp)
                            }
                        }

                        OutlinedButton(
                            onClick = { showManualDialog = true },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("IP", fontSize = 13.sp)
                        }
                    }
                }

                if (isScanning) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = AirPlayBlue
                    )
                }
            }

            // Discovered Devices List
            if (discoveredDevices.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.Speaker,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                if (isScanning) "Поиск колонок в Wi-Fi..." else "Колонки пока не найдены",
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                if (isScanning)
                                    "Сканирование сети продлится еще $scanRemainingSeconds сек или нажмите «Остановить»."
                                else
                                    "Нажмите кнопку ниже, чтобы запустить 10-секундный поиск HomePod в сети, или добавьте IP вручную.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (isScanning) {
                                    FilledTonalButton(onClick = onStopScan) {
                                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Остановить")
                                    }
                                } else {
                                    Button(onClick = onRefreshScan) {
                                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Поиск (10 сек)")
                                    }
                                }
                                OutlinedButton(onClick = { showManualDialog = true }) {
                                    Text("Указать IP")
                                }
                            }
                        }
                    }
                }
            } else {
                items(discoveredDevices) { device ->
                    DeviceItemCard(
                        device = device,
                        isConnecting = streamState is StreamState.Connecting && streamState.device.ip == device.ip,
                        isStreaming = streamState is StreamState.Streaming && streamState.device.ip == device.ip,
                        onConnect = { onConnectDevice(device) },
                        onRemove = { onRemoveDevice(device) }
                    )
                }
            }

            // Tips & Guide Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showGuideDialog = true },
                    colors = CardDefaults.cardColors(
                        containerColor = AirPlayBlue.copy(alpha = 0.08f)
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = AirPlayBlue)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                "HomePod Configuration Guide",
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium,
                                color = AirPlayBlue
                            )
                            Text(
                                "Tap here to see required Apple Home app settings",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }

    // Manual IP Dialog
    if (showManualDialog) {
        ManualDeviceDialog(
            onDismiss = { showManualDialog = false },
            onConfirm = { name, ip, port ->
                onAddManualDevice(name, ip, port)
                showManualDialog = false
            }
        )
    }

    // Guide Dialog
    if (showGuideDialog) {
        HomePodGuideDialog(onDismiss = { showGuideDialog = false })
    }
}

@Composable
fun ActiveStreamingCard(
    streamState: StreamState,
    currentVolume: Float,
    selectedSource: AudioSourceType,
    selectedLatencyMs: Int = 500,
    mutePhoneSpeaker: Boolean,
    onToggleMutePhoneSpeaker: (Boolean) -> Unit,
    onLatencySelected: (Int) -> Unit = {},
    onSourceSelected: (AudioSourceType) -> Unit,
    onStopStreaming: () -> Unit,
    onVolumeChanged: (Float) -> Unit
) {
    val isStreaming = streamState is StreamState.Streaming
    val isConnecting = streamState is StreamState.Connecting

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isStreaming) AirPlayBlue.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isStreaming -> SuccessGreen
                                    isConnecting -> WarningOrange
                                    else -> MaterialTheme.colorScheme.outlineVariant
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isStreaming) Icons.Default.CastConnected else Icons.Default.Speaker,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        val title = when (streamState) {
                            is StreamState.Streaming -> streamState.device.name
                            is StreamState.Connecting -> "Connecting to ${streamState.device.name}…"
                            is StreamState.Connected -> streamState.device.name
                            else -> "Ready to Stream"
                        }
                        val subtitle = when (streamState) {
                            is StreamState.Streaming -> "Streaming Audio (${streamState.source.displayName})"
                            is StreamState.Connecting -> "RTSP Handshake in progress…"
                            else -> "Select a speaker below to begin"
                        }

                        Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                if (isStreaming || isConnecting) {
                    IconButton(
                        onClick = onStopStreaming,
                        modifier = Modifier
                            .size(36.dp)
                            .background(ErrorRed.copy(alpha = 0.15f), CircleShape)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop", tint = ErrorRed)
                    }
                }
            }

            Column(modifier = Modifier.padding(top = 14.dp)) {
                if (isStreaming) {
                    // Volume Control
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.AutoMirrored.Filled.VolumeDown, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Slider(
                            value = currentVolume,
                            onValueChange = onVolumeChanged,
                            valueRange = 0f..100f,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "${currentVolume.toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(36.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                // Audio Source Selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedSource == AudioSourceType.SYSTEM_CAPTURE,
                        onClick = { onSourceSelected(AudioSourceType.SYSTEM_CAPTURE) },
                        label = { Text("System Audio (Any App)") },
                        leadingIcon = {
                            if (selectedSource == AudioSourceType.SYSTEM_CAPTURE) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        }
                    )

                    FilterChip(
                        selected = selectedSource == AudioSourceType.TEST_TONE,
                        onClick = { onSourceSelected(AudioSourceType.TEST_TONE) },
                        label = { Text("Test Tone") },
                        leadingIcon = {
                            if (selectedSource == AudioSourceType.TEST_TONE) {
                                Icon(Icons.Default.GraphicEq, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        }
                    )
                }

                // Mute phone speaker option for system capture
                if (selectedSource == AudioSourceType.SYSTEM_CAPTURE) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Заглушить динамик телефона/ТВ",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                            Text(
                                "Звук пойдет только на HomePod (динамики устройства не будут играть)",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        androidx.compose.material3.Switch(
                            checked = mutePhoneSpeaker,
                            onCheckedChange = onToggleMutePhoneSpeaker
                        )
                    }
                }

                // Latency / Delay Selector
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Задержка AirPlay (Буфер воспроизведения):",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val presets = listOf(
                        250 to "250 мс\nТВ / Видео",
                        500 to "500 мс\nОптимально",
                        1000 to "1.0 сек\nБаланс",
                        1500 to "1.5 сек\nМузыка"
                    )
                    presets.forEach { (ms, label) ->
                        FilterChip(
                            selected = selectedLatencyMs == ms,
                            onClick = { onLatencySelected(ms) },
                            label = {
                                Text(
                                    text = label,
                                    fontSize = 11.sp,
                                    lineHeight = 13.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceItemCard(
    device: AirPlayDevice,
    isConnecting: Boolean,
    isStreaming: Boolean,
    onConnect: () -> Unit,
    onRemove: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isStreaming) AirPlayBlue.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AirPlayBlue.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Speaker,
                        contentDescription = null,
                        tint = AirPlayBlue,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = device.name,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Wifi,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${device.ip}:${device.port} • ${device.model}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!isStreaming && !isConnecting && onRemove != null) {
                    IconButton(
                        onClick = onRemove,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "Удалить из списка",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }

                when {
                    isStreaming -> {
                        FilledTonalButton(
                            onClick = {},
                            enabled = false,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Active", color = SuccessGreen, fontWeight = FontWeight.Bold)
                        }
                    }
                    isConnecting -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 2.5.dp,
                            color = AirPlayBlue
                        )
                    }
                    else -> {
                        Button(
                            onClick = onConnect,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Stream")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ManualDeviceDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, Int) -> Unit
) {
    var name by remember { mutableStateOf("HomePod mini") }
    var ip by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("5000") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Speaker by IP") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Useful if your router isolates Wi-Fi multicast and mDNS doesn't find the HomePod automatically.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Speaker Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it.trim() },
                    label = { Text("IP Address (e.g. 192.168.1.150)") },
                    placeholder = { Text("192.168.1.150") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.trim() },
                    label = { Text("Port (Default: 5000)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (ip.isNotBlank()) {
                        onConfirm(name, ip, port.toIntOrNull() ?: 5000)
                    }
                },
                enabled = ip.isNotBlank()
            ) {
                Text("Add Speaker")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun HomePodGuideDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, contentDescription = null, tint = AirPlayBlue)
                Spacer(modifier = Modifier.width(8.dp))
                Text("HomePod Setup Guide")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "To stream from Android (just like from Linux / Bazzite), HomePod mini must allow access to devices on the local network:",
                    style = MaterialTheme.typography.bodyMedium
                )

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("1. Open the Apple Home app on iPhone, iPad, or Mac.", fontSize = 13.sp)
                        Text("2. Tap \"...\" (More) -> Home Settings.", fontSize = 13.sp)
                        Text("3. Go to \"Speakers & TV\".", fontSize = 13.sp)
                        Text("4. Set \"Allow Access\" to \"Anyone On The Same Network\".", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }

                Text(
                    "• Both devices must be connected to the same Wi-Fi router.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "• When you click Stream, Android will ask for permission to capture audio (standard dialog for casting).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "• Если у вас активен VPN (Amnezia, WireGuard и др.): добавьте «HomePod Streamer» в исключения VPN (Раздельное туннелирование) или отключите VPN во время трансляции.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "• Android TV: приложение полностью поддерживает приставки на Android 10+. В видеоплеерах (SmartTube, VLC, Kodi, Nova) можно выставить смещение звука (Audio Delay) под выбранную задержку буфера для идеальной синхронизации видео и губ (0 мс рассинхрона).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Got It")
            }
        }
    )
}
