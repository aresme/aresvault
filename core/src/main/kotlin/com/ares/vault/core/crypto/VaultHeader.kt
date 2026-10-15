package com.ares.vault.core.crypto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class InvalidHeaderException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The only plaintext-on-disk vault metadata. Contains nothing secret:
 * the KDF salt/params and two wrapped copies of the DEK.
 *
 * - [passwordSlot] = AES-GCM(KEK = Argon2id(master password, salt), DEK)
 * - [biometricSlot] = AES-GCM(Keystore biometric-bound key, DEK), optional
 */
@Serializable
class VaultHeader(
    val format: String = FORMAT,
    val version: Int = CURRENT_VERSION,
    val kdf: Argon2Params,
    @Serializable(with = Base64ByteArraySerializer::class) val salt: ByteArray,
    @SerialName("password_slot") val passwordSlot: Sealed,
    @SerialName("biometric_slot") val biometricSlot: Sealed? = null,
) {
    fun copy(
        kdf: Argon2Params = this.kdf,
        salt: ByteArray = this.salt,
        passwordSlot: Sealed = this.passwordSlot,
        biometricSlot: Sealed? = this.biometricSlot,
    ) = VaultHeader(format, version, kdf, salt, passwordSlot, biometricSlot)

    /**
     * AAD for the password slot. Binds version, salt and KDF params to the wrapped DEK
     * so editing any header field makes unwrapping fail instead of silently weakening it.
     */
    fun passwordSlotAad(): ByteArray =
        "$FORMAT|v$version|password|${kdf.canonical()}|".toByteArray(Charsets.US_ASCII) + salt

    fun encode(): ByteArray = json.encodeToString(serializer(), this).toByteArray(Charsets.UTF_8)

    override fun equals(other: Any?): Boolean = other is VaultHeader &&
        format == other.format && version == other.version && kdf == other.kdf &&
        salt.contentEquals(other.salt) && passwordSlot == other.passwordSlot &&
        biometricSlot == other.biometricSlot

    override fun hashCode(): Int =
        listOf(format, version, kdf, salt.contentHashCode(), passwordSlot, biometricSlot).hashCode()

    companion object {
        const val FORMAT = "aresvault-header"
        const val CURRENT_VERSION = 1
        const val SALT_BYTES = 16

        /** AAD for the biometric slot (domain-separated from the password slot). */
        fun biometricSlotAad(version: Int = CURRENT_VERSION): ByteArray =
            "$FORMAT|v$version|biometric".toByteArray(Charsets.US_ASCII)

        private val json = Json { encodeDefaults = true }

        fun decode(bytes: ByteArray): VaultHeader {
            val header = try {
                json.decodeFromString(serializer(), bytes.toString(Charsets.UTF_8))
            } catch (e: SerializationException) {
                throw InvalidHeaderException("Corrupt vault header", e)
            } catch (e: IllegalArgumentException) {
                throw InvalidHeaderException("Corrupt vault header", e)
            }
            if (header.format != FORMAT) throw InvalidHeaderException("Not a vault header")
            if (header.version != CURRENT_VERSION) {
                throw InvalidHeaderException("Unsupported header version ${header.version}")
            }
            if (header.salt.size != SALT_BYTES) throw InvalidHeaderException("Bad salt length")
            return header
        }
    }
}
