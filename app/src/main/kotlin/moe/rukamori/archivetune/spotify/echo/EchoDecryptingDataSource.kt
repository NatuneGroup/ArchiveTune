/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * Portions © 4nx3b — github.com/4nx3b
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Media3 DataSource that streams a Spotify audio CDN file (Echo resolve method)
 * and AES-128-CTR decrypts it in flight, following the byte alignment the
 * echo-spotify-extension oggStream pipeline uses: Ogg Vorbis payloads start at
 * 0xA7 (the unencrypted header), FLAC at 0; the cipher block grid is absolute
 * from that origin, so every range read re-aligns to 16 bytes and re-derives
 * the CTR counter for its own offset.
 */

package moe.rukamori.archivetune.spotify.echo

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import timber.log.Timber
import java.io.IOException
import java.math.BigInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

internal class EchoDecryptingDataSource(
    private val upstreamFactory: DataSource.Factory,
) : BaseDataSource(true) {
    private var upstream: DataSource? = null
    private var currentUri: Uri? = null
    private var key: ByteArray? = null
    private var cipher: Cipher? = null

    private var blockOffset = 0
    private val block = ByteArray(BLOCK_SIZE)

    private var blockLength = 0

    private var streamPos = 0L

    private var bytesRemaining = C.LENGTH_UNSET.toLong()

    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        val ref =
            SpotifyEchoResolver.parseStreamUri(dataSpec.uri)
                ?: throw IOException("Not an Echo stream URI: ${dataSpec.uri}")
        currentUri = dataSpec.uri

        val keyBytes = hexToBytes(ref.keyHex)
        if (keyBytes.size != 16) throw IOException("Invalid Echo audio key length")
        key = keyBytes

        val byteSkip = if (SpotifyEchoResolver.isFlac(ref.format)) 0L else HEADER_SKIP
        val requestedPosition = dataSpec.position
        val virtualPos = requestedPosition + byteSkip
        val alignedPosition = virtualPos - (virtualPos % BLOCK_SIZE)
        blockOffset = (virtualPos - alignedPosition).toInt()

        val upstreamLength =
            if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                C.LENGTH_UNSET.toLong()
            } else {
                dataSpec.length + blockOffset
            }

        val source = upstreamFactory.createDataSource()
        upstream = source

        val upstreamLengthReported =
            source.open(
                dataSpec
                    .buildUpon()
                    .setUri(Uri.parse(ref.cdnUrls.first()))
                    .setPosition(alignedPosition)
                    .setLength(upstreamLength)
                    .build(),
            )

        streamPos = alignedPosition

        bytesRemaining =
            when {
                dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
                upstreamLengthReported == C.LENGTH_UNSET.toLong() -> C.LENGTH_UNSET.toLong()
                else -> (upstreamLengthReported - blockOffset).coerceAtLeast(0L)
            }

        cipher = Cipher.getInstance("AES/CTR/NoPadding")

        opened = true
        transferStarted(dataSpec)

        if (blockOffset > 0) discardFully(blockOffset)

        return bytesRemaining
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        if (blockOffset >= blockLength && !fillBlock()) return C.RESULT_END_OF_INPUT

        var available = blockLength - blockOffset
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            available = min(available.toLong(), bytesRemaining).toInt()
        }
        val toCopy = min(length, available)
        if (toCopy == 0) return C.RESULT_END_OF_INPUT

        block.copyInto(buffer, offset, blockOffset, blockOffset + toCopy)
        blockOffset += toCopy
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= toCopy
        bytesTransferred(toCopy)
        return toCopy
    }

    private fun fillBlock(): Boolean {
        val source = upstream ?: return false
        var filled = 0
        while (filled < BLOCK_SIZE) {
            val read = source.read(block, filled, BLOCK_SIZE - filled)
            if (read == C.RESULT_END_OF_INPUT) break
            filled += read
        }
        if (filled == 0) return false

        val activeCipher = cipher ?: return false
        val keyBytes = key ?: return false
        val counter = AUDIO_IV.add(BigInteger.valueOf(streamPos / BLOCK_SIZE))
        try {
            activeCipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(counter.to16ByteArray()))
            val encrypted = if (filled == BLOCK_SIZE) block else block.copyOf(filled)
            val decrypted = activeCipher.doFinal(encrypted)
            decrypted.copyInto(block, 0, 0, decrypted.size)
        } catch (e: Exception) {
            throw IOException("Echo audio block decryption failed", e)
        }

        streamPos += filled
        blockLength = filled
        blockOffset = 0
        return true
    }

    private fun discardFully(count: Int) {
        var left = count
        while (left > 0) {
            if (blockOffset >= blockLength && !fillBlock()) return
            val skip = min(left, blockLength - blockOffset)
            blockOffset += skip
            left -= skip
        }
    }

    override fun getUri(): Uri? = currentUri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream?.responseHeaders ?: emptyMap()

    override fun close() {
        blockLength = 0
        blockOffset = 0
        streamPos = 0L
        key = null
        cipher = null
        currentUri = null
        bytesRemaining = C.LENGTH_UNSET.toLong()
        try {
            upstream?.close()
        } catch (e: IOException) {
            Timber.tag(TAG).w(e, "Failed to close Echo upstream")
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
        override fun createDataSource(): DataSource = EchoDecryptingDataSource(upstreamFactory)
    }

    private fun BigInteger.to16ByteArray(): ByteArray {
        val raw = toByteArray()
        if (raw.size == 16) return raw
        val out = ByteArray(16)
        if (raw.size > 16) {
            System.arraycopy(raw, raw.size - 16, out, 0, 16)
        } else {
            System.arraycopy(raw, 0, out, 16 - raw.size, raw.size)
        }
        return out
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    private companion object {
        private const val TAG = "EchoDataSource"
        private const val BLOCK_SIZE = 16
        private const val HEADER_SKIP = 0xA7L
        private val AUDIO_IV = BigInteger("72e067fbddcbcf77ebe8bc643f630d93", 16)
    }
}
