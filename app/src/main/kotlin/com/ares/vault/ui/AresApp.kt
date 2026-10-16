package com.ares.vault.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ares.vault.ui.backup.BackupScreen
import com.ares.vault.ui.detail.DetailScreen
import com.ares.vault.ui.edit.EditScreen
import com.ares.vault.ui.generator.GeneratorScreen
import com.ares.vault.ui.lock.LockScreen
import com.ares.vault.ui.nav.BackupRoute
import com.ares.vault.ui.nav.ImportAuthenticatorRoute
import com.ares.vault.ui.nav.PasskeysRoute
import com.ares.vault.ui.passkeys.PasskeysScreen
import com.ares.vault.ui.nav.ScanTotpRoute
import com.ares.vault.ui.scan.ImportAuthenticatorRoute
import com.ares.vault.ui.scan.ScanResultViewModel
import com.ares.vault.ui.scan.ScanTotpScreen
import com.ares.vault.ui.nav.DetailRoute
import com.ares.vault.ui.nav.EditRoute
import com.ares.vault.ui.nav.GeneratorRoute
import com.ares.vault.ui.nav.LockRoute
import com.ares.vault.ui.nav.OnboardingRoute
import com.ares.vault.ui.nav.SettingsRoute
import com.ares.vault.ui.nav.VaultListRoute
import com.ares.vault.ui.nav.VaultRoute
import com.ares.vault.ui.onboarding.OnboardingScreen
import com.ares.vault.ui.settings.SettingsScreen
import com.ares.vault.ui.vault.VaultListScreen

@Composable
fun AresApp(appState: AppStateViewModel = hiltViewModel()) {
    val screen by appState.screen.collectAsStateWithLifecycle()
    if (screen == AppScreen.LOADING) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val nav = rememberNavController()
    // No transitions: on lock the vault UI must vanish instantly, not fade out.
    NavHost(
        nav,
        startDestination = routeFor(screen),
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        composable<OnboardingRoute> { OnboardingScreen() }
        composable<LockRoute> { LockScreen() }
        // Recreated on every unlock, so no decrypted state survives a lock.
        composable<VaultRoute> { VaultNavHost() }
    }
    LaunchedEffect(screen) {
        val route = routeFor(screen)
        if (nav.currentDestination?.hasRoute(route::class) != true) {
            nav.navigate(route) { popUpTo(nav.graph.id) { inclusive = true } }
        }
    }
}

private fun routeFor(screen: AppScreen): Any = when (screen) {
    AppScreen.LOADING, AppScreen.ONBOARDING -> OnboardingRoute
    AppScreen.LOCKED -> LockRoute
    AppScreen.UNLOCKED -> VaultRoute
}

@Composable
private fun VaultNavHost() {
    val nav = rememberNavController()
    val enrollerVm: ReenrollViewModel = hiltViewModel()
    NavHost(nav, startDestination = VaultListRoute) {
        composable<VaultListRoute> {
            VaultListScreen(
                onOpen = { nav.navigate(DetailRoute(it)) },
                onAdd = { nav.navigate(EditRoute()) },
                onGenerator = { nav.navigate(GeneratorRoute) },
                onSettings = { nav.navigate(SettingsRoute) },
                onPasskeys = { nav.navigate(PasskeysRoute) },
                onReenrollBiometric = { enrollerVm.enable(it) },
            )
        }
        composable<DetailRoute> {
            DetailScreen(onBack = { nav.popBackStack() }, onEdit = { nav.navigate(EditRoute(it)) })
        }
        composable<EditRoute> {
            EditScreen(
                onScanTotp = { nav.navigate(ScanTotpRoute) },
                onBack = { nav.popBackStack() },
                onSaved = { id ->
                    nav.popBackStack()
                    if (nav.currentBackStackEntry?.destination?.route?.contains("DetailRoute") != true) {
                        nav.navigate(DetailRoute(id))
                    }
                },
            )
        }
        composable<GeneratorRoute> {
            GeneratorScreen(onBack = { nav.popBackStack() }, onSaveAsEntry = { nav.navigate(EditRoute()) })
        }
        composable<SettingsRoute> {
            SettingsScreen(onBack = { nav.popBackStack() }, onBackup = { nav.navigate(BackupRoute) }, onPasskeys = { nav.navigate(PasskeysRoute) })
        }
        composable<BackupRoute> {
            BackupScreen(onBack = { nav.popBackStack() }, onImportAuthenticator = { nav.navigate(ImportAuthenticatorRoute) })
        }
        composable<PasskeysRoute> { PasskeysScreen(onBack = { nav.popBackStack() }) }
        composable<ScanTotpRoute> {
            val scans: ScanResultViewModel = hiltViewModel()
            ScanTotpScreen(onBack = { nav.popBackStack() }, onResult = { scans.deliver(it); nav.popBackStack() })
        }
        composable<ImportAuthenticatorRoute> { ImportAuthenticatorRoute(onBack = { nav.popBackStack() }) }
    }
}
