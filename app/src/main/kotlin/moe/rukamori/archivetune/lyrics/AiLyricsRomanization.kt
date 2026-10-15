/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.lyrics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import moe.rukamori.archivetune.ai.toAiServiceConfig
import moe.rukamori.archivetune.utils.dataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.rukamori.archivetune.ai.AiLyricsRomanizer
import moe.rukamori.archivetune.ai.AiServiceConfig
import moe.rukamori.archivetune.constants.AiApiKeyKey
import moe.rukamori.archivetune.constants.AiCustomEndpointKey
import moe.rukamori.archivetune.constants.AiCustomModelKey
import moe.rukamori.archivetune.constants.AiProvider
import moe.rukamori.archivetune.constants.AiProviderKey
import moe.rukamori.archivetune.constants.AiRomanizeExcludedLanguagesKey
import moe.rukamori.archivetune.constants.AiRomanizeLyricsKey
import moe.rukamori.archivetune.constants.AiSelectedModelKey
import moe.rukamori.archivetune.constants.AutoAiRomanizeLyricsKey
import moe.rukamori.archivetune.constants.DeeplApiKeyKey
import moe.rukamori.archivetune.constants.DeeplFormalityKey
import moe.rukamori.archivetune.constants.OpenRouterApiKeyKey
import moe.rukamori.archivetune.constants.OpenRouterBaseUrlKey
import moe.rukamori.archivetune.constants.OpenRouterModelKey
import moe.rukamori.archivetune.db.entities.LyricsEntity
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreference
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/** AI-provided romanisation for the lyrics views. */
object AiLyricsRomanization {
    private const val TAG = "AiRomanization"

    /** Everything the renderers need to decide whether, and how, to romanise with the AI. */
    @Immutable
    data class Settings(
        val enabled: Boolean,
        val auto: Boolean,
        val excludedLanguages: Set<String>,
        val config: AiServiceConfig,
    ) {
        /**
         * True when AI romanisation should take over from the built-in romanisers.
         *
         * Deliberately independent of [auto]: switching AI romanisation on stops the built-in
         * romanisers immediately, so a configured-but-not-automatic setup shows AI results only, on
         * demand, rather than silently mixing the two engines' spellings.
         */
        val active: Boolean get() = enabled && config.supportsRomanization

        companion object {
            val Disabled =
                Settings(
                    enabled = false,
                    auto = false,
                    excludedLanguages = emptySet(),
                    config = AiServiceConfig(AiProvider.NONE, "", "", ""),
                )
        }
    }

    /**
     * Romanisation for one lyrics session, addressed by the **original line text** rather than by
     * index.
     */
    @Immutable
    data class Result(
        val sessionKey: String,
        val byLine: Map<String, String>,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val romanizer = AiLyricsRomanizer()
    private val inFlight = ConcurrentHashMap<String, Deferred<List<String?>>>()
    private val cache = ConcurrentHashMap<String, Map<String, String>>()

    @Volatile private var currentSettings: Settings? = null
    private var settingsGeneration = 0L
    private var visibleSession: String? = null
    private var observing = false

    @Synchronized
    private fun updateSettings(settings: Settings) {
        if (currentSettings == settings) return
        currentSettings = settings
        settingsGeneration++
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
        cache.clear()
        _results.value = null
        _running.value = false
    }

    @Synchronized
    fun observeSettings(context: Context) {
        if (observing) return
        observing = true
        scope.launch {
            context.applicationContext.dataStore.data.collect { prefs ->
                updateSettings(Settings(
                    enabled = prefs[AiRomanizeLyricsKey] ?: false,
                    auto = prefs[AutoAiRomanizeLyricsKey] ?: false,
                    excludedLanguages = prefs[AiRomanizeExcludedLanguagesKey] ?: emptySet(),
                    config = prefs.toAiServiceConfig(),
                ))
            }
        }
    }

    /** Shared fallback policy: excluded/ineligible AI must not suppress other phonetics. */
    fun isEligible(
        settings: Settings,
        lines: List<String>,
        sourceLanguageHints: List<String> = emptyList(),
    ): Boolean {
        if (!settings.active || lines.isEmpty()) return false
        return LyricsUtils.isAutomaticLanguageEligible(
            lines.joinToString("\n"), settings.excludedLanguages, sourceLanguageHints)
    }

    private val _results = MutableStateFlow<Result?>(null)

    /**
     * Signals that a romanisation finished. Renderers observe this only to know *when* to re-resolve;
     * the values themselves come from [linesFor], which is index-space independent.
     */
    val results: StateFlow<Result?> = _results.asStateFlow()

    private val _running = MutableStateFlow(false)

    /** True while a request is outstanding, for progress affordances in the lyrics menu. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /**
     * Reads the AI-romanisation settings inside composition.
     *
     * Mirrors how `AiIntegrationSettings` builds its own config so a provider/key/model change takes
     * effect on the next recomposition without any explicit invalidation.
     */
    @Composable
    fun rememberSettings(): Settings {
        observeSettings(LocalContext.current)
        val (enabled) = rememberPreference(AiRomanizeLyricsKey, defaultValue = false)
        val (auto) = rememberPreference(AutoAiRomanizeLyricsKey, defaultValue = false)
        val (excluded) = rememberPreference(AiRomanizeExcludedLanguagesKey, defaultValue = emptySet())
        val provider by rememberEnumPreference(AiProviderKey, AiProvider.NONE)
        val (apiKey) = rememberPreference(AiApiKeyKey, defaultValue = "")
        val (customEndpoint) = rememberPreference(AiCustomEndpointKey, defaultValue = "")
        val (selectedModel) = rememberPreference(AiSelectedModelKey, defaultValue = "")
        val (customModel) = rememberPreference(AiCustomModelKey, defaultValue = "")
        val (openRouterApiKey) = rememberPreference(OpenRouterApiKeyKey, defaultValue = "")
        val (openRouterBaseUrl) = rememberPreference(OpenRouterBaseUrlKey, defaultValue = "")
        val (openRouterModel) = rememberPreference(OpenRouterModelKey, defaultValue = "openai/gpt-4o-mini")
        val (deeplApiKey) = rememberPreference(DeeplApiKeyKey, defaultValue = "")
        val (deeplFormality) = rememberPreference(DeeplFormalityKey, defaultValue = "default")

        return remember(
            enabled,
            auto,
            excluded,
            provider,
            apiKey,
            customEndpoint,
            selectedModel,
            customModel,
            openRouterApiKey,
            openRouterBaseUrl,
            openRouterModel,
            deeplApiKey,
            deeplFormality,
        ) {
            Settings(
                enabled = enabled,
                auto = auto,
                excludedLanguages = excluded,
                config =
                    if (provider == AiProvider.OPENROUTER) {
                        AiServiceConfig(
                            provider,
                            openRouterApiKey,
                            openRouterBaseUrl,
                            openRouterModel.ifBlank { "openai/gpt-4o-mini" },
                        )
                    } else {
                        AiServiceConfig(
                            provider = provider,
                            apiKey = if (provider == AiProvider.DEEPL) deeplApiKey else apiKey,
                            customEndpoint = customEndpoint,
                            model = if (provider == AiProvider.CUSTOM) customModel else selectedModel,
                            deeplFormality = deeplFormality,
                        )
                    },
            )
        }.also { updateSettings(it) }
    }

    /**
     * Stable identity for "this track's lyrics as currently parsed". Romanisation is keyed on it so a
     * refetch, an edit or a translation invalidates the cache while a replay of the same text reuses
     * it.
     */
    fun sessionKey(
        mediaId: String?,
        lyrics: String?,
        sourceLanguageHints: List<String> = LyricsUtils.sourceLanguageHints(lyrics),
    ): String = "${mediaId.orEmpty()}|${lyrics?.length ?: 0}|${lyrics?.hashCode() ?: 0}|${hintIdentity(sourceLanguageHints)}"

    private fun hintIdentity(hints: List<String>): String =
        hints.mapNotNull(LyricsUtils::validatedSourceLanguage).distinct().sorted().joinToString(",")

    /**
     * Resolves romanisation for [lines] in the caller's own index space, or an empty list when
     * nothing has been fetched for [sessionKey] yet.
     */
    @Synchronized
    fun linesFor(
        sessionKey: String,
        lines: List<String>,
        sourceLanguageHints: List<String> = emptyList(),
    ): List<String?> {
        selectSession(sessionKey)
        val settings = currentSettings ?: return emptyList()
        if (!isEligible(settings, lines, sourceLanguageHints)) return emptyList()
        val byLine = cache["$sessionKey|${hintIdentity(sourceLanguageHints)}"] ?: return emptyList()
        return lines.map { byLine[it.trim()] }
    }

    /**
     * Parses [lyrics] into lines to send, for the manual "Romanise with AI" action.
     *
     * Does not have to agree with either renderer's index space — results are stored by line text —
     * so this only has to produce the same *set* of lines. It parses rather than splitting on newlines
     * so that timestamps and TTML markup never reach the model.
     */
    fun linesOf(
        lyrics: String?,
        durationSeconds: Int? = null,
    ): List<String> {
        val text = lyrics?.trim().orEmpty()
        if (text.isEmpty() || text == LyricsEntity.LYRICS_NOT_FOUND) return emptyList()
        return runCatching {
            when {
                LyricsUtils.isTtml(text) -> LyricsUtils.parseTtml(text, durationSeconds).map { it.text }
                LyricsUtils.isLineSyncedLrc(text) -> LyricsUtils.parseLyrics(text).map { it.text }
                else -> text.lines().filter { it.isNotBlank() }.map { it.trim() }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Requests romanisation for [lines], joining an in-flight request for the same [sessionKey].
     *
     * Returns immediately; the result is published to [results]. Silent no-op when the settings say
     * not to, when nothing needs romanising, or when the track's dominant language is excluded — the
     * caller does not have to pre-check any of that.
     */
    @Synchronized
    fun request(
        sessionKey: String,
        lines: List<String>,
        settings: Settings,
        sourceLanguageHints: List<String> = emptyList(),
    ) {
        updateSettings(settings)
        selectSession(sessionKey)
        if (settings != currentSettings || !isEligible(settings, lines, sourceLanguageHints)) return
        if (lines.none { LyricsUtils.hasRomanizableScript(it) }) return
        val cacheKey = "$sessionKey|${hintIdentity(sourceLanguageHints)}"
        cache[cacheKey]?.let { publish(sessionKey, it); return }
        if (inFlight.containsKey(sessionKey)) return
        val generation = settingsGeneration
        val job = scope.async(start = CoroutineStart.LAZY) {
            romanizer.romanize(settings.config, lines) {
                synchronized(this@AiLyricsRomanization) { generation == settingsGeneration }
            }
        }
        inFlight[sessionKey] = job
        _running.value = true
        scope.launch {
            try {
                val result = job.await()
                synchronized(this@AiLyricsRomanization) {
                    if (generation != settingsGeneration || visibleSession != sessionKey ||
                        !isEligible(settings, lines, sourceLanguageHints)) return@synchronized
                    val byLine = LinkedHashMap<String, String>()
                    lines.forEachIndexed { index, line ->
                        result.getOrNull(index)?.trim()?.takeIf { it.isNotEmpty() }?.let {
                            byLine.putIfAbsent(line.trim(), it)
                        }
                    }
                    // Do not turn a partially failed track into a permanent session-cache hit.
                    if (byLine.isNotEmpty() && lines.filter { LyricsUtils.hasRomanizableScript(it) }
                            .all { byLine.containsKey(it.trim()) }) {
                        cache[cacheKey] = byLine
                        trimCache(cacheKey)
                        publish(sessionKey, byLine)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Timber.tag(TAG).w("AI romanisation failed")
            } finally {
                synchronized(this@AiLyricsRomanization) {
                    inFlight.remove(sessionKey, job)
                    _running.value = inFlight.isNotEmpty()
                }
            }
        }
        job.start()
    }

    private fun selectSession(sessionKey: String) {
        if (visibleSession == sessionKey) return
        visibleSession = sessionKey
        inFlight.entries.filter { it.key != sessionKey }.forEach { (key, job) ->
            job.cancel()
            inFlight.remove(key, job)
        }
        _results.value = null
        _running.value = inFlight.isNotEmpty()
    }

    private fun publish(
        sessionKey: String,
        byLine: Map<String, String>,
    ) {
        _results.value = Result(sessionKey = sessionKey, byLine = byLine)
    }

    private fun trimCache(keep: String) {
        if (cache.size <= MaxCachedTracks) return
        // ConcurrentHashMap has no LRU; the eviction only has to keep memory bounded, and the
        // AiLyricsRomanizer already holds its own LRU of the expensive part (the model responses).
        cache.keys.firstOrNull { it != keep }?.let { cache.remove(it) }
    }

    private const val MaxCachedTracks = 8
}
