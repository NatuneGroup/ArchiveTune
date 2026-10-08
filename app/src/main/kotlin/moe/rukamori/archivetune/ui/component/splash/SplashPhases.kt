/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Opening animation phase vocabulary — ported from YumaPlayer (github.com/MuwMx/YumaPlayer),
 * ui/component/splash/SplashPhases.kt (GPL-3.0).
 */



package moe.rukamori.archivetune.ui.component.splash

sealed interface SplashPhase {
    data object Dust : SplashPhase
    data object Gather : SplashPhase
    data object Ignite : SplashPhase
    data object Burst : SplashPhase
    data object Idle : SplashPhase
    data object Transit : SplashPhase
    data object Success : SplashPhase
    data object Error : SplashPhase
}
