/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Pins the quality tiers this source asks for.
 * The url-minter does not name a track, it names a resource: a quality prefix, the track's resource
 * id and an extension. Getting the pair wrong asks for a quality that is not the one the user chose,
 * or for a container the service will not serve, and both come back as an empty path rather than as
 * an error — so the mapping is worth pinning against the values the service documents.
 */



package moe.rukamori.archivetune.qqmusic

import moe.rukamori.archivetune.constants.QqAudioQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QqAudioQualityFormatsTest {
    @Test
    fun plainTiersUseTheUnencryptedPrefixAndContainer() {
        assertEquals("F000.flac", resourceOf(QqAudioQualityFormats.plain(QqAudioQuality.LOSSLESS)))
        assertEquals("M800.mp3", resourceOf(QqAudioQualityFormats.plain(QqAudioQuality.HIGH)))
        assertEquals("M500.mp3", resourceOf(QqAudioQualityFormats.plain(QqAudioQuality.STANDARD)))
    }

    /**
     * The encrypted tier names the same quality, so a fallback must not silently drop the user down
     * a tier: lossless stays lossless, only the container changes.
     */
    @Test
    fun encryptedTiersMirrorThePlainOnes() {
        assertEquals("F0M0.mflac", resourceOf(QqAudioQualityFormats.encrypted(QqAudioQuality.LOSSLESS)))
        assertEquals("O8M0.mgg", resourceOf(QqAudioQualityFormats.encrypted(QqAudioQuality.HIGH)))
        assertEquals("O4M0.mgg", resourceOf(QqAudioQualityFormats.encrypted(QqAudioQuality.STANDARD)))
    }

    /** Every tier must be addressable in both containers, or the fallback would be a dead end. */
    @Test
    fun everyTierHasBothContainers() {
        for (quality in QqAudioQuality.entries) {
            val plain = QqAudioQualityFormats.plain(quality)
            val encrypted = QqAudioQualityFormats.encrypted(quality)
            assertTrue(quality.name, plain.prefix.length == 4)
            assertTrue(quality.name, encrypted.prefix.length == 4)
            assertTrue(quality.name, plain.extension.startsWith("."))
            assertTrue(quality.name, encrypted.extension.startsWith("."))
            assertTrue(quality.name, plain.prefix != encrypted.prefix)
        }
    }

    /** A path is only treated as protected when its own extension says so, query string aside. */
    @Test
    fun protectedContainersAreRecognisedFromThePath() {
        assertTrue(QqAudioQualityFormats.isEncryptedUrl("https://host/F0M0abc.mflac?vkey=1"))
        assertTrue(QqAudioQualityFormats.isEncryptedUrl("https://host/O8M0abc.mgg"))
        assertTrue(QqAudioQualityFormats.isEncryptedUrl("https://host/O8M1abc.MGG1"))
        assertFalse(QqAudioQualityFormats.isEncryptedUrl("https://host/F000abc.flac?vkey=1"))
        assertFalse(QqAudioQualityFormats.isEncryptedUrl("https://host/M800abc.mp3"))
    }

    private fun resourceOf(format: QqAudioFormat): String = format.prefix + format.extension
}
