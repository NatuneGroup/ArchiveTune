/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.applemusic

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Builds a playable progressive MP4 out of Apple Music's web-playback HLS asset. */
object AppleMusicVirtualStream {
    const val TAG = "AppleMusicStream"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/145.0.0.0 Safari/537.36"
    private const val DEFAULT_TIMESCALE = 44100L

    /** A CENC key id is always exactly 16 bytes; anything else cannot be a KID. */
    const val KID_BYTES = 16

    private data class Box(
        val type: String,
        val offset: Int,
        val size: Int,
        val headerSize: Int,
    )

    private class ParsedPlaylistInfo(
        val mediaUrl: String,
        val durationsSec: List<Double>,
    )

    /** Result of [build]: the virtual stream bytes plus the raw DRM URI (Apple's `uri` field). */
    class Built(
        val bytes: ByteArray,
        val drmUri: String,
    )

    /** Fetch the playlist, then the fMP4, and assemble the virtual progressive stream. */
    fun build(
        client: OkHttpClient,
        playlistUrl: String,
        kidHex: String?,
    ): Built {
        val playlist = fetch(client, playlistUrl)
        val parsed = parsePlaylist(playlistUrl, playlist.toString(Charsets.UTF_8))
        val mp4 = fetch(client, parsed.mediaUrl)
        val virtual = buildVirtualStream(mp4, parsed, kidHex)
        Log.i(
            TAG,
            "built virtual stream: file=${mp4.size} virtual=${virtual.size} " +
                "fragments=${parsed.durationsSec.size}",
        )
        return Built(bytes = virtual, drmUri = parsed.drmUri)
    }

    /** Fetches just the playlist and extracts the raw EXT-X-KEY URI (Apple license `uri` field). */
    fun drmUri(
        client: OkHttpClient,
        playlistUrl: String,
    ): String = parsePlaylist(playlistUrl, fetch(client, playlistUrl).toString(Charsets.UTF_8)).drmUri

    private fun fetch(
        client: OkHttpClient,
        url: String,
    ): ByteArray {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("fetch failed ${response.code} for $url")
            return response.body?.bytes() ?: throw IOException("empty body for $url")
        }
    }

    private fun walk(
        buf: ByteArray,
        start: Int,
        end: Int,
    ): List<Box> {
        val out = mutableListOf<Box>()
        var off = start
        while (off + 8 <= end) {
            var size = readU32(buf, off)
            val type = String(buf, off + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1) {
                if (off + 16 > end) break
                size = readU64(buf, off + 8).toInt()
                header = 16
            } else if (size == 0) {
                size = end - off
            }
            if (size < 8 || off + size > end) break
            out.add(Box(type, off, size, header))
            off += size
        }
        return out
    }

    private fun readU32(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 24) or
            ((buf[off + 1].toInt() and 0xFF) shl 16) or
            ((buf[off + 2].toInt() and 0xFF) shl 8) or
            (buf[off + 3].toInt() and 0xFF)

    private fun readU64(buf: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (buf[off + i].toLong() and 0xFF)
        return v
    }

    private class ParsedFile(
        val mediaUrl: String,
        val durationsSec: List<Double>,
        val drmUri: String,
    )

    /** Playlist → absolute mp4 URL + per-fragment durations (seconds, from #EXTINF). */
    private fun parsePlaylist(
        playlistUrl: String,
        text: String,
    ): ParsedFile {
        var mediaName: String? = null
        var drmUri: String? = null
        val durations = mutableListOf<Double>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-MAP") && mediaName == null ->
                    Regex("URI=\"([^\"]+)\"").find(line)?.let { mediaName = it.groupValues[1] }
                // The EXT-X-KEY URI is a data: URI carrying the key id. The RAW URI string is
                // what Apple's license endpoint expects as the `uri` field of the exchange.
                line.startsWith("#EXT-X-KEY") && drmUri == null ->
                    Regex("URI=\"([^\"]+)\"").find(line)?.let { drmUri = it.groupValues[1] }
                line.startsWith("#EXTINF") ->
                    Regex("#EXTINF:([0-9.]+)").find(line)?.let {
                        durations += it.groupValues[1].toDoubleOrNull() ?: 0.0
                    }
            }
        }
        if (mediaName == null) {
            mediaName = text.lineSequence().map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
        }
        val name = mediaName ?: throw IOException("playlist has no segments")
        val mediaUrl = playlistUrl.substringBeforeLast('/').trimEnd('/') + "/" + name
        return ParsedFile(mediaUrl, durations, drmUri ?: throw IOException("playlist has no EXT-X-KEY"))
    }

    /** Parse box layout + inject `pssh` (into moov) and `sidx` (after moov). */
    private fun buildVirtualStream(
        mp4: ByteArray,
        playlist: ParsedFile,
        kidHex: String?,
    ): ByteArray {
        val boxes = walk(mp4, 0, mp4.size)
        val moov = boxes.firstOrNull { it.type == "moov" } ?: throw IOException("no moov box")

        val pairs = mutableListOf<Pair<Box, Box>>()
        var i = 0
        val seq = boxes.filter { it.type == "moof" || it.type == "mdat" }
        while (i + 1 < seq.size) {
            if (seq[i].type == "moof" && seq[i + 1].type == "mdat") {
                pairs += seq[i] to seq[i + 1]
                i += 2
            } else {
                i++
            }
        }
        if (pairs.isEmpty()) throw IOException("no moof/mdat fragments")

        val timescale = findBox(mp4, moov, "mdhd")?.let { mdhd ->
            val p = mdhd.offset + mdhd.headerSize
            if (mp4[p].toInt() == 1) readU32(mp4, p + 4 + 16).toLong() else readU32(mp4, p + 4 + 8).toLong()
        }?.takeIf { it > 0 } ?: DEFAULT_TIMESCALE

        val baseTime = findBox(mp4, moov, "moof")?.let { firstMoof ->
            val traf = findBox(mp4, firstMoof, "traf") ?: return@let null
            val tfdt = findBox(mp4, traf, "tfdt") ?: return@let null
            val p = tfdt.offset + tfdt.headerSize
            if (mp4[p].toInt() == 1) readU64(mp4, p + 4) else readU32(mp4, p + 4).toLong()
        } ?: 0L

        // A Widevine challenge is only valid when the pssh carries the real 16-byte tenc KID.
        // Fabricating an all-zero KID instead still yields a structurally valid, playable file
        // that Apple's licence server refuses to issue a key for, so every track decodes to
        // SILENCE with no error logged anywhere. Fail loudly: resolveAppleStream catches this and
        // falls through to the next source, and the reason lands in the log rather than being
        // inaudible.
        val kid = kidHex
            ?.let { hexToBytes(it) }
            ?.takeIf { it.size == KID_BYTES }
            ?: throw IOException(
                "no usable Widevine key id in playlist (kid=${kidHex ?: "absent"}); " +
                    "cannot build a decryptable stream",
            )
        val pssh = buildWidevinePssh(kid)
        val sidx = buildSidx(pairs, playlist.durationsSec, timescale, baseTime)

        // Virtual layout: [before moov][patched moov hdr][pssh][moov children][sidx][rest].
        // The patched header REPLACES the original 8-byte header — copying the header twice
        // shifts everything after moov by 8 bytes and corrupts the box layout.
        val beforeMoov = moov.offset
        val moovChildrenAt = moov.offset + moov.headerSize
        val afterMoov = moov.offset + moov.size
        val newMoovSize = moov.size + pssh.size
        val out = ByteArray(mp4.size + pssh.size + sidx.size)
        var v = 0
        System.arraycopy(mp4, 0, out, v, beforeMoov)
        v += beforeMoov
        out[v++] = (newMoovSize ushr 24).toByte()
        out[v++] = (newMoovSize ushr 16).toByte()
        out[v++] = (newMoovSize ushr 8).toByte()
        out[v++] = newMoovSize.toByte()
        out[v++] = 'm'.code.toByte()
        out[v++] = 'o'.code.toByte()
        out[v++] = 'o'.code.toByte()
        out[v++] = 'v'.code.toByte()
        System.arraycopy(pssh, 0, out, v, pssh.size)
        v += pssh.size
        System.arraycopy(mp4, moovChildrenAt, out, v, moov.size - moov.headerSize)
        v += moov.size - moov.headerSize
        System.arraycopy(sidx, 0, out, v, sidx.size)
        v += sidx.size
        System.arraycopy(mp4, afterMoov, out, v, mp4.size - afterMoov)
        v += mp4.size - afterMoov
        check(v == out.size) { "virtual stream size mismatch: v=$v expected=${out.size}" }
        return out
    }

    /** Depth-limited recursive box search inside [root]'s children. */
    private fun findBox(
        buf: ByteArray,
        root: Box,
        type: String,
    ): Box? {
        val containers = setOf("moov", "trak", "mdia", "minf", "stbl", "moof", "traf")
        var frontier = listOf(root)
        var depth = 0
        while (frontier.isNotEmpty() && depth < 6) {
            val next = mutableListOf<Box>()
            for (container in frontier) {
                for (b in walk(buf, container.offset + container.headerSize, container.offset + container.size)) {
                    if (b.type == type) return b
                    if (b.type in containers) next += b
                }
            }
            frontier = next
            depth++
        }
        return null
    }

    private fun buildWidevinePssh(kid: ByteArray): ByteArray {
        val systemId = byteArrayOf(
            0xED.toByte(), 0xEF.toByte(), 0x8B.toByte(), 0xA9.toByte(),
            0x79.toByte(), 0xD6.toByte(), 0x4A.toByte(), 0xCE.toByte(),
            0xA3.toByte(), 0xC8.toByte(), 0x27.toByte(), 0xDC.toByte(),
            0xD5.toByte(), 0x1D.toByte(), 0x21.toByte(), 0xED.toByte(),
        )
        // The pssh DATA must be a WidevinePsshData protobuf carrying the key id — an empty or
        // wrong-length payload produces a license challenge Apple's server refuses (playback with
        // no sound). Mirrors gamdl's reconstruct_pssh:
        //   field 1 (algorithm) = 1 (AES-CTR): tag 0x08, value 0x01
        //   field 2 (key_ids)   = 16 bytes:    tag 0x12, len 0x10, <kid>
        // The length is written as a single-byte varint, which is only correct for a 16-byte KID —
        // hence the caller's size check, restated here so this stays true if it ever moves.
        require(kid.size == KID_BYTES) { "Widevine KID must be $KID_BYTES bytes, got ${kid.size}" }
        val data = ByteArray(4 + kid.size)
        data[0] = 0x08
        data[1] = 0x01 // algorithm = AESCTR
        data[2] = 0x12 // field 2 (key_ids), wire type 2
        data[3] = kid.size.toByte()
        System.arraycopy(kid, 0, data, 4, kid.size)
        val size = 8 + 4 + 16 + 4 + data.size
        val out = ByteArray(size)
        writeU32(out, 0, size)
        out[4] = 'p'.code.toByte()
        out[5] = 's'.code.toByte()
        out[6] = 's'.code.toByte()
        out[7] = 'h'.code.toByte()
        // version 0 + flags 0 stay zero
        System.arraycopy(systemId, 0, out, 12, 16)
        writeU32(out, 28, data.size)
        System.arraycopy(data, 0, out, 32, data.size)
        return out
    }

    private fun buildSidx(
        fragments: List<Pair<Box, Box>>,
        durationsSec: List<Double>,
        timescale: Long,
        baseTime: Long,
    ): ByteArray {
        val count = fragments.size
        // version 1: 64-bit earliest_presentation_time + first_offset — Apple's tfdt carries
        // large absolute base media decode times that do NOT fit in 32 bits (a v0 sidx with a
        // coerced value breaks ExoPlayer's timeline math, e.g. a "-15d" duration display).
        val size = 40 + 12 * count
        val out = ByteArray(size)
        writeU32(out, 0, size)
        out[4] = 's'.code.toByte()
        out[5] = 'i'.code.toByte()
        out[6] = 'd'.code.toByte()
        out[7] = 'x'.code.toByte()
        out[8] = 1 // version 1
        writeU32(out, 12, 1) // reference_ID
        writeU32(out, 16, timescale.coerceIn(1, Int.MAX_VALUE.toLong()).toInt())
        writeU64(out, 20, baseTime.coerceAtLeast(0))
        writeU64(out, 28, 0) // first_offset: sidx ends where the first moof begins
        // bytes 36..37 reserved(0); 38..39 reference_count
        writeU16(out, 38, count)
        var off = 40
        for ((index, pair) in fragments.withIndex()) {
            val (moof, mdat) = pair
            val referencedSize = moof.size + mdat.size
            val durationSec = durationsSec.getOrNull(index) ?: 0.0
            val duration = (durationSec * timescale).toLong().coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
            // Reference entry layout (3 words — matching ExoPlayer's FragmentedMp4Extractor.parseSidx):
            //   word 1: reference_type(1)=0 | referenced_size(31)
            //   word 2: subsegment_duration  ← the fragment's length in timescale ticks
            //   word 3: starts_with_SAP(1)=1 | SAP_type(3)=1 | SAP_delta_time(28)=0
            // Writing the SAP flags into word 2 (and never writing word 3) made every fragment
            // appear 0x90000000 ticks long (~15 days) — the "-15d duration" bug.
            writeU32(out, off, referencedSize and 0x7FFFFFFF)
            writeU32(out, off + 4, duration)
            writeU32(out, off + 8, (1 shl 31) or (1 shl 28))
            off += 12
        }
        return out
    }

    private fun writeU64(buf: ByteArray, off: Int, value: Long) {
        var v = value
        for (i in 7 downTo 0) {
            buf[off + i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
    }

    private fun writeU32(buf: ByteArray, off: Int, value: Int) {
        buf[off] = (value ushr 24).toByte()
        buf[off + 1] = (value ushr 16).toByte()
        buf[off + 2] = (value ushr 8).toByte()
        buf[off + 3] = value.toByte()
    }

    private fun writeU16(buf: ByteArray, off: Int, value: Int) {
        buf[off] = ((value shr 8) and 0xFF).toByte()
        buf[off + 1] = (value and 0xFF).toByte()
    }

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
}
