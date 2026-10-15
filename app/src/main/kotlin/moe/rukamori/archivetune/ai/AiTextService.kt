/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ai

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import moe.rukamori.archivetune.api.DeepLService
import moe.rukamori.archivetune.api.MistralService
import moe.rukamori.archivetune.constants.AiProvider
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import moe.rukamori.archivetune.utils.withAppProxy

open class AiServiceException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

object AiTextService {
    private const val TAG = "AiTextService"
    private const val OpenAiEndpoint = "https://api.openai.com/v1/chat/completions"
    private const val OpenAiModelsEndpoint = "https://api.openai.com/v1/models"
    private const val OpenRouterEndpoint = "https://openrouter.ai/api/v1/chat/completions"
    private const val OpenRouterModelsEndpoint = "https://openrouter.ai/api/v1/models"
    private const val GeminiBaseEndpoint = "https://generativelanguage.googleapis.com/v1beta"

    private fun openRouterCompletionEndpoint(baseUrl: String): String {
        val value = baseUrl.trim().trimEnd('/')
        return when {
            value.isBlank() -> OpenRouterEndpoint
            value.endsWith("/chat/completions") -> value
            value.endsWith("/v1") -> "$value/chat/completions"
            else -> "$value/v1/chat/completions"
        }
    }

    private fun openRouterModelsEndpoint(baseUrl: String): String {
        val value = baseUrl.trim().trimEnd('/')
        return when {
            value.isBlank() -> OpenRouterModelsEndpoint
            value.endsWith("/models") -> value
            value.endsWith("/chat/completions") -> value.removeSuffix("/chat/completions") + "/models"
            value.endsWith("/v1") -> "$value/models"
            else -> "$value/v1/models"
        }
    }

    /**
     * OkHttp's connection pool can enter a bad state after sustained use (stale sockets, SSL
     * session cache misses, half-closed connections from a server-side idle timeout).
     */
    private val clientHolder = AtomicReference<HttpClient>(createClient())

    private fun createClient(): HttpClient =
        HttpClient(OkHttp) {
            engine {
                config {
                    withAppProxy()
                    connectTimeout(20, TimeUnit.SECONDS)
                    readTimeout(60, TimeUnit.SECONDS)
                    writeTimeout(60, TimeUnit.SECONDS)
                    retryOnConnectionFailure(true)
                    // Aggressively evict idle connections so stale sockets don't accumulate
                    // in the pool between translation batches. 30s is below typical
                    // server-side idle timeouts (60-120s), so connections get reused
                    // within a song but evicted before they go stale.
                    // ConnectionPool(maxIdleConnections, keepAliveDuration, unit).
                    connectionPool(
                        okhttp3.ConnectionPool(5, 30, TimeUnit.SECONDS),
                    )
                }
            }
        }

    private val client: HttpClient get() = clientHolder.get()

    /**
     * Recreates the HttpClient. Called when a connection-level failure is detected
     * (IOException that smells like a stale pool — SocketTimeoutException,
     * ConnectException, SSLException, etc.). The old client is closed asynchronously
     * to avoid blocking the caller.
     */
    private fun recreateClientOnFailure(t: Throwable) {
        val oldClient = clientHolder.getAndSet(createClient())
        Log.w(TAG, "Recreated HttpClient after connection failure: ${t.javaClass.simpleName}")
        // Close the old client asynchronously — close() is blocking because it evicts
        // the connection pool. We don't want to stall the translation coroutine.
        Thread {
            runCatching { oldClient.close() }
        }.start()
    }

    /**
     * Returns true if the throwable indicates a connection-level failure that warrants
     * recreating the HttpClient. HTTP 4xx/5xx responses do NOT count — those are
     * application-level errors from a healthy connection.
     */
    private fun isConnectionLevelFailure(t: Throwable): Boolean =
        when (t) {
            is java.net.SocketTimeoutException -> true
            is java.net.ConnectException -> true
            is java.net.SocketException -> true
            is javax.net.ssl.SSLException -> true
            is java.net.UnknownHostException -> true
            is java.io.IOException -> true
            else -> {
                // Ktor wraps IOException in HttpRequestTimeoutException and other
                // engine-specific exceptions; check the cause chain.
                val cause = t.cause
                cause != null && cause !== t && isConnectionLevelFailure(cause)
            }
        }


    suspend fun test(config: AiServiceConfig) {
        val response =
            complete(
                config = config.copy(model = config.model.ifBlank { defaultModelFor(config.provider) }),
                systemPrompt = "You are a health check endpoint. Reply with OK only.",
                userPrompt = "Reply exactly OK.",
                temperature = 0.0,
                maxTokens = 32,
            ).trim()
        if (!response.contains("OK", ignoreCase = true)) {
            throw AiServiceException("AI API returned an unexpected test response")
        }
    }

    suspend fun translateLines(
        config: AiServiceConfig,
        targetLanguage: String,
        lines: List<String>,
        formatName: String,
    ): List<String> {
        if (lines.isEmpty()) return emptyList()
        when (config.provider) {
            AiProvider.DEEPL ->
                return AiRateLimiter.withLimit(AiRateLimiter.Feature.LYRICS_TRANSLATION) {
                    DeepLService
                        .translate(
                            text = lines.joinToString("\n"),
                            targetLanguage = targetLanguage,
                            apiKey = config.apiKey,
                            formality = config.deeplFormality,
                        ).getOrElse { throw AiServiceException("DeepL translation failed", it) }
                        .also { translated ->
                            if (translated.size != lines.size) {
                                throw AiServiceException("DeepL response changed the lyric segment count")
                            }
                        }
                }
            AiProvider.MISTRAL ->
                return AiRateLimiter.withLimit(AiRateLimiter.Feature.LYRICS_TRANSLATION) {
                    MistralService
                        .translate(
                            text = lines.joinToString("\n"),
                            targetLanguage = targetLanguage,
                            apiKey = config.apiKey,
                            model = config.model.ifBlank { "mistral-small-latest" },
                        ).getOrElse { throw AiServiceException("Mistral translation failed", it) }
                        .also { translated ->
                            if (translated.size != lines.size) {
                                throw AiServiceException("Mistral response changed the lyric segment count")
                            }
                        }
                }
            else -> Unit
        }
        val payload = JSONArray()
        lines.forEach { payload.put(it) }
        val response =
            try {
                AiRateLimiter.withLimit(AiRateLimiter.Feature.LYRICS_TRANSLATION) {
                    complete(
                        config = config,
                        systemPrompt =
                            """
                            You are an expert song lyrics translator.
                            Translate each input string into $targetLanguage with natural, accurate lyric phrasing.
                            Preserve meaning, tone, profanity level, names, repeated hooks, and line-level intent.
                            Do not add timestamps, IDs, XML, markdown, explanations, or extra lines.
                            Return only a JSON array of strings with exactly ${lines.size} items in the same order.
                            The caller will reconstruct the $formatName lyrics container separately.
                            """.trimIndent(),
                        userPrompt = payload.toString(),
                        temperature = 0.15,
                        maxTokens = 8192,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // Connection-level failures (SocketTimeout, ConnectException, SSLException,
                // IOException) suggest the OkHttp connection pool has gone stale. Recreate
                // the client so the NEXT call starts fresh — this prevents the "auto-translate
                // stops working after a few songs" cascade where every subsequent request
                // fails on the same stale pool.
                if (isConnectionLevelFailure(t)) {
                    recreateClientOnFailure(t)
                }
                throw t
            }
        val array = extractJsonArray(response)
        require(array.length() == lines.size) { "AI response changed the lyric segment count" }
        return List(array.length()) { index ->
            (array.get(index) as? String)?.takeIf { it.isNotBlank() }
                ?: throw AiServiceException("AI returned an invalid lyric segment")
        }
    }

    /** Transliterates [lines] into the Latin alphabet, one output string per input string. */
    suspend fun romanizeLines(
        config: AiServiceConfig,
        lines: List<String>,
        formatName: String,
    ): List<String> {
        if (lines.isEmpty()) return emptyList()
        if (!config.supportsRomanization) {
            throw AiServiceException("Provider does not support lyrics romanization")
        }
        if (config.provider == AiProvider.MISTRAL) {
            return AiRateLimiter.withLimit(AiRateLimiter.Feature.LYRICS_ROMANIZATION) {
                MistralService
                    .translate(
                        text = lines.joinToString("\n"),
                        targetLanguage = "Latin",
                        apiKey = config.apiKey,
                        model = config.model.ifBlank { "mistral-small-latest" },
                        mode = "romanize",
                    ).getOrElse { throw AiServiceException("Mistral romanization failed", it) }
                    .also { romanized ->
                        if (romanized.size != lines.size) {
                            throw AiServiceException("Mistral response changed the romanization segment count")
                        }
                    }
            }
        }
        val payload = JSONArray()
        lines.forEach { payload.put(it) }
        val response =
            try {
                AiRateLimiter.withLimit(AiRateLimiter.Feature.LYRICS_ROMANIZATION) {
                    complete(
                        config = config,
                        systemPrompt =
                            """
                            You are an expert lyrics romanisation (transliteration) assistant.
                            Transliterate each input string into the Latin alphabet exactly as it is sung.
                            DO NOT TRANSLATE. Never convey meaning — only how the words sound.
                            Use the standard scheme for the script: Hepburn for Japanese, Revised
                            Romanization for Korean, Hanyu Pinyin without tone marks for Chinese,
                            IAST-style for Devanagari, and the common romanisation otherwise.
                            Keep the original word order, punctuation and casing style.
                            If a line is already written in the Latin alphabet, return it unchanged.
                            Do not add timestamps, IDs, XML, markdown, explanations, or extra lines.
                            Return only a JSON array of strings with exactly ${lines.size} items in the same order.
                            The caller will reconstruct the $formatName lyrics container separately.
                            """.trimIndent(),
                        userPrompt = payload.toString(),
                        // Lower than translation's 0.15: transliteration has one right answer, and
                        // sampling variance here only produces inconsistent spellings between lines.
                        temperature = 0.0,
                        maxTokens = 8192,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // See translateLines: a connection-level failure means the pooled connection has
                // likely gone stale, and reusing it would fail every subsequent track the same way.
                if (isConnectionLevelFailure(t)) {
                    recreateClientOnFailure(t)
                }
                throw t
            }
        val array = extractJsonArray(response)
        require(array.length() == lines.size) { "AI response changed the lyric segment count" }
        return List(array.length()) { index ->
            (array.get(index) as? String)?.takeIf { it.isNotBlank() }
                ?: throw AiServiceException("AI returned an invalid lyric segment")
        }
    }

    suspend fun complete(
        config: AiServiceConfig,
        systemPrompt: String,
        userPrompt: String,
        temperature: Double = 0.2,
        maxTokens: Int = 4096,
    ): String {
        if (!config.canCallApi) throw AiServiceException("AI provider is not configured")
        val model = config.model.ifBlank { defaultModelFor(config.provider) }
        return when (config.provider) {
            AiProvider.CHATGPT -> {
                completeOpenAiCompatible(
                    endpoint = OpenAiEndpoint,
                    apiKey = config.apiKey,
                    model = model,
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    temperature = temperature,
                    maxTokens = maxTokens,
                )
            }

            AiProvider.OPENROUTER -> {
                completeOpenAiCompatible(
                    endpoint = openRouterCompletionEndpoint(config.customEndpoint),
                    apiKey = config.apiKey,
                    model = model,
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    temperature = temperature,
                    maxTokens = maxTokens,
                )
            }

            AiProvider.CUSTOM -> {
                completeOpenAiCompatible(
                    endpoint = config.customEndpoint,
                    apiKey = config.apiKey,
                    model = model,
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    temperature = temperature,
                    maxTokens = maxTokens,
                )
            }


            AiProvider.GEMINI -> {
                completeGemini(
                    apiKey = config.apiKey,
                    model = model,
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    temperature = temperature,
                    maxTokens = maxTokens,
                )
            }

            // DeepL / Mistral are translation-only providers (not generic chat completion).
            // AiTextService is used for AI Mix / Wrapped / chat-style prompts, so these
            // providers throw — translation calls go through LyricsTranslationHelper instead.
            AiProvider.DEEPL,
            AiProvider.MISTRAL,
            -> {
                throw AiServiceException("${config.provider.name} is a translation-only provider; use LyricsTranslationHelper for translation calls")
            }

            AiProvider.NONE -> {
                throw AiServiceException("AI provider is disabled")
            }
        }
    }

    suspend fun fetchModels(config: AiServiceConfig): List<AiModelOption> {
        if (!config.canCallApi) throw AiServiceException("AI provider is not configured")
        return when (config.provider) {
            AiProvider.CHATGPT -> fetchOpenAiModels(OpenAiModelsEndpoint, config.apiKey)
            AiProvider.OPENROUTER ->
                fetchOpenAiModels(
                    openRouterModelsEndpoint(config.customEndpoint),
                    config.apiKey,
                )
            AiProvider.GEMINI -> fetchGeminiModels(config.apiKey)
            // DeepL / Mistral have no models-list endpoint exposed in this service.
            AiProvider.DEEPL, AiProvider.MISTRAL, AiProvider.CUSTOM, AiProvider.NONE -> emptyList()
        }
    }

    private suspend fun completeOpenAiCompatible(
        endpoint: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        temperature: Double,
        maxTokens: Int,
    ): String {
        val messages =
            JSONArray()
                .put(JSONObject().put("role", "system").put("content", systemPrompt))
                .put(JSONObject().put("role", "user").put("content", userPrompt))
        val body =
            JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("temperature", temperature)
                .put("max_tokens", maxTokens)
                .toString()
        val response =
            client.post(endpoint.trim()) {
                header("Authorization", "Bearer ${apiKey.trim()}")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        val raw = response.bodyAsText()
        if (response.status.value !in 200..299) throw apiException(response.status.value)
        val json = JSONObject(raw)
        val content =
            json
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.takeIf { it.isNotBlank() }
        return content ?: throw AiServiceException("AI API returned an empty response")
    }

    private suspend fun completeGemini(
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        temperature: Double,
        maxTokens: Int,
    ): String {
        val endpoint = "$GeminiBaseEndpoint/models/${model.trim()}:generateContent"
        val body =
            JSONObject()
                .put(
                    "systemInstruction",
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", systemPrompt)),
                    ),
                )
                .put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put(
                            "role",
                            "user",
                        ).put(
                            "parts",
                            JSONArray().put(
                                JSONObject().put("text", userPrompt),
                            ),
                        ),
                    ),
                ).put(
                    "generationConfig",
                    JSONObject()
                        .put("temperature", temperature)
                        .put("maxOutputTokens", maxTokens),
                ).toString()
        val response =
            client.post(endpoint) {
                header("x-goog-api-key", apiKey.trim())
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        val raw = response.bodyAsText()
        if (response.status.value !in 200..299) throw apiException(response.status.value)
        val content =
            JSONObject(raw)
                .optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")
                ?.takeIf { it.isNotBlank() }
        return content ?: throw AiServiceException("AI API returned an empty response")
    }

    private fun defaultModelFor(provider: AiProvider): String =
        when (provider) {
            AiProvider.CHATGPT -> "gpt-4o"
            AiProvider.GEMINI -> "gemini-3.5-flash"
            AiProvider.MISTRAL -> "mistral-small-latest"
            AiProvider.OPENROUTER -> "~openai/gpt-latest"
            AiProvider.CUSTOM -> throw AiServiceException("No AI model configured")
            // DeepL doesn't use a model picker (the API key determines the tier).
            AiProvider.DEEPL, AiProvider.NONE -> throw AiServiceException("AI provider is disabled")
        }

    private suspend fun fetchOpenAiModels(
        endpoint: String,
        apiKey: String,
    ): List<AiModelOption> {
        val response =
            client.get(endpoint) {
                header("Authorization", "Bearer ${apiKey.trim()}")
            }
        val raw = response.bodyAsText()
        if (response.status.value !in 200..299) throw apiException(response.status.value)
        val data = JSONObject(raw).optJSONArray("data") ?: return emptyList()
        return buildList {
            for (i in 0 until data.length()) {
                val obj = data.optJSONObject(i) ?: continue
                val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
                add(AiModelOption(id = id, displayName = id))
            }
        }.sortedBy { it.id }
    }

    private suspend fun fetchGeminiModels(apiKey: String): List<AiModelOption> {
        val response = client.get("$GeminiBaseEndpoint/models?key=${apiKey.trim()}")
        val raw = response.bodyAsText()
        if (response.status.value !in 200..299) throw apiException(response.status.value)
        val models = JSONObject(raw).optJSONArray("models") ?: return emptyList()
        return buildList {
            for (i in 0 until models.length()) {
                val obj = models.optJSONObject(i) ?: continue
                val methods = obj.optJSONArray("supportedGenerationMethods")
                val supportsGenerate =
                    (0 until (methods?.length() ?: 0)).any {
                        methods?.optString(it) == "generateContent"
                    }
                if (!supportsGenerate) continue
                val id = obj.optString("name").removePrefix("models/").takeIf { it.isNotBlank() } ?: continue
                val displayName = obj.optString("displayName").ifBlank { id }
                add(AiModelOption(id = id, displayName = displayName))
            }
        }
    }


    private fun apiException(status: Int): AiServiceException =
        AiServiceException("AI API failed (HTTP $status)")
}
