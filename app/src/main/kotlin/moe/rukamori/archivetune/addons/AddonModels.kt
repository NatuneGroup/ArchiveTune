/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.addons

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import moe.rukamori.archivetune.db.entities.FormatEntity

@Immutable
@Serializable
data class AddonManifest(
    val id: String = "",
    val name: String = "",
    val version: String? = null,
    val searchEndpoint: String? = null,
    val streamEndpoint: String? = null,
    val resources: List<String> = emptyList(),
    val settings: List<AddonManifestSetting> = emptyList(),
)

@Serializable
data class AddonSearchResponse(
    val results: List<AddonTrack> = emptyList(),
    val tracks: List<AddonTrack> = emptyList(),
)

@Serializable
data class AddonManifestSetting(
    val key: String = "",
    @SerialName("default") val defaultValue: JsonElement? = null,
    val options: List<AddonSettingOption> = emptyList(),
)

@Serializable
data class AddonSettingOption(
    val value: JsonElement? = null,
)

@Immutable
@Serializable
data class AddonTrack(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String? = null,
    val artworkUrl: String? = null,
    @SerialName("artworkURL") val artworkURL: String? = null,
    @SerialName("albumArtworkURL") val albumArtworkURL: String? = null,
    @SerialName("streamURL") val streamURL: String? = null,
    val durationMs: Long? = null,
    /** BitChord's track duration is expressed in seconds. */
    val duration: Double? = null,
    val format: String? = null,
    val audioQuality: String? = null,
    val addonId: String = "",
    val addonName: String = "",
) {
    val displayArtworkUrl: String?
        get() = artworkUrl ?: artworkURL ?: albumArtworkURL

    val displayDurationMs: Long?
        get() = durationMs ?: duration?.takeIf { it > 0.0 }?.times(1_000.0)?.toLong()
}

@Serializable
data class AddonStreamResponse(
    val url: String = "",
    val mimeType: String? = null,
    val codecs: String? = null,
    val codec: String? = null,
    val fileCodec: String? = null,
    val format: String? = null,
    val quality: String? = null,
    val streamQuality: String? = null,
    val audioQuality: String? = null,
    val container: String? = null,
    val containerFormat: String? = null,
    val mediaType: String? = null,
    /** The stream's transport: `none`, `hls`, or `dash` in BitChord's protocol. */
    val manifest: String? = null,
    val encrypted: JsonElement? = null,
    /** Encoded audio bitrate in bits per second. */
    val bitrate: Double? = null,
    /** Sample rate in hertz. */
    val sampleRate: Double? = null,
    val bitDepth: Double? = null,
    val contentLength: Long? = null,
    val error: String? = null,
)

fun AddonStreamResponse.toFormatEntity(mediaId: String): FormatEntity {
    val qualityDescription = listOfNotNull(quality, streamQuality, audioQuality, format).joinToString(" ")
    val codec = codecs?.trim()?.ifEmpty { null }
        ?: codec?.trim()?.ifEmpty { null }
        ?: fileCodec?.trim()?.ifEmpty { null }
        ?: container?.takeIf { it.isAudioCodecName() }
        ?: containerFormat?.takeIf { it.isAudioCodecName() }
        ?: format?.takeIf { it.isAudioCodecName() }
        ?: mimeType?.substringAfter("codecs=", "")?.removeSurrounding("\"")?.takeIf(String::isNotBlank)
        ?: inferCodecFromUrl(url)
    val normalizedCodec = codec.ifBlank { inferCodecFromText(qualityDescription) }
    val mime = mimeType?.substringBefore(';')?.trim()?.takeIf { it.startsWith("audio/") }
        ?: mimeTypeForCodec(normalizedCodec)
    val normalizedBitrate = bitrate?.takeIf { it > 0 }?.toInt() ?: qualityDescription.bitrateFromText()
    val normalizedSampleRate = sampleRate?.takeIf { it > 0 }?.toInt()
        ?: qualityDescription.sampleRateFromText()
        ?: qualityDescription.takeIf { it.contains(HI_RES_QUALITY_HINT, ignoreCase = true) }?.let { 96_000 }

    return FormatEntity(
        id = mediaId,
        itag = 0,
        mimeType = mime,
        codecs = normalizedCodec,
        bitrate = normalizedBitrate ?: 0,
        sampleRate = normalizedSampleRate,
        contentLength = contentLength?.takeIf { it > 0L } ?: 0L,
        loudnessDb = null,
        playbackUrl = null,
    )
}

fun AddonStreamResponse.playbackMimeType(audioMimeType: String?): String? =
    when (transportType()) {
        "hls" -> "application/x-mpegURL"
        "dash" -> "application/dash+xml"
        else -> mimeType?.takeIf { it.startsWith("audio/") } ?: audioMimeType
    }

fun AddonStreamResponse.isEncrypted(): Boolean {
    val primitive = encrypted as? JsonPrimitive ?: return false
    primitive.booleanOrNull?.let { return it }
    return primitive.content.isNotBlank() &&
        !primitive.content.equals("false", ignoreCase = true) &&
        !primitive.content.equals("none", ignoreCase = true)
}

private fun AddonStreamResponse.transportType(): String? {
    val candidates = listOfNotNull(manifest, mediaType, mimeType, format)
    return candidates.firstNotNullOfOrNull { candidate ->
        when (candidate.trim().lowercase()) {
            "hls", "m3u8", "application/x-mpegurl", "application/vnd.apple.mpegurl" -> "hls"
            "dash", "mpd", "application/dash+xml" -> "dash"
            else -> null
        }
    }
}

private fun String.isAudioCodecName(): Boolean =
    contains("flac", true) || contains("alac", true) || contains("mp3", true) ||
        contains("aac", true) || contains("mp4a", true) || contains("opus", true) || contains("vorbis", true)

    private const val HI_RES_QUALITY_HINT = "hi-res"

private fun inferCodecFromText(value: String): String =
    when {
        value.contains("flac", true) -> "FLAC"
        value.contains("alac", true) -> "ALAC"
        value.contains("mp3", true) -> "MP3"
        value.contains("aac", true) -> "AAC"
        value.contains("opus", true) -> "Opus"
        value.contains("vorbis", true) -> "Vorbis"
        else -> ""
    }

private fun String.bitrateFromText(): Int? =
    Regex("(\\d{2,4})\\s*(?:kbps|kbit/s|kbit)", RegexOption.IGNORE_CASE)
        .find(this)
        ?.groupValues?.get(1)?.toIntOrNull()?.times(1_000)

private fun String.sampleRateFromText(): Int? =
    Regex("(\\d+(?:\\.\\d+)?)\\s*kHz", RegexOption.IGNORE_CASE)
        .find(this)
        ?.groupValues?.get(1)?.toDoubleOrNull()?.times(1_000)?.toInt()

private fun inferCodecFromUrl(url: String): String =
    when (url.substringBefore('?').substringAfterLast('.', "").lowercase()) {
        "flac" -> "FLAC"
        "mp3" -> "MP3"
        "m4a", "aac" -> "AAC"
        "opus" -> "Opus"
        "ogg" -> "Vorbis"
        "alac" -> "ALAC"
        else -> ""
    }

private fun mimeTypeForCodec(codec: String): String =
    when {
        codec.contains("flac", ignoreCase = true) -> "audio/flac"
        codec.contains("alac", ignoreCase = true) -> "audio/alac"
        codec.contains("mp3", ignoreCase = true) -> "audio/mpeg"
        codec.contains("aac", ignoreCase = true) || codec.contains("mp4a", ignoreCase = true) -> "audio/mp4"
        codec.contains("opus", ignoreCase = true) -> "audio/opus"
        codec.contains("vorbis", ignoreCase = true) -> "audio/ogg"
        else -> "application/octet-stream"
    }

@Immutable
@Serializable
data class AddonInstallation(
    val id: String,
    val manifestUrl: String,
    val name: String,
    val enabled: Boolean = true,
)