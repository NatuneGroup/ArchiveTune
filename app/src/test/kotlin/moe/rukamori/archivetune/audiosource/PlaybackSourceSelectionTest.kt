/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.audiosource

import moe.rukamori.archivetune.constants.AudioSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSourceSelectionTest {
    @Test
    fun disabledResolvedAndPinnedSourcesCannotRemainSelectable() {
        val candidates = listOf(AudioSourceType.TIDAL, AudioSourceType.QOBUZ, AudioSourceType.YOUTUBE)

        assertEquals(
            listOf(AudioSourceType.YOUTUBE),
            enabledPlaybackSources(candidates, setOf(AudioSourceType.YOUTUBE)),
        )
    }

    @Test
    fun everyEnabledAudioSourceCanAppearWithoutChangingTheChooserOrder() {
        val sources = AudioSourceType.entries.toList()

        assertEquals(sources, enabledPlaybackSources(sources, sources.toSet()))
        sources.forEach { source ->
            assertEquals(listOf(source), enabledPlaybackSources(sources, setOf(source)))
        }
    }

    @Test
    fun disabledYouTubeAndAllDisabledSourcesAreNotOffered() {
        val candidates = listOf(AudioSourceType.TIDAL, AudioSourceType.YOUTUBE)

        assertEquals(listOf(AudioSourceType.TIDAL), enabledPlaybackSources(candidates, setOf(AudioSourceType.TIDAL)))
        assertEquals(emptyList<AudioSourceType>(), enabledPlaybackSources(candidates, emptySet()))
    }

    @Test
    fun duplicateCandidatesAreRemovedAndSpotifyIsNotAnAudioSource() {
        assertEquals(
            listOf(AudioSourceType.TIDAL),
            enabledPlaybackSources(listOf(AudioSourceType.TIDAL, AudioSourceType.TIDAL), setOf(AudioSourceType.TIDAL)),
        )
        assertFalse(AudioSourceType.entries.any { it.name == "SPOTIFY" })
    }

    @Test
    fun cachedUrlsCannotBypassAnyDisabledSourceFlag() {
        AudioSourceType.entries.forEach { source ->
            assertFalse(canUseCachedPlaybackSource(source, enabled = false, rejectedSources = emptySet()))
            assertTrue(canUseCachedPlaybackSource(source, enabled = true, rejectedSources = emptySet()))
        }
    }

    @Test
    fun rejectedSourceUrlsStayUnusableEvenWhenTheirSourceIsEnabled() {
        assertFalse(canUseCachedPlaybackSource(AudioSourceType.TIDAL, enabled = true, setOf(AudioSourceType.TIDAL)))
        assertTrue(canUseCachedPlaybackSource(AudioSourceType.QOBUZ, enabled = true, setOf(AudioSourceType.TIDAL)))
    }
}
