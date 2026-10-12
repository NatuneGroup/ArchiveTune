/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * WebView-based Qobuz sign-in. See docs/qobuz-login.md for why the app_secret has to be found by
 * trial rather than by matching.
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.screens.settings

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.datastore.preferences.core.edit
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.QobuzEnabledKey
import moe.rukamori.archivetune.constants.QobuzTokensKey
import moe.rukamori.archivetune.qobuz.QobuzAudioProvider
import moe.rukamori.archivetune.qobuz.QobuzBundleSecrets
import moe.rukamori.archivetune.qobuz.QobuzToken
import moe.rukamori.archivetune.tidal.TidalAudioProvider
import moe.rukamori.archivetune.ui.component.AuthWebViewScreen
import moe.rukamori.archivetune.ui.component.TextFieldDialog
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.releaseAuthWebView
import moe.rukamori.archivetune.utils.resetAuthWebViewSession
import java.util.concurrent.atomic.AtomicBoolean
import android.webkit.HttpAuthHandler
import moe.rukamori.archivetune.utils.AppProxy

const val QOBUZ_LOGIN_ROUTE = "settings/qobuz/login"

private const val QOBUZ_WEB_PLAYER_URL = "https://play.qobuz.com/login"

private val AppSecret = Regex("^[a-f0-9]{32}$")

/**
 * Hooks fetch()/XHR to capture the `X-User-Auth-Token` and `X-App-Id` headers the web player sends,
 * then re-fetches the bundle scripts to collect app_secret candidates.
 *
 * It reports every candidate rather than picking one, because the bundle is full of 32-character hex
 * strings that look exactly like the secret. `keyed:` marks one found next to the app id, which is
 * worth trying first; `legacy:` carries an older bundle's split secret for the app to reassemble.
 *
 * Evaluated through evaluateJavascript(): a "javascript:" URL is a document-load API, and this scan
 * re-fetches every bundle script, so attaching it later than the document finishing costs that wait.
 */
private val QOBUZ_HOOK_JS =
    """
    (function(){
      if(window.__atQobuzHook)return;window.__atQobuzHook=1;
      var tok=null,app=null;
      function pushCreds(){try{if(tok&&app){QobuzAuth.onCredentials(tok,app);}}catch(e){}}
      function scanHeaders(h){try{if(!h)return;
        var t=h.get?h.get('X-User-Auth-Token'):(h['X-User-Auth-Token']||h['x-user-auth-token']);
        var a=h.get?h.get('X-App-Id'):(h['X-App-Id']||h['x-app-id']);
        if(t&&t.length>20){tok=t;} if(a&&a.length>3){app=a;} pushCreds();
      }catch(e){}}
      try{var of=window.fetch;if(of){window.fetch=function(){try{var a=arguments[1];if(a&&a.headers){scanHeaders(a.headers);}}catch(e){}return of.apply(this,arguments);};}}catch(e){}
      try{var os=XMLHttpRequest.prototype.setRequestHeader;XMLHttpRequest.prototype.setRequestHeader=function(k,v){try{var kk=String(k).toLowerCase();if(kk==='x-user-auth-token'&&v&&v.length>20){tok=v;}if(kk==='x-app-id'&&v){app=v;}pushCreds();}catch(e){}return os.apply(this,arguments);};}catch(e){}
      function collect(js){
        var out=[],seen={};
        var keyed=/app_?[sS]ecret"?\s*[:=]\s*"([a-f0-9]{32})"/g,m;
        while((m=keyed.exec(js))!==null){if(!seen[m[1]]){seen[m[1]]=1;out.push('keyed:'+m[1]);}}
        var legacy=/[a-z]\.initialSeed\("([\w=]+)",\s*window\.utimezone\.[a-z]+\)/g;
        var frag=/name:"[^"]*\/[A-Za-z_]+",info:"([\w=]+)",extras:"([\w=]+)"/g;
        var seeds=[],frags=[];
        while((m=legacy.exec(js))!==null){seeds.push(m[1]);}
        while((m=frag.exec(js))!==null){frags.push([m[1],m[2]]);}
        for(var i=0;i<seeds.length;i++){for(var j=0;j<frags.length;j++){out.push('legacy:'+seeds[i]+':'+frags[j][0]+':'+frags[j][1]);}}
        var bare=/[^a-fA-F0-9]([a-f0-9]{32})[^a-fA-F0-9]/g;
        while((m=bare.exec(js))!==null){if(!seen[m[1]]){seen[m[1]]=1;out.push('hex:'+m[1]);}}
        if(out.length){try{QobuzAuth.onSecretCandidates(out.join('\n'));}catch(e){}}
      }
      try{
        var scripts=document.querySelectorAll('script[src]');
        for(var i=0;i<scripts.length;i++){
          (function(src){fetch(src).then(function(r){return r.text();}).then(collect).catch(function(){});})(scripts[i].src);
        }
      }catch(e){}
    })()
    """.trimIndent()

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun QobuzLoginScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Held so the secret search can start as soon as either half arrives, in whichever order.
    var captured by remember { mutableStateOf<Pair<String, String>?>(null) }
    var candidates by remember { mutableStateOf<List<String>>(emptyList()) }
    var showSecretDialog by remember { mutableStateOf(false) }

    // The search is network-bound and can outlive several bundle callbacks, so only one runs at a
    // time, and a candidate the API has already rejected is never paid for twice.
    val searching = remember { AtomicBoolean(false) }
    val rejected = remember { mutableSetOf<String>() }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    suspend fun save(token: QobuzToken) {
        context.dataStore.edit { prefs ->
            val existing = QobuzToken.listFromJson(prefs[QobuzTokensKey])
            prefs[QobuzTokensKey] =
                QobuzToken.listToJson(existing.filterNot { it.token == token.token } + token)
            // Signing in is an explicit opt-in to a source that defaults off; leaving it off would
            // make a successful login look like it did nothing.
            prefs[QobuzEnabledKey] = true
        }
        toast(context.getString(R.string.qobuz_login_success))
        navController.navigateUp()
    }

    /**
     * Tries each candidate against the API and keeps the first that can sign a stream request.
     *
     * Matching cannot do this job: the secret is indistinguishable from a chunk hash on sight, and
     * saving the wrong one yields a session that fails on the first play. Only the API can tell
     * them apart.
     */
    fun searchForSecret(
        token: String,
        appId: String,
        pool: List<String>,
    ) {
        val untried = pool.filterNot(rejected::contains)
        if (untried.isEmpty() || !searching.compareAndSet(false, true)) return
        scope.launch {
            val verified =
                withContext(Dispatchers.IO) {
                    untried.firstNotNullOfOrNull { candidate ->
                        val attempt =
                            QobuzToken(token = token, appId = appId, appSecret = candidate, label = LABEL)
                        val healthy =
                            QobuzAudioProvider.verifyToken(attempt, null, 0) !=
                                TidalAudioProvider.InstanceHealth.UNREACHABLE
                        if (healthy) attempt else null.also { rejected += candidate }
                    }
                }
            searching.set(false)
            if (verified != null) {
                save(verified)
            } else {
                // Every candidate was rejected, so the bundle changed shape or this token cannot
                // sign. Ask rather than leaving the screen looking idle.
                toast(context.getString(R.string.qobuz_app_secret_not_found))
                showSecretDialog = true
            }
        }
    }

    fun onCredentials(
        token: String,
        appId: String,
    ) {
        captured = token to appId
        searchForSecret(token, appId, candidates)
    }

    fun onCandidates(found: List<String>) {
        candidates = (candidates + found).distinct()
        captured?.let { (token, appId) -> searchForSecret(token, appId, candidates) }
    }

    if (showSecretDialog) {
        captured?.let { (token, appId) ->
            TextFieldDialog(
                icon = { Icon(painterResource(R.drawable.token), null) },
                title = { Text(stringResource(R.string.qobuz_app_secret_title)) },
                placeholder = { Text(stringResource(R.string.qobuz_app_secret_hint)) },
                isInputValid = { AppSecret.matches(it.trim()) },
                onDone = { secret ->
                    showSecretDialog = false
                    searchForSecret(token, appId, listOf(secret.trim()))
                },
                onDismiss = { showSecretDialog = false },
            )
        }
    }

    AuthWebViewScreen(
        navController = navController,
        title = stringResource(R.string.qobuz_login),
        subtitle = stringResource(R.string.auth_webview_qobuz_subtitle),
        // A leaked WebView keeps its in-flight bundle re-fetches alive for the rest of the process,
        // which is part of why the second visit felt slower than the first.
        onRelease = { it.releaseAuthWebView("QobuzAuth") },
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

                        // onPageFinished and nothing earlier, unlike the YouTube screen's extra
                        // doUpdateVisitedHistory hook: the scan enumerates script[src] once and then
                        // latches window.__atQobuzHook, so injecting at commit time would enumerate a
                        // half-built document and lock out the complete list — fewer secret
                        // candidates, and a login that fails with nothing to show for it.
                        override fun onPageFinished(
                            view: WebView,
                            url: String?,
                        ) {
                            if (url?.contains("qobuz.com", ignoreCase = true) == true) {
                                view.evaluateJavascript(QOBUZ_HOOK_JS, null)
                            }
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
                        fun onCredentials(
                            token: String?,
                            appId: String?,
                        ) {
                            if (token.isNullOrBlank() || appId.isNullOrBlank()) return
                            if (token.length <= 20 || appId.length <= 3) return
                            scope.launch { onCredentials(token, appId) }
                        }

                        @JavascriptInterface
                        fun onSecretCandidates(payload: String?) {
                            val found = QobuzBundleSecrets.candidates(payload.orEmpty())
                            if (found.isEmpty()) return
                            scope.launch { onCandidates(found) }
                        }
                    },
                    "QobuzAuth",
                )
                resetAuthWebViewSession(ctx, this, clearCookies = true) {
                    loadUrl(QOBUZ_WEB_PLAYER_URL)
                }
            }
        },
    )
}

private const val LABEL = "Web login"
