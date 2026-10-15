package com.ares.vault.data.autofill

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.util.AtomicFile
import com.ares.vault.core.crypto.AesGcm
import com.ares.vault.core.crypto.AuthenticationFailedException
import com.ares.vault.core.crypto.Sealed
import com.ares.vault.data.AresLog
import com.ares.vault.data.keystore.BiometricKeyStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Non-secret account metadata used to label autofill suggestions while locked. No passwords. */
@Serializable
data class IndexEntry(
    val id: String,
    val username: String,
    val title: String,
    val appPackage: String? = null,
    val appCertSha256: String? = null,
    val webDomain: String? = null,
    val extraUrls: List<String> = emptyList(),
)

/**
 * Locked-state autofill index (spec §6.3, "Show account names when locked").
 *
 * Encrypted with a Keystore key that needs *no* user authentication, so it can be read while
 * the vault is locked but is still protected at rest and bound to this device. It reveals
 * only which usernames exist for which apps/sites — never passwords — and is deleted when the
 * user turns the setting off. Selecting an entry still requires unlocking.
 */
@Singleton
class AutofillIndexStore @Inject constructor(@ApplicationContext context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, FILE_NAME))
    private val keyStore: KeyStore by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }
    private val listSerializer = ListSerializer(IndexEntry.serializer())

    @Synchronized
    fun write(entries: List<IndexEntry>) {
        val plain = Json.encodeToString(listSerializer, entries).toByteArray()
        val cipher = Cipher.getInstance(AesGcm.TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = BiometricKeyStore.seal(cipher, plain, AAD)
        plain.fill(0)
        val out = file.startWrite()
        try {
            out.write(Json.encodeToString(Sealed.serializer(), sealed).toByteArray())
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    /** Null if there is no index (setting off, never unlocked) or it can't be decrypted. */
    @Synchronized
    fun read(): List<IndexEntry>? {
        val bytes = try {
            file.readFully()
        } catch (_: FileNotFoundException) {
            return null
        }
        val key = keyStore.getKey(ALIAS, null) as? SecretKey ?: return null
        return try {
            val sealed = Json.decodeFromString(Sealed.serializer(), bytes.decodeToString())
            val cipher = Cipher.getInstance(AesGcm.TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(AesGcm.TAG_BITS, sealed.iv))
            val plain = BiometricKeyStore.open(cipher, sealed, AAD)
            Json.decodeFromString(listSerializer, plain.decodeToString()).also { plain.fill(0) }
        } catch (e: AuthenticationFailedException) {
            AresLog.w(COMPONENT, "Autofill index failed authentication; ignoring", e)
            null
        } catch (e: IllegalArgumentException) {
            AresLog.w(COMPONENT, "Autofill index unreadable; ignoring", e)
            null
        }
    }

    @Synchronized
    fun delete() {
        file.delete()
        if (keyStore.containsAlias(ALIAS)) keyStore.deleteEntry(ALIAS)
    }

    private fun key(): SecretKey {
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(spec)
            generateKey()
        }
    }

    private companion object {
        const val FILE_NAME = "autofill_index.json"
        const val ALIAS = "aresvault.autofill-index.v1"
        const val COMPONENT = "AutofillIndex"
        val AAD = "aresvault-autofill-index|v1".toByteArray()
    }
}
