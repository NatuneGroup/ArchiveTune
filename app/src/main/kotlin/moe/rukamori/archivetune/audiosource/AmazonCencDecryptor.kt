/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.audiosource

import timber.log.Timber
import java.nio.Buffer
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * On-device decryptor for Amazon Music's CENC-protected fragmented MP4 streams (FLAC/Opus in MP4).
 *
 * Amazon's instance API (as used by monochrome.tf) returns a `stream_url` pointing at a
 * CENC-encrypted (scheme `cenc`, AES-128-CTR) fragmented MP4 plus a raw `decryption_key`. The web
 * player hands the manifest + key to the browser's EME/ClearKey stack, which decrypts natively.
 * This app has no EME, so we replicate the decryption ourselves.
 *
 * Because AES-CTR is length-preserving, we can decrypt every media sample *in place* and then simply
 * neutralize the protection metadata rather than rebuilding the file:
 *   - the `moov` sample entry `enca`/`encv` is renamed back to its original codec (`fLaC`/`Opus`),
 *   - the protection child box `sinf`, every `pssh`, the per-fragment `senc`/`saiz`/`saio` boxes
 *     and any `seig` sample-group boxes are renamed to `free` (an ignored box).
 * Every byte offset (`sidx`, `trun` data offsets, sample sizes) stays identical, so no index needs
 * recomputation. The result is a standard unencrypted FLAC/Opus-in-MP4 that ExoPlayer plays as a
 * plain progressive stream.
 *
 * All parsing is confined to the well-structured `moov`/`moof` metadata containers; `mdat` bytes are
 * only ever touched at the exact sample ranges computed from `trun`. Any structural anomaly causes
 * the whole operation to fail (return null) so the caller can fall through to another source
 * instead of playing garbage.
 */
object AmazonCencDecryptor {
    private const val TAG = "AmazonCenc"

    private data class Box(
        val type: String,
        val start: Int,
        val headerSize: Int,
        val size: Long,
    ) {
        val end: Int get() = (start + size).toInt() // exclusive
        val contentStart: Int get() = start + headerSize
    }

    /**
     * Absolute-index view over a heap or memory-mapped buffer. Positions are moved only on
     * duplicates, through [Buffer], because the covariant `ByteBuffer.position(int)` overload does
     * not exist on older Android runtimes.
     */
    private class Bytes(
        private val buffer: ByteBuffer,
    ) {
        val size: Int = buffer.limit()

        operator fun get(index: Int): Byte = buffer.get(index)

        operator fun set(
            index: Int,
            value: Byte,
        ) {
            buffer.put(index, value)
        }

        fun read(
            offset: Int,
            length: Int,
        ): ByteArray {
            val out = ByteArray(length)
            val view = buffer.duplicate()
            (view as Buffer).position(offset)
            view.get(out)
            return out
        }

        fun write(
            offset: Int,
            bytes: ByteArray,
        ) {
            val view = buffer.duplicate()
            (view as Buffer).position(offset)
            view.put(bytes)
        }

        fun ascii(offset: Int): String = String(read(offset, 4), Charsets.US_ASCII)
    }

    /** Container boxes whose children start immediately after the box header. */
    private val PLAIN_CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "mvex", "moof", "traf", "edts", "dinf")

    /**
     * Decrypts [data] in place. Returns the codec string ("flac"/"opus") on success, or null if the
     * input is not a recognizable CENC MP4 (in which case [data] may be partially modified and must
     * not be used).
     */
    fun decryptInPlace(
        data: ByteArray,
        keyHex: String,
    ): String? = decryptInPlace(ByteBuffer.wrap(data), keyHex)

    /**
     * Same as the array overload, for a buffer that is usually a read-write mapping of the
     * downloaded file: a hi-res track is far larger than the heap should be asked to hold.
     */
    fun decryptInPlace(
        buffer: ByteBuffer,
        keyHex: String,
    ): String? {
        val key = parseKey(keyHex)
        if (key == null) {
            Timber.tag(TAG).w("Invalid CENC key (need 16 bytes of hex, got %d chars)", keyHex.length)
            return null
        }
        return runCatching { decryptInternal(Bytes(buffer), key) }
            .getOrElse {
                Timber.tag(TAG).w(it, "CENC decrypt failed")
                null
            }
    }

    private fun decryptInternal(
        data: Bytes,
        key: ByteArray,
    ): String? {
        val topBoxes = readBoxes(data, 0, data.size)
        if (topBoxes.lastOrNull()?.end != data.size) {
            Timber.tag(TAG).w("Truncated or malformed MP4 (top-level boxes do not span the file)")
            return null
        }
        val moov = topBoxes.firstOrNull { it.type == "moov" } ?: run {
            Timber.tag(TAG).w("No moov box")
            return null
        }

        // --- Parse the sample encryption defaults + original codec from moov ---
        val stsdPath = listOf("trak", "mdia", "minf", "stbl", "stsd")
        val tenc = findDescendant(data, moov, stsdPath, "tenc", deep = true)
        if (tenc == null) {
            Timber.tag(TAG).w("No tenc box; stream may be unencrypted")
            return null
        }
        // Only the AES-CTR whole-block scheme is implemented. `cbcs`/`cbc1` (AES-CBC) and `cens`
        // (CTR with a crypt/skip pattern) would "decrypt" into noise that still plays as audio.
        val schm = findDescendant(data, moov, stsdPath, "schm", deep = true)
        val scheme = schm?.let { data.ascii(it.contentStart + 4) }
        if (scheme != null && scheme != "cenc") {
            Timber.tag(TAG).w("Unsupported protection scheme %s", scheme)
            return null
        }
        val tencInfo = parseTenc(data, tenc)
        if (!tencInfo.isProtected || (tencInfo.perSampleIvSize != 8 && tencInfo.perSampleIvSize != 16)) {
            Timber.tag(TAG).w(
                "Unsupported tenc defaults (protected=%s, ivSize=%d)",
                tencInfo.isProtected,
                tencInfo.perSampleIvSize,
            )
            return null
        }

        // Locate the encrypted audio sample entry (enca) and its original format (frma).
        val stsd = findDescendant(data, moov, listOf("trak", "mdia", "minf", "stbl"), "stsd", deep = false)
            ?: return null
        // stsd is a FullBox (4) + entry_count (4) before the first sample entry.
        val sampleEntry = readBoxes(data, stsd.contentStart + 8, stsd.end).firstOrNull()
            ?: return null
        val codec = neutralizeSampleEntry(data, sampleEntry) ?: return null
        val defaultSampleSize = trexDefaultSampleSize(data, moov)

        // Neutralize any pssh boxes in moov.
        renameChildrenMatching(data, moov, setOf("pssh"))

        // --- Decrypt every fragment ---
        var fragments = 0
        var samplesDecrypted = 0
        for (box in topBoxes) {
            if (box.type != "moof") continue
            val samples = decryptFragment(data, box, key, tencInfo.perSampleIvSize, defaultSampleSize)
            if (samples < 0) {
                Timber.tag(TAG).w("Fragment at %d could not be decrypted", box.start)
                return null
            }
            fragments++
            samplesDecrypted += samples
        }
        if (fragments == 0 || samplesDecrypted == 0) {
            Timber.tag(TAG).w("No encrypted fragments found")
            return null
        }
        Timber.tag(TAG).d("Decrypted %d samples across %d fragment(s), codec=%s", samplesDecrypted, fragments, codec)
        return codec
    }

    private data class TencInfo(
        val isProtected: Boolean,
        val perSampleIvSize: Int,
    )

    private fun parseTenc(
        data: Bytes,
        tenc: Box,
    ): TencInfo {
        // FullBox version/flags (4). Then: reserved(1), reserved/blocks(1), isProtected(1),
        // perSampleIvSize(1), default_KID(16), [constant IV if perSampleIvSize==0].
        val base = tenc.contentStart + 4
        val isProtected = data[base + 2].toInt() and 0xFF
        val perSampleIvSize = data[base + 3].toInt() and 0xFF
        return TencInfo(isProtected == 1, perSampleIvSize)
    }

    /** Offset of the first child box inside an audio sample entry, by sound-description version. */
    private fun sampleEntryChildStart(
        data: Bytes,
        entry: Box,
    ): Int {
        // reserved(6) + data_reference_index(2), then the version field of the sound description.
        val version = readU16(data, entry.contentStart + 8)
        return entry.contentStart + 28 +
            when (version) {
                1 -> 16
                2 -> 36
                else -> 0
            }
    }

    /**
     * Renames an `enca`/`encv` sample entry back to its original codec (read from the child `frma`
     * box) and renames the protection `sinf` child to `free`. Returns the codec label, or null when
     * the original format cannot be established or would not be playable.
     */
    private fun neutralizeSampleEntry(
        data: Bytes,
        entry: Box,
    ): String? {
        if (entry.type != "enca" && entry.type != "encv") {
            // Already unencrypted (unexpected here, but be lenient).
            return entry.type.trim().lowercase()
        }
        val children = readBoxes(data, sampleEntryChildStart(data, entry), entry.end)
        val sinf = children.firstOrNull { it.type == "sinf" }
        val frma = sinf?.let { readBoxes(data, it.contentStart, it.end).firstOrNull { box -> box.type == "frma" } }
        if (sinf == null || frma == null) {
            Timber.tag(TAG).w("Encrypted sample entry without sinf/frma")
            return null
        }
        val format = data.ascii(frma.contentStart)
        // Media3 builds the FLAC decoder's stream header from dfLa; without it the track has no
        // STREAMINFO and fails to decode, so give the next source a chance instead.
        if (format == "fLaC" && children.none { it.type == "dfLa" }) {
            Timber.tag(TAG).w("FLAC sample entry has no dfLa box")
            return null
        }
        writeType(data, sinf, "free")
        writeType(data, entry, format)
        return when (format.trim().lowercase()) {
            "flac", "fla" -> "flac"
            "opus" -> "opus"
            else -> format.trim().lowercase()
        }
    }

    /** `moov/mvex/trex` default_sample_size, the last fallback when neither trun nor tfhd has one. */
    private fun trexDefaultSampleSize(
        data: Bytes,
        moov: Box,
    ): Long? {
        val mvex = childrenOf(data, moov).firstOrNull { it.type == "mvex" } ?: return null
        val trex = childrenOf(data, mvex).firstOrNull { it.type == "trex" } ?: return null
        // FullBox(4) + track_ID(4) + default_sample_description_index(4) + default_sample_duration(4).
        return readU32(data, trex.contentStart + 16)
    }

    /**
     * Decrypts all samples in one `moof`+`mdat` fragment. Returns the number of samples decrypted,
     * or -1 when the fragment cannot be decrypted completely.
     */
    private fun decryptFragment(
        data: Bytes,
        moof: Box,
        key: ByteArray,
        perSampleIvSize: Int,
        trexDefaultSampleSize: Long?,
    ): Int {
        // A pssh left in a moof reaches Media3 as DrmInitData and makes the clear track unplayable.
        renameChildrenMatching(data, moof, setOf("pssh"))
        var decrypted = 0
        for (traf in childrenOf(data, moof)) {
            if (traf.type != "traf") continue
            val children = childrenOf(data, traf)
            val tfhd = children.firstOrNull { it.type == "tfhd" } ?: return -1
            val truns = children.filter { it.type == "trun" }
            if (truns.isEmpty()) continue
            val senc = children.firstOrNull { it.type == "senc" } ?: run {
                Timber.tag(TAG).w("Track fragment without senc")
                return -1
            }

            val tfhdInfo = parseTfhd(data, tfhd)
            val defaultSampleSize = tfhdInfo.defaultSampleSize ?: trexDefaultSampleSize
            val sencData = parseSenc(data, senc, perSampleIvSize) ?: return -1

            // Anchor for sample data: base_data_offset if present, else moof start (default-base-is-moof).
            val anchor = tfhdInfo.baseDataOffset ?: moof.start.toLong()
            var nextRunPos = anchor
            var sampleIndex = 0
            for (trun in truns) {
                val run = parseTrun(data, trun, defaultSampleSize) ?: return -1
                var samplePos = run.dataOffset?.let { anchor + it } ?: nextRunPos
                for (size in run.sampleSizes) {
                    if (sampleIndex >= sencData.ivs.size) {
                        Timber.tag(TAG).w("senc lists fewer samples than trun")
                        return -1
                    }
                    if (size < 0 || samplePos < 0 || samplePos + size > data.size) {
                        Timber.tag(TAG).w("Sample %d lies outside the file", sampleIndex)
                        return -1
                    }
                    val subsamples = sencData.subsamples[sampleIndex]
                    if (!decryptSample(data, samplePos.toInt(), size, key, sencData.ivs[sampleIndex], subsamples)) {
                        Timber.tag(TAG).w("Subsample map of sample %d does not fit the sample", sampleIndex)
                        return -1
                    }
                    samplePos += size
                    sampleIndex++
                }
                nextRunPos = samplePos
            }
            decrypted += sampleIndex

            // Neutralize the per-fragment protection boxes so ExoPlayer treats samples as clear. A
            // `seig` sample group would otherwise re-declare the samples encrypted on its own.
            for (child in children) {
                when (child.type) {
                    "senc", "saiz", "saio" -> writeType(data, child, "free")
                    "sbgp", "sgpd" -> if (data.ascii(child.contentStart + 4) == "seig") writeType(data, child, "free")
                }
            }
        }
        return decrypted
    }

    /** Decrypts one sample in place; false when its subsample map overruns the sample. */
    private fun decryptSample(
        data: Bytes,
        samplePos: Int,
        size: Int,
        key: ByteArray,
        iv: ByteArray,
        subsamples: List<Subsample>,
    ): Boolean {
        if (size <= 0) return true
        val cipher = newCtrCipher(key, iv)
        if (subsamples.isEmpty()) {
            // Whole-sample encryption (typical for audio).
            data.write(samplePos, cipher.doFinal(data.read(samplePos, size)))
            return true
        }
        if (subsamples.sumOf { it.clearBytes.toLong() + it.protectedBytes } > size) return false
        // Subsample encryption: the protected spans of one sample form a single CTR stream.
        var pos = samplePos
        for (ss in subsamples) {
            pos += ss.clearBytes
            if (ss.protectedBytes > 0) {
                val out = cipher.update(data.read(pos, ss.protectedBytes))
                if (out == null || out.size != ss.protectedBytes) return false
                data.write(pos, out)
                pos += ss.protectedBytes
            }
        }
        return true
    }

    private data class TfhdInfo(
        val baseDataOffset: Long?,
        val defaultSampleSize: Long?,
    )

    private fun parseTfhd(
        data: Bytes,
        tfhd: Box,
    ): TfhdInfo {
        val flags = readU24(data, tfhd.contentStart + 1)
        var pos = tfhd.contentStart + 4
        pos += 4 // track_ID
        var baseDataOffset: Long? = null
        if (flags and 0x000001 != 0) {
            baseDataOffset = readU64(data, pos)
            pos += 8
        }
        if (flags and 0x000002 != 0) pos += 4 // sample_description_index
        if (flags and 0x000008 != 0) pos += 4 // default_sample_duration
        var defaultSampleSize: Long? = null
        if (flags and 0x000010 != 0) {
            defaultSampleSize = readU32(data, pos)
        }
        return TfhdInfo(baseDataOffset, defaultSampleSize)
    }

    private class TrunInfo(
        val dataOffset: Long?,
        val sampleSizes: IntArray,
    )

    private fun parseTrun(
        data: Bytes,
        trun: Box,
        defaultSampleSize: Long?,
    ): TrunInfo? {
        val flags = readU24(data, trun.contentStart + 1)
        var pos = trun.contentStart + 4
        val sampleCount = readU32(data, pos).toInt()
        pos += 4
        var dataOffset: Long? = null
        if (flags and 0x000001 != 0) {
            dataOffset = readS32(data, pos)
            pos += 4
        }
        if (flags and 0x000004 != 0) pos += 4 // first_sample_flags

        val hasDuration = flags and 0x000100 != 0
        val hasSize = flags and 0x000200 != 0
        val hasFlags = flags and 0x000400 != 0
        val hasCto = flags and 0x000800 != 0
        if (!hasSize && defaultSampleSize == null) {
            Timber.tag(TAG).w("trun without sample sizes and no default size")
            return null
        }
        val perSample = 4 * listOf(hasDuration, hasSize, hasFlags, hasCto).count { it }
        if (sampleCount < 0 || pos.toLong() + sampleCount.toLong() * perSample > trun.end) return null

        val sizes = IntArray(sampleCount)
        for (i in 0 until sampleCount) {
            if (hasDuration) pos += 4
            if (hasSize) {
                sizes[i] = readU32(data, pos).toInt()
                pos += 4
            } else {
                sizes[i] = defaultSampleSize!!.toInt()
            }
            if (hasFlags) pos += 4
            if (hasCto) pos += 4
        }
        return TrunInfo(dataOffset, sizes)
    }

    private data class Subsample(
        val clearBytes: Int,
        val protectedBytes: Int,
    )

    private data class SencData(
        val ivs: List<ByteArray>,
        val subsamples: List<List<Subsample>>,
    )

    private fun parseSenc(
        data: Bytes,
        senc: Box,
        defaultIvSize: Int,
    ): SencData? {
        val flags = readU24(data, senc.contentStart + 1)
        var pos = senc.contentStart + 4
        var ivSize = defaultIvSize
        if (flags and 0x000001 != 0) {
            // override_TrackEncryptionBox_parameters: AlgorithmID(3), IV_size(1), KID(16).
            ivSize = data[pos + 3].toInt() and 0xFF
            pos += 20
        }
        if (ivSize != 8 && ivSize != 16) return null
        val sampleCount = readU32(data, pos).toInt()
        pos += 4
        val useSubsamples = flags and 0x000002 != 0
        if (sampleCount < 0 || pos.toLong() + sampleCount.toLong() * ivSize > senc.end) return null
        val ivs = ArrayList<ByteArray>(sampleCount)
        val subs = ArrayList<List<Subsample>>(sampleCount)
        for (i in 0 until sampleCount) {
            // An 8-byte IV is the high half of the counter block; the low half starts at zero.
            val iv = ByteArray(16)
            for (b in 0 until ivSize) iv[b] = data[pos + b]
            pos += ivSize
            ivs.add(iv)
            if (useSubsamples) {
                val ssCount = readU16(data, pos)
                pos += 2
                val list = ArrayList<Subsample>(ssCount)
                for (j in 0 until ssCount) {
                    val clear = readU16(data, pos)
                    pos += 2
                    val prot = readU32(data, pos)
                    pos += 4
                    if (prot > Int.MAX_VALUE) return null
                    list.add(Subsample(clear, prot.toInt()))
                }
                subs.add(list)
            } else {
                subs.add(emptyList())
            }
            if (pos > senc.end) return null
        }
        return SencData(ivs, subs)
    }

    // --- AES-CTR ---

    private fun newCtrCipher(
        key: ByteArray,
        iv16: ByteArray,
    ): Cipher {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv16))
        return cipher
    }

    // --- Box helpers (all reads big-endian) ---

    private fun readBox(
        data: Bytes,
        start: Int,
    ): Box? {
        if (start + 8 > data.size) return null
        var size = readU32(data, start)
        var headerSize = 8
        val type = data.ascii(start + 4)
        if (size == 1L) {
            if (start + 16 > data.size) return null
            size = readU64(data, start + 8)
            headerSize = 16
        } else if (size == 0L) {
            size = (data.size - start).toLong()
        }
        if (size < headerSize || start + size > data.size) return null
        return Box(type, start, headerSize, size)
    }

    private fun readBoxes(
        data: Bytes,
        start: Int,
        end: Int,
    ): List<Box> {
        val boxes = ArrayList<Box>()
        var pos = start
        while (pos + 8 <= end) {
            val box = readBox(data, pos) ?: break
            if (box.end > end) break
            boxes.add(box)
            pos = box.end
        }
        return boxes
    }

    /**
     * Finds a descendant box by walking [path] container types from [root], then locating [target].
     * When [deep] is true and the direct path lookup fails, falls back to a recursive search through
     * known container boxes (used for `tenc`/`schm`, nested under `stsd/enca/sinf[/schi]`).
     */
    private fun findDescendant(
        data: Bytes,
        root: Box,
        path: List<String>,
        target: String,
        deep: Boolean,
    ): Box? {
        var current = root
        for (type in path) {
            val next = childrenOf(data, current).firstOrNull { it.type == type } ?: return if (deep) deepFind(data, root, target) else null
            current = next
        }
        val direct = childrenOf(data, current).firstOrNull { it.type == target }
        if (direct != null) return direct
        return if (deep) deepFind(data, current, target) else null
    }

    private fun deepFind(
        data: Bytes,
        root: Box,
        target: String,
    ): Box? {
        for (child in childrenOf(data, root)) {
            if (child.type == target) return child
            val nested = deepFind(data, child, target)
            if (nested != null) return nested
        }
        return null
    }

    /** Returns children of a container box, accounting for special header layouts. */
    private fun childrenOf(
        data: Bytes,
        box: Box,
    ): List<Box> {
        val childStart =
            when (box.type) {
                in PLAIN_CONTAINERS -> box.contentStart
                "stsd" -> box.contentStart + 8 // FullBox(4) + entry_count(4)
                "enca", "encv" -> sampleEntryChildStart(data, box)
                "sinf", "schi" -> box.contentStart
                else -> return emptyList()
            }
        return readBoxes(data, childStart, box.end)
    }

    /** Renames any direct-child boxes of [parent] whose type is in [types] to `free`. */
    private fun renameChildrenMatching(
        data: Bytes,
        parent: Box,
        types: Set<String>,
    ) {
        for (child in childrenOf(data, parent)) {
            if (child.type in types) writeType(data, child, "free")
        }
    }

    private fun writeType(
        data: Bytes,
        box: Box,
        newType: String,
    ) {
        val bytes = newType.toByteArray(Charsets.US_ASCII)
        for (i in 0 until 4) data[box.start + 4 + i] = bytes[i]
    }

    private fun readU16(
        data: Bytes,
        pos: Int,
    ): Int = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)

    private fun readU24(
        data: Bytes,
        pos: Int,
    ): Int = ((data[pos].toInt() and 0xFF) shl 16) or ((data[pos + 1].toInt() and 0xFF) shl 8) or (data[pos + 2].toInt() and 0xFF)

    private fun readU32(
        data: Bytes,
        pos: Int,
    ): Long =
        ((data[pos].toLong() and 0xFF) shl 24) or
            ((data[pos + 1].toLong() and 0xFF) shl 16) or
            ((data[pos + 2].toLong() and 0xFF) shl 8) or
            (data[pos + 3].toLong() and 0xFF)

    private fun readS32(
        data: Bytes,
        pos: Int,
    ): Long = readU32(data, pos).toInt().toLong()

    private fun readU64(
        data: Bytes,
        pos: Int,
    ): Long {
        var result = 0L
        for (i in 0 until 8) result = (result shl 8) or (data[pos + i].toLong() and 0xFF)
        return result
    }

    /**
     * The instance API hands out the bare 32-hex content key; shaka/mp4decrypt-style `KID:KEY`
     * pairs are accepted too, keeping only the key.
     */
    private fun parseKey(raw: String): ByteArray? {
        val hex = raw.trim().substringAfterLast(':').removePrefix("0x").filterNot { it.isWhitespace() }
        if (hex.length != 32) return null
        val out = ByteArray(16)
        for (i in 0 until 16) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
