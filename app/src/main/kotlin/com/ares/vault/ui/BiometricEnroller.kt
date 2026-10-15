package com.ares.vault.ui

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.fragment.app.FragmentActivity
import com.ares.vault.data.session.SettingsRepository
import com.ares.vault.data.session.VaultManager
import com.ares.vault.prefs.AppPrefs
import javax.inject.Inject
import javax.inject.Singleton

/** Enables/disables fingerprint unlock. Holds no Activity beyond a single call. */
@Singleton
class BiometricEnroller @Inject constructor(
    private val manager: VaultManager,
    private val prefs: AppPrefs,
    private val settings: SettingsRepository,
) {
    enum class Availability { AVAILABLE, NONE_ENROLLED, UNAVAILABLE }

    sealed interface Result {
        data object Enabled : Result
        data object Cancelled : Result
        class Failed(val message: String) : Result
    }

    fun availability(context: Context): Availability =
        when (BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Availability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Availability.NONE_ENROLLED
            else -> Availability.UNAVAILABLE
        }

    suspend fun enable(activity: FragmentActivity): Result {
        val cipher = try {
            manager.biometricEnrollCipher()
        } catch (e: Exception) {
            return Result.Failed(e.message ?: "Could not create fingerprint key")
        }
        return when (val r = BiometricAuthenticator.authenticate(
            activity, cipher, "Enable fingerprint unlock", "Confirm with your fingerprint", negativeText = "Cancel",
        )) {
            is BiometricAuthenticator.Result.Success -> {
                manager.completeBiometricEnrollment(r.cipher)
                prefs.biometricWanted = true
                settings.update { it.copy(biometricEnabled = true) }
                Result.Enabled
            }
            is BiometricAuthenticator.Result.Error -> Result.Failed(r.message)
            else -> Result.Cancelled
        }
    }

    suspend fun disable() {
        manager.disableBiometric()
        prefs.biometricWanted = false
        settings.update { it.copy(biometricEnabled = false) }
    }
}
