/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.utils

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.HttpAuthHandler
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.telegram.TelegramClient
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import timber.log.Timber
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The user's proxy (Settings → Internet → Proxy), applied to the whole process.
 *
 * [install] puts [selector] in as the JVM default [ProxySelector] and a [java.net.Authenticator]
 * that answers for the configured proxy only. OkHttp clients built without an explicit `proxy()`,
 * Ktor's OkHttp engine, `HttpURLConnection`, Media3's default data source and Coil all consult the
 * default selector, so they follow the proxy without being touched, and SOCKS5 credentials reach
 * every socket through the authenticator.
 *
 * Two gaps the process default cannot close, which is what [withAppProxy] is for: OkHttp reads the
 * default selector once, when the client is built, so a client built before [install] keeps the
 * system selector; and OkHttp never consults `java.net.Authenticator` for an HTTP proxy's 407, so
 * an HTTP proxy with a username needs [authenticator] on each client.
 */
object AppProxy {
    data class Config(
        val type: Proxy.Type,
        val host: String,
        val port: Int,
        val username: String?,
        val password: String?,
        val bypassStreams: Boolean,
    ) {
        val proxy: Proxy get() = Proxy(type, InetSocketAddress.createUnresolved(host, port))

        val hasCredentials: Boolean get() = !username.isNullOrEmpty() && !password.isNullOrEmpty()

        fun matches(candidate: Proxy?): Boolean {
            if (candidate == null || candidate.type() != type) return false
            val address = candidate.address() as? InetSocketAddress ?: return false
            return address.port == port && address.hostString.equals(host, ignoreCase = true)
        }

        // Keeps the password out of logs and crash reports.
        override fun toString(): String = "Config(type=$type, host=$host, port=$port, auth=$hasCredentials, bypassStreams=$bypassStreams)"
    }

    @Volatile
    var config: Config? = null
        private set

    /** False until the stored preference has been read once; before that [config] is just "not known yet". */
    @Volatile
    var isLoaded: Boolean = false
        private set

    private val installed = AtomicBoolean(false)

    @Volatile
    private var systemSelector: ProxySelector? = null

    @Volatile
    private var webViewRule: String? = null

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private val PROXIED_SCHEMES = setOf("http", "https", "ws", "wss")
    private val IPV4_LITERAL = Regex("\\d{1,3}(\\.\\d{1,3}){3}")

    val selector: ProxySelector = AppProxySelector(forStreams = false)

    /** For audio and video payloads: the same as [selector] unless "Bypass proxy for streams" is on. */
    val streamSelector: ProxySelector = AppProxySelector(forStreams = true)

    val authenticator: Authenticator = authenticatorFor { config }

    fun install() {
        if (!installed.compareAndSet(false, true)) return
        val current = ProxySelector.getDefault()
        if (current !is AppProxySelector) systemSelector = current
        ProxySelector.setDefault(selector)
        java.net.Authenticator.setDefault(JavaNetProxyAuthenticator)
    }

    fun apply(newConfig: Config?) {
        val wasLoaded = isLoaded
        val previous = config
        config = newConfig
        isLoaded = true

        val proxy = newConfig?.proxy
        if (YouTube.proxy != proxy) YouTube.proxy = proxy
        val username = newConfig?.username?.takeIf { it.isNotBlank() }
        if (YouTube.proxyUsername != username) YouTube.proxyUsername = username
        val password = newConfig?.password?.takeIf { it.isNotBlank() }
        if (YouTube.proxyPassword != password) YouTube.proxyPassword = password
        YouTube.streamBypassProxy = newConfig?.bypassStreams == true

        if (wasLoaded && previous == newConfig) return
        Timber.tag(TAG).i("proxy %s", newConfig ?: "off")
        applyToWebView(newConfig)
        runCatching { TelegramClient.syncProxy() }
            .onFailure { Timber.tag(TAG).w(it, "Telegram proxy sync failed") }
    }

    /**
     * Answers an HTTP proxy's 407 for the configured proxy, including OkHttp's preemptive CONNECT
     * challenge, so tunnels open in one round trip. Never sends the credentials to any other proxy.
     */
    fun authenticatorFor(configProvider: () -> Config?): Authenticator =
        Authenticator { route, response -> proxyCredentialRequest(configProvider(), route, response) }

    private fun proxyCredentialRequest(
        cfg: Config?,
        route: Route?,
        response: Response,
    ): Request? {
        if (cfg == null || cfg.type != Proxy.Type.HTTP || !cfg.hasCredentials) return null
        if (response.code != 407 || !cfg.matches(route?.proxy)) return null
        val credential = Credentials.basic(cfg.username.orEmpty(), cfg.password.orEmpty(), Charsets.UTF_8)
        // A second 407 for the credential we already sent means it was rejected; retrying it would loop.
        if (response.request.header("Proxy-Authorization") == credential) return null
        return response.request
            .newBuilder()
            .header("Proxy-Authorization", credential)
            .build()
    }

    /**
     * Supplies the configured HTTP proxy's credentials to a WebView. Chromium has no SOCKS5
     * authentication at all, so a SOCKS proxy with a password cannot carry WebView traffic.
     */
    fun handleWebViewProxyAuth(
        handler: HttpAuthHandler,
        host: String?,
    ): Boolean {
        val cfg = config ?: return false
        if (cfg.type != Proxy.Type.HTTP || !cfg.hasCredentials) return false
        if (!host.equals(cfg.host, ignoreCase = true)) return false
        // False once the proxy has already rejected these credentials for this request.
        if (!handler.useHttpAuthUsernamePassword()) return false
        handler.proceed(cfg.username, cfg.password)
        return true
    }

    internal fun isLocalHost(rawHost: String?): Boolean {
        val host =
            rawHost
                ?.trim()
                ?.removePrefix("[")
                ?.removeSuffix("]")
                ?.lowercase()
                ?.takeIf(String::isNotEmpty)
                ?: return false
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return true
        // Only literals are checked: resolving a name here would leak the lookup the proxy exists to hide.
        if (!IPV4_LITERAL.matches(host) && !host.contains(':')) return false
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
        if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isAnyLocalAddress) {
            return true
        }
        return address is Inet6Address && (address.address[0].toInt() and 0xFE) == 0xFC
    }

    private fun systemSelect(uri: URI): List<Proxy> =
        runCatching { systemSelector?.select(uri) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(Proxy.NO_PROXY)

    private class AppProxySelector(
        private val forStreams: Boolean,
    ) : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> {
            requireNotNull(uri) { "uri == null" }
            val cfg = config
            val scheme = uri.scheme?.lowercase().orEmpty()
            if (cfg == null || (forStreams && cfg.bypassStreams) || scheme !in PROXIED_SCHEMES) {
                return systemSelect(uri)
            }
            if (isLocalHost(uri.host)) return listOf(Proxy.NO_PROXY)
            return listOf(cfg.proxy)
        }

        override fun connectFailed(
            uri: URI?,
            sa: SocketAddress?,
            ioe: IOException?,
        ) {
            // A single configured proxy has no fallback to rotate to; the system selector keeps
            // its own bookkeeping for the routes it handed out.
            if (config == null) runCatching { systemSelector?.connectFailed(uri, sa, ioe) }
        }
    }

    /**
     * Covers SOCKS5 username/password for every socket (Android's SOCKS implementation asks the
     * default authenticator) and HTTP proxy 407s for `HttpURLConnection`. Answers nothing but the
     * configured proxy, so a server's own 401 never receives the proxy password.
     */
    private object JavaNetProxyAuthenticator : java.net.Authenticator() {
        override fun getPasswordAuthentication(): PasswordAuthentication? {
            val cfg = config ?: return null
            if (!cfg.hasCredentials) return null
            val isSocks = requestingProtocol?.startsWith("SOCKS", ignoreCase = true) == true
            val wanted = if (isSocks) Proxy.Type.SOCKS else Proxy.Type.HTTP
            if (cfg.type != wanted) return null
            if (!isSocks && requestorType != java.net.Authenticator.RequestorType.PROXY) return null
            if (requestingPort != cfg.port) return null
            val hostMatches =
                requestingHost.equals(cfg.host, ignoreCase = true) ||
                    requestingSite?.hostAddress.equals(cfg.host, ignoreCase = true)
            if (!hostMatches) return null
            return PasswordAuthentication(cfg.username, cfg.password.orEmpty().toCharArray())
        }
    }

    @SuppressLint("RequiresFeature")
    private fun applyToWebView(cfg: Config?) {
        val rule =
            cfg?.let {
                val scheme = if (it.type == Proxy.Type.SOCKS) "socks5" else "http"
                val host = if (it.host.contains(':')) "[${it.host}]" else it.host
                "$scheme://$host:${it.port}"
            }
        if (rule == webViewRule) return
        // Loading the WebView provider costs a noticeable chunk of startup, so a user who never set
        // a proxy never pays it.
        if (rule == null && webViewRule == null) return
        webViewRule = rule
        mainHandler.post {
            runCatching {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                    Timber.tag(TAG).w("WebView proxy override unsupported; sign-in pages go direct")
                    return@runCatching
                }
                val controller = ProxyController.getInstance()
                val direct = Executor { it.run() }
                if (rule == null) {
                    controller.clearProxyOverride(direct) {}
                } else {
                    controller.setProxyOverride(ProxyConfig.Builder().addProxyRule(rule).build(), direct) {}
                }
            }.onFailure { Timber.tag(TAG).w(it, "WebView proxy override failed") }
        }
    }

    private const val TAG = "AppProxy"
}

/** Routes this client through the app proxy, including HTTP proxy authentication. */
fun OkHttpClient.Builder.withAppProxy(): OkHttpClient.Builder =
    proxySelector(AppProxy.selector).proxyAuthenticator(AppProxy.authenticator)

/** As [withAppProxy], but honouring "Bypass proxy for streams"; for clients that fetch audio or video. */
fun OkHttpClient.Builder.withAppStreamProxy(): OkHttpClient.Builder =
    proxySelector(AppProxy.streamSelector).proxyAuthenticator(AppProxy.authenticator)
