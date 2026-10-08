/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Ytdlnis-compatible fallback resolver: the external-yt-dlp tier, without bundling Python. Uses
 * yt-dlp via CompactYtDlp (YTDLnis plugin APK) — only if a plugin APK is installed
 * (com.deniscerri.ytdl.python etc). No Python is bundled; the APK's libpython.so is probed at
 * runtime (see CompactYtDlp.kt). This is the YTDLnis fallback path but compact.
 */



package moe.rukamori.archivetune.playback.stream

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import moe.rukamori.archivetune.ytdlp.CompactYtDlp
import org.json.JSONObject
import timber.log.Timber

@Singleton
class YtdlnisStreamRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : AudioStreamRepository {

        override suspend fun resolve(request: AudioStreamRequest): ResolvedAudioStream {
            if (CompactYtDlp.isAvailable(context)) {
                try {
                    val ytdlpResult = tryExternalYtDlp(request)
                    if (ytdlpResult != null) return ytdlpResult
                } catch (e: Exception) {
                    Timber.tag(TAG).d(e, "External yt-dlp fallback failed for %s", request.mediaId)
                }
            }

            throw YtDlpExtractionException("yt-dlp produced no stream for ${request.mediaId}")
        }

        private suspend fun tryExternalYtDlp(request: AudioStreamRequest): ResolvedAudioStream? {
            val json = CompactYtDlp.dumpJson(context, request.mediaId, extraArgs = listOf("--format", "bestaudio/best", "--no-playlist")) ?: return null
            // yt-dlp --dump-json returns a JSON object per line; take first line
            val firstLine = json.lineSequence().firstOrNull { it.trim().startsWith("{") } ?: return null
            val obj = JSONObject(firstLine)
            val url = obj.optString("url").takeIf { it.isNotBlank() }
                ?: obj.optJSONArray("formats")?.let { arr ->
                    // Pick best audio format
                    var best: JSONObject? = null
                    var bestAbr = 0
                    for (i in 0 until arr.length()) {
                        val f = arr.getJSONObject(i)
                        val vcodec = f.optString("vcodec")
                        if (vcodec != "none" && vcodec.isNotBlank()) continue // skip video
                        val abr = f.optInt("abr", 0)
                        if (abr > bestAbr) {
                            bestAbr = abr
                            best = f
                        }
                    }
                    best?.optString("url")
                } ?: return null

            val ext = obj.optString("ext", "mp4")
            val mimeType = when (ext) {
                "opus" -> "audio/opus"
                "m4a", "mp4" -> "audio/mp4"
                "webm" -> "audio/webm"
                else -> "audio/$ext"
            }
            val bitrate = obj.optInt("abr", 128) * 1000
            val duration = obj.optInt("duration", 0)
            return ResolvedAudioStream(
                url = url,
                requestHeaders = emptyMap(),
                formatId = obj.optInt("format_id", 0),
                mimeType = mimeType,
                codecs = obj.optString("acodec", "opus"),
                bitrate = bitrate,
                sampleRate = obj.optInt("asr", 48000),
                contentLength = 0L,
                expiresAtMs = System.currentTimeMillis() + 6 * 60 * 60 * 1000L,
                authFingerprint = request.authState.streamCacheFingerprint,
                source = StreamSource.YT_DLP,
                title = obj.optString("title"),
                durationSeconds = duration,
                thumbnailUrl = obj.optString("thumbnail"),
                loudnessDb = null,
                perceptualLoudnessDb = null,
                playbackTrackingUrl = null,
            )
        }

        companion object {
            private const val TAG = "YtdlnisStreamRepository"
        }
    }

class YtDlpExtractionException(message: String, cause: Throwable? = null) : Exception(message, cause)
