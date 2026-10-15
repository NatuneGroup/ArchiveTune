/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.viewmodels

import android.content.Context
import moe.rukamori.archivetune.canvas.models.CanvasArtwork
import moe.rukamori.archivetune.constants.AlbumCanvasEnabledKey
import moe.rukamori.archivetune.ui.player.resolveCanvasArtworkForPlayback
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.defaultStorefront
import moe.rukamori.archivetune.utils.get
import moe.rukamori.archivetune.utils.isLowDataModeActive

/**
 * How long a playlist page waits for the song its canvas is keyed on before giving up: a playlist
 * whose songs are all filtered out, or a chart with no plays yet, is a final state rather than
 * something worth holding a coroutine open for, and the canvas is decoration either way.
 */
internal const val PLAYLIST_CANVAS_LOOKUP_TIMEOUT_MS = 30_000L

/**
 * Resolves the looping animated canvas a playlist page plays behind its header.
 *
 * A playlist has no artwork of its own to look a canvas up by, so the first song stands in for it —
 * the same trade the album page makes with its first track.
 *
 * The loop piggybacks on the album page's switch ([AlbumCanvasEnabledKey], whose settings row reads
 * "Enable canvas in albums page") rather than getting a preference of its own: a playlist page
 * offers no separate choice, so a user who has turned canvases off in albums has turned them off
 * here too. What it does not share with the player is the timing — the loop starts as soon as the
 * page opens, whether or not anything is playing, which is why the fetch sits in the playlist
 * ViewModels rather than in the player's artwork pipeline.
 *
 * Only the Online and Top playlist pages ask for it: the two that pass the artwork to
 * `MediaDetailHero`, which loops it unless the Apple Music Experience is on — that branch swaps in
 * `AppleMusicPlaylistHero`, which has no backdrop for a canvas to sit behind. The other four
 * playlist pages pass no canvas and show static artwork.
 */
internal suspend fun fetchPlaylistCanvasArtwork(
    context: Context,
    firstSongId: String?,
    firstSongTitle: String?,
    firstSongArtist: String?,
    firstSongAlbumTitle: String? = null,
): CanvasArtwork? {
    if (firstSongId.isNullOrBlank() || firstSongTitle.isNullOrBlank()) return null

    if (!context.dataStore.get(AlbumCanvasEnabledKey, true)) return null

    if (context.isLowDataModeActive()) return null

    val storefront = defaultStorefront()

    return resolveCanvasArtworkForPlayback(
        mediaId = firstSongId,
        songTitleRaw = firstSongTitle,
        artistNameRaw = firstSongArtist.orEmpty(),
        storefront = storefront,
        requireVertical = false,
        allowNetwork = true,
        albumTitle = firstSongAlbumTitle,
        trySpotifyCanvas = true,
    )
}
