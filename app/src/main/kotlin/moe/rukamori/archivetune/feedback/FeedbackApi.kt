/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.feedback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.BuildConfig
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
     * Sends an already-assembled [moe.rukamori.archivetune.feedback.FeedbackReport] JSON
     * envelope. Never retries silently: a network failure is reported back so the
     * dialog can tell the user the report was not delivered.
     */
    suspend fun send(envelopeJson: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (!isAvailable()) {
                return@withContext Result.failure(IllegalStateException("Feedback service not configured"))
            }
            runCatching {
                val request =
                    Request
                        .Builder()
                        .url(trimmedUrl())
                        .addHeader("X-Ingest-Key", BuildConfig.FEEDBACK_INGEST_KEY.trim())
                        .post(envelopeJson.toRequestBody(JSON))
                        .build()
                client.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> {}
                        response.code == 429 -> throw FeedbackRejection("Rate limited, try again later")
                        response.code in 400..499 -> {
                            val reason =
                                response.body?.string()?.take(200)?.takeIf(String::isNotBlank)
                            throw FeedbackRejection(reason ?: "Report rejected (${response.code})")
                        }
                        else -> throw FeedbackRejection("Service unavailable (${response.code})")
                    }
                }
            }
        }

    private fun trimmedUrl(): String = BuildConfig.FEEDBACK_API_URL.trim().trimEnd('/') + PATH

    /**
     * Deliberately not a network failure: the server accepted the transport but
     * refused the payload or key, which the dialog must surface verbatim.
     */
    class FeedbackRejection(message: String) : Exception(message)
}
