/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import moe.rukamori.archivetune.constants.*
import moe.rukamori.archivetune.innertube.InnerTube
import moe.rukamori.archivetune.innertube.PlaybackAuthState
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.AccountInfo
import moe.rukamori.archivetune.innertube.models.YouTubeClient.Companion.WEB
import moe.rukamori.archivetune.innertube.models.YouTubeClient.Companion.WEB_REMIX
import moe.rukamori.archivetune.innertube.models.response.AccountMenuResponse
import moe.rukamori.archivetune.innertube.utils.hasYouTubeLoginCookie
import moe.rukamori.archivetune.utils.SavedAccount
import moe.rukamori.archivetune.utils.YTPlayerUtils
import moe.rukamori.archivetune.utils.clearPlaybackAuthSession
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.toPlaybackAuthState
import moe.rukamori.archivetune.utils.putLegacyPoToken
import javax.inject.Inject
import javax.inject.Singleton

data class YouTubeLoginSession(
    val authState: PlaybackAuthState,
    val accountName: String,
    val accountEmail: String,
    val accountChannelHandle: String,
    val origin: YouTubeSessionOrigin,
)

class MissingYouTubeDataSyncIdException : IllegalStateException("YouTube context is missing")
class UnverifiedYouTubeContextException : IllegalStateException("YouTube context could not be verified")
class StaleYouTubeLoginException : IllegalStateException("YouTube session changed")

data class YouTubeLoginAttempt internal constructor(val generation: Long, val baseRevision: Long)
data class YouTubeSessionOrigin internal constructor(val revision: Long, val authState: PlaybackAuthState)

@Singleton
class YouTubeLoginRepository internal constructor(
    private val store: DataStore<Preferences>,
    private val currentAuth: () -> PlaybackAuthState,
    private val publishAuth: (PlaybackAuthState) -> Unit,
    private val validateCandidate: suspend (PlaybackAuthState) -> AccountInfo,
    private val ioDispatcher: CoroutineDispatcher,
    private val publishRuntimeRepair: (PlaybackAuthState) -> Unit = publishAuth,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context.dataStore,
        { YouTube.currentPlaybackAuthState() },
        { auth ->
            val changed = YouTube.currentPlaybackAuthState().fingerprint != auth.fingerprint
            YouTube.authState = auth
            if (changed) YTPlayerUtils.clearPlaybackAuthCaches()
        },
        PrivateYouTubeValidator()::validate,
        Dispatchers.IO,
        { YouTube.authState = it },
    )
    private val mutationMutex = Mutex()
    private val ownershipLock = Any()
    private var generation = 0L
    private var revision = 0L
    private var writing = false
    private var pendingLogouts = 0
    private var lastPersistedAuth: PlaybackAuthState? = null
    private val ownershipChanges = MutableStateFlow(0L)
    val changes = ownershipChanges.asStateFlow()
    suspend fun beginAttempt(): YouTubeLoginAttempt = mutationMutex.withLock {
        reconcileLocked()
        synchronized(ownershipLock) {
            val attempt = YouTubeLoginAttempt(++generation, revision)
            ownershipChanges.value = generation
            attempt
        }
    }

    fun isCurrent(attempt: YouTubeLoginAttempt): Boolean = synchronized(ownershipLock) {
        pendingLogouts == 0 && attempt.generation == generation && attempt.baseRevision == revision
    }

    fun cancel(attempt: YouTubeLoginAttempt?) = synchronized(ownershipLock) {
        if (attempt != null && attempt.generation == generation) {
            ++generation
            ownershipChanges.value = generation
        }
        Unit
    }

    fun origin(): YouTubeSessionOrigin = synchronized(ownershipLock) {
        YouTubeSessionOrigin(revision, currentAuth())
    }

    fun isCurrent(origin: YouTubeSessionOrigin): Boolean = synchronized(ownershipLock) {
        !writing && pendingLogouts == 0 && origin.revision == revision && sameAccount(origin.authState, currentAuth())
    }

    /** Non-suspending token/repair publication must not race a persisted account transition. */
    fun publishRepair(origin: YouTubeSessionOrigin, authState: PlaybackAuthState): Boolean = synchronized(ownershipLock) {
        if (!isCurrent(origin) || !sameAccount(origin.authState, authState)) return@synchronized false
        publishRuntimeRepair(authState)
        true
    }

    suspend fun reconcilePersisted(): PlaybackAuthState = mutationMutex.withLock { reconcileLocked() }

    private suspend fun reconcileLocked(): PlaybackAuthState {
        // Deliberately ignore collector payloads: an older queued emission cannot restore an old account.
        val latest = store.data.first().toPlaybackAuthState()
        synchronized(ownershipLock) {
            if (lastPersistedAuth != latest) {
                ++revision
                ++generation
                lastPersistedAuth = latest
                publishLocked(latest)
                ownershipChanges.value = generation
            }
        }
        return latest
    }

    suspend fun completeLogin(
        attempt: YouTubeLoginAttempt,
        cookie: String,
        visitorData: String?,
        dataSyncId: String?,
        savedAccount: SavedAccount? = null,
        legacyPoToken: String? = null,
    ): Result<YouTubeLoginSession> = withContext(ioDispatcher) {
        runCatchingPreservingCancellation {
            check(isCurrent(attempt)) { "YouTube attempt expired" }
            val candidate = PlaybackAuthState(cookie = cookie, visitorData = visitorData, dataSyncId = dataSyncId).normalized()
            check(hasYouTubeLoginCookie(candidate.cookie)) { "YouTube signing cookie is missing" }
            if (candidate.dataSyncId == null) throw MissingYouTubeDataSyncIdException()
            val identity = validateCandidate(candidate)
            currentCoroutineContext().ensureActive()
            val committed = commit(attempt, candidate, identity, savedAccount, legacyPoToken)
            YouTubeLoginSession(committed.authState, identity.name, identity.email.orEmpty(), identity.channelHandle.orEmpty(), committed)
        }
    }

    private suspend fun commit(
        attempt: YouTubeLoginAttempt,
        auth: PlaybackAuthState,
        info: AccountInfo,
        saved: SavedAccount?,
        legacyPoToken: String?,
    ): YouTubeSessionOrigin =
        mutationMutex.withLock {
            reconcileLocked()
            currentCoroutineContext().ensureActive()
            val committedRevision = synchronized(ownershipLock) {
                if (!isCurrent(attempt)) throw StaleYouTubeLoginException()
                // This is the commit reservation. Later cancel does not roll back a linearized commit;
                // later logout is serialized after it and clears it, including its published snapshot.
                writing = true
                ++revision
                ++generation
                revision
            }
            try {
                withContext(NonCancellable) {
                    val persisted = store.edit { prefs ->
                        prefs.clearPlaybackAuthSession()
                        prefs[InnerTubeCookieKey] = requireNotNull(auth.cookie)
                        auth.visitorData?.let { prefs[VisitorDataKey] = it }
                        prefs[DataSyncIdKey] = requireNotNull(auth.dataSyncId)
                        putIdentity(prefs, info)
                        legacyPoToken?.let { prefs.putLegacyPoToken(it) }
                        saved?.let {
                            prefs[YtmSyncKey] = it.ytmSync
                            prefs[SelectedYtmPlaylistsKey] = it.selectedYtmPlaylists
                        }
                    }.toPlaybackAuthState()
                    synchronized(ownershipLock) {
                        lastPersistedAuth = persisted
                        publishLocked(persisted)
                        ownershipChanges.value = generation
                        YouTubeSessionOrigin(committedRevision, persisted)
                    }
                }
            } finally {
                synchronized(ownershipLock) { writing = false }
            }
        }

    suspend fun switchSavedAccount(account: SavedAccount): Result<PlaybackAuthState> =
        runCatchingPreservingCancellation {
            val attempt = beginAttempt()
            completeLogin(attempt, account.innerTubeCookie, account.visitorData, account.dataSyncId, account).getOrThrow().authState
        }

    suspend fun switchChannel(dataSyncId: String): Result<PlaybackAuthState> =
        runCatchingPreservingCancellation {
            val attempt = beginAttempt()
            val auth = store.data.first().toPlaybackAuthState()
            completeLogin(attempt, auth.cookie.orEmpty(), auth.visitorData, dataSyncId).getOrThrow().authState
        }

    suspend fun logout() {
        synchronized(ownershipLock) {
            ++generation
            ++revision
            ++pendingLogouts
            ownershipChanges.value = generation
        }
        try {
            withContext(NonCancellable) {
                mutationMutex.withLock {
                    synchronized(ownershipLock) { writing = true; ++generation; ++revision }
                    try {
                        val auth = store.edit { it.clearPlaybackAuthSession() }.toPlaybackAuthState()
                        synchronized(ownershipLock) {
                            lastPersistedAuth = auth
                            publishLocked(auth)
                            ownershipChanges.value = generation
                        }
                    } finally {
                        synchronized(ownershipLock) { writing = false }
                    }
                }
            }
        } finally {
            synchronized(ownershipLock) { --pendingLogouts }
        }
    }

    suspend fun persistVisitor(origin: YouTubeSessionOrigin, visitor: String): Boolean = updateOwned(origin) {
        if (visitor.isNotBlank()) it[VisitorDataKey] = visitor
    }

    suspend fun persistRepair(origin: YouTubeSessionOrigin, repaired: PlaybackAuthState): Boolean {
        if (!sameAccount(origin.authState, repaired)) return false
        return updateOwned(origin) { prefs ->
            repaired.visitorData?.let { prefs[VisitorDataKey] = it }
            // A repair is not an account switch. Never replace the selected DataSyncId here.
        }
    }

    suspend fun refreshIdentity(origin: YouTubeSessionOrigin): Result<AccountInfo> = withContext(ioDispatcher) {
        runCatchingPreservingCancellation {
            if (!isCurrent(origin)) throw StaleYouTubeLoginException()
            val info = validateCandidate(origin.authState)
            if (!persistIdentity(origin, info)) throw StaleYouTubeLoginException()
            info
        }
    }

    suspend fun persistIdentity(origin: YouTubeSessionOrigin, info: AccountInfo): Boolean = updateOwned(origin) { putIdentity(it, info) }

    suspend fun editOwned(origin: YouTubeSessionOrigin, edit: (MutablePreferences) -> Unit): Boolean = updateOwned(origin, edit)

    suspend fun importManualAuth(cookie: String, visitor: String?, dataSyncId: String?, poToken: String?): Result<YouTubeLoginSession> =
        runCatchingPreservingCancellation {
            completeLogin(beginAttempt(), cookie, visitor, dataSyncId, legacyPoToken = poToken).getOrThrow()
        }

    private suspend fun updateOwned(origin: YouTubeSessionOrigin, edit: (MutablePreferences) -> Unit): Boolean = mutationMutex.withLock {
        reconcileLocked()
        synchronized(ownershipLock) {
            if (!isCurrent(origin)) return@withLock false
            writing = true
        }
        try {
            withContext(NonCancellable) {
                val auth = store.edit { edit(it) }.toPlaybackAuthState()
                synchronized(ownershipLock) {
                    if (lastPersistedAuth != auth) { ++revision; ++generation }
                    lastPersistedAuth = auth
                    publishLocked(auth)
                    ownershipChanges.value = generation
                }
            }
            true
        } finally {
            synchronized(ownershipLock) { writing = false }
        }
    }

    private fun publishLocked(auth: PlaybackAuthState) { publishAuth(auth) }

    private fun putIdentity(prefs: MutablePreferences, info: AccountInfo) {
        prefs[AccountNameKey] = info.name
        prefs[AccountEmailKey] = info.email.orEmpty()
        prefs[AccountChannelHandleKey] = info.channelHandle.orEmpty()
        info.thumbnailUrl?.let { prefs[AccountImageUrlKey] = it } ?: prefs.remove(AccountImageUrlKey)
    }
}

// Core has no public close: one reusable client belongs to this repository-lifetime validator.
private class PrivateYouTubeValidator {
    private val validationInnerTube = InnerTube()
    private val validationMutex = Mutex()

    suspend fun validate(candidate: PlaybackAuthState): AccountInfo =
        withPrivateYouTubeCandidate(validationInnerTube, validationMutex, candidate) { client ->
            client.locale = YouTube.locale
            if (client.proxy != YouTube.proxy) client.proxy = YouTube.proxy
            if (client.proxyUsername != YouTube.proxyUsername) client.proxyUsername = YouTube.proxyUsername
            if (client.proxyPassword != YouTube.proxyPassword) client.proxyPassword = YouTube.proxyPassword
            if (client.dns != YouTube.dns) client.dns = YouTube.dns
            validateYouTubeCandidateContext(candidate, accountChannels = {
                val channels = client.accountChannels(WEB.copy(loginSupported = true, supportsCookieAuthentication = true))
                check(channels.status.value in 200..299) { "YouTube context request failed" }
                Json.parseToJsonElement(channels.bodyAsText())
            }, accountMenu = {
                val response = client.accountMenu(WEB_REMIX)
                check(response.status.value in 200..299) { "YouTube account request failed" }
                response.body<AccountMenuResponse>()
            })
        }
}

/** Both endpoint reads run under the same private lease, with no default-context substitution. */
internal suspend fun validateYouTubeCandidateContext(
    candidate: PlaybackAuthState,
    accountChannels: suspend () -> JsonElement,
    accountMenu: suspend () -> AccountMenuResponse,
): AccountInfo {
    verifySelectedYouTubeContext(accountChannels(), candidate.dataSyncId ?: throw MissingYouTubeDataSyncIdException())
    val identities = accountMenu().actions.mapNotNull {
        it.openPopupAction.popup.multiPageMenuRenderer.header?.activeAccountHeaderRenderer?.toAccountInfo()
    }
    return identities.singleOrNull() ?: throw UnverifiedYouTubeContextException()
}

/** The production validation lease: serialization and cleanup also cover cancellation/configuration failure. */
internal suspend fun <T> withPrivateYouTubeCandidate(
    client: InnerTube,
    mutex: Mutex,
    candidate: PlaybackAuthState,
    block: suspend (InnerTube) -> T,
): T = withPrivateYouTubeCandidate(client, mutex, candidate, { target, state -> target.applyAuthState(state) }, block)

internal suspend fun <C, T> withPrivateYouTubeCandidate(
    client: C,
    mutex: Mutex,
    candidate: PlaybackAuthState,
    applyAuthState: (C, PlaybackAuthState) -> Unit,
    block: suspend (C) -> T,
): T = mutex.withLock {
    try {
        applyAuthState(client, candidate)
        block(client)
    } finally {
        applyAuthState(client, PlaybackAuthState.EMPTY)
    }
}

private fun sameAccount(first: PlaybackAuthState, second: PlaybackAuthState): Boolean =
    first.cookie == second.cookie && first.dataSyncId == second.dataSyncId && first.oauthToken == second.oauthToken

/** Only explicit context evidence is accepted; labels and first/default account position are not proof. */
internal fun verifySelectedYouTubeContext(response: JsonElement, candidate: String) {
    val normalized = PlaybackAuthState(dataSyncId = candidate).normalized().dataSyncId ?: throw MissingYouTubeDataSyncIdException()
    val context = (response as? JsonObject)?.get("responseContext") as? JsonObject
    val webContext = context?.get("mainAppWebResponseContext") as? JsonObject
    val returned = (webContext?.get("dataSyncId") as? JsonPrimitive)?.contentOrNull
        ?.let { PlaybackAuthState(dataSyncId = it).normalized().dataSyncId }
    if (returned != normalized) throw UnverifiedYouTubeContextException()
    val selected = response.objectsNamed("accountItemRenderer").filter {
        (it["isSelected"] as? JsonPrimitive)?.booleanOrNull == true &&
            (it["isDisabled"] as? JsonPrimitive)?.booleanOrNull != true &&
            (it["hasChannel"] as? JsonPrimitive)?.booleanOrNull != false
    }.toList()
    val renderer = selected.singleOrNull() ?: throw UnverifiedYouTubeContextException()
    val endpoint = renderer["serviceEndpoint"] ?: throw UnverifiedYouTubeContextException()
    val delegated = endpoint.contextIds(setOf("onBehalfOfUser", "delegatedSessionId")).toSet()
    val ids = if (delegated.isNotEmpty()) delegated else
        endpoint.contextIds(setOf("obfuscatedSelectedGaiaId", "obfuscatedGaiaId", "accountId")).toSet()
    // For compound brand||user, onBehalfOfUser identifies the delegated (brand) component.
    if (ids != setOf(normalized) && ids != setOf(normalized.substringBefore("||"))) throw UnverifiedYouTubeContextException()
}

private fun JsonElement.objectsNamed(name: String): Sequence<JsonObject> = sequence {
    when (val element = this@objectsNamed) {
        is JsonObject -> {
            (element[name] as? JsonObject)?.let { yield(it) }
            element.values.forEach { yieldAll(it.objectsNamed(name)) }
        }
        is JsonArray -> element.forEach { yieldAll(it.objectsNamed(name)) }
        else -> Unit
    }
}

private fun JsonElement.contextIds(keys: Set<String>): Sequence<String> = sequence {
    when (val element = this@contextIds) {
        is JsonObject -> element.forEach { (key, value) ->
            if (key in keys) {
                (value as? JsonPrimitive)?.contentOrNull?.let { yield(it) }
            } else yieldAll(value.contextIds(keys))
        }
        is JsonArray -> element.forEach { yieldAll(it.contextIds(keys)) }
        else -> Unit
    }
}

private suspend inline fun <T> runCatchingPreservingCancellation(crossinline block: suspend () -> T): Result<T> =
    try { Result.success(block()) } catch (error: Throwable) {
        if (error is CancellationException) throw error
        Result.failure(error)
    }
