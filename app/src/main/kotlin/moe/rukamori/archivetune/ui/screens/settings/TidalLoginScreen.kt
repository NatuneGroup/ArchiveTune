/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * WebView-based Tidal sign-in. Primary path is the official PKCE authorization-code flow
 * (durable refresh token, can unlock HiRes); if that fails it falls back to capturing the live
 * Bearer token that the Tidal web player (listen.tidal.com) sends to the API. Mirrors the
 * YouTube [LoginScreen] WebView pattern but persists the Tidal session directly to DataStore.
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.foundation.layout.WindowInsets
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.JavascriptInterface
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
import moe.rukamori.archivetune.constants.TidalAccessTokenKey
import moe.rukamori.archivetune.constants.TidalAccountNameKey
import moe.rukamori.archivetune.constants.TidalAuthFlowKey
import moe.rukamori.archivetune.constants.TidalCountryCodeKey
import moe.rukamori.archivetune.constants.TidalNeedsReloginKey
import moe.rukamori.archivetune.constants.TidalRefreshTokenKey
import moe.rukamori.archivetune.constants.TidalSubscriptionKey
import moe.rukamori.archivetune.constants.TidalSubscriptionStatus
import moe.rukamori.archivetune.constants.TidalTokenExpiryKey
import moe.rukamori.archivetune.constants.TidalUserIdKey
import moe.rukamori.archivetune.tidal.TidalAccountManager
import moe.rukamori.archivetune.ui.component.AuthWebViewScreen
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.releaseAuthWebView
import moe.rukamori.archivetune.utils.resetAuthWebViewSession
import java.util.concurrent.atomic.AtomicBoolean
import android.webkit.HttpAuthHandler
import moe.rukamori.archivetune.utils.AppProxy

const val TIDAL_LOGIN_ROUTE = "settings/tidal/login"

private const val WEB_PLAYER_URL = "https://listen.tidal.com"

// Injected into the web player to forward the live "Authorization: Bearer <token>" header (used on
// requests to the Tidal API) back to the app. Hooks both fetch() and XMLHttpRequest, once.
// Evaluated through evaluateJavascript(): a "javascript:" URL is a document-load API, so the hook attaches late.
private val BEARER_HOOK_JS =
    """
    (function(){
      if(window.__atTidalHook)return;window.__atTidalHook=1;
      function send(h){try{if(!h)return;var m=/Bearer\s+([A-Za-z0-9._\-]+)/i.exec(h);if(m&&m[1]&&m[1].length>20){TidalAuth.onBearer(m[1]);}}catch(e){}}
      try{
        var of=window.fetch;
        if(of){window.fetch=function(){try{var a=arguments[1];if(a&&a.headers){var hh=a.headers;var v=hh.get?hh.get('Authorization'):(hh['Authorization']||hh['authorization']);send(v);}}catch(e){}return of.apply(this,arguments);};}
      }catch(e){}
      try{
        var os=XMLHttpRequest.prototype.setRequestHeader;
        XMLHttpRequest.prototype.setRequestHeader=function(k,v){try{if(k&&String(k).toLowerCase()==='authorization'){send(v);}}catch(e){}return os.apply(this,arguments);};
      }catch(e){}
    })()
    """.trimIndent()

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TidalLoginScreen(navController: NavController) {
    val context = LocalContext.current
    // Scope for the async token exchanges; cancelled automatically when the screen leaves composition.
    val scope = rememberCoroutineScope()
    val pkce = remember { TidalAccountManager.buildPkceChallenge() }
    // Guards against handling the redirect / captured token more than once.
    val handled = remember { AtomicBoolean(false) }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    // Persists a successful session, resolves subscription tier, then closes the screen.
    fun finishLogin(
        token: TidalAccountManager.TokenResult,
        flow: String,
    ) {
        scope.launch {
            val sub =
                token.userId?.let { uid ->
                    withContext(Dispatchers.IO) {
                        runCatching { TidalAccountManager.fetchSubscription(token.accessToken, uid) }
                            .getOrDefault(TidalAccountManager.Subscription.UNKNOWN)
                    }
                } ?: TidalAccountManager.Subscription.UNKNOWN
            val status =
                when (sub) {
                    TidalAccountManager.Subscription.PREMIUM -> TidalSubscriptionStatus.PREMIUM
                    TidalAccountManager.Subscription.FREE -> TidalSubscriptionStatus.FREE
                    TidalAccountManager.Subscription.UNKNOWN -> TidalSubscriptionStatus.UNKNOWN
                }
            context.dataStore.edit { prefs ->
                prefs[TidalAccessTokenKey] = token.accessToken
                if (token.refreshToken != null) {
                    prefs[TidalRefreshTokenKey] = token.refreshToken
                } else {
                    prefs.remove(TidalRefreshTokenKey)
                }
                prefs[TidalTokenExpiryKey] = token.expiresAtMillis
                prefs[TidalAccountNameKey] = token.username ?: "Tidal"
                token.userId?.let { prefs[TidalUserIdKey] = it }
                token.countryCode?.let { prefs[TidalCountryCodeKey] = it }
                prefs[TidalAuthFlowKey] = flow
                prefs[TidalSubscriptionKey] = status.name
                prefs[TidalNeedsReloginKey] = false
            }
            toast(context.getString(R.string.tidal_login_success))
            if (status == TidalSubscriptionStatus.FREE) {
                toast(context.getString(R.string.tidal_account_free_warning))
            }
            navController.navigateUp()
        }
    }

    // Switches the WebView to the web-player capture fallback when PKCE cannot complete.
    fun switchToCapture(view: WebView) {
        toast(context.getString(R.string.tidal_login_webplayer_fallback))
        view.loadUrl(WEB_PLAYER_URL)
    }

    // The web player is a single-page app: signing in can be a route push rather than a document
    // load, and neither shouldOverrideUrlLoading nor onPageFinished sees every one of those. Hooking
    // doUpdateVisitedHistory as well is what the YouTube screen does; the script's own
    // window.__atTidalHook guard makes a repeat injection free, and injecting early only installs the
    // fetch/XHR wrappers sooner, which is what we want.
    fun injectBearerHook(
        view: WebView,
        url: String?,
    ) {
        if (url?.contains("tidal.com", ignoreCase = true) != true) return
        if (!url.contains("listen", ignoreCase = true)) return
        view.evaluateJavascript(BEARER_HOOK_JS, null)
    }

    // Handles the PKCE redirect. Returns true if the URL was the redirect and was consumed.
    fun handleRedirect(
        view: WebView,
        url: String?,
    ): Boolean {
        if (url == null || !url.startsWith(TidalAccountManager.PKCE_REDIRECT_URI)) return false
        if (!handled.compareAndSet(false, true)) return true
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val code = uri?.getQueryParameter("code")
        val error = uri?.getQueryParameter("error")
        if (code.isNullOrBlank()) {
            // No code (user cancelled or Tidal returned an error) → fall back to capture.
            handled.set(false)
            android.util.Log.w("TidalLogin", "PKCE redirect without code (error=$error)")
            switchToCapture(view)
            return true
        }
        scope.launch {
            val token =
                withContext(Dispatchers.IO) {
                    TidalAccountManager.exchangePkceCode(code, pkce.verifier, pkce.uniqueKey)
                }
            if (token != null) {
                finishLogin(token, TidalAccountManager.FLOW_PKCE)
            } else {
                // Exchange failed → try the web-player Bearer capture instead of failing outright.
                handled.set(false)
                switchToCapture(view)
            }
        }
        return true
    }

    AuthWebViewScreen(
        navController = navController,
        title = stringResource(R.string.tidal_login),
        subtitle = stringResource(R.string.auth_webview_tidal_subtitle),
        onRelease = { it.releaseAuthWebView("TidalAuth") },
        factory = { ctx ->
            WebView(ctx).apply {
                webViewClient =
                    object : WebViewClient() {
                        override fun onReceivedHttpAuthRequest(
                            view: WebView?,
                            handler: HttpAuthHandler?,
                            host: String?,
                            realm: String?,
                        ) {
                            if (handler != null && AppProxy.handleWebViewProxyAuth(handler, host)) return
                            super.onReceivedHttpAuthRequest(view, handler, host, realm)
                        }

                        override fun onPageStarted(
                            view: WebView,
                            url: String?,
                            favicon: Bitmap?,
                        ) {
                            handleRedirect(view, url)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            url: String?,
                        ): Boolean = handleRedirect(view, url)

                        override fun onPageFinished(
                            view: WebView,
                            url: String?,
                        ) {
                            // Only the web-player fallback needs the header hook injected.
                            injectBearerHook(view, url)
                        }

                        override fun doUpdateVisitedHistory(
                            view: WebView,
                            url: String?,
                            isReload: Boolean,
                        ) {
                            super.doUpdateVisitedHistory(view, url, isReload)
                            injectBearerHook(view, url)
                        }
                    }
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                }
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun onBearer(bearer: String?) {
                            if (bearer.isNullOrBlank()) return
                            if (!handled.compareAndSet(false, true)) return
                            scope.launch {
                                val token =
                                    withContext(Dispatchers.IO) {
                                        TidalAccountManager.buildSessionFromBearer(bearer)
                                    }
                                if (token != null) {
                                    finishLogin(token, TidalAccountManager.FLOW_WEBCAPTURE)
                                } else {
                                    // The player sends a Bearer before anyone signs in, so this is
                                    // the ordinary case on first load rather than an error worth a
                                    // toast; reopen the gate and wait for the signed-in one.
                                    handled.set(false)
                                }
                            }
                        }
                    },
                    "TidalAuth",
                )
                resetAuthWebViewSession(ctx, this, clearCookies = true) {
                    loadUrl(pkce.authUrl)
                }
            }
        },
    )
}
