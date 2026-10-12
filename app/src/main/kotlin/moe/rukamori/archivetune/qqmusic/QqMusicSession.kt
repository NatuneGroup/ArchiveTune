/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * The signed-in QQ Music account: the two values every client call needs, plus the nickname the
 * settings screen shows.
 * QQ Music's own clients authenticate against `musicu.fcg` with a `musickey` ticket minted for the
 * account at login. The web client carries the same ticket in a cookie, where it is called
 * `qm_keyst` (older captures: `qqmusic_key`); the ticket is generated *from* the account's
 * credentials, which is why it is stored rather than derived. `g_tk` — the CSRF parameter several
 * of the RPC modules expect — is derived from the ticket on every request, so a rotated ticket
 * cannot leave a stale `g_tk` behind.
 * Nothing here is a credential the app invents: [uin] and [musicKey] are exactly what Tencent
 * issued for this account, and only the account's own entitlements are ever requested through them.
 */



package moe.rukamori.archivetune.qqmusic

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import moe.rukamori.archivetune.constants.QqMusicMusickeyKey
import moe.rukamori.archivetune.constants.QqMusicNicknameKey
import moe.rukamori.archivetune.constants.QqMusicUinKey
import moe.rukamori.archivetune.utils.get
import timber.log.Timber

/**
 * What the account server needs to mint a new ticket before the current one lapses, exactly as
 * the login reply named it. [keyCreatedAt] and [keyExpiresIn] are seconds; [loginType] is the
 * `tmeLoginType` the ticket was issued under (2 for QQ, 1 for WeChat).
 */
data class QqRefreshMaterial(
    val loginType: Int,
    val refreshKey: String?,
    val refreshToken: String?,
    val accessToken: String?,
    val openId: String?,
    val unionId: String?,
    val expiredAt: Long,
    val keyCreatedAt: Long,
    val keyExpiresIn: Long,
)

/**
 * A usable QQ Music session.
 *
 * [uin] is the account's decimal QQ number — the `musicid` the API reports — and [musicKey] is the
 * `musickey` ticket. [nickname] is display-only and never sent. [refresh] is null for a session
 * signed in before the refresh material was kept, which then simply lives until its ticket lapses.
 */
data class QqMusicSession(
    val uin: String,
    val musicKey: String,
    val nickname: String? = null,
    val refresh: QqRefreshMaterial? = null,
) {
    /**
     * The `Cookie` header the web-facing calls expect. The ticket appears under both of its
     * historical names so a request is accepted whichever one the server reads for that module.
     */
    val cookieHeader: String
        get() = "uin=$uin; qqmusic_uin=$uin; qm_keyst=$musicKey; qqmusic_key=$musicKey"

    /** The CSRF parameter derived from the current ticket, recomputed on every read. */
    val gTk: Int
        get() = QqMusicSign.gTk(musicKey)

    /** The `tmeLoginType` the ticket belongs to. A WeChat ticket is recognisable by its `W_X` prefix. */
    val loginType: Int
        get() = refresh?.loginType ?: inferLoginType(musicKey)

    /**
     * True once half of the ticket's lifetime has passed. Refreshing early rather than at the edge
     * keeps an account that is only used now and then from ever presenting a lapsed ticket.
     */
    internal fun refreshDue(nowSeconds: Long): Boolean {
        val material = refresh ?: return false
        if (material.refreshKey.isNullOrBlank() && material.refreshToken.isNullOrBlank()) return false
        if (material.keyCreatedAt <= 0L || material.keyExpiresIn <= 0L) return false
        return nowSeconds >= material.keyCreatedAt + material.keyExpiresIn / 2
    }

    companion object {
        private const val TAG = "QqMusicSession"

        /** A failed refresh is not retried sooner than this, so an outage does not cost every request. */
        private const val REFRESH_RETRY_MS = 10 * 60 * 1000L

        // Kept beside the session rather than in PreferenceKeys.kt; the JSON holds only what the
        // account server asks for on refresh.
        private val QqMusicCredentialKey = stringPreferencesKey("qqMusicCredential")

        private val json = Json { ignoreUnknownKeys = true }

        private val refreshLock = Mutex()

        @Volatile
        private var lastRefreshAttemptMs = 0L

        internal fun inferLoginType(musicKey: String): Int = if (musicKey.startsWith("W_X")) 1 else 2

        /**
         * The stored session, or null when the account is not connected or only half-written. A
         * blank half is treated as absent rather than as a session that will fail every request.
         *
         * When the stored ticket is past half its lifetime and the account server's refresh material
         * was kept, the ticket is refreshed first and the new one persisted. A refresh that fails
         * leaves the stored session untouched and in use: it is still the account's own ticket, and
         * the service is what decides whether it is still honoured.
         */
        suspend fun read(dataStore: DataStore<Preferences>): QqMusicSession? {
            val stored = readStored(dataStore) ?: return null
            if (!stored.refreshDue(System.currentTimeMillis() / 1000L)) return stored
            return refreshLock.withLock {
                val current = readStored(dataStore) ?: return null
                val nowMs = System.currentTimeMillis()
                if (!current.refreshDue(nowMs / 1000L) || nowMs - lastRefreshAttemptMs < REFRESH_RETRY_MS) {
                    return current
                }
                lastRefreshAttemptMs = nowMs
                val refreshed = QqMusicApi.refresh(current)
                if (refreshed == null) {
                    Timber.tag(TAG).w("QQ Music ticket refresh failed; keeping the current ticket")
                    return current
                }
                write(dataStore, refreshed)
                Timber.tag(TAG).i("QQ Music ticket refreshed")
                refreshed
            }
        }

        private suspend fun readStored(dataStore: DataStore<Preferences>): QqMusicSession? {
            val uin = dataStore.get(QqMusicUinKey, "").trim()
            val musicKey = dataStore.get(QqMusicMusickeyKey, "").trim()
            if (uin.isBlank() || musicKey.isBlank()) return null
            val nickname = dataStore.get(QqMusicNicknameKey, "").trim()
            val refresh = decodeRefresh(dataStore.get(QqMusicCredentialKey, ""))
            return QqMusicSession(
                uin = uin,
                musicKey = musicKey,
                nickname = nickname.ifBlank { null },
                refresh = refresh,
            )
        }

        /** Persists a freshly minted session. */
        suspend fun write(
            dataStore: DataStore<Preferences>,
            session: QqMusicSession,
        ) = dataStore.edit { prefs ->
            prefs[QqMusicUinKey] = session.uin
            prefs[QqMusicMusickeyKey] = session.musicKey
            prefs[QqMusicNicknameKey] = session.nickname.orEmpty()
            val refresh = session.refresh
            if (refresh != null) {
                prefs[QqMusicCredentialKey] = encodeRefresh(refresh)
            } else {
                prefs.remove(QqMusicCredentialKey)
            }
        }

        /** Sign-out: drops the account so no later request can carry the ticket. */
        suspend fun clear(dataStore: DataStore<Preferences>) = dataStore.edit { prefs ->
            prefs.remove(QqMusicUinKey)
            prefs.remove(QqMusicMusickeyKey)
            prefs.remove(QqMusicNicknameKey)
            prefs.remove(QqMusicCredentialKey)
        }

        /**
         * The session a login or refresh reply describes, or null when it names no account or no
         * ticket. Refresh material missing from [credential] is taken from [previous], because a
         * refresh reply is not guaranteed to repeat every field the original login carried.
         */
        internal fun fromCredential(
            credential: JsonObject,
            nickname: String?,
            previous: QqRefreshMaterial? = null,
        ): QqMusicSession? {
            val uin =
                credential.text("musicid")?.takeIf { it != "0" }
                    ?: credential.text("str_musicid")
                    ?: return null
            val musicKey = credential.text("musickey") ?: return null
            val refresh =
                QqRefreshMaterial(
                    loginType =
                        credential.text("loginType")?.toIntOrNull()?.takeIf { it > 0 }
                            ?: previous?.loginType
                            ?: inferLoginType(musicKey),
                    refreshKey = credential.text("refresh_key") ?: previous?.refreshKey,
                    refreshToken = credential.text("refresh_token") ?: previous?.refreshToken,
                    accessToken = credential.text("access_token") ?: previous?.accessToken,
                    openId = credential.text("openid") ?: previous?.openId,
                    unionId = credential.text("unionid") ?: previous?.unionId,
                    expiredAt = credential.number("expired_at") ?: previous?.expiredAt ?: 0L,
                    keyCreatedAt = credential.number("musickeyCreateTime") ?: 0L,
                    keyExpiresIn = credential.number("keyExpiresIn") ?: 0L,
                )
            return QqMusicSession(uin = uin, musicKey = musicKey, nickname = nickname, refresh = refresh)
        }

        private fun encodeRefresh(refresh: QqRefreshMaterial): String =
            buildJsonObject {
                put("loginType", refresh.loginType)
                refresh.refreshKey?.let { put("refresh_key", it) }
                refresh.refreshToken?.let { put("refresh_token", it) }
                refresh.accessToken?.let { put("access_token", it) }
                refresh.openId?.let { put("openid", it) }
                refresh.unionId?.let { put("unionid", it) }
                put("expired_at", refresh.expiredAt)
                put("musickeyCreateTime", refresh.keyCreatedAt)
                put("keyExpiresIn", refresh.keyExpiresIn)
            }.toString()

        private fun decodeRefresh(raw: String): QqRefreshMaterial? {
            if (raw.isBlank()) return null
            val stored = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
            return QqRefreshMaterial(
                loginType = stored.text("loginType")?.toIntOrNull() ?: 2,
                refreshKey = stored.text("refresh_key"),
                refreshToken = stored.text("refresh_token"),
                accessToken = stored.text("access_token"),
                openId = stored.text("openid"),
                unionId = stored.text("unionid"),
                expiredAt = stored.number("expired_at") ?: 0L,
                keyCreatedAt = stored.number("musickeyCreateTime") ?: 0L,
                keyExpiresIn = stored.number("keyExpiresIn") ?: 0L,
            )
        }

        private fun JsonObject.text(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

        private fun JsonObject.number(key: String): Long? = text(key)?.toLongOrNull()
    }
}
