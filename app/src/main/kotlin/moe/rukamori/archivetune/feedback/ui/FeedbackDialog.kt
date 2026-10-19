/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.feedback.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.LocalAnimationsDisabled
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.feedback.FeedbackApi
import moe.rukamori.archivetune.feedback.FeedbackDraft
import moe.rukamori.archivetune.feedback.FeedbackDrafts
import moe.rukamori.archivetune.feedback.FeedbackReport
import moe.rukamori.archivetune.ui.component.DefaultDialog
import java.time.Instant

/**
 * The single review-before-send surface for every feedback report. [loadDraft] builds the
 * text off the main thread and it is shown verbatim, so the user reads exactly what Send
 * posts. Only Send transmits: closing never does, and a failed send keeps the draft on
 * screen. [onSent] runs after a delivered report and defaults to [onDismiss].
 */
@Composable
fun FeedbackReviewDialog(
    title: String,
    loadDraft: suspend () -> FeedbackDraft?,
    onDismiss: () -> Unit,
    onSent: () -> Unit = onDismiss,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sentText = stringResource(R.string.feedback_sent)
    val animationsDisabled = LocalAnimationsDisabled.current
    var draft by remember { mutableStateOf<FeedbackDraft?>(null) }
    var buildFailed by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        draft = withContext(Dispatchers.IO) { loadDraft() }
        buildFailed = draft == null
    }

    val status =
        when {
            buildFailed -> ReviewStatus.Failed
            draft == null -> ReviewStatus.Preparing
            else -> ReviewStatus.Ready
        }

    DefaultDialog(
        onDismiss = { if (!sending) onDismiss() },
        title = { Text(title) },
        horizontalAlignment = Alignment.Start,
        contentScrollable = true,
        buttons = {
            TextButton(enabled = !sending, onClick = onDismiss) {
                Text(stringResource(R.string.later))
            }
            if (sending) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else {
                Button(
                    enabled = draft != null,
                    onClick = {
                        val ready = draft ?: return@Button
                        sending = true
                        sendError = null
                        scope.launch {
                            val result =
                                FeedbackApi.send(
                                    FeedbackDrafts.envelope(
                                        draft = ready,
                                        note = note,
                                        sentAt = Instant.now().toString(),
                                    ),
                                )
                            sending = false
                            result
                                .onSuccess {
                                    Toast.makeText(context, sentText, Toast.LENGTH_SHORT).show()
                                    onSent()
                                }
                                .onFailure { failure -> sendError = failure.message ?: failure.javaClass.simpleName }
                        }
                    },
                ) {
                    Text(stringResource(R.string.send))
                }
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AnimatedContent(
                targetState = status,
                transitionSpec = {
                    if (animationsDisabled) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        (fadeIn(tween(180)) togetherWith fadeOut(tween(120))).using(SizeTransform(clip = false))
                    }
                },
                label = "feedbackReviewStatus",
            ) { current ->
                when (current) {
                    ReviewStatus.Failed ->
                        Text(
                            text = stringResource(R.string.feedback_building_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    ReviewStatus.Preparing ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Text(
                                text = stringResource(R.string.feedback_building),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    ReviewStatus.Ready ->
                        draft?.let { ready ->
                            ReportPreview(report = ready.report, note = note, onNoteChange = { note = it })
                        }
                }
            }
            sendError?.let { message ->
                Text(
                    text = stringResource(R.string.feedback_send_failed, message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private enum class ReviewStatus { Preparing, Failed, Ready }

@Composable
private fun ReportPreview(
    report: String,
    note: String,
    onNoteChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            SelectionContainer(
                modifier =
                    Modifier
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = report,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        OutlinedTextField(
            value = note,
            onValueChange = { onNoteChange(it.take(FeedbackReport.MAX_NOTE_CHARS)) },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.feedback_note_hint)) },
            supportingText = { Text(stringResource(R.string.feedback_note_limit, FeedbackReport.MAX_NOTE_CHARS)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
