package com.homepod.airplay.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sin

class TestToneGenerator(
    private val onAudioChunkGenerated: (ByteArray, Int) -> Unit
) {
    private var generatorJob: Job? = null

    @Volatile
    private var isPlaying = false

    fun start(scope: CoroutineScope) {
        if (isPlaying) return
        isPlaying = true

        generatorJob = scope.launch(Dispatchers.Default) {
            val sampleRate = 44100
            val framesPerChunk = 352
            val chunkBytes = framesPerChunk * 4

            // Musical arpeggio notes: A4 (440Hz), C#5 (554Hz), E5 (659Hz), A5 (880Hz)
            val chordNotes = doubleArrayOf(440.0, 554.37, 659.25, 880.0)
            var noteIndex = 0
            var phase = 0.0
            var sampleCounter = 0

            val frameDurationNs = (1_000_000_000L * framesPerChunk) / sampleRate
            var nextTimeNs = System.nanoTime()

            while (isActive && isPlaying) {
                val currentFreq = chordNotes[noteIndex]
                val phaseIncrement = (2.0 * Math.PI * currentFreq) / sampleRate

                val chunk = ByteArray(chunkBytes)
                var idx = 0
                for (i in 0 until framesPerChunk) {
                    val envelope = 0.20 // comfortable, clean volume
                    val sampleVal = (sin(phase) * envelope * 32767.0).toInt().coerceIn(-32768, 32767)
                    phase += phaseIncrement
                    if (phase >= 2.0 * Math.PI) {
                        phase -= 2.0 * Math.PI
                    }

                    // Left channel (Little-Endian: low byte first, high byte second)
                    chunk[idx++] = (sampleVal and 0xFF).toByte()
                    chunk[idx++] = ((sampleVal shr 8) and 0xFF).toByte()
                    // Right channel
                    chunk[idx++] = (sampleVal and 0xFF).toByte()
                    chunk[idx++] = ((sampleVal shr 8) and 0xFF).toByte()
                }

                onAudioChunkGenerated(chunk, chunkBytes)

                sampleCounter += framesPerChunk
                if (sampleCounter >= sampleRate / 2) { // Change note every 500ms
                    sampleCounter = 0
                    noteIndex = (noteIndex + 1) % chordNotes.size
                }

                nextTimeNs += frameDurationNs
                val sleepNs = nextTimeNs - System.nanoTime()
                if (sleepNs > 2_000_000) {
                    delay(sleepNs / 1_000_000)
                } else if (sleepNs < -50_000_000) {
                    nextTimeNs = System.nanoTime()
                }
            }
        }
    }

    fun stop() {
        isPlaying = false
        generatorJob?.cancel()
        generatorJob = null
    }
}
