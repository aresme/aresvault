package com.ares.vault.data.keystore

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import com.ares.vault.core.crypto.AesGcm
import com.ares.vault.core.crypto.AuthenticationFailedException
import com.ares.vault.core.crypto.Sealed
import com.ares.vault.data.AresLog
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hardware-backed AES-256-GCM key that wraps the DEK for fingerprint unlock (spec §4.2).
 * Every use requires a fresh BIOMETRIC_STRONG authentication via a BiometricPrompt
 * CryptoObject, and enrolling a new fingerprint permanently invalidates the key.
 */
@Singleton
class BiometricKeyStore @Inject constructor() {

    sealed interface CipherResult {
        class Ready(val cipher: Cipher) : CipherResult
        /** Key was invalidated (e.g. new fingerprint enrolled) — fall back to master password. */
        data object Invalidated : CipherResult
        data object Missing : CipherResult
    }

    private val keyStore: KeyStore by lazy { KeyStore.getInstance(PROVIDER).apply { load(null) } }

    /** Creates a new key (replacing any old one) and returns a cipher ready to encrypt. */
    fun newEncryptCipher(): Cipher {
        deleteKey()
        val key = generateKey()
        return Cipher.getInstance(AesGcm.TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    fun decryptCipher(iv: ByteArray): CipherResult {
        val key = keyStore.getKey(ALIAS, null) as? SecretKey ?: return CipherResult.Missing
        return try {
            val cipher = Cipher.getInstance(AesGcm.TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(AesGcm.TAG_BITS, iv))
            CipherResult.Ready(cipher)
        } catch (e: KeyPermanentlyInvalidatedException) {
            AresLog.w(COMPONENT, "Biometric key invalidated (biometric enrollment changed)")
            deleteKey()
            CipherResult.Invalidated
        }
    }

    fun deleteKey() {
        if (keyStore.containsAlias(ALIAS)) keyStore.deleteEntry(ALIAS)
    }

    private fun generateKey(): SecretKey {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return generateKey(strongBox = true).also {
                    AresLog.i(COMPONENT, "Biometric key created in StrongBox")
                }
            } catch (_: StrongBoxUnavailableException) {
                AresLog.i(COMPONENT, "StrongBox unavailable; using TEE-backed key")
            }
        }
        return generateKey(strongBox = false)
    }

    private fun generateKey(strongBox: Boolean): SecretKey {
        val builder = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Timeout 0 = every operation needs its own biometric auth (no grace window).
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
            init(builder.build())
            generateKey()
        }
    }

    companion object {
        private const val PROVIDER = "AndroidKeyStore"
        private const val ALIAS = "aresvault.biometric.dek-wrap.v1"
        private const val COMPONENT = "BiometricKey"

        /** Seals [plaintext] with an authenticated encrypt cipher (Keystore picks the IV). */
        fun seal(cipher: Cipher, plaintext: ByteArray, aad: ByteArray): Sealed {
            cipher.updateAAD(aad)
            val ct = cipher.doFinal(plaintext)
            return Sealed(cipher.iv, ct)
        }

        fun open(cipher: Cipher, sealed: Sealed, aad: ByteArray): ByteArray = try {
            cipher.updateAAD(aad)
            cipher.doFinal(sealed.ciphertext)
        } catch (e: AEADBadTagException) {
            throw AuthenticationFailedException(e)
        }
    }
}
