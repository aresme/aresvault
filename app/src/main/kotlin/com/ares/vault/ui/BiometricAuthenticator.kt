package com.ares.vault.ui

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Shows a BiometricPrompt bound to [cipher] (a Keystore CryptoObject), so the key can
 * only be used after a real BIOMETRIC_STRONG match — not merely after a callback fires.
 */
object BiometricAuthenticator {
    sealed interface Result {
        class Success(val cipher: Cipher) : Result
        class Error(val message: String) : Result
        data object Cancelled : Result
        data object Confirmed : Result
    }

    suspend fun authenticate(
        activity: FragmentActivity,
        cipher: Cipher,
        title: String,
        subtitle: String? = null,
        negativeText: String = "Use master password",
    ): Result = suspendCancellableCoroutine { cont ->
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authed = result.cryptoObject?.cipher
                    if (cont.isActive) {
                        cont.resume(authed?.let { Result.Success(it) } ?: Result.Error("No cipher"))
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (!cont.isActive) return
                    val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    cont.resume(if (cancelled) Result.Cancelled else Result.Error(errString.toString()))
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .apply { subtitle?.let { setSubtitle(it) } }
            .setNegativeButtonText(negativeText)
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
        cont.invokeOnCancellation { prompt.cancelAuthentication() }
    }

    /** Whether [confirm] can run (fingerprint or screen lock is set up). */
    fun canConfirm(activity: FragmentActivity): Boolean =
        BiometricManager.from(activity).canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * User-presence check without a key (vault already unlocked): fingerprint, falling back to
     * the phone's PIN/pattern. Used for "ask before every fill".
     */
    suspend fun confirm(activity: FragmentActivity, title: String, subtitle: String?): Result =
        suspendCancellableCoroutine { cont ->
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (cont.isActive) cont.resume(Result.Confirmed)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (!cont.isActive) return
                        val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                            errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON || errorCode == BiometricPrompt.ERROR_CANCELED
                        cont.resume(if (cancelled) Result.Cancelled else Result.Error(errString.toString()))
                    }
                },
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .apply { subtitle?.let { setSubtitle(it) } }
                .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                .build()
            prompt.authenticate(info)
            cont.invokeOnCancellation { prompt.cancelAuthentication() }
        }
}
