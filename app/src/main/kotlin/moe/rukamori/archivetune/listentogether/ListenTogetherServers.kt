/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Listen Together public server list — ported from vivi-music (beta branch),
 * vivi-music's listentogether.ListenTogetherServers (GPL-3.0), plus the
 * Metrolist project's community server (metrolistgroup/metrolist, GPL-3.0).
 */



package moe.rukamori.archivetune.listentogether

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire protocol a Listen Together server speaks. All vivi-derived servers are
 * JSON; Metrolist's metroserver (The Meowery) is protobuf-only and rejects JSON
 * frames with an `invalid_message` error.
 */
@Serializable
enum class ListenTogetherProtocol {
    JSON,
    PROTOBUF,
}

@Serializable
data class ListenTogetherServer(
    val name: String,
    val url: String,
    val location: String,
    val operator: String,
    val protocol: ListenTogetherProtocol = ListenTogetherProtocol.JSON,
)

object ListenTogetherServers {
    private const val ServersJson = """
        [
          {
            "name": "Hugging Face Sync",
            "url": "wss://devilmi-vivi-music-listen-together.hf.space",
            "location": "Global",
            "operator": "VIVIDH"
          },
          {
            "name": "The Meowery",
            "url": "wss://metroserverx.meowery.eu/ws",
            "location": "Poland",
            "operator": "Nyx",
            "protocol": "PROTOBUF"
          }
        ]
    """

    private val json = Json { ignoreUnknownKeys = true }

    val servers: List<ListenTogetherServer> by lazy {
        json.decodeFromString(ServersJson)
    }

    val defaultServerUrl: String
        get() = servers.first().url

    fun findByUrl(url: String): ListenTogetherServer? = servers.firstOrNull { it.url == url }

    // The retired vivi server: never contacted, only recognised so invite links handed out before
    // the switch still open the app. Keep in sync with the https invite filter in AndroidManifest.
    private const val LEGACY_INVITE_HOST = "vivimusic-listen-together.onrender.com"

    /** Host part of a server URL, e.g. `wss://host/ws` -> `host`. */
    fun hostOf(url: String): String? =
        url
            .trim()
            .substringAfter("://", missingDelimiterValue = url.trim())
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore(':')
            .lowercase()
            .takeIf { it.isNotBlank() }

    /**
     * Host of the web invite link for [serverUrl]. Only JSON (vivi) servers serve the `/listen`
     * web client; anything else, including a custom or LAN URL, falls back to the default server.
     */
    fun inviteHost(serverUrl: String): String {
        val server = findByUrl(serverUrl)?.takeIf { it.protocol == ListenTogetherProtocol.JSON }
        return server?.let { hostOf(it.url) }
            ?: hostOf(defaultServerUrl)
            ?: LEGACY_INVITE_HOST
    }

    /** Hosts whose `https://<host>/listen?code=` links open the app. */
    val inviteHosts: Set<String> by lazy {
        servers
            .filter { it.protocol == ListenTogetherProtocol.JSON }
            .mapNotNull { hostOf(it.url) }
            .toSet() + LEGACY_INVITE_HOST
    }

    fun isInviteHost(host: String?): Boolean = host != null && host.lowercase() in inviteHosts
}
