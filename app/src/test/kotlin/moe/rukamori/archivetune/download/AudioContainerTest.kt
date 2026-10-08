/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards container sniffing, which decides the name every exported file gets.
 *
 * These are cheap table-driven checks, but the failure they protect against is not cosmetic: an
 * extension that contradicts the bytes makes some players reject the file outright.
 */
class AudioContainerTest {
    /** Builds a [AudioContainer.PROBE_BYTES]-sized buffer so tests exercise real probe-sized input. */
    private fun header(vararg bytes: Int): ByteArray =
        ByteArray(AudioContainer.PROBE_BYTES).also { buf ->
            bytes.forEachIndexed { i, b -> buf[i] = b.toByte() }
        }

    private fun headerOf(
        prefix: String,
        atOffset: Int = 0,
    ): ByteArray =
        ByteArray(AudioContainer.PROBE_BYTES).also { buf ->
            prefix.forEachIndexed { i, c -> buf[atOffset + i] = c.code.toByte() }
        }

    @Test
    fun detectsFlacOggAndWav() {
        assertEquals(AudioContainer.FLAC, AudioContainer.detect(headerOf("fLaC")))
        assertEquals(AudioContainer.OGG, AudioContainer.detect(headerOf("OggS")))

        val wav = headerOf("RIFF").also { buf -> "WAVE".forEachIndexed { i, c -> buf[8 + i] = c.code.toByte() } }
        assertEquals(AudioContainer.WAV, AudioContainer.detect(wav))
    }

    @Test
    fun detectsWebmEbmlHeader() {
        assertEquals(AudioContainer.WEBM, AudioContainer.detect(header(0x1A, 0x45, 0xDF, 0xA3)))
    }

    @Test
    fun detectsMp4ByFtypAtOffsetFour() {
        // The ftyp box sits after a 4-byte size field, so a match at offset 0 must not count.
        assertEquals(AudioContainer.MP4, AudioContainer.detect(headerOf("ftyp", atOffset = 4)))
    }

    @Test
    fun riffWithoutWaveIsNotWav() {
        // RIFF also fronts AVI and other formats; the WAVE tag at byte 8 is what makes it audio.
        assertNull(AudioContainer.detect(headerOf("RIFF")))
    }

    @Test
    fun detectsBareMpegFrameSync() {
        // 11 bits of frame sync: 0xFF followed by the top 3 bits of the next byte.
        assertEquals(AudioContainer.MP3, AudioContainer.detect(header(0xFF, 0xFB)))
        assertEquals(AudioContainer.MP3, AudioContainer.detect(header(0xFF, 0xE0)))
    }

    @Test
    fun mpegSyncRequiresAllElevenBits() {
        // 0xFF 0x0F has the byte-0 sync but not the three high bits in byte 1.
        assertNull(AudioContainer.detect(header(0xFF, 0x0F)))
    }

    @Test
    fun id3TaggedStreamFallsBackToMp3() {
        // ID3 in front of audio we cannot see past: MP3 is overwhelmingly the common case.
        assertEquals(AudioContainer.MP3, AudioContainer.detect(headerOf("ID3")))
    }

    @Test
    fun id3SizeIsReadAsSynchsafeIntegerToFindMp4() {
        // A 28-bit synchsafe size of 7 bits per byte: 0,0,0,20 means the tag body is 20 bytes,
        // so the stream resumes at 10 + 20 = 30, where an ftyp box starts 4 bytes later.
        val buf = headerOf("ID3")
        buf[6] = 0
        buf[7] = 0
        buf[8] = 0
        buf[9] = 20
        "ftyp".forEachIndexed { i, c -> buf[30 + 4 + i] = c.code.toByte() }

        assertEquals(AudioContainer.MP4, AudioContainer.detect(buf))
    }

    @Test
    fun id3SizeBytesNeverUseTheHighBit() {
        // If the size were parsed as plain big-endian, 0x80 in a size byte would shift the resume
        // point far past the real one and the ftyp box below would be missed.
        val buf = headerOf("ID3")
        buf[6] = 0
        buf[7] = 0
        buf[8] = 0
        buf[9] = 0x80.toByte() // high bit set: synchsafe parsing masks it to 0
        "ftyp".forEachIndexed { i, c -> buf[10 + 4 + i] = c.code.toByte() }

        assertEquals(AudioContainer.MP4, AudioContainer.detect(buf))
    }

    @Test
    fun shortHeadersAreRejectedRatherThanGuessed() {
        assertNull(AudioContainer.detect(ByteArray(0)))
        assertNull(AudioContainer.detect("fLaC".toByteArray()))
    }

    @Test
    fun unknownBytesReturnNullSoCallersCanFallBack() {
        assertNull(AudioContainer.detect(headerOf("%PDF-1.7")))
    }

    @Test
    fun mimeFallbackMapsKnownTypes() {
        assertEquals("flac", AudioContainer.extensionForMime("audio/flac"))
        assertEquals("mp3", AudioContainer.extensionForMime("audio/mpeg"))
        assertEquals("ogg", AudioContainer.extensionForMime("audio/opus"))
        assertEquals("webm", AudioContainer.extensionForMime("audio/webm"))
        assertEquals("wav", AudioContainer.extensionForMime("audio/wav"))
    }

    @Test
    fun mimeFallbackIsCaseInsensitiveAndDefaultsToM4a() {
        assertEquals("flac", AudioContainer.extensionForMime("AUDIO/X-FLAC"))
        assertEquals("m4a", AudioContainer.extensionForMime(null))
        assertEquals("m4a", AudioContainer.extensionForMime("application/octet-stream"))
    }
}
