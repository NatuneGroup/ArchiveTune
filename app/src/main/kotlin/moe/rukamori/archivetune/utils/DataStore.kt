/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.utils

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.audiosource.AudioSourceConfig
import moe.rukamori.archivetune.constants.AudioSourceOrderKey
import moe.rukamori.archivetune.constants.HISTORY_DURATION_LEGACY_FLOAT_KEY
import moe.rukamori.archivetune.constants.HISTORY_DURATION_MAX
import moe.rukamori.archivetune.constants.HISTORY_DURATION_MIN
import moe.rukamori.archivetune.constants.HistoryDuration
import moe.rukamori.archivetune.constants.LyricsMode
import moe.rukamori.archivetune.constants.LyricsModeKey
import moe.rukamori.archivetune.constants.PlayerStreamClient
import moe.rukamori.archivetune.constants.PlayerStreamClientKey
import moe.rukamori.archivetune.constants.UpdateChannel
import moe.rukamori.archivetune.constants.UpdateChannelKey
import moe.rukamori.archivetune.constants.canaryChannelMigration
import moe.rukamori.archivetune.extensions.toEnum
import kotlin.properties.ReadOnlyProperty

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    produceMigrations = { _ ->
        listOf(
            object : DataMigration<Preferences> {
                override suspend fun shouldMigrate(currentData: Preferences): Boolean =
                    currentData[HISTORY_DURATION_LEGACY_FLOAT_KEY] != null &&
                        currentData[HistoryDuration] == null

                override suspend fun migrate(currentData: Preferences): Preferences =
                    currentData.toMutablePreferences().apply {
                        val oldFloat = currentData[HISTORY_DURATION_LEGACY_FLOAT_KEY]
                        if (oldFloat != null) {
                            this[HistoryDuration] =
                                oldFloat
                                    .toInt()
                                    .coerceIn(HISTORY_DURATION_MIN, HISTORY_DURATION_MAX)
                            this.remove(HISTORY_DURATION_LEGACY_FLOAT_KEY)
                        }
                    }

                override suspend fun cleanUp() {}
            },
            // Rewrite the legacy alias for the pre-release channel. Only "DAILY_NIGHTLY" triggers
            // it: an already-stored "NIGHTLY" must not match, or the migration re-runs on every
            // DataStore init for every nightly reader.
            object : DataMigration<Preferences> {
                override suspend fun shouldMigrate(currentData: Preferences): Boolean =
                    currentData[UpdateChannelKey] == "DAILY_NIGHTLY"

                override suspend fun migrate(currentData: Preferences): Preferences =
                    currentData.toMutablePreferences().apply {
                        this[UpdateChannelKey] = UpdateChannel.NIGHTLY.name
                    }

                override suspend fun cleanUp() {}
            },
            // The Canary channel is gone. A stored "CANARY" becomes "NIGHTLY" — the feed those
            // installs follow from now on — and the keys only that channel wrote (the About
            // easter-egg unlock flag and the canary release cache) are dropped. The decision is
            // [canaryChannelMigration]; with neither the stored value nor the legacy keys present
            // it is a no-op, so the migration runs at most once.
            object : DataMigration<Preferences> {
                override suspend fun shouldMigrate(currentData: Preferences): Boolean =
                    canaryChannelMigration(
                        storedChannel = currentData[UpdateChannelKey],
                        presentLegacyKeyNames =
                            canaryLegacyKeys.filter { currentData.contains(it) }.map { it.name }.toSet(),
                    ).needed

                override suspend fun migrate(currentData: Preferences): Preferences =
                    currentData.toMutablePreferences().apply {
                        if (currentData[UpdateChannelKey] == "CANARY") {
                            this[UpdateChannelKey] = UpdateChannel.NIGHTLY.name
                        }
                        canaryLegacyKeys.forEach { remove(it) }
                    }

                override suspend fun cleanUp() {}
            },
            // SimpMusic's lyrics renderer was a standalone boolean read only by the SimpMusic player
            // style's lyrics card; as a LyricsMode it now applies to the lyrics page under every
            // style, so a stored `true` has to be carried across.
            object : DataMigration<Preferences> {
                override suspend fun shouldMigrate(currentData: Preferences): Boolean =
                    currentData[LEGACY_SIMPMUSIC_LYRICS_KEY] == true

                override suspend fun migrate(currentData: Preferences): Preferences =
                    currentData.toMutablePreferences().apply {
                        this[LyricsModeKey] = LyricsMode.SIMPMUSIC.name
                        remove(LEGACY_SIMPMUSIC_LYRICS_KEY)
                    }

                override suspend fun cleanUp() {}
            },
            // Stored ARCHIVETUNE_EXTRACTOR values resolved to ANDROID_MUSIC (with login) or WEB_REMIX
            // (without); the option is gone, so rewrite stale values to WEB_REMIX and keep existing
            // users off an unknown enum value.
            object : DataMigration<Preferences> {
                override suspend fun shouldMigrate(currentData: Preferences): Boolean =
                    currentData[PlayerStreamClientKey] in
                        setOf("ARCHIVETUNE_EXTRACTOR", "HI_RES_LOSSLESS")

                override suspend fun migrate(currentData: Preferences): Preferences =
                    currentData.toMutablePreferences().apply {
                        this[PlayerStreamClientKey] = PlayerStreamClient.WEB_REMIX.name
                    }

                override suspend fun cleanUp() {}
            },
            // Source orders written before Spotify, Amazon and QQ became first-class entries in
            // AudioSourceConfig.DEFAULT_ORDER don't list them; the decision (pure, idempotent,
            // unit-tested) inserts the missing ones directly above YouTube and drops unknown
            // names. A blank order stays blank so it keeps tracking the defaults.
            object : DataMigration<Preferences> {
                override suspend fun shouldMigrate(currentData: Preferences): Boolean {
                    val raw = currentData[AudioSourceOrderKey] ?: return false
                    return AudioSourceConfig.migrateStoredOrder(raw) != raw
                }

                override suspend fun migrate(currentData: Preferences): Preferences =
                    currentData.toMutablePreferences().apply {
                        this[AudioSourceOrderKey] = AudioSourceConfig.migrateStoredOrder(this[AudioSourceOrderKey])
                    }

                override suspend fun cleanUp() {}
            },
        )
    },
)

/** The retired `simpMusicLyrics` boolean, kept only so the migration above can read it. */
private val LEGACY_SIMPMUSIC_LYRICS_KEY = androidx.datastore.preferences.core.booleanPreferencesKey("simpMusicLyrics")

// The keys only the removed Canary channel wrote, by literal name — the typed declarations went
// away with the channel (canonical list: constants.CanaryLegacyKeyNames).
private val canaryLegacyKeys: List<Preferences.Key<*>> =
    listOf(
        booleanPreferencesKey("canaryChannelUnlocked"),
        stringPreferencesKey("canary_releases_etag"),
        stringPreferencesKey("canary_releases_json"),
        longPreferencesKey("canary_releases_last_checked_at"),
        stringPreferencesKey("canary_releases_fingerprint"),
    )

object PreferenceStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _prefs = MutableStateFlow<Preferences?>(null)

    @Volatile private var started = false

    /** False in a process that never called [start]: the `:crash` process returns before it. */
    val isStarted: Boolean get() = started

    /**
     * Completes when the first snapshot has landed, so a caller that cannot tolerate a default can
     * wait for the real value.
     *
     * [start] only *launches* the collection. Until its first emission arrives, [get] returns null
     * and every caller silently takes its own default — and a caller that runs once at startup and
     * caches the answer keeps that default for the life of the process. `newImageLoader` did
     * exactly this: on a cold start it could read a null cache size and pin Coil to the built-in
     * default, ignoring the size the reader had chosen, until the app was killed.
     */
    private val firstLoad = CompletableDeferred<Unit>()

    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            scope.launch {
                context.dataStore.data.collect { preferences ->
                    _prefs.value = preferences
                    firstLoad.complete(Unit)
                }
            }
        }
    }

    /** Suspends until the first snapshot lands. Returns immediately once it has. */
    suspend fun awaitFirstLoad() = firstLoad.await()

    /**
     * Blocks the caller until preferences are readable, for the handful of entry points that are
     * not suspending and must not read a default — a factory whose result is cached for the whole
     * process, chiefly.
     *
     * Bounded, because a preference read must never be the reason the app fails to start: if the
     * snapshot has not arrived within [timeoutMs] the caller proceeds on defaults, which is the
     * behaviour it had unconditionally before this existed.
     */
    fun blockUntilLoaded(timeoutMs: Long = FIRST_LOAD_TIMEOUT_MS) {
        if (firstLoad.isCompleted) return
        runBlocking { withTimeoutOrNull(timeoutMs) { firstLoad.await() } }
    }

    private const val FIRST_LOAD_TIMEOUT_MS = 1_500L

    fun <T> get(key: Preferences.Key<T>): T? = _prefs.value?.get(key)

    fun snapshot(): Preferences? = _prefs.value

    fun launchEdit(
        dataStore: DataStore<Preferences>,
        block: MutablePreferences.() -> Unit,
    ) {
        scope.launch {
            dataStore.edit { prefs ->
                prefs.block()
            }
        }
    }
}

operator fun <T> DataStore<Preferences>.get(key: Preferences.Key<T>): T? =
    PreferenceStore.get(key)
        ?: if (Looper.getMainLooper().thread == Thread.currentThread()) {
            null
        } else {
            blockingRead(key)
        }

fun <T> DataStore<Preferences>.get(
    key: Preferences.Key<T>,
    defaultValue: T,
): T =
    PreferenceStore.get(key)
        ?: if (Looper.getMainLooper().thread == Thread.currentThread()) {
            defaultValue
        } else {
            blockingRead(key) ?: defaultValue
        }

/**
 * The off-main-thread fallback both [get] overloads share. It waits on the single shared initial
 * load instead of enqueuing an independent read per call: on a fresh launch dozens of these fire
 * before the snapshot lands, and each cold `data.first()` queued behind the others, serialising
 * callers into multi-second startup jank. A process that never started [PreferenceStore] reads the
 * store directly, because its snapshot is never coming. Bounded, so a blocking get never hangs.
 */
private fun <T> DataStore<Preferences>.blockingRead(key: Preferences.Key<T>): T? =
    runBlocking(Dispatchers.IO) {
        withTimeoutOrNull(1500) {
            if (PreferenceStore.isStarted) {
                PreferenceStore.awaitFirstLoad()
                PreferenceStore.get(key)
            } else {
                data.first()[key]
            }
        }
    }

suspend fun <T> DataStore<Preferences>.getAsync(key: Preferences.Key<T>): T? = data.first()[key]

suspend fun <T> DataStore<Preferences>.getAsync(
    key: Preferences.Key<T>,
    defaultValue: T,
): T = data.first()[key] ?: defaultValue

fun <T> preference(
    context: Context,
    key: Preferences.Key<T>,
    defaultValue: T,
) = ReadOnlyProperty<Any?, T> { _, _ -> context.dataStore[key] ?: defaultValue }

inline fun <reified T : Enum<T>> enumPreference(
    context: Context,
    key: Preferences.Key<String>,
    defaultValue: T,
) = ReadOnlyProperty<Any?, T> { _, _ -> context.dataStore[key].toEnum(defaultValue) }

@Composable
fun <T> rememberPreference(
    key: Preferences.Key<T>,
    defaultValue: T,
): MutableState<T> {
    val context = LocalContext.current

    // Seeded from the in-memory snapshot, not from [defaultValue]. DataStore's flow is
    // asynchronous, so seeding with the default paints one frame of the default before the stored
    // value arrives — visible as a flash of the wrong screen whenever a preference chooses WHICH ui
    // to show (the Home tab briefly rendering the YouTube page before the stored Spotify one).
    // [PreferenceStore] keeps a hot copy of the whole Preferences object for exactly this, so the
    // first frame is already correct; the fallback still applies before the store has loaded.
    val initial = remember { PreferenceStore.get(key) ?: defaultValue }

    val state =
        remember {
            context.dataStore.data
                .map { it[key] ?: defaultValue }
                .distinctUntilChanged()
        }.collectAsStateWithLifecycle(initialValue = initial)

    return remember {
        object : MutableState<T> {
            override var value: T
                get() = state.value
                set(value) {
                    PreferenceStore.launchEdit(context.dataStore) {
                        this[key] = value
                    }
                }

            override fun component1() = value

            override fun component2(): (T) -> Unit = { value = it }
        }
    }
}

@Composable
inline fun <reified T : Enum<T>> rememberEnumPreference(
    key: Preferences.Key<String>,
    defaultValue: T,
): MutableState<T> {
    val context = LocalContext.current

    // See [rememberPreference] — same first-frame seeding, same reason.
    val initial = remember { PreferenceStore.get(key).toEnum(defaultValue = defaultValue) }

    val state =
        remember {
            context.dataStore.data
                .map { it[key].toEnum(defaultValue = defaultValue) }
                .distinctUntilChanged()
        }.collectAsStateWithLifecycle(initialValue = initial)

    return remember {
        object : MutableState<T> {
            override var value: T
                get() = state.value
                set(value) {
                    PreferenceStore.launchEdit(context.dataStore) {
                        this[key] = value.name
                    }
                }

            override fun component1() = value

            override fun component2(): (T) -> Unit = { value = it }
        }
    }
}
