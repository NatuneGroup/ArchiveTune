/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.CancellationException
import moe.rukamori.archivetune.tidal.TidalAudioProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** Presents TIDAL's fragmented FLAC DASH stream as a progressive FLAC stream. */
internal class TidalProgressiveDashDataSource(
    private val httpClient: OkHttpClient,
) : BaseDataSource(true) {
    private var currentUri: Uri? = null
    private var segmentUrls: List<String> = emptyList()
    private var nextSegmentIndex = 0
    private var pendingBytes = ByteArray(0)
    private var pendingOffset = 0
    private var bytesRemaining = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        val manifestPath =
            dataSpec.uri.getQueryParameter("manifest")
                ?.takeIf(String::isNotBlank)
                ?: throw IOException("TIDAL progressive stream did not include a manifest")
        val manifestFile = File(manifestPath)
        if (!manifestFile.isFile) throw IOException("TIDAL progressive manifest is unavailable")

        val urls =
            runCatching {
                TidalAudioProvider.progressiveDashSegmentUrls(
                    manifestFile.readText(Charsets.UTF_8),
                )
            }.getOrElse { error ->
                throw IOException("TIDAL progressive manifest could not be parsed", error)
            }
        if (urls.size < 2 || urls.any { !it.startsWith("http://") && !it.startsWith("https://") }) {
            throw IOException("TIDAL progressive manifest did not contain absolute FLAC segments")
        }

        currentUri = dataSpec.uri
        segmentUrls = urls
        nextSegmentIndex = 1
        pendingBytes =
            byteArrayOf(
                'f'.code.toByte(),
                'L'.code.toByte(),
                'a'.code.toByte(),
                'C'.code.toByte(),
            ) + fetchSegment(urls.first(), 0)
        pendingOffset = 0
        bytesRemaining = C.LENGTH_UNSET.toLong()
        opened = true
        transferStarted(dataSpec)

        try {
            if (dataSpec.position > 0L) skipFully(dataSpec.position)
            bytesRemaining = dataSpec.length
            return bytesRemaining
        } catch (error: CancellationException) {
            close()
            throw error
        } catch (error: Throwable) {
            close()
            if (error is IOException) throw error
            throw IOException("TIDAL progressive stream seek failed", error)
        }
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        if (!opened) throw IOException("TIDAL progressive stream is not open")
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        if (pendingOffset >= pendingBytes.size && !loadNextSegment()) {
            return C.RESULT_END_OF_INPUT
        }

        var available = pendingBytes.size - pendingOffset
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            available = minOf(available.toLong(), bytesRemaining).toInt()
        }
        if (available <= 0) return C.RESULT_END_OF_INPUT

        val count = minOf(length, available)
        pendingBytes.copyInto(buffer, offset, pendingOffset, pendingOffset + count)
        pendingOffset += count
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= count
        bytesTransferred(count)
        return count
    }

    private fun loadNextSegment(): Boolean {
        if (nextSegmentIndex >= segmentUrls.size) return false
        val segmentIndex = nextSegmentIndex
        val payload = fetchSegment(segmentUrls[segmentIndex], segmentIndex)
        nextSegmentIndex++
        pendingBytes = payload
        pendingOffset = 0
        return pendingBytes.isNotEmpty()
    }

    private fun fetchSegment(
        url: String,
        segmentIndex: Int,
    ): ByteArray {
        val request =
            Request
                .Builder()
                .url(url)
                .header("Accept", "audio/flac,audio/*,*/*;q=0.8")
                .header("Accept-Encoding", "identity")
                .header("User-Agent", "ArchiveTune-Android")
                .get()
                .build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("TIDAL FLAC segment ${segmentIndex + 1} HTTP ${response.code}")
                }
                val bytes = response.body?.bytes() ?: throw IOException("TIDAL FLAC segment was empty")
                if (segmentIndex == 0) {
                    TidalAudioProvider.progressiveDashFlacMetadata(bytes)
                } else {
                    TidalAudioProvider.progressiveDashAudioPayload(bytes)
                }
            }
        } catch (error: IOException) {
            throw error
        } catch (error: Throwable) {
            throw IOException("TIDAL FLAC segment ${segmentIndex + 1} failed", error)
        }
    }

    private fun skipFully(bytesToSkip: Long) {
        var remaining = bytesToSkip
        val scratch = ByteArray(16 * 1024)
        while (remaining > 0L) {
            val read = read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
            if (read == C.RESULT_END_OF_INPUT) {
                throw IOException("TIDAL progressive stream ended before the requested position")
            }
            remaining -= read
        }
    }

    override fun getUri(): Uri? = currentUri

    override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

    override fun close() {
        segmentUrls = emptyList()
        nextSegmentIndex = 0
        pendingBytes = ByteArray(0)
        pendingOffset = 0
        bytesRemaining = C.LENGTH_UNSET.toLong()
        currentUri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    class Factory(
        private val httpClient: OkHttpClient,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = TidalProgressiveDashDataSource(httpClient)
    }
}
