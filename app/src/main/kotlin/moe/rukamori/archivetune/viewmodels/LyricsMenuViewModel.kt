/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.viewmodels

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.common.collect.ImmutableList
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import androidx.datastore.preferences.core.Preferences
import moe.rukamori.archivetune.constants.AutoTranslateLyricsKey
import moe.rukamori.archivetune.constants.TranslateLanguageKey
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.ai.AiLyricsTranslator
import moe.rukamori.archivetune.ai.toAiServiceConfig
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.LyricsEntity
import moe.rukamori.archivetune.lyrics.LyricsHelper
import moe.rukamori.archivetune.lyrics.LyricsResult
import moe.rukamori.archivetune.lyrics.LyricsUtils
import moe.rukamori.archivetune.lyrics.LyricsUtils.displayLyricsText
import moe.rukamori.archivetune.lyrics.LyricsUtils.isLineSyncedLrc
import moe.rukamori.archivetune.lyrics.LyricsUtils.isTtml
import moe.rukamori.archivetune.constants.AutoTranslateExcludedLanguagesKey
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.utils.NetworkConnectivityObserver
import moe.rukamori.archivetune.utils.dataStore
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

sealed interface LyricsSearchScreenState {
    data object Loading : LyricsSearchScreenState

    @Immutable
    data class Success(
        val results: ImmutableList<LyricsSearchResultUiModel>,
        val isSearching: Boolean,
    ) : LyricsSearchScreenState

    data object Empty : LyricsSearchScreenState

    @Immutable
    data class Error(
        @StringRes val messageResId: Int,
    ) : LyricsSearchScreenState
}

@Immutable
data class LyricsSearchResultUiModel(
    val id: String,
    val providerName: String,
    val lyrics: String,
    val preview: String,
    val lineCount: Int,
    val characterCount: Int,
    val isLineSynced: Boolean,
    val isWordSynced: Boolean,
)

data class LyricsTranslationUndoSnapshot(
    val mediaId: String,
    val lyrics: String,
    val source: String,
    val providerName: String,
)

@HiltViewModel
class LyricsMenuViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val lyricsHelper: LyricsHelper,
        val database: MusicDatabase,
        private val networkConnectivity: NetworkConnectivityObserver,
    ) : ViewModel() {
        private var job: Job? = null
        private var aiTranslationJob: Job? = null
        private var translationTarget: Pair<String, String>? = null
        private val translationGeneration = AtomicLong(0L)
        private val searchGeneration = AtomicLong(0L)
        private val _lyricsSearchState = MutableStateFlow<LyricsSearchScreenState>(LyricsSearchScreenState.Empty)
        val lyricsSearchState: StateFlow<LyricsSearchScreenState> = _lyricsSearchState.asStateFlow()
        private val _isRefetching = MutableStateFlow(false)
        val isRefetching: StateFlow<Boolean> = _isRefetching.asStateFlow()
        private val _refetchCompletionEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val refetchCompletionEvents: SharedFlow<Unit> = _refetchCompletionEvents.asSharedFlow()
        val isAiTranslating = MutableStateFlow(false)
        private val _translationUndo = MutableStateFlow<LyricsTranslationUndoSnapshot?>(null)
        val translationUndo: StateFlow<LyricsTranslationUndoSnapshot?> = _translationUndo.asStateFlow()

        /**
         * Set of media IDs for which the user has clicked "Undo Translation". While a media ID is
         * in this set, auto-translation is suppressed — the user explicitly reverted the
         * translation and does NOT want it re-translated automatically.
         */
        private val _translationDismissedMediaIds = MutableStateFlow<Set<String>>(emptySet())
        val translationDismissedMediaIds: StateFlow<Set<String>> =
            _translationDismissedMediaIds.asStateFlow()

        private val _aiTranslationEvents = MutableSharedFlow<String>()
        val aiTranslationEvents: SharedFlow<String> = _aiTranslationEvents.asSharedFlow()

        private val _isNetworkAvailable = MutableStateFlow(false)
        val isNetworkAvailable: StateFlow<Boolean> = _isNetworkAvailable.asStateFlow()

        init {
            viewModelScope.launch {
                networkConnectivity.networkStatus.collect { isConnected ->
                    _isNetworkAvailable.value = isConnected
                }
            }

            _isNetworkAvailable.value =
                try {
                    networkConnectivity.isCurrentlyConnected()
                } catch (e: Exception) {
                    true
                }
        }

        fun search(
            mediaId: String,
            title: String,
            artist: String,
            album: String?,
            duration: Int,
        ) {
            val generation = searchGeneration.incrementAndGet()
            job?.cancel()
            _lyricsSearchState.value = LyricsSearchScreenState.Loading
            job =
                viewModelScope.launch(Dispatchers.IO) {
                    val resultModels = mutableListOf<LyricsSearchResultUiModel>()
                    try {
                        lyricsHelper.getAllLyrics(
                            mediaId = mediaId,
                            songTitle = title,
                            songArtists = artist,
                            songAlbum = album,
                            duration = duration,
                            forceRefresh = true,
                        ) { result ->
                            if (generation != searchGeneration.get()) return@getAllLyrics
                            val model = result.toUiModel(resultModels.size)
                            if (model.preview.isBlank()) return@getAllLyrics

                            resultModels += model
                            _lyricsSearchState.value =
                                LyricsSearchScreenState.Success(
                                    results = ImmutableList.copyOf(resultModels),
                                    isSearching = true,
                                )
                        }
                        if (generation != searchGeneration.get()) return@launch
                        _lyricsSearchState.value =
                            if (resultModels.isEmpty()) {
                                LyricsSearchScreenState.Empty
                            } else {
                                LyricsSearchScreenState.Success(
                                    results = ImmutableList.copyOf(resultModels),
                                    isSearching = false,
                                )
                            }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        if (generation == searchGeneration.get()) {
                            _lyricsSearchState.value = LyricsSearchScreenState.Error(R.string.error_unknown)
                        }
                    }
                }
        }

        fun cancelSearch() {
            searchGeneration.incrementAndGet()
            job?.cancel()
            job = null
        }

        fun resetSearchState() {
            cancelSearch()
            _lyricsSearchState.value = LyricsSearchScreenState.Empty
        }

        fun refetchLyrics(mediaMetadata: MediaMetadata) {
            cancelAiTranslation()
            if (!_isRefetching.compareAndSet(expect = false, update = true)) return

            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val result = lyricsHelper.getLyricsWithProvider(mediaMetadata, forceRefresh = true)
                    database.withTransaction {
                        replaceLyrics(
                            id = mediaMetadata.id,
                            lyrics = result.lyrics,
                            source = LyricsEntity.Source.REMOTE.value,
                            providerName = result.providerName,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                } finally {
                    _isRefetching.value = false
                    _refetchCompletionEvents.tryEmit(Unit)
                }
            }
        }

        fun updateLyrics(
            mediaMetadata: MediaMetadata,
            lyrics: String,
            source: LyricsEntity.Source = LyricsEntity.Source.USER_EDIT,
            providerName: String = "",
        ) {
            cancelAiTranslation()
            viewModelScope.launch(Dispatchers.IO) {
                // When AI_TRANSLATION is saved without an explicit providerName (e.g.
                val effectiveProviderName =
                    if ((source == LyricsEntity.Source.AI_TRANSLATION || source == LyricsEntity.Source.TRANSLATION) && providerName.isBlank()) {
                        captureLyricsBeforeTranslation(mediaMetadata.id)
                        _translationDismissedMediaIds.value =
                            _translationDismissedMediaIds.value - mediaMetadata.id
                        val fromSnapshot =
                            _translationUndo.value
                                ?.takeIf { it.mediaId == mediaMetadata.id }
                                ?.providerName
                                .orEmpty()
                        if (fromSnapshot.isNotBlank()) {
                            fromSnapshot
                        } else {
                            database
                                .withTransaction { getLyricsById(mediaMetadata.id) }
                                ?.providerName
                                .orEmpty()
                        }
                    } else {
                        providerName
                    }
                val lyricsToSave =
                    when (source) {
                        LyricsEntity.Source.REMOTE,
                        LyricsEntity.Source.EMBEDDED,
                        LyricsEntity.Source.USER_SELECTION,
                        -> LyricsUtils.lyricsOrNotFound(lyrics)

                        LyricsEntity.Source.USER_EDIT,
                        -> lyrics

                        LyricsEntity.Source.AI_TRANSLATION,
                        LyricsEntity.Source.TRANSLATION,
                        -> usableTranslatedLyrics(lyrics) ?: return@launch
                    }
                database.query {
                    replaceLyrics(
                        id = mediaMetadata.id,
                        lyrics = lyricsToSave,
                        source = source.value,
                        providerName = effectiveProviderName,
                    )
                }
            }
        }

        /** Renderers call this on track/text changes, even when auto-translation is disabled. */
        fun onLyricsTargetChanged(mediaId: String, lyrics: String?) {
            val target = mediaId to lyrics.orEmpty()
            if (translationTarget != null && translationTarget != target) cancelAiTranslation()
            translationTarget = target
        }

        private fun translationIdentity(prefs: Preferences): List<Any?> = listOf(
            prefs.toAiServiceConfig().cacheIdentity,
            prefs[AutoTranslateLyricsKey] ?: false,
            prefs[TranslateLanguageKey],
            prefs[AutoTranslateExcludedLanguagesKey] ?: emptySet<String>(),
        )

        fun translateLyricsWithAi(
            mediaMetadata: MediaMetadata,
            lyrics: String,
            targetLanguage: String,
            automatic: Boolean = false,
        ) {
            onLyricsTargetChanged(mediaMetadata.id, lyrics)
            if (lyrics.isBlank() || !isAiTranslating.compareAndSet(false, true)) return
            val generation = translationGeneration.incrementAndGet()
            aiTranslationJob = viewModelScope.launch(Dispatchers.IO) {
                var monitor: Job? = null
                val valid = java.util.concurrent.atomic.AtomicBoolean(true)
                try {
                    val prefs = context.dataStore.data.first()
                    val identity = translationIdentity(prefs)
                    val config = prefs.toAiServiceConfig()
                    if (!config.canCallApi) return@launch
                    if (config.provider == moe.rukamori.archivetune.constants.AiProvider.DEEPL &&
                        moe.rukamori.archivetune.api.DeepLService.targetCode(targetLanguage.ifBlank { "ENGLISH" }) == null) {
                        if (!automatic) _aiTranslationEvents.emit(context.getString(R.string.ai_unsupported_translation_target))
                        return@launch
                    }
                    if (automatic) {
                        if (prefs[AutoTranslateLyricsKey] != true) return@launch
                        val dominant = LyricsUtils.detectDominantLanguageCode(lyrics)
                        if (dominant != null && LyricsUtils.matchesExcludedLanguage(
                                dominant, prefs[AutoTranslateExcludedLanguagesKey] ?: emptySet())) return@launch
                    }
                    val expected = database.withTransaction { getLyricsById(mediaMetadata.id) }
                    val requestJob = currentCoroutineContext()[Job]!!
                    monitor = launch {
                        context.dataStore.data.collect { current ->
                            if (translationIdentity(current) != identity) {
                                valid.set(false)
                                requestJob.cancel()
                            }
                        }
                    }
                    val translated = AiLyricsTranslator().translate(
                        config, lyrics, targetLanguage.ifBlank { "ENGLISH" },
                        isCurrent = { valid.get() && generation == translationGeneration.get() },
                    )
                    currentCoroutineContext().ensureActive()
                    if (translationIdentity(context.dataStore.data.first()) != identity) return@launch
                    val usable = usableTranslatedLyrics(translated) ?: throw IllegalStateException("Empty translation")
                    val saved = database.withTransaction {
                        currentCoroutineContext().ensureActive()
                        if (!valid.get() || generation != translationGeneration.get() ||
                            getLyricsById(mediaMetadata.id) != expected) return@withTransaction false
                        if (expected != null && expected.source != LyricsEntity.Source.AI_TRANSLATION.value &&
                            expected.source != LyricsEntity.Source.TRANSLATION.value) {
                            _translationUndo.value = LyricsTranslationUndoSnapshot(
                                expected.id, expected.lyrics, expected.source, expected.providerName)
                        }
                        replaceLyrics(mediaMetadata.id, usable, LyricsEntity.Source.AI_TRANSLATION.value,
                            expected?.providerName.orEmpty())
                        true
                    }
                    currentCoroutineContext().ensureActive()
                    if (saved && valid.get() && generation == translationGeneration.get()) {
                        _translationDismissedMediaIds.value = _translationDismissedMediaIds.value - mediaMetadata.id
                        if (!automatic) _aiTranslationEvents.emit(context.getString(R.string.translation_success))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    Log.w(TAG, "AI translation failed")
                    if (!automatic) _aiTranslationEvents.emit(context.getString(R.string.translation_failed))
                } finally {
                    valid.set(false)
                    monitor?.cancel()
                    if (generation == translationGeneration.get()) {
                        isAiTranslating.value = false
                        aiTranslationJob = null
                    }
                }
            }
        }

        private fun usableTranslatedLyrics(lyrics: String): String? =
            LyricsUtils
                .normalizeLyricsText(lyrics)
                .takeIf(LyricsUtils::hasMeaningfulLyricsContent)

        fun cancelAiTranslation() {
            translationGeneration.incrementAndGet()
            aiTranslationJob?.cancel()
            aiTranslationJob = null
            isAiTranslating.value = false
        }

        fun undoTranslation(mediaId: String) {
            cancelAiTranslation()
            viewModelScope.launch(Dispatchers.IO) {
                val snapshot = _translationUndo.value?.takeIf { it.mediaId == mediaId } ?: return@launch
                database.withTransaction {
                    replaceLyrics(
                        id = snapshot.mediaId,
                        lyrics = snapshot.lyrics,
                        source = snapshot.source,
                        providerName = snapshot.providerName,
                    )
                }
                _translationUndo.value = null
                // Mark this media ID as "translation dismissed" so that
                // auto-translate does NOT re-translate it. The user clicked
                // "Undo Translation" — they explicitly do not want the
                // translation back. Auto-translate will be re-enabled for
                // this song only when the user manually triggers translation
                // again (which clears the dismissal via translateLyricsWithAi).
                _translationDismissedMediaIds.value =
                    _translationDismissedMediaIds.value + mediaId
            }
        }

        private suspend fun captureLyricsBeforeTranslation(mediaId: String) {
            if (_translationUndo.value?.mediaId == mediaId) return
            val existing = database.withTransaction { getLyricsById(mediaId) } ?: return
            if (existing.source == LyricsEntity.Source.AI_TRANSLATION.value) return
            if (existing.source == LyricsEntity.Source.TRANSLATION.value) return
            _translationUndo.value =
                LyricsTranslationUndoSnapshot(
                    mediaId = existing.id,
                    lyrics = existing.lyrics,
                    source = existing.source,
                    providerName = existing.providerName,
                )
        }

        private fun LyricsResult.toUiModel(index: Int): LyricsSearchResultUiModel {
            val preview = displayLyricsText(lyrics)
            val lineCount = preview.lineSequence().count { it.isNotBlank() }
            val isTtmlLyrics = isTtml(lyrics)
            val ttmlEntries =
                if (isTtmlLyrics) {
                    runCatching { LyricsUtils.parseTtml(lyrics) }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
            // Delegate to the shared detector so the badge shown here always agrees
            // with what the "Prioritize Word Synced Lyrics" override in LyricsHelper
            // considers word-synced. The detector must cover Enhanced LRC
            // ([mm:ss.xxx]<mm:ss.xxx>word) from YouLyPlus's fallback endpoint as well
            // as TTML <span> entries, otherwise the badge here says "Line Synced" while
            // the override skips the same lyrics, or vice versa.
            val isWordSynced = LyricsUtils.hasWordSyncedLyrics(lyrics)

            return LyricsSearchResultUiModel(
                id = "${providerName}_${lyrics.hashCode()}_$index",
                providerName = providerName,
                lyrics = lyrics,
                preview = preview,
                lineCount = lineCount,
                characterCount = preview.length,
                isLineSynced =
                    if (isTtmlLyrics) {
                        ttmlEntries.isNotEmpty() && !isWordSynced
                    } else {
                        isLineSyncedLrc(lyrics) && !isWordSynced
                    },
                isWordSynced = isWordSynced,
            )
        }

        companion object {
            private const val TAG = "LyricsMenuViewModel"
        }
    }
