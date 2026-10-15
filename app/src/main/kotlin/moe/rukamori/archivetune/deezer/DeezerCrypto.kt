/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.deezer

import android.net.Uri
import androidx.core.net.toUri
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Blowfish stream layout used by Deezer's CDN, plus the `deezer://` URI wrapper that carries a
 * resolved stream from [DeezerAudioProvider] to [DeezerDecryptingDataSource].
 */
internal object DeezerCrypto {
    /** Deezer's CDN chunk size. Encryption applies per whole chunk, so this is also the seek grain. */
    const val CHUNK_SIZE = 2048

    /** Only every third chunk is encrypted; the other two are stored as plaintext. */
    const val ENCRYPTED_CHUNK_STRIDE = 3

    /** Scheme for the internal URI that wraps a resolved Deezer stream. */
    const val SCHEME = "deezer"

    private const val PARAM_URL = "u"
    private const val PARAM_KEY = "k"
    private const val PARAM_SALT = "s"

    /**
     * Fixed key-derivation salt for the per-track Blowfish key. Deezer reuses one constant for every
     * track and derives the actual key from the track id, so this alone unlocks nothing — resolving a
     * playable CDN URL still requires an authenticated account (see [DeezerAudioProvider]).
     */
    const val DEFAULT_KEY_SALT = "g4el58wc0zvf9na1"

    /** Deezer restarts every encrypted chunk from this same IV, which is what makes chunks seekable. */
    private val CHUNK_IV = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)

    /**
     * Derives the 16-byte Blowfish key for [trackId] by XOR-folding the two halves of the track id's
     * MD5 against [salt], which defaults to [DEFAULT_KEY_SALT].
     *
     * Note this consumes the MD5 as lowercase hex *text*, not as raw digest bytes — the bytes of the
     * hex characters are what get XORed. Using the raw digest yields a wrong key that still decrypts
     * without error, producing plausible-looking noise instead of a failure, so this stays explicit.
     */
    fun deriveKey(
        trackId: String,
        salt: String = DEFAULT_KEY_SALT,
    ): ByteArray {
        // A short override would index out of bounds below, and a wrong-length salt cannot be the
        // real one anyway, so fall back rather than crash on a bad pool payload.
        val effective = if (salt.length >= 16) salt else DEFAULT_KEY_SALT
        val md5Hex =
            MessageDigest
                .getInstance("MD5")
                .digest(trackId.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        return ByteArray(16) { i ->
            (md5Hex[i].code xor md5Hex[i + 16].code xor effective[i].code).toByte()
        }
    }

    /** True when the chunk at absolute [chunkIndex] is encrypted rather than stored plaintext. */
    fun isEncryptedChunk(chunkIndex: Long): Boolean = chunkIndex % ENCRYPTED_CHUNK_STRIDE == 0L

    /**
     * Decrypts one whole chunk in place, in-place over the first [length] bytes of [chunk].
     *
     * [length] must be a multiple of the Blowfish 8-byte block size; callers only ever pass a
     * complete [CHUNK_SIZE] chunk, because a trailing short chunk is never encrypted in the first
     * place and must be passed through untouched.
     */
    fun decryptChunk(
        chunk: ByteArray,
        length: Int,
        key: ByteArray,
    ) {
        if (length < 8) return
        // Any remainder past the last whole 8-byte block is stored as-is and must not be fed to the
        // cipher, which would throw on a partial block.
        val decryptable = length - (length % 8)
        val cipher = Cipher.getInstance("Blowfish/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "Blowfish"), IvParameterSpec(CHUNK_IV))
        cipher.doFinal(chunk, 0, decryptable, chunk, 0)
    }

    /**
     * Wraps a real CDN [url] and its [trackId] into a `deezer://` URI.
     *
     * The scheme exists so the existing scheme-routing DataSources in `MusicService` and
     * `DownloadUtil` can dispatch Deezer bytes to the decrypting source the same way they already
     * dispatch `telegram://`. The track id rides along because the decryption key derives from it and
     * the DataSource has no other way to recover it.
     */
    fun buildUri(
        url: String,
        trackId: String,
        salt: String? = null,
    ): String =
        Uri
            .Builder()
            .scheme(SCHEME)
            .authority("stream")
            .appendQueryParameter(PARAM_URL, url)
            .appendQueryParameter(PARAM_KEY, trackId)
            .apply {
                // Only carried when a pool account overrode it, so ordinary URIs stay short.
                if (!salt.isNullOrBlank() && salt != DEFAULT_KEY_SALT) {
                    appendQueryParameter(PARAM_SALT, salt)
                }
            }.build()
            .toString()

    /** A resolved Deezer stream recovered from a [buildUri] URI. */
    data class StreamRef(
        val url: Uri,
        val trackId: String,
        val salt: String,
    )

    /** The CDN URL, track id and key salt carried by a [buildUri] URI, or null when [uri] is not one. */
    fun parseUri(uri: Uri): StreamRef? {
        if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null
        val url = uri.getQueryParameter(PARAM_URL)?.takeIf { it.isNotBlank() } ?: return null
        val trackId = uri.getQueryParameter(PARAM_KEY)?.takeIf { it.isNotBlank() } ?: return null
        val salt = uri.getQueryParameter(PARAM_SALT)?.takeIf { it.isNotBlank() } ?: DEFAULT_KEY_SALT
        return StreamRef(url.toUri(), trackId, salt)
    }
}
