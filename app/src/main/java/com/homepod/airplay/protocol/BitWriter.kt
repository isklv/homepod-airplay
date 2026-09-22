package com.homepod.airplay.protocol

/**
 * BitWriter for packing arbitrary-bit fields into a byte array,
 * matching Apple Lossless (ALAC) uncompressed frame encoding in RAOP.
 */
class BitWriter(capacity: Int = 2048) {
    val buffer = ByteArray(capacity)
    var byteIndex = 0
        private set
    var bitPos = 0
        private set

    fun reset() {
        byteIndex = 0
        bitPos = 0
    }

    fun write(data: Int, bitLength: Int) {
        val rb = 8 - bitPos - bitLength
        if (rb >= 0) {
            if (bitPos == 0) {
                buffer[byteIndex] = 0
            }
            buffer[byteIndex] = (buffer[byteIndex].toInt() or ((data shl rb) and 0xFF)).toByte()
            bitPos += bitLength
            if (bitPos == 8) {
                byteIndex++
                bitPos = 0
            }
        } else {
            val shift = -rb
            buffer[byteIndex] = (buffer[byteIndex].toInt() or ((data ushr shift) and 0xFF)).toByte()
            byteIndex++
            buffer[byteIndex] = ((data shl (8 + rb)) and 0xFF).toByte()
            bitPos = shift
        }
    }

    fun totalBytes(): Int {
        return if (bitPos > 0) byteIndex + 1 else byteIndex
    }
}
