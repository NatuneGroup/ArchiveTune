/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ai

import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.api.DeepLService
import moe.rukamori.archivetune.constants.*
import moe.rukamori.archivetune.lyrics.LyricsUtils
import moe.rukamori.archivetune.viewmodels.translationIdentity
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.UUID

class AiLyricsRequestRegressionTest {
    private fun newConfig() = AiServiceConfig(AiProvider.CUSTOM, "fixture-not-a-secret", "https://example.invalid/a", UUID.randomUUID().toString())

    @Test fun deepLMapsExplicitTargetsAndNeverCallsRomanization() = runTest {
        mapOf("GERMAN" to "DE", "Spanish" to "ES", "JAPANESE" to "JA",
            "CHINESE_SIMPLIFIED" to "ZH-HANS", "CHINESE_TRADITIONAL" to "ZH-HANT",
            "zh-CN" to "ZH-HANS", "zh-TW" to "ZH-HANT").forEach { (target, code) ->
            assertEquals(code, DeepLService.targetCode(target))
        }
        listOf("URDU", "MARATHI", "not a language").forEach { assertNull(DeepLService.targetCode(it)) }
        var calls = 0
        val romanizer = AiLyricsRomanizer { _, _, _ -> calls++; listOf("never") }
        val deepL = newConfig().copy(provider = AiProvider.DEEPL)
        assertTrue(deepL.canCallApi)
        assertFalse(deepL.supportsRomanization)
        assertTrue(romanizer.romanize(deepL, listOf("日本語")).isEmpty())
        assertEquals(0, calls)
    }

    @Test fun malformedNullBlankAndCountMismatchedSegmentsAreRejectedAtRealResponseBoundary() {
        listOf("not json", "[]", "[null]", "[5]", "[{}]", "[\"\"]", "[\"   \"]", "[\"one\",\"two\"]").forEach { raw ->
            try {
                parseAiLyricSegments(raw, 1)
                fail("Accepted incomplete provider output: $raw")
            } catch (_: Exception) { }
        }
        assertEquals(listOf("original"), parseAiLyricSegments("[\"original\"]", 1))
        assertEquals(listOf("hola"), parseAiLyricSegments("```json\n[\"hola\"]\n```", 1))
    }

    @Test fun failedTranslationIsNotCachedAndRepairedRetryPreservesSourceAndTiming() = runTest {
        listOf("malformed", "[]", "[null,null]", "[\"one\"]", "[\"one\",\"\"]").forEach { failedResponse ->
            val config = newConfig()
            val source = "[00:01.234]原文\n[00:02.500]次"
            var calls = 0
            val translator = AiLyricsTranslator { _, target, lines, format ->
                calls++
                assertEquals("English", target)
                assertEquals(listOf("原文", "次"), lines)
                assertEquals("synced LRC", format)
                parseAiLyricSegments(if (calls == 1) failedResponse else "[\"first\",\"next\"]", lines.size)
            }
            var persisted = source
            var successEvents = 0
            try {
                persisted = translator.translate(config, source, "ENGLISH")
                successEvents++
                fail("Failed response became a successful document")
            } catch (_: Exception) { }
            assertEquals(source, persisted)
            assertEquals(0, successEvents)
            val accepted = translator.translate(config, source, "ENGLISH")
            assertEquals(2, calls)
            val entries = LyricsUtils.parseLyrics(accepted)
            assertEquals(listOf(1234L, 2500L), entries.map { it.time })
            assertEquals(listOf("原文", "次"), entries.map { it.text })
            assertEquals(listOf("first", "next"), entries.map { it.providerTranslationText })
            assertEquals(accepted, translator.translate(config, source, "ENGLISH"))
            assertEquals(2, calls)
        }
    }

    @Test fun authQuotaOfflineAndPartialBatchFailuresHaveBoundedCallsAndNoSuccessCache() = runTest {
        listOf("401 auth", "429 quota", "offline").forEach { reason ->
            var calls = 0
            val translator = AiLyricsTranslator { _, _, _, _ -> calls++; throw IOException(reason) }
            val config = newConfig()
            repeat(2) {
                try { translator.translate(config, (1..40).joinToString("\n") { "line$it" }, "ENGLISH"); fail("Failure accepted") }
                catch (_: IOException) { }
            }
            assertEquals(2, calls) // One request per batch, never a recursive per-line fanout.
        }
        var calls = 0
        var failSecondBatch = true
        val translator = AiLyricsTranslator { _, _, lines, _ ->
            calls++
            if (failSecondBatch && calls == 2) throw IOException("partial track")
            lines.map { "translated $it" }
        }
        val config = newConfig()
        val source = (1..81).joinToString("\n") { "line$it" }
        try { translator.translate(config, source, "ENGLISH"); fail("Partial track accepted") }
        catch (_: IOException) { }
        assertEquals(2, calls)
        failSecondBatch = false
        assertTrue(translator.translate(config, source, "ENGLISH").contains("translated line81"))
        assertEquals(4, calls)
    }

    @Test fun equalValidOutputIsAcceptedButRomanizationEchoAndNullStayRetryable() = runTest {
        val config = newConfig()
        var translateCalls = 0
        val translator = AiLyricsTranslator { _, _, lines, _ -> translateCalls++; lines }
        assertEquals("same", translator.translate(config, "same", "ENGLISH"))
        assertEquals("same", translator.translate(config, "same", "ENGLISH"))
        assertEquals(1, translateCalls)
        listOf<List<String?>>(listOf(null, null), listOf("한", "글"), listOf("han"), listOf("han", null)).forEach { failure ->
            val romanConfig = newConfig()
            var calls = 0
            val romanizer = AiLyricsRomanizer { _, _, _ -> calls++; if (calls == 1) failure else listOf("han", "geul") }
            romanizer.romanize(romanConfig, listOf("한", "글"))
            assertEquals(listOf("han", "geul"), romanizer.romanize(romanConfig, listOf("한", "글")))
            assertEquals(2, calls)
            assertEquals(listOf("han", "geul"), romanizer.romanize(romanConfig, listOf("한", "글")))
            assertEquals(2, calls)
        }
    }

    @Test fun romanizerPersistentFailureIsOneCallAndCancellationPropagates() = runTest {
        var calls = 0
        val romanizer = AiLyricsRomanizer { _, _, _ -> calls++; throw IOException("quota") }
        val config = newConfig()
        val lines = List(40) { "한$it" }
        assertEquals(List<String?>(40) { null }, romanizer.romanize(config, lines))
        assertEquals(List<String?>(40) { null }, romanizer.romanize(config, lines))
        assertEquals(2, calls)
        val cancelled = AiLyricsRomanizer { _, _, _ -> throw CancellationException("fixture") }
        try { cancelled.romanize(newConfig(), listOf("한")); fail("Cancellation swallowed") }
        catch (_: CancellationException) { }
    }

    @Test fun obsoleteCompletionAndCancellationCannotPublishOrPopulateSuccessCache() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var current = true
        var calls = 0
        val config = newConfig()
        val translator = AiLyricsTranslator { _, _, _, _ ->
            calls++
            if (calls == 1) { entered.complete(Unit); release.await() }
            listOf("translated")
        }
        val pending = async {
            runCatching { translator.translate(config, "原文", "ENGLISH") { current } }
        }
        entered.await()
        current = false
        release.complete(Unit)
        assertTrue(pending.await().isFailure)
        current = true
        assertEquals("原文\ntranslated", translator.translate(config, "原文", "ENGLISH") { current })
        assertEquals(2, calls)
        var cancel = true
        val cancelling = AiLyricsTranslator { _, _, _, _ ->
            if (cancel) throw CancellationException("fixture") else listOf("retry")
        }
        val cancelConfig = newConfig()
        try { cancelling.translate(cancelConfig, "原文", "ENGLISH"); fail("Cancellation swallowed") }
        catch (_: CancellationException) { }
        cancel = false
        assertEquals("原文\nretry", cancelling.translate(cancelConfig, "原文", "ENGLISH"))
    }

    @Test fun fullConfigurationAndCanonicalTargetChangeActualRequestCacheIdentities() = runTest {
        val base = newConfig()
        val variants = listOf(base, base.copy(customEndpoint = "https://example.invalid/b"),
            base.copy(model = "other-model"), base.copy(apiKey = "corrected-fixture"),
            base.copy(provider = AiProvider.CHATGPT), base.copy(deeplFormality = "more"))
        assertEquals(variants.size, variants.map { it.cacheIdentity }.distinct().size)
        variants.forEach {
            assertFalse(it.cacheIdentity.contains(it.apiKey))
            assertFalse(it.cacheIdentity.contains("https://"))
        }
        assertNotEquals(aiCacheIdentity("ab", "c"), aiCacheIdentity("a", "bc"))
        var calls = 0
        val translator = AiLyricsTranslator { _, target, _, _ -> calls++; listOf(target) }
        variants.forEach { translator.translate(it, "原文", "ENGLISH") }
        translator.translate(base, "原文", "GERMAN")
        translator.translate(base, "変更", "ENGLISH")
        assertEquals(variants.size + 2, calls)
        val prefs = mutablePreferencesOf(AiProviderKey to AiProvider.CUSTOM.name, AiApiKeyKey to "fixture",
            AiCustomEndpointKey to "https://example.invalid", AiCustomModelKey to "fixture",
            AutoTranslateLyricsKey to true, TranslatorTargetLangKey to "ENGLISH")
        val identity = translationIdentity(prefs, "ENGLISH")
        prefs[TranslateLanguageKey] = "GERMAN" // The unrelated picker key must not govern lyric freshness.
        assertEquals(identity, translationIdentity(prefs, "ENGLISH"))
        prefs[TranslatorTargetLangKey] = "GERMAN"
        assertNotEquals(identity, translationIdentity(prefs, "ENGLISH"))
        prefs[TranslatorTargetLangKey] = "ENGLISH"
        prefs[AutoTranslateLyricsKey] = false
        assertNotEquals(identity, translationIdentity(prefs, "ENGLISH"))
        prefs[AutoTranslateLyricsKey] = true
        prefs[AutoTranslateExcludedLanguagesKey] = setOf("JAPANESE")
        assertNotEquals(identity, translationIdentity(prefs, "ENGLISH"))
    }

    @Test fun delayedOldCanonicalTargetCannotCacheOverCurrentTargetAndOldTargetCanRetry() = runTest {
        val prefs = mutablePreferencesOf(TranslatorTargetLangKey to "ENGLISH")
        val captured = translationIdentity(prefs, "ENGLISH")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val translator = AiLyricsTranslator { _, target, _, _ ->
            calls++
            if (calls == 1) { entered.complete(Unit); release.await() }
            listOf("for $target")
        }
        val config = newConfig()
        val old = async {
            runCatching {
                translator.translate(config, "原文", "ENGLISH") {
                    translationIdentity(prefs, "ENGLISH") == captured
                }
            }
        }
        entered.await()
        prefs[TranslatorTargetLangKey] = "GERMAN"
        assertEquals("原文\nfor German", translator.translate(config, "原文", "GERMAN"))
        release.complete(Unit)
        assertTrue(old.await().isFailure)
        assertEquals("原文\nfor German", translator.translate(config, "原文", "GERMAN"))
        assertEquals(2, calls)
        prefs[TranslatorTargetLangKey] = "ENGLISH"
        assertEquals("原文\nfor English", translator.translate(config, "原文", "ENGLISH"))
        assertEquals(3, calls)
    }
}
