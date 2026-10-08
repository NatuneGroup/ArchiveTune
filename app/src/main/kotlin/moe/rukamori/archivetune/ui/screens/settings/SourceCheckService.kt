/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.ui.screens.settings

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.audiosource.AmazonInstances
import moe.rukamori.archivetune.constants.AmazonBypassTokenKey
import moe.rukamori.archivetune.constants.AmazonInstancesKey
import moe.rukamori.archivetune.constants.AmazonTurnstileJwtExpiryMsKey
import moe.rukamori.archivetune.constants.AmazonTurnstileJwtKey
import moe.rukamori.archivetune.qqmusic.QqMusicApi
import moe.rukamori.archivetune.qqmusic.QqMusicSession
import moe.rukamori.archivetune.constants.AudioSourceType
import moe.rukamori.archivetune.applemusic.AppleMusicAudioProvider
import moe.rukamori.archivetune.deezer.DeezerAudioProvider
import moe.rukamori.archivetune.jiosaavn.SaavnService
import moe.rukamori.archivetune.qobuz.QobuzAudioProvider
import moe.rukamori.archivetune.qobuz.QobuzToken
import moe.rukamori.archivetune.tidal.TidalAccountManager
import moe.rukamori.archivetune.tidal.TidalAudioProvider
import moe.rukamori.archivetune.utils.PoolAccountManager
import moe.rukamori.archivetune.utils.dataStore
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Result of a "Check source" probe. The [summary] is shown in a Toast / dialog body;
 * [healthy] drives the icon (green check / red x) on the row.
 */
data class SourceCheckResult(
    val healthy: Boolean,
    val summary: String,
)

/** Per-source health probe for the "Check source" row in Source Settings. */
object SourceCheckService {
    /** Stable, well-known YouTube video id used to probe the kouzu.in backup. */
    private const val KOZU_PROBE_YT_ID = "dQw4w9WgXcQ"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Run the per-source health probe. Returns a [SourceCheckResult] — never throws.
     * [context] is needed for the source pool refresh (which reads / writes the
     * app's DataStore cache).
     */
    suspend fun check(source: AudioSourceType, context: Context): SourceCheckResult =
        withContext(Dispatchers.IO) {
            when (source) {
                AudioSourceType.TIDAL -> checkTidal(context)
                AudioSourceType.QOBUZ -> checkQobuz(context)
                AudioSourceType.QOBUZ_BACKUP -> checkQobuzBackup()
                AudioSourceType.DEEZER -> checkDeezer(context)
                AudioSourceType.APPLE -> checkAppleMusic()
                AudioSourceType.AMAZON -> checkAmazon(context)
                AudioSourceType.QQ -> checkQqMusic(context)
                AudioSourceType.JIOSAAVN -> checkJioSaavn()
                AudioSourceType.YOUTUBE -> SourceCheckResult(
                    healthy = true,
                    summary = "YouTube is always available as the fallback source.",
                )
            }
        }

    private suspend fun checkQqMusic(context: Context): SourceCheckResult {
        val session =
            runCatching { QqMusicSession.read(context.dataStore) }.getOrNull()
                ?: return SourceCheckResult(
                    healthy = false,
                    summary = "No QQ Music account is connected. Sign in from Integration → QQ Music " +
                        "by scanning the code with the QQ app, then switch the source on.",
                )

        // The probe inspects the reply envelope rather than what it found, so an empty result set
        // is not mistaken for a dead session; only a rejected or malformed call is.
        val reachable = runCatching { QqMusicApi.reachable(session, "love") }.getOrDefault(false)
        val who = session.nickname?.takeIf { it.isNotBlank() } ?: session.uin
        return if (reachable) {
            SourceCheckResult(
                healthy = true,
                summary = "Signed in as $who. Playback uses this account's own entitlements; a tier " +
                    "the account does not hold comes back empty and falls through to the next source.",
            )
        } else {
            SourceCheckResult(
                healthy = false,
                summary = "Signed in as $who, but the catalogue did not answer for that ticket — it has " +
                    "most likely expired. Sign in again from Integration → QQ Music.",
            )
        }
    }

    private suspend fun checkTidal(context: Context): SourceCheckResult {
        // Refresh the pool first so newly-added accounts are visible.
        PoolAccountManager.refresh(context, force = false)
        val accounts = PoolAccountManager.tidalAccounts()
        if (accounts.isEmpty()) {
            return SourceCheckResult(
                healthy = false,
                summary = "No Tidal accounts in the source pool. Tap 'Refresh source pool' at the top, " +
                    "or add your own Tidal token via Integration → Manual source sign-in.",
            )
        }
        val premium = accounts.count { it.premium }

        // Probe the path that actually streams. LosslessStreamResolver.resolveTidal tries the
        // official Tidal API with the pool's subscriber tokens FIRST and only falls back to public
        // HiFi/QQDL instances if every account fails — so a valid premium token means Tidal works
        // with zero instances. Reporting the instance count as the headline verdict (as this check
        // used to) told users Tidal was broken while it was streaming fine.
        val probeAccount = accounts.firstOrNull { it.premium } ?: accounts.first()
        val session = runCatching { TidalAccountManager.buildSessionFromBearer(probeAccount.token) }.getOrNull()
        val subscription =
            session?.userId?.let { userId ->
                runCatching { TidalAccountManager.fetchSubscription(probeAccount.token, userId) }.getOrNull()
            }
        val accountLabel =
            when {
                session == null -> "token rejected by the Tidal API (expired — refresh the source pool)"
                subscription == TidalAccountManager.Subscription.PREMIUM -> "valid (premium — lossless available)"
                subscription == TidalAccountManager.Subscription.FREE -> "valid but FREE (previews only, no lossless)"
                else -> "valid, subscription tier unknown"
            }
        val accountPathReady = session != null && subscription != TidalAccountManager.Subscription.FREE

        // Instances are the optional no-account fallback. Report them as such.
        val healthyInstances = runCatching {
            moe.rukamori.archivetune.tidal.TidalInstanceHealthManager.healthyUrls(context).size
        }.getOrDefault(0)

        val summary = buildString {
            append("Pool accounts: ${accounts.size} ($premium premium)\n")
            append("Account stream path: $accountLabel\n")
            append("Public instances (optional fallback): $healthyInstances healthy")
            if (accountPathReady) {
                append("\n\nTidal source is READY via the account path.")
                if (healthyInstances == 0) {
                    append(
                        " No public instance is reachable, but none is needed — " +
                            "the pool's subscriber token streams directly from Tidal.",
                    )
                }
            } else {
                append("\n\nTidal source is NOT ready: ")
                append(
                    if (healthyInstances > 0) {
                        "the account path failed, so playback will fall back to a public instance " +
                            "(lower quality, may serve previews)."
                    } else {
                        "the account path failed and no public instance is reachable. " +
                            "Tap 'Refresh source pool' to pull fresh tokens, or add a private " +
                            "Tidal instance via Integration."
                    },
                )
            }
        }
        return SourceCheckResult(
            healthy = accountPathReady || healthyInstances > 0,
            summary = summary,
        )
    }

    private suspend fun checkQobuz(context: Context): SourceCheckResult {
        PoolAccountManager.refresh(context, force = false)
        val accounts = PoolAccountManager.qobuzAccounts()
        if (accounts.isEmpty()) {
            return SourceCheckResult(
                healthy = false,
                summary = "No Qobuz accounts in the source pool. Tap 'Refresh source pool' at the top, " +
                    "or add your own Qobuz token (with app_id + app_secret) via Integration → Manual source sign-in.",
            )
        }
        val premium = accounts.count { it.premium }
        // Take the first pool account and try a real Qobuz API call against it
        // (user/get) — this verifies the token + app_id are valid. We can't
        // verify app_secret without signing a stream URL, but user/get proves
        // the account is alive.
        val first = accounts.first()
        val token = QobuzToken(
            token = first.token,
            appId = first.appId,
            appSecret = first.appSecret,
            label = "Source Pool",
            subscription = if (first.premium) "premium" else "",
        )
        val health = QobuzAudioProvider.verifyToken(token, probeTrackId = null, formatId = 5)
        val healthLabel = when (health) {
            moe.rukamori.archivetune.tidal.TidalAudioProvider.InstanceHealth.HEALTHY -> "healthy (premium)"
            moe.rukamori.archivetune.tidal.TidalAudioProvider.InstanceHealth.PREVIEW_ONLY -> "preview-only (no subscription)"
            moe.rukamori.archivetune.tidal.TidalAudioProvider.InstanceHealth.UNREACHABLE -> "unreachable (token invalid / app_secret mismatch)"
            else -> "unknown"
        }
        return SourceCheckResult(
            healthy = health == moe.rukamori.archivetune.tidal.TidalAudioProvider.InstanceHealth.HEALTHY,
            summary = "Pool accounts: ${accounts.size} ($premium premium)\n" +
                "First token probe: $healthLabel\n" +
                "Qobuz source is ${if (health == moe.rukamori.archivetune.tidal.TidalAudioProvider.InstanceHealth.HEALTHY) "READY" else "NOT ready — see above"}.",
        )
    }

    private fun checkQobuzBackup(): SourceCheckResult {
        // Server addresses are intentionally hidden from the summary text.
        // The retired mlc-ytify mirror stopped resolving; mls.kouzu.in serves the same API
        // (see QobuzBackupProvider, which funnels every endpoint through normalizeEndpoint).
        val resolverUrl = "https://mls.kouzu.in/api/stream?id=$KOZU_PROBE_YT_ID"
        return runCatching {
            val resolverRequest = Request.Builder()
                .url(resolverUrl)
                .get()
                .header("x-request-source", "muzo")
                .header("User-Agent", "ArchiveTune-Android")
                .header("Accept", "application/json")
                .build()
            client.newCall(resolverRequest).execute().use { resolverResponse ->
                if (!resolverResponse.isSuccessful) {
                    return@runCatching SourceCheckResult(
                        healthy = false,
                        summary = "Qobuz backup resolver returned HTTP ${resolverResponse.code}. " +
                            "The backup server may be down or rate-limiting your IP.",
                    )
                }
                val body = resolverResponse.body?.string().orEmpty()
                if (body.isBlank()) {
                    return@runCatching SourceCheckResult(
                        healthy = false,
                        summary = "Qobuz backup resolver returned an empty body.",
                    )
                }
                val root = runCatching { JSONObject(body) }.getOrNull()
                if (root == null) {
                    return@runCatching SourceCheckResult(
                        healthy = false,
                        summary = "Qobuz backup resolver returned a non-JSON response.",
                    )
                }
                // The resolver returns both a lossy `url` mirror and a `lossless`
                // FLAC mirror. Report on the lossless one first, since that is the
                // reason to use this source at all.
                val losslessUrl = root.optString("lossless").takeIf { it.isNotBlank() }
                val lossyUrl = root.optString("url").takeIf { it.isNotBlank() }
                if (losslessUrl == null && lossyUrl == null) {
                    return@runCatching SourceCheckResult(
                        healthy = false,
                        summary = "Qobuz backup resolver returned a JSON envelope with no stream URL.",
                    )
                }
                // Step 2: range-probe the resolved CDN URL.
                //
                // Deliberately a ranged GET, not HEAD: the CDN answers HEAD with
                // `405 Method Not Allowed` (`allow: GET`) for every object, so the
                // old HEAD probe always reported a scary "HEAD probe got HTTP 405"
                // even when the stream was perfectly playable. `Range: bytes=0-1`
                // downloads two bytes and returns the real Content-Type.
                val losslessProbe = losslessUrl?.let { probeCdn(it) }
                val lossyProbe = if (losslessProbe?.ok == true) null else lossyUrl?.let { probeCdn(it) }
                when {
                    losslessProbe?.ok == true ->
                        SourceCheckResult(
                            healthy = true,
                            summary = "Qobuz backup is reachable and served a lossless stream " +
                                "(${losslessProbe.contentType}${losslessProbe.sizeSuffix()}). " +
                                "Qobuz backup is READY.",
                        )

                    lossyProbe?.ok == true ->
                        SourceCheckResult(
                            healthy = true,
                            summary = "Qobuz backup is reachable but only the lossy mirror served audio " +
                                "(${lossyProbe.contentType}). The backup server has no lossless copy of the " +
                                "probe track yet — lossless will still be used for tracks that have one.",
                        )

                    else -> {
                        val failed = losslessProbe ?: lossyProbe
                        SourceCheckResult(
                            healthy = false,
                            summary = "Qobuz backup resolver returned a stream URL but the CDN served " +
                                "${failed?.describeFailure() ?: "no response"}. The backup server may be " +
                                "rebuilding its cache — try again in a few minutes.",
                        )
                    }
                }
            }
        }.getOrElse { e ->
            SourceCheckResult(
                healthy = false,
                summary = "Failed to reach Qobuz backup: ${e.message ?: e.javaClass.simpleName}",
            )
        }
    }

    /** Outcome of a two-byte ranged GET against a Qobuz-backup CDN mirror. */
    private data class CdnProbe(
        val ok: Boolean,
        val code: Int,
        val contentType: String,
        val totalBytes: Long?,
    ) {
        fun sizeSuffix(): String =
            totalBytes?.let { ", ${it / 1_000_000}MB" }.orEmpty()

        fun describeFailure(): String =
            if (code in 200..299) "an unexpected content type: $contentType" else "HTTP $code"
    }

    private fun probeCdn(url: String): CdnProbe? =
        runCatching {
            val request = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", "ArchiveTune-Android")
                .header("Range", "bytes=0-1")
                .build()
            client.newCall(request).execute().use { response ->
                val contentType = response.header("Content-Type")?.lowercase().orEmpty()
                val isAudio =
                    contentType.startsWith("audio/") ||
                        contentType.startsWith("video/") ||
                        contentType.contains("octet-stream")
                val total =
                    response
                        .header("Content-Range")
                        ?.substringAfter('/', "")
                        ?.trim()
                        ?.toLongOrNull()
                CdnProbe(
                    ok = response.isSuccessful && isAudio,
                    code = response.code,
                    contentType = contentType.ifBlank { "unknown" },
                    totalBytes = total,
                )
            }
        }.getOrNull()

    private suspend fun checkAppleMusic(): SourceCheckResult {
        val mediaToken = AppleMusicAudioProvider.mediaUserToken()
        val devToken = AppleMusicAudioProvider.devToken()
        if (mediaToken == null || devToken == null) {
            val missing =
                buildList {
                    if (devToken == null) add("dev (Bearer) token")
                    if (mediaToken == null) add("Media-User-Token")
                }.joinToString(" and ")
            val pool = PoolAccountManager.appleMusicAccounts()
            if (pool.isNotEmpty()) {
                return SourceCheckResult(
                    healthy = true,
                    summary = "Signed in via the Source Pool (%d shared Apple Music account%s).".format(
                        pool.size,
                        if (pool.size == 1) "" else "s",
                    ),
                )
            }
            return SourceCheckResult(
                healthy = false,
                summary = "No $missing. Sign in via Settings → Apple Music, or refresh the source pool.",
            )
        }
        return runCatching {
            val storefront = AppleMusicAudioProvider.resolveStorefront()
            SourceCheckResult(
                healthy = true,
                summary = "Apple Music reachable — storefront '$storefront' resolved from your token.",
            )
        }.getOrElse {
            SourceCheckResult(
                healthy = false,
                summary = "Token present but the API rejected it (${it.message}). Re-paste a fresh Media-User-Token.",
            )
        }
    }

    private suspend fun checkDeezer(context: Context): SourceCheckResult {
        // force = true. The whole point of tapping "Check source" is to find out whether accounts
        // can be obtained *now*, and a non-forced refresh is throttled — for up to 24h whenever any
        // other service had accounts cached — so the message telling the user to refresh the pool
        // would be advice this very call had just declined to follow.
        PoolAccountManager.refresh(context, force = true)

        // Ask the provider, not the pool. DeezerAudioProvider.accounts() merges the manually
        // signed-in ARL (Integration → Deezer) with the pool's shared accounts; reading
        // PoolAccountManager.deezerAccounts() directly skips the manual one entirely and reported
        // "No Deezer accounts in the source pool" to users who had signed in successfully and whose
        // playback was in fact resolving. Same defect 7ede13689 fixed in MusicService's resolver.
        val availability = DeezerAudioProvider.accountAvailability()
        if (availability.total == 0) {
            // No account at all: the API-instance tier is the only thing left to check.
            val health =
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    moe.rukamori.archivetune.deezer.DeezerInstances.checkFirst()
                }
            if (health != null) {
                return SourceCheckResult(
                    healthy = health.ok,
                    summary =
                        if (health.ok) {
                            "No Deezer account, but API instance ${health.instance} is live. Deezer source is READY " +
                                "through the instance."
                        } else {
                            "No Deezer account, and API instance ${health.instance} is not serving (${health.detail}). " +
                                "Sign in via Integration → Deezer, add another instance, or refresh the source pool."
                        },
                )
            }
            return SourceCheckResult(
                healthy = false,
                summary = "No Deezer credentials available. Sign in via Integration → Deezer (browser sign-in or " +
                    "paste an ARL), add a Deezer API instance there, or tap 'Refresh source pool' at the top.",
            )
        }

        val origin =
            buildList {
                if (availability.manual) {
                    add("your own account${if (availability.manualPremium) " (premium)" else ""}")
                }
                if (availability.pooled > 0) {
                    add("${availability.pooled} pool account(s), ${availability.pooledPremium} premium")
                }
            }.joinToString(" + ")

        // Probe the credential resolve() would reach for first. A stored ARL says nothing about
        // whether Deezer still accepts it — an expired cookie looks identical until playback
        // silently falls through to the next source.
        val info = DeezerAudioProvider.verifyPreferredAccount()
        return if (info == null) {
            SourceCheckResult(
                healthy = false,
                summary = "Found $origin, but the Deezer gateway rejected the credential it would use " +
                    "first. Sign in again via Integration → Deezer, or refresh the source pool.",
            )
        } else {
            val tier = if (info.lossless) "lossless (FLAC) available" else "no lossless — 320kbps MP3 at best"
            SourceCheckResult(
                healthy = true,
                summary = "Credentials: $origin. Verified as '${info.name}' — $tier. Deezer source is READY.",
            )
        }
    }

    private suspend fun checkAmazon(context: Context): SourceCheckResult {
        val prefs = context.dataStore.data.first()
        val nowMs = System.currentTimeMillis()
        // The check must look at what playback will actually try: the user's own instances plus any
        // the pool serves, each with the material issued for it.
        val instances =
            AmazonInstances.merge(
                local = AmazonInstances.parseBaseUrls(prefs[AmazonInstancesKey].orEmpty()),
                localBypassToken = prefs[AmazonBypassTokenKey],
                localTurnstileJwt =
                    prefs[AmazonTurnstileJwtKey].takeIf {
                        !it.isNullOrBlank() &&
                            AmazonInstances.isJwtUsable(
                                expiresAtMs = prefs[AmazonTurnstileJwtExpiryMsKey]?.takeIf { it > 0L },
                                nowMs = nowMs,
                            )
                    },
                pooled = PoolAccountManager.amazonInstances(),
                nowMs = nowMs,
            )
        if (instances.isEmpty()) {
            return SourceCheckResult(
                healthy = false,
                summary = "Not configured: no Amazon Music instance with authorization material. " +
                    "Open Integration → Amazon Music and add an instance URL (or authorize one " +
                    "with Turnstile). The source is off until then.",
            )
        }
        // Probe the first instance's /health with a short timeout; report it dead if it doesn't
        // answer. This is a liveness check only — auth is already settled by the list above.
        val first = instances.first()
        val base = first.baseUrl
        val origin = if (first.fromPool) "from the pool" else "yours"
        val client =
            OkHttpClient
                .Builder()
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(4, TimeUnit.SECONDS)
                .callTimeout(4, TimeUnit.SECONDS)
                .build()
        val probe =
            runCatching {
                val request =
                    Request
                        .Builder()
                        .url("${base.trimEnd('/')}/health")
                        .get()
                        .build()
                client.newCall(request).execute().use { response ->
                    response.isSuccessful
                }
            }.getOrElse {
                Timber.tag("SourceCheck").d(it, "Amazon /health probe failed")
                false
            }
        val count =
            if (instances.size == 1) {
                "1 instance"
            } else {
                "${instances.size} instances (${instances.count { it.fromPool }} from the pool)"
            }
        return if (probe) {
            SourceCheckResult(
                healthy = true,
                summary = "$count available; the first ($base, $origin) is alive. " +
                    "Playback resolves through that instance and is decrypted on-device.",
            )
        } else {
            SourceCheckResult(
                healthy = false,
                summary = "$count available, but the first ($base, $origin) did not answer " +
                    "/health within 4s — it may be dead or unreachable.",
            )
        }
    }

    private fun checkJioSaavn(): SourceCheckResult {
        // Server addresses are intentionally hidden from the summary text.
        return runCatching {
            val result = kotlinx.coroutines.runBlocking {
                SaavnService.searchSongs("test query").getOrDefault(emptyList())
            }
            if (result.isEmpty()) {
                SourceCheckResult(
                    healthy = false,
                    summary = "JioSaavn search returned no results. The service may be down or " +
                        "rate-limiting your IP — try again in a minute.",
                )
            } else {
                SourceCheckResult(
                    healthy = true,
                    summary = "JioSaavn is reachable and returned ${result.size} results for a probe query. " +
                        "JioSaavn source is READY.",
                )
            }
        }.getOrElse { e ->
            SourceCheckResult(
                healthy = false,
                summary = "Failed to reach JioSaavn: ${e.message ?: e.javaClass.simpleName}",
            )
        }
    }
}
