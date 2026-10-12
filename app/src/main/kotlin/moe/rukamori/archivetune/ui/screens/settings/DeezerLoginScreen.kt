/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * WebView-based Deezer sign-in. Deezer has no OAuth flow we can use, so the credential is the `arl`
 * session cookie the site sets on a signed-in browser. Mirrors the [TidalLoginScreen] WebView
 * pattern and persists the cookie to DataStore.
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.foundation.layout.WindowInsets
import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.datastore.preferences.core.edit
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.DeezerAccountNameKey
import moe.rukamori.archivetune.constants.DeezerAccountPremiumKey
import moe.rukamori.archivetune.constants.DeezerArlKey
import moe.rukamori.archivetune.constants.DeezerEnabledKey
import moe.rukamori.archivetune.deezer.DeezerAudioProvider
import moe.rukamori.archivetune.ui.component.AuthWebViewScreen
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.releaseAuthWebView
import moe.rukamori.archivetune.utils.resetAuthWebViewSession
import java.util.concurrent.atomic.AtomicBoolean
import android.webkit.HttpAuthHandler
import moe.rukamori.archivetune.utils.AppProxy

const val DEEZER_LOGIN_ROUTE = "settings/deezer/login"

private const val LOGIN_URL = "https://www.deezer.com/login"

/** Cookies are read for this origin; the `arl` cookie is scoped to `.deezer.com`. */
private const val COOKIE_ORIGIN = "https://www.deezer.com"

// The cookie can land after the page that carries it has finished loading, and nothing follows until
// the user navigates again. Same bounded retry as the YouTube screen's extraction.
private const val ARL_RETRY_DELAY_MS = 1_000L
private const val ARL_EXTRACTION_ATTEMPTS = 10

/**
 * Pulls `arl` out of the cookie jar. Read through [CookieManager] rather than `document.cookie`
 * because the cookie is HttpOnly and therefore invisible to JavaScript.
 *
 * No `CookieManager.flush()` first: per the platform docs flush only "ensures all cookies currently
 * accessible through the getCookie API are written to persistent storage", so it cannot reveal a
 * cookie that getCookie does not already return — and it blocks the calling thread while doing I/O,
 * which is the UI thread on every retry tick.
 */
private fun readDeezerArl(): String? =
    CookieManager
        .getInstance()
        .getCookie(COOKIE_ORIGIN)
        ?.split(';')
        ?.firstNotNullOfOrNull { part ->
            val (name, value) = part.split('=', limit = 2).takeIf { it.size == 2 } ?: return@firstNotNullOfOrNull null
            value.trim().takeIf { name.trim().equals("arl", ignoreCase = true) && it.isNotEmpty() }
        }

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DeezerLoginScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // The cookie appears while the page is still navigating, so onPageFinished can fire several more
    // times with it present. Without this guard each one would kick off its own verification.
    val handled = remember { AtomicBoolean(false) }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun finishLogin(arl: String) {
        scope.launch {
            // Verify before saving: Deezer also issues an `arl` to anonymous visitors, so its mere
            // presence does not mean anyone signed in.
            val info = withContext(Dispatchers.IO) { DeezerAudioProvider.verifyArl(arl) }
            if (info == null) {
                // Not signed in yet (or the cookie is stale) — let the user keep going rather than
                // closing the screen on them, but say so: an anonymous visitor also gets an `arl`,
                // so silence here looks identical to the screen doing nothing at all.
                handled.set(false)
                toast(context.getString(R.string.deezer_login_not_signed_in))
                return@launch
            }
            context.dataStore.edit { prefs ->
                prefs[DeezerArlKey] = arl
                prefs[DeezerAccountNameKey] = info.name
                prefs[DeezerAccountPremiumKey] = info.lossless
                // Signing in is an explicit opt-in to the source, which defaults off; leaving it off
                // would make a successful login look like it did nothing.
                prefs[DeezerEnabledKey] = true
            }
            // Push it immediately so playback works without waiting for the App-level collector.
            DeezerAudioProvider.setManualArl(arl, info.lossless)
            toast(context.getString(R.string.deezer_login_success, info.name))
            navController.navigateUp()
        }
    }

    AuthWebViewScreen(
        navController = navController,
        title = stringResource(R.string.deezer_login),
        subtitle = stringResource(R.string.auth_webview_deezer_subtitle),
        // Stops the pending retry when the sheet closes, so a dismissed Deezer sheet leaves nothing
        // posting to a dead WebView.
        onRelease = { it.releaseAuthWebView(beforeDestroy = ::stopDeezerArlExtraction) },
        factory = { ctx ->
            WebView(ctx).apply {
                webViewClient =
                    DeezerArlWebViewClient { arl ->
                        // The retry stops at the first cookie it sees, but a later navigation starts a
                        // new one; only the first attempt across both may save and navigate.
                        if (handled.compareAndSet(false, true)) {
                            finishLogin(arl)
                        }
                    }
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                }
                // Clearing cookies first means an already-signed-in browser session cannot hand back
                // a stale ARL for an account the user is trying to switch away from.
                resetAuthWebViewSession(ctx, this, clearCookies = true) {
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    loadUrl(LOGIN_URL)
                }
            }
        },
    )
}

/**
 * Watches for the `arl` cookie across the sign-in navigations.
 *
 * Checked on every navigation rather than on a single redirect URL: Deezer has no post-login redirect
 * we control, and the cookie can land on any of several pages depending on how the account signs in.
 * Both navigation callbacks are hooked, then a bounded retry runs, because the page carrying the
 * cookie can finish loading before the cookie is in the jar — and nothing follows after that unless
 * the user navigates again, which is the wait this removes.
 *
 * Mirrors LoginScreen's bounded-retry client; a shared base is not worth extracting for two callers.
 */
private class DeezerArlWebViewClient(
    private val onArl: (String) -> Unit,
) : WebViewClient() {
    override fun onReceivedHttpAuthRequest(
        view: WebView?,
        handler: HttpAuthHandler?,
        host: String?,
        realm: String?,
    ) {
        if (handler != null && AppProxy.handleWebViewProxyAuth(handler, host)) return
        super.onReceivedHttpAuthRequest(view, handler, host, realm)
    }

    private var extractionRunnable: Runnable? = null

    override fun onPageFinished(
        view: WebView,
        url: String?,
    ) {
        super.onPageFinished(view, url)
        scheduleArlExtraction(view)
    }

    override fun doUpdateVisitedHistory(
        view: WebView,
        url: String?,
        isReload: Boolean,
    ) {
        super.doUpdateVisitedHistory(view, url, isReload)
        scheduleArlExtraction(view)
    }

    fun release(view: WebView) {
        extractionRunnable?.let(view::removeCallbacks)
        extractionRunnable = null
    }

    private fun scheduleArlExtraction(view: WebView) {
        extractionRunnable?.let(view::removeCallbacks)
        extractionRunnable = null

        var remainingAttempts = ARL_EXTRACTION_ATTEMPTS
        val runnable =
            object : Runnable {
                override fun run() {
                    val arl = readDeezerArl()
                    if (arl != null) {
                        onArl(arl)
                        return
                    }

                    remainingAttempts -= 1
                    if (remainingAttempts > 0) {
                        view.postDelayed(this, ARL_RETRY_DELAY_MS)
                    }
                }
            }
        extractionRunnable = runnable
        view.post(runnable)
    }
}

/** Cancels the extraction runnable the Deezer client posted, before the WebView is destroyed. */
private fun stopDeezerArlExtraction(view: WebView) {
    (view.webViewClient as? DeezerArlWebViewClient)?.release(view)
}
