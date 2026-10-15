/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.lyrics

import com.atilika.kuromoji.util.ResourceResolver
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/** Downloads data only. No class from this archive is loaded or executed. Runs in final remote unit CI. */
class DownloadedJapaneseTokenizerIntegrationTest {
    @Test(timeout = 5_000L)
    fun missingOrCorruptResourcesCannotBecomeUsableTokenizer() {
        var resolverCalls = 0
        listOf<ResourceResolver>(
            ResourceResolver { resolverCalls++; throw IOException("Missing dictionary resource") },
            ResourceResolver { resolverCalls++; ByteArrayInputStream(byteArrayOf()) },
        ).forEach { resolver ->
            try {
                DownloadedJapaneseTokenizer.create(resolver)
                fail("Invalid data became a usable tokenizer")
            } catch (_: Exception) { }
        }
        assertTrue(resolverCalls >= 2)
    }

    @Test(timeout = 240_000L)
    fun verifiedPinnedDataProducesRealReadingsRomajiAndOfflineReopen() = runBlocking {
        val networkCallsAfterFixture = downloadCalls
        ZipFile(artifact).use { archive ->
            val tokenizer = openProductionTokenizer(archive)
            assertReadings(tokenizer)
            val tokyo = tokenizer.tokenize("東京")
            assertEquals(listOf("東京"), tokyo.map { it.surface })
            assertEquals(listOf(0), tokyo.map { it.position })
            // A real dictionary token spans both supplied timed fragments: no invented alignment.
            assertEquals(listOf<String?>(null, null),
                LyricsUtils.romanizeJapaneseWordsForLine(listOf("東", "京"), "東京", tokenizer))
            assertEquals(listOf("toukyou"),
                LyricsUtils.romanizeJapaneseWordsForLine(listOf("東京"), "東京", tokenizer))
            assertEquals("🦄", LyricsUtils.romanizeJapaneseWithTokenizer("🦄", tokenizer))
        }
        // Close/reopen the same verified installed file. This path has no network collaborator.
        ZipFile(artifact).use { archive -> assertReadings(openProductionTokenizer(archive)) }
        assertEquals(networkCallsAfterFixture, downloadCalls)
        assertEquals(EXPECTED_BYTES, artifact.length())
        println("VAL-JPACK-004 production readings/romaji verified for 日本語 and 東京; closed ZIP reopened offline from the same verified file")
    }

    private fun assertReadings(tokenizer: DownloadedJapaneseTokenizer) {
        mapOf("日本語" to ("ニホンゴ" to "nihongo"), "東京" to ("トウキョウ" to "toukyou")).forEach { (text, expected) ->
            val tokens = tokenizer.tokenize(text)
            assertEquals(text, tokens.joinToString("") { it.surface })
            assertEquals(expected.first, tokens.joinToString("") { it.reading })
            assertEquals(expected.second, LyricsUtils.katakanaToRomaji(expected.first))
            assertEquals(expected.second,
                LyricsUtils.romanizeJapaneseWithTokenizer(text, tokenizer).replace(" ", ""))
        }
    }

    private fun openProductionTokenizer(archive: ZipFile): DownloadedJapaneseTokenizer {
        REQUIRED_RESOURCES.forEach { name ->
            val entry = archive.getEntry(PREFIX + name)
            assertNotNull("Missing pinned dictionary data: $name", entry)
            assertTrue("Empty pinned resource: $name", requireNotNull(entry).size > 0)
        }
        return DownloadedJapaneseTokenizer.create(ResourceResolver { name ->
            // Production adapter reads only binary data, never downloaded .class files.
            require(name.endsWith(".bin")) { "Unexpected non-data dictionary resource" }
            val entry = archive.getEntry(PREFIX + name) ?: throw IOException("Missing resource: $name")
            archive.getInputStream(entry)
        })
    }

    companion object {
        private const val SOURCE = "https://repo.maven.apache.org/maven2/com/atilika/kuromoji/kuromoji-ipadic/0.9.0/kuromoji-ipadic-0.9.0.jar"
        private const val EXPECTED_BYTES = 13_343_016L
        private const val SHA256 = "24909fd751c0b439f7af5131b080eb65bc85062f0c4977ca4a71c76abe74e0b6"
        private const val PREFIX = "com/atilika/kuromoji/ipadic/"
        private val REQUIRED_RESOURCES = listOf("characterDefinitions.bin", "connectionCosts.bin", "doubleArrayTrie.bin",
            "tokenInfoDictionary.bin", "tokenInfoFeaturesMap.bin", "tokenInfoPartOfSpeechMap.bin",
            "tokenInfoTargetMap.bin", "unknownDictionary.bin")
        private lateinit var directory: File
        private lateinit var artifact: File
        private var downloadCalls = 0

        @JvmStatic @BeforeClass
        fun fetchAndVerifyPinnedFixtureOnce() {
            directory = Files.createTempDirectory("archivetune-ipadic-fixture-").toFile()
            artifact = File(directory, "kuromoji-ipadic-0.9.0.jar")
            try {
                val supplied = System.getenv("ARCHIVETUNE_TEST_IPADIC_JAR")?.takeIf { it.isNotBlank() }
                if (supplied != null) {
                    // Optional pre-provisioning is never trusted without the same byte/digest checks.
                    File(supplied).inputStream().use { copyBounded(it) }
                } else {
                    val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(15, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS)
                        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
                    try {
                        downloadCalls++
                        client.newCall(Request.Builder().url(SOURCE).build()).execute().use { response ->
                            check(response.code == 200) { "Pinned dictionary HTTP ${response.code}" }
                            val body = response.body ?: error("Missing pinned dictionary body")
                            check(body.contentLength() == -1L || body.contentLength() == EXPECTED_BYTES)
                            body.byteStream().use { copyBounded(it) }
                        }
                    } finally {
                        client.connectionPool.evictAll()
                        client.dispatcher.executorService.shutdown()
                    }
                }
                assertEquals(EXPECTED_BYTES, artifact.length())
                val digest = MessageDigest.getInstance("SHA-256")
                artifact.inputStream().buffered().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                assertEquals(SHA256, digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })
                println("VAL-JPACK-004 verified bytes=${artifact.length()} sha256=$SHA256 requiredResources=${REQUIRED_RESOURCES.joinToString()}")
            } catch (failure: Throwable) {
                artifact.delete()
                directory.delete()
                throw failure // No assume/Ignore/silent missing-fixture skip.
            }
        }

        private fun copyBounded(input: InputStream) {
            val start = System.nanoTime()
            var bytes = 0L
            artifact.outputStream().buffered().use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    check(System.nanoTime() - start <= TimeUnit.SECONDS.toNanos(180)) { "Fixture transfer deadline" }
                    val count = input.read(buffer)
                    if (count < 0) break
                    bytes += count
                    check(bytes <= EXPECTED_BYTES) { "Oversized pinned fixture" }
                    output.write(buffer, 0, count)
                }
            }
            check(bytes == EXPECTED_BYTES) { "Truncated pinned fixture" }
        }

        @JvmStatic @AfterClass
        fun removeOwnedFixture() {
            if (::artifact.isInitialized && artifact.exists()) check(artifact.delete())
            if (::directory.isInitialized && directory.exists()) check(directory.delete())
        }
    }
}
