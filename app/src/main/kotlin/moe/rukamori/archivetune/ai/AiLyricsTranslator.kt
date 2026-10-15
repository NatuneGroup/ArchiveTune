/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ai

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class AiLyricsTranslator {
    suspend fun translate(
        config: AiServiceConfig,
        lyrics: String,
        targetLanguage: String,
        isCurrent: () -> Boolean = { true },
    ): String {
        val normalizedLanguage = normalizeTargetLanguage(targetLanguage)
        currentCoroutineContext().ensureActive()
        check(isCurrent()) { "Obsolete AI request" }
        val cacheKey = aiCacheIdentity(config.cacheIdentity, normalizedLanguage, lyrics)
        synchronized(resultCache) { resultCache[cacheKey] }?.let { return it }

        val document = AiLyricsDocumentParser.parse(lyrics)
        if (document.segments.isEmpty()) throw AiServiceException("No translatable lyric segments")
        val translated = mutableMapOf<Int, String>()
        document.segments.chunkedByBudget().forEach { batch ->
            val batchTranslations =
                translateBatchResilient(
                    config = config,
                    targetLanguage = normalizedLanguage,
                    batch = batch,
                    formatName = document.formatName,
                )
            batch.forEachIndexed { index, segment ->
                translated[segment.id] = batchTranslations[index]
            }
        }
        currentCoroutineContext().ensureActive()
        check(isCurrent()) { "Obsolete AI request" }
        return document.rebuild(translated).also { result ->
            synchronized(resultCache) { resultCache[cacheKey] = result }
        }
    }

    private suspend fun translateBatchResilient(
        config: AiServiceConfig,
        targetLanguage: String,
        batch: List<AiLyricsSegment>,
        formatName: String,
    ): List<String> {
        if (batch.isEmpty()) return emptyList()
        // Fail the operation atomically: locally substituted originals are not translations.
        // Transport/auth/quota failures cannot be repaired by recursively splitting lyric lines.
        val result = AiTextService.translateLines(
            config = config,
            targetLanguage = targetLanguage,
            lines = batch.map { it.text },
            formatName = formatName,
        )
        if (result.size != batch.size || result.any { it.isBlank() }) {
            throw AiServiceException("AI returned incomplete lyric segments")
        }
        return result
    }

    private fun List<AiLyricsSegment>.chunkedByBudget(): List<List<AiLyricsSegment>> {
        val chunks = ArrayList<List<AiLyricsSegment>>()
        val current = ArrayList<AiLyricsSegment>()
        var currentChars = 0
        forEach { segment ->
            val nextSize = currentChars + segment.text.length
            if (current.isNotEmpty() && (current.size >= MaxItemsPerBatch || nextSize > MaxCharsPerBatch)) {
                chunks.add(current.toList())
                current.clear()
                currentChars = 0
            }
            current.add(segment)
            currentChars += segment.text.length
        }
        if (current.isNotEmpty()) chunks.add(current.toList())
        return chunks
    }

    private fun normalizeTargetLanguage(language: String): String =
        language
            .ifBlank { "ENGLISH" }
            .replace('_', ' ')
            .lowercase()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    private companion object {
        const val MaxItemsPerBatch = 80
        const val MaxCharsPerBatch = 6000
        const val MaxCachedTranslations = 8

        /** Process-wide LRU of finished translations, shared across ViewModel instances. */
        val resultCache =
            object : LinkedHashMap<String, String>(MaxCachedTranslations, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
                    size > MaxCachedTranslations
            }
    }
}
