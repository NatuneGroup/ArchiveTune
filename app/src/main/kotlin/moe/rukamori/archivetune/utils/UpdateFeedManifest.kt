/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * One APK asset of an [UpdateFeedManifest]. [size] and [sha256] are informational — the system
 * package check remains the trust anchor — only [name] and [url] drive update selection.
 */
internal data class UpdateFeedAsset(
    val name: String,
    val url: String,
    val size: Long?,
    val sha256: String?,
)

/**
 * The schema-v1 update-feed manifest CI uploads to the fixed `update-feed` release
 * (`releases/download/update-feed/<channel>.json`). Because release-asset downloads are CDN-served
 * and not API-metered, reading this manifest is how an update check avoids GitHub's
 * 60-requests-per-hour API limit entirely.
 */
internal data class UpdateFeedManifest(
    val channel: String,
    val tag: String,
    val versionName: String,
    val versionCode: Long,
    val commit: String?,
    val publishedAt: String,
    val releaseUrl: String,
    val notes: String?,
    val assets: List<UpdateFeedAsset>,
)

internal const val UPDATE_FEED_MANIFEST_SCHEMA = 1

/**
 * Parses a manifest for the channel named [expectedChannel] ("nightly" / "stable"). This is the
 * single defensive boundary between the feed and the updater: anything unexpected — bad JSON, a
 * schema this build does not speak, a manifest for the other channel, a missing or non-positive
 * versionCode — is a miss (null) that sends the updater to its API fallback, never a crash.
 * Unknown fields are ignored so an older build survives a within-v1 field addition.
 */
internal fun parseUpdateFeedManifest(
    text: String,
    expectedChannel: String,
): UpdateFeedManifest? =
    runCatching {
        val root = Json.parseToJsonElement(text).jsonObject
        if (root["schema"]?.jsonPrimitive?.intOrNull != UPDATE_FEED_MANIFEST_SCHEMA) return@runCatching null
        val channel =
            root["channel"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@runCatching null
        if (channel != expectedChannel) return@runCatching null
        val versionCode =
            root["versionCode"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 } ?: return@runCatching null
        val tag = root["tag"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@runCatching null
        UpdateFeedManifest(
            channel = channel,
            tag = tag,
            versionName = root["versionName"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: tag,
            versionCode = versionCode,
            commit = root["commit"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            publishedAt = root["publishedAt"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            releaseUrl = root["releaseUrl"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            notes = root["notes"]?.jsonPrimitive?.contentOrNull,
            assets =
                root["assets"]?.jsonArray.orEmpty().mapNotNull { element ->
                    val asset = element.jsonObject
                    val name = asset["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val url = asset["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    UpdateFeedAsset(
                        name = name,
                        url = url,
                        size = asset["size"]?.jsonPrimitive?.longOrNull,
                        sha256 = asset["sha256"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                    )
                },
        )
    }.getOrNull()

/**
 * Picks this build's APK from the manifest's assets by exact artifact name
 * (`app-<dist>-<device>-<abi>-<suffix>.apk`, the same names the release workflows upload). Null
 * when the manifest carries no asset for this device — the caller then falls back to the API
 * release list instead of offering an update whose APK does not exist.
 */
internal fun selectUpdateFeedAssetUrl(
    assets: List<UpdateFeedAsset>,
    expectedArtifactName: String,
): String? = assets.firstOrNull { it.name == expectedArtifactName }?.url?.takeIf { it.isNotBlank() }

/**
 * Maps a manifest into the updater's existing [ReleaseInfo] model, so the "(build n)" comparison
 * and the asset download/install path are unchanged. [assetUrl] is the selected asset's download
 * URL; [releasesBaseUrl] (e.g. "https://github.com/NatuneGroup/ArchiveTune/releases") fills in a
 * release link when the manifest omits one. The manifest's [versionCode] rides on the release
 * model's build-number field, which is what the > comparison consumes.
 */
internal fun UpdateFeedManifest.toReleaseInfo(
    assetUrl: String?,
    releasesBaseUrl: String,
): ReleaseInfo =
    ReleaseInfo(
        tagName = tag,
        name = versionName,
        body = notes,
        publishedAt = publishedAt,
        htmlUrl = releaseUrl.ifBlank { "$releasesBaseUrl/tag/$tag" },
        downloadUrl = assetUrl,
        versionCode = versionCode.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
    )
