package com.ares.vault.core.crypto

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Thrown when an AES-GCM tag does not verify: wrong key or tampered data. */
class AuthenticationFailedException(cause: Throwable? = null) :
    GeneralSecurityException("Authentication failed", cause)

/**
 * Thin wrapper over the platform AES-256-GCM implementation (JCA) for raw keys.
 * Keystore-held keys use the same [Sealed] format but go through their own Cipher.
 */
object AesGcm {
    const val KEY_BYTES = 32
    const val IV_BYTES = 12
    const val TAG_BITS = 128
    const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun seal(
        key: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
        random: SecureRandom = SecureRandom(),
    ): Sealed {
        require(key.size == KEY_BYTES) { "AES-256 key required" }
        // A fresh random 96-bit IV per encryption; keys here encrypt only a handful of
        // messages, so random IV collision is not a practical concern.
        val iv = SecureBytes.random(IV_BYTES, random)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad)
        return Sealed(iv, cipher.doFinal(plaintext))
    }

    fun open(key: ByteArray, sealed: Sealed, aad: ByteArray): ByteArray {
        require(key.size == KEY_BYTES) { "AES-256 key required" }
        if (sealed.iv.size != IV_BYTES) throw AuthenticationFailedException()
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, sealed.iv),
            )
            cipher.updateAAD(aad)
            cipher.doFinal(sealed.ciphertext)
        } catch (e: AEADBadTagException) {
            throw AuthenticationFailedException(e)
        }
    }
}
