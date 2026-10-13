/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.utils

import androidx.datastore.preferences.core.edit
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import moe.rukamori.archivetune.App
import moe.rukamori.archivetune.BuildConfig
import moe.rukamori.archivetune.constants.UpdateChannel
import moe.rukamori.archivetune.constants.NightlyReleasesEtagKey
import moe.rukamori.archivetune.constants.NightlyReleasesFingerprintKey
import moe.rukamori.archivetune.constants.NightlyReleasesJsonKey
import moe.rukamori.archivetune.constants.NightlyReleasesLastCheckedAtKey
import moe.rukamori.archivetune.constants.GitHubReleasesEtagKey
import moe.rukamori.archivetune.constants.GitHubReleasesFingerprintKey
import moe.rukamori.archivetune.constants.GitHubReleasesJsonKey
import moe.rukamori.archivetune.constants.GitHubReleasesLastCheckedAtKey
import org.json.JSONArray
import org.json.JSONObject

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
)

private data class ReleasesNetworkResult(
    val status: HttpStatusCode,
    val body: String?,
    val etag: String?,
)

object Updater {
    // The engine is named because the default one is whichever ServiceLoader finds first, and CIO
    // (also on the classpath) ignores the proxy.
    private val client =
        HttpClient(OkHttp) {
            engine {
                config {
                    withAppProxy()
                }
            }
        }
    private const val ReleaseCacheCheckIntervalMs: Long = 6 * 60 * 60 * 1000L
    private const val NightlyCacheCheckIntervalMs: Long = 15 * 60 * 1000L
    private const val OWNER = "NatuneGroup/ArchiveTune"
    private const val StableReleaseBaseUrl = "https://github.com/$OWNER/releases"

    // The pre-release channel is served as a GitHub pre-release on this repo. NightlyReleaseBaseUrl
    // is kept as a name because the feed shares the stable channel's releases URL.
    private const val NightlyReleaseBaseUrl =
        "https://github.com/$OWNER/releases"

    /** The pre-release update feed. Nightly tracks `dev`. */
    private enum class PreChannel(
        val artifactSuffix: String,
        val tagPrefix: String,
        val workflowFile: String,
        val branch: String,
    ) {
        NIGHTLY("nightly", "N", "nightly.yml", "dev"),
        ;

        val tagRegex: Regex get() = Regex("""$tagPrefix\d{8}(?:\d{4})?""")
    }

    private fun preWorkflowRunsUrl(channel: PreChannel): String =
        "https://api.github.com/repos/$OWNER/actions/workflows/${channel.workflowFile}/runs" +
            "?branch=${channel.branch}&status=success&per_page=1&exclude_pull_requests=true"

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

    private fun workflowArtifactName(channel: PreChannel): String =
        "app-$releaseArtifactPrefix${BuildConfig.DEVICE}-${BuildConfig.ARCHITECTURE}-${channel.artifactSuffix}"

    private fun workflowArtifactDownloadUrl(channel: PreChannel): String {
        val workflowName = channel.workflowFile.removeSuffix(".yml")
        val artifactUrl =
            "https://nightly.link/$OWNER/workflows/$workflowName/${channel.branch}/${workflowArtifactName(channel)}"
        return if (canDownloadUpdatesDirectly) "$artifactUrl.zip" else artifactUrl
    }

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
        val nonPreRelease = releases.filterNot { preReleaseTagRegex.matches(it.tagName) }
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

    private fun findLatestPreRelease(
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
        return when (response.status) {
            HttpStatusCode.NotModified -> {
                ReleasesNetworkResult(
                    status = response.status,
                    body = null,
                    etag = cachedEtag ?: etag,
                )
            }

            else -> {
                ReleasesNetworkResult(
                    status = response.status,
                    body = response.bodyAsText(),
                    etag = etag,
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

            while (pageUrl != null) {
                val response = client.get(pageUrl) {
                    headers {
                        append("Accept", "application/vnd.github+json")
                        append("User-Agent", "ArchiveTune")
                    }
                }
                if (response.status.value !in 200..299) break

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
    // for older releases that predate the marker.
    private val nightlyBuildMarkerRegex = Regex("""at-build:(\d+)""")

    internal fun nightlyBuildNumber(release: ReleaseInfo): Int? =
        release.body?.let { nightlyBuildMarkerRegex.find(it)?.groupValues?.get(1)?.toIntOrNull() }
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

    suspend fun getAllNightlyReleases(perPage: Int = 10, forceRefresh: Boolean = false): Result<List<ReleaseInfo>> =
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
        perPage: Int = 10,
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

            val shouldCheckNetwork =
                forceRefresh || cachedJson.isNullOrBlank() || (now - lastCheckedAt) >= NightlyCacheCheckIntervalMs

            if (!shouldCheckNetwork) {
                return@runCatchingCancellable cachedReleases ?: emptyList()
            }

            val networkResult =
                try {
                    fetchPreReleasesNetwork(
                        perPage = perPage,
                        cachedEtag = cachedEtag,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }

            when {
                networkResult?.status == HttpStatusCode.NotModified && cachedReleases != null -> {
                    App.instance.dataStore.edit { settings ->
                        settings[NightlyReleasesLastCheckedAtKey] = now
                        networkResult.etag?.let { settings[NightlyReleasesEtagKey] = it }
                    }
                    return@runCatchingCancellable cachedReleases
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
                        return@runCatchingCancellable releases
                    }
                }
            }

            val releasePageFallback =
                try {
                    fetchLatestPreReleasePage(channel)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
            val workflowFallback =
                if (releasePageFallback == null) {
                    try {
                        fetchLatestWorkflowRelease(channel)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }
            val fallbackRelease = releasePageFallback ?: workflowFallback
            if (fallbackRelease != null) {
                val cachedLatest = cachedReleases?.let { findLatestPreRelease(it, channel) }
                if (
                    cachedLatest != null &&
                    findLatestPreRelease(listOf(cachedLatest, fallbackRelease), channel) === cachedLatest
                ) {
                    return@runCatchingCancellable cachedReleases
                }

                val fallbackReleases = listOf(fallbackRelease)
                val fallbackJson = encodeReleasesJson(fallbackReleases)
                App.instance.dataStore.edit { settings ->
                    settings[NightlyReleasesLastCheckedAtKey] = now
                    settings.remove(NightlyReleasesEtagKey)
                    settings[NightlyReleasesJsonKey] = fallbackJson
                    settings[NightlyReleasesFingerprintKey] = getPreReleaseTopFingerprint(fallbackReleases, channel)
                }
                return@runCatchingCancellable fallbackReleases
            }

            cachedReleases ?: throw IllegalStateException("No ${channel.name.lowercase()} update source is currently available")
        }
    }

    private suspend fun fetchLatestPreReleasePage(channel: PreChannel): ReleaseInfo? {
        val response: HttpResponse =
            client.get("$NightlyReleaseBaseUrl/latest") {
                headers {
                    append("User-Agent", "ArchiveTune")
                }
            }
        response.bodyAsText()
        if (response.status.value !in 200..299) return null

        val resolvedUrl = response.call.request.url.toString()
        val tagName =
            resolvedUrl
                .substringAfter("/tag/", missingDelimiterValue = "")
                .substringBefore('?')
                .trimEnd('/')
        // `/releases/latest` resolves to whatever the newest published release is, which may belong
        // to the OTHER pre-release channel (or a stable). Only accept it for this channel's prefix.
        if (!channel.tagRegex.matches(tagName)) return null

        val date = tagName.removePrefix(channel.tagPrefix)
        return ReleaseInfo(
            tagName = tagName,
            name = tagName,
            body = null,
            publishedAt =
                "${date.substring(0, 4)}-${date.substring(4, 6)}-${date.substring(6, 8)}T00:00:00Z",
            htmlUrl = resolvedUrl,
            downloadUrl = "$NightlyReleaseBaseUrl/download/$tagName/${preReleaseArtifactName(channel)}",
        )
    }

    private suspend fun fetchPreReleasesNetwork(
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
        return when (response.status) {
            HttpStatusCode.NotModified -> {
                ReleasesNetworkResult(
                    status = response.status,
                    body = null,
                    etag = cachedEtag ?: etag,
                )
            }

            else -> {
                ReleasesNetworkResult(
                    status = response.status,
                    body = response.bodyAsText(),
                    etag = etag,
                )
            }
        }
    }

    private suspend fun fetchLatestWorkflowRelease(channel: PreChannel): ReleaseInfo? {
        val response: HttpResponse =
            client.get(preWorkflowRunsUrl(channel)) {
                headers {
                    append("Accept", "application/vnd.github+json")
                    append("User-Agent", "ArchiveTune")
                }
            }
        val responseBody = response.bodyAsText()
        if (response.status.value !in 200..299) return null

        val workflowRun =
            JSONObject(responseBody)
                .optJSONArray("workflow_runs")
                ?.optJSONObject(0)
                ?: return null
        val publishedAt =
            workflowRun
                .optString("run_started_at")
                .ifBlank { workflowRun.optString("created_at") }
        val date = publishedAt.take(10).filter(Char::isDigit)
        if (date.length != 8) return null

        val tagName = "${channel.tagPrefix}$date"
        return ReleaseInfo(
            tagName = tagName,
            name = tagName,
            body = null,
            publishedAt = publishedAt,
            htmlUrl = workflowRun.optString("html_url"),
            downloadUrl = workflowArtifactDownloadUrl(channel),
        )
    }

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

            val shouldCheckNetwork =
                forceRefresh || cachedJson.isNullOrBlank() || (now - lastCheckedAt) >= ReleaseCacheCheckIntervalMs

            if (!shouldCheckNetwork) {
                lastCheckTime = now
                return@runCatchingCancellable cachedReleases ?: emptyList()
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

            if (networkResult == null) {
                val fallback = cachedReleases
                if (fallback != null) {
                    lastCheckTime = now
                    return@runCatchingCancellable fallback
                }
                throw IllegalStateException("Failed to fetch releases")
            }

            when {
                networkResult.status == HttpStatusCode.NotModified -> {
                    App.instance.dataStore.edit { settings ->
                        settings[GitHubReleasesLastCheckedAtKey] = now
                        networkResult.etag?.let { settings[GitHubReleasesEtagKey] = it }
                    }
                    val fallback = cachedReleases
                    if (fallback != null) {
                        lastCheckTime = now
                        return@runCatchingCancellable fallback
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
