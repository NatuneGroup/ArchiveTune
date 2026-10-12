/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * Portions © 4nx3b — github.com/4nx3b
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Spotify desktop-persona authentication, ported from echo-spotify-extension
 * (TokenManagerDesktop + ClientTokenManager + DesktopConfig). An sp_dc cookie
 * authorizes a desktop device through the accounts.spotify.com pairing flow and
 * yields a bearer token the spclient endpoints accept for extended-metadata,
 * storage-resolve and playplay key requests.
 */

package moe.rukamori.archivetune.spotify.echo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

object SpotifyDesktopAuth {
    private const val TAG = "SpotifyDesktopAuth"

    private const val CLIENT_ID = "65b708073fc0480ea92a077233ca87bd"
    private const val WEB_CLIENT_ID = "d8a5ed958d274c2e8ee717e6a4b0971d"
    private const val WEB_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private const val PLATFORM_HEADER = "Win32_x86_64"
    private const val DEFAULT_APP_VERSION = "1.2.66.510.gd0327113"
    private const val VERSIONS_URL = "https://loadspot.pages.dev/versions.json"
    private const val DEVICE_AUTH_URL = "https://accounts.spotify.com/oauth2/device/authorize"
    private const val DEVICE_TOKEN_URL = "https://accounts.spotify.com/api/token"
    private const val DEVICE_RESOLVE_URL = "https://accounts.spotify.com/pair/api/resolve"
    private const val CLIENT_TOKEN_URL = "https://clienttoken.spotify.com/v1/clienttoken"
    private const val DEVICE_SCOPE =
        "app-remote-control,playlist-modify,playlist-modify-private,playlist-modify-public," +
            "playlist-read,playlist-read-collaborative,playlist-read-private,streaming," +
            "transfer-auth-session,ugc-image-upload,user-follow-modify,user-follow-read," +
            "user-library-modify,user-library-read,user-modify,user-modify-playback-state," +
            "user-modify-private,user-personalized,user-read-birthdate," +
            "user-read-currently-playing,user-read-email,user-read-play-history," +
            "user-read-playback-position,user-read-playback-state,user-read-private," +
            "user-read-recently-played,user-top-read"
    private const val DEVICE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"

    private val JSON_MEDIA_TYPE = "application/json".toMediaType()

    private val NEXT_DATA_REGEX = Regex(
        """<script id="__NEXT_DATA__" type="application/json"[^>]*>(.*?)</script>""",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )

    private val baseClient =
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

    private val tokenMutex = Mutex()
    private val clientTokenMutex = Mutex()

    @Volatile
    private var accessToken: String? = null

    @Volatile
    private var tokenExpiresAtMs = 0L

    @Volatile
    private var clientToken: String? = null

    @Volatile
    private var clientTokenExpiresAtMs = 0L

    @Volatile
    private var liveAppVersion: String? = null

    private val versionRefreshAttempted = java.util.concurrent.atomic.AtomicBoolean(false)

    fun appVersion(): String = liveAppVersion ?: DEFAULT_APP_VERSION

    private fun maybeRefreshLiveVersion() {
        if (!versionRefreshAttempted.compareAndSet(false, true)) return
        runCatching {
            val request =
                Request
                    .Builder()
                    .url(VERSIONS_URL)
                    .build()
            baseClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use
                val body = response.body?.string().orEmpty()
                val matched =
                    Regex("\"fullversion\"\\s*:\\s*\"([^\"]+)\"")
                        .find(body)
                        ?.groupValues
                        ?.get(1)
                        ?: return@use
                if (matched.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+\\.g[0-9a-f]+"))) {
                    liveAppVersion = matched
                    Timber.tag(TAG).i("Desktop app version refreshed: %s", matched)
                }
            }
        }
    }

    fun userAgent(): String = "Spotify/${buildFromAppVersion(appVersion())} $PLATFORM_HEADER/0 (PC laptop)"

    private fun buildFromAppVersion(version: String): String {
        val parts = version.split('.')
        if (parts.size < 4) return version.filter { it.isDigit() }
        val (major, minor, patch, subPatch) = parts
        return "$major$minor${patch}00$subPatch"
    }

    // The cached desktop token belongs to whichever sp_dc minted it; a different cookie (a new
    // streaming account) must never be answered with the previous account's token.
    @Volatile
    private var tokenOwner: String? = null

    fun reset() {
        accessToken = null
        tokenExpiresAtMs = 0L
        tokenOwner = null
        clientToken = null
        clientTokenExpiresAtMs = 0L
    }

    suspend fun desktopToken(spDc: String): String? {
        accessToken?.takeIf { tokenOwner == spDc && System.currentTimeMillis() < tokenExpiresAtMs }?.let { return it }
        tokenMutex.withLock {
            if (tokenOwner != spDc) {
                accessToken = null
                tokenExpiresAtMs = 0L
            }
            accessToken?.takeIf { System.currentTimeMillis() < tokenExpiresAtMs }?.let { return it }
            val fetched =
                withContext(Dispatchers.IO) {
                    maybeRefreshLiveVersion()
                    runCatching { authorizeDesktopDevice(spDc) }.getOrNull()
                } ?: return null
            accessToken = fetched
            tokenOwner = spDc
            tokenExpiresAtMs = System.currentTimeMillis() + 30L * 60 * 1000
            clientToken = null
            clientTokenExpiresAtMs = 0L
            return fetched
        }
    }

    suspend fun ensureClientToken(spDc: String): String? {
        clientToken?.takeIf { System.currentTimeMillis() < clientTokenExpiresAtMs }?.let { return it }
        clientTokenMutex.withLock {
            clientToken?.takeIf { System.currentTimeMillis() < clientTokenExpiresAtMs }?.let { return it }
            val token = withContext(Dispatchers.IO) { runCatching { fetchClientToken(spDc) }.getOrNull() } ?: return null
            clientToken = token
            clientTokenExpiresAtMs = System.currentTimeMillis() + 20L * 60 * 60 * 1000
            return token
        }
    }

    private fun fetchClientToken(spDc: String): String? {
        maybeRefreshLiveVersion()
        val deviceId = SpotifyEchoResolver.deviceId()
        if (postClientToken(
                clientId = CLIENT_ID,
                clientVersion = appVersion(),
                deviceBrand = "Microsoft",
                deviceModel = "PC",
                os = "windows",
                osVersion = "NT 10.0",
                userAgent = userAgent(),
                webPersona = false,
            )
        ) {
            return clientToken
        }
        if (postClientToken(
                clientId = WEB_CLIENT_ID,
                clientVersion = appVersion(),
                deviceBrand = "unknown",
                deviceModel = "unknown",
                os = "windows",
                osVersion = "NT 10.0",
                userAgent = WEB_USER_AGENT,
                webPersona = true,
            )
        ) {
            return clientToken
        }
        return null
    }

    private fun postClientToken(
        clientId: String,
        clientVersion: String,
        deviceBrand: String,
        deviceModel: String,
        os: String,
        osVersion: String,
        userAgent: String,
        webPersona: Boolean,
    ): Boolean {
        val payload =
            JSONObject().apply {
                put(
                    "client_data",
                    JSONObject().apply {
                        put("client_version", clientVersion)
                        put("client_id", clientId)
                        put(
                            "js_sdk_data",
                            JSONObject().apply {
                                put("device_brand", deviceBrand)
                                put("device_id", SpotifyEchoResolver.deviceId())
                                put("device_model", deviceModel)
                                put("device_type", "computer")
                                put("os", os)
                                put("os_version", osVersion)
                            },
                        )
                    },
                )
            }
        val request =
            Request
                .Builder()
                .url(CLIENT_TOKEN_URL)
                .apply {
                    if (webPersona) {
                        header("User-Agent", userAgent)
                        header("Origin", "https://open.spotify.com")
                        header("Referer", "https://open.spotify.com/")
                    } else {
                        header("User-Agent", userAgent)
                        header("Accept-Language", "en")
                    }
                }.header("Accept", "application/json")
                .header("content-type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        baseClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Timber.tag(TAG).w("Client token request failed: HTTP %d (persona=%s)", response.code, if (webPersona) "web" else "desktop")
                return false
            }
            val body = JSONObject(response.body?.string().orEmpty())
            val token = body.optJSONObject("granted_token")?.optString("token")?.takeIf { it.isNotBlank() }
            if (token != null) {
                clientToken = token
                clientTokenExpiresAtMs = System.currentTimeMillis() + 20L * 60 * 60 * 1000
                return true
            }
            return false
        }
    }

    private fun authorizeDesktopDevice(spDc: String): String? {
        val flowClient = buildFlowClient(spDc)
        val authorization = initiateDeviceAuthorization(flowClient) ?: return null
        val verification = parseVerificationPage(flowClient, authorization.verificationUriComplete) ?: return null
        if (!submitUserCode(flowClient, authorization.userCode, verification)) return null
        return exchangeDeviceCode(flowClient, authorization.deviceCode)
    }

    private class DeviceAuthorization(
        val deviceCode: String,
        val userCode: String,
        val verificationUriComplete: String,
    )

    private class VerificationContext(
        val flowContext: String,
        val csrfToken: String,
        val refererUrl: String,
    )

    private fun initiateDeviceAuthorization(flowClient: OkHttpClient): DeviceAuthorization? {
        val request =
            Request
                .Builder()
                .url(DEVICE_AUTH_URL)
                .header("User-Agent", userAgent())
                .post(
                    FormBody
                        .Builder()
                        .add("client_id", CLIENT_ID)
                        .add("scope", DEVICE_SCOPE)
                        .build(),
                ).build()
        flowClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Timber.tag(TAG).w("Desktop device authorization failed: HTTP %d", response.code)
                return null
            }
            val body = JSONObject(response.body?.string().orEmpty())
            val deviceCode = body.optString("device_code").takeIf { it.isNotBlank() } ?: return null
            val userCode = body.optString("user_code").takeIf { it.isNotBlank() } ?: return null
            val verificationUri = body.optString("verification_uri_complete").takeIf { it.isNotBlank() }
                ?: body.optString("verification_uri").takeIf { it.isNotBlank() } ?: return null
            return DeviceAuthorization(deviceCode, userCode, verificationUri)
        }
    }

    private fun parseVerificationPage(
        flowClient: OkHttpClient,
        verificationUrl: String,
    ): VerificationContext? {
        val request =
            Request
                .Builder()
                .url(verificationUrl)
                .header("User-Agent", userAgent())
                .get()
                .build()
        flowClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Timber.tag(TAG).w("Desktop verification page failed: HTTP %d", response.code)
                return null
            }
            val html = response.body?.string().orEmpty()
            val flowContext = response.request.url.queryParameter("flow_ctx")
                ?.substringBefore(':')
                ?.takeIf { it.isNotBlank() }
                ?: return null
            val scriptBody = NEXT_DATA_REGEX.find(html)?.groupValues?.get(1) ?: return null
            val csrfToken = runCatching { JSONObject(scriptBody) }
                .getOrNull()
                ?.optJSONObject("props")
                ?.optString("initialToken")
                ?.takeIf { it.isNotBlank() }
                ?: return null
            return VerificationContext(flowContext, csrfToken, response.request.url.toString())
        }
    }

    private fun submitUserCode(
        flowClient: OkHttpClient,
        userCode: String,
        verification: VerificationContext,
    ): Boolean {
        val resolveUrl =
            DEVICE_RESOLVE_URL
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("flow_ctx", "${verification.flowContext}:${System.currentTimeMillis() / 1000}")
                .build()
        val request =
            Request
                .Builder()
                .url(resolveUrl)
                .header("User-Agent", userAgent())
                .header("x-csrf-token", verification.csrfToken)
                .header("referer", verification.refererUrl)
                .header("origin", "https://accounts.spotify.com")
                .post(JSONObject().put("code", userCode).toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        flowClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Timber.tag(TAG).w("Desktop user code submission failed: HTTP %d", response.code)
                return false
            }
            val body = JSONObject(response.body?.string().orEmpty())
            return body.optString("result") == "ok"
        }
    }

    private fun exchangeDeviceCode(
        flowClient: OkHttpClient,
        deviceCode: String,
    ): String? {
        val request =
            Request
                .Builder()
                .url(DEVICE_TOKEN_URL)
                .header("User-Agent", userAgent())
                .post(
                    FormBody
                        .Builder()
                        .add("client_id", CLIENT_ID)
                        .add("device_code", deviceCode)
                        .add("grant_type", DEVICE_GRANT_TYPE)
                        .build(),
                ).build()
        flowClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Timber.tag(TAG).w("Desktop device token exchange failed: HTTP %d", response.code)
                return null
            }
            return JSONObject(response.body?.string().orEmpty()).optString("access_token").takeIf { it.isNotBlank() }
        }
    }

    private fun buildFlowClient(spDc: String): OkHttpClient {
        val cookieStore = DesktopCookieStore()
        cookieStore.seed(spDc, "accounts.spotify.com")
        cookieStore.seed(spDc, "spotify.com")
        return baseClient
            .newBuilder()
            .addNetworkInterceptor { chain ->
                val original = chain.request()
                val cookieHeader = mergeCookieHeader(original.header("Cookie"), cookieStore.loadForRequest(original.url))
                val request =
                    original
                        .newBuilder()
                        .header("User-Agent", userAgent())
                        .apply {
                            if (cookieHeader.isNotBlank()) {
                                header("Cookie", cookieHeader)
                            }
                            if (original.header("Referer").isNullOrBlank()) {
                                header("Referer", "https://accounts.spotify.com/")
                            }
                        }.build()
                val response = chain.proceed(request)
                response.headers("Set-Cookie").forEach { raw ->
                    Cookie.parse(request.url, raw)?.let(cookieStore::store)
                }
                response
            }.followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private fun mergeCookieHeader(
        originalHeader: String?,
        scopedCookies: List<Cookie>,
    ): String {
        val cookies = linkedMapOf<String, String>()
        originalHeader
            ?.split(';')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() && it.contains('=') }
            ?.forEach {
                cookies[it.substringBefore('=')] = it.substringAfter('=')
            }
        scopedCookies.forEach { cookies[it.name] = it.value }
        return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private class DesktopCookieStore {
        private val cookies = mutableListOf<Cookie>()

        @Synchronized
        fun seed(value: String, domain: String) {
            val cookie =
                Cookie
                    .Builder()
                    .name("sp_dc")
                    .value(value)
                    .domain(domain)
                    .path("/")
                    .secure()
                    .httpOnly()
                    .build()
            store(cookie)
        }

        @Synchronized
        fun store(cookie: Cookie) {
            if (cookie.expiresAt <= System.currentTimeMillis()) {
                cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                return
            }
            cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            cookies += cookie
            cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
        }

        @Synchronized
        fun loadForRequest(url: HttpUrl): List<Cookie> {
            cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
            return cookies.filter { it.matches(url) }
        }
    }
}
