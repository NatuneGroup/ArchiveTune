/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.feedback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.rukamori.archivetune.BuildConfig
import moe.rukamori.archivetune.audiosource.executeCancellable
import moe.rukamori.archivetune.utils.withAppProxy
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Transport for the opt-in Natune feedback service. Endpoints are empty by
 * default, which keeps the whole feature (playback-error feedback, crash report
 * prompts, and crash capture writing) hidden; CI or a local build supplies
 * FEEDBACK_API_URL / FEEDBACK_INGEST_KEY to turn it on.
 *
 * The ingest key is baked into the APK and is therefore public by nature: the
 * server treats it as a rate-limit/container key that may only create reports
 * and can never read, list or delete stored ones.
 */
object FeedbackApi {
    private const val PATH = "/api/report"

    private val client =
        OkHttpClient
            .Builder()
            .withAppProxy()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** Blank URL or key on a build leaves every feedback surface unrendered. */
    fun isAvailable(): Boolean =
        BuildConfig.FEEDBACK_API_URL.isNotBlank() && BuildConfig.FEEDBACK_INGEST_KEY.isNotBlank()

    /**
     * Posts an already-assembled [FeedbackReport] envelope. Cancelling the caller cancels
     * the HTTP call too, so a dismissed review never keeps uploading. Never retries silently:
     * a failure is returned so the dialog can say the report was not delivered.
     */
    suspend fun send(envelopeJson: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (!isAvailable()) {
                return@withContext Result.failure(IllegalStateException("Feedback service not configured"))
            }
            try {
                val request =
                    Request
                        .Builder()
                        .url(trimmedUrl())
                        .addHeader("X-Ingest-Key", BuildConfig.FEEDBACK_INGEST_KEY.trim())
                        .post(envelopeJson.toRequestBody(JSON))
                        .build()
                client.newCall(request).executeCancellable().use { response ->
                    when {
                        response.isSuccessful -> Unit
                        response.code == 429 -> throw FeedbackRejection("Rate limited, try again later")
                        response.code in 400..499 ->
                            throw FeedbackRejection(
                                serverError(response.body?.string()) ?: "Report rejected (${response.code})",
                            )
                        else -> throw FeedbackRejection("Service unavailable (${response.code})")
                    }
                }
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** The server's `error` string for a rejected request; the raw body is never shown. */
    private fun serverError(body: String?): String? =
        runCatching { Json.parseToJsonElement(body.orEmpty()).jsonObject["error"]?.jsonPrimitive?.content }
            .getOrNull()
            ?.takeIf(String::isNotBlank)

    private fun trimmedUrl(): String = BuildConfig.FEEDBACK_API_URL.trim().trimEnd('/') + PATH

    /**
     * Deliberately not a network failure: the server accepted the transport but
     * refused the payload or key, which the dialog must surface verbatim.
     */
    class FeedbackRejection(message: String) : Exception(message)
}
