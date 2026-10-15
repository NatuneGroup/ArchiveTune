/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.audiosource

import moe.rukamori.archivetune.constants.AudioSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the one-time DataStore migration that brings stored source orders up to the current
 * catalogue ([AudioSourceConfig.migrateStoredOrder]). The migration rewrites only orders that
 * actually change, so it never loops: DataStore re-runs `shouldMigrate` on every start.
 */
class AudioSourceOrderMigrationTest {
    @Test
    fun blankOrderStaysBlank() {
        // A blank order keeps tracking the defaults — writing the expanded list would pin
        // today's default priority onto installs that never chose one.
        assertEquals("", AudioSourceConfig.migrateStoredOrder(""))
        assertEquals("", AudioSourceConfig.migrateStoredOrder(null))
        assertEquals("   ", AudioSourceConfig.migrateStoredOrder("   "))
    }

    @Test
    fun legacyOrderGetsMissingEntriesAboveYouTube() {
        val migrated = AudioSourceConfig.migrateStoredOrder("TIDAL,QOBUZ,YOUTUBE")

        val sources = migrated.split(',').map { AudioSourceType.valueOf(it) }
        assertTrue(sources.indexOf(AudioSourceType.SPOTIFY) < sources.indexOf(AudioSourceType.YOUTUBE))
        assertTrue(sources.indexOf(AudioSourceType.AMAZON) < sources.indexOf(AudioSourceType.YOUTUBE))
        assertTrue(sources.indexOf(AudioSourceType.QQ) < sources.indexOf(AudioSourceType.YOUTUBE))
        // Pre-existing entries keep their relative order, YouTube stays last.
        assertEquals(
            listOf(AudioSourceType.TIDAL, AudioSourceType.QOBUZ),
            sources.filter { it == AudioSourceType.TIDAL || it == AudioSourceType.QOBUZ },
        )
        assertEquals(AudioSourceType.YOUTUBE, sources.last())
    }

    @Test
    fun migrationIsIdempotent() {
        val orders = listOf("TIDAL,QOBUZ,YOUTUBE", "YOUTUBE,TIDAL", "QOBUZ", AudioSourceConfig.DEFAULT_ORDER.joinToString(",") { it.name })
        orders.forEach { raw ->
            val once = AudioSourceConfig.migrateStoredOrder(raw)
            assertEquals("migrating twice must equal migrating once for $raw", once, AudioSourceConfig.migrateStoredOrder(once))
        }
    }

    @Test
    fun unknownNamesAreDropped() {
        val migrated = AudioSourceConfig.migrateStoredOrder("TIDAL,NOT_A_SOURCE,YOUTUBE")

        assertEquals(false, migrated.contains("NOT_A_SOURCE"))
        assertTrue(AudioSourceType.TIDAL in AudioSourceConfig.parseOrder(migrated))
    }

    @Test
    fun garbageOnlyOrderNormalizesToTheDefaults() {
        val once = AudioSourceConfig.migrateStoredOrder("NOT_A_SOURCE")

        assertEquals(AudioSourceConfig.DEFAULT_ORDER, AudioSourceConfig.parseOrder(once))
        assertEquals(once, AudioSourceConfig.migrateStoredOrder(once))
    }

    @Test
    fun aCompleteOrderIsLeftUntouched() {
        val complete = AudioSourceConfig.DEFAULT_ORDER.joinToString(",") { it.name }

        assertEquals(complete, AudioSourceConfig.migrateStoredOrder(complete))
    }
}
