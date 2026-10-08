/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Media3 DataSource that streams a Deezer CDN file and Blowfish-decrypts it in flight, so the
 * extractor above it sees an ordinary FLAC/MP3 byte stream.
 * This is deliberately a DataSource rather than a standalone downloader. Downloads in this app run
 * through Media3's DownloadManager over the same DataSource factory as playback, so implementing
 * Deezer here means downloading, caching, tag embedding and codec reporting all keep working with no
 * Deezer-specific code in those paths. A separate download loop cannot share that machinery, and is
 * how corrupted files and unreadable tags arise.
 */



package moe.rukamori.archivetune.deezer

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import timber.log.Timber
import java.io.IOException
import kotlin.math.min

/**
 * Wraps [upstreamFactory] (an HTTP source) and decrypts Deezer's chunked Blowfish stream.
 *
 * Reads are served out of a one-chunk buffer: a whole 2048-byte chunk is pulled from upstream,
 * decrypted if its absolute index says it is encrypted, and then drained to the caller across as many
 * [read] calls as it takes. Buffering a whole chunk is required rather than an optimisation — a chunk
 * cannot be decrypted until all of it has arrived.
 */
internal class DeezerDecryptingDataSource(
    private val upstreamFactory: DataSource.Factory,
) : BaseDataSource(true) {
    private var upstream: DataSource? = null
    private var currentUri: Uri? = null
    private var key: ByteArray? = null

    private val chunk = ByteArray(DeezerCrypto.CHUNK_SIZE)

    /** Valid decrypted bytes currently held in [chunk], and how far the caller has drained them. */
    private var chunkLength = 0
    private var chunkOffset = 0

    /** Absolute index of the next chunk to fetch, which decides whether it is encrypted. */
    private var nextChunkIndex = 0L

    /** Bytes still owed to the caller, or [C.LENGTH_UNSET] when the total length is unknown. */
    private var bytesRemaining = C.LENGTH_UNSET.toLong()

    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        val ref =
            DeezerCrypto.parseUri(dataSpec.uri)
                ?: throw IOException("Not a Deezer stream URI: ${dataSpec.uri}")
        val realUri = ref.url

        key = DeezerCrypto.deriveKey(ref.trackId, ref.salt)
        currentUri = dataSpec.uri

        // Snap the requested offset down to a chunk boundary. Encrypted chunks are only decryptable
        // as whole chunks, so a seek landing mid-chunk has to re-fetch from that chunk's start and
        // throw away the bytes before the target; without this, seeks decode garbage.
        val requestedPosition = dataSpec.position
        val alignedPosition = requestedPosition - (requestedPosition % DeezerCrypto.CHUNK_SIZE)
        val discardCount = (requestedPosition - alignedPosition).toInt()
        nextChunkIndex = alignedPosition / DeezerCrypto.CHUNK_SIZE

        // Ask upstream for the discarded prefix too, otherwise a bounded request would come up short
        // by exactly discardCount bytes at the end of the range.
        val upstreamLength =
            if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                C.LENGTH_UNSET.toLong()
            } else {
                dataSpec.length + discardCount
            }

        // Deliberately not forwarding our transfer listeners to the upstream source: this class already
        // reports every byte it hands out via bytesTransferred, and registering the same listeners
        // downstream would count each byte twice in the bandwidth meter.
        val source = upstreamFactory.createDataSource()
        upstream = source

        val upstreamLengthReported =
            source.open(
                dataSpec
                    .buildUpon()
                    .setUri(realUri)
                    .setPosition(alignedPosition)
                    .setLength(upstreamLength)
                    .build(),
            )

        bytesRemaining =
            when {
                dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
                upstreamLengthReported == C.LENGTH_UNSET.toLong() -> C.LENGTH_UNSET.toLong()
                // Report the length the caller sees, which excludes the prefix we are about to drop.
                else -> (upstreamLengthReported - discardCount).coerceAtLeast(0L)
            }

        opened = true
        transferStarted(dataSpec)

        // Drop the pre-seek remainder of the first chunk before returning, so the very first read()
        // already starts at the byte the caller asked for.
        if (discardCount > 0) discardFully(discardCount)

        return bytesRemaining
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        if (chunkOffset >= chunkLength && !fillChunk()) return C.RESULT_END_OF_INPUT

        var available = chunkLength - chunkOffset
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            available = min(available.toLong(), bytesRemaining).toInt()
        }
        val toCopy = min(length, available)
        if (toCopy == 0) return C.RESULT_END_OF_INPUT

        chunk.copyInto(buffer, offset, chunkOffset, chunkOffset + toCopy)
        chunkOffset += toCopy
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= toCopy
        bytesTransferred(toCopy)
        return toCopy
    }

    /**
     * Pulls the next whole chunk from upstream and decrypts it when required. Returns false at
     * end of stream.
     */
    private fun fillChunk(): Boolean {
        val source = upstream ?: return false
        var filled = 0
        // Loop because a single upstream read is free to return fewer bytes than asked, and a chunk
        // that is short only because of a partial read must not be mistaken for the final chunk.
        while (filled < DeezerCrypto.CHUNK_SIZE) {
            val read = source.read(chunk, filled, DeezerCrypto.CHUNK_SIZE - filled)
            if (read == C.RESULT_END_OF_INPUT) break
            filled += read
        }
        if (filled == 0) return false

        // A trailing partial chunk is stored plaintext, so only decrypt a chunk that came back whole.
        if (filled == DeezerCrypto.CHUNK_SIZE && DeezerCrypto.isEncryptedChunk(nextChunkIndex)) {
            val chunkKey = key ?: return false
            try {
                DeezerCrypto.decryptChunk(chunk, filled, chunkKey)
            } catch (e: Exception) {
                // Surface as an IOException so the player treats it as a source failure and can fall
                // through to the next audio source, rather than crashing on a crypto exception.
                throw IOException("Deezer chunk decryption failed at index $nextChunkIndex", e)
            }
        }

        nextChunkIndex++
        chunkLength = filled
        chunkOffset = 0
        return true
    }

    /** Drops exactly [count] decrypted bytes, used to honour a mid-chunk seek offset. */
    private fun discardFully(count: Int) {
        var left = count
        while (left > 0) {
            if (chunkOffset >= chunkLength && !fillChunk()) return
            val skip = min(left, chunkLength - chunkOffset)
            chunkOffset += skip
            left -= skip
        }
    }

    override fun getUri(): Uri? = currentUri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream?.responseHeaders ?: emptyMap()

    override fun close() {
        chunkLength = 0
        chunkOffset = 0
        key = null
        currentUri = null
        bytesRemaining = C.LENGTH_UNSET.toLong()
        try {
            upstream?.close()
        } catch (e: IOException) {
            Timber.tag(TAG).w(e, "Failed to close Deezer upstream")
        } finally {
            upstream = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    class Factory(
        private val upstreamFactory: DataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = DeezerDecryptingDataSource(upstreamFactory)
    }

    private companion object {
        private const val TAG = "DeezerDataSource"
    }
}
