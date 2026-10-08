/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Plain models for the Telegram channel browser plus the lossless-format detection used to filter
 * channel content. Kept free of Android/TDLib imports so the detection logic is unit-testable.
 */



package moe.rukamori.archivetune.telegram

import java.util.Locale

/** A public Telegram channel (or supergroup) as shown in channel search results. */
data class TelegramChannel(
    val chatId: Long,
    val title: String,
    val username: String?,
    val memberCount: Int,
    val isBroadcastChannel: Boolean,
    val photoMinithumbnail: ByteArray?,
    /** TDLib file id of the full-size channel avatar (0 when the chat has no photo). */
    val photoFileId: Int = 0,
) {
    override fun equals(other: Any?): Boolean = other is TelegramChannel && other.chatId == chatId

    override fun hashCode(): Int = chatId.hashCode()
}

/** One playable audio file found in a Telegram channel. */
data class TelegramTrack(
    val chatId: Long,
    val messageId: Long,
    val fileId: Int,
    val fileUniqueId: String,
    val title: String,
    val performer: String?,
    val fileName: String,
    val mimeType: String,
    val durationSeconds: Int,
    val sizeBytes: Long,
    val dateSeconds: Int,
    val albumCoverMinithumbnail: ByteArray?,
    /** TDLib file id of the full album-cover thumbnail (0 when the file has none). */
    val thumbnailFileId: Int = 0,
) {
    val mediaId: String
        get() =
            TelegramMediaId(
                chatId = chatId,
                messageId = messageId,
                fileId = fileId,
                fileUniqueId = fileUniqueId,
            ).encode()

    val isLossless: Boolean
        get() = isLosslessAudio(mimeType, fileName)

    /** Best-effort display title: audio tag title, else the file name without its extension. */
    val displayTitle: String
        get() =
            title.ifBlank {
                fileName.substringBeforeLast('.').ifBlank { fileName }
            }

    /**
     * Title/artist for metadata lookups (lyrics, canvas, cover art) and library rows. Prefers the
     * audio tags; when the performer tag is missing, tries to split the file name as
     * "Artist - Title" (with track numbers and noise stripped) so provider lookups can match.
     */
    val lookupMetadata: TelegramTrackMetadata
        get() = deriveTrackMetadata(tagTitle = title, tagPerformer = performer, fileName = fileName)

    override fun equals(other: Any?): Boolean =
        other is TelegramTrack && other.chatId == chatId && other.messageId == messageId

    override fun hashCode(): Int = (chatId * 31 + messageId).hashCode()
}

/** One page of channel audio results plus the cursor for the next page (0 = exhausted). */
data class TelegramAudioPage(
    val tracks: List<TelegramTrack>,
    val nextFromMessageId: Long,
)

private val LOSSLESS_MIME_TYPES =
    setOf(
        "audio/flac",
        "audio/x-flac",
        "audio/wav",
        "audio/x-wav",
        "audio/wave",
        "audio/vnd.wave",
        "audio/aiff",
        "audio/x-aiff",
        "audio/x-ape",
        "audio/x-monkeys-audio",
        "audio/x-wavpack",
        "audio/wavpack",
        "audio/x-tak",
        "audio/x-tta",
        "audio/x-dsd",
        "audio/dsd",
        "audio/x-dsf",
        "audio/alac",
    )

private val LOSSLESS_EXTENSIONS =
    setOf(
        "flac",
        "wav",
        "wave",
        "aiff",
        "aif",
        "ape",
        "alac",
        "wv",
        "tak",
        "tta",
        "dsf",
        "dff",
        "shn",
    )

/** Extensions that make a document message count as audio at all (documents carry no duration). */
private val AUDIO_EXTENSIONS =
    LOSSLESS_EXTENSIONS +
        setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wma", "mka")

fun fileExtension(fileName: String): String = fileName.substringAfterLast('.', "").lowercase(Locale.US)

fun isLosslessAudio(
    mimeType: String,
    fileName: String,
): Boolean {
    val mime = mimeType.trim().lowercase(Locale.US)
    if (mime in LOSSLESS_MIME_TYPES) return true
    return fileExtension(fileName) in LOSSLESS_EXTENSIONS
}

/** Whether a document message (arbitrary file) looks like an audio file worth listing. */
fun isAudioDocument(
    mimeType: String,
    fileName: String,
): Boolean {
    val mime = mimeType.trim().lowercase(Locale.US)
    if (mime.startsWith("audio/")) return true
    return fileExtension(fileName) in AUDIO_EXTENSIONS
}

/** Cleaned-up title + optional artist derived from a track's tags/file name. */
data class TelegramTrackMetadata(
    val title: String,
    val artist: String?,
)

private val NOISE_SUFFIX_REGEX =
    Regex("\\((?:official|lyric|audio|video|hd|hq|visualizer)[^)]*\\)", RegexOption.IGNORE_CASE)
private val BRACKET_TAG_REGEX = Regex("\\[[^\\]]*\\]")
private val LEADING_TRACK_NUMBER_REGEX = Regex("^\\s*\\d{1,3}\\s*[.\\-]\\s*")
private val WHITESPACE_REGEX = Regex("\\s+")

/** Strips bracketed tags, "(official …)" noise and leading track numbers from a raw name. */
fun cleanTrackName(raw: String): String =
    raw
        .replace(NOISE_SUFFIX_REGEX, " ")
        .replace(BRACKET_TAG_REGEX, " ")
        .replace(LEADING_TRACK_NUMBER_REGEX, "")
        .replace(WHITESPACE_REGEX, " ")
        .trim()

/**
 * Derives lookup metadata from tags + file name. When the performer tag is missing, a file name
 * shaped like "Artist - Title.flac" is split so the artist isn't lost (many channels tag nothing).
 */
fun deriveTrackMetadata(
    tagTitle: String,
    tagPerformer: String?,
    fileName: String,
): TelegramTrackMetadata {
    val performer = tagPerformer?.trim()?.takeIf(String::isNotEmpty)
    if (tagTitle.isNotBlank()) {
        return TelegramTrackMetadata(title = cleanTrackName(tagTitle).ifBlank { tagTitle.trim() }, artist = performer)
    }
    val base = cleanTrackName(fileName.substringBeforeLast('.').ifBlank { fileName })
    if (performer == null) {
        val separators = listOf(" - ", " – ", " — ")
        for (separator in separators) {
            val index = base.indexOf(separator)
            if (index > 0 && index < base.length - separator.length) {
                val artist = base.substring(0, index).trim()
                val title = base.substring(index + separator.length).trim()
                if (artist.isNotEmpty() && title.isNotEmpty()) {
                    return TelegramTrackMetadata(title = title, artist = artist)
                }
            }
        }
    }
    return TelegramTrackMetadata(title = base.ifBlank { fileName }, artist = performer)
}
