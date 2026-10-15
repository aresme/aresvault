package com.ares.vault.core.crypto

/**
 * Password-based key derivation (Argon2id). Implementations wrap a vetted library;
 * we never implement the primitive ourselves.
 */
interface Kdf {
    /**
     * Derives [Argon2Params.outputLength] bytes from [password] and [salt].
     * The caller owns (and must wipe) both the input and the returned array.
     */
    fun derive(password: ByteArray, salt: ByteArray, params: Argon2Params): ByteArray
}
