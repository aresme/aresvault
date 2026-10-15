package com.ares.vault.core.crypto

import java.security.SecureRandom

/**
 * Master password → KEK (Argon2id) → wraps the random DEK (the SQLCipher key).
 * Neither the master password nor the KEK is ever persisted.
 */
class VaultKeyHierarchy(
    private val kdf: Kdf,
    private val random: SecureRandom = SecureRandom(),
) {
    /** Result of vault creation. The caller owns [dek] and must wipe it when locking. */
    class Created(val header: VaultHeader, val dek: ByteArray)

    fun create(password: CharArray, params: Argon2Params): Created {
        val dek = SecureBytes.random(AesGcm.KEY_BYTES, random)
        val header = wrapForPassword(dek, password, params, biometricSlot = null)
        return Created(header, dek)
    }

    /**
     * Returns the DEK, or throws [AuthenticationFailedException] for a wrong password
     * *or* a tampered header (GCM cannot, and should not, tell the two apart).
     */
    fun unlock(header: VaultHeader, password: CharArray): ByteArray {
        val kek = deriveKek(password, header.salt, header.kdf)
        try {
            return AesGcm.open(kek, header.passwordSlot, header.passwordSlotAad())
        } finally {
            SecureBytes.wipe(kek)
        }
    }

    /**
     * Re-wraps the existing DEK under a new password with a fresh salt. The database is
     * not re-encrypted (spec §6.2); the biometric slot stays valid because the DEK is unchanged.
     */
    fun changePassword(
        header: VaultHeader,
        dek: ByteArray,
        newPassword: CharArray,
        params: Argon2Params,
    ): VaultHeader = wrapForPassword(dek, newPassword, params, header.biometricSlot)

    private fun wrapForPassword(
        dek: ByteArray,
        password: CharArray,
        params: Argon2Params,
        biometricSlot: Sealed?,
    ): VaultHeader {
        val salt = SecureBytes.random(VaultHeader.SALT_BYTES, random)
        val placeholder = VaultHeader(
            kdf = params,
            salt = salt,
            passwordSlot = Sealed(ByteArray(0), ByteArray(0)),
            biometricSlot = biometricSlot,
        )
        val kek = deriveKek(password, salt, params)
        try {
            val slot = AesGcm.seal(kek, dek, placeholder.passwordSlotAad(), random)
            return placeholder.copy(passwordSlot = slot)
        } finally {
            SecureBytes.wipe(kek)
        }
    }

    private fun deriveKek(password: CharArray, salt: ByteArray, params: Argon2Params): ByteArray {
        val passwordBytes = SecureBytes.utf8(password)
        try {
            return kdf.derive(passwordBytes, salt, params)
        } finally {
            SecureBytes.wipe(passwordBytes)
        }
    }
}
