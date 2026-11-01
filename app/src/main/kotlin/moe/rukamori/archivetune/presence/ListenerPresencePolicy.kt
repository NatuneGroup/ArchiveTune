/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.presence

import kotlin.random.Random

/** On-disk names for the listener-presence opt-in inside its own DataStore file. */
internal object ListenerPresenceKeys {
    const val ENABLED: String = "listenerPresenceEnabled"
    const val LISTENER_ID: String = "listenerPresenceId"
    const val CONSENT_VERSION: String = "listenerPresenceConsentVersion"
}

/**
 * The persisted listener-presence opt-in. [listenerId] is a random pseudonym that the service only
 * ever sees as a keyed hash. An opt-in recorded under an older [consentVersion] reads as off, so a
 * changed disclosure asks the user again.
 */
data class ListenerPresenceState(
    val enabled: Boolean,
    val listenerId: String?,
    val consentVersion: Int,
) {
    val active: Boolean
        get() = enabled && consentVersion == ListenerPresencePolicy.CONSENT_VERSION && !listenerId.isNullOrEmpty()

    companion object {
        val Off: ListenerPresenceState = ListenerPresenceState(enabled = false, listenerId = null, consentVersion = 0)
    }
}

/**
 * The whole listener-presence contract: a random identifier, the platform, the app version and a
 * playing or stopped state. Nothing about tracks, playlists, searches, accounts or the device's
 * location is part of the payload, and the backend rejects any extra field.
 */
object ListenerPresencePolicy {
    const val CONSENT_VERSION: Int = 1
    const val SCHEMA_VERSION: Int = 1

    /** One beat on play start, then about every 15 minutes plus up to two minutes of jitter. */
    const val HEARTBEAT_INTERVAL_MS: Long = 900_000L
    const val HEARTBEAT_JITTER_MS: Long = 120_000L
    const val MAX_PAYLOAD_BYTES: Int = 1024
    const val STATE_PLAYING: String = "playing"
    const val STATE_STOPPED: String = "stopped"
    const val PLATFORM_ANDROID: String = "android"
    const val MAP_URL: String = "https://natunegroup.github.io/natune-status/"

    private val listenerIdPattern = Regex("^[a-f0-9]{32}$")
    private val appVersionPattern = Regex("^[0-9A-Za-z._+-]{1,32}$")

    /** Sixteen random bytes as 32 lowercase hex characters. Android passes SecureRandom through [randomBytes]. */
    fun newListenerId(randomBytes: (Int) -> ByteArray = { Random.nextBytes(it) }): String =
        randomBytes(LISTENER_ID_BYTES).joinToString(separator = "") { byte ->
            (byte.toInt() and 0xFF).toString(radix = 16).padStart(2, '0')
        }

    fun isValidListenerId(id: String): Boolean = listenerIdPattern.matches(id)

    /** An unusual version string is replaced by a fixed token rather than sent as free text. */
    fun sanitizedAppVersion(versionName: String): String =
        if (appVersionPattern.matches(versionName)) versionName else UNKNOWN_APP_VERSION

    fun payloadJson(
        listenerId: String,
        appVersion: String,
        state: String,
    ): String {
        require(isValidListenerId(listenerId)) { "Listener presence id must be 32 lowercase hex characters" }
        require(state == STATE_PLAYING || state == STATE_STOPPED) { "Unknown listener presence state" }
        val json =
            """{"schema":$SCHEMA_VERSION,"id":"$listenerId","platform":"$PLATFORM_ANDROID",""" +
                """"app":"${sanitizedAppVersion(appVersion)}","state":"$state"}"""
        check(json.encodeToByteArray().size <= MAX_PAYLOAD_BYTES) { "Listener presence payload exceeds its limit" }
        return json
    }

    /** The opt-in after the user switches it on: a fresh identifier and the current consent version. */
    fun enable(
        current: ListenerPresenceState,
        newId: () -> String,
    ): ListenerPresenceState =
        if (current.active) {
            current
        } else {
            ListenerPresenceState(enabled = true, listenerId = newId(), consentVersion = CONSENT_VERSION)
        }

    /** The opt-in after the user switches it off. The identifier is removed, so a later opt-in starts fresh. */
    fun disable(): ListenerPresenceState = ListenerPresenceState.Off

    /** Replaces the identifier of an active opt-in. An inactive opt-in is returned unchanged. */
    fun resetId(
        current: ListenerPresenceState,
        newId: () -> String,
    ): ListenerPresenceState = if (current.active) current.copy(listenerId = newId()) else current

    private const val LISTENER_ID_BYTES: Int = 16
    private const val UNKNOWN_APP_VERSION: String = "unknown"
}
