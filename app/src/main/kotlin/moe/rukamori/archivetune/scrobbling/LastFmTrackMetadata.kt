/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.scrobbling

import moe.rukamori.archivetune.models.MediaMetadata

/** The fields Last.fm's track.* write methods take, cleaned of YouTube-specific noise. */
data class LastFmTrackMetadata(
    val artist: String,
    val track: String,
    val album: String?,
    val durationSeconds: Int?,
) {
    companion object {
        private val TOPIC_CHANNEL_SUFFIX = Regex("""\s+-\s+Topic$""", RegexOption.IGNORE_CASE)

        // Only bracketed upload-format labels: "(Live)", "(Remix)" and "(feat. X)" name a
        // different recording and must reach Last.fm unchanged.
        private val UPLOAD_LABEL_SUFFIX =
            Regex(
                """\s*[(\[]\s*(?:official\s+)?(?:music\s+video|video|audio|lyrics?(?:\s+video)?|visuali[sz]er|mv|m/v|hd|hq|4k)\s*[)\]]""",
                RegexOption.IGNORE_CASE,
            )

        /**
         * Last.fm takes one artist string and matches it against its catalogue, so a joined
         * "A, B" list creates a phantom artist that never links to either; the primary artist is
         * the only value that resolves. Returns null when there is nothing Last.fm would accept.
         */
        fun from(
            metadata: MediaMetadata,
            durationSeconds: Int?,
        ): LastFmTrackMetadata? {
            val artist =
                metadata.artists
                    .asSequence()
                    .map { it.name.replace(TOPIC_CHANNEL_SUFFIX, "").trim() }
                    .firstOrNull { it.isNotEmpty() }
                    ?: return null
            val rawTitle = metadata.title.trim()
            val track = rawTitle.replace(UPLOAD_LABEL_SUFFIX, "").trim().ifEmpty { rawTitle }
            if (track.isEmpty()) return null
            return LastFmTrackMetadata(
                artist = artist,
                track = track,
                album = metadata.album?.title?.trim()?.takeIf { it.isNotEmpty() },
                durationSeconds = durationSeconds?.takeIf { it > 0 },
            )
        }
    }
}
