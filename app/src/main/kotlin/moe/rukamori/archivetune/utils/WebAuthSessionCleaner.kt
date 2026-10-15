/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.utils

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

fun clearPlaybackWebAuthSession() {
    // Android exposes no per-domain remove API. Expire only cookie names visible to official
    // YouTube origins, at host/domain scopes; do not touch Google or other providers' storage.
    val manager = CookieManager.getInstance()
    listOf("music.youtube.com", "www.youtube.com", "youtube.com").forEach { host ->
        val origin = "https://$host"
        manager.getCookie(origin)?.split(';')?.forEach { part ->
            val name = part.substringBefore('=').trim()
            if (name.isNotBlank()) {
                val expired = "$name=; Max-Age=0; Path=/; Secure"
                manager.setCookie(origin, expired)
                manager.setCookie(origin, "$expired; Domain=$host")
                manager.setCookie(origin, "$expired; Domain=.youtube.com")
            }
        }
    }
    manager.flush()
}

suspend fun clearWebAuthSession(context: Context) {
    withContext(Dispatchers.Main.immediate) {
        clearWebAuthStorage(context)
        val cookieManager = CookieManager.getInstance()
        suspendCancellableCoroutine<Unit> { continuation ->
            cookieManager.removeSessionCookies {
                cookieManager.removeAllCookies {
                    cookieManager.flush()
                    if (continuation.isActive) {
                        continuation.resume(Unit)
                    }
                }
            }
        }
    }
}

fun resetAuthWebViewSession(
    context: Context,
    webView: WebView,
    clearCookies: Boolean = true,
    onReady: () -> Unit,
) {
    webView.stopLoading()
    webView.clearHistory()
    webView.clearFormData()
    // No clearCache(): the resource cache holds no credential the sign-in can reuse — cookies and
    // DOM storage do, and they are still cleared below. It is also per-application, so wiping it
    // here throws away every provider's scripts and images and makes the next open of a
    // bundle-heavy sign-in page (Apple's login document alone is ~1.8 MB) a full cold download.
    clearWebAuthStorage(context)

    val cookieManager = CookieManager.getInstance()
    cookieManager.setAcceptCookie(true)
    cookieManager.setAcceptThirdPartyCookies(webView, true)
    if (!clearCookies) {
        onReady()
        return
    }

    cookieManager.removeSessionCookies {
        cookieManager.removeAllCookies {
            // The sheet can be dismissed while this wipe is in flight, so these calls may land on a
            // WebView that onRelease already destroyed. WebView exposes no isDestroyed(), so a guard
            // would need screen-held state; left as LoginScreen has always shipped it.
            cookieManager.flush()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(webView, true)
            onReady()
        }
    }
}

/**
 * Tears down a sign-in WebView whose sheet has left the composition.
 *
 * A leaked WebView keeps its renderer, its connection pool and any in-flight navigation alive for
 * the rest of the process, so every visit to a login screen would leave one more of them competing
 * with the next. [beforeDestroy] runs first so a client can cancel what it posted to the view, then
 * the bridge is detached, the load stopped and the WebView destroyed.
 */
fun WebView.releaseAuthWebView(
    javascriptInterface: String? = null,
    beforeDestroy: ((WebView) -> Unit)? = null,
) {
    beforeDestroy?.invoke(this)
    if (javascriptInterface != null) {
        removeJavascriptInterface(javascriptInterface)
    }
    stopLoading()
    destroy()
}

private fun clearWebAuthStorage(context: Context) {
    val appContext = context.applicationContext
    WebStorage.getInstance().deleteAllData()
    WebViewDatabase.getInstance(appContext).apply {
        clearFormData()
        clearHttpAuthUsernamePassword()
        clearUsernamePassword()
    }
}
