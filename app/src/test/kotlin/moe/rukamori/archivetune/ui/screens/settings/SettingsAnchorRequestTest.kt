/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.ui.screens.settings

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Guards the handoff between a settings search result and the sub-screen it deep links into.
 *
 * The tricky part is not "does it return the anchor" but the lifetime rules around it: a screen may
 * read the request more than once before it sticks, and a request that is never claimed must not
 * sit around waiting to fire on some unrelated later visit.
 */
class SettingsAnchorRequestTest {
    private var now = 1_000L

    @Before
    fun setUp() {
        SettingsAnchorRequest.reset()
        SettingsAnchorRequest.elapsedMs = { now }
    }

    @After
    fun tearDown() {
        SettingsAnchorRequest.reset()
        // Deliberately does NOT restore the android.os.SystemClock default: calling it on a plain
        // JVM throws "not mocked", and every test here installs its own clock in setUp anyway.
    }

    @Test
    fun `returns the anchor requested for that screen`() {
        SettingsAnchorRequest.request(SettingsAnchorScreens.PLAYER, SettingsAnchors.CROSSFADE)

        assertEquals(
            SettingsAnchors.CROSSFADE,
            SettingsAnchorRequest.consume(SettingsAnchorScreens.PLAYER),
        )
    }

    @Test
    fun `returns null for a screen the request was not aimed at`() {
        SettingsAnchorRequest.request(SettingsAnchorScreens.PLAYER, SettingsAnchors.CROSSFADE)

        assertNull(SettingsAnchorRequest.consume(SettingsAnchorScreens.STORAGE))
    }

    @Test
    fun `a request aimed elsewhere is left intact for its own screen`() {
        SettingsAnchorRequest.request(SettingsAnchorScreens.STORAGE, SettingsAnchors.SMART_TRIMMER)

        // Another screen composing first must not consume someone else's request.
        assertNull(SettingsAnchorRequest.consume(SettingsAnchorScreens.APPEARANCE))
        assertEquals(
            SettingsAnchors.SMART_TRIMMER,
            SettingsAnchorRequest.consume(SettingsAnchorScreens.STORAGE),
        )
    }

    @Test
    fun `survives repeated reads inside the claim window`() {
        // StorageSettings returns early until its image cache is ready, so its first composition is
        // discarded and it reads the request again a moment later. That must still work.
        SettingsAnchorRequest.request(SettingsAnchorScreens.STORAGE, SettingsAnchors.EXPORT_DOWNLOADS)

        assertEquals(
            SettingsAnchors.EXPORT_DOWNLOADS,
            SettingsAnchorRequest.consume(SettingsAnchorScreens.STORAGE),
        )
        now += 200
        assertEquals(
            SettingsAnchors.EXPORT_DOWNLOADS,
            SettingsAnchorRequest.consume(SettingsAnchorScreens.STORAGE),
        )
    }

    @Test
    fun `expires once the claim window has passed`() {
        SettingsAnchorRequest.request(SettingsAnchorScreens.PLAYER, SettingsAnchors.GAPLESS)

        assertEquals(
            SettingsAnchors.GAPLESS,
            SettingsAnchorRequest.consume(SettingsAnchorScreens.PLAYER),
        )
        // Re-opening the screen much later must not replay a stale highlight.
        now += 60_000
        assertNull(SettingsAnchorRequest.consume(SettingsAnchorScreens.PLAYER))
    }

    @Test
    fun `expiry is measured from the first claim not from the request`() {
        SettingsAnchorRequest.request(SettingsAnchorScreens.APPEARANCE, SettingsAnchors.DARK_THEME)

        // A request can sit unclaimed while the screen is still being built; that wait must not
        // count against the window, otherwise a slow navigation would drop the highlight.
        now += 10_000
        assertEquals(
            SettingsAnchors.DARK_THEME,
            SettingsAnchorRequest.consume(SettingsAnchorScreens.APPEARANCE),
        )
        now += 200
        assertEquals(
            SettingsAnchors.DARK_THEME,
            SettingsAnchorRequest.consume(SettingsAnchorScreens.APPEARANCE),
        )
    }

    @Test
    fun `a newer request replaces an unclaimed older one`() {
        SettingsAnchorRequest.request(SettingsAnchorScreens.PLAYER, SettingsAnchors.CROSSFADE)
        SettingsAnchorRequest.request(SettingsAnchorScreens.PLAYER, SettingsAnchors.SKIP_SILENCE)

        assertEquals(
            SettingsAnchors.SKIP_SILENCE,
            SettingsAnchorRequest.consume(SettingsAnchorScreens.PLAYER),
        )
    }

    @Test
    fun `re-requesting restarts the claim window`() {
        SettingsAnchorRequest.request(SettingsAnchorScreens.PLAYER, SettingsAnchors.CROSSFADE)
        assertEquals(
            SettingsAnchors.CROSSFADE,
            SettingsAnchorRequest.consume(SettingsAnchorScreens.PLAYER),
        )

        // Tapping the same search result again after the first window lapsed must work, not be
        // swallowed by the previous claim's bookkeeping.
        now += 60_000
        SettingsAnchorRequest.request(SettingsAnchorScreens.PLAYER, SettingsAnchors.CROSSFADE)
        assertEquals(
            SettingsAnchors.CROSSFADE,
            SettingsAnchorRequest.consume(SettingsAnchorScreens.PLAYER),
        )
    }

    @Test
    fun `consume with no pending request returns null`() {
        assertNull(SettingsAnchorRequest.consume(SettingsAnchorScreens.PLAYER))
    }

    @Test
    fun `anchor ids are unique`() {
        val ids =
            listOf(
                SettingsAnchors.CROSSFADE,
                SettingsAnchors.GAPLESS,
                SettingsAnchors.SKIP_SILENCE,
                SettingsAnchors.AUDIO_NORMALIZATION,
                SettingsAnchors.PERSISTENT_QUEUE,
                SettingsAnchors.EXTERNAL_DOWNLOADER,
                SettingsAnchors.DYNAMIC_THEME,
                SettingsAnchors.DARK_THEME,
                SettingsAnchors.PURE_BLACK,
                SettingsAnchors.APP_ICON,
                SettingsAnchors.FONT,
                SettingsAnchors.HIGH_REFRESH_RATE,
                SettingsAnchors.EXPORT_DOWNLOADS,
                SettingsAnchors.CLEAR_DOWNLOADS,
                SettingsAnchors.SONG_CACHE_SIZE,
                SettingsAnchors.CLEAR_SONG_CACHE,
                SettingsAnchors.IMAGE_CACHE_SIZE,
                SettingsAnchors.SMART_TRIMMER,
            )

        // Two preferences sharing an id would both light up, and the scroll offset would latch onto
        // whichever was measured first.
        assertEquals(ids.size, ids.distinct().size)
    }
}
