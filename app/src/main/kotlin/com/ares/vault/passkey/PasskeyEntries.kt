package com.ares.vault.passkey

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.credentials.provider.PublicKeyCredentialEntry
import com.ares.vault.R
import com.ares.vault.core.passkey.WebAuthn
import com.ares.vault.data.repo.PasskeyRepository
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** Builds the account list Credential Manager shows for a sign-in request. Vault must be unlocked. */
@Singleton
class PasskeyEntries @Inject constructor(private val repository: PasskeyRepository) {
    private val codes = AtomicInteger(5000)

    suspend fun build(context: Context, request: BeginGetCredentialRequest): BeginGetCredentialResponse {
        val builder = BeginGetCredentialResponse.Builder()
        for (option in request.beginGetCredentialOptions.filterIsInstance<BeginGetPublicKeyCredentialOption>()) {
            val req = runCatching { WebAuthn.parseRequest(option.requestJson) }.getOrNull() ?: continue
            val allowed = req.allowCredentialIds.toSet()
            for (p in repository.forRp(req.rpId)) {
                if (allowed.isNotEmpty() && p.credentialId !in allowed) continue
                builder.addCredentialEntry(
                    PublicKeyCredentialEntry.Builder(context, p.userName.ifEmpty { p.userDisplayName }, getIntent(context, p.id), option)
                        .setDisplayName(p.userDisplayName.ifEmpty { p.rpId })
                        .setIcon(Icon.createWithResource(context, R.drawable.ic_autofill_key))
                        .apply { p.lastUsedAt?.let { setLastUsedTime(Instant.ofEpochMilli(it)) } }
                        .build(),
                )
            }
        }
        return builder.build()
    }

    fun getIntent(context: Context, passkeyId: String): PendingIntent = PendingIntent.getActivity(
        context,
        codes.incrementAndGet(),
        Intent(context, PasskeyActivity::class.java)
            .putExtra(PasskeyActivity.EXTRA_MODE, PasskeyActivity.MODE_GET)
            .putExtra(PasskeyActivity.EXTRA_PASSKEY_ID, passkeyId),
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun intent(context: Context, mode: String): PendingIntent = PendingIntent.getActivity(
        context,
        codes.incrementAndGet(),
        Intent(context, PasskeyActivity::class.java).putExtra(PasskeyActivity.EXTRA_MODE, mode),
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
