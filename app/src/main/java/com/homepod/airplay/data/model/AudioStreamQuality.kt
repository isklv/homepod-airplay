package com.homepod.airplay.data.model

enum class StreamQualityLevel(val label: String) {
    EXCELLENT("Отличное"),
    GOOD("Хорошее"),
    FAIR("Среднее"),
    POOR("Нестабильное")
}

data class AudioStreamQuality(
    val isStreaming: Boolean = false,
    val uptimeSeconds: Long = 0L,
    val bitrateKbps: Int = 0,
    val packetsSent: Long = 0L,
    val bytesSent: Long = 0L,
    val packetsPerSec: Int = 0,
    val bufferQueueSize: Int = 0,
    val bufferCapacity: Int = 128,
    val bufferUnderruns: Long = 0L,
    val bufferDrops: Long = 0L,
    val socketErrors: Long = 0L,
    val rtspPingMs: Long = -1L,
    val consecutiveFailures: Int = 0,
    val qualityLevel: StreamQualityLevel = StreamQualityLevel.EXCELLENT
)
