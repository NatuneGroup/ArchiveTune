/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * Portions © 4nx3b — github.com/4nx3b
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * The account the experimental Spotify streaming source plays with. It is a second account,
 * deliberately not the one the catalog integration (Spotify.accessToken, SpotifySpDcKey) is
 * signed in with: streaming registers a virtual Connect device, issues play commands and reports
 * plays, and none of that may land on the listener's real library. Everything here reads and
 * writes only the SpotifyStream* preferences, holds its own token, and talks to Spotify through
 * its own HTTP client; the catalog's global token is never read or set.
 */

package moe.rukamori.archivetune.spotify

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.constants.SpotifyAccessTokenExpiresAtKey
import moe.rukamori.archivetune.constants.SpotifyAccessTokenKey
import moe.rukamori.archivetune.constants.SpotifySpDcKey
import moe.rukamori.archivetune.constants.SpotifySpKeyKey
import moe.rukamori.archivetune.constants.SpotifyStreamAccessTokenExpiresAtKey
import moe.rukamori.archivetune.constants.SpotifyStreamAccessTokenKey
import moe.rukamori.archivetune.constants.SpotifyStreamAccountNameKey
import moe.rukamori.archivetune.constants.SpotifyStreamDeviceIdKey
import moe.rukamori.archivetune.constants.SpotifyStreamEnabledKey
import moe.rukamori.archivetune.constants.SpotifyStreamResolveMethod
import moe.rukamori.archivetune.constants.SpotifyStreamResolveMethodKey
import moe.rukamori.archivetune.constants.SpotifyStreamSpDcKey
import moe.rukamori.archivetune.constants.SpotifyStreamSpKeyKey
import moe.rukamori.archivetune.constants.SpotifyStreamUserIdKey
import moe.rukamori.archivetune.spotify.echo.SpotifyDesktopAuth
import moe.rukamori.archivetune.spotify.echo.SpotifyEchoResolver
import moe.rukamori.archivetune.utils.dataStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs

object SpotifyStreamingAccount {
    /** The WebView profile the streaming sign-in runs in, apart from the catalog's cookie jar. */
    const val WEBVIEW_PROFILE = "spotify-streaming"

    private const val TAG = "SpotifyStreamAccount"
    private const val GQL_URL = "https://api-partner.spotify.com/pathfinder/v2/query"
    private const val REST_ME_URL = "https://api.spotify.com/v1/me"
    private const val TOKEN_REFRESH_MARGIN_MS = 60_000L
    private const val MIN_MATCH_SCORE = 5

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

    private val tokenMutex = Mutex()

    // The catalog sp_dc whose account was last compared with the streaming account, and whether
    // the two turned out to be the same person. Checked once per catalog session, not per play.
    @Volatile
    private var comparedCatalogSpDc: String? = null

    @Volatile
    private var catalogIsStreamingAccount = false

    data class Profile(
        val userId: String,
        val displayName: String?,
    )

    data class TrackMatch(
        val trackId: String,
        val title: String,
        val artist: String?,
        val durationMs: Long?,
    )

    sealed class SignInError(message: String) : Exception(message) {
        /** The cookie did not mint a signed-in token. */
        class InvalidSession : SignInError("Spotify did not accept the streaming session")

        /** The account could not be identified, so separation from the catalog cannot be checked. */
        class ProfileUnavailable : SignInError("Could not read the streaming account's profile")

        /** The catalog is signed in but its account could not be read, so the two cannot be compared. */
        class CatalogUnverifiable : SignInError("Could not read the catalog account to compare")

        /** The streaming sign-in is the account the catalog already uses. */
        class SameAsCatalog : SignInError("The streaming account is the catalog account")
    }

    private suspend fun <T> pref(
        context: Context,
        key: Preferences.Key<T>,
    ): T? = context.dataStore.data.first()[key]

    suspend fun isSignedIn(context: Context): Boolean = pref(context, SpotifyStreamSpDcKey).orEmpty().isNotBlank()

    suspend fun isActive(context: Context): Boolean = pref(context, SpotifyStreamEnabledKey) == true && isSignedIn(context)

    suspend fun spDc(context: Context): String = pref(context, SpotifyStreamSpDcKey).orEmpty()

    suspend fun resolveMethod(context: Context): SpotifyStreamResolveMethod =
        pref(context, SpotifyStreamResolveMethodKey)
            ?.let { stored -> runCatching { SpotifyStreamResolveMethod.valueOf(stored) }.getOrNull() }
            ?: SpotifyStreamResolveMethod.Default

    suspend fun deviceId(context: Context): String {
        val existing = pref(context, SpotifyStreamDeviceIdKey).orEmpty()
        if (existing.length == 40) return existing
        val generated = UUID.randomUUID().toString().replace("-", "") + "00000000"
        runCatching { context.dataStore.edit { it[SpotifyStreamDeviceIdKey] = generated } }
        return generated
    }

    /**
     * A bearer token for the streaming account, or null when it is signed out, its session has
     * lapsed, or it has turned out to be the catalog account.
     */
    suspend fun accessToken(context: Context): String? =
        withContext(Dispatchers.IO) { lockedAccessToken(context) }

    private suspend fun lockedAccessToken(context: Context): String? =
        tokenMutex.withLock {
            val prefs = context.dataStore.data.first()
            val spDc = prefs[SpotifyStreamSpDcKey].orEmpty()
            if (spDc.isBlank()) return@withLock null
            if (collidesWithCatalog(context, spDc)) return@withLock null
            val cached = prefs[SpotifyStreamAccessTokenKey].orEmpty()
            val expiry = prefs[SpotifyStreamAccessTokenExpiresAtKey] ?: 0L
            if (cached.isNotBlank() && expiry - TOKEN_REFRESH_MARGIN_MS > System.currentTimeMillis()) {
                return@withLock cached
            }
            val fresh =
                SpotifyAuth
                    .fetchAccessToken(spDc, prefs[SpotifyStreamSpKeyKey].orEmpty())
                    .getOrNull()
                    ?.takeUnless { it.isAnonymous }
                    ?: return@withLock null
            runCatching {
                context.dataStore.edit {
                    it[SpotifyStreamAccessTokenKey] = fresh.accessToken
                    it[SpotifyStreamAccessTokenExpiresAtKey] = fresh.accessTokenExpirationTimestampMs
                }
            }
            fresh.accessToken
        }

    /**
     * Whether the catalog is now signed in with the streaming account. The catalog can be signed
     * in after streaming was set up, so this is checked again whenever the catalog session
     * changes. It only spends the catalog's existing token; it never mints or stores one.
     */
    private suspend fun collidesWithCatalog(
        context: Context,
        streamingSpDc: String,
    ): Boolean {
        val prefs = context.dataStore.data.first()
        val catalogSpDc = prefs[SpotifySpDcKey].orEmpty()
        if (catalogSpDc.isBlank()) return false
        if (catalogSpDc == streamingSpDc) return true
        if (comparedCatalogSpDc == catalogSpDc) return catalogIsStreamingAccount
        val streamingUserId = prefs[SpotifyStreamUserIdKey].orEmpty()
        val catalogToken =
            prefs[SpotifyAccessTokenKey]
                .orEmpty()
                .takeIf { (prefs[SpotifyAccessTokenExpiresAtKey] ?: 0L) > System.currentTimeMillis() }
        if (streamingUserId.isBlank() || catalogToken.isNullOrBlank()) return false
        val catalogProfile = fetchProfile(catalogToken) ?: return false
        catalogIsStreamingAccount = catalogProfile.userId == streamingUserId
        comparedCatalogSpDc = catalogSpDc
        if (catalogIsStreamingAccount) {
            Timber.tag(TAG).w("Catalog is signed in with the streaming account; streaming stands down")
        }
        return catalogIsStreamingAccount
    }

    /**
     * Verifies a freshly captured streaming session and stores it. Nothing is written unless the
     * cookie yields a signed-in token, the account can be identified, and it is not the account
     * the catalog uses.
     */
    suspend fun signIn(
        context: Context,
        spDc: String,
        spKey: String,
    ): Result<Profile> =
        withContext(Dispatchers.IO) {
            runCatching {
                val prefs = context.dataStore.data.first()
                val catalogSpDc = prefs[SpotifySpDcKey].orEmpty()
                if (catalogSpDc.isNotBlank() && catalogSpDc == spDc) throw SignInError.SameAsCatalog()

                val token =
                    SpotifyAuth
                        .fetchAccessToken(spDc, spKey)
                        .getOrNull()
                        ?.takeUnless { it.isAnonymous }
                        ?: throw SignInError.InvalidSession()
                val profile = fetchProfile(token.accessToken) ?: throw SignInError.ProfileUnavailable()

                if (catalogSpDc.isNotBlank()) {
                    val catalogProfile =
                        catalogToken(context)?.let { fetchProfile(it) }
                            ?: throw SignInError.CatalogUnverifiable()
                    if (catalogProfile.userId == profile.userId) throw SignInError.SameAsCatalog()
                }

                resetSessions()
                comparedCatalogSpDc = catalogSpDc.ifBlank { null }
                catalogIsStreamingAccount = false
                context.dataStore.edit {
                    it[SpotifyStreamSpDcKey] = spDc
                    it[SpotifyStreamSpKeyKey] = spKey
                    it[SpotifyStreamAccessTokenKey] = token.accessToken
                    it[SpotifyStreamAccessTokenExpiresAtKey] = token.accessTokenExpirationTimestampMs
                    it[SpotifyStreamUserIdKey] = profile.userId
                    it[SpotifyStreamAccountNameKey] = profile.displayName.orEmpty()
                }
                profile
            }
        }

    /**
     * The catalog account's token, for the one-off identity comparison at sign-in. A stored token
     * that is still valid is used as is; otherwise one is minted from the catalog cookie and
     * thrown away, so the catalog's own session state is never touched from here.
     */
    private suspend fun catalogToken(context: Context): String? {
        val prefs = context.dataStore.data.first()
        val stored = prefs[SpotifyAccessTokenKey].orEmpty()
        if (stored.isNotBlank() && (prefs[SpotifyAccessTokenExpiresAtKey] ?: 0L) - TOKEN_REFRESH_MARGIN_MS > System.currentTimeMillis()) {
            return stored
        }
        val catalogSpDc = prefs[SpotifySpDcKey].orEmpty().ifBlank { return null }
        val catalogSpKey = prefs[SpotifySpKeyKey].orEmpty()
        return SpotifyAuth.fetchAccessToken(catalogSpDc, catalogSpKey).getOrNull()?.takeUnless { it.isAnonymous }?.accessToken
    }

    suspend fun signOut(context: Context) {
        resetSessions()
        comparedCatalogSpDc = null
        catalogIsStreamingAccount = false
        context.dataStore.edit {
            it.remove(SpotifyStreamSpDcKey)
            it.remove(SpotifyStreamSpKeyKey)
            it.remove(SpotifyStreamAccessTokenKey)
            it.remove(SpotifyStreamAccessTokenExpiresAtKey)
            it.remove(SpotifyStreamUserIdKey)
            it.remove(SpotifyStreamAccountNameKey)
            it[SpotifyStreamEnabledKey] = false
        }
    }

    /** Drops every in-memory session the streaming pipelines hold, so no state crosses accounts. */
    fun resetSessions() {
        SpotifyWebStreamer.resetSession()
        SpotifyDesktopAuth.reset()
        SpotifyEchoResolver.clearCache()
    }

    fun fetchProfile(token: String): Profile? = fetchProfileGraphQl(token) ?: fetchProfileRest(token)

    private fun fetchProfileGraphQl(token: String): Profile? {
        val response = graphQl(token, "profileAttributes", JSONObject()) ?: return null
        val profile = response.optJSONObject("data")?.optJSONObject("me")?.optJSONObject("profile") ?: return null
        val userId = profile.optString("uri").substringAfterLast(':').takeIf { it.isNotBlank() } ?: return null
        return Profile(userId = userId, displayName = profile.optString("name").takeIf { it.isNotBlank() })
    }

    private fun fetchProfileRest(token: String): Profile? =
        runCatching {
            val request =
                Request
                    .Builder()
                    .url(REST_ME_URL)
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json")
                    .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val json = JSONObject(response.body?.string().orEmpty())
                val userId = json.optString("id").takeIf { it.isNotBlank() } ?: return@use null
                Profile(userId = userId, displayName = json.optString("display_name").takeIf { it.isNotBlank() })
            }
        }.getOrNull()

    /**
     * Finds the Spotify track for a song that carries no Spotify id, searching as the streaming
     * account. Same scoring 4nx3b's resolver uses: title, artist and duration must broadly agree,
     * and a duration far off sinks a candidate, so an unrelated cover is not played in its place.
     */
    suspend fun searchTrack(
        context: Context,
        title: String,
        artists: List<String>,
        durationMs: Long?,
    ): TrackMatch? {
        val token = accessToken(context) ?: return null
        val term =
            buildString {
                append(title)
                if (artists.isNotEmpty()) append(' ').append(artists.joinToString(" "))
            }.trim()
        if (term.isBlank()) return null
        val variables =
            JSONObject().apply {
                put("searchTerm", term)
                put("offset", 0)
                put("limit", 10)
                put("numberOfTopResults", 5)
                put("includeAudiobooks", false)
                put("includeArtistHasConcertsField", false)
                put("includePreReleases", false)
                put("includeLocalConcertsField", false)
                put("includeAuthors", false)
            }
        val response = withContext(Dispatchers.IO) { graphQl(token, "searchDesktop", variables) } ?: return null
        val items =
            response
                .optJSONObject("data")
                ?.optJSONObject("searchV2")
                ?.optJSONObject("tracksV2")
                ?.optJSONArray("items")
                ?: return null

        var best: TrackMatch? = null
        var bestScore = Int.MIN_VALUE
        for (index in 0 until items.length()) {
            val raw = items.optJSONObject(index) ?: continue
            val wrapper = raw.optJSONObject("item") ?: raw
            val data = wrapper.optJSONObject("data") ?: wrapper
            val uri =
                wrapper.optString("_uri").ifBlank { wrapper.optString("uri") }.ifBlank { data.optString("uri") }
            if (!uri.startsWith("spotify:track:")) continue
            val trackId = uri.substringAfterLast(':')
            val name = data.optString("name")
            if (trackId.isBlank() || name.isBlank()) continue
            val artistItems = data.optJSONObject("artists")?.optJSONArray("items")
            val artistNames =
                buildList<String> {
                    if (artistItems != null) {
                        for (a in 0 until artistItems.length()) {
                            artistItems
                                .optJSONObject(a)
                                ?.optJSONObject("profile")
                                ?.optString("name")
                                ?.takeIf { it.isNotBlank() }
                                ?.let(::add)
                        }
                    }
                }
            val duration =
                data
                    .optJSONObject("duration")
                    ?.optLong("totalMilliseconds")
                    ?.takeIf { it > 0 }
                    ?: data.optLong("durationMs").takeIf { it > 0 }

            var score = 0
            if (name.equals(title, ignoreCase = true)) {
                score += 4
            } else if (name.contains(title, ignoreCase = true)) {
                score += 2
            }
            if (artistNames.any { found -> artists.any { wanted -> found.equals(wanted, ignoreCase = true) } }) {
                score += 3
            }
            if (durationMs != null && duration != null) {
                val delta = abs(duration - durationMs)
                score +=
                    when {
                        delta <= 2_000 -> 3
                        delta <= 5_000 -> 1
                        delta > 15_000 -> -4
                        else -> 0
                    }
            }
            if (score > bestScore) {
                bestScore = score
                best =
                    TrackMatch(
                        trackId = trackId,
                        title = name,
                        artist = artistNames.joinToString(", ").ifBlank { null },
                        durationMs = duration,
                    )
            }
        }
        return best?.takeIf { bestScore >= MIN_MATCH_SCORE }
    }

    private fun graphQl(
        token: String,
        operationName: String,
        variables: JSONObject,
    ): JSONObject? {
        val primary = SpotifyHashProvider.getHash(operationName)
        val hashes = listOfNotNull(primary, SpotifyHashProvider.getPreviousHash(operationName)?.takeIf { it != primary })
        for (hash in hashes) {
            val body =
                JSONObject().apply {
                    put("variables", variables)
                    put("operationName", operationName)
                    put(
                        "extensions",
                        JSONObject().put(
                            "persistedQuery",
                            JSONObject().apply {
                                put("version", 1)
                                put("sha256Hash", hash)
                            },
                        ),
                    )
                }
            val result =
                runCatching {
                    val request =
                        Request
                            .Builder()
                            .url(GQL_URL)
                            .header("Authorization", "Bearer $token")
                            .header("Accept", "application/json")
                            .header("app-platform", "WebPlayer")
                            .header("Origin", "https://open.spotify.com")
                            .header("Referer", "https://open.spotify.com/")
                            .header("User-Agent", SpotifyWebStreamer.USER_AGENT)
                            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                            .build()
                    client.newCall(request).execute().use { response ->
                        val text = response.body?.string().orEmpty()
                        when {
                            response.code == 412 -> null
                            !response.isSuccessful -> {
                                Timber.tag(TAG).w("GraphQL %s failed: HTTP %d", operationName, response.code)
                                return null
                            }
                            else -> {
                                val json = JSONObject(text)
                                val firstError = json.optJSONArray("errors")?.optJSONObject(0)?.optString("message").orEmpty()
                                if (firstError.contains("PersistedQueryNotFound", ignoreCase = true)) null else json
                            }
                        }
                    }
                }.getOrNull()
            if (result != null) return result
        }
        return null
    }
}
