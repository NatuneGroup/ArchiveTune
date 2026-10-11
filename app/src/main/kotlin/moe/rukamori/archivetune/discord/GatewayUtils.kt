/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.discord

object GatewayOp {
    const val DISPATCH = 0
    const val HEARTBEAT = 1
    const val IDENTIFY = 2
    const val PRESENCE_UPDATE = 3
    const val VOICE_STATE_UPDATE = 4
    const val RESUME = 6
    const val RECONNECT = 7
    const val INVALID_SESSION = 9
    const val HELLO = 10
    const val HEARTBEAT_ACK = 11
}

val NON_RESUMABLE_CLOSE_CODES: Set<Int> = setOf(4004, 4010, 4011, 4012, 4013, 4014)

object GatewayDefaults {
    const val API_BASE = "https://discord.com/api"
    const val GATEWAY_URL = "wss://gateway.discord.gg"
    const val GATEWAY_VERSION = 10
    const val USER_AGENT = "Discord Embedded/1.9.15780"
    const val HELLO_TIMEOUT_MS = 20_000L
}

object ActivityTypes {
    private val keys = listOf("PLAYING", "STREAMING", "LISTENING", "WATCHING", "CUSTOM", "COMPETING", "HANG")
    private val forward = keys.filter { it.isNotEmpty() }.mapIndexed { i, k -> k to i }.toMap()
    private val reverse = forward.entries.associate { (k, v) -> v to k }

    fun fromString(name: String): Int? = forward[name]

    fun fromInt(value: Int): String? = reverse[value]
}
