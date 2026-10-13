/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.utils

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import moe.rukamori.archivetune.App
import moe.rukamori.archivetune.BuildConfig
import moe.rukamori.archivetune.constants.UpdateChannel
import moe.rukamori.archivetune.constants.GitHubCommitsBranchKey
import moe.rukamori.archivetune.constants.GitHubCommitsEtagKey
import moe.rukamori.archivetune.constants.GitHubCommitsJsonKey
import moe.rukamori.archivetune.constants.GitHubCommitsLastCheckedAtKey
import moe.rukamori.archivetune.constants.GitHubRateLimitedUntilKey
import moe.rukamori.archivetune.constants.NightlyReleasesEtagKey
import moe.rukamori.archivetune.constants.NightlyReleasesFingerprintKey
import moe.rukamori.archivetune.constants.NightlyReleasesJsonKey
import moe.rukamori.archivetune.constants.NightlyReleasesLastCheckedAtKey
import moe.rukamori.archivetune.constants.GitHubReleasesEtagKey
import moe.rukamori.archivetune.constants.GitHubReleasesFingerprintKey
import moe.rukamori.archivetune.constants.GitHubReleasesJsonKey
import moe.rukamori.archivetune.constants.GitHubReleasesLastCheckedAtKey
import moe.rukamori.archivetune.constants.UpdateFeedManifestNightlyJsonKey
import moe.rukamori.archivetune.constants.UpdateFeedManifestNightlyLastCheckedAtKey
import moe.rukamori.archivetune.constants.UpdateFeedManifestStableJsonKey
import moe.rukamori.archivetune.constants.UpdateFeedManifestStableLastCheckedAtKey
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class GitCommit(
    val sha: String,
    val message: String,
    val author: String,
    val date: String,
    val url: String,
    val authorAvatarUrl: String? = null,
)

data class ReleaseInfo(
    val tagName: String,
    val name: String,
    val body: String?,
    val publishedAt: String,
    val htmlUrl: String,
    val downloadUrl: String? = null,
    // The monotonic build number (git commit count) the release was built from. Set by the
    // update-feed manifest mapping; API-sourced releases carry it inside the `at-build:` body
    // marker instead, which [nightlyBuildNumber] falls back to.
    val versionCode: Int? = null,
)

private data class ReleasesNetworkResult(
    val status: HttpStatusCode,
    val body: String?,
    val etag: String?,
    val rateLimitRemaining: String? = null,
    val rateLimitReset: String? = null,
)

object Updater {
    // The engine is named because the default one is whichever ServiceLoader finds first, and CIO
    // (also on the classpath) ignores the proxy.
    private val client =
        HttpClient(OkHttp) {
            engine {
                config {
                    withAppProxy()
                    // Every request through this client is a small JSON GET (feeds, commit pages),
                    // so bound them: an update check must not hang on a stuck connection, and the
                    // manifest fetch runs ahead of every fallback.
                    connectTimeout(10, TimeUnit.SECONDS)
                    callTimeout(15, TimeUnit.SECONDS)
                }
            }
        }
    private const val ReleaseCacheCheckIntervalMs: Long = 6 * 60 * 60 * 1000L
    private const val NightlyCacheCheckIntervalMs: Long = 15 * 60 * 1000L
    private const val CommitHistoryCacheIntervalMs: Long = 15 * 60 * 1000L
    private const val OWNER = "NatuneGroup/ArchiveTune"
    private const val StableReleaseBaseUrl = "https://github.com/$OWNER/releases"

    // The pre-release channel is served as a GitHub pre-release on this repo. NightlyReleaseBaseUrl
    // is kept as a name because the feed shares the stable channel's releases URL.
    private const val NightlyReleaseBaseUrl =
        "https://github.com/$OWNER/releases"

    /**
     * The fixed release whose assets are the keyless update-feed manifests
     * (`releases/download/update-feed/<channel>.json`). It is a prerelease marked not-latest, and
     * both tag filters reject it, so it can never be picked as an update.
     */
    private const val UpdateFeedTag = "update-feed"

    /** The pre-release update feed. Nightly tracks `dev`. */
    internal enum class PreChannel(
        val artifactSuffix: String,
        val tagPrefix: String,
        val branch: String,
    ) {
        NIGHTLY("nightly", "N", "dev"),
        ;

        val tagRegex: Regex get() = Regex("""$tagPrefix\d{8}(?:\d{4})?""")
    }

    var lastCheckTime = -1L
        private set
    private var latestReleaseTag: String? = null
    private var latestReleaseDownloadUrl: String? = null

    // Per-channel so a channel switch never serves the other feed's cached tag/url from a
    // previous resolve.
    private val latestPreReleaseTag = mutableMapOf<PreChannel, String?>()
    private val latestPreReleaseDownloadUrl = mutableMapOf<PreChannel, String?>()

    private val isUpdaterDistribution: Boolean
        get() =
            BuildConfig.UPDATER_AVAILABLE &&
                when (BuildConfig.DISTRIBUTION) {
                    "gms", "foss" -> true
                    else -> false
                }

    private val canDownloadUpdatesDirectly: Boolean
        get() = BuildConfig.DISTRIBUTION == "gms"

    private val releaseArtifactPrefix: String
        get() =
            when (BuildConfig.DISTRIBUTION) {
                "gms" -> "gms-"
                "foss" -> "foss-"
                else -> ""
            }

    private fun stableReleaseArtifactName(): String =
        "app-$releaseArtifactPrefix${BuildConfig.DEVICE}-${BuildConfig.ARCHITECTURE}-release.apk"

    private fun preReleaseArtifactName(channel: PreChannel): String =
        "app-$releaseArtifactPrefix${BuildConfig.DEVICE}-${BuildConfig.ARCHITECTURE}-${channel.artifactSuffix}.apk"

    private data class SemVer(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val preRelease: List<PreReleaseIdentifier>,
    ) : Comparable<SemVer> {
        override fun compareTo(other: SemVer): Int {
            val majorCompare = major.compareTo(other.major)
            if (majorCompare != 0) return majorCompare
            val minorCompare = minor.compareTo(other.minor)
            if (minorCompare != 0) return minorCompare
            val patchCompare = patch.compareTo(other.patch)
            if (patchCompare != 0) return patchCompare

            val thisIsStable = preRelease.isEmpty()
            val otherIsStable = other.preRelease.isEmpty()
            if (thisIsStable && !otherIsStable) return 1
            if (!thisIsStable && otherIsStable) return -1

            val maxIndex = minOf(preRelease.size, other.preRelease.size)
            for (i in 0 until maxIndex) {
                val c = preRelease[i].compareTo(other.preRelease[i])
                if (c != 0) return c
            }
            return preRelease.size.compareTo(other.preRelease.size)
        }

        fun normalizedName(): String =
            if (preRelease.isEmpty()) {
                "$major.$minor.$patch"
            } else {
                "$major.$minor.$patch-" + preRelease.joinToString(".") { it.raw }
            }
    }

    private sealed interface PreReleaseIdentifier : Comparable<PreReleaseIdentifier> {
        val raw: String
    }

    private data class NumericIdentifier(
        override val raw: String,
        val value: Long,
    ) : PreReleaseIdentifier {
        override fun compareTo(other: PreReleaseIdentifier): Int =
            when (other) {
                is NumericIdentifier -> value.compareTo(other.value)
                is AlphaIdentifier -> -1
            }
    }

    private data class AlphaIdentifier(
        override val raw: String,
    ) : PreReleaseIdentifier {
        override fun compareTo(other: PreReleaseIdentifier): Int =
            when (other) {
                is NumericIdentifier -> 1
                is AlphaIdentifier -> raw.compareTo(other.raw)
            }
    }

    private val semVerRegex =
        Regex("""(?i)\bv?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?\b""")
    // Pre-release tags include a date and usually an HHmm suffix (e.g. NyyyyMMddHHmm for Nightly).
    // Accept the older date-only form too so workflow fallback stays compatible. The C prefix of
    // the retired Canary channel matches as well, so its leftover releases never reach the stable
    // feed.
    private val preReleaseTagRegex = Regex("""[NC]\d{8}(?:\d{4})?""")

    private fun parseSemVerOrNull(text: String): SemVer? {
        val match = semVerRegex.find(text) ?: return null
        val major = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val minor = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return null
        val patch = match.groupValues.getOrNull(3)?.toIntOrNull() ?: return null
        val preReleaseText = match.groupValues.getOrNull(4)?.takeIf { it.isNotBlank() }
        val preRelease =
            preReleaseText
                ?.split('.')
                ?.filter { it.isNotBlank() }
                ?.map { identifier ->
                    if (identifier.all { it.isDigit() }) {
                        NumericIdentifier(raw = identifier, value = identifier.toLong())
                    } else {
                        AlphaIdentifier(raw = identifier)
                    }
                }
                ?: emptyList()
        return SemVer(
            major = major,
            minor = minor,
            patch = patch,
            preRelease = preRelease,
        )
    }

    private fun parseReleaseSemVerOrNull(release: ReleaseInfo): SemVer? =
        parseSemVerOrNull(release.tagName) ?: parseSemVerOrNull(release.name)

    // Nightly builds share a single fixed display versionName (e.g. "13.7.5"), so the version *name*
    // can't distinguish two nightly builds. Instead each nightly build number is carried inline as a
    // human-readable suffix: "13.7.5 (build <versionCode>)". This string is both shown in the UI and
    // used for comparison — when it carries a build number we compare that monotonic number against
    // the running app's BuildConfig.VERSION_CODE, which is what makes "update available" true only
    // for a genuinely newer nightly build. The string never leaves the app (it is produced and parsed
    // by this same Updater), so the format is free to be display-friendly.
    private val buildNumberRegex = Regex("""\(build (\d+)\)""")

    internal fun buildNumberOrNull(version: String): Int? = buildNumberRegex.find(version)?.groupValues?.get(1)?.toIntOrNull()

    internal fun isSameVersion(
        a: String,
        b: String,
    ): Boolean {
        buildNumberOrNull(a)?.let { return it == BuildConfig.VERSION_CODE }
        buildNumberOrNull(b)?.let { return it == BuildConfig.VERSION_CODE }
        val aSemVer = parseSemVerOrNull(a)
        val bSemVer = parseSemVerOrNull(b)
        return if (aSemVer != null && bSemVer != null) {
            aSemVer.major == bSemVer.major &&
                aSemVer.minor == bSemVer.minor &&
                aSemVer.patch == bSemVer.patch &&
                aSemVer.preRelease == bSemVer.preRelease
        } else {
            a.trim() == b.trim()
        }
    }

    internal fun isUpdateAvailable(
        latestVersion: String,
        currentVersion: String,
    ): Boolean {
        // Nightly build-number comparison takes priority: a newer build number means an update is
        // available even though the display versionName is unchanged.
        buildNumberOrNull(latestVersion)?.let { return it > BuildConfig.VERSION_CODE }
        val latestSemVer = parseSemVerOrNull(latestVersion)
        val currentSemVer = parseSemVerOrNull(currentVersion)
        return if (latestSemVer != null && currentSemVer != null) {
            latestSemVer > currentSemVer
        } else {
            !isSameVersion(latestVersion, currentVersion)
        }
    }

    internal fun findLatestRelease(releases: List<ReleaseInfo>): ReleaseInfo? {
        if (releases.isEmpty()) return null

        // Exclude pre-release-tagged (Nightly N…, retired Canary C…) releases up front. Without
        // this filter, a nightly release whose *name* is "Nightly 13.7.5" would slip through the
        // `preRelease.isEmpty()` stable filter below — `parseReleaseSemVerOrNull` falls back to
        // parsing the release name when the tag itself isn't SemVer, and "13.7.5" has no
        // pre-release identifier — and a stable-channel user would see a pre-release popup.
        // The `update-feed` manifest release is excluded too: its tag carries no version, and with
        // no SemVer release on the page the last-resort fallback below could otherwise serve it.
        val nonPreRelease =
            releases.filterNot { preReleaseTagRegex.matches(it.tagName) || it.tagName == UpdateFeedTag }
        if (nonPreRelease.isEmpty()) return null

        val parsed =
            nonPreRelease.mapNotNull { release ->
                parseReleaseSemVerOrNull(release)?.let { version -> version to release }
            }

        if (parsed.isEmpty()) return nonPreRelease.firstOrNull()

        val stable = parsed.filter { it.first.preRelease.isEmpty() }
        val candidates = stable.ifEmpty { parsed }
        return candidates.maxWithOrNull(compareBy({ it.first }, { it.second.publishedAt }))?.second
    }

    internal fun findLatestPreRelease(
        releases: List<ReleaseInfo>,
        channel: PreChannel,
    ): ReleaseInfo? {
        return releases
            .filter { channel.tagRegex.matches(it.tagName) }
            .maxByOrNull { release ->
                val dateTag = release.tagName.removePrefix(channel.tagPrefix).takeWhile { it.isDigit() }
                dateTag.toLongOrNull() ?: 0L
            }
    }

    private fun preferredReleaseVersionNameOrNull(release: ReleaseInfo): String? =
        parseReleaseSemVerOrNull(release)?.normalizedName()

    internal fun getReleaseVersionName(release: ReleaseInfo): String =
        preferredReleaseVersionNameOrNull(release) ?: release.name.ifBlank { release.tagName }

    private fun parseReleasesJson(
        json: String,
        expectedArtifactName: String,
    ): List<ReleaseInfo> {
        val jsonArray = JSONArray(json)
        val releases = ArrayList<ReleaseInfo>(jsonArray.length())
        for (i in 0 until jsonArray.length()) {
            val item = jsonArray.getJSONObject(i)
            val assets = item.optJSONArray("assets")
            val assetDownloadUrl =
                assets?.let { releaseAssets ->
                    (0 until releaseAssets.length())
                        .asSequence()
                        .mapNotNull(releaseAssets::optJSONObject)
                        .firstOrNull { asset -> asset.optString("name") == expectedArtifactName }
                        ?.optString("browser_download_url")
                        ?.takeIf { it.isNotBlank() }
                }
            releases.add(
                ReleaseInfo(
                    tagName = item.optString("tag_name", ""),
                    name = item.optString("name", ""),
                    body = if (item.isNull("body")) null else item.optString("body"),
                    publishedAt = item.optString("published_at", ""),
                    htmlUrl = item.optString("html_url", ""),
                    downloadUrl =
                        if (item.isNull("download_url")) {
                            null
                        } else {
                            item.optString("download_url").takeIf { it.isNotBlank() }
                        }
                            ?: assetDownloadUrl,
                    versionCode = item.optInt("version_code", -1).takeIf { it > 0 },
                ),
            )
        }
        return releases
    }

    private fun encodeReleasesJson(releases: List<ReleaseInfo>): String =
        JSONArray().apply {
            releases.forEach { release ->
                put(
                    JSONObject().apply {
                        put("tag_name", release.tagName)
                        put("name", release.name)
                        put("body", release.body ?: JSONObject.NULL)
                        put("published_at", release.publishedAt)
                        put("html_url", release.htmlUrl)
                        put("download_url", release.downloadUrl ?: JSONObject.NULL)
                        release.versionCode?.let { put("version_code", it) }
                    },
                )
            }
        }.toString()

    private fun getTopReleaseFingerprint(releases: List<ReleaseInfo>): String {
        val latest = findLatestRelease(releases) ?: return ""
        return listOf(
            latest.tagName,
            latest.name,
            latest.publishedAt,
            latest.body.orEmpty(),
            latest.htmlUrl,
        ).joinToString("||")
    }

    private suspend fun fetchReleasesNetwork(
        perPage: Int,
        cachedEtag: String?,
    ): ReleasesNetworkResult {
        val response: HttpResponse =
            client.get("https://api.github.com/repos/$OWNER/releases?per_page=$perPage") {
                headers {
                    append("Accept", "application/vnd.github+json")
                    append("User-Agent", "ArchiveTune")
                    if (!cachedEtag.isNullOrBlank()) {
                        append("If-None-Match", cachedEtag)
                    }
                }
            }
        val etag = response.headers["ETag"]
        val rateLimitRemaining = response.headers["X-RateLimit-Remaining"]
        val rateLimitReset = response.headers["X-RateLimit-Reset"]
        return when (response.status) {
            HttpStatusCode.NotModified -> {
                ReleasesNetworkResult(
                    status = response.status,
                    body = null,
                    etag = cachedEtag ?: etag,
                    rateLimitRemaining = rateLimitRemaining,
                    rateLimitReset = rateLimitReset,
                )
            }

            else -> {
                ReleasesNetworkResult(
                    status = response.status,
                    body = response.bodyAsText(),
                    etag = etag,
                    rateLimitRemaining = rateLimitRemaining,
                    rateLimitReset = rateLimitReset,
                )
            }
        }
    }

    suspend fun getCachedReleases(): List<ReleaseInfo> {
        if (!isUpdaterDistribution) {
            return emptyList()
        }

        val cachedJson = App.instance.dataStore.getAsync(GitHubReleasesJsonKey)
        return cachedJson
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { parseReleasesJson(it, stableReleaseArtifactName()) }.getOrNull() }
            ?: emptyList()
    }

    suspend fun getLatestVersionName(): Result<String> = getLatestReleaseInfo().map(::getReleaseVersionName)

    suspend fun getLatestReleaseNotes(): Result<String?> = getLatestReleaseInfo().map { it.body }

    suspend fun getLatestReleaseInfo(forceRefresh: Boolean = false): Result<ReleaseInfo> =
        runCatchingCancellable {
            if (!isUpdaterDistribution) {
                throw IllegalStateException("Updater is not available for this distribution")
            }

            val releases = getAllReleases(forceRefresh = forceRefresh).getOrThrow()
            val latest =
                findLatestRelease(releases)
                    ?: throw IllegalStateException("No releases found")
            lastCheckTime = System.currentTimeMillis()
            latestReleaseTag = latest.tagName
            latestReleaseDownloadUrl = latest.downloadUrl
            latest
        }

    /**
     * The branch each update channel is built from, so the Updates page can show the commits that
     * will actually reach the reader rather than always showing `main`.
     */
    fun branchForChannel(channel: UpdateChannel): String =
        when (channel) {
            UpdateChannel.STABLE -> "main"
            UpdateChannel.NIGHTLY -> PreChannel.NIGHTLY.branch
        }

    suspend fun getCommitHistory(
        count: Int = 20,
        branch: String = "main",
    ): Result<List<GitCommit>> =
        runCatchingCancellable {
            if (!isUpdaterDistribution) {
                return@runCatchingCancellable emptyList()
            }

            val now = System.currentTimeMillis()
            val cachedBranch = App.instance.dataStore.getAsync(GitHubCommitsBranchKey)
            val cachedJson = App.instance.dataStore.getAsync(GitHubCommitsJsonKey)
            val cachedEtag = App.instance.dataStore.getAsync(GitHubCommitsEtagKey)
            val lastCheckedAt = App.instance.dataStore.getAsync(GitHubCommitsLastCheckedAtKey, 0L)
            // Only a page fetched for the same branch is usable: a channel switch (main ↔ dev)
            // must not serve the other branch's history.
            val cachedCommits = cachedJson?.takeIf { it.isNotBlank() }?.let(::parseCommitHistoryJson)
            val cachedCommitsForBranch = cachedCommits?.takeIf { cachedBranch == branch }

            if (cachedCommitsForBranch != null && now - lastCheckedAt < CommitHistoryCacheIntervalMs) {
                return@runCatchingCancellable cachedCommitsForBranch
            }

            // The Updates screen fires this request on every open; while the rate-limit backoff is
            // active api.github.com is off limits and the cached page is served instead.
            if (rateLimitBackoffActive(now)) {
                if (cachedCommitsForBranch != null) {
                    return@runCatchingCancellable cachedCommitsForBranch
                }
                throw IllegalStateException("GitHub API is rate limited and no cached commit history exists")
            }

            // GitHub's `/commits` endpoint caps `per_page` at 100. To honour a request larger
            // than that (or an "unlimited" request when callers pass a very large `count`),
            // we paginate by following the `Link: rel="next"` header until we've collected
            // `count` commits or run out of pages. A `count <= 0` is treated as unlimited
            // and pages until the API stops returning a next link.
            val perPage = if (count <= 0) 100 else count.coerceAtMost(100)
            val unlimited = count <= 0
            val commits = mutableListOf<GitCommit>()
            var pageUrl: String? =
                "https://api.github.com/repos/$OWNER/commits?sha=$branch&per_page=$perPage&page=1"
            var firstPageEtag: String? = null
            var onFirstPage = true

            while (pageUrl != null) {
                val response = client.get(pageUrl) {
                    headers {
                        append("Accept", "application/vnd.github+json")
                        append("User-Agent", "ArchiveTune")
                        if (onFirstPage && !cachedEtag.isNullOrBlank() && cachedCommitsForBranch != null) {
                            append("If-None-Match", cachedEtag)
                        }
                    }
                }
                if (onFirstPage) {
                    onFirstPage = false
                    recordRateLimitBackoff(
                        response.status.value,
                        response.headers["X-RateLimit-Remaining"],
                        response.headers["X-RateLimit-Reset"],
                        now,
                    )
                    if (response.status == HttpStatusCode.NotModified && cachedCommitsForBranch != null) {
                        App.instance.dataStore.edit { settings ->
                            settings[GitHubCommitsLastCheckedAtKey] = now
                            response.headers["ETag"]?.let { settings[GitHubCommitsEtagKey] = it }
                        }
                        return@runCatchingCancellable cachedCommitsForBranch
                    }
                    if (response.status.value !in 200..299) {
                        if (cachedCommitsForBranch != null) {
                            return@runCatchingCancellable cachedCommitsForBranch
                        }
                        throw IllegalStateException("Failed to fetch commit history: HTTP ${response.status.value}")
                    }
                    firstPageEtag = response.headers["ETag"]
                }

                val body = response.bodyAsText()
                val jsonArray = JSONArray(body)
                if (jsonArray.length() == 0) break

                for (i in 0 until jsonArray.length()) {
                    if (!unlimited && commits.size >= count) break
                    val commitObj = jsonArray.getJSONObject(i)
                    val commit = commitObj.getJSONObject("commit")
                    val authorObj = commit.optJSONObject("author")
                    val githubAuthorObj = commitObj.optJSONObject("author")
                    commits.add(
                        GitCommit(
                            sha = commitObj.optString("sha", "").take(7),
                            message = commit.optString("message", "").lines().firstOrNull() ?: "",
                            author = authorObj?.optString("name", "Unknown") ?: "Unknown",
                            date = authorObj?.optString("date", "") ?: "",
                            url = commitObj.optString("html_url", ""),
                            authorAvatarUrl = githubAuthorObj?.optString("avatar_url")?.takeIf { it.isNotBlank() },
                        ),
                    )
                }

                if (!unlimited && commits.size >= count) break

                // Parse `Link: <url>; rel="next", <url>; rel="last"` if present.
                pageUrl = response.headers["Link"]?.let { linkHeader ->
                    val nextRegex = Regex("""<([^>]+)>;\s*rel="next"""")
                    nextRegex.find(linkHeader)?.groupValues?.getOrNull(1)
                }
            }

            if (commits.isEmpty()) {
                // A usable cache beats an empty page on the Updates screen (e.g. pagination died
                // mid-way, or the API answered 2xx with nothing parseable).
                if (cachedCommitsForBranch != null) {
                    return@runCatchingCancellable cachedCommitsForBranch
                }
                return@runCatchingCancellable emptyList()
            }

            App.instance.dataStore.edit { settings ->
                settings[GitHubCommitsBranchKey] = branch
                settings[GitHubCommitsJsonKey] = encodeCommitHistory(commits)
                firstPageEtag?.let { settings[GitHubCommitsEtagKey] = it }
                settings[GitHubCommitsLastCheckedAtKey] = now
            }
            commits
        }

    fun getLatestDownloadUrl(): String {
        if (!isUpdaterDistribution) {
            return ""
        }

        if (!canDownloadUpdatesDirectly) {
            return "$StableReleaseBaseUrl/latest"
        }

        val artifactName = stableReleaseArtifactName()
        latestReleaseDownloadUrl?.let { return it }
        val tag = latestReleaseTag
        if (tag != null) {
            return "$StableReleaseBaseUrl/download/$tag/$artifactName"
        }
        return "$StableReleaseBaseUrl/latest/download/$artifactName"
    }

    // The nightly workflow embeds the build's monotonic versionCode in the release notes as
    // "at-build:<n>". Fall back to the numeric patch segment of the release name ("Nightly 13.7.<n>")
    // for older releases that predate the marker. A release mapped from the update-feed manifest
    // carries the number directly in [ReleaseInfo.versionCode].
    private val nightlyBuildMarkerRegex = Regex("""at-build:(\d+)""")

    internal fun nightlyBuildNumber(release: ReleaseInfo): Int? =
        release.versionCode
            ?: release.body?.let { nightlyBuildMarkerRegex.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: parseSemVerOrNull(release.name)?.patch

    /**
     * Converts a Nightly release into the same build-number form used everywhere in the updater.
     * Never compare a Nightly release name as SemVer: names contain the commit count in the patch
     * position, which makes an already-installed build look newer than its fixed display version.
     */
    internal fun getPreReleaseVersionName(release: ReleaseInfo): String {
        val buildNumber = nightlyBuildNumber(release)
        return if (buildNumber != null) {
            "${BuildConfig.VERSION_NAME} (build $buildNumber)"
        } else {
            release.tagName.ifBlank { release.name }
        }
    }

    // Public wrappers over the shared pre-release resolution below. Nightly tracks `dev`.
    fun getNightlyReleaseVersionName(release: ReleaseInfo): String = getPreReleaseVersionName(release)

    suspend fun getLatestNightlyVersionName(): Result<String> =
        getLatestPreReleaseInfo(PreChannel.NIGHTLY).map(::getPreReleaseVersionName)

    suspend fun getLatestNightlyReleaseNotes(): Result<String?> =
        getLatestPreReleaseInfo(PreChannel.NIGHTLY).map { it.body }

    suspend fun getLatestNightlyReleaseInfo(forceRefresh: Boolean = false): Result<ReleaseInfo> =
        getLatestPreReleaseInfo(PreChannel.NIGHTLY, forceRefresh)

    suspend fun getCachedNightlyReleases(): List<ReleaseInfo> = getCachedPreReleases(PreChannel.NIGHTLY)

    suspend fun getAllNightlyReleases(perPage: Int = 30, forceRefresh: Boolean = false): Result<List<ReleaseInfo>> =
        getAllPreReleases(PreChannel.NIGHTLY, perPage, forceRefresh)

    fun getLatestNightlyDownloadUrl(): String = getLatestPreReleaseDownloadUrl(PreChannel.NIGHTLY)

    private suspend fun getLatestPreReleaseInfo(
        channel: PreChannel,
        forceRefresh: Boolean = false,
    ): Result<ReleaseInfo> =
        runCatchingCancellable {
            if (!isUpdaterDistribution) {
                throw IllegalStateException("Updater is not available for this distribution")
            }

            val releases = getAllPreReleases(channel, forceRefresh = forceRefresh).getOrThrow()
            val latest =
                findLatestPreRelease(releases, channel)
                    ?: throw IllegalStateException("No ${channel.name.lowercase()} releases found")
            lastCheckTime = System.currentTimeMillis()
            latestPreReleaseTag[channel] = latest.tagName
            latestPreReleaseDownloadUrl[channel] = latest.downloadUrl
            latest
        }

    private suspend fun getCachedPreReleases(channel: PreChannel): List<ReleaseInfo> {
        if (!isUpdaterDistribution) {
            return emptyList()
        }

        val cachedJson = App.instance.dataStore.getAsync(NightlyReleasesJsonKey)
        return cachedJson
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { parseReleasesJson(it, preReleaseArtifactName(channel)) }.getOrNull() }
            ?: emptyList()
    }

    private suspend fun getAllPreReleases(
        channel: PreChannel,
        perPage: Int = 30,
        forceRefresh: Boolean = false,
    ): Result<List<ReleaseInfo>> {
        if (!isUpdaterDistribution) {
            return Result.success(emptyList())
        }

        return runCatchingCancellable {
            val now = System.currentTimeMillis()
            val cachedJson = App.instance.dataStore.getAsync(NightlyReleasesJsonKey)
            val cachedEtag = App.instance.dataStore.getAsync(NightlyReleasesEtagKey)
            val lastCheckedAt = App.instance.dataStore.getAsync(NightlyReleasesLastCheckedAtKey, 0L)
            val cachedFingerprint = App.instance.dataStore.getAsync(NightlyReleasesFingerprintKey)

            val cachedReleases =
                cachedJson
                    ?.takeIf { it.isNotBlank() }
                    ?.let { runCatching { parseReleasesJson(it, preReleaseArtifactName(channel)) }.getOrNull() }

            val feedChannel = UpdateChannel.NIGHTLY
            val cachedManifestRelease = readCachedUpdateFeed(feedChannel, preReleaseArtifactName(channel))

            val manifestFresh = isUpdateFeedCacheFresh(feedChannel, now)
            val releasesFresh = !cachedJson.isNullOrBlank() && (now - lastCheckedAt) < NightlyCacheCheckIntervalMs

            if (!forceRefresh && (manifestFresh || releasesFresh)) {
                lastCheckTime = now
                return@runCatchingCancellable mergeManifestRelease(cachedReleases, cachedManifestRelease)
            }

            // Manifest first: release-asset downloads are CDN-served, unauthenticated and not
            // API-metered, so the fixed feed asset is read before every api.github.com call. A
            // miss (bad JSON, wrong channel, no asset for this device, HTTP failure) is silent —
            // the API below is the fallback, not an error.
            fetchUpdateFeed(feedChannel, preReleaseArtifactName(channel))?.let { fetched ->
                val merged = mergeManifestRelease(cachedReleases, fetched.release)
                App.instance.dataStore.edit { settings ->
                    settings[manifestJsonKey(feedChannel)] = fetched.rawJson
                    settings[manifestCheckedAtKey(feedChannel)] = now
                    settings[NightlyReleasesLastCheckedAtKey] = now
                    settings[NightlyReleasesJsonKey] = encodeReleasesJson(merged)
                    settings[NightlyReleasesFingerprintKey] = getPreReleaseTopFingerprint(merged, channel)
                }
                lastCheckTime = now
                return@runCatchingCancellable merged
            }

            // Manifest miss. api.github.com is the fallback, and while the rate-limit backoff is
            // active it is off limits entirely: serve whatever cache exists until the reset passes.
            if (rateLimitBackoffActive(now)) {
                lastCheckTime = now
                val cached = mergeManifestRelease(cachedReleases, cachedManifestRelease)
                if (cached.isNotEmpty()) {
                    return@runCatchingCancellable cached
                }
                throw IllegalStateException("GitHub API is rate limited and no cached releases exist")
            }

            val networkResult =
                try {
                    fetchReleasesNetwork(
                        perPage = perPage,
                        cachedEtag = cachedEtag,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
            networkResult?.let { recordRateLimitBackoff(it.status.value, it.rateLimitRemaining, it.rateLimitReset, now) }

            when {
                networkResult?.status == HttpStatusCode.NotModified && cachedReleases != null -> {
                    App.instance.dataStore.edit { settings ->
                        settings[NightlyReleasesLastCheckedAtKey] = now
                        networkResult.etag?.let { settings[NightlyReleasesEtagKey] = it }
                    }
                    lastCheckTime = now
                    return@runCatchingCancellable mergeManifestRelease(cachedReleases, cachedManifestRelease)
                }

                networkResult != null &&
                    networkResult.status.value in 200..299 &&
                    !networkResult.body.isNullOrBlank() -> {
                    val networkBody = networkResult.body
                    val releases = parseReleasesJson(networkBody, preReleaseArtifactName(channel))
                        .filter { channel.tagRegex.matches(it.tagName) }
                    if (releases.isNotEmpty()) {
                        val newFingerprint = getPreReleaseTopFingerprint(releases, channel)
                        val hasPayloadChanged = cachedJson != networkBody
                        val hasTopReleaseChanged = cachedFingerprint != newFingerprint

                        App.instance.dataStore.edit { settings ->
                            settings[NightlyReleasesLastCheckedAtKey] = now
                            networkResult.etag?.let { settings[NightlyReleasesEtagKey] = it }
                            if (hasPayloadChanged || hasTopReleaseChanged || cachedJson.isNullOrBlank()) {
                                settings[NightlyReleasesJsonKey] = networkBody
                                settings[NightlyReleasesFingerprintKey] = newFingerprint
                            }
                        }
                        lastCheckTime = now
                        return@runCatchingCancellable releases
                    }
                }
            }

            val cached = mergeManifestRelease(cachedReleases, cachedManifestRelease)
            if (cached.isNotEmpty()) {
                lastCheckTime = now
                return@runCatchingCancellable cached
            }
            throw IllegalStateException("No ${channel.name.lowercase()} update source is currently available")
        }
    }

    // The old `/releases/latest` HTML-redirect and workflow-runs/nightly.link fallbacks are gone.
    // `/releases/latest` never resolves to a prerelease, and the workflow-runs path is API-metered
    // and reachable only through the pre-release fallback — exactly the requests the rate-limit
    // backoff exists to avoid. The keyless update-feed manifest plus the cached API page cover it.

    private fun getPreReleaseTopFingerprint(
        releases: List<ReleaseInfo>,
        channel: PreChannel,
    ): String {
        val latest = findLatestPreRelease(releases, channel) ?: return ""
        return listOf(
            latest.tagName,
            latest.name,
            latest.publishedAt,
            latest.body.orEmpty(),
            latest.htmlUrl,
        ).joinToString("||")
    }

    private fun getLatestPreReleaseDownloadUrl(channel: PreChannel): String {
        if (!isUpdaterDistribution) {
            return ""
        }

        if (!canDownloadUpdatesDirectly) {
            return "$NightlyReleaseBaseUrl/latest"
        }

        latestPreReleaseDownloadUrl[channel]?.let { return it }
        val artifactName = preReleaseArtifactName(channel)
        val tag = latestPreReleaseTag[channel]
        if (tag != null) {
            return "$NightlyReleaseBaseUrl/download/$tag/$artifactName"
        }
        return "$NightlyReleaseBaseUrl/latest/download/$artifactName"
    }

    // ── Keyless update feed (schema-v1 manifest) ──────────────────────────────────────────

    private data class UpdateFeedFetch(
        val rawJson: String,
        val release: ReleaseInfo,
    )

    private fun updateFeedManifestUrl(channel: UpdateChannel): String =
        "$NightlyReleaseBaseUrl/download/$UpdateFeedTag/${channel.name.lowercase()}.json"

    private fun manifestJsonKey(channel: UpdateChannel): Preferences.Key<String> =
        when (channel) {
            UpdateChannel.NIGHTLY -> UpdateFeedManifestNightlyJsonKey
            UpdateChannel.STABLE -> UpdateFeedManifestStableJsonKey
        }

    private fun manifestCheckedAtKey(channel: UpdateChannel): Preferences.Key<Long> =
        when (channel) {
            UpdateChannel.NIGHTLY -> UpdateFeedManifestNightlyLastCheckedAtKey
            UpdateChannel.STABLE -> UpdateFeedManifestStableLastCheckedAtKey
        }

    private fun manifestTtlMs(channel: UpdateChannel): Long =
        when (channel) {
            UpdateChannel.NIGHTLY -> NightlyCacheCheckIntervalMs
            UpdateChannel.STABLE -> ReleaseCacheCheckIntervalMs
        }

    /**
     * Maps raw manifest JSON into this build's release model, or null on any miss. An asset for
     * THIS device must exist: offering an update whose APK the manifest does not carry is worse
     * than falling back to the API release list.
     */
    private fun mapUpdateFeedBody(
        body: String,
        channel: UpdateChannel,
        expectedArtifactName: String,
    ): ReleaseInfo? {
        val manifest = parseUpdateFeedManifest(body, channel.name.lowercase()) ?: return null
        val assetUrl = selectUpdateFeedAssetUrl(manifest.assets, expectedArtifactName) ?: return null
        return manifest.toReleaseInfo(assetUrl, StableReleaseBaseUrl)
    }

    private suspend fun readCachedUpdateFeed(
        channel: UpdateChannel,
        expectedArtifactName: String,
    ): ReleaseInfo? =
        App.instance.dataStore
            .getAsync(manifestJsonKey(channel))
            ?.takeIf { it.isNotBlank() }
            ?.let { mapUpdateFeedBody(it, channel, expectedArtifactName) }

    private suspend fun isUpdateFeedCacheFresh(
        channel: UpdateChannel,
        now: Long,
    ): Boolean {
        val lastCheckedAt = App.instance.dataStore.getAsync(manifestCheckedAtKey(channel), 0L)
        return lastCheckedAt > 0 && now - lastCheckedAt < manifestTtlMs(channel)
    }

    /**
     * Fetches the channel's manifest from the fixed `update-feed` release asset. The asset URL is
     * served by a CDN redirect (followed by the engine), carries no Authorization header and does
     * not count against the api.github.com budget — which is why it is tried before every API
     * call. The engine-level call timeout bounds a hung redirect.
     */
    private suspend fun fetchUpdateFeed(
        channel: UpdateChannel,
        expectedArtifactName: String,
    ): UpdateFeedFetch? =
        try {
            val response =
                client.get(updateFeedManifestUrl(channel)) {
                    headers {
                        append("Accept", "application/vnd.github+json")
                        append("User-Agent", "ArchiveTune")
                    }
                }
            if (response.status.value !in 200..299) return null
            val body = response.bodyAsText()
            mapUpdateFeedBody(body, channel, expectedArtifactName)?.let { UpdateFeedFetch(rawJson = body, release = it) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }

    /** The manifest carries only the newest release; the cached API history is kept behind it. */
    private fun mergeManifestRelease(
        cached: List<ReleaseInfo>?,
        manifest: ReleaseInfo?,
    ): List<ReleaseInfo> =
        when (manifest) {
            null -> cached.orEmpty()
            else -> listOf(manifest) + cached.orEmpty().filter { it.tagName != manifest.tagName }
        }

    private suspend fun rateLimitBackoffActive(now: Long): Boolean =
        GitHubRateLimit.isBlocked(App.instance.dataStore.getAsync(GitHubRateLimitedUntilKey, 0L), now)

    private suspend fun recordRateLimitBackoff(
        statusCode: Int,
        rateLimitRemaining: String?,
        rateLimitReset: String?,
        nowMs: Long,
    ) {
        val until = GitHubRateLimit.backoffUntil(statusCode, rateLimitRemaining, rateLimitReset, nowMs) ?: return
        App.instance.dataStore.edit { settings -> settings[GitHubRateLimitedUntilKey] = until }
    }

    private fun encodeCommitHistory(commits: List<GitCommit>): String =
        buildJsonArray {
            commits.forEach { commit ->
                add(
                    buildJsonObject {
                        put("sha", commit.sha)
                        put("message", commit.message)
                        put("author", commit.author)
                        put("date", commit.date)
                        put("url", commit.url)
                        commit.authorAvatarUrl?.let { put("authorAvatarUrl", it) }
                    },
                )
            }
        }.toString()

    internal fun parseCommitHistoryJson(text: String): List<GitCommit>? =
        runCatching {
            Json.parseToJsonElement(text).jsonArray.map { element ->
                val commit = element.jsonObject
                GitCommit(
                    sha = commit["sha"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    message = commit["message"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    author = commit["author"]?.jsonPrimitive?.contentOrNull.orEmpty().ifEmpty { "Unknown" },
                    date = commit["date"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    url = commit["url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    authorAvatarUrl = commit["authorAvatarUrl"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                )
            }
        }.getOrNull()

    suspend fun getAllReleases(
        perPage: Int = 30,
        forceRefresh: Boolean = false,
    ): Result<List<ReleaseInfo>> {
        if (!isUpdaterDistribution) {
            return Result.success(emptyList())
        }

        return runCatchingCancellable {
            val now = System.currentTimeMillis()
            val cachedJson = App.instance.dataStore.getAsync(GitHubReleasesJsonKey)
            val cachedEtag = App.instance.dataStore.getAsync(GitHubReleasesEtagKey)
            val lastCheckedAt = App.instance.dataStore.getAsync(GitHubReleasesLastCheckedAtKey, 0L)
            val cachedFingerprint = App.instance.dataStore.getAsync(GitHubReleasesFingerprintKey)

            val cachedReleases =
                cachedJson
                    ?.takeIf { it.isNotBlank() }
                    ?.let { runCatching { parseReleasesJson(it, stableReleaseArtifactName()) }.getOrNull() }

            val feedChannel = UpdateChannel.STABLE
            val cachedManifestRelease = readCachedUpdateFeed(feedChannel, stableReleaseArtifactName())

            val manifestFresh = isUpdateFeedCacheFresh(feedChannel, now)
            val releasesFresh = !cachedJson.isNullOrBlank() && (now - lastCheckedAt) < ReleaseCacheCheckIntervalMs

            if (!forceRefresh && (manifestFresh || releasesFresh)) {
                lastCheckTime = now
                return@runCatchingCancellable mergeManifestRelease(cachedReleases, cachedManifestRelease)
            }

            // Manifest first (see getAllPreReleases): the fixed stable.json asset avoids the API
            // and its rate limit, and only a miss falls through to api.github.com.
            fetchUpdateFeed(feedChannel, stableReleaseArtifactName())?.let { fetched ->
                val merged = mergeManifestRelease(cachedReleases, fetched.release)
                App.instance.dataStore.edit { settings ->
                    settings[manifestJsonKey(feedChannel)] = fetched.rawJson
                    settings[manifestCheckedAtKey(feedChannel)] = now
                    settings[GitHubReleasesLastCheckedAtKey] = now
                    settings[GitHubReleasesJsonKey] = encodeReleasesJson(merged)
                    settings[GitHubReleasesFingerprintKey] = getTopReleaseFingerprint(merged)
                }
                lastCheckTime = now
                return@runCatchingCancellable merged
            }

            if (rateLimitBackoffActive(now)) {
                lastCheckTime = now
                val cached = mergeManifestRelease(cachedReleases, cachedManifestRelease)
                if (cached.isNotEmpty()) {
                    return@runCatchingCancellable cached
                }
                throw IllegalStateException("GitHub API is rate limited and no cached releases exist")
            }

            val networkResult =
                try {
                    fetchReleasesNetwork(
                        perPage = perPage,
                        cachedEtag = cachedEtag,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
            networkResult?.let { recordRateLimitBackoff(it.status.value, it.rateLimitRemaining, it.rateLimitReset, now) }

            if (networkResult == null) {
                val cached = mergeManifestRelease(cachedReleases, cachedManifestRelease)
                if (cached.isNotEmpty()) {
                    lastCheckTime = now
                    return@runCatchingCancellable cached
                }
                throw IllegalStateException("Failed to fetch releases")
            }

            when {
                networkResult.status == HttpStatusCode.NotModified -> {
                    App.instance.dataStore.edit { settings ->
                        settings[GitHubReleasesLastCheckedAtKey] = now
                        networkResult.etag?.let { settings[GitHubReleasesEtagKey] = it }
                    }
                    val cached = mergeManifestRelease(cachedReleases, cachedManifestRelease)
                    if (cached.isNotEmpty()) {
                        lastCheckTime = now
                        return@runCatchingCancellable cached
                    }
                    throw IllegalStateException("Release cache is empty")
                }

                networkResult.status.value in 200..299 && !networkResult.body.isNullOrBlank() -> {
                    val networkBody = networkResult.body
                    val releases = parseReleasesJson(networkBody, stableReleaseArtifactName())
                    val newFingerprint = getTopReleaseFingerprint(releases)
                    val hasPayloadChanged = cachedJson != networkBody
                    val hasTopReleaseChanged = cachedFingerprint != newFingerprint

                    App.instance.dataStore.edit { settings ->
                        settings[GitHubReleasesLastCheckedAtKey] = now
                        networkResult.etag?.let { settings[GitHubReleasesEtagKey] = it }
                        if (hasPayloadChanged || hasTopReleaseChanged || cachedJson.isNullOrBlank()) {
                            settings[GitHubReleasesJsonKey] = networkBody
                            settings[GitHubReleasesFingerprintKey] = newFingerprint
                        }
                    }
                    lastCheckTime = now
                    releases
                }

                else -> {
                    val fallback = cachedReleases
                    if (fallback != null) {
                        lastCheckTime = now
                        fallback
                    } else {
                        throw IllegalStateException("Failed to fetch releases: HTTP ${networkResult.status.value}")
                    }
                }
            }
        }
    }

    private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
}
