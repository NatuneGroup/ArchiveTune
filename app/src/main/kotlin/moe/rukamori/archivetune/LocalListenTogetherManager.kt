/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune

import androidx.compose.runtime.staticCompositionLocalOf
import moe.rukamori.archivetune.listentogether.ListenTogetherManager

/**
 * CompositionLocal exposing the singleton [ListenTogetherManager] to the Listen Together UI.
 * Mirrors vivi-music's `LocalListenTogetherManager` (declared at the bottom of vivi's
 * MainActivity.kt); here it lives in its own file. MainActivity provides the value inside its
 * CompositionLocalProvider tree — screens read `LocalListenTogetherManager.current` and render
 * a "not configured" placeholder when it is null.
 */
val LocalListenTogetherManager = staticCompositionLocalOf<ListenTogetherManager?> { null }
