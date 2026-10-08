/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * QQ Music's encrypted audio containers, decrypted on-device into the plain file they wrap.
 * Only files this account is served are ever touched: the app asks Tencent's url-minter for a
 * track, and if what comes back is a protected container rather than a plain FLAC/MP3 the bytes are
 * decrypted locally so the player can read them. Nothing is uploaded, nothing is re-shared, and the
 * decrypted file never leaves the app's own cache directory.
 * Two container families exist and they are told apart by where the key lives, not by the
 * extension:
 * - **QMC1** (`qmc0`, `qmc3`, `qmcflac`, `qmcogg`, …) is self-contained. The key is a fixed
 * keystream indexed by byte offset, so any tool that knows the scheme can read it, and the whole
 * file is XORed with that keystream.
 * - **QMC2** (`mflac`, `mgg`, `mgg1`, …) is keyed per file, and the tail says where that key is.
 * The older layouts carry it in the file — behind the literal `QTag` marker or behind a trailing
 * little-endian size — and those decrypt on their own. The current PC layout and the Android
 * `STag` one deliberately carry metadata only, so those payloads are decrypted with the key the
 * service disclosed alongside the resource, and are refused when it disclosed none rather than
 * guessed at.
 * The payload is therefore treated as opaque until a footer key is found or the fixed keystream is
 * applied, and the result is only accepted when the decrypted bytes actually start with a container
 * signature. A wrong key produces noise, not a plausible file, so that check is what stands between
 * a key-derivation mistake and garbage sent to the decoder.
 * The schemes here are the documented behaviour of the open-source QQ Music decryptors named in
 * `docs/QQ_MUSIC.md`; the tests that pin them carry the same upstream fixtures.
 */



package moe.rukamori.archivetune.qqmusic

import java.util.Base64
import kotlin.math.abs
import kotlin.math.tan
import timber.log.Timber

/** The audio container a decrypted payload turned out to be. */
internal enum class QmcContainer(
    val mimeType: String,
    val extension: String,
) {
    FLAC("audio/flac", "flac"),
    OGG("audio/ogg", "ogg"),
    MP3("audio/mpeg", "mp3"),
    M4A("audio/mp4", "m4a"),
    WAV("audio/wav", "wav"),
    ;

    companion object {
        /**
         * Identifies the container from its leading bytes. These are fixed file headers, so a
         * payload that decrypts to something else is rejected rather than handed to the decoder.
         */
        fun sniff(data: ByteArray): QmcContainer? {
            if (data.size < 8) return null
            fun at(offset: Int, text: String) = text.indices.all { data[offset + it] == text[it].code.toByte() }
            return when {
                at(0, "fLaC") -> FLAC
                at(0, "OggS") -> OGG
                at(0, "RIFF") -> WAV
                at(0, "ID3") -> MP3
                at(4, "ftyp") -> M4A
                (data[0].toInt() and 0xFF) == 0xFF && (data[1].toInt() and 0xE0) == 0xE0 -> MP3
                else -> null
            }
        }
    }
}

/** The codec inside an Ogg container, which QQ Music serves as both Vorbis and Opus. */
internal fun oggCodec(data: ByteArray): String {
    val head = String(data, 0, minOf(data.size, 256), Charsets.ISO_8859_1)
    return if (head.contains("OpusHead")) "opus" else "vorbis"
}

/** True when this array begins with exactly [prefix]. */
internal fun ByteArray.startsWithBytes(prefix: ByteArray): Boolean {
    if (size < prefix.size) return false
    for (index in prefix.indices) {
        if (this[index] != prefix[index]) return false
    }
    return true
}

/**
 * QQ Music's fixed-keystream container (QMC1).
 *
 * The keystream is a 256-byte box indexed by `offset² + 27`, wrapping every `0x7FFF` bytes. The
 * table and the index arithmetic were cross-checked against two independent open-source
 * implementations — one that walks an 8×7 seed map into the same values, one that tabulates the
 * 256 bytes directly — and they agree for every offset, including the wrap.
 */
internal object QmcStaticCipher {
    /** The keystream restarts every 0x7FFF bytes; offsets beyond one wrap are reduced first. */
    const val PERIOD = 0x7FFF

    private const val STATIC_BOX_HEX =
        "77483273def2c0c895ec30b251c3e1a09ee69dcffa7f14d1ceb8dcc34a6793d6" +
            "28c29170ca8da2a4f00861907e6fa2e0ebae3eb667c792f491b5f66c5e8440f7" +
            "f31b027fd5ab418928f425cc5211ad4368a6418b84b5ff2c924a26d8476a7c95" +
            "61cce6cbbb3f47588975c375a1d9afcc087317dcaa9aa21641d8a206c68bfc66" +
            "349fcf1823a00a74e72b277092e9af37e68ca7bc62659cc208c988b3f343ac74" +
            "2c0fd4afa1c30164954e489ff43578957a39d66aa06d40e84fa8ef111df31b3f" +
            "3f07dd6f5b193019fbef0e37f00ecd1649fe5347131abda4f14019600eed6809" +
            "065f4dcf3d1afe2077e4d9daf9a42b761c71db00bcfd0c6ca547f7f600794a11"

    private val box: ByteArray =
        ByteArray(STATIC_BOX_HEX.length / 2) { index ->
            STATIC_BOX_HEX.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }

    /** The keystream byte that cancels the cipher at [offset]. */
    fun mask(offset: Int): Int {
        val index = if (offset > PERIOD) offset % PERIOD else offset
        val square = index.toLong() * index.toLong()
        return box[((square + 27L) and 0xFFL).toInt()].toInt() and 0xFF
    }

    /** Applies the keystream to [data] in place, starting at [offset]. */
    fun decrypt(
        data: ByteArray,
        offset: Int = 0,
    ) {
        for (index in data.indices) {
            data[index] = (data[index].toInt() xor mask(offset + index)).toByte()
        }
    }
}

/**
 * QQ Music's short-key stream cipher (QMC2 with a key of at most 300 bytes).
 *
 * The key is first compressed to one 128-byte table — each slot takes a key byte chosen by
 * `slot² + 71214` and rotates it by that byte's own index — and the file is then XORed with that
 * table read at `offset % 128`. Folding the offset into the table here rather than into the key
 * lookup is what makes the cipher well defined for a key of any length; for the power-of-two key
 * lengths that are actually issued the two forms agree byte for byte, and the published fixtures
 * for both forms are pinned in the tests.
 */
internal class QmcMapCipher(
    key: ByteArray,
) {
    private val table: ByteArray

    init {
        require(key.isNotEmpty()) { "a map cipher needs a key" }
        table = ByteArray(TABLE_SIZE) { index -> compressSlot(key, index) }
    }

    /** The keystream byte that cancels the cipher at [offset]. */
    fun mask(offset: Int): Int {
        val index = if (offset > QmcStaticCipher.PERIOD) offset % QmcStaticCipher.PERIOD else offset
        return table[index % TABLE_SIZE].toInt() and 0xFF
    }

    /** Applies the keystream to [data] in place, starting at [offset]. */
    fun decrypt(
        offset: Int,
        data: ByteArray,
    ) {
        var position = offset
        for (index in data.indices) {
            data[index] = (data[index].toInt() xor mask(position)).toByte()
            position++
        }
    }

    companion object {
        private const val TABLE_SIZE = 128

        private fun compressSlot(
            key: ByteArray,
            slot: Int,
        ): Byte {
            val source = ((slot.toLong() * slot.toLong() + 71214L) % key.size).toInt()
            val value = key[source].toInt() and 0xFF
            val rotation = (source + 4) and 0b111
            return (((value shl rotation) or (value ushr rotation)) and 0xFF).toByte()
        }
    }
}

/**
 * QQ Music's long-key stream cipher (QMC2 with a key longer than 300 bytes): a modified RC4.
 *
 * The first 128 bytes use a plain keyed XOR; everything after runs through an RC4 keystream that is
 * restarted per 5120-byte segment and primed with a count derived from the key. [decrypt] keeps the
 * reference's segment walk, so a caller may decrypt the file in blocks and get the same bytes a
 * single pass would.
 */
internal class QmcRc4Cipher(
    private val key: ByteArray,
) {
    private val size = key.size
    private val hash = hashBase(key)
    private val seedBox = ByteArray(size) { (it and 0xFF).toByte() }

    init {
        var j = 0
        for (i in 0 until size) {
            j = (j + (seedBox[i].toInt() and 0xFF) + (key[i].toInt() and 0xFF)) % size
            val swap = seedBox[i]
            seedBox[i] = seedBox[j]
            seedBox[j] = swap
        }
    }

    fun decrypt(
        offset: Int,
        data: ByteArray,
    ) {
        var position = offset
        var index = 0
        var remaining = data.size

        if (position < FIRST_SEGMENT_SIZE) {
            val count = minOf(remaining, FIRST_SEGMENT_SIZE - position)
            firstSegment(position, data, index, count)
            index += count
            remaining -= count
            position += count
        }
        val toAlign = position % OTHER_SEGMENT_SIZE
        if (toAlign != 0) {
            val count = minOf(remaining, OTHER_SEGMENT_SIZE - toAlign)
            otherSegment(position, data, index, count)
            index += count
            remaining -= count
            position += count
        }
        while (remaining > OTHER_SEGMENT_SIZE) {
            otherSegment(position, data, index, OTHER_SEGMENT_SIZE)
            index += OTHER_SEGMENT_SIZE
            remaining -= OTHER_SEGMENT_SIZE
            position += OTHER_SEGMENT_SIZE
        }
        if (remaining > 0) otherSegment(position, data, index, remaining)
    }

    private fun firstSegment(
        offset: Int,
        data: ByteArray,
        from: Int,
        count: Int,
    ) {
        var position = offset
        for (index in from until from + count) {
            val seed = key[position % size].toInt() and 0xFF
            val shift = (segmentKey(position, seed) % size.toULong()).toInt()
            data[index] = (data[index].toInt() xor (key[shift].toInt() and 0xFF)).toByte()
            position++
        }
    }

    private fun otherSegment(
        offset: Int,
        data: ByteArray,
        from: Int,
        count: Int,
    ) {
        val segment = offset / OTHER_SEGMENT_SIZE
        val seed = key[segment % size].toInt() and 0xFF
        val discard = (segmentKey(segment, seed) and 0x1FFuL).toInt() + (offset % OTHER_SEGMENT_SIZE)

        val state = seedBox.copyOf()
        var j = 0
        var k = 0
        repeat(discard) {
            j = (j + 1) % size
            k = ((state[j].toInt() and 0xFF) + k) % size
            val swap = state[j]
            state[j] = state[k]
            state[k] = swap
        }
        for (index in from until from + count) {
            j = (j + 1) % size
            k = ((state[j].toInt() and 0xFF) + k) % size
            val swap = state[j]
            state[j] = state[k]
            state[k] = swap
            val mask =
                state[((state[j].toInt() and 0xFF) + (state[k].toInt() and 0xFF)) % size].toInt() and 0xFF
            data[index] = (data[index].toInt() xor mask).toByte()
        }
    }

    /**
     * The per-segment prime count: a ratio of the key's hash base to the segment's place in the
     * stream, in the widest unsigned type the reference uses.
     *
     * The reference divides without guarding a zero divisor, and Rust's saturating float-to-integer
     * cast turns the resulting infinity into the largest 64-bit value. Callers reduce that value
     * themselves — by the key length for a table index, and to nine bits for a prime count — so the
     * saturation has to be preserved here rather than folded away: the two reductions disagree on
     * it, and the published fixtures for a zero seed byte depend on the nine-bit one.
     */
    private fun segmentKey(
        id: Int,
        seed: Int,
    ): ULong {
        val divisor = (id.toLong() + 1L) * seed.toLong()
        if (divisor == 0L) return ULong.MAX_VALUE
        val value = hash.toDouble() / divisor.toDouble() * 100.0
        return if (value <= 0.0) 0uL else value.toULong()
    }

    companion object {
        private const val FIRST_SEGMENT_SIZE = 0x80
        private const val OTHER_SEGMENT_SIZE = 0x1400

        /**
         * A running product of the key's non-zero bytes, stopping as soon as it can no longer grow —
         * including on the wrap-around the reference's overflow check deliberately keeps.
         */
        fun hashBase(key: ByteArray): Long {
            var hash = 1L
            for (byte in key) {
                val value = byte.toLong() and 0xFFL
                if (value == 0L) continue
                val next = (hash * value) and 0xFFFFFFFFL
                if (next == 0L || next <= hash) break
                hash = next
            }
            return hash
        }
    }
}

/**
 * Tencent's modified TEA, which wraps every QMC2 key the service issues.
 *
 * Two departures from stock TEA: the first block is ECB, while each later block is XORed with the
 * previous *decrypted* block before its own ECB pass, and the whole tail is finally XORed with the
 * ciphertext shifted by one block. The plaintext is framed with a pad-length byte, up to seven
 * padding bytes, a two-byte salt and seven trailing zero bytes; those trailing zeros double as the
 * check that tells a right key from a wrong one.
 */
internal object TcTea {
    private const val ROUNDS = 16
    private const val DELTA = 0x9E3779B9.toInt()
    private const val SALT_LEN = 2
    private const val ZERO_LEN = 7
    private const val FIXED_PADDING_LEN = 1 + SALT_LEN + ZERO_LEN

    /** Salt for the per-file TEA key; part of the scheme, not a tunable. */
    private const val KEY_SALT = 106

    fun decrypt(
        encrypted: ByteArray,
        key: ByteArray,
    ): ByteArray? {
        if (key.size < 16) return null
        val length = encrypted.size
        if (length < FIXED_PADDING_LEN || length % 8 != 0) return null

        val schedule = IntArray(4) { readU32Be(key, it * 4) }
        val buffer = encrypted.copyOf()
        decryptBlock(buffer, 0, schedule)
        var offset = 8
        while (offset < length) {
            for (index in 0 until 8) {
                buffer[offset + index] =
                    (buffer[offset + index].toInt() xor buffer[offset + index - 8].toInt()).toByte()
            }
            decryptBlock(buffer, offset, schedule)
            offset += 8
        }
        for (index in 0 until length - 8) {
            buffer[8 + index] = (buffer[8 + index].toInt() xor encrypted[index].toInt()).toByte()
        }

        val padLength = buffer[0].toInt() and 0x07
        val start = 1 + padLength + SALT_LEN
        val end = length - ZERO_LEN
        if (start > end) return null
        for (index in end until length) {
            if (buffer[index].toInt() != 0) return null
        }
        return buffer.copyOfRange(start, end)
    }

    /** `simple[i] = truncate(100 * |tan(seed + i / 10)|)`, in single precision as the scheme does. */
    fun simpleMakeKey(
        seed: Int,
        size: Int,
    ): ByteArray =
        ByteArray(size) { index ->
            val value = seed.toFloat() + index.toFloat() * 0.1f
            (100.0f * abs(tan(value))).toInt().toByte()
        }

    /** The 16-byte TEA key: the salt bytes interleaved with the file's own 8-byte header. */
    fun deriveTeaKey(header: ByteArray): ByteArray {
        val simple = simpleMakeKey(KEY_SALT, 8)
        val teaKey = ByteArray(16)
        for (index in 0 until 8) {
            teaKey[index * 2] = simple[index]
            teaKey[index * 2 + 1] = header[index]
        }
        return teaKey
    }

    private fun decryptBlock(
        buffer: ByteArray,
        offset: Int,
        schedule: IntArray,
    ) {
        var y = readU32Be(buffer, offset)
        var z = readU32Be(buffer, offset + 4)
        var sum = DELTA * ROUNDS
        repeat(ROUNDS) {
            z -= round(y, sum, schedule[2], schedule[3])
            y -= round(z, sum, schedule[0], schedule[1])
            sum -= DELTA
        }
        writeU32Be(buffer, offset, y)
        writeU32Be(buffer, offset + 4, z)
    }

    private fun round(
        value: Int,
        sum: Int,
        key1: Int,
        key2: Int,
    ): Int = ((value shl 4) + key1) xor (sum + value) xor ((value ushr 5) + key2)

    private fun readU32Be(
        buffer: ByteArray,
        offset: Int,
    ): Int =
        ((buffer[offset].toInt() and 0xFF) shl 24) or
            ((buffer[offset + 1].toInt() and 0xFF) shl 16) or
            ((buffer[offset + 2].toInt() and 0xFF) shl 8) or
            (buffer[offset + 3].toInt() and 0xFF)

    private fun writeU32Be(
        buffer: ByteArray,
        offset: Int,
        value: Int,
    ) {
        buffer[offset] = (value ushr 24).toByte()
        buffer[offset + 1] = (value ushr 16).toByte()
        buffer[offset + 2] = (value ushr 8).toByte()
        buffer[offset + 3] = value.toByte()
    }
}

/**
 * The tail a QMC2 container ends with, and the key it carries if any.
 *
 * Four layouts are recognised, and they are tried in the order the modern clients try them. Two of
 * them — the Android `STag` one and the current PC `musicex` one — deliberately carry *metadata*
 * only: the key is not in those files at all, so their payload can only be decrypted by a key the
 * service disclosed. That is reported as such rather than being run through a cipher that cannot
 * possibly be right.
 */
internal object QmcEkey {
    private const val TAG = "QmcEkey"

    /** `QTag` read as a little-endian word. */
    private const val QTAG_MAGIC = 0x67615451

    /** The older Android layout's size word; anything longer than this is not a key length. */
    private const val V1_MAX_KEY_SIZE = 0x400

    /** The layout that names itself instead: `STag` metadata only, `musicex\0` metadata only. */
    private const val STAG_MAGIC = "STag"
    private const val MUSICEX_MAGIC = "musicex\u0000"
    private const val MUSICEX_VERSION = 1
    private const val MUSICEX_TAG_SIZE = 0xC0

    private const val ENCV2_PREFIX = "QQMusic EncV2,Key:"

    /** Fixed TEA keys for the two passes that unwrap an `EncV2` key. */
    private const val ENCV2_STAGE1_KEY = "386ZJY!@#*$%^&)("
    private const val ENCV2_STAGE2_KEY = "**#!(#$%&^a1cZ,T"

    /** Where a container's key comes from, and therefore whether it is readable at all. */
    enum class FooterKind {
        /** Newer Android layout: a big-endian payload length, then the key in a CSV before `QTag`. */
        QTAG,

        /** Older layout: the key's own length as a little-endian word at the very end. */
        LEGACY_LENGTH,

        /** Android layout carrying metadata only — the key has to come from the service. */
        STAG,

        /** Current PC layout carrying metadata only — the key has to come from the service. */
        MUSICEX,

        /** Nothing recognised, so the whole file is the payload. */
        NONE,
    }

    /** A container's tail: how it is laid out, the key it carries if any, and where the audio ends. */
    data class Footer(
        val kind: FooterKind,
        val keyText: String?,
        val audioLength: Int,
    )

    /**
     * Reads the tail of [data]. Never fails: a file whose tail is not one of the known layouts comes
     * back as [FooterKind.NONE] with the whole file as its payload, which is the correct reading of
     * a container that carries no trailer.
     */
    fun findFooter(data: ByteArray): Footer {
        val whole = Footer(FooterKind.NONE, null, data.size)
        if (data.size < 16) return whole

        if (endsWith(data, STAG_MAGIC)) {
            val payloadLength = readU32Be(data, data.size - 8)
            val footerSize = payloadLength + 8
            if (payloadLength > 0 && footerSize < data.size) {
                return Footer(FooterKind.STAG, null, data.size - footerSize)
            }
            return whole
        }

        if (endsWith(data, MUSICEX_MAGIC)) {
            // The trailer is a little-endian tag size and version immediately before the magic.
            val version = readU32Le(data, data.size - 12)
            val tagSize = readU32Le(data, data.size - 16)
            if (version == MUSICEX_VERSION && tagSize == MUSICEX_TAG_SIZE && tagSize < data.size) {
                return Footer(FooterKind.MUSICEX, null, data.size - tagSize)
            }
            return whole
        }

        val trailing = readU32Le(data, data.size - 4)
        if (trailing == QTAG_MAGIC) {
            val metaSize = readU32Be(data, data.size - 8)
            val endOfMeta = data.size - 8
            if (metaSize <= 0 || metaSize >= endOfMeta) return whole
            val keyStart = endOfMeta - metaSize
            val comma = indexOf(data, ','.code.toByte(), keyStart, endOfMeta)
            if (comma < 0) return whole
            return Footer(
                kind = FooterKind.QTAG,
                keyText = String(data, keyStart, comma - keyStart, Charsets.UTF_8),
                audioLength = keyStart,
            )
        }

        if (trailing in 1..V1_MAX_KEY_SIZE) {
            val keyStart = data.size - 4 - trailing
            if (keyStart < 0) return whole
            return Footer(
                kind = FooterKind.LEGACY_LENGTH,
                keyText = String(data, keyStart, trailing, Charsets.UTF_8),
                audioLength = keyStart,
            )
        }
        return whole
    }

    /**
     * Turns a footer key into the raw bytes the stream ciphers take.
     *
     * A key is base64 of an 8-byte header followed by a TEA-wrapped body, and the body's TEA key is
     * derived from that header. The `EncV2` variant wraps the whole thing in two more TEA passes
     * under fixed keys before base64. If the body does not survive the TEA pass the blob is taken as
     * a raw key, which is how the service hands out a key it generated itself.
     */
    fun derive(ekey: String): ByteArray? {
        val trimmed = ekey.trim().trim('\u0000')
        if (trimmed.isBlank()) return null
        val decoded =
            runCatching { Base64.getDecoder().decode(trimmed) }.getOrElse { error ->
                Timber.tag(TAG).w(error, "QQ Music key was not base64")
                return null
            }
        if (decoded.size < 8) return null

        val unwrapped =
            if (decoded.startsWithBytes(ENCV2_PREFIX.toByteArray(Charsets.US_ASCII))) {
                val stage1 =
                    TcTea.decrypt(
                        decoded.copyOfRange(ENCV2_PREFIX.length, decoded.size),
                        ENCV2_STAGE1_KEY.toByteArray(Charsets.US_ASCII),
                    ) ?: return null
                val stage2 =
                    TcTea.decrypt(stage1, ENCV2_STAGE2_KEY.toByteArray(Charsets.US_ASCII))
                        ?: return null
                val text = String(stage2, Charsets.UTF_8).trim().trim('\u0000')
                runCatching { Base64.getDecoder().decode(text) }.getOrNull() ?: return null
            } else {
                decoded
            }
        if (unwrapped.size < 8) return null

        val header = unwrapped.copyOfRange(0, 8)
        if (unwrapped.size == 8) return header
        val body = TcTea.decrypt(unwrapped.copyOfRange(8, unwrapped.size), TcTea.deriveTeaKey(header))
        return if (body == null) unwrapped else header + body
    }

    private fun indexOf(
        data: ByteArray,
        target: Byte,
        from: Int,
        to: Int,
    ): Int {
        for (index in from until to) {
            if (data[index] == target) return index
        }
        return -1
    }

    /** True when [data] ends with the ASCII [suffix]. */
    private fun endsWith(
        data: ByteArray,
        suffix: String,
    ): Boolean {
        if (data.size < suffix.length) return false
        val start = data.size - suffix.length
        for (index in suffix.indices) {
            if (data[start + index] != suffix[index].code.toByte()) return false
        }
        return true
    }

    private fun readU32Le(
        data: ByteArray,
        offset: Int,
    ): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)

    private fun readU32Be(
        data: ByteArray,
        offset: Int,
    ): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
}

/** A decrypted payload together with the container it turned out to be. */
internal class QmcDecrypted(
    val bytes: ByteArray,
    val container: QmcContainer,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is QmcDecrypted && container == other.container && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + container.hashCode()
}

internal object QmcDecryptor {
    private const val TAG = "QmcDecryptor"

    /**
     * Decrypts an encrypted container into the plain file it wraps.
     *
     * The footer decides everything: where the audio ends, and whether the key is in the file at
     * all. When it is not, [ekey] — the key the service disclosed along with the resource — is what
     * makes the track playable, and without it the payload is refused rather than run through a
     * cipher that cannot be right.
     *
     * [encryptedExtension] is carried for logging only. Returns null when the payload is too small
     * to be a container, when a key is present but cannot be derived, when the container carries no
     * key and none was disclosed, or when the result does not begin with a container signature —
     * never a partial or unverified payload.
     */
    fun decrypt(
        data: ByteArray,
        encryptedExtension: String,
        ekey: String? = null,
    ): QmcDecrypted? {
        if (data.size < 16) return null
        val extension = encryptedExtension.lowercase().let { if (it.startsWith(".")) it else ".$it" }

        val footer = QmcEkey.findFooter(data)
        val keyText = footer.keyText ?: ekey?.trim()?.takeIf { it.isNotEmpty() }
        val plain: ByteArray
        if (keyText != null) {
            val key =
                QmcEkey.derive(keyText) ?: run {
                    Timber.tag(TAG).w("QQ Music container carried a key that could not be derived")
                    return null
                }
            if (footer.audioLength < 16) return null
            plain = data.copyOfRange(0, footer.audioLength)
            if (key.size > 300) QmcRc4Cipher(key).decrypt(0, plain) else QmcMapCipher(key).decrypt(0, plain)
        } else {
            if (footer.kind != QmcEkey.FooterKind.NONE) {
                Timber.tag(TAG).i("QQ Music container %s (%s) carries no key", extension, footer.kind)
                return null
            }
            plain = data.copyOf()
            QmcStaticCipher.decrypt(plain)
        }

        val container = QmcContainer.sniff(plain)
        if (container == null) {
            Timber.tag(TAG).i("QQ Music container %s did not decrypt to a known audio file", extension)
            return null
        }
        Timber.tag(TAG).d("QQ Music container %s decrypted to %s", extension, container)
        return QmcDecrypted(plain, container)
    }
}
