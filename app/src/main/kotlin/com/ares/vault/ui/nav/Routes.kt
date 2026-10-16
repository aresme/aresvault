package com.ares.vault.ui.nav

import kotlinx.serialization.Serializable

// Top-level gates. Switching pops everything, so each gate's ViewModels die with it.
@Serializable data object OnboardingRoute
@Serializable data object LockRoute
@Serializable data object VaultRoute

// Routes carry only ids — never secrets (they end up in saved-instance state).
@Serializable data object VaultListRoute
@Serializable data class DetailRoute(val id: String)
@Serializable data class EditRoute(val id: String? = null)
@Serializable data object GeneratorRoute
@Serializable data object SettingsRoute
/** [emailNow]: opened from the weekly reminder — go straight to “Email encrypted backup”. */
@Serializable data class BackupRoute(val emailNow: Boolean = false)
@Serializable data object ScanTotpRoute
@Serializable data object PasskeysRoute
@Serializable data object ImportAuthenticatorRoute
