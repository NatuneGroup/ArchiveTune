/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 License Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.playback

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.audiosource.AudioSourceAttemptScope
import moe.rukamori.archivetune.audiosource.AudioSourceAttemptTimeouts
import moe.rukamori.archivetune.audiosource.DirectStream
import moe.rukamori.archivetune.audiosource.rethrowIfAudioSourceCancelled
import moe.rukamori.archivetune.constants.AudioSourceType
import moe.rukamori.archivetune.constants.QobuzInstancesKey
import moe.rukamori.archivetune.constants.QobuzTokensKey
import moe.rukamori.archivetune.constants.TidalAccessTokenKey
import moe.rukamori.archivetune.constants.TidalAccountFirstKey
import moe.rukamori.archivetune.constants.TidalAudioQuality
import moe.rukamori.archivetune.constants.TidalCountryCodeKey
import moe.rukamori.archivetune.constants.TidalInstancesKey
import moe.rukamori.archivetune.deezer.DeezerAudioProvider
import moe.rukamori.archivetune.jiosaavn.SaavnService
import moe.rukamori.archivetune.qobuz.QobuzAudioProvider
import moe.rukamori.archivetune.qobuz.QobuzBackupProvider
import moe.rukamori.archivetune.qobuz.QobuzToken
import moe.rukamori.archivetune.tidal.TidalAccountManager
import moe.rukamori.archivetune.tidal.TidalAudioProvider
import moe.rukamori.archivetune.tidal.TidalInstanceHealthManager
import moe.rukamori.archivetune.utils.PoolAccountManager
import moe.rukamori.archivetune.utils.dataStore
import timber.log.Timber
import java.io.File

/**
 * Pool-aware lossless stream resolver, used by the **download path**
 * ([DownloadUtil.resolveSourceStream] / [DownloadUtil.prewarmSongForDownload]).
 */
object LosslessStreamResolver {

    /**
     * Resolves a Qobuz stream URL for [mediaId] using the user's own Qobuz tokens + community
     * Source Pool accounts.
     */
    fun resolveQobuz(
        context: Context,
        mediaId: String,
        title: String,
        artists: List<String>,
        album: String?,
        durationMs: Long?,
        formatId: Int,
        directTrackId: String? = null,
    ): DirectStream? {
        val userInstances = parseMultiline(context, QobuzInstancesKey)
        val discoveredInstances = runCatching { QobuzAudioProvider.discoverInstances() }
            .getOrDefault(emptyList())
        val mergedInstances = LinkedHashSet<String>().apply {
            addAll(userInstances)
            addAll(discoveredInstances)
        }.toList()

        val userTokens = QobuzToken.listFromJson(readString(context, QobuzTokensKey))
        val poolTokens = PoolAccountManager.qobuzAccounts().map {
            QobuzToken(
                token = it.token,
                appId = it.appId,
                appSecret = it.appSecret,
                label = "Source Pool",
                subscription = if (it.premium) "premium" else "",
                poolId = it.id,
            )
        }
        val mergedTokens = (userTokens + poolTokens).distinctBy { it.token }

        if (mergedInstances.isEmpty() && mergedTokens.isEmpty()) {
            Timber.tag("LosslessResolver").d("Qobuz skip: no tokens or instances configured")
            return null
        }

        QobuzAudioProvider.setTokens(mergedTokens)
        QobuzAudioProvider.setInstances(mergedInstances)

        return runCatching {
            runBlocking(Dispatchers.IO) {
                QobuzAudioProvider.resolve(
                    query = QobuzAudioProvider.Query(
                        mediaId = mediaId,
                        title = title,
                        artists = artists,
                        album = album,
                        durationMs = durationMs,
                        directTrackId = directTrackId,
                    ),
                    formatId = formatId,
                )
            }
        }.onFailure { error ->
            Timber.tag("LosslessResolver").w(error, "Qobuz resolve failed for %s", mediaId)
        }.getOrNull()
    }

    /**
     * Resolves a Tidal stream URL for [mediaId] using:
     *
     *   1. The user's own Tidal access token (if signed in)
     *   2. Community Source Pool Tidal accounts (premium first)
     *   3. Public HiFi/QQDL instances (user-configured + pool-discovered)
     *
     * Mirrors [MusicService.resolveTidalStream]. Each pool token is tried
     * in turn via [TidalAccountManager.resolveDirectStream] (full-quality
     * FLAC via the official API), and only when all account paths fail do
     * we fall through to the public-instance path via
     * [TidalAudioProvider.resolve].
     *
     * Returns null when no path yields a stream.
     */
    fun resolveTidal(
        context: Context,
        mediaId: String,
        title: String,
        artists: List<String>,
        album: String?,
        durationMs: Long?,
        audioQuality: TidalAudioQuality,
        cacheDir: File,
    ): DirectStream? {
        val apiQuality = when (audioQuality) {
            TidalAudioQuality.HI_RES_LOSSLESS -> "HI_RES_LOSSLESS"
            TidalAudioQuality.FLAC -> "LOSSLESS"
            TidalAudioQuality.AAC_320 -> "HIGH"
        }
        val accountFirst = readBoolean(context, TidalAccountFirstKey, true)
        Timber.tag("LosslessResolver").d(
            "Tidal resolve start | quality=%s accountFirst=%s poolAccounts=%d",
            audioQuality.name, accountFirst, PoolAccountManager.tidalAccounts().size,
        )

        if (accountFirst) {
            // 1) The user's own Tidal token. We do NOT refresh here —
            //    playback's MusicService.refreshTidalToken() handles that
            //    for the signed-in user, and pool tokens are fresh from
            //    the Source Pool website's /api/sources endpoint.
            val userToken = readString(context, TidalAccessTokenKey)
            if (userToken.isNotBlank()) {
                val country = readString(context, TidalCountryCodeKey).ifBlank { "US" }
                val stream = runCatching {
                    runBlocking(Dispatchers.IO) {
                        TidalAccountManager.resolveDirectStream(
                            accessToken = userToken,
                            title = title,
                            artists = artists,
                            durationMs = durationMs,
                            audioQuality = apiQuality,
                            cacheDir = cacheDir,
                            countryCode = country,
                        )
                    }
                }.onFailure {
                    Timber.tag("LosslessResolver").w(it, "Tidal user-token resolve failed for %s", mediaId)
                }.getOrNull()
                if (stream != null) return stream
            }

            // 2) Shared premium Tidal accounts from the community Source Pool. These are real
            // subscriber tokens, so they resolve full-quality FLAC directly via the official API —
            // no proxy instance needed.
            var poolAccounts = PoolAccountManager.tidalAccounts()
            // Pooled Tidal access tokens live about an hour, but the app's pool cache is held for
            // hours, so the cached copies are usually already stale. Using one anyway guarantees a
            // 401 — and the handler below then reports a healthy account dead, which is how good
            // accounts used to get disabled. Check the token's own expiry and re-lease first.
            if (poolAccounts.any { TidalAccountManager.isAccessTokenExpired(it.token) }) {
                runCatching {
                    runBlocking(Dispatchers.IO) { PoolAccountManager.refresh(context, force = true) }
                }.onFailure {
                    Timber.tag("LosslessResolver").w(it, "Tidal pool re-lease failed; using cached accounts")
                }
                val refreshed = PoolAccountManager.tidalAccounts()
                if (refreshed.isNotEmpty()) {
                    poolAccounts = refreshed
                    Timber.tag("LosslessResolver").d("Re-leased %d fresh Tidal pool account(s)", refreshed.size)
                }
            }
            if (poolAccounts.isNotEmpty()) {
                // Race all pool accounts in parallel: the first hit wins and the rest are
                // cancelled. With N pool accounts this reduces the worst-case wall time from
                // N × resolve_time to ~1 × resolve_time (typical speedup: 10× for a 10-account
                // pool where the user's first 9 accounts don't have the track).
                //
                // resolveTidal is NOT a suspend function, so we wrap the
                // coroutineScope in runBlocking to bridge into the suspend world.
                val stream = runCatching {
                    runBlocking(Dispatchers.IO) {
                        coroutineScope {
                            val jobs = poolAccounts.map { poolAccount ->
                                async(Dispatchers.IO) {
                                    val poolCountry =
                                        poolAccount.countryCode?.trim()?.ifBlank { null } ?: "US"
                                    runCatching {
                                        TidalAccountManager.resolveDirectStream(
                                            accessToken = poolAccount.token,
                                            title = title,
                                            artists = artists,
                                            durationMs = durationMs,
                                            audioQuality = apiQuality,
                                            cacheDir = cacheDir,
                                            countryCode = poolCountry,
                                        )
                                    }.onFailure {
                                        if (TidalAccountManager.isUnauthorized(it)) {
                                            PoolAccountManager.report("tidal", "account", poolAccount.id, "dead")
                                        } else {
                                            Timber.tag("LosslessResolver").w(
                                                it, "Tidal pool account resolve failed for %s", mediaId,
                                            )
                                        }
                                    }.getOrNull()
                                }
                            }
                            // First non-null wins; cancel the rest.
                            var winner: DirectStream? = null
                            for (job in jobs) {
                                val result = job.await()
                                if (result != null) {
                                    winner = result
                                    // Cancel any still-pending jobs — we have a winner.
                                    jobs.forEach { other -> if (!other.isCompleted) other.cancel() }
                                    break
                                }
                            }
                            winner
                        }
                    }
                }.onFailure {
                    Timber.tag("LosslessResolver").w(it, "Tidal pool race failed for %s", mediaId)
                }.getOrNull()
                if (stream != null) {
                    Timber.tag("LosslessResolver").d(
                        "Tidal resolved via pool race for %s", mediaId,
                    )
                    return stream
                }
            }
        }

        // 3) Fallback: public HiFi/QQDL instances (user-configured + pool-discovered).
        //    These proxy servers do NOT require any account — they re-stream Tidal
        //    lossless audio publicly. Quality is lower than the account path but
        //    still FLAC when the instance supports it.
        val configuredInstances = parseMultiline(context, TidalInstancesKey)
        val discoveredInstances = TidalInstanceHealthManager.healthyUrls(context)
        val mergedInstances = LinkedHashSet<String>().apply {
            addAll(configuredInstances)
            addAll(discoveredInstances)
        }.toList()
        TidalAudioProvider.setInstances(mergedInstances)

        return runCatching {
            runBlocking(Dispatchers.IO) {
                TidalAudioProvider.resolve(
                    query = TidalAudioProvider.Query(
                        mediaId = mediaId,
                        title = title,
                        artists = artists,
                        album = album,
                        isrc = null,
                        durationMs = durationMs,
                    ),
                    cacheDir = cacheDir,
                    preferAtmos = false,
                    preferLiveDash = false,
                    audioQuality = audioQuality,
                )
            }
        }.onFailure {
            Timber.tag("LosslessResolver").w(it, "Tidal public-instance resolve failed for %s", mediaId)
        }.getOrNull()?.let { resolved ->
            DirectStream(
                uri = resolved.mediaUri,
                mimeType = resolved.mimeType,
                codecs = resolved.codecs,
                contentLength = resolved.contentLength,
                label = "Tidal ${resolved.label}",
                source = AudioSourceType.TIDAL,
                matchedTitle = resolved.matchedTitle,
                matchedArtist = resolved.matchedArtist,
                matchedAlbum = resolved.matchedAlbum,
                matchedDurationMs = resolved.matchedDurationMs,
            )
        }
    }

    /**
     * Resolves a **Qobuz backup** stream for [mediaId] — the community-hosted `mlc-ytify.kouzu.in`
     * mirror, keyed by YouTube video id.
     */
    fun resolveQobuzBackup(mediaId: String): DirectStream? =
        runCatching {
            runBlocking(Dispatchers.IO) {
                QobuzBackupProvider.resolveStream(mediaId)?.let { resolved ->
                    DirectStream(
                        uri = resolved.uri,
                        mimeType = resolved.mimeType,
                        codecs = resolved.codecs,
                        contentLength = resolved.contentLength,
                        label = resolved.label,
                        source = AudioSourceType.QOBUZ_BACKUP,
                        // The mirror is keyed by the YouTube id we asked for, so the match is
                        // authoritative by construction — there is no catalogue search to verify.
                        trustedDirectId = true,
                        sampleRate = resolved.sampleRate,
                        bitDepth = resolved.bitDepth,
                        matchedDurationMs = resolved.durationMs,
                    )
                }
            }
        }.onFailure { error ->
            Timber.tag("LosslessResolver").w(error, "Qobuz backup resolve failed for %s", mediaId)
        }.getOrNull()

    /** Resolves a **Deezer** stream for [mediaId]. */
    fun resolveDeezer(
        mediaId: String,
        title: String,
        artists: List<String>,
        album: String?,
        durationMs: Long?,
        format: String,
    ): DirectStream? {
        if (!DeezerAudioProvider.hasBackends()) {
            Timber.tag("LosslessResolver").d("Deezer skip: no accounts and no API instances available")
            return null
        }
        return runCatching {
            runBlocking(Dispatchers.IO) {
                DeezerAudioProvider
                    .resolve(
                        query =
                            DeezerAudioProvider.Query(
                                mediaId = mediaId,
                                title = title,
                                artists = artists,
                                album = album,
                                durationMs = durationMs,
                            ),
                        format = format,
                    )?.let { resolved ->
                        DirectStream(
                            uri = resolved.uri,
                            mimeType = resolved.mimeType,
                            codecs = resolved.codecs,
                            contentLength = resolved.contentLength,
                            label = resolved.label,
                            source = AudioSourceType.DEEZER,
                            matchedTitle = resolved.matchedTitle,
                            matchedArtist = resolved.matchedArtist,
                            matchedAlbum = resolved.matchedAlbum,
                            matchedDurationMs = resolved.matchedDurationMs,
                            sampleRate = resolved.sampleRate,
                            bitDepth = resolved.bitDepth,
                        )
                    }
            }
        }.onFailure { error ->
            Timber.tag("LosslessResolver").w(error, "Deezer resolve failed for %s", mediaId)
        }.getOrNull()
    }

    /** Resolves a **JioSaavn** stream for [mediaId]. */
    fun resolveJioSaavn(
        mediaId: String,
        title: String,
        artists: List<String>,
        album: String?,
        durationMs: Long?,
        qualityApiValue: String,
    ): DirectStream? {
        val artistHint = artists.firstOrNull()?.takeIf { it.isNotBlank() }.orEmpty()
        val albumHint = album?.takeIf { it.isNotBlank() }.orEmpty()
        val searchQuery =
            buildString {
                append(title)
                if (artistHint.isNotBlank()) append(' ').append(artistHint)
                if (albumHint.isNotBlank()) append(' ').append(albumHint)
            }.trim()

        return AudioSourceAttemptScope.within(AudioSourceAttemptTimeouts.PROVIDER_ATTEMPT_MS) {
            runCatching {
                runBlocking(Dispatchers.IO + AudioSourceAttemptScope.coroutineContextElement()) {
                    AudioSourceAttemptScope.withinSuspending(AudioSourceAttemptTimeouts.PROVIDER_ATTEMPT_MS) {
                val results = SaavnService.searchSongs(searchQuery).getOrNull().orEmpty()
                if (results.isEmpty()) return@withinSuspending null
                val wantedDurationSec = durationMs?.let { it / 1000 }
                val candidate =
                    results
                        // Pro-only tracks answer with a 30-second preview, which would silently
                        // download as a truncated file.
                        .filter { !it.isProOnly && it.downloadUrl.isNotEmpty() }
                        .minByOrNull { song ->
                            saavnMatchPenalty(
                                candidateTitle = song.name,
                                candidateArtist = song.artists.primary.firstOrNull()?.name,
                                candidateDurationSec = song.duration?.toLong(),
                                wantedTitle = title,
                                wantedArtist = artistHint,
                                wantedDurationSec = wantedDurationSec,
                            )
                        } ?: return@withinSuspending null
                val streamUrl =
                    SaavnService.selectBestUrl(candidate.downloadUrl, qualityApiValue)
                        ?: return@withinSuspending null
                DirectStream(
                    uri = streamUrl,
                    mimeType = "audio/mp4",
                    codecs = "mp4a.40.2",
                    contentLength = null,
                    label = "JioSaavn $qualityApiValue",
                    source = AudioSourceType.JIOSAAVN,
                    matchedTitle = candidate.name,
                    matchedArtist = candidate.artists.primary.firstOrNull()?.name,
                    matchedAlbum = candidate.album?.name,
                    matchedDurationMs = candidate.duration?.toLong()?.times(1000L),
                )
                    }
                }
            }.onFailure { error ->
                error.rethrowIfAudioSourceCancelled()
                Timber.tag("LosslessResolver").w(error, "JioSaavn resolve failed for %s", mediaId)
            }
                .getOrNull()
        }
    }

    /**
     * Scores how badly a JioSaavn search hit matches what we asked for — lower is better.
     *
     * Same weighting as `MusicService.resolveJioSaavnStream`: an exact title is free, a substring
     * match costs a little, anything else costs a lot; artist is weighted lower than title because
     * JioSaavn credits features inconsistently; duration is the tie-breaker that catches remixes
     * and extended edits sharing a title.
     */
    private fun saavnMatchPenalty(
        candidateTitle: String,
        candidateArtist: String?,
        candidateDurationSec: Long?,
        wantedTitle: String,
        wantedArtist: String,
        wantedDurationSec: Long?,
    ): Int {
        var penalty = 0
        val normTitle = candidateTitle.lowercase().replace(SAAVN_NORMALIZE_REGEX, "")
        val normWanted = wantedTitle.lowercase().replace(SAAVN_NORMALIZE_REGEX, "")
        penalty +=
            when {
                normTitle == normWanted -> 0
                normTitle.contains(normWanted) || normWanted.contains(normTitle) -> 1
                else -> 5
            }
        val normArtist = candidateArtist?.lowercase()?.replace(SAAVN_NORMALIZE_REGEX, "").orEmpty()
        val normWantedArtist = wantedArtist.lowercase().replace(SAAVN_NORMALIZE_REGEX, "")
        if (normWantedArtist.isNotBlank() && normArtist.isNotBlank()) {
            penalty +=
                when {
                    normArtist == normWantedArtist -> 0
                    normArtist.contains(normWantedArtist) || normWantedArtist.contains(normArtist) -> 1
                    else -> 3
                }
        }
        if (wantedDurationSec != null && candidateDurationSec != null) {
            val delta = kotlin.math.abs(candidateDurationSec - wantedDurationSec)
            penalty +=
                when {
                    delta <= 3 -> 0
                    delta <= 10 -> 2
                    else -> 6
                }
        }
        return penalty
    }

    /** Strips punctuation and whitespace so titles compare on their words alone. */
    private val SAAVN_NORMALIZE_REGEX = Regex("[^a-z0-9]")

    /**
     * Returns the cache key prefix for [source], matching
     * [MusicService.sourceCacheKey] so bytes cached here are picked up
     * by the playback resolver and vice versa.
     */
    fun cacheKeyPrefix(source: AudioSourceType): String = when (source) {
        AudioSourceType.TIDAL -> "tidal:"
        AudioSourceType.QOBUZ -> "qobuz:"
        else -> "${source.name.lowercase()}:"
    }

    // ---- helpers ----

    private fun readString(context: Context, key: androidx.datastore.preferences.core.Preferences.Key<String>): String =
        runCatching { runBlocking { context.dataStore.data.first()[key] ?: "" } }.getOrDefault("")

    private fun readBoolean(
        context: Context,
        key: androidx.datastore.preferences.core.Preferences.Key<Boolean>,
        default: Boolean,
    ): Boolean = runCatching { runBlocking { context.dataStore.data.first()[key] ?: default } }.getOrDefault(default)

    private fun parseMultiline(
        context: Context,
        key: androidx.datastore.preferences.core.Preferences.Key<String>,
    ): List<String> = readString(context, key)
        .split('\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}
