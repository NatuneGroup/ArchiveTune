/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Signing in to QQ Music with the account's own QR code, the way the official web client does it.
 * The user scans the code with the QQ app they already have. What follows is Tencent's own login
 * chain: the QR is issued by `ptlogin2`, the scan is confirmed against the same endpoint, and the
 * resulting `p_skey` is exchanged for a QQ Music ticket through the account server. Nothing is
 * scraped and no credential is invented — the ticket this produces is the one Tencent issued for
 * this account, and it is used only for that account's own playback.
 * The chain is four hops and each one is needed:
 * 1. `ptqrshow` returns the image plus a `qrsig` cookie, which *is* the login session id. It is
 * never sent back as a parameter, only hashed into the poll token.
 * 2. `ptqrlogin` is polled while the user scans. Its reply is the `ptuiCB(...)` script below,
 * whose first argument is the state and whose third carries a signed `uin`/`ptsigx` pair.
 * 3. `check_sig` exchanges that pair for the `p_skey` cookie. This hop cannot be skipped: the
 * `p_skey` is both the CSRF basis for the next call and the account proof it needs.
 * 4. `oauth2.0/authorize` turns the `p_skey` into a one-shot authorisation code, which
 * `QQConnectLogin.LoginServer` trades for the ticket the playback calls use.
 * Every hop fails closed: a null at any point means "ask for a new code", never a half-signed-in
 * state.
 */



package moe.rukamori.archivetune.qqmusic

import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber

/** Where a scanned QR code has got to. */
internal enum class QqQrStatus {
    /** No scan yet — keep polling. */
    WAITING,

    /** Scanned, waiting for the user to confirm on their phone. */
    SCANNED,

    /** Confirmed; the account can now be exchanged for a session. */
    CONFIRMED,

    /** The code timed out; a new one is needed. */
    EXPIRED,

    /** The user declined the scan. */
    REFUSED,

    /** Anything else the login server reports. */
    FAILED,
}

/**
 * The `ptuiCB(...)` reply, split into the fields the flow uses.
 *
 * The script is positional: the state code first, the signed redirect URL third, the human message
 * fifth and the nickname sixth. [uin] and [ptsigx] are read out of the redirect URL rather than
 * returned as fields, which is why they are extracted here.
 */
internal data class QqPtuiCallback(
    val code: String,
    val redirectUrl: String,
    val message: String,
    val nickname: String?,
    val uin: String?,
    val ptsigx: String?,
) {
    val status: QqQrStatus get() = QqQrProtocol.statusOf(code)
}

/** The endpoints and the wire format of Tencent's login chain. */
internal object QqQrProtocol {
    const val QR_IMAGE_URL = "https://ssl.ptlogin2.qq.com/ptqrshow"
    const val POLL_URL = "https://ssl.ptlogin2.qq.com/ptqrlogin"
    const val CHECK_SIG_URL = "https://ssl.ptlogin2.graph.qq.com/check_sig"
    const val AUTHORIZE_URL = "https://graph.qq.com/oauth2.0/authorize"

    /** The QQ Music web application, as the login server knows it. */
    const val APP_ID = "716027609"
    const val THIRD_PARTY_APP_ID = "100497308"

    const val REDIRECT_URI = "https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=https://y.qq.com/"
    const val LOGIN_JUMP = "https://graph.qq.com/oauth2.0/login_jump"
    const val REFERER = "https://xui.ptlogin2.qq.com/"

    /**
     * QQ Connect's QR states. Only the QQ numbering is mapped: the WeChat flow reports a second
     * numbering (402–408) over its own long poll, and this source does not implement that flow.
     */
    fun statusOf(code: String): QqQrStatus =
        when (code.trim()) {
            "0" -> QqQrStatus.CONFIRMED
            "66" -> QqQrStatus.WAITING
            "67" -> QqQrStatus.SCANNED
            "65" -> QqQrStatus.EXPIRED
            "68" -> QqQrStatus.REFUSED
            else -> QqQrStatus.FAILED
        }

    private val callbackPattern = Regex("""ptuiCB\((.*)\)""", RegexOption.DOT_MATCHES_ALL)

    /** Single-quoted script arguments, tolerating backslash escapes as the server emits them. */
    private val argumentPattern = Regex("""'((?:\\.|[^'])*)'""")

    fun parseCallback(body: String): QqPtuiCallback? {
        val inner = callbackPattern.find(body)?.groupValues?.get(1) ?: return null
        val arguments = argumentPattern.findAll(inner).map { it.groupValues[1] }.toList()
        if (arguments.size < 3) return null
        val redirect = arguments[2]
        return QqPtuiCallback(
            code = arguments[0],
            redirectUrl = redirect,
            message = arguments.getOrElse(4) { "" },
            nickname = arguments.getOrNull(5)?.takeIf { it.isNotBlank() },
            uin = queryParameter(redirect, "uin"),
            ptsigx = queryParameter(redirect, "ptsigx"),
        )
    }

    /** A value out of a URL, whichever parameter order the server used. */
    fun queryParameter(
        url: String,
        name: String,
    ): String? =
        Regex("[?&]" + Regex.escape(name) + "=([^&]+)")
            .find(url)
            ?.groupValues
            ?.get(1)
            ?.takeIf { it.isNotBlank() }
}

/**
 * One QR code the user can scan, together with the session id its scan is reported against.
 */
internal class QqQrChallenge(
    val image: ByteArray,
    val qrsig: String,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is QqQrChallenge && qrsig == other.qrsig && image.contentEquals(other.image))

    override fun hashCode(): Int = 31 * image.contentHashCode() + qrsig.hashCode()
}

/**
 * A response reduced to what the login chain needs, so a consumed body can never be read twice.
 */
private class QqHttpResponse(
    val code: Int,
    val location: String?,
    val bytes: ByteArray,
)

/**
 * Cookies for one login attempt. A fresh jar per attempt is what keeps a stale `qrsig` or `p_skey`
 * from a previous scan out of the next one; the flow also reads specific cookies back out of it,
 * which [CookieJar] alone does not offer.
 */
private class LoginCookieJar : CookieJar {
    private val stored = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(
        url: HttpUrl,
        cookies: List<Cookie>,
    ) {
        for (cookie in cookies) {
            stored.removeAll { it.name == cookie.name }
            stored.add(cookie)
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> = stored.toList()

    @Synchronized
    fun value(name: String): String? = stored.firstOrNull { it.name == name }?.value

    @Synchronized
    fun clear() = stored.clear()
}

internal object QqQrLogin {
    private const val TAG = "QqQrLogin"

    private const val MUSICU_ENDPOINT = "https://u.y.qq.com/cgi-bin/musicu.fcg"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36"

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private val jar = LoginCookieJar()

    private val client by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            // Each hop's answer is in its own headers — cookies, and the authorisation code in a
            // 302 — so redirects must not be followed.
            .followRedirects(false)
            .followSslRedirects(false)
            .cookieJar(jar)
            .build()
    }

    /**
     * Forgets every cookie from the attempt in progress. Called before a fresh code is fetched so a
     * retry cannot reuse the previous scan's `qrsig`.
     */
    fun reset() {
        jar.clear()
    }

    /** Fetches a fresh QR code. The image is raw PNG bytes. */
    suspend fun requestChallenge(): QqQrChallenge? =
        withContext(Dispatchers.IO) {
            val url =
                QqQrProtocol.QR_IMAGE_URL.toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("appid", QqQrProtocol.APP_ID)
                    .addQueryParameter("e", "2")
                    .addQueryParameter("l", "M")
                    .addQueryParameter("s", "3")
                    .addQueryParameter("d", "72")
                    .addQueryParameter("v", "4")
                    .addQueryParameter("t", Math.random().toString())
                    .addQueryParameter("daid", "383")
                    .addQueryParameter("pt_3rd_aid", QqQrProtocol.THIRD_PARTY_APP_ID)
                    .build()
            val response =
                execute(
                    Request
                        .Builder()
                        .url(url)
                        .header("User-Agent", USER_AGENT)
                        .header("Referer", QqQrProtocol.REFERER)
                        .get()
                        .build(),
                ) ?: return@withContext null
            val qrsig = jar.value("qrsig")
            if (qrsig.isNullOrBlank()) {
                Timber.tag(TAG).w("ptqrshow returned no qrsig cookie")
                return@withContext null
            }
            if (response.bytes.isEmpty()) {
                Timber.tag(TAG).w("ptqrshow returned no image")
                return@withContext null
            }
            QqQrChallenge(image = response.bytes, qrsig = qrsig)
        }

    /** Polls the scan state of [challenge]. */
    suspend fun poll(challenge: QqQrChallenge): QqPtuiCallback? =
        withContext(Dispatchers.IO) {
            val url =
                QqQrProtocol.POLL_URL.toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("u1", QqQrProtocol.LOGIN_JUMP)
                    .addQueryParameter("ptqrtoken", QqMusicSign.ptqrToken(challenge.qrsig).toString())
                    .addQueryParameter("ptredirect", "0")
                    .addQueryParameter("h", "1")
                    .addQueryParameter("t", "1")
                    .addQueryParameter("g", "1")
                    .addQueryParameter("from_ui", "1")
                    .addQueryParameter("ptlang", "2052")
                    .addQueryParameter("action", "0-0-${System.currentTimeMillis()}")
                    .addQueryParameter("js_ver", "20102616")
                    .addQueryParameter("js_type", "1")
                    .addQueryParameter("pt_uistyle", "40")
                    .addQueryParameter("aid", QqQrProtocol.APP_ID)
                    .addQueryParameter("daid", "383")
                    .addQueryParameter("pt_3rd_aid", QqQrProtocol.THIRD_PARTY_APP_ID)
                    .addQueryParameter("has_onekey", "1")
                    .build()
            val response =
                execute(
                    Request
                        .Builder()
                        .url(url)
                        .header("User-Agent", USER_AGENT)
                        .header("Referer", QqQrProtocol.REFERER)
                        .get()
                        .build(),
                ) ?: return@withContext null
            val callback = QqQrProtocol.parseCallback(String(response.bytes, Charsets.UTF_8))
            if (callback == null) {
                Timber.tag(TAG).w("ptqrlogin reply was not a ptuiCB script")
            }
            callback
        }

    /**
     * Completes the chain for a confirmed scan and returns the account's session.
     *
     * The signed `uin`/`ptsigx` pair is exchanged for `p_skey`, that for an authorisation code, and
     * the code for the QQ Music ticket. A null return means the chain broke; the settings screen
     * asks for a new code rather than saving anything.
     */
    suspend fun complete(callback: QqPtuiCallback): QqMusicSession? =
        withContext(Dispatchers.IO) {
            val uin = callback.uin
            val ptsigx = callback.ptsigx
            if (uin.isNullOrBlank() || ptsigx.isNullOrBlank()) {
                Timber.tag(TAG).w("a confirmed scan arrived without a signed uin/ptsigx pair")
                return@withContext null
            }
            val pSkey = exchangeForPSkey(uin, ptsigx) ?: return@withContext null
            val code = requestAuthorizeCode(pSkey) ?: return@withContext null
            exchangeForSession(code, callback.nickname)
        }

    /** The signed pair becomes the `p_skey` cookie. */
    private fun exchangeForPSkey(
        uin: String,
        ptsigx: String,
    ): String? {
        val url =
            QqQrProtocol.CHECK_SIG_URL.toHttpUrl()
                .newBuilder()
                .addQueryParameter("uin", uin)
                .addQueryParameter("pttype", "1")
                .addQueryParameter("service", "ptqrlogin")
                .addQueryParameter("nodirect", "0")
                .addQueryParameter("ptsigx", ptsigx)
                .addQueryParameter("s_url", QqQrProtocol.LOGIN_JUMP)
                .addQueryParameter("ptlang", "2052")
                .addQueryParameter("ptredirect", "100")
                .addQueryParameter("aid", QqQrProtocol.APP_ID)
                .addQueryParameter("daid", "383")
                .addQueryParameter("j_later", "0")
                .addQueryParameter("low_login_hour", "0")
                .addQueryParameter("regmaster", "0")
                .addQueryParameter("pt_login_type", "3")
                .addQueryParameter("pt_aid", "0")
                .addQueryParameter("pt_aaid", "16")
                .addQueryParameter("pt_light", "0")
                .addQueryParameter("pt_3rd_aid", QqQrProtocol.THIRD_PARTY_APP_ID)
                .build()
        execute(
            Request
                .Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", QqQrProtocol.REFERER)
                .get()
                .build(),
        ) ?: return null
        val pSkey = jar.value("p_skey")
        if (pSkey.isNullOrBlank()) {
            Timber.tag(TAG).w("check_sig returned no p_skey cookie")
            return null
        }
        return pSkey
    }

    /** The `p_skey` becomes a one-shot authorisation code, carried in a redirect. */
    private fun requestAuthorizeCode(pSkey: String): String? {
        val form =
            FormBody
                .Builder()
                .add("response_type", "code")
                .add("client_id", QqQrProtocol.THIRD_PARTY_APP_ID)
                .add("redirect_uri", QqQrProtocol.REDIRECT_URI)
                .add("scope", "get_user_info,get_app_friends")
                .add("state", "state")
                .add("switch", "")
                .add("from_ptlogin", "1")
                .add("src", "1")
                .add("update_auth", "1")
                .add("openapi", "1010_1030")
                .add("g_tk", QqMusicSign.gTk(pSkey).toString())
                .add("auth_time", System.currentTimeMillis().toString())
                .add("ui", UUID.randomUUID().toString())
                .build()
        val response =
            execute(
                Request
                    .Builder()
                    .url(QqQrProtocol.AUTHORIZE_URL)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://graph.qq.com/")
                    .post(form)
                    .build(),
            ) ?: return null
        val code =
            response.location
                ?.let { QqQrProtocol.queryParameter(it, "code") }
        if (code == null) {
            Timber.tag(TAG).w("authorize answered %d without an authorisation code", response.code)
        }
        return code
    }

    /** The authorisation code becomes the QQ Music ticket. */
    private fun exchangeForSession(
        code: String,
        nickname: String?,
    ): QqMusicSession? {
        val body =
            buildJsonObject {
                putJsonObject("comm") { put("tmeLoginType", 2) }
                putJsonObject("req_0") {
                    put("module", "QQConnectLogin.LoginServer")
                    put("method", "QQLogin")
                    putJsonObject("param") { put("code", code) }
                }
            }
        val response =
            execute(
                Request
                    .Builder()
                    .url(MUSICU_ENDPOINT)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://y.qq.com/")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build(),
            ) ?: return null
        val parsed =
            runCatching { json.parseToJsonElement(String(response.bytes, Charsets.UTF_8)).jsonObject }
                .getOrElse { error ->
                    Timber.tag(TAG).w(error, "the login exchange body was not a JSON object")
                    return null
                }
        val credential = credentialOf(parsed) ?: run {
            Timber.tag(TAG).w("the login exchange returned no credential")
            return null
        }
        val uin = credential.textOrNull("musicid") ?: credential.textOrNull("str_musicid")
        val musicKey = credential.textOrNull("musickey")
        if (uin.isNullOrBlank() || musicKey.isNullOrBlank()) {
            Timber.tag(TAG).w("the login exchange credential held no account id or ticket")
            return null
        }
        Timber.tag(TAG).i("QQ Music signed in as %s", nickname ?: uin)
        return QqMusicSession(uin = uin, musicKey = musicKey, nickname = nickname)
    }

    /** The credential object, wherever the envelope put it. */
    private fun credentialOf(response: JsonObject): JsonObject? {
        val data =
            response["req_0"]?.jsonObjectOrNull()?.get("data")?.jsonObjectOrNull()
                ?: response["data"]?.jsonObjectOrNull()
        return data?.get("credential")?.jsonObjectOrNull() ?: data
    }

    private fun JsonObject.textOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

    private fun execute(request: Request): QqHttpResponse? =
        try {
            client.newCall(request).execute().use { response ->
                QqHttpResponse(
                    code = response.code,
                    location = response.header("Location"),
                    bytes = response.body?.bytes() ?: ByteArray(0),
                )
            }
        } catch (error: IOException) {
            Timber.tag(TAG).w(error, "a QQ login hop failed")
            null
        }
}
