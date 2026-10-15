/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.lyrics

import android.icu.text.Transliterator
import android.text.format.DateUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.betterlyrics.QRCParser
import moe.rukamori.archivetune.betterlyrics.TTMLParser
import moe.rukamori.archivetune.db.entities.LyricsEntity
import java.lang.Character.UnicodeScript
import java.text.Normalizer
import java.util.Locale
import java.io.StringReader
import org.xmlpull.v1.XmlPullParser
import kotlin.math.roundToLong

data class LyricsRomanizationPreferences(
    val romanizeJapanese: Boolean,
    val romanizeKorean: Boolean,
    val romanizeChinese: Boolean,
    val romanizeHindi: Boolean,
    val romanizeOther: Boolean,
    /** True when an AI provider is supplying romanisation instead (see [AiLyricsRomanization]). */
    val aiHandled: Boolean = false,
) {
    val isEnabled: Boolean
        get() =
            !aiHandled &&
                (romanizeJapanese || romanizeKorean || romanizeChinese || romanizeHindi || romanizeOther)

    /** True when romanisation should be rendered at all, whichever engine produced it. */
    val showsRomanization: Boolean
        get() = aiHandled || isEnabled
}

data class LyricsPhonetics(
    val line: String? = null,
    val words: List<String?>? = null,
)

@Suppress("RegExpRedundantEscape")
object LyricsUtils {
    val LINE_REGEX = Regex("""((\[\d{1,3}:\d{2}(?:[.:]\d{2,3})?\]\s*)+)(.*)""")
    val TIME_REGEX = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{2,3}))?\]""")
    private val WHITESPACE_REGEX = "\\s+".toRegex()
    private val ENHANCED_LRC_WORD_TIME_REGEX = Regex("""<\d{1,3}:\d{2}(?:[.:]\d{2,3})?>""")
    private val ENHANCED_LRC_WORD_TOKEN_REGEX = Regex("""(<\d{1,3}:\d{2}(?:[.:]\d{2,3})?>)([^<]*)""")
    private val INLINE_MILLISECONDS_TIME_REGEX = Regex("""<\d{1,8}(?:,\d{1,8})?>""")
    private val YRC_LINE_REGEX = Regex("""\[(\d{1,8}),\d{1,8}\](.*)""")
    private val YRC_WORD_TIME_REGEX = Regex("""\(\d{1,8},\d{1,8}(?:,\d{1,8})?\)""")
    private val QrcTranslationLineRegex = Regex("""^\[(\d{1,8}),(\d{1,8})](.*)$""")
    private val QrcWordTimingDetectRegex = Regex("""\(\d{1,8},\d{1,8}(?:,\d{1,8})?\)""")
    // Matches the leading timestamp prefix of any LRC/QRC/YRC line. Used by
    // `hasTranslation()` to detect duplicate prefixes (which indicate the
    // translator appended a translated line under the same timestamp).
    private val SyncedLinePrefixRegex = Regex("""^(\s*(?:\[[^\]]+])+)(\s*)(.*?)(\s*)$""")
    private val TTML_SPAN_REGEX =
        Regex(
            pattern = """<span\b[^>]*>""",
            options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
    private val TTML_BEGIN_ATTRIBUTE_REGEX = Regex("""\bbegin\s*=""", RegexOption.IGNORE_CASE)
    private val TTML_END_ATTRIBUTE_REGEX = Regex("""\b(?:end|dur)\s*=""", RegexOption.IGNORE_CASE)
    private const val NBSP = '\u00A0'
    /** End given to the last word of a line whose end no further stamp pins down. */
    private const val ENHANCED_LRC_LAST_WORD_DEFAULT_DURATION_MS = 600L
    /** Floor so a word is never lit for a single frame. */
    private const val MIN_WORD_DURATION_MS = 40L
    /** Assumed length of the final line, which has no following stamp to clamp against. */
    private const val ENHANCED_LRC_TRAILING_LINE_DURATION_MS = 4_000L
    private const val GENERIC_ROMANIZATION_TRANSFORM = "Any-Latin; Latin-ASCII"
    private val OTHER_ROMANIZATION_EXCLUDED_SCRIPTS =
        setOf(
            UnicodeScript.LATIN,
            UnicodeScript.COMMON,
            UnicodeScript.INHERITED,
            UnicodeScript.HAN,
            UnicodeScript.HIRAGANA,
            UnicodeScript.KATAKANA,
            UnicodeScript.HANGUL,
            UnicodeScript.DEVANAGARI,
        )
    private val genericRomanizationTransliterator =
        ThreadLocal.withInitial {
            Transliterator.getInstance(GENERIC_ROMANIZATION_TRANSFORM)
        }

    private val KANA_ROMAJI_MAP: Map<String, String> =
        mapOf(
            // Digraphs (Yōon - combinations like kya, sho)
            "キャ" to "kya",
            "キュ" to "kyu",
            "キョ" to "kyo",
            "シャ" to "sha",
            "シュ" to "shu",
            "ショ" to "sho",
            "チャ" to "cha",
            "チュ" to "chu",
            "チョ" to "cho",
            "ニャ" to "nya",
            "ニュ" to "nyu",
            "ニョ" to "nyo",
            "ヒャ" to "hya",
            "ヒュ" to "hyu",
            "ヒョ" to "hyo",
            "ミャ" to "mya",
            "ミュ" to "myu",
            "ミョ" to "myo",
            "リャ" to "rya",
            "リュ" to "ryu",
            "リョ" to "ryo",
            "ギャ" to "gya",
            "ギュ" to "gyu",
            "ギョ" to "gyo",
            "ジャ" to "ja",
            "ジュ" to "ju",
            "ジョ" to "jo",
            "ヂャ" to "ja",
            "ヂュ" to "ju",
            "ヂョ" to "jo", // ヂ variants, also commonly 'ja', 'ju', 'jo'
            "ビャ" to "bya",
            "ビュ" to "byu",
            "ビョ" to "byo",
            "ピャ" to "pya",
            "ピュ" to "pyu",
            "ピョ" to "pyo",
            // Basic Katakana Characters
            "ア" to "a",
            "イ" to "i",
            "ウ" to "u",
            "エ" to "e",
            "オ" to "o",
            "カ" to "ka",
            "キ" to "ki",
            "ク" to "ku",
            "ケ" to "ke",
            "コ" to "ko",
            "サ" to "sa",
            "シ" to "shi",
            "ス" to "su",
            "セ" to "se",
            "ソ" to "so",
            "タ" to "ta",
            "チ" to "chi",
            "ツ" to "tsu",
            "テ" to "te",
            "ト" to "to",
            "ナ" to "na",
            "ニ" to "ni",
            "ヌ" to "nu",
            "ネ" to "ne",
            "ノ" to "no",
            "ハ" to "ha",
            "ヒ" to "hi",
            "フ" to "fu",
            "ヘ" to "he",
            "ホ" to "ho",
            "マ" to "ma",
            "ミ" to "mi",
            "ム" to "mu",
            "メ" to "me",
            "モ" to "mo",
            "ヤ" to "ya",
            "ユ" to "yu",
            "ヨ" to "yo",
            "ラ" to "ra",
            "リ" to "ri",
            "ル" to "ru",
            "レ" to "re",
            "ロ" to "ro",
            "ワ" to "wa",
            "ヲ" to "o", // ヲ is pronounced 'o'
            "ン" to "n",
            // Dakuten (voiced consonants)
            "ガ" to "ga",
            "ギ" to "gi",
            "グ" to "gu",
            "ゲ" to "ge",
            "ゴ" to "go",
            "ザ" to "za",
            "ジ" to "ji",
            "ズ" to "zu",
            "ゼ" to "ze",
            "ゾ" to "zo",
            "ダ" to "da",
            "ヂ" to "ji",
            "ヅ" to "zu",
            "デ" to "de",
            "ド" to "do", // ヂ and ヅ are often 'ji' and 'zu'
            // Handakuten (p-sounds for 'h' group) / Dakuten for 'h' group
            "バ" to "ba",
            "ビ" to "bi",
            "ブ" to "bu",
            "ベ" to "be",
            "ボ" to "bo", // Dakuten for ハ행 (ha-row)
            "パ" to "pa",
            "ピ" to "pi",
            "プ" to "pu",
            "ペ" to "pe",
            "ポ" to "po", // Handakuten for ハ행 (ha-row)
            // Chōonpu (long vowel mark) - removed as per original logic
            "ー" to "",
        )

    private val HANGUL_ROMAJA_MAP: Map<String, Map<String, String>> =
        mapOf(
            "cho" to
                mapOf(
                    "ᄀ" to "g",
                    "ᄁ" to "kk",
                    "ᄂ" to "n",
                    "ᄃ" to "d",
                    "ᄄ" to "tt",
                    "ᄅ" to "r",
                    "ᄆ" to "m",
                    "ᄇ" to "b",
                    "ᄈ" to "pp",
                    "ᄉ" to "s",
                    "ᄊ" to "ss",
                    "ᄋ" to "",
                    "ᄌ" to "j",
                    "ᄍ" to "jj",
                    "ᄎ" to "ch",
                    "ᄏ" to "k",
                    "ᄐ" to "t",
                    "ᄑ" to "p",
                    "ᄒ" to "h",
                ),
            "jung" to
                mapOf(
                    "ᅡ" to "a",
                    "ᅢ" to "ae",
                    "ᅣ" to "ya",
                    "ᅤ" to "yae",
                    "ᅥ" to "eo",
                    "ᅦ" to "e",
                    "ᅧ" to "yeo",
                    "ᅨ" to "ye",
                    "ᅩ" to "o",
                    "ᅪ" to "wa",
                    "ᅫ" to "wae",
                    "ᅬ" to "oe",
                    "ᅭ" to "yo",
                    "ᅮ" to "u",
                    "ᅯ" to "wo",
                    "ᅰ" to "we",
                    "ᅱ" to "wi",
                    "ᅲ" to "yu",
                    "ᅳ" to "eu",
                    "ᅴ" to "eui",
                    "ᅵ" to "i",
                ),
            "jong" to
                mapOf(
                    "ᆨ" to "k",
                    "ᆨᄋ" to "g",
                    "ᆨᄂ" to "ngn",
                    "ᆨᄅ" to "ngn",
                    "ᆨᄆ" to "ngm",
                    "ᆨᄒ" to "kh",
                    "ᆩ" to "kk",
                    "ᆩᄋ" to "kg",
                    "ᆩᄂ" to "ngn",
                    "ᆩᄅ" to "ngn",
                    "ᆩᄆ" to "ngm",
                    "ᆩᄒ" to "kh",
                    "ᆪ" to "k",
                    "ᆪᄋ" to "ks",
                    "ᆪᄂ" to "ngn",
                    "ᆪᄅ" to "ngn",
                    "ᆪᄆ" to "ngm",
                    "ᆪᄒ" to "kch",
                    "ᆫ" to "n",
                    "ᆫᄅ" to "ll",
                    "ᆬ" to "n",
                    "ᆬᄋ" to "nj",
                    "ᆬᄂ" to "nn",
                    "ᆬᄅ" to "nn",
                    "ᆬᄆ" to "nm",
                    "ᆬㅎ" to "nch",
                    "ᆭ" to "n",
                    "ᆭᄋ" to "nh",
                    "ᆭᄅ" to "nn",
                    "ᆮ" to "t",
                    "ᆮᄋ" to "d",
                    "ᆮᄂ" to "nn",
                    "ᆮᄅ" to "nn",
                    "ᆮᄆ" to "nm",
                    "ᆮᄒ" to "th",
                    "ᆯ" to "l",
                    "ᆯᄋ" to "r",
                    "ᆯᄂ" to "ll",
                    "ᆯᄅ" to "ll",
                    "ᆰ" to "k",
                    "ᆰᄋ" to "lg",
                    "ᆰᄂ" to "ngn",
                    "ᆰᄅ" to "ngn",
                    "ᆰᄆ" to "ngm",
                    "ᆰᄒ" to "lkh",
                    "ᆱ" to "m",
                    "ᆱᄋ" to "lm",
                    "ᆱᄂ" to "mn",
                    "ᆱᄅ" to "mn",
                    "ᆱᄆ" to "mm",
                    "ᆱᄒ" to "lmh",
                    "ᆲ" to "p",
                    "ᆲᄋ" to "lb",
                    "ᆲᄂ" to "mn",
                    "ᆲᄅ" to "mn",
                    "ᆲᄆ" to "mm",
                    "ᆲᄒ" to "lph",
                    "ᆳ" to "t",
                    "ᆳᄋ" to "ls",
                    "ᆳᄂ" to "nn",
                    "ᆳᄅ" to "nn",
                    "ᆳᄆ" to "nm",
                    "ᆳᄒ" to "lsh",
                    "ᆴ" to "t",
                    "ᆴᄋ" to "lt",
                    "ᆴᄂ" to "nn",
                    "ᆴᄅ" to "nn",
                    "ᆴᄆ" to "nm",
                    "ᆴᄒ" to "lth",
                    "ᆵ" to "p",
                    "ᆵᄋ" to "lp",
                    "ᆵᄂ" to "mn",
                    "ᆵᄅ" to "mn",
                    "ᆵᄆ" to "mm",
                    "ᆵᄒ" to "lph",
                    "ᆶ" to "l",
                    "ᆶᄋ" to "lh",
                    "ᆶᄂ" to "ll",
                    "ᆶᄅ" to "ll",
                    "ᆶᄆ" to "lm",
                    "ᆶᄒ" to "lh",
                    "ᆷ" to "m",
                    "ᆷᄅ" to "mn",
                    "ᆸ" to "p",
                    "ᆸᄋ" to "b",
                    "ᆸᄂ" to "mn",
                    "ᆸᄅ" to "mn",
                    "ᆸᄆ" to "mm",
                    "ᆸᄒ" to "ph",
                    "ᆹ" to "p",
                    "ᆹᄋ" to "ps",
                    "ᆹᄂ" to "mn",
                    "ᆹᄅ" to "mn",
                    "ᆹᄆ" to "mm",
                    "ᆹᄒ" to "psh",
                    "ᆺ" to "t",
                    "ᆺᄋ" to "s",
                    "ᆺᄂ" to "nn",
                    "ᆺᄅ" to "nn",
                    "ᆺᄆ" to "nm",
                    "ᆺᄒ" to "sh",
                    "ᆻ" to "t",
                    "ᆻᄋ" to "ss",
                    "ᆻᄂ" to "tn",
                    "ᆻᄅ" to "tn",
                    "ᆻᄆ" to "nm",
                    "ᆻᄒ" to "th",
                    "ᆼ" to "ng",
                    "ᆽ" to "t",
                    "ᆽᄋ" to "j",
                    "ᆽᄂ" to "nn",
                    "ᆽᄅ" to "nn",
                    "ᆽᄆ" to "nm",
                    "ᆽᄒ" to "ch",
                    "ᆾ" to "t",
                    "ᆾᄋ" to "ch",
                    "ᆾᄂ" to "nn",
                    "ᆾᄅ" to "nn",
                    "ᆾᄆ" to "nm",
                    "ᆾᄒ" to "ch",
                    "ᆿ" to "k",
                    "ᆿᄋ" to "k",
                    "ᆿᄂ" to "ngn",
                    "ᆿᄅ" to "ngn",
                    "ᆿᄆ" to "ngm",
                    "ᆿᄒ" to "kh",
                    "ᇀ" to "t",
                    "ᇀᄋ" to "t",
                    "ᇀᄂ" to "nn",
                    "ᇀᄅ" to "nn",
                    "ᇀᄆ" to "nm",
                    "ᇀᄒ" to "th",
                    "ᇁ" to "p",
                    "ᇁᄋ" to "p",
                    "ᇁᄂ" to "mn",
                    "ᇁᄅ" to "mn",
                    "ᇁᄆ" to "mm",
                    "ᇁᄒ" to "ph",
                    "ᇂ" to "t",
                    "ᇂᄋ" to "h",
                    "ᇂᄂ" to "nn",
                    "ᇂᄅ" to "nn",
                    "ᇂᄆ" to "mm",
                    "ᇂᄒ" to "t",
                    "ᇂᄀ" to "k",
                ),
        )

    /**
     * Whether [text] should lay out right-to-left.
     *
     * Decided by the first character with a strong direction — an Arabic line that opens with a
     * quote mark or a digit is still an Arabic line, and those carry no direction of their own.
     */
    fun isRtlText(text: String): Boolean {
        for (codePoint in text.codePoints().toArray()) {
            when (Character.getDirectionality(codePoint)) {
                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE,
                -> return true

                Character.DIRECTIONALITY_LEFT_TO_RIGHT,
                Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING,
                Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE,
                -> return false
            }
        }
        return false
    }

    fun isTtml(lyrics: String): Boolean {
        val trimmed = normalizeLyricsText(lyrics)
        if (!trimmed.startsWith("<")) return false

        return trimmed.contains("<tt", ignoreCase = true) ||
            trimmed.contains("http://www.w3.org/ns/ttml", ignoreCase = true)
    }

    /**
     * Returns true when [lyrics] contains at least one actual translation entry produced by
     * [moe.rukamori.archivetune.ai.AiLyricsTranslator].
     */
    fun hasTranslation(lyrics: String): Boolean {
        if (lyrics.isBlank()) return false
        if (isTtml(lyrics)) {
            return lyrics.contains("data-archivetune", ignoreCase = true) &&
                lyrics.contains("translation", ignoreCase = true)
        }
        val seenPrefixes = HashSet<String>()
        lyrics.lineSequence().forEach { line ->
            val match = SyncedLinePrefixRegex.matchEntire(line) ?: return@forEach
            val prefix = match.groupValues[1]
            if (!seenPrefixes.add(prefix)) return true
        }
        return false
    }

    /**
     * Conservatively applies exclusions to the script family returned by [detectDominantLanguageCode].
     * Without source-language metadata, Urdu/Persian/Arabic and Cyrillic/Devanagari siblings cannot
     * be distinguished. Excluding one therefore excludes its ambiguous siblings too, not just the
     * legacy language label used for that script. Latin-language identification remains unknown.
     */
    fun matchesExcludedLanguage(
        dominantCode: String,
        excludedLanguageCodes: Set<String>,
    ): Boolean {
        if (excludedLanguageCodes.isEmpty()) return false
        val dominant = dominantCode.trim().uppercase()
        if (dominant.isEmpty()) return false
        val normalized = excludedLanguageCodes.mapTo(HashSet()) { it.trim().uppercase() }
        if (dominant in normalized) return true
        return EXCLUSION_ALIASES[dominant]?.any { it in normalized } == true
    }

    /**
     * Picker codes that should satisfy an exclusion for a detected script that has no exact code of
     * its own. See [matchesExcludedLanguage].
     */
    private val EXCLUSION_ALIASES: Map<String, List<String>> =
        mapOf(
            // Han without kana is ambiguous, including kanji-only Japanese.
            "CHINESE" to listOf("CHINESE_SIMPLIFIED", "CHINESE_TRADITIONAL", "JAPANESE"),
            "ARABIC" to listOf("PERSIAN", "URDU", "PASHTO", "SINDHI", "UYGHUR"),
            "RUSSIAN" to listOf("UKRAINIAN", "BELARUSIAN", "BULGARIAN", "SERBIAN", "MACEDONIAN", "KAZAKH", "KYRGYZ", "MONGOLIAN", "TAJIK"),
            "HINDI" to listOf("MARATHI", "NEPALI", "SANSKRIT"),
            "HEBREW" to listOf("YIDDISH"),
        )

    // Use the same English Locale names as the existing translation picker, not a text detector.
    private val sourceLanguageCodes: Map<String, String> by lazy {
        val pickerNames = mapOf(
            "ug" to "UIGHUR", "fy" to "FRISIAN", "gd" to "SCOTS_GAELIC",
            "nb" to "NORWEGIAN", "no" to "NORWEGIAN", "or" to "ODIA_ORIYA",
            "ny" to "NYANJA_CHICHEWA", "ht" to "HAITIAN_CREOLE", "ku" to "KURDISH_KURMANJI",
        )
        buildMap {
            for (code in Locale.getISOLanguages()) {
                val locale = Locale.forLanguageTag(code)
                val name = pickerNames[code] ?: locale.getDisplayLanguage(Locale.ENGLISH)
                    .uppercase(Locale.ROOT).replace(' ', '_')
                put(code, name)
                runCatching { locale.getISO3Language() }.getOrNull()?.let { put(it, name) }
            }
            put("fil", "FILIPINO")
            put("ceb", "CEBUANO")
            put("haw", "HAWAIIAN")
            put("hmn", "HMONG")
        }
    }

    fun validatedSourceLanguage(hint: String): String? {
        val normalized = hint.trim().replace('_', '-')
        val label = normalized.uppercase(Locale.ROOT)
        if (label == "UYGHUR") return "UIGHUR"
        if (label in setOf("CHINESE-SIMPLIFIED", "ZH-HANS", "ZH-CN", "ZH-SG")) return "CHINESE_SIMPLIFIED"
        if (label in setOf("CHINESE-TRADITIONAL", "ZH-HANT", "ZH-TW", "ZH-HK", "ZH-MO")) return "CHINESE_TRADITIONAL"
        sourceLanguageCodes.values.firstOrNull { it.replace('_', '-') == label }?.let { return it }
        val locale = runCatching { Locale.Builder().setLanguageTag(normalized).build() }.getOrNull() ?: return null
        if (locale.language == "zh") {
            return when (locale.script) {
                "Hans" -> "CHINESE_SIMPLIFIED"
                "Hant" -> "CHINESE_TRADITIONAL"
                else -> "CHINESE"
            }
        }
        return sourceLanguageCodes[locale.language]
    }

    /** Whole-document requests respect every known source hint, including mixed-language entries. */
    fun sourceLanguageHints(lyrics: String?, entries: List<LyricsEntry>? = null): List<String> {
        val text = lyrics?.let(::normalizeLyricsText).orEmpty()
        val hints = mutableSetOf<String>()
        if (isTtml(text)) {
            val documentHint = runCatching {
                val parser = android.util.Xml.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                parser.setInput(StringReader(text))
                if (parser.nextTag() == XmlPullParser.START_TAG && parser.name == "tt") {
                    parser.getAttributeValue("http://www.w3.org/XML/1998/namespace", "lang")
                } else null
            }.getOrNull()?.let(::validatedSourceLanguage)
            if (documentHint != null) hints.add(documentHint)
        }
        val parsed = entries ?: if (isTtml(text)) runCatching { parseTtml(text) }.getOrDefault(emptyList()) else emptyList()
        parsed.mapNotNullTo(hints) { it.providerRomanizedLanguage?.let(::validatedSourceLanguage) }
        return hints.sorted()
    }

    /** Exact known metadata is not expanded to ambiguous script siblings. */
    fun isAutomaticLanguageEligible(
        text: String,
        excludedLanguageCodes: Set<String>,
        sourceLanguageHints: List<String> = emptyList(),
    ): Boolean {
        val exact = sourceLanguageHints.mapNotNull(::validatedSourceLanguage).distinct()
        if (exact.isNotEmpty()) {
            val excluded = excludedLanguageCodes.mapNotNull(::validatedSourceLanguage).toSet()
            return exact.none { code ->
                code in excluded ||
                    (code.startsWith("CHINESE") && "CHINESE" in excluded) ||
                    (code == "CHINESE" && excluded.any { it.startsWith("CHINESE") })
            }
        }
        val dominant = detectDominantLanguageCode(text)
        return dominant == null || !matchesExcludedLanguage(dominant, excludedLanguageCodes)
    }

    /** True when [lyrics] should be sent for automatic AI translation into [targetLanguage]. */
    fun shouldAutoTranslate(
        lyrics: String,
        targetLanguage: String,
        excludedLanguageCodes: Set<String>,
        sourceLanguageHints: List<String> = LyricsUtils.sourceLanguageHints(lyrics),
    ): Boolean {
        if (lyrics.isBlank()) return false
        if (!isAutomaticLanguageEligible(lyrics, excludedLanguageCodes, sourceLanguageHints)) {
            return false
        }
        val allowedScripts = allowedScriptsForLanguage(targetLanguage)
        // When the target is English (or unset, which defaults to English), we also
        // trigger on non-ASCII Latin letters (á, é, í, ó, ú, ñ, ü, ç, etc.) so that
        // Spanish/French/German/Portuguese/Italian lyrics auto-translate to English.
        // Without this, Latin-to-English pairs never fire because both share the
        // Latin script. For non-English Latin targets (e.g. Spanish→French), we
        // skip this heuristic to avoid wasteful same-script translations.
        val targetIsEnglish = isEnglishTarget(targetLanguage)
        return lyrics.asSequence().any { char ->
            if (!char.isLetter()) return@any false
            val script = UnicodeScript.of(char.code)
            script !in allowedScripts ||
                (targetIsEnglish && script == UnicodeScript.LATIN && char.code > 127)
        }
    }

    private fun isEnglishTarget(language: String): Boolean {
        val normalized = language.trim().lowercase().replace('_', '-').substringBefore('-')
        return normalized.isEmpty() || normalized == "english" || normalized == "en"
    }

    /**
     * Returns a legacy uppercase label for the dominant non-Latin script family, not an exact
     * language detector. Kana supplies Japanese context for Han; Han alone remains ambiguous.
     * Latin-only or unrecognized scripts return null rather than an invented language identity.
     */
    fun detectDominantLanguageCode(lyrics: String): String? {
        if (lyrics.isBlank()) return null
        // Tally non-Latin scripts found in the lyrics. Latin/Common/Inherited are ignored.
        val scriptCounts = HashMap<UnicodeScript, Int>()
        for (codePoint in lyrics.codePoints().toArray()) {
            if (!Character.isLetter(codePoint)) continue
            val script = UnicodeScript.of(codePoint)
            when (script) {
                UnicodeScript.LATIN,
                UnicodeScript.COMMON,
                UnicodeScript.INHERITED -> Unit
                else -> scriptCounts[script] = (scriptCounts[script] ?: 0) + 1
            }
        }
        if (scriptCounts.isEmpty()) return null
        val dominantScript = scriptCounts.maxByOrNull { it.value }!!.key
        return when (dominantScript) {
            UnicodeScript.HAN -> if ((scriptCounts[UnicodeScript.HIRAGANA] ?: 0) +
                (scriptCounts[UnicodeScript.KATAKANA] ?: 0) > 0
            ) "JAPANESE" else "CHINESE"
            UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA -> "JAPANESE"
            UnicodeScript.HANGUL -> "KOREAN"
            UnicodeScript.DEVANAGARI -> "HINDI"
            UnicodeScript.ARABIC -> "ARABIC"
            UnicodeScript.CYRILLIC -> "RUSSIAN"
            UnicodeScript.THAI -> "THAI"
            UnicodeScript.HEBREW -> "HEBREW"
            UnicodeScript.GREEK -> "GREEK"
            UnicodeScript.ARMENIAN -> "ARMENIAN"
            UnicodeScript.GEORGIAN -> "GEORGIAN"
            else -> null
        }
    }

    private fun allowedScriptsForLanguage(language: String): Set<UnicodeScript> {
        val normalized = language.trim().lowercase().replace('_', '-').substringBefore('-')
        val code = when (normalized) {
            "english", "en" -> "en"
            "japanese", "ja" -> "ja"
            "korean", "ko" -> "ko"
            "chinese", "zh", "mandarin", "cmn", "cantonese", "yue" -> "zh"
            "hindi", "hi", "sanskrit", "sa", "marathi", "mr", "nepali", "ne" -> "hi"
            "arabic", "ar", "persian", "fa", "urdu", "ur" -> "ar"
            "russian", "ru", "ukrainian", "uk", "belarusian", "be", "bulgarian", "bg" -> "ru"
            "thai", "th" -> "th"
            "hebrew", "he", "yiddish", "yi" -> "he"
            "greek", "el" -> "el"
            "armenian", "hy" -> "hy"
            "georgian", "ka" -> "ka"
            else -> normalized
        }
        return when (code) {
            "ja" -> setOf(
                UnicodeScript.LATIN,
                UnicodeScript.COMMON,
                UnicodeScript.INHERITED,
                UnicodeScript.HAN,
                UnicodeScript.HIRAGANA,
                UnicodeScript.KATAKANA,
            )
            "ko" -> setOf(
                UnicodeScript.LATIN,
                UnicodeScript.COMMON,
                UnicodeScript.INHERITED,
                UnicodeScript.HANGUL,
            )
            "zh" -> setOf(
                UnicodeScript.LATIN,
                UnicodeScript.COMMON,
                UnicodeScript.INHERITED,
                UnicodeScript.HAN,
            )
            "hi" -> setOf(
                UnicodeScript.LATIN,
                UnicodeScript.COMMON,
                UnicodeScript.INHERITED,
                UnicodeScript.DEVANAGARI,
            )
            "ar" -> setOf(
                UnicodeScript.LATIN,
                UnicodeScript.COMMON,
                UnicodeScript.INHERITED,
                UnicodeScript.ARABIC,
            )
            "ru" -> setOf(
                UnicodeScript.LATIN,
                UnicodeScript.COMMON,
                UnicodeScript.INHERITED,
                UnicodeScript.CYRILLIC,
            )
            "th" -> setOf(
                UnicodeScript.LATIN,
                UnicodeScript.COMMON,
                UnicodeScript.INHERITED,
                UnicodeScript.THAI,
            )
            "he" -> setOf(
                UnicodeScript.LATIN,
                UnicodeScript.COMMON,
                UnicodeScript.INHERITED,
                UnicodeScript.HEBREW,
            )
            "el" -> setOf(
                UnicodeScript.LATIN,
                UnicodeScript.COMMON,
                UnicodeScript.INHERITED,
                UnicodeScript.GREEK,
            )
            "hy" -> setOf(
                UnicodeScript.LATIN,
                UnicodeScript.COMMON,
                UnicodeScript.INHERITED,
                UnicodeScript.ARMENIAN,
            )
            "ka" -> setOf(
                UnicodeScript.LATIN,
                UnicodeScript.COMMON,
                UnicodeScript.INHERITED,
                UnicodeScript.GEORGIAN,
            )
            else -> setOf(
                UnicodeScript.LATIN,
                UnicodeScript.COMMON,
                UnicodeScript.INHERITED,
            )
        }
    }

    fun isLineSyncedLrc(lyrics: String): Boolean =
        QRCParser.isQrc(normalizeLyricsText(lyrics)) ||
            lyrics.lineSequence().any { line ->
            val trimmedLine = line.trim()
            LINE_REGEX.matches(trimmedLine) || YRC_LINE_REGEX.matches(trimmedLine)
        }

    fun hasWordSyncedLyrics(lyrics: String): Boolean {
        val normalized = normalizeLyricsText(lyrics)
        if (QRCParser.isQrc(normalized)) return QRCParser.hasWordTimings(normalized)
        if (isTtml(normalized)) {
            return TTML_SPAN_REGEX.findAll(normalized).any { match ->
                TTML_BEGIN_ATTRIBUTE_REGEX.containsMatchIn(match.value) &&
                    TTML_END_ATTRIBUTE_REGEX.containsMatchIn(match.value)
            }
        }
        // Enhanced LRC: lines like "[00:01.234]<00:01.500>Hello <00:01.700>world"
        // where each word has its own <mm:ss.xxx> inline timestamp. This format is
        // produced by YouLyPlus's v2/lyrics/get fallback endpoint (when v1/ttml/get
        // is unavailable) and by some other providers. Without this check, those
        // word-synced lyrics would be misclassified as plain line-synced LRC and
        // the "Prioritize Word Synced Lyrics" feature would skip them.
        //
        // We require BOTH a line-level [mm:ss.xxx] tag AND an inline <mm:ss.xxx>
        // word timestamp on the same line, so that plain text containing angle
        // brackets (e.g. "<3") doesn't false-positive.
        return normalized.lineSequence().any { line ->
            LINE_REGEX.containsMatchIn(line) &&
                (ENHANCED_LRC_WORD_TIME_REGEX.containsMatchIn(line) ||
                    INLINE_MILLISECONDS_TIME_REGEX.containsMatchIn(line))
        }
    }

    /**
     * True when a *stored* lyrics row is something the word-synced toggle can still improve:
     * the reader asked for word-synced timing and the row holds real text without word-level
     * timing.
     *
     * This exists because the two fetch gates cannot see the helper's decision. [LyricsHelper]
     * already treats its own cached non-word-synced entry as stale under this toggle, but both
     * callers short-circuit on a stored row *before* the helper is asked — MusicService's
     * auto-fetch only for a null or LYRICS_NOT_FOUND row, LyricsScreen only for a row with no
     * lyrics, no provider name or the sentinel. So a song that had been played once kept its
     * line-synced text forever and turning the toggle on changed nothing for it, which is
     * precisely the case the toggle is meant to serve.
     *
     * Only a row this app fetched for itself is eligible. A USER_EDIT is the reader's own
     * text, an AI_TRANSLATION is one they spent a request on, and a USER_SELECTION is a result
     * they picked by hand — the toggle asks for better automatic lyrics, not permission to
     * discard any of those, and the table's other writers preserve them deliberately. EMBEDDED
     * is left out because the local scanner owns re-reading a file's own tags, not a provider.
     *
     * The sentinel and blanks are excluded rather than merely handled here: both gates already
     * retry them, so this need only be the *new* reason to fetch.
     */
    fun needsWordSyncedUpgrade(
        prioritizeWordSynced: Boolean,
        stored: LyricsEntity?,
    ): Boolean =
        prioritizeWordSynced &&
            stored != null &&
            stored.source == LyricsEntity.Source.REMOTE.value &&
            stored.lyrics != LyricsEntity.LYRICS_NOT_FOUND &&
            stored.lyrics.isNotBlank() &&
            !hasWordSyncedLyrics(stored.lyrics)

    fun parseTtml(
        lyrics: String,
        durationSeconds: Int? = null,
    ): List<LyricsEntry> {
        val parsedLines = TTMLParser.parseTTML(normalizeLyricsText(lyrics))
        if (parsedLines.isEmpty()) return emptyList()
        val scale = 1.0

        return parsedLines
            .map { line ->
                val words =
                    line.words
                        .filter { it.text.isNotEmpty() }
                        .map { word ->
                            WordTimestamp(
                                text = word.text,
                                startTime = word.startTime * scale,
                                endTime = word.endTime * scale,
                                isBackground = word.isBackground,
                            )
                        }.takeIf { it.isNotEmpty() }

                LyricsEntry(
                    time = (line.startTime * scale * 1000.0).toLong(),
                    text = line.text,
                    words = words,
                    agent = line.agent,
                    providerRomanizedText = line.providerRomanizedText,
                    providerRomanizedWords = line.providerRomanizedWords,
                    providerRomanizedLanguage = line.providerRomanizedLanguage,
                    providerTranslationText = line.providerTranslationText,
                )
            }.sorted()
    }

    fun parseLyrics(lyrics: String): List<LyricsEntry> {
        val normalizedLyrics = normalizeLyricsText(lyrics)
        if (QRCParser.isQrc(normalizedLyrics)) {
            val translationsByStartMs = extractQrcTranslations(normalizedLyrics)
            return QRCParser.parseQrc(normalizedLyrics).map { line ->
                val startMs = (line.startTime * 1000.0).toLong()
                LyricsEntry(
                    time = startMs,
                    text = line.text,
                    words =
                        line.words
                            .map { word ->
                                WordTimestamp(
                                    text = word.text,
                                    startTime = word.startTime,
                                    endTime = word.endTime,
                                )
                            }.takeIf { it.isNotEmpty() },
                    agent = line.agent,
                    providerTranslationText = translationsByStartMs[startMs],
                    durationMs = ((line.endTime - line.startTime) * 1000.0).toLong().coerceAtLeast(0L),
                )
            }
        }

        val lines = normalizedLyrics.lines()
        val result = mutableListOf<LyricsEntry>()

        for (line in lines) {
            val entries = parseLineSyncedLrcLine(line) ?: parseMillisecondsSyncedLine(line)
            if (entries != null) {
                result.addAll(entries)
            }
        }
        return clampEnhancedLrcLastWordEnds(mergeLineSyncedTranslations(result).sorted())
    }

    /**
     * Pulls the end of each line's last word back to the start of the next line.
     *
     * Enhanced LRC timestamps only mark word STARTs, so the last word carries the
     * [ENHANCED_LRC_LAST_WORD_DEFAULT_DURATION_MS] guess, which can run past the line it belongs to and keep
     * the karaoke highlight on a word while the next line is already singing. The final line has no
     * following stamp to clamp against, so it keeps a generous fixed tail.
     *
     * Only guessed tails are touched. A word end from any other producer is real, and a line
     * legitimately runs past the next one there (duets, instrumental tails), so clamping those would
     * cut a line short; [hasGuessedLastWordEnd] is what tells the two apart.
     */
    private fun clampEnhancedLrcLastWordEnds(entries: List<LyricsEntry>): List<LyricsEntry> =
        entries.mapIndexed { index, entry ->
            val words = entry.words ?: return@mapIndexed entry
            if (words.isEmpty() || !hasGuessedLastWordEnd(words)) return@mapIndexed entry
            val nextStartMs =
                entries
                    .getOrNull(index + 1)
                    ?.takeIf { it.time > entry.time }
                    ?.time
                    ?: (entry.time + ENHANCED_LRC_TRAILING_LINE_DURATION_MS)
            val lastEndMs = (words.last().endTime * 1000.0).toLong()
            if (lastEndMs > nextStartMs) {
                val clamped = words.last().copy(endTime = nextStartMs / 1000.0)
                entry.copy(words = words.dropLast(1) + clamped)
            } else {
                entry
            }
        }

    /**
     * True when [words] ends in the tail [extractEnhancedLrcWordTimestamps] invents for a last word.
     * That is the extractor's signature — the duration is not read from anywhere — so it doubles as
     * the provenance check that keeps the clamp off real word timings.
     */
    private fun hasGuessedLastWordEnd(words: List<WordTimestamp>): Boolean {
        val lastWord = words.last()
        val startMs = (lastWord.startTime * 1000.0).roundToLong()
        val guessedDurationMs =
            ENHANCED_LRC_LAST_WORD_DEFAULT_DURATION_MS.coerceAtLeast(MIN_WORD_DURATION_MS)
        return (lastWord.endTime * 1000.0).roundToLong() - startMs == guessedDurationMs
    }

    private fun extractQrcTranslations(lyrics: String): Map<Long, String> {
        val wordTimedStartMs = mutableSetOf<Long>()
        val translationCandidates = mutableListOf<Pair<Long, String>>()
        lyrics.lineSequence().forEach { line ->
            val trimmed = line.trim()
            val match = QrcTranslationLineRegex.matchEntire(trimmed) ?: return@forEach
            val startMs = match.groupValues[1].toLongOrNull() ?: return@forEach
            val content = match.groupValues[3]
            if (QrcWordTimingDetectRegex.containsMatchIn(content)) {
                wordTimedStartMs.add(startMs)
            } else if (content.isNotBlank()) {
                translationCandidates.add(startMs to content.trim())
            }
        }
        val translations = mutableMapOf<Long, String>()
        translationCandidates.forEach { (startMs, text) ->
            if (startMs in wordTimedStartMs && startMs !in translations) {
                translations[startMs] = text
            }
        }
        return translations
    }

    fun normalizeLyricsText(lyrics: String): String {
        val raw =
            lyrics
                .removePrefix("\uFEFF")
                .trim { it.isWhitespace() || it == NBSP }

        val unwrapped = unwrapJsonEnvelope(stripCodeFence(raw))
        val normalized =
            if (isEscapedTtml(unwrapped)) {
                unwrapped
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace("&#39;", "'")
                    .replace("&apos;", "'")
            } else {
                unwrapped
            }

        return normalized.trim { it.isWhitespace() || it == NBSP }
    }

    fun displayLyricsText(lyrics: String): String {
        val raw = normalizeLyricsText(lyrics)
        if (raw.isEmpty() || raw == LyricsEntity.LYRICS_NOT_FOUND) return ""

        val visibleLines =
            when {
                isTtml(raw) -> runCatching { parseTtml(raw).map { it.text } }.getOrElse { emptyList() }
                isLineSyncedLrc(raw) -> runCatching { parseLyrics(raw).map { it.text } }.getOrElse { emptyList() }
                raw.startsWith("<") -> emptyList()
                else -> raw.lines().map(::cleanInlineWordTimingText)
            }

        return visibleLines
            .map { line ->
                line
                    .replace(WHITESPACE_REGEX, " ")
                    .trim { it.isWhitespace() || it == NBSP }
            }.filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    fun hasMeaningfulLyricsContent(lyrics: String): Boolean = displayLyricsText(lyrics).isNotEmpty()

    fun lyricsOrNotFound(lyrics: String): String {
        val normalized = normalizeLyricsText(lyrics)
        return normalized.takeIf(::hasMeaningfulLyricsContent) ?: LyricsEntity.LYRICS_NOT_FOUND
    }

    // Older caches can hold a provider's JSON envelope verbatim; unwrap it so the TTML inside renders.
    private fun unwrapJsonEnvelope(value: String): String {
        val first = value.firstOrNull() ?: return value
        if (first != '{' && first != '[') return value
        // LRC text also starts with '[', so only a document that actually parses as JSON is an envelope.
        if (LyricsProviderHttp.parseJson(value) == null) return value
        val inner = runCatching { LyricsPayload.unwrap(value) }.getOrNull() ?: return value
        if (inner.isBlank()) return value
        return inner.trim { it.isWhitespace() || it == NBSP }
    }

    private fun stripCodeFence(lyrics: String): String {
        if (!lyrics.startsWith("```")) return lyrics

        val lines = lyrics.lines()
        if (lines.size <= 1) return lyrics

        val bodyLines =
            lines.drop(1).let { remainingLines ->
                if (remainingLines.lastOrNull()?.trim() == "```") {
                    remainingLines.dropLast(1)
                } else {
                    remainingLines
                }
            }

        return bodyLines.joinToString("\n").trim { it.isWhitespace() || it == NBSP }
    }

    private fun isEscapedTtml(lyrics: String): Boolean {
        val trimmed = lyrics.trimStart()
        return trimmed.startsWith("&lt;tt", ignoreCase = true) ||
            trimmed.contains("&lt;tt", ignoreCase = true) ||
            trimmed.contains("http://www.w3.org/ns/ttml", ignoreCase = true) &&
            trimmed.contains("&lt;", ignoreCase = true)
    }

    fun insertInstrumentalBreaks(
        entries: List<LyricsEntry>,
        songDurationMs: Long = 0L,
    ): List<LyricsEntry> {
        if (entries.isEmpty()) return entries
        val result = mutableListOf<LyricsEntry>()
        insertIntroInstrumentalIfNeeded(entries, result)
        result.addAll(entries)
        insertOutroInstrumentalIfNeeded(entries, songDurationMs, result)
        return result
    }

    private const val INSTRUMENTAL_GAP_THRESHOLD_MS = 5000L
    private const val INSTRUMENTAL_INTRO_START_MS = 1000L
    private const val INSTRUMENTAL_OUTRO_VOCAL_TAIL_MS = 2500L

    private fun insertIntroInstrumentalIfNeeded(
        entries: List<LyricsEntry>,
        result: MutableList<LyricsEntry>,
    ) {
        val firstTimedVocalEntry = entries.firstOrNull { it.time >= 0L && it.text.isNotBlank() } ?: return
        val introGapMs = firstTimedVocalEntry.time - INSTRUMENTAL_INTRO_START_MS
        if (introGapMs < INSTRUMENTAL_GAP_THRESHOLD_MS) return

        result.add(
            LyricsEntry(
                time = INSTRUMENTAL_INTRO_START_MS,
                text = "",
                isInstrumental = true,
                durationMs = introGapMs,
            ),
        )
    }

    private fun insertOutroInstrumentalIfNeeded(
        entries: List<LyricsEntry>,
        songDurationMs: Long,
        result: MutableList<LyricsEntry>,
    ) {
        if (songDurationMs <= 0L) return
        val lastVocalEntry = entries.lastOrNull { it.text.isNotBlank() } ?: return
        val outroStartMs = lastVocalEntry.time + INSTRUMENTAL_OUTRO_VOCAL_TAIL_MS
        val outroDurationMs = songDurationMs - outroStartMs
        if (outroDurationMs < INSTRUMENTAL_GAP_THRESHOLD_MS) return

        result.add(
            LyricsEntry(
                time = outroStartMs,
                text = "",
                isInstrumental = true,
                durationMs = outroDurationMs,
            ),
        )
    }

    private fun parseLineSyncedLrcLine(line: String): List<LyricsEntry>? {
        if (line.isEmpty()) {
            return null
        }
        val matchResult = LINE_REGEX.matchEntire(line.trim()) ?: return null
        val times = matchResult.groupValues[1]
        val rawText = matchResult.groupValues[3]
        val text = cleanInlineWordTimingText(rawText)
        val inlineWords = extractEnhancedLrcWordTimestamps(rawText)
        val timeMatchResults = TIME_REGEX.findAll(times)

        return timeMatchResults
            .map { timeMatchResult ->
                val min = timeMatchResult.groupValues[1].toLong()
                val sec = timeMatchResult.groupValues[2].toLong()
                val milString = timeMatchResult.groupValues[3]
                var mil = milString.toLongOrNull() ?: 0L
                when (milString.length) {
                    1 -> mil *= 100
                    2 -> mil *= 10
                }
                val time = min * DateUtils.MINUTE_IN_MILLIS + sec * DateUtils.SECOND_IN_MILLIS + mil
                if (inlineWords != null) {
                    // A repeated stamp ("[00:12.00][00:14.00]<00:12.00>word …") reuses the same
                    // words: any word stamped before this row's own start is pulled forward so the
                    // karaoke never highlights a word before its line begins.
                    val lineStartSec = time / 1000.0
                    val words =
                        inlineWords.map { word ->
                            if (word.startTime >= lineStartSec - 0.001) {
                                word
                            } else {
                                word.copy(
                                    startTime = lineStartSec,
                                    endTime = maxOf(word.endTime, lineStartSec + MIN_WORD_DURATION_MS / 1000.0),
                                )
                            }
                        }
                    LyricsEntry(time, text, words = words)
                } else {
                    LyricsEntry(time, text)
                }
            }.toList()
    }

    /**
     * Parses the `<mm:ss.xxx>` fraction of an enhanced-LRC word stamp into milliseconds, or null
     * when the stamp is malformed.
     */
    private fun parseEnhancedLrcStampMs(stamp: String): Long? {
        val body = stamp.removePrefix("<").removeSuffix(">")
        val parts = body.split(':', '.')
        if (parts.size < 2) return null
        val min = parts[0].toLongOrNull() ?: return null
        val sec = parts[1].toLongOrNull() ?: return null
        val mil =
            parts.getOrNull(2)?.let { fraction ->
                when (fraction.length) {
                    1 -> fraction.toLongOrNull()?.times(100)
                    2 -> fraction.toLongOrNull()?.times(10)
                    else -> fraction.take(3).padEnd(3, '0').toLongOrNull()
                }
            } ?: 0L
        return min * DateUtils.MINUTE_IN_MILLIS + sec * DateUtils.SECOND_IN_MILLIS + mil
    }

    /**
     * Extracts per-word timings from a line carrying inline `<mm:ss.xxx>` stamps — the shape
     * YouLyPlus's v2 endpoint generates and most enhanced-LRC files use — or null when the line has
     * fewer than two stamped words (one stamp is not word sync, and a single word says nothing about
     * which word is being sung).
     *
     * The stamp marks a word's start and the NEXT stamp ends it; the last word of the line gets
     * [ENHANCED_LRC_LAST_WORD_DEFAULT_DURATION_MS] and is later clamped to the next line by
     * [clampEnhancedLrcLastWordEnds].
     */
    private fun extractEnhancedLrcWordTimestamps(rawText: String): List<WordTimestamp>? {
        if (!ENHANCED_LRC_WORD_TIME_REGEX.containsMatchIn(rawText)) return null
        val tokens = ENHANCED_LRC_WORD_TOKEN_REGEX.findAll(rawText).toList()
        if (tokens.size < 2) return null

        val words = mutableListOf<WordTimestamp>()
        tokens.forEachIndexed { index, token ->
            val startMs = parseEnhancedLrcStampMs(token.groupValues[1]) ?: return@forEachIndexed
            val normalizedText = token.groupValues[2].replace(WHITESPACE_REGEX, " ")
            val wordText = normalizedText.trim { it.isWhitespace() || it == NBSP }
            if (wordText.isEmpty()) return@forEachIndexed
            // Keep one trailing space when the source carries it: the verbatim word renderers
            // (LyricsV2 / LyricsEnhanced karaoke) lay each word out as-is, so trimming the edge gap
            // collapsed "Hello world" into "Helloworld" for every provider whose generator puts the
            // separator after the word.
            val textWithGap = if (normalizedText.endsWith(" ")) "$wordText " else wordText
            val nextStartMs =
                tokens
                    .getOrNull(index + 1)
                    ?.let { parseEnhancedLrcStampMs(it.groupValues[1]) }
            val endMs = nextStartMs ?: (startMs + ENHANCED_LRC_LAST_WORD_DEFAULT_DURATION_MS)
            words.add(
                WordTimestamp(
                    text = textWithGap,
                    startTime = startMs / 1000.0,
                    endTime = maxOf(endMs, startMs + MIN_WORD_DURATION_MS) / 1000.0,
                ),
            )
        }
        return words.takeIf { it.size >= 2 }
    }

    private fun parseMillisecondsSyncedLine(line: String): List<LyricsEntry>? {
        if (line.isEmpty()) {
            return null
        }
        val matchResult = YRC_LINE_REGEX.matchEntire(line.trim()) ?: return null
        val time = matchResult.groupValues[1].toLongOrNull() ?: return null
        val text = cleanInlineWordTimingText(matchResult.groupValues[2])
        return listOf(LyricsEntry(time, text))
    }

    /**
     * Folds the second row of a translation-annotated line-synced document into
     * [LyricsEntry.providerTranslationText]. Such a document repeats a line's timestamp and puts
     * the translation on the following row — that is how an AI translation is stored back
     * ([moe.rukamori.archivetune.ai.AiLyricsDocument]) and how bilingual providers ship lyrics.
     *
     * The fold is only valid when the first row at that timestamp is real text. A blank row is not
     * the original half of a translation pair: LRCLIB carries empty spacer rows (e.g.
     * `[00:21.50]` with no text followed by `[00:21.50] Respiravo una cosa`). Folding one of those
     * left an empty entry holding the only text as its "translation", so neither row was rendered
     * and both lines vanished from the lyrics. Blank rows and any row beyond the single translation
     * are kept as their own entries, so a genuine second line at a shared timestamp survives.
     */
    private fun mergeLineSyncedTranslations(entries: List<LyricsEntry>): List<LyricsEntry> {
        val rowsByTime = linkedMapOf<Long, MutableList<LyricsEntry>>()
        entries.forEach { entry -> rowsByTime.getOrPut(entry.time) { mutableListOf() }.add(entry) }

        val merged = mutableListOf<LyricsEntry>()
        rowsByTime.values.forEach { rows ->
            val original = rows.first()
            if (original.text.isBlank()) {
                merged.addAll(rows)
                return@forEach
            }

            var head = original
            val extras = mutableListOf<LyricsEntry>()
            rows.drop(1).forEach { row ->
                val translatedText =
                    row.text
                        .replace(WHITESPACE_REGEX, " ")
                        .trim()
                        .takeIf { it.isNotEmpty() && !it.equals(head.text.trim(), ignoreCase = true) }

                if (translatedText != null && head.providerTranslationText == null) {
                    head = head.copy(providerTranslationText = translatedText)
                } else {
                    extras.add(row)
                }
            }
            merged.add(head)
            merged.addAll(extras)
        }
        return merged
    }

    private fun cleanInlineWordTimingText(text: String): String =
        text
            .replace(ENHANCED_LRC_WORD_TIME_REGEX, "")
            .replace(INLINE_MILLISECONDS_TIME_REGEX, "")
            .replace(YRC_WORD_TIME_REGEX, "")
            .replace(WHITESPACE_REGEX, " ")
            .trim { it.isWhitespace() || it == NBSP }

    fun findCurrentLineIndex(
        lines: List<LyricsEntry>,
        position: Long,
        leadMs: Long = 300L,
    ): Int {
        if (lines.isEmpty()) return -1

        // Find the last line whose start time is <= position (no lead). This is the
        // "candidate current line" — we then decide whether to advance to the next line.
        val exactTarget = position
        var low = 0
        var high = lines.lastIndex

        while (low <= high) {
            val mid = (low + high).ushr(1)
            val midTime = lines[mid].time

            if (midTime < exactTarget) {
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        val currentIdx = high.coerceIn(0, lines.lastIndex)

        // If there's no next line, the current line is the answer.
        val nextIdx = currentIdx + 1
        if (nextIdx > lines.lastIndex) return currentIdx

        val currentLine = lines[currentIdx]
        val nextLine = lines[nextIdx]

        // Fix for "next line becomes active while the previous line is still being sung" —
        // when the current line has an explicit durationMs (TTML), do not advance to the next
        // line until we're past the current line's end. The previous behaviour unconditionally
        // applied `leadMs` and could highlight N+1 up to 300ms before N finished singing,
        // which was jarring on lines with tight tail gaps.
        if (currentLine.durationMs > 0L) {
            val currentLineEndMs = currentLine.time + currentLine.durationMs
            if (position < currentLineEndMs) {
                return currentIdx
            }
        }

        // For line-synced LRC (no durationMs), the previous behaviour applied a flat 300ms lead
        // regardless of how big the gap to the next line was. That made the next line highlight
        // 300ms early even when there was a long instrumental break — and when the actual singing
        // of the next line was still slightly delayed by the singer, the highlight felt premature.
        //
        // We now only apply the lead when lines are back-to-back (gap between line starts <= 2s).
        // For longer gaps (slow ballads, instrumental interludes), we transition exactly at the
        // next line's start time — no read-ahead. This preserves smooth transitions on rapid
        // lyrics while avoiding the premature highlight on long-gap tracks.
        val gapBetweenLineStartsMs = nextLine.time - currentLine.time
        val effectiveLeadMs = if (gapBetweenLineStartsMs > 2_000L) 0L else leadMs

        return if (position + effectiveLeadMs >= nextLine.time) {
            nextIdx
        } else {
            currentIdx
        }
    }

    /**
     * Returns true when [entry] has real, per-word timing information that should drive
     * word-by-word (karaoke) animation.
     */
    fun hasTrueWordSync(entry: LyricsEntry): Boolean {
        val raw = entry.words ?: return false
        val words = raw.filter { it.text.isNotBlank() }
        if (words.isEmpty()) return false
        if (words.size == 1) {
            // Single-word line: still counts as word-synced when the one span has a
            // real, positive duration. The all-starts-identical / all-ends-identical
            // / even-distribution heuristics below are meaningless for N=1, so just
            // verify the word actually spans a non-zero interval.
            val only = words.first()
            return (only.endTime - only.startTime) > 0.0
        }

        val startTimes = words.map { it.startTime }
        val endTimes = words.map { it.endTime }

        // All start times identical → no per-word timing.
        if (startTimes.distinct().size == 1) return false
        // All end times identical → no per-word timing.
        if (endTimes.distinct().size == 1) return false

        val durations = words.map { (it.endTime - it.startTime).coerceAtLeast(0.0) }
        // All durations zero → no real word spans.
        if (durations.all { it <= 0.0 }) return false

        val lineStart = startTimes.min()
        val lineEnd = endTimes.max()
        val lineDuration = lineEnd - lineStart

        // Detect perfectly even linear distribution of start times across the line.
        // This is what providers produce when they fake word sync by computing
        //   word[i].start = lineStart + i * (lineEnd - lineStart) / (N - 1)
        // We tolerate up to 50ms deviation per word; real word sync deviates more.
        if (lineDuration > 0.0 && words.size > 2) {
            val tolerance = 0.05 // 50ms
            val isEvenlyDistributed = startTimes.indices.all { i ->
                val expected = lineStart + (lineDuration * i / (words.size - 1))
                kotlin.math.abs(startTimes[i] - expected) < tolerance
            }
            if (isEvenlyDistributed) {
                // Even distribution could still be real singing that happens to be very
                // regular. Require word durations to also have meaningful variation —
                // fake providers typically give every word the same duration too.
                val positiveDurations = durations.filter { it > 0.0 }
                if (positiveDurations.size >= 3) {
                    val avg = positiveDurations.average()
                    if (avg > 0.0) {
                        val variance = positiveDurations.map { (it - avg) * (it - avg) }.average()
                        val stddev = kotlin.math.sqrt(variance)
                        if (stddev / avg < 0.1) {
                            // Durations are essentially identical AND start times are perfectly
                            // evenly spaced — this is almost certainly synthetic timing.
                            return false
                        }
                    }
                }
            }
        }

        return true
    }

    /**
     * Converts any Hiragana characters in [text] to their Katakana equivalents. Hiragana and
     * Katakana share the same Unicode ordering — every Hiragana codepoint has a Katakana
     * counterpart at offset 0x60 (e.g. あ U+3042 → ア U+30A2).
     */
    private fun hiraganaToKatakana(text: String): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder(text.length)
        for (ch in text) {
            sb.append(
                if (ch in '\u3041'..'\u3096') {
                    (ch.code + 0x60).toChar()
                } else {
                    ch
                },
            )
        }
        return sb.toString()
    }

    /**
     * Romanizes Japanese text using Kuromoji Tokenizer and the optimized katakanaToRomaji function.
     * Runs on Dispatchers.Default for CPU-intensive work.
     */
    suspend fun romanizeJapanese(text: String): String =
        withContext(Dispatchers.Default) {
            val tokenizer = JapaneseLanguagePackManager.tokenizerOrNull() ?: return@withContext text
            romanizeJapaneseWithTokenizer(text, tokenizer)
        }

    internal fun romanizeJapaneseWithTokenizer(text: String, tokenizer: DownloadedJapaneseTokenizer): String {
        val tokens = tokenizer.tokenize(text)
        val romanizedTokens =
            tokens.mapIndexed { index, token ->
                val currentReading =
                    if (token.reading.isNullOrEmpty() || token.reading == "*") {
                        token.surface
                    } else {
                        token.reading
                    }
                // Normalize Hiragana → Katakana so KANA_ROMAJI_MAP can handle both.
                val katakanaReading = hiraganaToKatakana(currentReading)

                // Pass the next token's reading for sokuon handling at token boundaries.
                // Also normalized to Katakana for consistency.
                val nextTokenReading =
                    if (index + 1 < tokens.size) {
                        val nextReading =
                            tokens[index + 1].reading?.takeIf { it.isNotEmpty() && it != "*" }
                                ?: tokens[index + 1].surface
                        hiraganaToKatakana(nextReading)
                    } else {
                        null
                    }
                katakanaToRomaji(katakanaReading, nextTokenReading)
            }
        return romanizedTokens.joinToString(" ")
    }

    /** Converts a Katakana string to Romaji using the pre-defined [KANA_ROMAJI_MAP]. */
    fun katakanaToRomaji(
        katakana: String?,
        nextKatakana: String? = null,
    ): String {
        if (katakana.isNullOrEmpty()) return ""

        val romajiBuilder = StringBuilder(katakana.length)
        var i = 0
        val n = katakana.length
        while (i < n) {
            var consumed = false
            // Prioritize 2-character sequences from the map (e.g., "キャ" before "キ")
            if (i + 1 < n) {
                val twoCharCandidate = katakana.substring(i, i + 2)
                val mappedTwoChar = KANA_ROMAJI_MAP[twoCharCandidate]
                if (mappedTwoChar != null) {
                    romajiBuilder.append(mappedTwoChar)
                    i += 2
                    consumed = true
                }
            }

            // Handle sokuon (ッ) — gemination. Doubles the consonant of the next
            // character. Look INSIDE the current string first; only fall back to
            // the next token's first character when sokuon is at the end of the
            // current string (rare; usually a tokenizer artifact).
            if (!consumed && katakana[i] == 'ッ') {
                val nextCharInSameString = katakana.getOrNull(i + 1)
                val nextCharToDouble = nextCharInSameString ?: nextKatakana?.getOrNull(0)
                if (nextCharToDouble != null) {
                    val nextCharRomaji =
                        KANA_ROMAJI_MAP[nextCharToDouble.toString()]
                            ?: nextCharToDouble.toString()
                    // Take the first letter (the consonant to geminate) and double it.
                    // For vowel-initial kana (あ, い, う, え, お) the first letter is the
                    // vowel itself — geminating a vowel is unusual but renders as the
                    // vowel doubled (e.g. っあ → "aa"), which matches common romaji
                    // conventions for emphatic speech.
                    val firstLetter = nextCharRomaji.firstOrNull()?.lowercase()?.trim()
                    if (firstLetter != null && firstLetter.isNotEmpty()) {
                        romajiBuilder.append(firstLetter)
                    }
                }
                i += 1 // Consume the 'ッ'
                consumed = true
            }

            // Handle chōonpu (ー) — long vowel mark. Extends the previous vowel
            // instead of being silently dropped. Maps to the same vowel as the last
            // emitted character (e.g. "カ" + "ー" → "ka" + "a" = "kaa"). If there's
            // no previous vowel (start of string, or previous char was a consonant),
            // we emit nothing.
            if (!consumed && katakana[i] == 'ー') {
                val lastChar = romajiBuilder.lastOrNull()
                val extension = when (lastChar) {
                    'a' -> "a"
                    'i' -> "i"
                    'u' -> "u"
                    'e' -> "e"
                    'o' -> "o"
                    else -> ""
                }
                romajiBuilder.append(extension)
                i += 1
                consumed = true
            }

            if (!consumed) {
                // If no 2-character sequence matched, try 1-character
                val oneCharCandidate = katakana[i].toString()
                val mappedOneChar = KANA_ROMAJI_MAP[oneCharCandidate]
                if (mappedOneChar != null) {
                    romajiBuilder.append(mappedOneChar)
                } else {
                    // If the character is not in Katakana map, append it as is.
                    romajiBuilder.append(oneCharCandidate)
                }
                i += 1
            }
        }
        return romajiBuilder.toString().lowercase()
    }

    suspend fun romanizeKorean(text: String): String =
        withContext(Dispatchers.Default) {
            val romajaBuilder = StringBuilder()
            var prevFinal: String? = null

            // Compose only the phonetic working copy, never the displayed lyric.
            val composed = Normalizer.normalize(text, Normalizer.Form.NFC)
            for (i in composed.indices) {
                val char = composed[i]

                if (char in '\uAC00'..'\uD7A3') {
                    val syllableIndex = char.code - 0xAC00

                    val choIndex = syllableIndex / (21 * 28)
                    val jungIndex = (syllableIndex % (21 * 28)) / 28
                    val jongIndex = syllableIndex % 28

                    val choChar = (0x1100 + choIndex).toChar().toString()
                    val jungChar = (0x1161 + jungIndex).toChar().toString()
                    val jongChar = if (jongIndex == 0) null else (0x11A7 + jongIndex).toChar().toString()

                    if (prevFinal != null) {
                        val contextKey = prevFinal + choChar
                        val jong =
                            HANGUL_ROMAJA_MAP["jong"]?.get(contextKey)
                                ?: HANGUL_ROMAJA_MAP["jong"]?.get(prevFinal)
                                ?: prevFinal
                        romajaBuilder.append(jong)
                    }

                    val cho = HANGUL_ROMAJA_MAP["cho"]?.get(choChar) ?: choChar
                    val jung = HANGUL_ROMAJA_MAP["jung"]?.get(jungChar) ?: jungChar
                    romajaBuilder.append(cho).append(jung)

                    prevFinal = jongChar
                } else {
                    if (prevFinal != null) {
                        val jong = HANGUL_ROMAJA_MAP["jong"]?.get(prevFinal) ?: prevFinal
                        romajaBuilder.append(jong)
                        prevFinal = null
                    }
                    romajaBuilder.append(char)
                }
            }

            if (prevFinal != null) {
                val jong = HANGUL_ROMAJA_MAP["jong"]?.get(prevFinal) ?: prevFinal
                romajaBuilder.append(jong)
            }

            romajaBuilder.toString()
        }

    // region Hindi (Devanagari) romanization A hand-written Devanagari→Latin mapper that produces
    // intuitive, pronounceable romanization for Hindi lyrics (e.g. "नमस्ते" → "namaste", "आदित्य" →
    // "aaditya", "क्षमा" → "kshama").
    private val DEVANAGARI_INDEPENDENT_VOWELS =
        mapOf(
            'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ii", 'उ' to "u", 'ऊ' to "uu",
            'ऋ' to "ri", 'ॠ' to "rii", 'ऌ' to "lri", 'ॡ' to "lrii",
            'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au",
        )

    private val DEVANAGARI_MATRAS =
        mapOf(
            'ा' to "aa", 'ि' to "i", 'ी' to "ii", 'ु' to "u", 'ू' to "uu",
            'ृ' to "ri", 'ॄ' to "rii", 'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au",
            'ॅ' to "e", 'ॉ' to "o", 'ॢ' to "lri", 'ॣ' to "lrii",
        )

    private val DEVANAGARI_CONSONANTS =
        mapOf(
            'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "ng",
            'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "ny",
            'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
            'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
            'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m",
            'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v",
            'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h",
            'ळ' to "l",
            // Note: common conjuncts (क्ष, ज्ञ, त्र, श्र) are NOT single Unicode
            // codepoints — they're consonant + virama + consonant sequences, so they
            // can't be map keys here. They are handled naturally by the main loop's
            // consonant + virama + consonant flow, which produces the same result
            // (e.g. क + ् + ष → "k" + "" + "sh" + "a" = "ksha").
        )

    // Approximations for less-common letters / chillu characters (Malayalam-in-Devanagari etc.)
    private val DEVANAGARI_OTHER =
        mapOf(
            'ॐ' to "om",
            '।' to ".", '॥' to "..",
            'ऽ' to "'",  // avagraha
            'ं' to "n",  // anusvara (default; refined by context below)
            'ः' to "h",  // visarga
            'ँ' to "n",  // candrabindu (nasalization, simplified)
            '्' to "",   // virama (halant) — suppresses inherent "a"; handled by loop
        )

    private val DEVANAGARI_NUMERALS =
        mapOf(
            '०' to "0", '१' to "1", '२' to "2", '३' to "3", '४' to "4",
            '५' to "5", '६' to "6", '७' to "7", '८' to "8", '९' to "9",
        )

    private val LABIALS = setOf('प', 'फ', 'ब', 'भ', 'म')

    suspend fun romanizeHindi(text: String): String =
        withContext(Dispatchers.Default) {
            val sb = StringBuilder(text.length * 2)
            var i = 0
            val n = text.length
            while (i < n) {
                val ch = text[i]

                // Devanagari numerals
                if (DEVANAGARI_NUMERALS[ch] != null) {
                    sb.append(DEVANAGARI_NUMERALS[ch])
                    i++
                    continue
                }

                // Anusvara (ं) — context-sensitive:
                //   before labials (प फ ब भ म) → "m"
                //   otherwise → "n"
                if (ch == 'ं') {
                    val next = text.getOrNull(i + 1)
                    sb.append(if (next != null && next in LABIALS) "m" else "n")
                    i++
                    continue
                }

                // Candrabindu (ँ) — nasalization marker, simplified to "n"
                if (ch == 'ँ') {
                    sb.append("n")
                    i++
                    continue
                }

                // Visarga (ः)
                if (ch == 'ः') {
                    sb.append("h")
                    i++
                    continue
                }

                // Virama (्) — suppresses inherent "a" of preceding consonant.
                // The preceding consonant was already emitted WITHOUT inherent "a"
                // (see consonant branch below), so we just skip the virama here.
                if (ch == '्') {
                    i++
                    continue
                }

                // Matras (vowel signs) — replace the inherent "a" of the preceding
                // consonant. The preceding consonant was emitted WITHOUT "a" in
                // anticipation (see consonant branch below).
                val matra = DEVANAGARI_MATRAS[ch]
                if (matra != null) {
                    sb.append(matra)
                    i++
                    continue
                }

                // Independent vowels
                val independentVowel = DEVANAGARI_INDEPENDENT_VOWELS[ch]
                if (independentVowel != null) {
                    sb.append(independentVowel)
                    i++
                    continue
                }

                // Consonants — look ahead to decide whether to emit inherent "a":
                //   - If next char is a matra, virama, anusvara, visarga, or
                //     candrabindu, emit just the consonant base (no "a").
                //   - Otherwise emit consonant + "a" (inherent vowel).
                val consonant = DEVANAGARI_CONSONANTS[ch]
                if (consonant != null) {
                    val next = text.getOrNull(i + 1)
                    val suppressInherentA =
                        next != null && (
                            next in DEVANAGARI_MATRAS ||
                                next == '्' ||
                                next == 'ं' ||
                                next == 'ः' ||
                                next == 'ँ'
                        )
                    sb.append(consonant)
                    if (!suppressInherentA) {
                        sb.append('a')
                    }
                    i++
                    continue
                }

                // Other Devanagari signs (ॐ, ।, ॥, ऽ)
                val other = DEVANAGARI_OTHER[ch]
                if (other != null) {
                    sb.append(other)
                    i++
                    continue
                }

                // Non-Devanagari character — pass through as-is (spaces, punctuation,
                // Latin letters, etc.)
                sb.append(ch)
                i++
            }
            sb.toString()
        }
    // endregion

    /**
     * Checks if the given text contains any Japanese characters (Hiragana, Katakana, or common Kanji).
     * This function is generally efficient due to '.any' and early exit.
     * No major performance bottlenecks expected here for typical inputs.
     */
    fun isJapanese(text: String): Boolean =
        text.any { char ->
            (char in '\u3040'..'\u309F') || // Hiragana
                (char in '\u30A0'..'\u30FF') || // Katakana
                // CJK Unified Ideographs (covers most common Kanji). This range also
                // includes many Chinese Hanzi; the two scripts share characters, so a
                // range check alone cannot tell them apart.
                (char in '\u4E00'..'\u9FFF')
        }

    /**
     * Checks if the given text contains any Korean characters (Hangul Syllables, Jamo, etc.).
     */
    fun isKorean(text: String): Boolean =
        text.any { char ->
            UnicodeScript.of(char.code) == UnicodeScript.HANGUL
        }

    /**
     * Checks if the given text contains any Chinese characters (common Hanzi). This function is
     * generally efficient due to '.any' and early exit.
     */
    fun isChinese(text: String): Boolean {
        if (text.isEmpty()) return false

        val hanCharCount = text.codePoints().filter { UnicodeScript.of(it) == UnicodeScript.HAN }.count()
        if (hanCharCount == 0L) return false

        val japaneseKanaCount = text.count { hasScript(it, UnicodeScript.HIRAGANA) || hasScript(it, UnicodeScript.KATAKANA) }
        val hangulCount = text.count { hasScript(it, UnicodeScript.HANGUL) }

        return japaneseKanaCount == 0 && hangulCount == 0
    }

    fun isHindi(text: String): Boolean = text.any { hasScript(it, UnicodeScript.DEVANAGARI) }

    fun hasOtherRomanizableScript(text: String): Boolean {
        return text.any { char ->
            if (!char.isLetter()) return@any false
            val script = UnicodeScript.of(char.code)
            script !in OTHER_ROMANIZATION_EXCLUDED_SCRIPTS
        }
    }

    fun shouldRomanizeLyricsLine(
        text: String,
        preferences: LyricsRomanizationPreferences,
        sourceLanguage: String? = null,
        contextText: String = text,
    ): Boolean {
        if (!preferences.isEnabled || text.isBlank()) return false

        return when {
            preferences.romanizeJapanese && looksJapanese(text, sourceLanguage, contextText) -> true
            preferences.romanizeKorean && isKorean(text) -> true
            preferences.romanizeHindi && isHindi(text) -> true
            preferences.romanizeChinese && !looksJapanese(text, sourceLanguage, contextText) && isChinese(text) -> true
            preferences.romanizeOther && hasOtherRomanizableScript(text) -> true
            else -> false
        }
    }

    /**
     * True when [text] contains any script a romanisation could apply to, regardless of which engines
     * the user has enabled.
     *
     * [shouldRomanizeLyricsLine] answers "should the built-in romanisers touch this line", which is
     * the wrong question for the AI path: that one has a single on/off switch and no per-language
     * engine toggles, but still must not spend a request on lyrics that are already Latin script.
     */
    fun hasRomanizableScript(text: String): Boolean {
        if (text.isBlank()) return false
        return looksJapanese(text) ||
            isKorean(text) ||
            isHindi(text) ||
            isChinese(text) ||
            hasOtherRomanizableScript(text)
    }

    fun shouldUseProvidedRomanization(
        originalText: String,
        providerRomanizedText: String?,
        providerRomanizedLanguage: String?,
        preferences: LyricsRomanizationPreferences,
    ): Boolean {
        if (!preferences.isEnabled || originalText.isBlank()) return false
        val normalized =
            providerRomanizedText
                ?.replace(WHITESPACE_REGEX, " ")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return false
        if (normalized.equals(originalText.trim(), ignoreCase = true)) return false

        val language =
            providerRomanizedLanguage
                ?.substringBefore("-")
                ?.substringBefore("_")
                ?.lowercase()

        return when (language) {
            "ja" -> preferences.romanizeJapanese
            "ko" -> preferences.romanizeKorean
            "zh", "cmn", "yue" -> preferences.romanizeChinese
            "hi", "sa", "mr", "ne" -> preferences.romanizeHindi
            null, "" -> shouldRomanizeLyricsLine(originalText, preferences)
            else -> preferences.romanizeOther || shouldRomanizeLyricsLine(originalText, preferences)
        }
    }

    fun providedRomanizedTextForEntry(
        entry: LyricsEntry,
        preferences: LyricsRomanizationPreferences,
    ): String? =
        entry.providerRomanizedText
            ?.replace(WHITESPACE_REGEX, " ")
            ?.trim()
            ?.takeIf {
                shouldUseProvidedRomanization(
                    originalText = entry.text,
                    providerRomanizedText = it,
                    providerRomanizedLanguage = entry.providerRomanizedLanguage,
                    preferences = preferences,
                )
            }

    fun providedTranslationTextForEntry(entry: LyricsEntry): String? =
        entry.providerTranslationText
            ?.replace(WHITESPACE_REGEX, " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals(entry.text.trim(), ignoreCase = true) }

    fun providedRomanizedWordsForEntry(
        entry: LyricsEntry,
        expectedWordCount: Int,
        preferences: LyricsRomanizationPreferences,
    ): List<String?>? {
        if (expectedWordCount <= 0) return null
        if (providedRomanizedTextForEntry(entry, preferences) == null) return null

        val words =
            entry.providerRomanizedWords
                ?.map { word -> word.replace(WHITESPACE_REGEX, " ").trim() }
                ?.filter { it.isNotEmpty() }
                ?.takeIf { it.size == expectedWordCount }
                ?: return null

        return words
    }

    suspend fun phoneticsForEntry(
        entry: LyricsEntry,
        preferences: LyricsRomanizationPreferences,
        contextText: String = entry.text,
    ): LyricsPhonetics {
        if (!preferences.isEnabled) return LyricsPhonetics()
        val line = providedRomanizedTextForEntry(entry, preferences)
            ?: romanizeLyricsLine(entry.text, preferences, entry.providerRomanizedLanguage, contextText)
        val words = entry.words?.filter { !it.isBackground }
        val aligned = if (words.isNullOrEmpty()) null else {
            providedRomanizedWordsForEntry(entry, words.size, preferences)
                ?: romanizeWordsForLine(
                    words.map { it.text }, entry.text, preferences,
                    entry.providerRomanizedLanguage, contextText,
                ).takeIf { readings -> readings.any { !it.isNullOrBlank() } }
        }
        return LyricsPhonetics(line = line, words = aligned)
    }

    suspend fun romanizeLyricsLine(
        text: String,
        preferences: LyricsRomanizationPreferences,
        sourceLanguage: String? = null,
        contextText: String = text,
    ): String? {
        if (!shouldRomanizeLyricsLine(text, preferences, sourceLanguage, contextText)) return null

        val romanized =
            when {
                preferences.romanizeJapanese && looksJapanese(text, sourceLanguage, contextText) -> romanizeJapanese(text)
                preferences.romanizeKorean && isKorean(text) -> romanizeKorean(text)
                preferences.romanizeHindi && isHindi(text) -> romanizeHindi(text)
                preferences.romanizeChinese && !looksJapanese(text, sourceLanguage, contextText) && isChinese(text) -> romanizeWithIcu(text)
                preferences.romanizeOther && hasOtherRomanizableScript(text) -> romanizeWithIcu(text)
                else -> null
            }

        return normalizeRomanizedText(text, romanized)
    }

    suspend fun romanizeLyricsWordWithLineContext(
        word: String,
        lineText: String,
        preferences: LyricsRomanizationPreferences,
        sourceLanguage: String? = null,
        contextText: String = lineText,
    ): String? {
        if (word.isBlank()) return null
        val romanized =
            when {
                preferences.romanizeJapanese && looksJapanese(lineText, sourceLanguage, contextText) -> romanizeJapanese(word)
                preferences.romanizeKorean && isKorean(lineText) -> romanizeKorean(word)
                preferences.romanizeHindi && isHindi(lineText) -> romanizeHindi(word)
                preferences.romanizeChinese && !looksJapanese(lineText, sourceLanguage, contextText) && isChinese(lineText) -> romanizeWithIcu(word)
                preferences.romanizeOther && hasOtherRomanizableScript(lineText) -> romanizeWithIcu(word)
                else -> null
            }
        return normalizeRomanizedText(word, romanized)
    }

    /** Romanizes a list of words from a single line using ONE tokenization pass for Japanese. */
    suspend fun romanizeWordsForLine(
        words: List<String>,
        lineText: String,
        preferences: LyricsRomanizationPreferences,
        sourceLanguage: String? = null,
        contextText: String = lineText,
    ): List<String?> {
        if (words.isEmpty()) return emptyList()
        if (!preferences.isEnabled) return words.map { null }
        // Japanese: single-pass line tokenization (Nx faster than per-word).
        if (preferences.romanizeJapanese && looksJapanese(lineText, sourceLanguage, contextText)) {
            return romanizeJapaneseWordsForLine(words, lineText)
        }
        // Other languages: per-word is cheap (character-by-character), keep as-is.
        return words.map { word ->
            romanizeLyricsWordWithLineContext(word, lineText, preferences, sourceLanguage, contextText)
        }
    }

    /** Japanese-specific per-line tokenization. See [romanizeWordsForLine] for the rationale. */
    internal suspend fun romanizeJapaneseWordsForLine(
        words: List<String>,
        lineText: String,
        tokenizer: DownloadedJapaneseTokenizer? = JapaneseLanguagePackManager.tokenizerOrNull(),
    ): List<String?> = withContext(Dispatchers.Default) {
        if (words.isEmpty()) return@withContext emptyList()
        if (tokenizer == null) return@withContext words.map { null }

        val tokens = tokenizer.tokenize(lineText)
        if (tokens.isEmpty()) return@withContext words.map { null }

        // Pre-compute each token's [start, end) range and its romaji.
        // The next token's reading is kept for sokuon-at-boundary gemination
        // (same logic as romanizeJapanese, just vectorized).
        val tokenCount = tokens.size
        val tokenStarts = IntArray(tokenCount)
        val tokenEnds = IntArray(tokenCount)
        val tokenRomaji = ArrayList<String>(tokenCount)
        for (i in 0 until tokenCount) {
            val token = tokens[i]
            val surface = token.surface
            tokenStarts[i] = token.position
            tokenEnds[i] = token.position + surface.length

            val currentReading =
                if (token.reading.isNullOrEmpty() || token.reading == "*") {
                    surface
                } else {
                    token.reading
                }
            val katakanaReading = hiraganaToKatakana(currentReading)
            val nextTokenReading =
                if (i + 1 < tokenCount) {
                    val nr = tokens[i + 1].reading
                    val nextReading =
                        if (nr.isNullOrEmpty() || nr == "*") tokens[i + 1].surface else nr
                    hiraganaToKatakana(nextReading)
                } else {
                    null
                }
            tokenRomaji.add(katakanaToRomaji(katakanaReading, nextTokenReading))
        }

        // Walk through words and tokens in parallel (both are in text order).
        // This is O(words + tokens) — no nested loops.
        val result = ArrayList<String?>(words.size)
        var scanOffset = 0
        var tokenIdx = 0
        for (word in words) {
            val wordStart = lineText.indexOf(word, startIndex = scanOffset)
            if (wordStart < 0) {
                return@withContext words.map { null }
            }
            val wordEnd = wordStart + word.length

            // Advance tokenIdx past tokens that end before this word starts.
            while (tokenIdx < tokenCount && tokenEnds[tokenIdx] <= wordStart) {
                tokenIdx++
            }

            // Collect all tokens that overlap [wordStart, wordEnd).
            val romajiBuilder = StringBuilder()
            var tIdx = tokenIdx
            while (tIdx < tokenCount && tokenStarts[tIdx] < wordEnd) {
                if (tokenEnds[tIdx] > wordStart) {
                    // There is no trustworthy kana-to-timed-fragment alignment inside a token.
                    if (tokenStarts[tIdx] < wordStart || tokenEnds[tIdx] > wordEnd) {
                        return@withContext words.map { null }
                    }
                    romajiBuilder.append(tokenRomaji[tIdx])
                }
                tIdx++
            }

            result.add(romajiBuilder.toString().takeIf { it.isNotEmpty() })
            scanOffset = wordEnd
        }
        result
    }

    internal suspend fun romanizeWithIcu(
        text: String,
        convert: (String) -> String = { genericRomanizationTransliterator.get().transliterate(it) },
    ): String =
        withContext(Dispatchers.Default) {
            try {
                convert(text)
            } catch (_: Exception) {
                text
            }
        }

    internal fun normalizeRomanizedText(
        original: String,
        romanized: String?,
    ): String? {
        val normalized =
            romanized
                ?.replace(WHITESPACE_REGEX, " ")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return null

        return normalized.takeUnless { it.equals(original.trim(), ignoreCase = true) }
    }

    private fun looksJapanese(
        text: String,
        sourceLanguage: String? = null,
        contextText: String = text,
    ): Boolean {
        val language = sourceLanguage?.lowercase()?.replace('_', '-')?.substringBefore('-')
        if (language in setOf("zh", "cmn", "yue")) return false
        if (language == "ja") return true
        // Han alone is ambiguous. Nearby kana is context, installed data is not language evidence.
        return contextText.codePoints().anyMatch {
            UnicodeScript.of(it) == UnicodeScript.HIRAGANA || UnicodeScript.of(it) == UnicodeScript.KATAKANA
        }
    }

    private fun hasScript(
        char: Char,
        script: UnicodeScript,
    ): Boolean = char.isLetter() && UnicodeScript.of(char.code) == script
}
