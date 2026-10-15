/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.auth

import java.net.URI
import java.net.URLDecoder

/** Login-only policy. No supplied destination or intent extra is ever loaded as a fallback. */
internal object YouTubeLoginNavigationPolicy {
    const val MUSIC_HOME = "https://music.youtube.com/"
    private const val MUSIC_PACKAGE = "com.google.android.apps.youtube.music"
    private val youtubeHosts = setOf("music.youtube.com", "www.youtube.com", "youtube.com")

    enum class Decision { Allow, ReturnToMusic, Block }

    fun trustedYouTubeOrigin(url: String?): String? {
        val uri = parse(url) ?: return null
        return if (officialHttps(uri) && uri.host in youtubeHosts) "https://${uri.host}" else null
    }

    fun trustedLoginPage(url: String?): Boolean {
        val uri = parse(url) ?: return false
        return officialHttps(uri) && (uri.host in youtubeHosts || uri.host == "accounts.google.com")
    }

    fun decide(url: String, mainFrame: Boolean, method: String, trustedSource: Boolean): Decision {
        val uri = parse(url) ?: return Decision.Block
        if (isMusicInstall(url)) {
            return if (mainFrame && method == "GET" && trustedSource) Decision.ReturnToMusic else Decision.Block
        }
        // Google consent/challenge requests, including POST, retain their original navigation.
        return if (uri.scheme == "https" || uri.scheme == "http") Decision.Allow else Decision.Block
    }

    fun isMusicInstall(url: String): Boolean {
        val uri = parse(url) ?: return false
        return when (uri.scheme) {
            "https" -> officialStore(uri)
            "market" -> cleanAuthority(uri, "details") && uri.path.orEmpty().isEmpty() && musicProduct(uri)
            "intent" -> musicIntent(url)
            else -> false
        }
    }

    private fun musicIntent(url: String): Boolean {
        val marker = "#Intent;"
        if (url.indexOf(marker) != url.lastIndexOf(marker) || !url.endsWith(";end")) return false
        val parts = url.substringAfter(marker, "").removeSuffix(";end").split(';')
        val extras = linkedMapOf<String, String>()
        for (part in parts) {
            val key = part.substringBefore('=')
            val value = part.substringAfter('=', "")
            if (key !in setOf("scheme", "package", "S.browser_fallback_url", "action", "category") ||
                value.isEmpty() || extras.put(key, value) != null) return false
        }
        if (extras["scheme"] != "https") return false
        if (extras["action"]?.let { it != "android.intent.action.VIEW" } == true) return false
        if (extras["category"]?.let { it != "android.intent.category.BROWSABLE" } == true) return false
        val target = parse("https:" + url.substringBefore(marker).removePrefix("intent:")) ?: return false
        val store = officialStore(target)
        val music = officialHttps(target) && target.host == "music.youtube.com" && target.rawPath.orEmpty() in setOf("", "/") && target.rawQuery == null
        if (!store && !music) return false
        val pkg = extras["package"] ?: return false
        if (pkg != MUSIC_PACKAGE && !(store && pkg == "com.android.vending")) return false
        extras["S.browser_fallback_url"]?.let { encoded ->
            val fallback = parse(decode(encoded) ?: return false) ?: return false
            if (!officialStore(fallback) && !(officialHttps(fallback) && fallback.host == "music.youtube.com" &&
                    fallback.rawPath.orEmpty() in setOf("", "/") && fallback.rawQuery == null && fallback.rawFragment == null)) return false
        }
        return true
    }

    private fun officialStore(uri: URI): Boolean =
        officialHttps(uri) && uri.host == "play.google.com" && uri.rawPath == "/store/apps/details" && musicProduct(uri)

    private fun musicProduct(uri: URI): Boolean {
        if (uri.rawFragment != null) return false
        val query = uri.rawQuery ?: return false
        val values = query.split('&').filter { it.substringBefore('=') == "id" }
        return values.size == 1 && decode(values.single().substringAfter('=', "")) == MUSIC_PACKAGE
    }

    private fun cleanAuthority(uri: URI, host: String): Boolean =
        uri.host == host && uri.rawAuthority == host && uri.rawUserInfo == null && uri.port == -1

    private fun officialHttps(uri: URI): Boolean =
        uri.scheme == "https" && uri.host != null && cleanAuthority(uri, uri.host)

    private fun parse(url: String?): URI? = runCatching { URI(url ?: return null) }.getOrNull()
    private fun decode(value: String): String? = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull()
}

/** Shared by both WebView callbacks and app-owned loads; a new instance belongs to explicit Retry. */
internal class YouTubeLoginReturnGuard {
    private var returned = false
    private var pending = false
    private var released = false

    fun requestReturn(): Boolean {
        if (released || returned || pending) return false
        pending = true
        return true
    }

    fun consumePending(): Boolean {
        if (released || !pending) return false
        pending = false
        returned = true
        return true
    }

    fun isPending(): Boolean = pending && !released
    fun release() { released = true; pending = false }
}

internal fun mergePreferredYouTubeCookies(snapshots: List<String?>): String? {
    val values = linkedMapOf<String, String>()
    snapshots.forEach { snapshot ->
        snapshot?.split(';')?.forEach { part ->
            val separator = part.indexOf('=')
            if (separator > 0) {
                val name = part.substring(0, separator).trim()
                val value = part.substring(separator + 1).trim()
                if (name.isNotEmpty() && value.isNotBlank()) values.putIfAbsent(name, value)
            }
        }
    }
    return values.takeIf { it.isNotEmpty() }?.entries?.joinToString("; ") { "${it.key}=${it.value}" }
}
