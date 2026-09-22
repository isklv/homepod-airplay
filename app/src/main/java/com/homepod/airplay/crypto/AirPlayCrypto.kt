package com.homepod.airplay.crypto

import android.util.Base64
import java.math.BigInteger
import java.security.KeyFactory
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object AirPlayCrypto {

    // Apple RAOP 2048-bit RSA Public Key modulus (same as PipeWire / OwnTone / AirPlay 1)
    private const val RSA_MODULUS_BASE64 =
        "59dE8qLieItsH1WgjrcFRKj6eUWqi+bGLOX1HL3U3GhC/j0Qg90u3sG/1CUtwC" +
        "5vOYvfDmFI6oSFXi5ELabWJmT2dKHzBJKa3k9ok+8t9ucRqMd6DZHJ2YCCLlDR" +
        "KSKv6kDqnw4UwPdpOMXziC/AMj3Z/lUVX1G7WSHCAWKf1zNS1eLvqr+boEjXuB" +
        "OitnZ/bDzPHrTOZz0Dew0uowxf/+sG+NCK3eQJVxqcaJ/vEHKIVd2M+5qL71yJ" +
        "Q+87X6oV3eaYvt3zWZYD6z5vYTcrtij2VZ9Zmni/UAaHqn9JdsBWLUEpVviYnh" +
        "imNVvYFZeCXg/IdTQ+x4IRdiXNv5hEew=="

    // Exponent 65537 (AQAB in Base64)
    private val RSA_EXPONENT = BigInteger.valueOf(65537)

    private val secureRandom = SecureRandom()

    private val rsaPublicKey: PublicKey by lazy {
        val modulusBytes = Base64.decode(RSA_MODULUS_BASE64, Base64.DEFAULT)
        val modulus = BigInteger(1, modulusBytes)
        val keySpec = RSAPublicKeySpec(modulus, RSA_EXPONENT)
        KeyFactory.getInstance("RSA").generatePublic(keySpec)
    }

    fun generateRandomBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        secureRandom.nextBytes(bytes)
        return bytes
    }

    /**
     * Encrypts the 16-byte random AES key using Apple's RSA key with OAEP padding.
     */
    fun encryptAesKey(aesKey: ByteArray): ByteArray {
        val cipher = try {
            Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")
        } catch (e: Exception) {
            Cipher.getInstance("RSA/ECB/OAEPPadding")
        }
        cipher.init(Cipher.ENCRYPT_MODE, rsaPublicKey)
        return cipher.doFinal(aesKey)
    }

    /**
     * Creates an AES-128-CBC cipher initialized with key and IV.
     */
    fun createAesCipher(key: ByteArray, iv: ByteArray): Cipher {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        return cipher
    }

    /**
     * Helper to encode byte array to Base64 without newlines.
     */
    fun toBase64(bytes: ByteArray): String {
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
