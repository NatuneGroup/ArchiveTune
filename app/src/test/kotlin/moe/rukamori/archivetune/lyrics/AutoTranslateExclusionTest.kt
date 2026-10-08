/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards "Don't auto translate these languages" / "Don't romanise these languages".
 *
 * This shipped doing nothing at all: `shouldAutoTranslate` took the exclusion set as a parameter
 * defaulting to `emptySet()`, and both live callers omitted it, so the picker wrote a preference that
 * was then read only by a class nothing ever injected. Selecting Hindi and still getting Hindi
 * translated is [excludedHindiIsNotAutoTranslated], and it is why that default is gone.
 */
class AutoTranslateExclusionTest {
    // Devanagari consonants; the matras are combining marks and don't count as letters, which is
    // fine — the consonants alone make Devanagari the dominant script.
    private val hindi = "मैं तुझको लेकर उड़ जाऊँ"

    // Deliberately all-kana. "君の名は" is mostly Han by character count, so the detector calls it
    // CHINESE — a real limitation of script-based detection, and not what this test is about.
    private val japanese = "きみのなまえはぼくのなまえ"
    private val chinese = "我的心里只有你"
    private val english = "Just a regular english line"

    @Test
    fun detectsScriptsWithTheCodesThePickerUses() {
        assertEquals("HINDI", LyricsUtils.detectDominantLanguageCode(hindi))
        assertEquals("JAPANESE", LyricsUtils.detectDominantLanguageCode(japanese))
        assertEquals("CHINESE", LyricsUtils.detectDominantLanguageCode(chinese))
    }

    @Test
    fun latinLyricsHaveNoDominantScript() {
        assertNull(LyricsUtils.detectDominantLanguageCode(english))
        assertNull(LyricsUtils.detectDominantLanguageCode(""))
    }

    // ── The reported bug ──

    @Test
    fun excludedHindiIsNotAutoTranslated() {
        assertFalse(
            LyricsUtils.shouldAutoTranslate(
                lyrics = hindi,
                targetLanguage = "ENGLISH",
                excludedLanguageCodes = setOf("HINDI"),
            ),
        )
    }

    @Test
    fun hindiIsAutoTranslatedWhenNotExcluded() {
        // The other half of the assertion above: the exclusion is what stops it, not the detector
        // failing to notice Devanagari in the first place.
        assertTrue(
            LyricsUtils.shouldAutoTranslate(
                lyrics = hindi,
                targetLanguage = "ENGLISH",
                excludedLanguageCodes = emptySet(),
            ),
        )
    }

    @Test
    fun excludingOneLanguageDoesNotExcludeAnother() {
        assertTrue(
            LyricsUtils.shouldAutoTranslate(
                lyrics = japanese,
                targetLanguage = "ENGLISH",
                excludedLanguageCodes = setOf("HINDI"),
            ),
        )
    }

    // ── Code-space mismatches ──

    @Test
    fun chineseMatchesEitherPickerVariant() {
        // detectDominantLanguageCode can only ever say "CHINESE" (it sees the Han script), while the
        // picker offers CHINESE_SIMPLIFIED and CHINESE_TRADITIONAL and no plain "CHINESE". A direct
        // set lookup therefore never matched, so Chinese could not be excluded from either feature.
        assertTrue(LyricsUtils.matchesExcludedLanguage("CHINESE", setOf("CHINESE_SIMPLIFIED")))
        assertTrue(LyricsUtils.matchesExcludedLanguage("CHINESE", setOf("CHINESE_TRADITIONAL")))
        assertFalse(
            LyricsUtils.shouldAutoTranslate(
                lyrics = chinese,
                targetLanguage = "ENGLISH",
                excludedLanguageCodes = setOf("CHINESE_TRADITIONAL"),
            ),
        )
    }

    @Test
    fun aliasesDoNotLeakAcrossFamilies() {
        assertFalse(LyricsUtils.matchesExcludedLanguage("JAPANESE", setOf("CHINESE_SIMPLIFIED")))
        assertFalse(LyricsUtils.matchesExcludedLanguage("CHINESE", setOf("JAPANESE")))
    }

    @Test
    fun comparisonIgnoresCaseAndSurroundingSpace() {
        assertTrue(LyricsUtils.matchesExcludedLanguage("HINDI", setOf(" hindi ")))
        assertTrue(LyricsUtils.matchesExcludedLanguage("hindi", setOf("HINDI")))
    }

    @Test
    fun emptyExclusionSetMatchesNothing() {
        assertFalse(LyricsUtils.matchesExcludedLanguage("HINDI", emptySet()))
    }
}
