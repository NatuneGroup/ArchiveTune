/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.utils

import moe.rukamori.archivetune.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A manifest release must ride the existing decision path unchanged: its versionCode becomes the
 * "(build n)" display name, and an update is offered only when that build number is strictly
 * greater than the installed VERSION_CODE.
 */
class UpdateFeedDecisionTest {
    private fun manifestJson(versionCode: Long) =
        """
        {
          "schema": 1,
          "channel": "nightly",
          "tag": "N202610131530",
          "versionName": "${BuildConfig.VERSION_NAME}",
          "versionCode": $versionCode,
          "publishedAt": "2026-10-13T15:30:00Z",
          "releaseUrl": "https://github.com/NatuneGroup/ArchiveTune/releases/tag/N202610131530",
          "notes": "Nightly build notes",
          "assets": [
            {
              "name": "app-gms-mobile-arm64-nightly.apk",
              "url": "https://github.com/NatuneGroup/ArchiveTune/releases/download/N202610131530/app-gms-mobile-arm64-nightly.apk"
            }
          ]
        }
        """.trimIndent()

    private fun releaseFor(versionCode: Long): ReleaseInfo {
        val manifest = parseUpdateFeedManifest(manifestJson(versionCode), expectedChannel = "nightly")!!
        return manifest.toReleaseInfo(
            assetUrl = selectUpdateFeedAssetUrl(manifest.assets, "app-gms-mobile-arm64-nightly.apk"),
            releasesBaseUrl = "https://github.com/NatuneGroup/ArchiveTune/releases",
        )
    }

    @Test
    fun greaterVersionCodeReportsUpdateWithBuildName() {
        val newerCode = BuildConfig.VERSION_CODE + 1
        val release = releaseFor(newerCode.toLong())
        val versionName = Updater.getPreReleaseVersionName(release)

        assertEquals("${BuildConfig.VERSION_NAME} (build $newerCode)", versionName)
        assertTrue(Updater.isUpdateAvailable(versionName, BuildConfig.VERSION_NAME))
    }

    @Test
    fun equalVersionCodeReportsNoUpdate() {
        val release = releaseFor(BuildConfig.VERSION_CODE.toLong())
        val versionName = Updater.getPreReleaseVersionName(release)

        assertEquals("${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})", versionName)
        assertFalse(Updater.isUpdateAvailable(versionName, BuildConfig.VERSION_NAME))
    }

    @Test
    fun lowerVersionCodeReportsNoUpdate() {
        val release = releaseFor((BuildConfig.VERSION_CODE - 1).toLong())

        assertFalse(Updater.isUpdateAvailable(Updater.getPreReleaseVersionName(release), BuildConfig.VERSION_NAME))
    }

    private fun stableReleaseFor(
        tag: String,
        versionCode: Long,
    ): ReleaseInfo {
        val json =
            """
            {
              "schema": 1,
              "channel": "stable",
              "tag": "$tag",
              "versionName": "${tag.removePrefix("v")}",
              "versionCode": $versionCode,
              "publishedAt": "2026-10-13T15:30:00Z",
              "assets": []
            }
            """.trimIndent()
        val manifest = parseUpdateFeedManifest(json, expectedChannel = "stable")!!
        return manifest.toReleaseInfo(assetUrl = null, releasesBaseUrl = "https://github.com/NatuneGroup/ArchiveTune/releases")
    }

    @Test
    fun stableEqualVersionCodeReportsNoUpdate() {
        val release = stableReleaseFor("v${BuildConfig.VERSION_NAME}", BuildConfig.VERSION_CODE.toLong())

        assertFalse(Updater.isUpdateAvailable(Updater.getReleaseVersionName(release), BuildConfig.VERSION_NAME))
    }

    @Test
    fun stableSameSemVerHigherVersionCodeReportsUpdate() {
        val release = stableReleaseFor("v${BuildConfig.VERSION_NAME}", (BuildConfig.VERSION_CODE + 1).toLong())

        assertTrue(Updater.isUpdateAvailable(Updater.getReleaseVersionName(release), BuildConfig.VERSION_NAME))
    }

    @Test
    fun stableHigherSemVerLowerVersionCodeReportsNoUpdate() {
        val release = stableReleaseFor("v999.0.0", (BuildConfig.VERSION_CODE - 1).coerceAtLeast(1).toLong())

        assertFalse(Updater.isUpdateAvailable(Updater.getReleaseVersionName(release), BuildConfig.VERSION_NAME))
    }

    @Test
    fun stableHigherSemVerEqualVersionCodeReportsNoUpdate() {
        val release = stableReleaseFor("v999.0.0", BuildConfig.VERSION_CODE.toLong())

        assertFalse(Updater.isUpdateAvailable(Updater.getReleaseVersionName(release), BuildConfig.VERSION_NAME))
    }

    @Test
    fun stableApiReleaseWithoutVersionCodeKeepsSemVerName() {
        val release =
            ReleaseInfo(
                tagName = "v999.0.0",
                name = "999.0.0",
                body = null,
                publishedAt = "",
                htmlUrl = "",
                downloadUrl = null,
            )

        assertEquals("999.0.0", Updater.getReleaseVersionName(release))
    }

    @Test
    fun mappedReleaseCarriesTagNotesAndSelectedAsset() {
        val release = releaseFor((BuildConfig.VERSION_CODE + 1).toLong())

        assertEquals("N202610131530", release.tagName)
        assertEquals("Nightly build notes", release.body)
        assertEquals(
            "https://github.com/NatuneGroup/ArchiveTune/releases/download/N202610131530/app-gms-mobile-arm64-nightly.apk",
            release.downloadUrl,
        )
        assertEquals("https://github.com/NatuneGroup/ArchiveTune/releases/tag/N202610131530", release.htmlUrl)
    }

    @Test
    fun missingReleaseUrlFallsBackToTagPage() {
        val json =
            manifestJson((BuildConfig.VERSION_CODE + 1).toLong())
                .replace(Regex("\"releaseUrl\": \"[^\"]*\","), "")
        val manifest = parseUpdateFeedManifest(json, expectedChannel = "nightly")!!

        val release =
            manifest.toReleaseInfo(
                assetUrl = null,
                releasesBaseUrl = "https://github.com/NatuneGroup/ArchiveTune/releases",
            )

        assertEquals("https://github.com/NatuneGroup/ArchiveTune/releases/tag/N202610131530", release.htmlUrl)
    }
}
