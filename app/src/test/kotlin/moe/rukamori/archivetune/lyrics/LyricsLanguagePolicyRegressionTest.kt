/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.lyrics

import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.ai.AiServiceConfig
import moe.rukamori.archivetune.constants.AiProvider
import org.junit.Assert.*
import org.junit.Test

class LyricsLanguagePolicyRegressionTest {
    private fun preferences(japanese: Boolean = false, korean: Boolean = false, chinese: Boolean = false,
        hindi: Boolean = false, other: Boolean = false, ai: Boolean = false) =
        LyricsRomanizationPreferences(japanese, korean, chinese, hindi, other, ai)

    @Test fun japaneseSelectionNeedsMetadataOrKanaNotInstalledData() {
        val jp = preferences(japanese = true)
        assertFalse(LyricsUtils.shouldRomanizeLyricsLine("東京", jp))
        assertTrue(LyricsUtils.shouldRomanizeLyricsLine("東京", jp, "ja"))
        assertTrue(LyricsUtils.shouldRomanizeLyricsLine("東京", jp, contextText = "東京の空"))
        assertFalse(LyricsUtils.shouldRomanizeLyricsLine("東京", jp, "zh-CN", "東京の空"))
        assertTrue(LyricsUtils.shouldRomanizeLyricsLine("中文\uD840\uDC00", preferences(chinese = true), "zh"))
        assertFalse(LyricsUtils.shouldRomanizeLyricsLine("日本語", preferences()))
    }

    @Test fun koreanNfcNfdUseEquivalentWorkingCopiesWithoutRewritingOriginal() = runBlocking {
        val nfc = "한글"
        val nfd = "한글"
        assertEquals(LyricsUtils.romanizeKorean(nfc), LyricsUtils.romanizeKorean(nfd))
        assertEquals("hangeul", LyricsUtils.romanizeKorean(nfd))
        val entry = LyricsEntry(1234, nfd)
        assertEquals("hangeul", LyricsUtils.phoneticsForEntry(entry, preferences(korean = true)).line)
        assertEquals(nfd, entry.text)
        assertEquals(1234L, entry.time)
    }

    @Test fun providerAlignmentWinsAndLineOnlyReadingDoesNotBecomeFakeWords() = runBlocking {
        val words = listOf(WordTimestamp("東", 1.0, 1.5), WordTimestamp("京", 1.5, 2.0))
        val entry = LyricsEntry(1000, "東京", words, providerRomanizedText = "toukyou",
            providerRomanizedLanguage = "ja", providerRomanizedWords = listOf("tou", "kyou"))
        val aligned = LyricsUtils.phoneticsForEntry(entry, preferences(japanese = true))
        assertEquals("toukyou", aligned.line)
        assertEquals(listOf("tou", "kyou"), aligned.words)
        assertNull(LyricsUtils.providedRomanizedWordsForEntry(entry.copy(providerRomanizedWords = listOf("toukyou")), 2, preferences(japanese = true)))
        val fallback = LyricsUtils.phoneticsForEntry(entry.copy(providerRomanizedWords = null), preferences(japanese = true))
        assertEquals("toukyou", fallback.line)
        assertNull(fallback.words)
        assertEquals(LyricsPhonetics(), LyricsUtils.phoneticsForEntry(entry, preferences()))
        assertEquals(words, entry.words)
        assertEquals(1000L, entry.time)
    }

    @Test fun exactMetadataDoesNotOverblockSiblingLanguagesButUnknownScriptsDo() {
        val cases = listOf(
            Triple("corazón", "es", "SPANISH"), Triple("東京", "ja", "JAPANESE"),
            Triple("中文", "zh", "CHINESE_SIMPLIFIED"), Triple("Україна", "uk", "UKRAINIAN"),
            Triple("Россия", "ru", "RUSSIAN"), Triple("اردو", "ur", "URDU"),
            Triple("فارسی", "fa", "PERSIAN"), Triple("العربية", "ar", "ARABIC"),
            Triple("मराठी", "mr", "MARATHI"), Triple("हिन्दी", "hi", "HINDI"),
        )
        cases.forEach { (text, hint, excluded) ->
            assertFalse(LyricsUtils.isAutomaticLanguageEligible(text, setOf(excluded), listOf(hint)))
        }
        listOf(Triple("東京", "ja", "CHINESE_SIMPLIFIED"), Triple("中文", "zh", "JAPANESE"),
            Triple("Україна", "uk", "RUSSIAN"), Triple("Россия", "ru", "UKRAINIAN"),
            Triple("اردو", "ur", "PERSIAN"), Triple("فارسی", "fa", "ARABIC"),
            Triple("العربية", "ar", "URDU"), Triple("मराठी", "mr", "HINDI"),
            Triple("हिन्दी", "hi", "MARATHI")).forEach { (text, hint, sibling) ->
            assertTrue(LyricsUtils.isAutomaticLanguageEligible(text, setOf(sibling), listOf(hint)))
            assertFalse(LyricsUtils.isAutomaticLanguageEligible(text, setOf(sibling)))
        }
        assertFalse(LyricsUtils.shouldAutoTranslate("corazón", "ENGLISH", setOf("SPANISH"), listOf("es")))
        assertTrue(LyricsUtils.shouldAutoTranslate("corazón", "ENGLISH", setOf("SPANISH"), listOf("not a tag")))
    }

    @Test fun entryMetadataAndChangedHintsAffectEligibilityCacheAndDisplayIdentity() {
        val entry = LyricsEntry(1000, "東京", providerRomanizedLanguage = "ja-JP")
        assertEquals(listOf("JAPANESE"), LyricsUtils.sourceLanguageHints(null, listOf(entry)))
        assertEquals("CHINESE_TRADITIONAL", LyricsUtils.validatedSourceLanguage("zh-Hant"))
        assertNull(LyricsUtils.validatedSourceLanguage("not a tag"))
        val settings = AiLyricsRomanization.Settings(true, true, setOf("JAPANESE"),
            AiServiceConfig(AiProvider.CUSTOM, "fixture", "https://example.invalid", "fixture"))
        assertFalse(AiLyricsRomanization.isEligible(settings, listOf("東京"), listOf("ja")))
        assertTrue(AiLyricsRomanization.isEligible(settings, listOf("東京"), listOf("zh")))
        val ja = AiLyricsRomanization.sessionKey("track", "東京", listOf("ja"))
        val zh = AiLyricsRomanization.sessionKey("track", "東京", listOf("zh"))
        assertNotEquals(ja, zh)
        AiLyricsRomanization.request(ja, listOf("東京"), settings, listOf("ja"))
        assertTrue(AiLyricsRomanization.linesFor(ja, listOf("東京"), listOf("ja")).isEmpty())
        assertTrue(AiLyricsRomanization.linesFor(zh, listOf("東京"), listOf("zh")).isEmpty())
        assertNotEquals(ja, AiLyricsRomanization.sessionKey("next", "東京", listOf("ja")))
    }

    @Test fun sharedSimpMusicFallbackAndRtlPolicyKeepLogicalTextAndSourcePositions() = runBlocking {
        val config = AiServiceConfig(AiProvider.DEEPL, "fixture", "", "")
        val ai = AiLyricsRomanization.Settings(true, true, emptySet(), config)
        assertFalse(AiLyricsRomanization.isEligible(ai, listOf("한")))
        val entry = LyricsEntry(4500, "한", listOf(WordTimestamp("한", 4.5, 5.0)))
        val phonetics = LyricsUtils.phoneticsForEntry(entry, preferences(korean = true,
            ai = AiLyricsRomanization.isEligible(ai, listOf(entry.text))))
        assertEquals("han", phonetics.line)
        assertEquals(4500L, entry.time)
        assertEquals(4.5, entry.words!!.single().startTime, 0.0)
        listOf("می\u200Cروم", "123 العربية Latin", "اردو").forEach { assertTrue(LyricsUtils.isRtlText(it)) }
        listOf("東京", "中文", "Hello world", "Latin العربية").forEach { assertFalse(LyricsUtils.isRtlText(it)) }
        assertEquals("namaste", LyricsUtils.romanizeHindi("नमस्ते"))
    }

    @Test fun unavailableThrowingOrUnchangedIcuKeepsOriginalWithoutDuplicatePhonetics() = runBlocking {
        val original = "العربية می\u200Cروم"
        val unchanged = LyricsUtils.romanizeWithIcu(original) { it }
        val failed = LyricsUtils.romanizeWithIcu(original) { throw IllegalStateException("unavailable") }
        assertEquals(original, unchanged)
        assertEquals(original, failed)
        assertNull(LyricsUtils.normalizeRomanizedText(original, unchanged))
        assertNull(LyricsUtils.normalizeRomanizedText(original, failed))
        assertNull(LyricsUtils.normalizeRomanizedText(original, " "))
        // This proves fallback behavior only, not the quality or availability of device ICU.
    }

    @Test fun offExcludedAndUnconfiguredAiDoNotSuppressEnabledBuiltIns() = runBlocking {
        val base = AiLyricsRomanization.Settings(true, true, emptySet(),
            AiServiceConfig(AiProvider.CUSTOM, "fixture", "https://example.invalid", "fixture"))
        val lines = listOf("한")
        assertTrue(AiLyricsRomanization.isEligible(base, lines))
        listOf(base.copy(enabled = false), base.copy(excludedLanguages = setOf("KOREAN")),
            base.copy(config = base.config.copy(apiKey = "")),
            base.copy(config = base.config.copy(provider = AiProvider.DEEPL))).forEach { settings ->
            assertFalse(AiLyricsRomanization.isEligible(settings, lines))
            val policy = preferences(korean = true, ai = AiLyricsRomanization.isEligible(settings, lines))
            assertEquals("han", LyricsUtils.phoneticsForEntry(LyricsEntry(1000, "한"), policy).line)
        }
    }
}
