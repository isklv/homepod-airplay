package com.homepod.airplay.data.model

data class AirPlayDevice(
    val id: String,
    val name: String,
    val ip: String,
    val port: Int = 5000,
    val model: String = "HomePod",
    val isHomePod: Boolean = true,
    val encryptionType: String = "RSA",
    val sampleRate: Int = 44100,
    val channels: Int = 2
)
