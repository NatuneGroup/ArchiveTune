/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.telegram

import moe.rukamori.archivetune.db.entities.FormatEntity
import moe.rukamori.archivetune.models.MediaMetadata

/**
 * Bridges a Telegram channel track into the app's playback model. The media id is the encoded
 * telegram:// URI, so the player's scheme router hands the item straight to [TelegramDataSource].
 */
fun TelegramTrack.toMediaMetadata(channelTitle: String? = null): MediaMetadata {
    // Prefer tag metadata, fall back to "Artist - Title" parsed from the file name — this is what
    // lyrics/canvas/cover lookups match against, so a real artist beats the channel name.
    val metadata = lookupMetadata
    return MediaMetadata(
        id = mediaId,
        title = metadata.title,
        artists =
            listOf(
                MediaMetadata.Artist(
                    id = null,
                    name = metadata.artist ?: channelTitle ?: "Telegram",
                ),
            ),
        duration = durationSeconds,
        // Artwork resolves lazily via the Coil tgart:// fetcher: a high-res catalogue cover looked
        // up by title/artist online, falling back to the embedded Telegram cover, then the tiny
        // minithumbnail.
        thumbnailUrl =
            telegramArtworkModel(thumbnailFileId, metadata.title, metadata.artist)
                ?: TelegramClient.cacheArtwork(
                    uniqueKey = fileUniqueId.ifEmpty { "$chatId-$messageId" },
                    data = albumCoverMinithumbnail,
                ),
    )
}

/**
 * Seed a [FormatEntity] for a Telegram track so the player-details / Nerd Stats screen shows file
 * size, container and an approximate bitrate immediately instead of hanging on "Loading format".
 * Sample rate is left null here and refined from the downloaded file during playback (see the
 * telegram branch of PlayerConnection's metadata-extraction job). itag = -1 marks a non-YouTube
 * source, matching the local-media convention.
 */
fun TelegramTrack.toFormatEntity(): FormatEntity {
    val extension = fileExtension(fileName)
    val mime =
        mimeType.substringBefore(";").ifBlank {
            when (extension) {
                "flac" -> "audio/flac"
                "wav", "wave" -> "audio/wav"
                "aiff", "aif" -> "audio/aiff"
                "alac" -> "audio/alac"
                "ape" -> "audio/x-ape"
                "wv" -> "audio/x-wavpack"
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "ogg", "oga" -> "audio/ogg"
                "opus" -> "audio/opus"
                else -> "audio/*"
            }
        }
    val averageBitrate =
        if (durationSeconds > 0 && sizeBytes > 0) {
            (sizeBytes * 8 / durationSeconds).toInt().coerceAtLeast(0)
        } else {
            0
        }
    return FormatEntity(
        id = mediaId,
        itag = -1,
        mimeType = mime,
        codecs = extension,
        bitrate = averageBitrate,
        sampleRate = null,
        contentLength = sizeBytes,
        loudnessDb = null,
        perceptualLoudnessDb = null,
        playbackUrl = null,
    )
}
