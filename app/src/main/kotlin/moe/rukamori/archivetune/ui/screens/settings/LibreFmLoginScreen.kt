/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.ui.screens.settings

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
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
import moe.rukamori.archivetune.constants.CustomScrobbleApiKeyOverrideKey
import moe.rukamori.archivetune.constants.CustomScrobbleSecretOverrideKey
import moe.rukamori.archivetune.constants.LastFmProvider
import moe.rukamori.archivetune.constants.LastFMCustomEndpointKey
import moe.rukamori.archivetune.constants.LastFMProviderKey
import moe.rukamori.archivetune.constants.LastFMSessionKey
import moe.rukamori.archivetune.constants.LastFMUsernameKey
import moe.rukamori.archivetune.constants.LibreFMApiKeyOverrideKey
import moe.rukamori.archivetune.constants.LibreFMSecretOverrideKey
import moe.rukamori.archivetune.lastfm.LastFM
import moe.rukamori.archivetune.lastfm.LastFmAppCredentials
import moe.rukamori.archivetune.lastfm.models.Authentication
import moe.rukamori.archivetune.ui.component.AuthWebViewScreen
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.resetAuthWebViewSession
import java.util.concurrent.atomic.AtomicBoolean

const val LASTFM_LIBREFM_LOGIN_ROUTE = "settings/lastfm/librefm-login"

/**
 * WebView-based Libre.fm sign-in. Identical flow to [LastFmLoginScreen] but pointing at
 * libre.fm — Libre.fm is API-compatible with Last.fm, so the same `auth.getSession` token exchange
 * works against `https://libre.fm/2.0/`.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LibreFmLoginScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val handled = remember { AtomicBoolean(false) }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun finishLogin(auth: Authentication) {
        scope.launch {
            // Configure the runtime to talk to libre.fm — same baked-in
            // api_key + secret work because libre.fm is API-compatible and
            // accepts any api_key for read access. Scrobble / now-playing
            // writes are signed with the secret, which libre.fm also accepts.
            LastFM.configure(
                endpoint = LastFM.LIBREFM_API_ENDPOINT,
                apiKey = LastFmAppCredentials.API_KEY,
                secret = LastFmAppCredentials.API_SECRET,
                sessionKey = auth.session.key,
            )
            context.dataStore.edit { prefs ->
                prefs[LastFMProviderKey] = LastFmProvider.LIBREFM.name
                prefs[LastFMCustomEndpointKey] = ""
                // Pin the Libre.fm-scoped credential slots so the service-config
                // layer reads them back correctly on next app start (the
                // fromValues logic looks at LibreFMApiKeyOverrideKey when the
                // provider is LIBREFM).
                prefs[LibreFMApiKeyOverrideKey] = LastFmAppCredentials.API_KEY
                prefs[LibreFMSecretOverrideKey] = LastFmAppCredentials.API_SECRET
                // Also clear the Custom-scoped slots so a later switch to CUSTOM
                // doesn't accidentally reuse the libre.fm credentials.
                prefs[CustomScrobbleApiKeyOverrideKey] = ""
                prefs[CustomScrobbleSecretOverrideKey] = ""
                prefs[LastFMUsernameKey] = auth.session.name
                prefs[LastFMSessionKey] = auth.session.key
            }
            withContext(Dispatchers.Main) {
                toast(context.getString(R.string.lastfm_login_success))
                navController.navigateUp()
            }
        }
    }

    fun handleRedirect(url: String?): Boolean {
        if (url == null || !url.startsWith(LastFmAppCredentials.AUTH_CALLBACK_URI)) return false
        if (!handled.compareAndSet(false, true)) return true
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val token = uri?.getQueryParameter("token")?.trim()
        if (token.isNullOrBlank()) {
            android.util.Log.w("LibreFmLogin", "Auth callback without token: $url")
            scope.launch {
                withContext(Dispatchers.Main) {
                    toast(context.getString(R.string.lastfm_login_cancelled))
                    navController.navigateUp()
                }
            }
            return true
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                // Configure against libre.fm BEFORE calling getSession — the
                // auth.getSession signature uses the api_secret, so we need
                // the runtime endpoint + secret set to libre.fm values for
                // the call to sign + route correctly.
                LastFM.configure(
                    endpoint = LastFM.LIBREFM_API_ENDPOINT,
                    apiKey = LastFmAppCredentials.API_KEY,
                    secret = LastFmAppCredentials.API_SECRET,
                    sessionKey = null,
                )
                LastFM.getSession(token)
            }
            result
                .onSuccess { auth -> finishLogin(auth) }
                .onFailure { error ->
                    android.util.Log.e("LibreFmLogin", "auth.getSession failed", error)
                    handled.set(false)
                    withContext(Dispatchers.Main) {
                        toast(context.getString(R.string.lastfm_login_failed))
                        navController.navigateUp()
                    }
                }
        }
        return true
    }

    AuthWebViewScreen(
        navController = navController,
        title = stringResource(R.string.lastfm_connect_librefm_button),
        subtitle = stringResource(R.string.auth_webview_librefm_subtitle),
        factory = { ctx ->
            WebView(ctx).apply {
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(
                        view: WebView,
                        url: String?,
                        favicon: Bitmap?,
                    ) {
                        handleRedirect(url)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        url: String?,
                    ): Boolean = handleRedirect(url)
                }
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                }
                resetAuthWebViewSession(ctx, this, clearCookies = true) {
                    // Libre.fm's auth URL — same shape as Last.fm's but
                    // pointing at libre.fm. Note the no-`www` host and the
                    // slightly different path (no trailing slash before `?`).
                    loadUrl(libreFmAuthUrl())
                }
            }
        },
    )
}

private fun libreFmAuthUrl(): String =
    "https://libre.fm/api/auth/?api_key=${LastFmAppCredentials.API_KEY}&cb=${LastFmAppCredentials.AUTH_CALLBACK_URI}"
