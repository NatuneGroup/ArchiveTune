/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.constants.InnerTubeOAuthExpiresAtKey
import moe.rukamori.archivetune.constants.InnerTubeOAuthRefreshTokenKey
import moe.rukamori.archivetune.constants.InnerTubeOAuthTokenKey
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * YouTube sign-in over the OAuth2 **device-code** flow, as an alternative to the WebView cookie
 * path in [YouTubeLoginRepository].
 */
object YouTubeOAuthRepository {
    private const val TAG = "YouTubeOAuth"

    // The public YouTube VR (Oculus) OAuth client. Public by construction — a device-flow client
    // cannot keep a secret on the device — and the same pair every open-source YouTube client uses.
    // If Google rotates it this path stops working and the WebView login remains the fallback.
    private const val CLIENT_ID = "861556708454-d6dlm3lh05idd8npek18k6be8ba3oc68.apps.googleusercontent.com"
    private const val CLIENT_SECRET = "SboVhoG9s0rNafixCSGGKXAT"
    private const val SCOPE = "https://www.googleapis.com/auth/youtube"
    private const val GRANT_TYPE_DEVICE = "http://oauth.net/grant_type/device/1.0"

    private const val DEVICE_CODE_URL = "https://www.youtube.com/o/oauth2/device/code"
    private const val TOKEN_URL = "https://www.youtube.com/o/oauth2/token"
    private const val REVOKE_URL = "https://oauth2.googleapis.com/revoke"

    /** Refresh this far before actual expiry, so a request never races the deadline. */
    private const val REFRESH_SKEW_MS = 5 * 60 * 1000L

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()

    /** What the user has to be shown: type [userCode] at [verificationUrl]. */
    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUrl: String,
        val intervalSeconds: Int,
        val expiresInSeconds: Int,
    )

    sealed interface PollResult {
        data object Pending : PollResult
        data class Success(val accessToken: String) : PollResult
        data class Failed(val reason: String) : PollResult
    }

    private fun post(url: String, form: FormBody): JSONObject? =
        runCatching {
            client.newCall(Request.Builder().url(url).post(form).build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@use null
                JSONObject(body)
            }
        }.getOrElse {
            Timber.tag(TAG).w(it, "OAuth request to %s failed", url)
            null
        }

    /** Step 1: ask Google for a device/user code pair. Null when the request fails. */
    suspend fun requestDeviceCode(): DeviceCode? =
        withContext(Dispatchers.IO) {
            val form =
                FormBody
                    .Builder()
                    .add("client_id", CLIENT_ID)
                    .add("scope", SCOPE)
                    .add("device_id", UUID.randomUUID().toString())
                    .add("device_model", "ytlr::")
                    .build()
            val json = post(DEVICE_CODE_URL, form) ?: return@withContext null
            val deviceCode = json.optString("device_code").takeIf { it.isNotBlank() } ?: return@withContext null
            val userCode = json.optString("user_code").takeIf { it.isNotBlank() } ?: return@withContext null
            DeviceCode(
                deviceCode = deviceCode,
                userCode = userCode,
                verificationUrl =
                    json.optString("verification_url").takeIf { it.isNotBlank() }
                        ?: "https://www.google.com/device",
                intervalSeconds = json.optInt("interval", 5).coerceAtLeast(1),
                expiresInSeconds = json.optInt("expires_in", 1800),
            )
        }

    /**
     * Step 2: poll until the user finishes (or the code expires). Honours the server's `interval`
     * rather than a fixed delay — polling faster earns `slow_down` and then a hard failure.
     * Persists both tokens on success.
     */
    suspend fun pollForToken(context: Context, code: DeviceCode): PollResult =
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + code.expiresInSeconds * 1000L
            var interval = code.intervalSeconds * 1000L
            while (System.currentTimeMillis() < deadline) {
                delay(interval)
                val form =
                    FormBody
                        .Builder()
                        .add("client_id", CLIENT_ID)
                        .add("client_secret", CLIENT_SECRET)
                        .add("code", code.deviceCode)
                        .add("grant_type", GRANT_TYPE_DEVICE)
                        .build()
                val json = post(TOKEN_URL, form) ?: return@withContext PollResult.Failed("network")
                when (val error = json.optString("error")) {
                    "" -> {
                        val access = json.optString("access_token").takeIf { it.isNotBlank() }
                            ?: return@withContext PollResult.Failed("no access token")
                        persist(
                            context = context,
                            accessToken = access,
                            refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() },
                            expiresInSeconds = json.optInt("expires_in", 3600),
                        )
                        return@withContext PollResult.Success(access)
                    }
                    // The user has not finished yet; keep waiting.
                    "authorization_pending" -> Unit
                    // Explicitly asked to back off. Ignoring this escalates to access_denied.
                    "slow_down" -> interval += 5_000L
                    else -> return@withContext PollResult.Failed(error)
                }
            }
            PollResult.Failed("expired")
        }

    /**
     * Returns a valid access token, refreshing when it is near expiry. Null when no session exists
     * or the refresh was rejected — a rejected refresh means the grant was revoked (password
     * change, sign-out, or the 6-month idle expiry), so the stored session is cleared rather than
     * left to fail on every request.
     */
    suspend fun validAccessToken(context: Context): String? =
        withContext(Dispatchers.IO) {
            val prefs = context.dataStore.data
            val expiresAt = context.dataStore.get(InnerTubeOAuthExpiresAtKey, 0L)
            val current = context.dataStore.get(InnerTubeOAuthTokenKey, "")
            if (current.isNotBlank() && expiresAt - REFRESH_SKEW_MS > System.currentTimeMillis()) {
                return@withContext current
            }
            val refresh = context.dataStore.get(InnerTubeOAuthRefreshTokenKey, "")
            if (refresh.isBlank()) return@withContext null

            val form =
                FormBody
                    .Builder()
                    .add("client_id", CLIENT_ID)
                    .add("client_secret", CLIENT_SECRET)
                    .add("refresh_token", refresh)
                    .add("grant_type", "refresh_token")
                    .build()
            val json = post(TOKEN_URL, form)
            val access = json?.optString("access_token")?.takeIf { it.isNotBlank() }
            if (access == null) {
                Timber.tag(TAG).w("Refresh rejected — clearing the OAuth session")
                clear(context)
                return@withContext null
            }
            persist(
                context = context,
                accessToken = access,
                // Google usually omits a new refresh token here; keep the existing one.
                refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() },
                expiresInSeconds = json.optInt("expires_in", 3600),
            )
            access
        }

    private suspend fun persist(
        context: Context,
        accessToken: String,
        refreshToken: String?,
        expiresInSeconds: Int,
    ) {
        context.dataStore.edit { prefs ->
            prefs[InnerTubeOAuthTokenKey] = accessToken
            prefs[InnerTubeOAuthExpiresAtKey] = System.currentTimeMillis() + expiresInSeconds * 1000L
            refreshToken?.let { prefs[InnerTubeOAuthRefreshTokenKey] = it }
        }
    }

    /** Signs out: revokes the grant server-side (best effort) and drops the local session. */
    suspend fun signOut(context: Context) {
        withContext(Dispatchers.IO) {
            val refresh = context.dataStore.get(InnerTubeOAuthRefreshTokenKey, "")
            if (refresh.isNotBlank()) {
                post(REVOKE_URL, FormBody.Builder().add("token", refresh).build())
            }
            clear(context)
        }
    }

    private suspend fun clear(context: Context) {
        context.dataStore.edit { prefs ->
            prefs.remove(InnerTubeOAuthTokenKey)
            prefs.remove(InnerTubeOAuthRefreshTokenKey)
            prefs.remove(InnerTubeOAuthExpiresAtKey)
        }
    }

    /** True when a device-flow session exists at all (regardless of access-token freshness). */
    fun isSignedIn(context: Context): Boolean =
        context.dataStore.get(InnerTubeOAuthRefreshTokenKey, "").isNotBlank()
}
