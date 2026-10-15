/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.scrobbling

import androidx.datastore.preferences.core.booleanPreferencesKey

/** Mirrors song likes onto Last.fm loved tracks (track.love / track.unlove). Off by default. */
val LastFmSendLovesKey = booleanPreferencesKey("lastfmSendLoves")
