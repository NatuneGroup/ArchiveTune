/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * OpenRouter translation service. Ported from vivi-music
 * (https://github.com/vivizzz007/vivi-music) under GPL-3.0.
 *
 * OpenRouter is an OpenAI-compatible aggregator: any model on openrouter.ai/quotes
 * can be used by passing its model id (e.g. "openai/gpt-4o-mini", "anthropic/claude-3.5-sonnet",
 * "google/gemini-flash-1.5"). The base URL and model are user-configurable so users can point at
 * any OpenAI-compatible endpoint (Ollama, vLLM, LM Studio, etc.) — when the base URL is overridden,
 * the request is sent verbatim with a Bearer API key.
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import moe.rukamori.archivetune.utils.withAppProxy

object OpenRouterService {
    private val client =
        OkHttpClient
            .Builder()
            .withAppProxy()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1/chat/completions"

    suspend fun translate(
        text: String,
        targetLanguage: String,
        apiKey: String,
        baseUrl: String,
        model: String,
        mode: String,
        maxRetries: Int = 3,
        sourceLanguage: String? = null,
    ): Result<List<String>> =
        withContext(Dispatchers.IO) {
            if (text.isBlank()) return@withContext Result.failure(Exception("Input text is empty"))

            val lines = text.lines()
            val lineCount = lines.size

            val systemPrompt =
                if (mode == "romanize") {
                    val src = sourceLanguage?.takeIf { it.isNotBlank() }?.let { " from $it" } ?: ""
                    "You are a romanization assistant. Convert each line of the following lyrics$src into the Latin alphabet. " +
                        "Preserve line breaks exactly. Do not translate meaning; only transliterate. Output only the romanized lines."
                } else {
                    val src = sourceLanguage?.takeIf { it.isNotBlank() }?.let { " from $it" } ?: ""
                    "You are a translation assistant. Translate each line of the following lyrics$src into $targetLanguage. " +
                        "Preserve line breaks exactly. Output only the translated lines, one per input line, no numbering."
                }

            val userContent =
                buildString {
                    append("Translate each line below. Keep the same number of lines.\n\n")
                    lines.forEachIndexed { idx, line -> append("${idx + 1}. ${line}\n") }
                }

            val endpoint =
                if (baseUrl.isBlank()) DEFAULT_BASE_URL
                else if (baseUrl.endsWith("/")) baseUrl + "chat/completions"
                else if (baseUrl.endsWith("/chat/completions")) baseUrl
                else if (baseUrl.endsWith("/v1")) baseUrl + "/chat/completions"
                else baseUrl + "/v1/chat/completions"

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
                            .url(endpoint)
                            .addHeader("Authorization", "Bearer ${apiKey.trim()}")
                            .addHeader("Content-Type", "application/json")
                            .addHeader("HTTP-Referer", "https://github.com/vossgraves/ArchiveTune")
                            .addHeader("X-Title", "ArchiveTune")
                            .post(jsonBody.toString().toRequestBody(JSON))
                            .build()

                    val response = client.newCall(request).execute()
                    val responseBody = response.body?.string()
                    if (!response.isSuccessful) {
                        if (response.code >= 500) {
                            currentAttempt++
                            kotlinx.coroutines.delay(1000L * currentAttempt)
                            continue
                        }
                        val msg =
                            try {
                                JSONObject(responseBody ?: "").optString("error")
                                    ?: JSONObject(responseBody ?: "").optString("message")
                                    ?: "HTTP ${response.code}"
                            } catch (e: Exception) {
                                "HTTP ${response.code}"
                            }
                        return@withContext Result.failure(Exception("OpenRouter translation failed: $msg"))
                    }
                    if (responseBody == null) {
                        currentAttempt++
                        continue
                    }

                    val content =
                        JSONObject(responseBody)
                            .optJSONArray("choices")
                            ?.optJSONObject(0)
                            ?.optJSONObject("message")
                            ?.optString("content")
                            .orEmpty()
                            .trim()

                    val translatedLines = content.split("\n").map { it.trim() }
                    return@withContext Result.success(alignLines(translatedLines, lineCount))
                } catch (e: Exception) {
                    if (currentAttempt == maxRetries - 1) return@withContext Result.failure(e)
                }
                currentAttempt++
                kotlinx.coroutines.delay(1000L * currentAttempt)
            }
            return@withContext Result.failure(Exception("Max retries exceeded"))
        }

    private fun alignLines(
        translated: List<String>,
        expected: Int,
    ): List<String> =
        when {
            translated.size == expected -> translated
            translated.size > expected -> translated.take(expected)
            else -> translated.toMutableList().apply { while (size < expected) add("") }
        }
}
