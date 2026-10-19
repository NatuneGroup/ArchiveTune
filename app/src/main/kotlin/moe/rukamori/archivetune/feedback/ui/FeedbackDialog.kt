/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.feedback.ui

import android.widget.Toast
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
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.feedback.FeedbackApi
import moe.rukamori.archivetune.feedback.FeedbackApi.FeedbackRejection
import moe.rukamori.archivetune.feedback.FeedbackDraft
import moe.rukamori.archivetune.feedback.FeedbackDrafts
import java.time.Instant

/**
 * The single review-before-send surface for every feedback report. The report
 * text is built by [loadDraft] (off the main thread) and shown verbatim so the
 * user can read it, edit the optional note, or back out; only Send posts it.
 * Closing the dialog never sends, and a failed send keeps the draft on screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackReviewDialog(
    title: String,
    loadDraft: suspend () -> FeedbackDraft?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sentText = stringResource(R.string.feedback_sent)
    var draft by remember { mutableStateOf<FeedbackDraft?>(null) }
    var buildFailed by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        draft = withContext(Dispatchers.IO) { loadDraft() }
        buildFailed = draft == null
    }

    BasicAlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        properties =
            DialogProperties(
                dismissOnBackPress = !sending,
                dismissOnClickOutside = !sending,
            ),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    when {
                        buildFailed -> Text(
                            text = stringResource(R.string.feedback_building_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        draft == null -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Text(
                                text = stringResource(R.string.feedback_building),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                draft?.let { ready ->
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
                                text = ready.report,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.feedback_note_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                sendError?.let { message ->
                    Text(
                        text = stringResource(R.string.feedback_send_failed, message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        enabled = !sending,
                        onClick = onDismiss,
                    ) {
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
                                            onDismiss()
                                        }
                                        .onFailure { failure ->
                                            sendError =
                                                if (failure is FeedbackRejection) {
                                                    failure.message
                                                } else {
                                                    failure.message
                                                        ?: failure.javaClass.simpleName
                                                }
                                        }
                                }
                            },
                        ) {
                            Text(stringResource(R.string.send))
                        }
                    }
                }
            }
        }
    }
}
