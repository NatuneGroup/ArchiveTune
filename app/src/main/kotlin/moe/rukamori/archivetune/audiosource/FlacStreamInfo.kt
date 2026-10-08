/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.audiosource

/** Reads a FLAC `STREAMINFO` block out of the first bytes of a stream. */
object FlacStreamInfo {
    /** Bytes needed to cover `fLaC` + block header + the whole STREAMINFO block. */
    const val REQUIRED_BYTES: Int = 42

    private const val MAGIC_LENGTH = 4
    private const val BLOCK_HEADER_LENGTH = 4
    private const val STREAMINFO_LENGTH = 34
    private const val BLOCK_TYPE_STREAMINFO = 0

    /**
     * Offset of the packed 64-bit field *within* the STREAMINFO block: it follows
     * min/max block size (2 B each) and min/max frame size (3 B each) = 10 bytes.
     */
    private const val PACKED_FIELD_OFFSET = 10

    data class Info(
        val sampleRate: Int,
        val channels: Int,
        val bitDepth: Int,
        /** Total interchannel samples, or null when the encoder left it unset (0 = unknown). */
        val totalSamples: Long?,
    ) {
        /** Track length in milliseconds, or null when the encoder left the sample count unset. */
        val durationMs: Long?
            get() {
                val samples = totalSamples ?: return null
                if (samples <= 0L || sampleRate <= 0) return null
                return samples * 1000L / sampleRate
            }

        /**
         * Uncompressed PCM rate in bits/sec — the figure streaming services quote
         * for a tier. The true average rate of the encoded file is lower; derive
         * that from the byte length and [durationMs] when both are known.
         */
        val pcmBitrate: Int
            get() = sampleRate * bitDepth * channels
    }

    /**
     * Parses [bytes] (the beginning of a FLAC file) into an [Info], or returns null
     * when the data is too short, is not FLAC, or the first metadata block is not
     * a well-formed `STREAMINFO`.
     *
     * Never throws: callers use this on network data they do not control, and a
     * missing Details row is preferable to a crashed playback path.
     */
    fun parse(bytes: ByteArray): Info? {
        if (bytes.size < MAGIC_LENGTH + BLOCK_HEADER_LENGTH + STREAMINFO_LENGTH) return null
        if (bytes[0] != 'f'.code.toByte() ||
            bytes[1] != 'L'.code.toByte() ||
            bytes[2] != 'a'.code.toByte() ||
            bytes[3] != 'C'.code.toByte()
        ) {
            return null
        }

        val blockType = bytes[MAGIC_LENGTH].toInt() and 0x7F
        if (blockType != BLOCK_TYPE_STREAMINFO) return null
        val blockLength = readUnsigned(bytes, MAGIC_LENGTH + 1, 3).toInt()
        if (blockLength != STREAMINFO_LENGTH) return null

        val packedOffset = MAGIC_LENGTH + BLOCK_HEADER_LENGTH + PACKED_FIELD_OFFSET
        val packed = readUnsigned(bytes, packedOffset, 8)

        val sampleRate = ((packed ushr 44) and 0xFFFFF).toInt()
        val channels = (((packed ushr 41) and 0x7).toInt()) + 1
        val bitDepth = (((packed ushr 36) and 0x1F).toInt()) + 1
        val totalSamples = packed and 0xF_FFFF_FFFFL

        // A zero sample rate is illegal in FLAC and means we mis-parsed; refuse
        // rather than reporting "0 Hz" in the UI.
        if (sampleRate <= 0) return null

        return Info(
            sampleRate = sampleRate,
            channels = channels,
            bitDepth = bitDepth,
            totalSamples = totalSamples.takeIf { it > 0L },
        )
    }

    /** Big-endian unsigned read of [length] (1..8) bytes starting at [offset]. */
    private fun readUnsigned(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): Long {
        var value = 0L
        for (index in 0 until length) {
            value = (value shl 8) or (bytes[offset + index].toLong() and 0xFF)
        }
        return value
    }
}
