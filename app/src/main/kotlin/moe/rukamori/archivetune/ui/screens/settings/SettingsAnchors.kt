/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.screens.settings

import android.os.SystemClock
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp

/** Deep links from settings search to an individual preference on a sub-screen. */
object SettingsAnchorRequest {
    /**
     * How long a request stays claimable. Some settings screens bail out of composition on their
     * first pass (StorageSettings returns early until the player service and image cache are
     * available) and compose again a moment later. Clearing on the first read would throw the
     * request away during that discarded pass, so a claim is instead remembered for a short
     * window and re-served to the same screen until it sticks. Long enough to survive a service
     * connection, far too short to replay when the user opens the screen again later.
     */
    private const val CLAIM_WINDOW_MS = 1_500L

    private var pendingScreen: String? = null
    private var pendingAnchor: String? = null

    /**
     * When the pending request was first served, or null while it is still unclaimed.
     *
     * Deliberately nullable rather than using 0 as "unclaimed": uptimeMillis legitimately returns
     * small values just after boot, and a 0 reading would make the claim never register and the
     * request never expire.
     */
    private var claimedAtMs: Long? = null

    /**
     * Monotonic time source. Overridable only so the claim window is testable on a plain JVM,
     * where android.os.SystemClock is not implemented and throws. Production never reassigns it.
     */
    internal var elapsedMs: () -> Long = { SystemClock.uptimeMillis() }

    /** Drops any pending request. Test-only; production requests are cleared by [consume]. */
    internal fun reset() {
        pendingScreen = null
        pendingAnchor = null
        claimedAtMs = null
    }

    /** Ask [screen] to scroll to and highlight [anchor] the next time it composes. */
    fun request(screen: String, anchor: String) {
        pendingScreen = screen
        pendingAnchor = anchor
        claimedAtMs = null
    }

    /**
     * Returns the anchor requested for [screen].
     *
     * Returns null when the pending request was aimed at a different screen, so a stale request
     * can never highlight a row on a screen it was not meant for.
     */
    fun consume(screen: String): String? {
        if (pendingScreen != screen) return null
        val now = elapsedMs()
        val claimedAt = claimedAtMs
        if (claimedAt == null) {
            claimedAtMs = now
            return pendingAnchor
        }
        if (now - claimedAt > CLAIM_WINDOW_MS) {
            reset()
            return null
        }
        return pendingAnchor
    }
}

/**
 * Route strings of the settings screens that support deep anchors.
 *
 * Only screens whose content is a `Column` with a single `verticalScroll` can appear here: the
 * anchor maths resolves a row's offset against one [ScrollState]. Screens built on a LazyColumn
 * (account, Discord) never measure off-screen rows at all, so they would need a different
 * mechanism entirely rather than an entry in this list.
 */
object SettingsAnchorScreens {
    const val PLAYER = "settings/player"
    const val APPEARANCE = "settings/appearance"
    const val STORAGE = "settings/storage"
    const val CONTENT = "settings/content"
    const val PRIVACY = "settings/privacy"
    const val INTERNET = "settings/internet"
    const val LYRICS = "settings/lyrics"
    const val BACKUP = "settings/backup_restore"
    const val INTEGRATION = "settings/integration"
}

/** Stable ids for individually searchable preferences. Referenced by the deep search index. */
object SettingsAnchors {
    // Player
    const val CROSSFADE = "crossfade"
    const val GAPLESS = "gapless"
    const val SKIP_SILENCE = "skip_silence"
    const val AUDIO_NORMALIZATION = "audio_normalization"
    const val PERSISTENT_QUEUE = "persistent_queue"
    const val EXTERNAL_DOWNLOADER = "external_downloader"

    // Appearance
    const val DYNAMIC_THEME = "dynamic_theme"
    const val DARK_THEME = "dark_theme"
    const val PURE_BLACK = "pure_black"
    const val APP_ICON = "app_icon"
    const val FONT = "font"
    const val HIGH_REFRESH_RATE = "high_refresh_rate"

    // Storage
    const val EXPORT_DOWNLOADS = "export_downloads"
    const val EXPORT_DOWNLOADS_PICK = "export_downloads_pick"
    const val CLEAR_DOWNLOADS = "clear_downloads"
    const val SONG_CACHE_SIZE = "song_cache_size"
    const val CLEAR_SONG_CACHE = "clear_song_cache"
    const val IMAGE_CACHE_SIZE = "image_cache_size"
    const val SMART_TRIMMER = "smart_trimmer"

    // Content
    const val HIDE_EXPLICIT = "hide_explicit"
    const val HIDE_VIDEO = "hide_video"
    const val ALLOW_AGE_RESTRICTED = "allow_age_restricted"
    const val APP_LANGUAGE = "app_language"

    // Privacy
    const val PAUSE_LISTEN_HISTORY = "pause_listen_history"
    const val PAUSE_SEARCH_HISTORY = "pause_search_history"
    const val HAPTICS = "haptics"
    const val DISABLE_SCREENSHOT = "disable_screenshot"

    // Internet
    const val DNS_OVER_HTTPS = "dns_over_https"
    const val PROXY = "proxy"

    // Lyrics
    const val LYRICS_MODE = "lyrics_mode"
    const val LYRICS_ANIMATION = "lyrics_animation"
    const val LYRICS_AUTO_SCROLL = "lyrics_auto_scroll"
    const val LYRICS_LINE_BLUR = "lyrics_line_blur"

    // Backup and restore
    const val BACKUP = "backup"
    const val RESTORE = "restore"

    // Integration
    const val CROSS_SERVICE_IMPORT = "cross_service_import"
}

private val HighlightCornerRadius = 18.dp
private val HighlightInset = 1.dp
private const val HighlightFadeInMs = 220
private const val HighlightHoldMs = 1100L
private const val HighlightFadeOutMs = 450
