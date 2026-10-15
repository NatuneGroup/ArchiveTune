/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.lyrics

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Structural boundaries only: does not claim Compose runtime, pixel, gesture or Android execution. */
class LyricsRendererSourceContractTest {
    private fun source(relative: String): String {
        val candidates = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        val root = candidates.firstOrNull { File(it, "app/src/main/kotlin").isDirectory }
            ?: error("Repository sources required for structural contract tests")
        return File(root, "app/src/main/kotlin/moe/rukamori/archivetune/$relative").readText()
    }

    @Test fun v2ReplacementClockProviderIsKeyedByTheActualReplacementState() {
        val v2 = source("ui/component/LyricsV2.kt")
        assertTrue(Regex("remember\\(lyrics\\)\\s*\\{\\s*mutableLongStateOf\\(0L\\)").containsMatchIn(v2))
        assertTrue(Regex("remember\\(currentPositionMsState\\)\\s*\\{\\s*\\{\\s*currentPositionMsState.longValue").containsMatchIn(v2))
        assertTrue(v2.contains("currentPositionProvider = currentPositionProvider"))
    }

    @Test fun allRenderersAndSettingsShareFalseJapaneseDefaultAndFullAiEligibility() {
        listOf("ui/component/LyricsEnhanced.kt", "ui/component/LyricsV2.kt",
            "ui/player/simpmusic/SimpMusicLyrics.kt", "ui/screens/settings/LyricsRomanisationSettings.kt").forEach { path ->
            assertTrue(path, Regex("rememberPreference\\(LyricsRomanizeJapaneseKey,\\s*defaultValue = false\\)").containsMatchIn(source(path)))
        }
        listOf("ui/component/LyricsEnhanced.kt", "ui/component/LyricsV2.kt", "ui/player/simpmusic/SimpMusicLyrics.kt").forEach { path ->
            val text = source(path)
            assertTrue(path, text.contains("AiLyricsRomanization.isEligible("))
            assertTrue(path, text.contains("sourceLanguageHints"))
            assertTrue(path, text.contains("phoneticsForEntry("))
        }
    }

    @Test fun simpMusicOriginalTextIsOneLogicalLayoutWithOriginalSeekAndScrollOwnership() {
        val text = source("ui/player/simpmusic/SimpMusicLyrics.kt")
        assertTrue(text.contains("timedLyricsPresentation(entry, phonetics)"))
        assertTrue(text.contains("append(presentation.text)"))
        assertTrue(text.contains("presentation.ranges.forEachIndexed"))
        assertTrue(text.contains("isRtlText(text)"))
        assertTrue(text.contains("entry.time"))
        assertTrue(text.contains("onLyricsScroll(dragged)"))
        assertTrue(text.contains("onDispose { onLyricsScroll(false) }"))
    }

    @Test fun installCancellationRemovalAndForegroundRemainOwnedAndNonblocking() {
        val text = source("lyrics/JapaneseLanguagePackManager.kt")
        assertTrue(text.contains("fun install() = requestInstall(automatic = false)"))
        assertTrue(text.contains("if (operation?.isActive == true || cachedTokenizer != null) return@synchronized"))
        assertTrue(text.indexOf("mutableState.value = JapaneseLanguagePackState.Downloading()") < text.indexOf("downloadPack(partial, id)"))
        assertTrue(text.contains("if (mutableState.value is JapaneseLanguagePackState.Downloading) operation?.cancel()"))
        val remove = text.substringAfter("fun remove()").substringBefore("fun tokenizerOrNull")
        assertTrue(remove.indexOf("++generation") < remove.indexOf("previous?.cancel()"))
        assertTrue(remove.contains("cachedTokenizer = null"))
        assertTrue(remove.contains("it[removedKey] = true"))
        assertTrue(remove.contains("it[LyricsRomanizeJapaneseKey] = false"))
        assertTrue(remove.contains("previous?.join()"))
        assertTrue(text.contains("if (id == generation)"))
        val foreground = text.substringAfter("fun onForeground()").substringBefore("internal fun automaticEligible")
        assertTrue(foreground.contains("scope.launch"))
        assertTrue(foreground.contains("Resources.getSystem().configuration.locales.get(0)?.country"))
        assertTrue(foreground.indexOf("it[autoAttemptedKey] = true") < foreground.indexOf("context.isInternetConnected()"))
        assertFalse(foreground.contains("while ("))
        assertFalse(foreground.contains("TRANSPORT_WIFI"))
        assertFalse(foreground.contains("TRANSPORT_CELLULAR"))
    }

    @Test fun canonicalIdentityAndGenerationGuardsPrecedeRoomSuccessAndFeedback() {
        val text = source("viewmodels/LyricsMenuViewModel.kt")
        assertTrue(text.contains("translationIdentity(current, requestTarget) != identity"))
        assertTrue(text.contains("translationRequestTarget != requestIdentityTarget"))
        assertTrue(text.contains("generation != translationGeneration.get()"))
        assertTrue(text.contains("getLyricsById(mediaMetadata.id) != expected"))
        assertTrue(text.contains("LyricsEntity.Source.AI_TRANSLATION.value"))
        assertTrue(text.contains("if (saved && valid.get() && generation == translationGeneration.get()"))
        assertTrue(text.contains("if (generation == translationGeneration.get())"))
    }
}
