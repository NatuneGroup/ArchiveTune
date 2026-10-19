/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.feedback

import android.content.Context
import android.os.Build
import android.util.Log
import moe.rukamori.archivetune.BuildConfig
import moe.rukamori.archivetune.logcat.unredactedDiagnosticReport

/**
 * A reviewable feedback draft: the exact text the user sees before a send, plus the
 * kind used for the server envelope. Building runs off the main thread and never
 * touches the server; that decision stays with the user.
 */
data class FeedbackDraft(
    val kind: String,
    /** The on-screen report body, already redacted and bounded by [FeedbackReport.finish]. */
    val report: String,
)

/**
 * Assembles drafts from the pieces every feedback surface shows. Each draft is built as
 * one text and goes through [FeedbackReport.finish] once, so redaction and the size bound
 * cover the whole report rather than individual sections.
 */
object FeedbackDrafts {
    private const val TAG = "FeedbackDrafts"

    fun appIdentity(): FeedbackReport.AppIdentity =
        FeedbackReport.AppIdentity(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            forkRevision = BuildConfig.FORK_REVISION,
            forkBranch = BuildConfig.FORK_BRANCH,
            variant =
                BuildConfig.DISTRIBUTION + "/" + BuildConfig.DEVICE + "/" +
                    BuildConfig.ARCHITECTURE + "/" + BuildConfig.BUILD_TYPE,
        )

    fun deviceIdentity(): FeedbackReport.DeviceIdentity =
        FeedbackReport.DeviceIdentity(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            osRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
        )

    /** Header of playback drafts and crash capture files. */
    fun header(): String = FeedbackReport.header(appIdentity(), deviceIdentity(), BuildConfig.FORK_DIRTY)

    /** The JSON envelope a reviewed draft becomes when the user presses send. */
    fun envelope(draft: FeedbackDraft, note: String, sentAt: String): String =
        FeedbackReport.json(
            kind = draft.kind,
            sentAt = sentAt,
            app = appIdentity(),
            device = deviceIdentity(),
            note = note,
            report = draft.report,
        )

    suspend fun playback(context: Context, errorDetails: String): FeedbackDraft =
        FeedbackDraft(
            kind = FeedbackReport.KIND_PLAYBACK,
            report =
                FeedbackReport.finish(
                    buildString {
                        appendLine(header())
                        appendLine()
                        appendLine("Report kind: playback error")
                        appendLine()
                        appendLine("Playback error details (as shown in the dialog):")
                        appendLine(errorDetails)
                        appendLine()
                        appendLine(FeedbackReport.LOG_HEADING)
                        append(diagnosticOrEmpty(context))
                    },
                ),
        )

    suspend fun crash(context: Context): FeedbackDraft? {
        val stored = CrashFeedback.storedReport(context) ?: return null
        return FeedbackDraft(
            kind = FeedbackReport.KIND_CRASH,
            report =
                FeedbackReport.finish(
                    buildString {
                        appendLine(stored)
                        appendLine()
                        appendLine(FeedbackReport.LOG_HEADING)
                        append(diagnosticOrEmpty(context))
                    },
                ),
        )
    }

    /**
     * Unredacted on purpose: [FeedbackReport.finish] redacts the whole draft once. If the
     * diagnostics cannot be read (DataStore stalled, capture file unreadable) the draft
     * keeps the sections that did build instead of failing the review.
     */
    private suspend fun diagnosticOrEmpty(context: Context): String =
        try {
            unredactedDiagnosticReport(context, "")
        } catch (e: Exception) {
            Log.e(TAG, "diagnostic report unavailable", e)
            "(unavailable)"
        }
}
