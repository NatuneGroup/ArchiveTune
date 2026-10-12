/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.utils

import androidx.datastore.preferences.core.Preferences
import moe.rukamori.archivetune.constants.ProxyEnabledKey
import moe.rukamori.archivetune.constants.ProxyHostKey
import moe.rukamori.archivetune.constants.ProxyPasswordKey
import moe.rukamori.archivetune.constants.ProxyPortKey
import moe.rukamori.archivetune.constants.ProxyTypeKey
import moe.rukamori.archivetune.constants.ProxyUsernameKey
import moe.rukamori.archivetune.constants.StreamBypassProxyKey
import moe.rukamori.archivetune.extensions.toEnum
import java.net.Proxy

object ProxyUtils {
    /** What the settings screen shows when the port was never edited; the stored value is then absent. */
    const val DEFAULT_PORT = 8080

    data class HostInput(
        val host: String,
        val port: Int?,
    )

    private val HOST_CHARS = Regex("[A-Za-z0-9._%:-]+")

    /**
     * Accepts what people paste from a proxy client: `127.0.0.1`, `127.0.0.1:7890`,
     * `socks5://user@proxy.example:1080/`, `[::1]:7890`. A port written into the host wins over the
     * separate port field, since it is the one the user typed last.
     */
    fun parseHostInput(raw: String?): HostInput? {
        var value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        value = value.substringAfter("://", value)
        value = value.substringBefore('/').substringBefore('?').substringBefore('#')
        value = value.substringAfterLast('@')
        val host: String
        var port: Int? = null
        when {
            value.startsWith("[") -> {
                val end = value.indexOf(']')
                if (end < 0) return null
                host = value.substring(1, end)
                val rest = value.substring(end + 1)
                if (rest.startsWith(":")) {
                    port = rest.drop(1).toIntOrNull() ?: return null
                } else if (rest.isNotEmpty()) {
                    return null
                }
            }
            value.count { it == ':' } == 1 -> {
                host = value.substringBefore(':')
                port = value.substringAfter(':').toIntOrNull() ?: return null
            }
            else -> host = value
        }
        if (host.isEmpty() || !HOST_CHARS.matches(host)) return null
        if (port != null && port !in 1..65535) return null
        return HostInput(host, port)
    }

    fun isValidHostInput(raw: String): Boolean = parseHostInput(raw) != null

    fun configOrNull(
        type: Proxy.Type,
        host: String?,
        port: Int?,
        username: String?,
        password: String?,
        bypassStreams: Boolean,
    ): AppProxy.Config? {
        val parsed = parseHostInput(host) ?: return null
        val resolvedPort = (parsed.port ?: port)?.takeIf { it in 1..65535 } ?: return null
        return AppProxy.Config(
            type = if (type == Proxy.Type.SOCKS) Proxy.Type.SOCKS else Proxy.Type.HTTP,
            host = parsed.host,
            port = resolvedPort,
            username = username?.trim()?.takeIf(String::isNotEmpty),
            password = password?.takeIf(String::isNotEmpty),
            bypassStreams = bypassStreams,
        )
    }

    fun configFromPreferences(prefs: Preferences): AppProxy.Config? {
        if (prefs[ProxyEnabledKey] != true) return null
        return configOrNull(
            type = prefs[ProxyTypeKey].toEnum(defaultValue = Proxy.Type.HTTP),
            host = prefs[ProxyHostKey],
            port = prefs[ProxyPortKey] ?: DEFAULT_PORT,
            username = prefs[ProxyUsernameKey],
            password = prefs[ProxyPasswordKey],
            bypassStreams = prefs[StreamBypassProxyKey] == true,
        )
    }
}
