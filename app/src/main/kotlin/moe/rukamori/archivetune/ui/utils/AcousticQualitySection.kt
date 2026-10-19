/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.utils

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.acoustics.AcousticAssessment
import moe.rukamori.archivetune.acoustics.AcousticAuditController
import moe.rukamori.archivetune.acoustics.AcousticAuditResult
import moe.rukamori.archivetune.acoustics.AcousticAuditStatus
import moe.rukamori.archivetune.acoustics.AcousticAuditTarget
import moe.rukamori.archivetune.acoustics.AcousticHeatAxis
import moe.rukamori.archivetune.acoustics.rememberAcousticAuditController

@Composable
fun AcousticQualitySection(
    target: AcousticAuditTarget,
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    val controller = rememberAcousticAuditController(target, active)
    AcousticQualityContent(target, controller, modifier, active)
}

@Composable
internal fun AcousticQualityContent(
    target: AcousticAuditTarget,
    controller: AcousticAuditController,
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val running =
        state.status == AcousticAuditStatus.RESOLVING ||
            state.status == AcousticAuditStatus.DECODING ||
            state.status == AcousticAuditStatus.ANALYZING
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(target.title, style = MaterialTheme.typography.titleMedium)
        if (target.artists.isNotBlank())
            Text(target.artists, style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(R.string.acoustic_quality_explanation),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(
                when (state.status) {
                    AcousticAuditStatus.IDLE -> R.string.acoustic_quality_idle
                    AcousticAuditStatus.RESOLVING -> R.string.acoustic_quality_resolving
                    AcousticAuditStatus.DECODING -> R.string.acoustic_quality_decoding
                    AcousticAuditStatus.ANALYZING -> R.string.acoustic_quality_analyzing
                    AcousticAuditStatus.COMPLETE -> R.string.acoustic_quality_complete
                    AcousticAuditStatus.UNAVAILABLE -> R.string.acoustic_quality_unavailable
                    AcousticAuditStatus.FAILED -> R.string.acoustic_quality_failed
                    AcousticAuditStatus.CANCELLED -> R.string.acoustic_quality_cancelled
                }
            ),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (running) {
            state.progress
                ?.takeIf { it.isFinite() }
                ?.let {
                    Text(
                        stringResource(
                            R.string.acoustic_quality_progress,
                            (it.coerceIn(0f, 1f) * 100).roundToInt(),
                        )
                    )
                }
        }
        state.message?.let { Text(acousticCodeText(it)) }
        state.result
            ?.takeIf { active && it.mediaId == target.mediaId }
            ?.let { result ->
                AcousticQualityResult(result)
            }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (running) {
                TextButton(onClick = controller::cancel) {
                    Text(stringResource(R.string.acoustic_quality_cancel))
                }
            } else {
                Button(onClick = controller::start, enabled = active) {
                    Text(
                        stringResource(
                            if (state.status == AcousticAuditStatus.IDLE) {
                                R.string.acoustic_quality_run
                            } else R.string.acoustic_quality_retry
                        )
                    )
                }
            }
        }
    }
}

/**
 * Notes that change how to read a result; the rest of [AcousticAuditResult.limitations] is fixed
 * method detail.
 */
private val ReaderNotes = setOf("insufficient_active_evidence", "pcm_budget_shortened_excerpt")

@Composable
private fun AcousticQualityResult(result: AcousticAuditResult) {
    val source = acousticCodeText(result.sourceLabel)
    val channels =
        when (result.channelCount) {
            1 -> stringResource(R.string.acoustic_quality_mono)
            2 -> stringResource(R.string.acoustic_quality_stereo)
            else -> stringResource(R.string.acoustic_quality_channels, result.channelCount)
        }
    val details =
        stringResource(
            R.string.acoustic_quality_details,
            AcousticHeatAxis.formatClock(result.startMs / 1000.0),
            AcousticHeatAxis.formatClock((result.startMs + result.durationMs) / 1000.0),
            result.sampleRate / 1000f,
            channels,
            source,
        )
    val topKHz = result.nyquistHz / 1000f
    val cutoff =
        result.cutoffHz
            ?.takeIf { it.isFinite() }
            ?.let {
                stringResource(R.string.acoustic_quality_cutoff, it / 1000f, topKHz)
            } ?: stringResource(R.string.acoustic_quality_cutoff_unknown, topKHz)
    val assessment =
        stringResource(
            when (result.assessment) {
                AcousticAssessment.INCONCLUSIVE -> R.string.acoustic_quality_inconclusive
                AcousticAssessment.NO_SUSPICIOUS_CUTOFF -> R.string.acoustic_quality_no_cutoff
                AcousticAssessment.SUSPECTED_UPSCALED -> R.string.acoustic_quality_suspected
            }
        )
    Text(assessment, style = MaterialTheme.typography.titleSmall)
    Text(cutoff)
    AcousticHeatGraph(result, "$assessment. $cutoff. $details")
    Text(details, style = MaterialTheme.typography.bodySmall)
    result.limitations
        .distinct()
        .filter { it in ReaderNotes }
        .forEach {
            Text(acousticCodeText(it), style = MaterialTheme.typography.bodySmall)
        }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.acoustic_quality_read_title),
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            stringResource(R.string.acoustic_quality_read_axes),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(R.string.acoustic_quality_read_colour),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(R.string.acoustic_quality_read_cutoff),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
internal fun acousticCodeText(code: String): String =
    stringResource(
        when (code) {
            "LOCAL_FILE" -> R.string.acoustic_quality_source_local_file
            "LOCAL_CONTENT" -> R.string.acoustic_quality_source_local_content
            "TIDAL_CACHED" -> R.string.acoustic_quality_code_tidal_cached
            "QOBUZ_CACHED" -> R.string.acoustic_quality_code_qobuz_cached
            "QOBUZ_BACKUP_CACHED" -> R.string.acoustic_quality_code_qobuz_backup_cached
            "TIDAL_CURRENT" -> R.string.acoustic_quality_code_tidal_current
            "QOBUZ_CURRENT" -> R.string.acoustic_quality_code_qobuz_current
            "QOBUZ_BACKUP_CURRENT" -> R.string.acoustic_quality_code_qobuz_backup_current
            "insufficient_active_evidence" ->
                R.string.acoustic_quality_code_insufficient_active_evidence
            "pcm_budget_shortened_excerpt" ->
                R.string.acoustic_quality_code_pcm_budget_shortened_excerpt
            "deadline_exceeded" -> R.string.acoustic_quality_code_deadline_exceeded
            "analysis_busy" -> R.string.acoustic_quality_code_analysis_busy
            "source_changed" -> R.string.acoustic_quality_code_source_changed
            "input_unreadable" -> R.string.acoustic_quality_code_input_unreadable
            "local_unreadable" -> R.string.acoustic_quality_code_local_unreadable
            "input_permission_denied" -> R.string.acoustic_quality_code_input_permission_denied
            "analysis_failed" -> R.string.acoustic_quality_code_analysis_failed
            "input_closed" -> R.string.acoustic_quality_code_input_closed
            "invalid_read" -> R.string.acoustic_quality_code_invalid_read
            "read_budget_exceeded" -> R.string.acoustic_quality_code_read_budget_exceeded
            "network_budget_exceeded" -> R.string.acoustic_quality_code_network_budget_exceeded
            "no_target" -> R.string.acoustic_quality_code_no_target
            "local_authority_unsupported" ->
                R.string.acoustic_quality_code_local_authority_unsupported
            "local_not_regular" -> R.string.acoustic_quality_code_local_not_regular
            "content_unreadable" -> R.string.acoustic_quality_code_content_unreadable
            "content_not_seekable" -> R.string.acoustic_quality_code_content_not_seekable
            "provider_input_unsupported" ->
                R.string.acoustic_quality_code_provider_input_unsupported
            "telegram_unsupported" -> R.string.acoustic_quality_code_telegram_unsupported
            "current_source_unavailable" ->
                R.string.acoustic_quality_code_current_source_unavailable
            "no_safe_source" -> R.string.acoustic_quality_code_no_safe_source
            "local_changed" -> R.string.acoustic_quality_code_local_changed
            "cache_gap" -> R.string.acoustic_quality_code_cache_gap
            "cache_changed" -> R.string.acoustic_quality_code_cache_changed
            "remote_unsupported" -> R.string.acoustic_quality_code_remote_unsupported
            "source_expired" -> R.string.acoustic_quality_code_source_expired
            "remote_length_unknown" -> R.string.acoustic_quality_code_remote_length_unknown
            "remote_unreadable" -> R.string.acoustic_quality_code_remote_unreadable
            "remote_short_read" -> R.string.acoustic_quality_code_remote_short_read
            "remote_range_unsupported" -> R.string.acoustic_quality_code_remote_range_unsupported
            "drm_unsupported" -> R.string.acoustic_quality_code_drm_unsupported
            "no_audio_track" -> R.string.acoustic_quality_code_no_audio_track
            "codec_unknown" -> R.string.acoustic_quality_code_codec_unknown
            "decoder_stalled" -> R.string.acoustic_quality_code_decoder_stalled
            "decoder_unavailable" -> R.string.acoustic_quality_code_decoder_unavailable
            "encrypted_unsupported" -> R.string.acoustic_quality_code_encrypted_unsupported
            "decoded_format_unknown" -> R.string.acoustic_quality_code_decoded_format_unknown
            "decoded_format_unsupported" ->
                R.string.acoustic_quality_code_decoded_format_unsupported
            "decoded_format_changed" -> R.string.acoustic_quality_code_decoded_format_changed
            "insufficient_heap_headroom" ->
                R.string.acoustic_quality_code_insufficient_heap_headroom
            "decoded_buffer_invalid" -> R.string.acoustic_quality_code_decoded_buffer_invalid
            "decoded_timestamps_discontinuous" ->
                R.string.acoustic_quality_code_decoded_timestamps_discontinuous
            "non_finite_pcm" -> R.string.acoustic_quality_code_non_finite_pcm
            "insufficient_pcm" -> R.string.acoustic_quality_code_insufficient_pcm
            "codec_unsupported" -> R.string.acoustic_quality_code_codec_unsupported
            "container_or_codec_unsupported" ->
                R.string.acoustic_quality_code_container_or_codec_unsupported
            else -> R.string.acoustic_quality_generic
        }
    )
