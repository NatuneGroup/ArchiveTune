/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.constants

import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * Apple Music sign-in state. Login-only by design: no pool participation, and independent of the
 * Developer Options manual-login settings.
 */
val AppleMusicMediaUserTokenKey = stringPreferencesKey("appleMusicMediaUserToken")
val AppleMusicDevTokenKey = stringPreferencesKey("appleMusicDevToken")
val AppleMusicAccountNameKey = stringPreferencesKey("appleMusicAccountName")
