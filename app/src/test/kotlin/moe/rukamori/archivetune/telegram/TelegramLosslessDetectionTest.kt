/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.telegram

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramLosslessDetectionTest {
    @Test
    fun detectsLosslessByMimeType() {
        assertTrue(isLosslessAudio("audio/flac", "unknown.bin"))
        assertTrue(isLosslessAudio("audio/x-flac", ""))
        assertTrue(isLosslessAudio("AUDIO/WAV", ""))
        assertTrue(isLosslessAudio("audio/x-wavpack", ""))
    }

    @Test
    fun detectsLosslessByExtension() {
        assertTrue(isLosslessAudio("application/octet-stream", "Album - 01 Track.flac"))
        assertTrue(isLosslessAudio("", "track.WAV"))
        assertTrue(isLosslessAudio("", "master.aiff"))
        assertTrue(isLosslessAudio("", "rip.ape"))
        assertTrue(isLosslessAudio("", "song.dsf"))
    }

    @Test
    fun rejectsLossyAudio() {
        assertFalse(isLosslessAudio("audio/mpeg", "track.mp3"))
        assertFalse(isLosslessAudio("audio/mp4", "track.m4a"))
        assertFalse(isLosslessAudio("audio/ogg", "track.opus"))
        assertFalse(isLosslessAudio("", "document.pdf"))
    }

    @Test
    fun audioDocumentDetectionCoversLossyAndLossless() {
        assertTrue(isAudioDocument("application/octet-stream", "track.flac"))
        assertTrue(isAudioDocument("application/octet-stream", "track.mp3"))
        assertTrue(isAudioDocument("audio/flac", "weird_name"))
        assertFalse(isAudioDocument("application/zip", "album.zip"))
        assertFalse(isAudioDocument("video/mp4", "clip.mp4"))
    }

    @Test
    fun trackDisplayTitleFallsBackToFileName() {
        val track =
            TelegramTrack(
                chatId = -1L,
                messageId = 1L,
                fileId = 1,
                fileUniqueId = "u",
                title = "",
                performer = null,
                fileName = "01 - Intro.flac",
                mimeType = "audio/flac",
                durationSeconds = 0,
                sizeBytes = 1L,
                dateSeconds = 0,
                albumCoverMinithumbnail = null,
            )
        assertTrue(track.isLossless)
        assertTrue(track.displayTitle == "01 - Intro")
    }
}
