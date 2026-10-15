/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * DeepL translation service. Ported from vivi-music
 * (https://github.com/vivizzz007/vivi-music) under GPL-3.0.
 *
 * Routes through api-free.deepl.com when the API key ends with `:fx` (free tier),
 * otherwise api.deepl.com. Supports per-line batch translation with optional
 * formality ("default" / "more" / "less"). Returns the same number of lines as
 * the input; incomplete responses fail rather than fabricating successful segments.
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import moe.rukamori.archivetune.utils.withAppProxy

object DeepLService {
    private val client =
        OkHttpClient
            .Builder()
            .withAppProxy()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    suspend fun translate(
        text: String,
        targetLanguage: String,
        apiKey: String,
        formality: String = "default",
        maxRetries: Int = 3,
    ): Result<List<String>> =
        withContext(Dispatchers.IO) {
            var currentAttempt = 0

            if (text.isBlank()) {
                return@withContext Result.failure(Exception("Input text is empty"))
            }

            val lines = text.lines()
            val lineCount = lines.size

            val deeplLangCode = targetCode(targetLanguage)
                ?: return@withContext Result.failure(IllegalArgumentException("Unsupported DeepL target language"))

            val baseUrl =
                if (apiKey.endsWith(":fx")) {
                    "https://api-free.deepl.com/v2/translate"
                } else {
                    "https://api.deepl.com/v2/translate"
                }

            while (currentAttempt < maxRetries) {
                try {
                    val jsonBody =
                        JSONObject().apply {
                            put(
                                "text",
                                JSONArray().apply {
                                    lines.forEach { put(it) }
                                },
                            )
                            put("target_lang", deeplLangCode)
                            if (formality != "default") {
                                put("formality", formality)
                            }
                            put("preserve_formatting", true)
                        }

                    val request =
                        Request
                            .Builder()
                            .url(baseUrl)
                            .addHeader("Authorization", "DeepL-Auth-Key ${apiKey.trim()}")
                            .addHeader("Content-Type", "application/json")
                            .post(jsonBody.toString().toRequestBody(JSON))
                            .build()

                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val response = client.newCall(request).execute()
                    val responseBody = response.use { it.body?.string() }
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()

                    if (!response.isSuccessful) {
                        // Retry on server errors (5xx)
                        if (response.code >= 500) {
                            currentAttempt++
                            kotlinx.coroutines.delay(1000L * currentAttempt)
                            continue
                        }

                        response.close()
                        return@withContext Result.failure(Exception("DeepL HTTP ${response.code}"))
                    }

                    if (responseBody == null) {
                        currentAttempt++
                        continue
                    }

                    val jsonResponse = JSONObject(responseBody)
                    val translations = jsonResponse.optJSONArray("translations")
                    if (translations != null && translations.length() > 0) {
                        val translatedLines =
                            (0 until translations.length()).map { i ->
                                translations.getJSONObject(i).opt("text") as? String ?: ""
                            }

                        if (translatedLines.size != lineCount || translatedLines.any { it.isBlank() }) {
                            return@withContext Result.failure(Exception("Incomplete DeepL response"))
                        }
                        return@withContext Result.success(translatedLines)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (currentAttempt == maxRetries - 1) {
                        return@withContext Result.failure(e)
                    }
                }
                currentAttempt++
                kotlinx.coroutines.delay(1000L * currentAttempt)
            }
            return@withContext Result.failure(Exception("Max retries exceeded"))
        }
    /** Explicit text-translation targets; picker names are never truncated into wire codes. */
    fun targetCode(language: String): String? {
        val normalized = language.trim().uppercase(java.util.Locale.ROOT).replace('_', '-').replace(' ', '-')
        val aliases = mapOf(
            "ARABIC" to "AR", "BULGARIAN" to "BG", "CZECH" to "CS", "DANISH" to "DA",
            "GERMAN" to "DE", "GREEK" to "EL", "ENGLISH" to "EN-US", "SPANISH" to "ES",
            "ESTONIAN" to "ET", "FINNISH" to "FI", "FRENCH" to "FR", "HUNGARIAN" to "HU",
            "INDONESIAN" to "ID", "ITALIAN" to "IT", "JAPANESE" to "JA", "KOREAN" to "KO",
            "LITHUANIAN" to "LT", "LATVIAN" to "LV", "NORWEGIAN" to "NB", "DUTCH" to "NL",
            "POLISH" to "PL", "PORTUGUESE" to "PT-PT", "ROMANIAN" to "RO", "RUSSIAN" to "RU",
            "SLOVAK" to "SK", "SLOVENIAN" to "SL", "SWEDISH" to "SV", "TURKISH" to "TR",
            "UKRAINIAN" to "UK", "CHINESE-SIMPLIFIED" to "ZH-HANS", "CHINESE-TRADITIONAL" to "ZH-HANT",
            "ZH-CN" to "ZH-HANS", "ZH-TW" to "ZH-HANT", "EN" to "EN-US", "PT" to "PT-PT",
        )
        val codes = setOf("AR", "BG", "CS", "DA", "DE", "EL", "EN-US", "EN-GB", "ES", "ET", "FI",
            "FR", "HU", "ID", "IT", "JA", "KO", "LT", "LV", "NB", "NL", "PL", "PT-PT", "PT-BR",
            "RO", "RU", "SK", "SL", "SV", "TR", "UK", "ZH", "ZH-HANS", "ZH-HANT")
        return aliases[normalized] ?: normalized.takeIf { it in codes }
    }

}
