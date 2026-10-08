/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.lyrics

import moe.rukamori.archivetune.canvas.AppleMusicProvider
import moe.rukamori.archivetune.paxsenix.PaxsenixLyrics

/**
 * Hands the Paxsenix module a live Apple Music web player token before it is asked for lyrics.
 *
 * The module keeps its own token: it scrapes one from `index-<hash>.js`, a bundle name Apple no
 * longer serves, and falls back to a JWT that expired 2026-06-17. Both are 401s, and the module
 * cannot be fixed from here — it is a pinned submodule. Installing a token first means its scrape
 * and its fallback are never reached, and every Apple Music lookup the module makes carries a
 * token this app validated.
 */
object PaxsenixAppleMusicToken {
    /**
     * Installs a validated token when one can be obtained, and reports whether the module now
     * holds one. Callers that have an alternative provider on failure can ignore the result; the
     * Apple Music lyrics provider cannot, because the module's own token is dead.
     */
    suspend fun install(): Boolean {
        val token = AppleMusicProvider.ensureTokenFresh() ?: return false
        PaxsenixLyrics.setAmpToken(token)
        return true
    }
}
