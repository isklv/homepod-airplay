package com.homepod.airplay.data.model

sealed interface StreamState {
    data object Disconnected : StreamState
    data object Scanning : StreamState
    data class Connecting(val device: AirPlayDevice) : StreamState
    data class Connected(val device: AirPlayDevice) : StreamState
    data class Streaming(val device: AirPlayDevice, val source: AudioSourceType) : StreamState
    data class Paused(val device: AirPlayDevice) : StreamState
    data class Error(val message: String) : StreamState
}

enum class AudioSourceType(val displayName: String) {
    SYSTEM_CAPTURE("System Audio (Any App)"),
    TEST_TONE("Built-in Test Tone")
}
