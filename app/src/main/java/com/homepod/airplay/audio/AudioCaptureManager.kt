package com.homepod.airplay.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AudioCaptureManager(
    private val mediaProjection: MediaProjection,
    private val onAudioChunkCaptured: (ByteArray, Int) -> Unit
) {
    companion object {
        private const val TAG = "AudioCaptureManager"
        const val TARGET_SAMPLE_RATE = 44100
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null

    @Volatile
    private var isRecording = false

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.Q)
    fun start(scope: CoroutineScope): Boolean {
        if (isRecording) return true

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        var sampleRate = TARGET_SAMPLE_RATE
        var minBufferSize = AudioRecord.getMinBufferSize(sampleRate, CHANNEL_CONFIG, AUDIO_FORMAT)

        // If 44.1kHz is not supported by device capture HAL, fallback to 48kHz
        if (minBufferSize <= 0) {
            sampleRate = 48000
            minBufferSize = AudioRecord.getMinBufferSize(sampleRate, CHANNEL_CONFIG, AUDIO_FORMAT)
        }

        val bufferSize = (minBufferSize * 2).coerceAtLeast(8192)

        val format = AudioFormat.Builder()
            .setEncoding(AUDIO_FORMAT)
            .setSampleRate(sampleRate)
            .setChannelMask(CHANNEL_CONFIG)
            .build()

        return try {
            val record = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .build()

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                return false
            }

            audioRecord = record
            record.startRecording()
            isRecording = true

            Log.d(TAG, "AudioPlaybackCapture recording started at $sampleRate Hz")

            val isResamplingNeeded = (sampleRate != TARGET_SAMPLE_RATE)
            captureJob = scope.launch(Dispatchers.IO) {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                val readBuffer = ByteArray(4096)

                while (isActive && isRecording) {
                    val readBytes = record.read(readBuffer, 0, readBuffer.size)
                    if (readBytes > 0) {
                        if (isResamplingNeeded) {
                            val resampled = resample48kTo44k(readBuffer, readBytes)
                            onAudioChunkCaptured(resampled, resampled.size)
                        } else {
                            onAudioChunkCaptured(readBuffer, readBytes)
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioPlaybackCapture: ${e.message}", e)
            false
        }
    }

    fun stop() {
        isRecording = false
        captureJob?.cancel()
        captureJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord: ${e.message}")
        }
        audioRecord = null
    }

    /**
     * Resamples 16-bit stereo PCM from 48000 Hz to 44100 Hz using linear interpolation.
     */
    private fun resample48kTo44k(input: ByteArray, inputLength: Int): ByteArray {
        val inFrames = inputLength / 4 // 4 bytes per stereo 16-bit frame
        val outFrames = (inFrames * TARGET_SAMPLE_RATE.toDouble() / 48000.0).toInt()
        val output = ByteArray(outFrames * 4)

        var outIdx = 0
        for (i in 0 until outFrames) {
            val srcPos = i * (48000.0 / TARGET_SAMPLE_RATE)
            val srcIdx = srcPos.toInt().coerceAtMost(inFrames - 2)
            val frac = (srcPos - srcIdx).toFloat()

            val byteOffset1 = srcIdx * 4
            val byteOffset2 = (srcIdx + 1) * 4

            // Left channel
            val l1 = (input[byteOffset1].toInt() and 0xFF) or (input[byteOffset1 + 1].toInt() shl 8)
            val l2 = (input[byteOffset2].toInt() and 0xFF) or (input[byteOffset2 + 1].toInt() shl 8)
            val lInterp = (l1 + (l2 - l1) * frac).toInt()

            // Right channel
            val r1 = (input[byteOffset1 + 2].toInt() and 0xFF) or (input[byteOffset1 + 3].toInt() shl 8)
            val r2 = (input[byteOffset2 + 2].toInt() and 0xFF) or (input[byteOffset2 + 3].toInt() shl 8)
            val rInterp = (r1 + (r2 - r1) * frac).toInt()

            output[outIdx++] = (lInterp and 0xFF).toByte()
            output[outIdx++] = ((lInterp shr 8) and 0xFF).toByte()
            output[outIdx++] = (rInterp and 0xFF).toByte()
            output[outIdx++] = ((rInterp shr 8) and 0xFF).toByte()
        }
        return output
    }
}
