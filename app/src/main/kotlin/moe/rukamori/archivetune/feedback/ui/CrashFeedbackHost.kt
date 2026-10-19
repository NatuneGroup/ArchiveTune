/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.feedback.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.feedback.CrashFeedback
import moe.rukamori.archivetune.feedback.FeedbackApi
import moe.rukamori.archivetune.feedback.FeedbackDrafts

/**
 * Next-launch crash feedback prompt. Reads nothing eagerly: on builds without a
 * configured feedback endpoint this host renders nothing and no crash file is
 * ever written by [CrashFeedback.capture]. "Later" keeps the stored report for
 * the next launch; deleting it is always explicit.
 */
@Composable
fun CrashFeedbackPromptHost() {
    if (!FeedbackApi.isAvailable()) return
    val context = LocalContext.current
    var checked by remember { mutableStateOf(false) }
    var showPrompt by remember { mutableStateOf(false) }
    var showReviewer by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val stored = withContext(Dispatchers.IO) { CrashFeedback.storedReport(context) }
        checked = true
        showPrompt = stored != null
    }

    if (!checked) return
    if (showReviewer) {
        FeedbackReviewDialog(
            title = stringResource(R.string.feedback_review_title),
            loadDraft = { FeedbackDrafts.crash(context) },
            onDismiss = { showReviewer = false },
        )
        // While the reviewer is open the underlying prompt is parked, not dismissed.
        return
    }
    if (!showPrompt) return
    AlertDialog(
        onDismissRequest = { showPrompt = false },
        title = { Text(stringResource(R.string.crash_prompt_title)) },
        text = { Text(stringResource(R.string.crash_prompt_text)) },
        confirmButton = {
            TextButton(onClick = { showReviewer = true }) {
                Text(stringResource(R.string.crash_prompt_review))
            }
        },
        dismissButton = {
            // Deleting the stored report is the explicit "do not send" action;
            // silently dismissing only skips the prompt for this session.
            TextButton(onClick = { CrashFeedback.clear(context) }) {
                Text(stringResource(R.string.crash_prompt_delete))
            }
            TextButton(onClick = { showPrompt = false }) {
                Text(stringResource(R.string.later))
            }
        },
    )
}
