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
import moe.rukamori.archivetune.constants.QqMusicMusickeyKey
import moe.rukamori.archivetune.constants.QqMusicNicknameKey
import moe.rukamori.archivetune.constants.QqMusicUinKey
import moe.rukamori.archivetune.utils.get

/**
 * A usable QQ Music session.
 *
 * [uin] is the account's decimal QQ number — the `musicid` the API reports — and [musicKey] is the
 * `musickey` ticket. [nickname] is display-only and never sent.
 */
data class QqMusicSession(
    val uin: String,
    val musicKey: String,
    val nickname: String? = null,
) {
    /**
     * The `Cookie` header the web-facing calls expect. The ticket appears under both of its
     * historical names so a request is accepted whichever one the server reads for that module.
     */
    val cookieHeader: String
        get() = "uin=$uin; qm_keyst=$musicKey; qqmusic_key=$musicKey"

    /** The CSRF parameter derived from the current ticket, recomputed on every read. */
    val gTk: Int
        get() = QqMusicSign.gTk(musicKey)

    companion object {
        /**
         * The stored session, or null when the account is not connected or only half-written. A
         * blank half is treated as absent rather than as a session that will fail every request.
         */
        suspend fun read(dataStore: DataStore<Preferences>): QqMusicSession? {
            val uin = dataStore.get(QqMusicUinKey, "").trim()
            val musicKey = dataStore.get(QqMusicMusickeyKey, "").trim()
            if (uin.isBlank() || musicKey.isBlank()) return null
            val nickname = dataStore.get(QqMusicNicknameKey, "").trim()
            return QqMusicSession(uin = uin, musicKey = musicKey, nickname = nickname.ifBlank { null })
        }

        /** Persists a freshly minted session. */
        suspend fun write(
            dataStore: DataStore<Preferences>,
            session: QqMusicSession,
        ) = dataStore.edit { prefs ->
            prefs[QqMusicUinKey] = session.uin
            prefs[QqMusicMusickeyKey] = session.musicKey
            prefs[QqMusicNicknameKey] = session.nickname.orEmpty()
        }

        /** Sign-out: drops the account so no later request can carry the ticket. */
        suspend fun clear(dataStore: DataStore<Preferences>) = dataStore.edit { prefs ->
            prefs.remove(QqMusicUinKey)
            prefs.remove(QqMusicMusickeyKey)
            prefs.remove(QqMusicNicknameKey)
        }
    }
}
