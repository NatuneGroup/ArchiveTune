/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.auth

import moe.rukamori.archivetune.viewmodels.LoginError

/** Primitive WebView events keep Android response objects out of recovery decisions. */
internal object YouTubeLoginWebEvents {
    fun httpError(mainFrame: Boolean, status: Int): LoginError? =
        if (mainFrame && status >= 400) LoginError.Http else null

    fun networkError(mainFrame: Boolean, unsupportedScheme: Boolean): LoginError? =
        if (!mainFrame) null else if (unsupportedScheme) LoginError.UnsupportedNavigation else LoginError.Network
}

/** A callback must still belong to the same document and evaluation after it is delivered. */
internal class YouTubeLoginDocumentCallbacks {
    var document = 0L
        private set
    private var evaluation = 0L
    private var released = false

    fun pageStarted() { document += 1; invalidateEvaluation() }
    fun invalidateEvaluation() { evaluation += 1 }
    fun nextEvaluation(): Long = ++evaluation
    fun accepts(page: Long, evaluationId: Long): Boolean =
        !released && page == document && evaluationId == evaluation
    fun release() { released = true; invalidateEvaluation() }
}
