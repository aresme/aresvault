package com.ares.vault.core.crypto

import kotlinx.serialization.Serializable

/** AES-GCM output: IV plus ciphertext-with-appended-tag. */
@Serializable
class Sealed(
    @Serializable(with = Base64ByteArraySerializer::class) val iv: ByteArray,
    @Serializable(with = Base64ByteArraySerializer::class) val ciphertext: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is Sealed && iv.contentEquals(other.iv) && ciphertext.contentEquals(other.ciphertext)

    override fun hashCode(): Int = 31 * iv.contentHashCode() + ciphertext.contentHashCode()
}
