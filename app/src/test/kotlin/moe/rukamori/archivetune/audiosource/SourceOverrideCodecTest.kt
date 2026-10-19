/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.audiosource

import moe.rukamori.archivetune.constants.AudioSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceOverrideCodecTest {
    @Test
    fun absentOrBlankOverridesDoNotPinAnySource() {
        listOf(null, "", "   ").forEach { raw ->
            assertEquals(emptyMap<String, AudioSourceType>(), SongSourceOverride.parse(raw))
            assertNull(SongSourceOverride.get(raw, "song"))
            assertEquals(emptyMap<String, String>(), SongSourceQobuzTrackId.parse(raw))
        }
    }

    @Test
    fun sourceOverridesIgnoreMalformedEntriesAndKeepTheLastValidPin() {
        val raw = "broken;=TIDAL; =DEEZER;song=tidal; song = qobuz ;future=UNKNOWN;empty="
        assertEquals(mapOf("song" to AudioSourceType.QOBUZ), SongSourceOverride.parse(raw))
    }

    @Test
    fun everyKnownSourceSurvivesBackupSerializationWithoutChangingItsPin() {
        val pins = AudioSourceType.entries.associate { "song_${it.name}" to it }
        assertEquals(pins, SongSourceOverride.parse(SongSourceOverride.serialize(pins)))
    }

    @Test
    fun changingAndClearingASourcePinPreservesOtherSongs() {
        val original = "first=TIDAL;second=DEEZER"
        val changed = SongSourceOverride.withOverride(original, "first", AudioSourceType.QOBUZ)
        assertEquals(AudioSourceType.QOBUZ, SongSourceOverride.get(changed, "first"))
        assertEquals(AudioSourceType.DEEZER, SongSourceOverride.get(changed, "second"))
        val cleared = SongSourceOverride.withOverride(changed, "first", null)
        assertEquals(mapOf("second" to AudioSourceType.DEEZER), SongSourceOverride.parse(cleared))
        assertEquals(cleared, SongSourceOverride.withOverride(cleared, "missing", null))
        assertEquals("", SongSourceOverride.withOverride(null, "first", null))
    }

    @Test
    fun addingASourcePinStartsFromAnUnpinnedSong() {
        val raw = SongSourceOverride.withOverride(null, "first", AudioSourceType.YOUTUBE)
        assertEquals(AudioSourceType.YOUTUBE, SongSourceOverride.get(raw, "first"))
        assertNull(SongSourceOverride.get(raw, "different"))
    }

    @Test
    fun qobuzTrackIdsRemainStringsAndMalformedEntriesDoNotOverrideValidIds() {
        val raw = "broken;=123; =456;song=00123; song = 00999 ;empty=; blank = "
        assertEquals(mapOf("song" to "00999"), SongSourceQobuzTrackId.parse(raw))
        val ids = linkedMapOf("first" to "000123", "second" to "id=with=equals")
        assertEquals(ids, SongSourceQobuzTrackId.parse(SongSourceQobuzTrackId.serialize(ids)))
    }

    @Test
    fun qobuzTrackPinReplacementAndRemovalPreserveUnrelatedTracks() {
        val added = SongSourceQobuzTrackId.withOverride(null, "first", "00123")
        val changed = SongSourceQobuzTrackId.withOverride("$added;second=00456", "first", "00789")
        assertEquals("00789", SongSourceQobuzTrackId.get(changed, "first"))
        assertEquals("00456", SongSourceQobuzTrackId.get(changed, "second"))
        assertNull(SongSourceQobuzTrackId.get(changed, "missing"))
        val cleared = SongSourceQobuzTrackId.withOverride(changed, "first", null)
        assertEquals(mapOf("second" to "00456"), SongSourceQobuzTrackId.parse(cleared))
        assertEquals("", SongSourceQobuzTrackId.withOverride(null, "missing", null))
    }

    @Test
    fun backupVideoPinsKeepTheirOwnIdentifiersThroughTheSameLifecycle() {
        val pins = linkedMapOf("first" to "video_one", "second" to "video_two")
        val original = SongSourceQobuzBackupVideoId.serialize(pins)
        assertEquals(pins, SongSourceQobuzBackupVideoId.parse(original))
        val changed = SongSourceQobuzBackupVideoId.withOverride(original, "first", "replacement")
        assertEquals("replacement", SongSourceQobuzBackupVideoId.get(changed, "first"))
        val cleared = SongSourceQobuzBackupVideoId.withOverride(changed, "first", null)
        assertEquals(mapOf("second" to "video_two"), SongSourceQobuzBackupVideoId.parse(cleared))
        assertNull(SongSourceQobuzBackupVideoId.get(cleared, "first"))
    }
}
