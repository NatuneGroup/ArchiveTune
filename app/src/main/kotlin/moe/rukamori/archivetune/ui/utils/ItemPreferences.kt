/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.utils

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf

/**
 * Preferences read by list, grid, queue and mini-player items. They are collected once at the
 * root and provided here: one DataStore collector per item multiplied into hundreds of live
 * collectors on long lists, and every settings write re-ran each of them.
 */
@Immutable
data class ItemPreferences(
    val hapticFeedbackEnabled: Boolean = true,
    val swipeToSongEnabled: Boolean = true,
    val cropThumbnailToSquare: Boolean = false,
    val pureBlack: Boolean = false,
)

val LocalItemPreferences = compositionLocalOf { ItemPreferences() }
