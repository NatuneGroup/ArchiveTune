/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The schema-v1 update-feed manifest is the updater's first and unauthenticated source. The parser
 * is the defensive boundary: anything unexpected is a miss that sends the updater to its API
 * fallback, never a throw.
 */
class UpdateFeedManifestTest {
    private val validNightlyManifest =
        """
        {
          "schema": 1,
          "channel": "nightly",
          "tag": "N202610131530",
          "versionName": "15.0.0",
          "versionCode": 6731,
          "commit": "abc1234def5678",
          "publishedAt": "2026-10-13T15:30:00Z",
          "releaseUrl": "https://github.com/NatuneGroup/ArchiveTune/releases/tag/N202610131530",
          "notes": "Nightly build notes",
          "assets": [
            {
              "name": "app-gms-mobile-arm64-nightly.apk",
              "url": "https://github.com/NatuneGroup/ArchiveTune/releases/download/N202610131530/app-gms-mobile-arm64-nightly.apk",
              "size": 59000000,
              "sha256": "deadbeef"
            },
            {
              "name": "app-foss-mobile-universal-nightly.apk",
              "url": "https://github.com/NatuneGroup/ArchiveTune/releases/download/N202610131530/app-foss-mobile-universal-nightly.apk",
              "size": 58000000,
              "sha256": "cafebabe"
            }
          ]
        }
        """.trimIndent()

    @Test
    fun validNightlyManifestParses() {
        val manifest = parseUpdateFeedManifest(validNightlyManifest, expectedChannel = "nightly")

        assertEquals("nightly", manifest?.channel)
        assertEquals("N202610131530", manifest?.tag)
        assertEquals("15.0.0", manifest?.versionName)
        assertEquals(6731L, manifest?.versionCode)
        assertEquals("Nightly build notes", manifest?.notes)
        assertEquals("2026-10-13T15:30:00Z", manifest?.publishedAt)
        assertEquals(2, manifest?.assets?.size)
        assertEquals(
            "https://github.com/NatuneGroup/ArchiveTune/releases/download/N202610131530/app-gms-mobile-arm64-nightly.apk",
            manifest?.assets?.first()?.url,
        )
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val withExtras =
            validNightlyManifest
                .replace("\"notes\": \"Nightly build notes\",", "\"notes\": \"Nightly build notes\", \"futureField\": {\"x\": 1},")
                .replace("\"sha256\": \"deadbeef\"", "\"sha256\": \"deadbeef\", \"checksumV2\": \"zzz\"")

        val manifest = parseUpdateFeedManifest(withExtras, expectedChannel = "nightly")

        assertEquals("N202610131530", manifest?.tag)
        assertEquals(2, manifest?.assets?.size)
    }

    @Test
    fun malformedJsonIsAMiss() {
        assertNull(parseUpdateFeedManifest("{oops", expectedChannel = "nightly"))
        assertNull(parseUpdateFeedManifest("not json at all", expectedChannel = "nightly"))
        assertNull(parseUpdateFeedManifest("", expectedChannel = "nightly"))
    }

    @Test
    fun missingVersionCodeIsAMiss() {
        val withoutVersionCode = validNightlyManifest.replace(Regex("\"versionCode\": \\d+,"), "")

        assertNull(parseUpdateFeedManifest(withoutVersionCode, expectedChannel = "nightly"))
    }

    @Test
    fun nonPositiveVersionCodeIsAMiss() {
        val zero = validNightlyManifest.replace("\"versionCode\": 6731", "\"versionCode\": 0")

        assertNull(parseUpdateFeedManifest(zero, expectedChannel = "nightly"))
    }

    @Test
    fun wrongChannelIsAMiss() {
        assertNull(parseUpdateFeedManifest(validNightlyManifest, expectedChannel = "stable"))
    }

    @Test
    fun schemaOtherThanOneIsAMiss() {
        val v2 = validNightlyManifest.replace("\"schema\": 1", "\"schema\": 2")
        assertNull(parseUpdateFeedManifest(v2, expectedChannel = "nightly"))

        val noSchema = validNightlyManifest.replace("\"schema\": 1,", "")
        assertNull(parseUpdateFeedManifest(noSchema, expectedChannel = "nightly"))
    }

    @Test
    fun assetSelectionPicksGmsMobileArm64Nightly() {
        val manifest = parseUpdateFeedManifest(validNightlyManifest, expectedChannel = "nightly")!!

        assertEquals(
            "https://github.com/NatuneGroup/ArchiveTune/releases/download/N202610131530/app-gms-mobile-arm64-nightly.apk",
            selectUpdateFeedAssetUrl(manifest.assets, "app-gms-mobile-arm64-nightly.apk"),
        )
    }

    @Test
    fun assetSelectionPicksFossMobileUniversalNightly() {
        val manifest = parseUpdateFeedManifest(validNightlyManifest, expectedChannel = "nightly")!!

        assertEquals(
            "https://github.com/NatuneGroup/ArchiveTune/releases/download/N202610131530/app-foss-mobile-universal-nightly.apk",
            selectUpdateFeedAssetUrl(manifest.assets, "app-foss-mobile-universal-nightly.apk"),
        )
    }

    @Test
    fun assetSelectionReturnsNullWhenNoAssetMatches() {
        val manifest = parseUpdateFeedManifest(validNightlyManifest, expectedChannel = "nightly")!!

        assertNull(selectUpdateFeedAssetUrl(manifest.assets, "app-gms-tv-universal-nightly.apk"))
        assertNull(selectUpdateFeedAssetUrl(emptyList(), "app-gms-mobile-arm64-nightly.apk"))
    }
}
