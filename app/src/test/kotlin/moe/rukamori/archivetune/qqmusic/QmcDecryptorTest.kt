/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Pins the container ciphers against the fixtures their reference implementations publish.
 * A cipher that is subtly wrong does not throw: it produces bytes, the decoder meets noise, and the
 * track is reported unavailable with no clue as to why. Every fixture below is therefore a
 * known-answer vector taken from an upstream implementation rather than something this code
 * generated for itself, so a regression in an offset, a rotation, a segment size or an endianness
 * fails here instead of at playback.
 */



package moe.rukamori.archivetune.qqmusic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QmcDecryptorTest {
    // ------------------------------------------------------------------ fixed keystream (QMC1)

    /**
     * The first sixteen keystream bytes. The table is indexed by `offset² + 27`, which is a
     * different formulation from the walk over an 8×7 seed map that the format was first described
     * with; both are published, they agree for every offset, and this is the value they agree on.
     */
    @Test
    fun theFixedKeystreamStartsWithThePublishedMask() {
        assertArrayEquals(
            hex("c34ad6ca9067f752d8a166629f5b0900"),
            maskRun(0, 16),
        )
    }

    /**
     * The keystream wraps every `0x7FFF` bytes. This window straddles the wrap, so the last eight
     * bytes of one pass are followed by the first eight of the next — the single most likely place
     * for an off-by-one in the offset handling.
     */
    @Test
    fun theFixedKeystreamWrapsAtItsPeriod() {
        assertArrayEquals(
            hex("d852f76790cad64a4ad6ca9067f752d8"),
            maskRun(0x7FF8, 16),
        )
    }

    /** A whole file, decrypted with the real fixed keystream, down to the plaintext bytes. */
    @Test
    fun theFixedKeystreamDecryptsAWholePayload() {
        val cipher = hex("ab2fbaa6ff47803daacd02")
        QmcStaticCipher.decrypt(cipher)
        assertArrayEquals("hello world".toByteArray(Charsets.US_ASCII), cipher)
    }

    // ------------------------------------------------------------------ short-key stream (map)

    /**
     * The key's own table, for the published oversized key. The rotation is what makes this
     * distinguishable from a plain table read, so a regression in the rotation fails here.
     */
    @Test
    fun theShortKeyTableMatchesThePublishedCompression() {
        assertArrayEquals(
            hex(
                "79f400759e3600148a6300b4be770017ba0037000000bf8041bf83ddbc5c0243148249020055be6dbf49" +
                    "808e4300fa4167a817f4ae161500c13782dd362138550079419e42c136facf35000041dd4342174d8e" +
                    "8add00bef538b4bf007acc4d0200cfc1c102a80016c1bfc242004900c1c2f5001741dc83c2009e41c1" +
                    "71360080",
            ),
            maskRun(0, 128, longKey),
        )
    }

    /** The same key, decrypting a published ciphertext at an offset near the wrap. */
    @Test
    fun theShortKeyDecryptsThePublishedCiphertext() {
        val cipher = hex("009e41c171360080f400759e3600148a")
        QmcMapCipher(longKey).decrypt(32760, cipher)
        assertArrayEquals(ByteArray(16), cipher)
    }

    /** A 256-byte key, the size the container fixtures derive, over the published window. */
    @Test
    fun aFullSizedShortKeyMatchesThePublishedWindow() {
        val key = ByteArray(256) { it.toByte() }
        assertArrayEquals(
            hex("bb7d80beff3881fbbbff823cffba8379"),
            ByteArray(16).also { QmcMapCipher(key).decrypt(0, it) },
        )
    }

    /**
     * A short key, which is where the two published formulations of this cipher would part company
     * if the key were read directly rather than folded into its table.
     */
    @Test
    fun aShortKeyMatchesThePublishedWindows() {
        assertArrayEquals(
            hex("3f8ac1493f49c18a3f8ac1493f49c18a"),
            ByteArray(16).also { QmcMapCipher(shortKey).decrypt(0, it) },
        )
        assertArrayEquals(
            hex("8a3f8ac1493f49c18a8ac1493f49c18a"),
            ByteArray(16).also { QmcMapCipher(shortKey).decrypt(0x7FFF - 8, it) },
        )
    }

    // ------------------------------------------------------------------ long-key stream (RC4)

    /**
     * The first 128 bytes use a keyed lookup rather than the stream, so this window — the first
     * eight bytes, then the eight after the boundary — covers both halves of that handover.
     */
    @Test
    fun theLongKeyCipherHandlesItsFirstSegmentBoundary() {
        val cipher = QmcRc4Cipher(sequentialKey(255))
        assertArrayEquals(
            hex("00321008050302010101000000000000"),
            ByteArray(16).also { cipher.decrypt(0, it) },
        )
        assertArrayEquals(
            hex("00000000000000008d617ac1a665e9d6"),
            ByteArray(16).also { cipher.decrypt(0x80 - 8, it) },
        )
    }

    /**
     * The window that straddles a stream-segment boundary, and the first bytes of the segment
     * after it. A wrong segment size, prime count or seed byte shows up in the first half while
     * the second half stays correct, which is what makes this pair worth both halves.
     */
    @Test
    fun theLongKeyCipherHandlesItsStreamSegmentBoundary() {
        val cipher = QmcRc4Cipher(sequentialKey(255))
        assertArrayEquals(
            hex("76c1b0530a6269ea9738c601e2ad7f04"),
            ByteArray(16).also { cipher.decrypt(0x1400 - 8, it) },
        )
        assertArrayEquals(
            hex("9738c601e2ad7f04b5a5ab155298c3d2"),
            ByteArray(16).also { cipher.decrypt(0x1400, it) },
        )
    }

    /**
     * Reading a segment on its own must produce the same bytes as reading that range out of one
     * long pass from the start of the file. This is the property a block-by-block caller depends
     * on, and the one a segment-relative state bug breaks while every single-window fixture above
     * still passes.
     */
    @Test
    fun aSegmentDecryptedAloneMatchesTheSameBytesInALongPass() {
        val key = sequentialKey(255)
        val whole = ByteArray(0x1400 + 64).also { QmcRc4Cipher(key).decrypt(0, it) }
        val window = ByteArray(64).also { QmcRc4Cipher(key).decrypt(0x1400, it) }
        assertArrayEquals(whole.copyOfRange(0x1400, 0x1400 + 64), window)
    }

    /** The hash the segment sizes are derived from, against the published value. */
    @Test
    fun theLongKeyHashBaseMatchesThePublishedValue() {
        assertEquals(4045008896L, QmcRc4Cipher.hashBase("hello world".toByteArray(Charsets.US_ASCII)))
        assertEquals(0xfc05fc01L, QmcRc4Cipher.hashBase(ByteArray(16) { 0xFF.toByte() }))
    }

    // ------------------------------------------------------------------ key wrapping (TEA)

    /**
     * The modified TEA, against a ciphertext the reference implementation generated from its C++
     * counterpart. The trailing zero bytes are the integrity check, so a wrong round count or block
     * chaining fails the check rather than returning plausible bytes.
     */
    @Test
    fun tencentTeaDecryptsThePublishedCiphertext() {
        val plain =
            TcTea.decrypt(
                hex("91095162e3f5b6dc6b414b50d1a5b84ec50d0c1b1196fd3c"),
                "12345678ABCDEFGH".toByteArray(Charsets.US_ASCII),
            )
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), plain)
    }

    /** A tampered payload must fail the zero check instead of decrypting to rubbish. */
    @Test
    fun tencentTeaRejectsATamperedPayload() {
        val tampered = hex("91095162e3f5b6dc6b414b50d1a5b84ec50d0c1b1196fd3d")
        assertNull(TcTea.decrypt(tampered, "12345678ABCDEFGH".toByteArray(Charsets.US_ASCII)))
    }

    /** The per-file TEA salt, against the value two independent implementations assert. */
    @Test
    fun theTeaSaltMatchesThePublishedTable() {
        assertArrayEquals(hex("695646382b20150b"), TcTea.simpleMakeKey(106, 8))
    }

    /** The salt interleaved with the file's own header is what keys the body. */
    @Test
    fun theTeaKeyInterleavesTheSaltWithTheHeader() {
        assertArrayEquals(
            hex("69f156f246f338f42bf520f615f70bf8"),
            TcTea.deriveTeaKey(hex("f1f2f3f4f5f6f7f8")),
        )
    }

    /**
     * A wrapped key, end to end: base64, then the header-keyed TEA pass, against a fixture the
     * reference implementation ships. This is the whole key path in one assertion.
     */
    @Test
    fun aWrappedKeyDecodesToItsPlaintext() {
        val key = QmcEkey.derive("VGhpcyBpcyBHFWEh4cjZ1Vi7rJ56XeoPlqGM1sxBGPg7mt89umKclFBr9iqfmFdS")
        assertEquals("This is a test key for test purpose :D", key?.toString(Charsets.UTF_8))
    }

    /**
     * A key the service generated itself arrives as raw bytes rather than as a wrapped one. Taking
     * it as-is is what keeps those tracks playable; treating it as wrapped would corrupt it.
     */
    @Test
    fun aRawKeyIsTakenAsIs() {
        assertArrayEquals(hex("00112233445566778899aabbccddeeff"), QmcEkey.derive("ABEiM0RVZneImaq7zN3u/w=="))
    }

    /** Anything that is not a key at all must be refused rather than decoded into noise. */
    @Test
    fun anUnusableKeyIsRefused() {
        assertNull(QmcEkey.derive(""))
        assertNull(QmcEkey.derive("not base64 !!"))
        assertNull(QmcEkey.derive("YWJj"))
    }

    // ------------------------------------------------------------------ footers and the facade

    /** The newer footer: a length before the `QTag` magic, then the key and its bookkeeping. */
    @Test
    fun theQTagFooterIsReadWithItsBigEndianLength() {
        val footer = QmcEkey.findFooter(qtagFile)
        assertEquals(QmcEkey.FooterKind.QTAG, footer.kind)
        assertEquals("ABEiM0RVZneImaq7zN3u/w==", footer.keyText)
        assertEquals(64, footer.audioLength)
    }

    /** The older footer: the key sits immediately before its own length, little-endian. */
    @Test
    fun theOlderFooterIsReadWithItsLittleEndianLength() {
        val footer = QmcEkey.findFooter(legacyFile)
        assertEquals(QmcEkey.FooterKind.LEGACY_LENGTH, footer.kind)
        assertEquals("ABEiM0RVZneImaq7zN3u/w==", footer.keyText)
        assertEquals(64, footer.audioLength)
    }

    /** A container with no trailer at all is read as one: the whole file is the payload. */
    @Test
    fun aFileWithoutAFooterIsReadAsWholeFileAudio() {
        val footer = QmcEkey.findFooter(ByteArray(64))
        assertEquals(QmcEkey.FooterKind.NONE, footer.kind)
        assertNull(footer.keyText)
        assertEquals(64, footer.audioLength)
    }

    /**
     * The two layouts that carry metadata only. Their payload still has to be located exactly, which
     * is what [QmcEkey.Footer.audioLength] is for, and their lack of a key has to be visible — a
     * payload decrypted with a cipher it cannot have been encrypted with is worse than a refusal.
     */
    @Test
    fun theMetadataOnlyFootersAreReadAndRefusedWithoutAKey() {
        val expected = listOf(QmcEkey.FooterKind.STAG to stagFile, QmcEkey.FooterKind.MUSICEX to musicExFile)
        for ((kind, file) in expected) {
            val footer = QmcEkey.findFooter(file)
            assertEquals(kind, footer.kind)
            assertNull(footer.keyText)
            assertEquals(64, footer.audioLength)
            assertNull(QmcDecryptor.decrypt(file, ".mflac"))
        }
    }

    /** The same two files, decrypted with the key the service discloses alongside the resource. */
    @Test
    fun theMetadataOnlyFootersDecryptWithTheDisclosedKey() {
        for ((name, file) in listOf("STAG" to stagFile, "MUSICEX" to musicExFile)) {
            val decrypted = QmcDecryptor.decrypt(file, ".mflac", serviceEkey)
            assertEquals(name, QmcContainer.OGG, decrypted?.container)
            assertArrayEquals(name, plainPayload, decrypted?.bytes)
        }
    }

    /**
     * Both footer layouts, all the way through: the tail is dropped, the key is recovered, the
     * payload decrypts, and the result is recognised as Ogg. The trailing metadata must not survive
     * into the audio either.
     */
    @Test
    fun bothFooterLayoutsDecryptToTheSameAudio() {
        for ((name, file) in listOf("QTag" to qtagFile, "legacy" to legacyFile)) {
            val decrypted = QmcDecryptor.decrypt(file, ".mgg")
            assertEquals(name, QmcContainer.OGG, decrypted?.container)
            assertArrayEquals(name, plainPayload, decrypted?.bytes)
        }
    }

    /** A payload whose key is not in the file decrypts to noise, and noise is refused. */
    @Test
    fun aPayloadThatDoesNotDecryptToAudioIsRefused() {
        assertNull(QmcDecryptor.decrypt(ByteArray(64), ".mgg"))
        assertNull(QmcDecryptor.decrypt(hex("00010203"), ".mgg"))
    }

    /** Every container this source can be handed is recognised from its own header. */
    @Test
    fun containersAreRecognisedFromTheirHeaders() {
        assertEquals(QmcContainer.FLAC, QmcContainer.sniff("fLaC".toByteArray(Charsets.US_ASCII) + ByteArray(8)))
        assertEquals(QmcContainer.OGG, QmcContainer.sniff("OggS".toByteArray(Charsets.US_ASCII) + ByteArray(8)))
        assertEquals(QmcContainer.WAV, QmcContainer.sniff("RIFF".toByteArray(Charsets.US_ASCII) + ByteArray(8)))
        assertEquals(QmcContainer.MP3, QmcContainer.sniff("ID3\u0004".toByteArray(Charsets.US_ASCII) + ByteArray(8)))
        assertEquals(
            QmcContainer.M4A,
            QmcContainer.sniff(hex("00000018") + "ftypM4A ".toByteArray(Charsets.US_ASCII) + ByteArray(4)),
        )
        assertEquals(QmcContainer.MP3, QmcContainer.sniff(hex("fffb9064") + ByteArray(8)))
        assertNull(QmcContainer.sniff(ByteArray(16) { 0x5A }))
        assertTrue(QmcContainer.sniff(ByteArray(4)) == null)
    }

    // ------------------------------------------------------------------ fixtures

    private fun hex(value: String): ByteArray =
        ByteArray(value.length / 2) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }

    private fun maskRun(
        offset: Int,
        count: Int,
        key: ByteArray? = null,
    ): ByteArray =
        if (key == null) {
            ByteArray(count) { QmcStaticCipher.mask(offset + it).toByte() }
        } else {
            val cipher = QmcMapCipher(key)
            ByteArray(count) { cipher.mask(offset + it).toByte() }
        }

    private fun sequentialKey(size: Int): ByteArray = ByteArray(size) { (it and 0xFF).toByte() }

    private val shortKey = hex("4142434445464748494a4b4c4d4e4f50")

    /** The published oversized key: the alphabet repeated until it is 325 bytes long. */
    private val longKey =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            .repeat(6)
            .repeat(10)
            .take(325)
            .toByteArray(Charsets.US_ASCII)

    private val plainPayload =
        hex(
            "4f676753000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" +
                "202122232425262728292a2b2c2d2e2f303132333435363738393a3b",
        )

    /** An encrypted container with the newer `QTag` footer, and the same audio with the older one. */
    private val qtagFile =
        hex(
            "f498e7edbbbf82fcbffa86b9b3b78af4b7f28eb1abaf92ecafea96a9a3a79ae4a7e29ea19b9fa2dc" +
                "9fdaa6999397aad497d2ae918b8fb2cc8fcab6898387bac4414245694d3052565a6e65496d6171" +
                "377a4e33752f773d3d2c393030312c322c0000002051546167",
        )

    private val legacyFile =
        hex(
            "f498e7edbbbf82fcbffa86b9b3b78af4b7f28eb1abaf92ecafea96a9a3a79ae4a7e29ea19b9fa2dc" +
                "9fdaa6999397aad497d2ae918b8fb2cc8fcab6898387bac4414245694d3052565a6e65496d6171" +
                "377a4e33752f773d3d18000000",
        )

    /** The key both footer fixtures carry, and the one the service would disclose for them. */
    private val serviceEkey = "ABEiM0RVZneImaq7zN3u/w=="

    /** The encrypted audio every footer fixture wraps, taken from the `QTag` one. */
    private val audioCiphertext = qtagFile.copyOfRange(0, 64)

    private fun littleEndian(value: Int): ByteArray =
        byteArrayOf(
            value.toByte(),
            (value shr 8).toByte(),
            (value shr 16).toByte(),
            (value shr 24).toByte(),
        )

    private fun bigEndian(value: Int): ByteArray =
        byteArrayOf(
            (value shr 24).toByte(),
            (value shr 16).toByte(),
            (value shr 8).toByte(),
            value.toByte(),
        )

    private val stagMetadata = "9001,2,00QTESTMEDIAAAA".toByteArray(Charsets.US_ASCII)

    /**
     * A tail of the Android metadata-only kind: a CSV the file keeps for its own bookkeeping, its
     * big-endian length, then the magic. Nothing here is audio, and nothing here is a key.
     */
    private val stagFile =
        audioCiphertext +
            stagMetadata +
            bigEndian(stagMetadata.size) +
            "STag".toByteArray(Charsets.US_ASCII)

    /**
     * A tail of the current PC kind: a metadata body, then a little-endian size and version before
     * the magic. Only the trailer is inspected, so the body only has to be the right length.
     */
    private val musicExFile =
        audioCiphertext +
            ByteArray(0xB0).also { body ->
                "00QTESTMEDIAAAA".toByteArray(Charsets.UTF_16LE).copyInto(body, 0x0C)
            } +
            littleEndian(0xC0) +
            littleEndian(1) +
            "musicex\u0000".toByteArray(Charsets.US_ASCII)
}
