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
        val active: Boolean get() = enabled && config.canCallApi

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
        }
    }

    /**
     * Stable identity for "this track's lyrics as currently parsed". Romanisation is keyed on it so a
     * refetch, an edit or a translation invalidates the cache while a replay of the same text reuses
     * it.
     */
    fun sessionKey(
        mediaId: String?,
        lyrics: String?,
    ): String = "${mediaId.orEmpty()}|${lyrics?.length ?: 0}|${lyrics?.hashCode() ?: 0}"

    /**
     * Resolves romanisation for [lines] in the caller's own index space, or an empty list when
     * nothing has been fetched for [sessionKey] yet.
     */
    fun linesFor(
        sessionKey: String,
        lines: List<String>,
    ): List<String?> {
        val byLine = cache[sessionKey] ?: return emptyList()
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
    fun request(
        sessionKey: String,
        lines: List<String>,
        settings: Settings,
    ) {
        if (!settings.active) return
        if (lines.isEmpty()) return

        cache[sessionKey]?.let { cached ->
            publish(sessionKey, cached)
            return
        }
        if (inFlight.containsKey(sessionKey)) return

        // The exclusion list is checked against the whole lyric rather than per line: a single track
        // is one language for this purpose, and per-line detection would send a Japanese song's
        // occasional English hook to the model as if it were a different track.
        //
        // Comparison goes through LyricsUtils so this and the translation gate agree on what an
        // exclusion means — including that a detected "CHINESE" has to match the picker's
        // CHINESE_SIMPLIFIED / CHINESE_TRADITIONAL, which a direct string compare never did.
        val dominant = LyricsUtils.detectDominantLanguageCode(lines.joinToString("\n"))
        if (dominant != null && LyricsUtils.matchesExcludedLanguage(dominant, settings.excludedLanguages)) {
            Timber.tag(TAG).d("skipping %s: %s is excluded", sessionKey, dominant)
            return
        }
        // Nothing to do for lyrics that are already Latin script. Reuses the built-in detectors so
        // the two engines agree on which lines are candidates at all.
        if (lines.none { LyricsUtils.hasRomanizableScript(it) }) return

        val job =
            scope.async {
                _running.value = true
                try {
                    romanizer.romanize(settings.config, lines)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Timber.tag(TAG).w(t, "AI romanisation failed for %s", sessionKey)
                    emptyList()
                } finally {
                    _running.value = false
                }
            }
        inFlight[sessionKey] = job
        scope.async {
            val result = runCatching { job.await() }.getOrDefault(emptyList())
            inFlight.remove(sessionKey)
            // Index-aligned coming out of the romanizer, then immediately folded into the text-keyed
            // form every reader uses. `associate` would keep the *last* occurrence of a repeated
            // line; build it forwards so a chorus resolves from the first, which is the one whose
            // batch had the most surrounding context.
            val byLine = LinkedHashMap<String, String>(result.size)
            lines.forEachIndexed { index, line ->
                val romanized = result.getOrNull(index)?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEachIndexed
                byLine.putIfAbsent(line.trim(), romanized)
            }
            if (byLine.isNotEmpty()) {
                cache[sessionKey] = byLine
                trimCache(sessionKey)
                publish(sessionKey, byLine)
            }
        }
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
