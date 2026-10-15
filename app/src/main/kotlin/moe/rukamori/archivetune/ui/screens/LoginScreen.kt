/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.serialization.json.*
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.auth.YouTubeLoginNavigationPolicy
import moe.rukamori.archivetune.auth.YouTubeLoginReturnGuard
import moe.rukamori.archivetune.auth.YouTubeLoginWebEvents
import moe.rukamori.archivetune.auth.YouTubeLoginDocumentCallbacks
import moe.rukamori.archivetune.auth.mergePreferredYouTubeCookies
import moe.rukamori.archivetune.innertube.utils.hasYouTubeLoginCookie
import moe.rukamori.archivetune.ui.component.AuthWebViewScreen
import moe.rukamori.archivetune.utils.AppProxy
import moe.rukamori.archivetune.viewmodels.LoginError
import moe.rukamori.archivetune.viewmodels.LoginScreenState
import moe.rukamori.archivetune.viewmodels.LoginViewModel

const val LOGIN_ROUTE = "login"
const val LOGIN_URL_ARGUMENT = "url"

fun buildLoginRoute(startUrl: String? = null): String {
    val resolvedUrl = startUrl?.trim().takeUnless { it.isNullOrBlank() } ?: return LOGIN_ROUTE
    return "$LOGIN_ROUTE?$LOGIN_URL_ARGUMENT=${Uri.encode(resolvedUrl)}"
}

private const val DEFAULT_LOGIN_URL = "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"
private const val LOGIN_CONTEXT_RETRY_DELAY_MS = 1_000L
private const val LOGIN_CONTEXT_EXTRACTION_ATTEMPTS = 10
private const val LOGIN_CONTEXT_SCRIPT =
    "(function(){try{var c=window.ytcfg;var y=window.yt&&window.yt.config_;var s=document.querySelectorAll('script');var v=c&&c.get&&c.get('VISITOR_DATA')||y&&y.VISITOR_DATA;var d=c&&c.get&&c.get('DATASYNC_ID')||y&&y.DATASYNC_ID;for(var i=0;i<s.length&&(!v||!d);i++){var x=s[i].textContent;if(!v){var vm=x.match(/[\"']VISITOR_DATA[\"']\\s*:\\s*[\"']([^\"']+)[\"']/);if(vm)v=vm[1]}if(!d){var dm=x.match(/[\"'](?:DATASYNC_ID|dataSyncId)[\"']\\s*:\\s*[\"']([^\"']+)[\"']/);if(dm)d=dm[1]}}return JSON.stringify({visitorData:v||null,dataSyncId:d||null})}catch(e){return null}})();"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(
    navController: NavController,
    startUrl: String? = null,
    onLoginComplete: (() -> Unit)? = null,
    onNavigateBack: (() -> Unit)? = null,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val screenState by viewModel.screenState.collectAsStateWithLifecycle()
    val webRevision by viewModel.webRevision.collectAsStateWithLifecycle()
    val successMessage = stringResource(R.string.login_success)
    val cancel: () -> Unit = {
        viewModel.release()
        onNavigateBack?.invoke() ?: run { navController.navigateUp() }
    }
    DisposableEffect(viewModel) {
        viewModel.open()
        onDispose { viewModel.release() }
    }
    LaunchedEffect(screenState) {
        if (screenState is LoginScreenState.Success && viewModel.consumeSuccess()) {
            Toast.makeText(context, successMessage, Toast.LENGTH_SHORT).show()
            onLoginComplete?.invoke() ?: run { navController.navigateUp() }
        }
    }
    key(webRevision) {
        AuthWebViewScreen(
            navController = navController,
            title = stringResource(R.string.login),
            subtitle = stringResource(R.string.auth_webview_youtube_subtitle),
            onDismiss = cancel,
            navigateBackInWebView = false,
            statusContent = {
                if (screenState == LoginScreenState.Loading) {
                    LinearProgressIndicator()
                    Text(stringResource(R.string.youtube_login_working))
                }
                (screenState as? LoginScreenState.Error)?.let {
                    Text(stringResource(it.error.messageResource()), color = MaterialTheme.colorScheme.error)
                }
                Row {
                    if (screenState is LoginScreenState.Error) {
                        TextButton(onClick = viewModel::retry) { Text(stringResource(R.string.retry)) }
                    }
                    TextButton(onClick = cancel) { Text(stringResource(R.string.cancel_button)) }
                }
            },
            onRelease = { view ->
                (view.tag as? YouTubeLoginWebViewClient)?.destroy(view)
            },
            factory = { webContext ->
                WebView(webContext).apply {
                    val owner = YouTubeLoginWebViewClient(viewModel, webRevision)
                    webViewClient = owner
                    tag = owner
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        setSupportZoom(true)
                        builtInZoomControls = true
                        displayZoomControls = false
                    }
                    // Keep this jar, including through the official return and explicit Retry.
                    val loginView = this
                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        setAcceptThirdPartyCookies(loginView, true)
                    }
                    owner.loadOwned(this, startUrl?.takeIf(String::isNotBlank) ?: DEFAULT_LOGIN_URL)
                }
            },
        )
    }
}

private fun LoginError.messageResource(): Int = when (this) {
    LoginError.MissingDataSyncId -> R.string.youtube_login_missing_context
    LoginError.LoginFailed -> R.string.youtube_login_validation_failed
    LoginError.Network -> R.string.youtube_login_network_error
    LoginError.Http -> R.string.youtube_login_http_error
    LoginError.UnsupportedNavigation -> R.string.youtube_login_unsupported_navigation
    LoginError.RedirectLoop -> R.string.youtube_login_redirect_loop
    LoginError.RendererLost -> R.string.youtube_login_renderer_lost
}

private class YouTubeLoginWebViewClient(
    private val viewModel: LoginViewModel,
    private val viewRevision: Long,
) : WebViewClient() {
    private val returns = YouTubeLoginReturnGuard()
    private var extraction: Runnable? = null
    private var returnLoad: Runnable? = null
    private var released = false
    private var destroyed = false
    private val callbacks = YouTubeLoginDocumentCallbacks()
    private var trustedSource = true
    private fun owns(): Boolean = !released && viewModel.ownsView(viewRevision)

    fun loadOwned(view: WebView, url: String) {
        if (!owns()) return
        if (!intercept(view, url, mainFrame = true, method = "GET")) view.loadUrl(url)
    }

    private fun intercept(view: WebView, url: String, mainFrame: Boolean, method: String): Boolean {
        if (!owns()) return true
        return when (YouTubeLoginNavigationPolicy.decide(url, mainFrame, method, trustedSource)) {
            YouTubeLoginNavigationPolicy.Decision.Allow -> false
            YouTubeLoginNavigationPolicy.Decision.Block -> {
                if (mainFrame) fail(view, LoginError.UnsupportedNavigation)
                true
            }
            YouTubeLoginNavigationPolicy.Decision.ReturnToMusic -> {
                // Both overloads/history callbacks may describe the same pending navigation.
                if (returns.isPending()) return true
                if (!returns.requestReturn()) { fail(view, LoginError.RedirectLoop); return true }
                stopExtraction(view)
                val task = Runnable {
                    if (owns() && returns.consumePending()) loadOwned(view, YouTubeLoginNavigationPolicy.MUSIC_HOME)
                }
                returnLoad = task
                view.post(task)
                true
            }
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        intercept(view, request.url.toString(), request.isForMainFrame, request.method)

    @Deprecated("Legacy WebView callback lacks frame/method ownership")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
        // This overload cannot prove frame or method. Consume non-web/install targets but never
        // turn a possible subframe into a top-level GET; supported Android uses the request overload.
        return intercept(view, url, mainFrame = false, method = "UNKNOWN")
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        if (!owns()) return
        callbacks.pageStarted()
        stopExtraction(view)
        // A provider may report an unsupported intent as a started page before reporting its
        // error. Keep the preceding document's trust for that narrow recovery path.
        if (url?.startsWith("https://") == true || url?.startsWith("http://") == true) {
            trustedSource = YouTubeLoginNavigationPolicy.trustedLoginPage(url)
        }
        viewModel.onPageLoading(viewRevision)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        if (!owns() || url != view.url) return
        if (url != null && YouTubeLoginNavigationPolicy.isMusicInstall(url)) {
            // No method information here: do not replay a possible POST as a GET.
            fail(view, LoginError.UnsupportedNavigation)
            return
        }
        viewModel.onPageReady(viewRevision)
        scheduleExtraction(view, url)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        if (owns() && url == view.url) scheduleExtraction(view, url)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (!owns() || !request.isForMainFrame) return
        if (error.errorCode == ERROR_UNSUPPORTED_SCHEME && YouTubeLoginNavigationPolicy.isMusicInstall(request.url.toString()) &&
            request.method == "GET") {
            intercept(view, request.url.toString(), true, request.method)
        } else YouTubeLoginWebEvents.networkError(request.isForMainFrame, error.errorCode == ERROR_UNSUPPORTED_SCHEME)?.let { fail(view, it) }
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
        if (owns()) YouTubeLoginWebEvents.httpError(request.isForMainFrame, errorResponse.statusCode)?.let { fail(view, it) }
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        if (owns()) viewModel.onError(viewRevision, LoginError.RendererLost)
        destroy(view)
        return true
    }

    override fun onReceivedHttpAuthRequest(view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?) {
        if (handler != null && AppProxy.handleWebViewProxyAuth(handler, host)) return
        super.onReceivedHttpAuthRequest(view, handler, host, realm)
    }

    fun destroy(view: WebView) {
        if (destroyed) return
        released = true
        callbacks.release()
        returns.release()
        stopExtraction(view)
        returnLoad?.let(view::removeCallbacks)
        returnLoad = null
        view.stopLoading()
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        view.destroy()
        destroyed = true
    }

    private fun fail(view: WebView, error: LoginError) {
        stopExtraction(view)
        returnLoad?.let(view::removeCallbacks)
        returns.release()
        view.stopLoading()
        viewModel.onError(viewRevision, error)
    }

    private fun stopExtraction(view: WebView) {
        callbacks.invalidateEvaluation()
        extraction?.let(view::removeCallbacks)
        extraction = null
    }

    private fun scheduleExtraction(view: WebView, url: String?) {
        if (extraction != null || !owns() || YouTubeLoginNavigationPolicy.trustedYouTubeOrigin(url) == null) return
        val page = callbacks.document
        var attempts = LOGIN_CONTEXT_EXTRACTION_ATTEMPTS
        val task = object : Runnable {
            override fun run() {
                if (!owns() || page != callbacks.document || view.url != url) return
                if (attempts-- <= 0) {
                    extraction = null
                    viewModel.onExtractionExhausted(viewRevision)
                    return
                }
                val evaluationId = callbacks.nextEvaluation()
                view.evaluateJavascript(LOGIN_CONTEXT_SCRIPT) { raw ->
                    if (!owns() || !callbacks.accepts(page, evaluationId) || view.url != url) return@evaluateJavascript
                    val payload = runCatching {
                        val text = (Json.parseToJsonElement(raw) as? JsonPrimitive)?.contentOrNull ?: return@runCatching null
                        Json.parseToJsonElement(text) as? JsonObject
                    }.getOrNull()
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.flush()
                    val origins = linkedSetOf<String>()
                    YouTubeLoginNavigationPolicy.trustedYouTubeOrigin(url)?.let(origins::add)
                    origins.addAll(listOf("https://music.youtube.com", "https://www.youtube.com", "https://youtube.com"))
                    val cookies = mergePreferredYouTubeCookies(origins.map(cookieManager::getCookie))
                    if (hasYouTubeLoginCookie(cookies)) {
                        viewModel.onContextExtracted(viewRevision, cookies.orEmpty(),
                            (payload?.get("visitorData") as? JsonPrimitive)?.contentOrNull,
                            (payload?.get("dataSyncId") as? JsonPrimitive)?.contentOrNull)
                    }
                }
                // The budget belongs to the view, not to completion of a possibly lost JS callback.
                if (owns()) view.postDelayed(this, LOGIN_CONTEXT_RETRY_DELAY_MS)
            }
        }
        extraction = task
        view.post(task)
    }
}
