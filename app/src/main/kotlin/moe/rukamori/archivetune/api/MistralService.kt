/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Mistral AI translation service. Ported from vivi-music
 * (https://github.com/vivizzz007/vivi-music) under GPL-3.0.
 *
 * Uses Mistral's chat-completions endpoint (https://api.mistral.ai/v1/chat/completions)
 * to translate or romanize lyric lines. The model is configurable via AiSelectedModelKey;
 * the default is "mistral-small-latest".
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
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

object MistralService {
    private val client =
        OkHttpClient
            .Builder()
            .withAppProxy()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private const val BASE_URL = "https://api.mistral.ai/v1/chat/completions"

    suspend fun translate(
        text: String,
        targetLanguage: String,
        apiKey: String,
        model: String = "mistral-small-latest",
        mode: String = "translate",
        maxRetries: Int = 3,
        sourceLanguage: String? = null,
    ): Result<List<String>> =
        withContext(Dispatchers.IO) {
            if (text.isBlank()) return@withContext Result.failure(Exception("Input text is empty"))

            val lines = text.lines()
            val lineCount = lines.size

            val systemPrompt =
                if (mode == "romanize") {
                    val src =
                        sourceLanguage?.takeIf { it.isNotBlank() }?.let { " from $it" } ?: ""
                    "You are a romanization assistant. Convert each line of the following lyrics$src into the Latin alphabet. " +
                        "Preserve line breaks exactly. Do not translate meaning; only transliterate. Output only the romanized lines."
                } else {
                    val src =
                        sourceLanguage?.takeIf { it.isNotBlank() }?.let { " from $it" } ?: ""
                    "You are a translation assistant. Translate each line of the following lyrics$src into $targetLanguage. " +
                        "Preserve line breaks exactly. Output only the translated lines, one per input line, no numbering."
                }

            val userContent =
                buildString {
                    append("Translate each line below. Keep the same number of lines.\n\n")
                    lines.forEachIndexed { idx, line -> append("${idx + 1}. ${line}\n") }
                }

            var currentAttempt = 0
            while (currentAttempt < maxRetries) {
                try {
                    val jsonBody =
                        JSONObject().apply {
                            put("model", model)
                            put(
                                "messages",
                                JSONArray().apply {
                                    put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                                    put(JSONObject().apply { put("role", "user"); put("content", userContent) })
                                },
                            )
                            put("temperature", 0.2)
                        }

                    val request =
                        Request
                            .Builder()
                            .url(BASE_URL)
                            .addHeader("Authorization", "Bearer ${apiKey.trim()}")
                            .addHeader("Content-Type", "application/json")
                            .post(jsonBody.toString().toRequestBody(JSON))
                            .build()

                    currentCoroutineContext().ensureActive()
                    val response = client.newCall(request).execute()
                    val responseBody = response.use { it.body?.string() }
                    currentCoroutineContext().ensureActive()
                    if (!response.isSuccessful) {
                        if (response.code >= 500) {
                            currentAttempt++
                            kotlinx.coroutines.delay(1000L * currentAttempt)
                            continue
                        }
                        return@withContext Result.failure(Exception("Mistral HTTP ${response.code}"))
                    }
                    if (responseBody == null) {
                        currentAttempt++
                        continue
                    }

                    val content =
                        (JSONObject(responseBody)
                            .optJSONArray("choices")
                            ?.optJSONObject(0)
                            ?.optJSONObject("message")
                            ?.opt("content") as? String)
                            .orEmpty()
                            .trim()

                    val translatedLines = content.split("\n").map { it.trim() }
                    if (translatedLines.size != lineCount || translatedLines.any { it.isBlank() }) {
                        return@withContext Result.failure(Exception("Incomplete Mistral response"))
                    }
                    return@withContext Result.success(translatedLines)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (currentAttempt == maxRetries - 1) return@withContext Result.failure(e)
                }
                currentAttempt++
                kotlinx.coroutines.delay(1000L * currentAttempt)
            }
            return@withContext Result.failure(Exception("Max retries exceeded"))
        }

}
