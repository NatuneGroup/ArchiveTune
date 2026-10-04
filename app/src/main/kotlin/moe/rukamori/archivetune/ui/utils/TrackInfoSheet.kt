/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.utils

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.flowOf
import moe.rukamori.archivetune.LocalDatabase
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.AppleMusicExperienceKey
import moe.rukamori.archivetune.db.entities.FormatEntity
import moe.rukamori.archivetune.playback.DecodedPcmFormat
import moe.rukamori.archivetune.ui.component.LocalBottomSheetPageState
import moe.rukamori.archivetune.utils.credits.TrackCredit
import moe.rukamori.archivetune.utils.credits.TrackCreditRole
import moe.rukamori.archivetune.utils.credits.TrackCreditSource
import moe.rukamori.archivetune.utils.credits.trackCreditsFromMetadata
import moe.rukamori.archivetune.utils.rememberPreference

private enum class TrackInfoTab(@StringRes val labelRes: Int) {
    Overview(R.string.track_info_overview),
    AudioSpecs(R.string.track_info_audio_specs),
}

@Composable
fun TrackInfoAndSpecs(
    trackId: String,
    title: String,
    artists: String,
    album: String? = null,
    artwork: String? = null,
    durationMs: Long? = null,
) {
    val database = LocalDatabase.current
    val format by database.format(trackId).collectAsStateWithLifecycle(initialValue = null)
    val lyrics by database.lyrics(trackId).collectAsStateWithLifecycle(initialValue = null)
    val playerConnection = LocalPlayerConnection.current
    val decodedPcmFlow =
        remember(playerConnection) {
            playerConnection?.decodedPcmFormat ?: flowOf<DecodedPcmFormat?>(null)
        }
    val decodedPcmFormat by decodedPcmFlow.collectAsStateWithLifecycle(initialValue = null)
    val trackDecodedPcmFormat = decodedPcmForTrack(trackId, decodedPcmFormat)
    val mediaInfo = rememberMediaInfo(trackId)
    val (appleExperience) = rememberPreference(AppleMusicExperienceKey, defaultValue = false)
    var selectedTab by rememberSaveable(trackId) { mutableStateOf(TrackInfoTab.Overview) }
    val credits = remember(mediaInfo?.description, lyrics?.lyrics) {
        trackCreditsFromMetadata(mediaInfo?.description, lyrics?.lyrics)
    }
    val resolvedTitle = title.ifBlank { mediaInfo?.title.orEmpty() }
    val resolvedArtists = artists.ifBlank { mediaInfo?.author.orEmpty() }
    val bottomSheetPageState = LocalBottomSheetPageState.current

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            TrackInfoHeader(
                title = resolvedTitle,
                artists = resolvedArtists,
                artwork = artwork,
                appleExperience = appleExperience,
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TrackInfoTab.entries.forEach { tab ->
                    val selected = tab == selectedTab
                    TextButton(
                        onClick = { selectedTab = tab },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = stringResource(tab.labelRes),
                            color =
                                if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                    }
                }
            }
        }
        item {
            when (selectedTab) {
                TrackInfoTab.Overview -> TrackInfoOverview(
                    title = resolvedTitle,
                    artists = resolvedArtists,
                    album = album,
                    durationMs = durationMs,
                    credits = credits,
                    appleExperience = appleExperience,
                )
                TrackInfoTab.AudioSpecs -> TrackInfoAudioSpecs(
                    format = format,
                    decodedPcmFormat = trackDecodedPcmFormat,
                    appleExperience = appleExperience,
                )
            }
        }
        if (isYouTubeVideoId(trackId)) {
            item {
                TextButton(
                    onClick = { bottomSheetPageState.show { ShowMediaInfo(trackId) } },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.track_info_more_video_details))
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun TrackInfoHeader(
    title: String,
    artists: String,
    artwork: String?,
    appleExperience: Boolean,
) {
    TrackInfoCard(appleExperience) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                modifier = Modifier.size(76.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                if (!artwork.isNullOrBlank()) {
                    AsyncImage(
                        model = artwork,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(76.dp),
                    )
                } else {
                    Text(
                        text = title.take(1).ifBlank { "♫" },
                        modifier = Modifier.padding(20.dp),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.track_info_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = title.ifBlank { stringResource(R.string.track_info_not_reported) },
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (artists.isNotBlank()) {
                    Text(
                        text = artists,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackInfoOverview(
    title: String,
    artists: String,
    album: String?,
    durationMs: Long?,
    credits: List<TrackCredit>,
    appleExperience: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TrackInfoCard(appleExperience) {
            Text(stringResource(R.string.track_info_identity), style = MaterialTheme.typography.titleMedium)
            TrackInfoValueRow(
                label = stringResource(R.string.track_info_title_label),
                value = title.takeIf(String::isNotBlank) ?: stringResource(R.string.track_info_not_reported),
            )
            if (artists.isNotBlank()) {
                HorizontalDivider()
                TrackInfoValueRow(stringResource(R.string.track_info_artist), artists)
            }
            if (!album.isNullOrBlank()) {
                HorizontalDivider()
                TrackInfoValueRow(stringResource(R.string.track_info_album), album)
            }
            formatTrackDuration(durationMs)?.let { duration ->
                HorizontalDivider()
                TrackInfoValueRow(stringResource(R.string.track_info_duration), duration)
            }
        }
        TrackInfoCard(appleExperience) {
            Text(stringResource(R.string.track_info_credits), style = MaterialTheme.typography.titleMedium)
            if (credits.isEmpty()) {
                Text(
                    text = stringResource(R.string.track_info_no_credits),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                credits.forEachIndexed { index, credit ->
                    if (index > 0) HorizontalDivider()
                    TrackCreditRow(credit)
                }
            }
            Text(
                text = stringResource(R.string.track_info_credit_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TrackInfoAudioSpecs(
    format: FormatEntity?,
    decodedPcmFormat: DecodedPcmFormat?,
    appleExperience: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TrackInfoCard(appleExperience) {
            Text(stringResource(R.string.track_info_reported_stream), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.track_info_stream_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (format == null) {
                Text(
                    text = stringResource(R.string.track_info_no_reported_format),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val details = buildList {
                    format.codecs.takeIf(String::isNotBlank)?.let {
                        add(R.string.track_info_codec to it)
                    }
                    format.mimeType.takeIf(String::isNotBlank)?.let {
                        add(R.string.track_info_mime_type to it)
                    }
                    format.itag.takeIf { it > 0 }?.let {
                        add(R.string.track_info_format_id to it.toString())
                    }
                    formatReportedBitrate(format.bitrate)?.let {
                        add(R.string.track_info_reported_bitrate to it)
                    }
                    formatReportedSampleRate(format.sampleRate)?.let {
                        add(R.string.track_info_reported_sample_rate to it)
                    }
                    formatReportedByteCount(format.contentLength)?.let {
                        add(R.string.track_info_reported_size to it)
                    }
                    formatReportedLoudness(format.loudnessDb)?.let {
                        add(R.string.track_info_reported_loudness to it)
                    }
                    formatReportedLoudness(format.perceptualLoudnessDb)?.let {
                        add(R.string.track_info_reported_perceptual_loudness to it)
                    }
                }
                if (details.isEmpty()) {
                    Text(
                        text = stringResource(R.string.track_info_no_reported_format),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    details.forEachIndexed { index, (label, value) ->
                        if (index > 0) HorizontalDivider()
                        TrackInfoValueRow(stringResource(label), value)
                    }
                }
            }
        }
        TrackInfoCard(appleExperience) {
            Text(
                stringResource(R.string.track_info_decoded_audio_observed),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.track_info_decoded_audio_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val unknownValue = stringResource(R.string.track_info_unknown)
            val notMeasured = stringResource(R.string.track_info_not_measured)
            val sampleRate =
                decodedPcmFormat?.let { formatReportedSampleRate(it.sampleRateHz) }
                    ?: if (decodedPcmFormat == null) notMeasured else unknownValue
            val channelCount =
                when {
                    decodedPcmFormat == null -> notMeasured
                    decodedPcmFormat.channelCount > 0 -> decodedPcmFormat.channelCount.toString()
                    else -> unknownValue
                }
            val bitDepth = decodedPcmFormat?.bitsPerSample?.takeIf { it > 0 }
            val sampleFormat =
                when {
                    decodedPcmFormat == null -> notMeasured
                    bitDepth == null -> unknownValue
                    decodedPcmFormat.isFloatingPoint ->
                        stringResource(R.string.track_info_decoded_pcm_float_format, bitDepth)
                    else -> stringResource(R.string.track_info_decoded_pcm_integer_format, bitDepth)
                }
            TrackInfoValueRow(stringResource(R.string.track_info_decoded_sample_rate), sampleRate)
            HorizontalDivider()
            TrackInfoValueRow(stringResource(R.string.track_info_decoded_channel_count), channelCount)
            HorizontalDivider()
            TrackInfoValueRow(stringResource(R.string.track_info_decoded_sample_format), sampleFormat)
        }
        TrackInfoCard(appleExperience) {
            Text(stringResource(R.string.track_info_actual_output), style = MaterialTheme.typography.titleMedium)
            TrackInfoValueRow(
                label = stringResource(R.string.track_info_output_sample_format),
                value = stringResource(R.string.track_info_not_measured),
            )
            Text(
                text = stringResource(R.string.track_info_output_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TrackCreditRow(credit: TrackCredit) {
    val role =
        when (credit.role) {
            TrackCreditRole.WRITTEN_BY -> stringResource(R.string.track_info_credit_written_by)
            TrackCreditRole.COMPOSED_BY -> stringResource(R.string.track_info_credit_composed_by)
            TrackCreditRole.LYRICS_BY -> stringResource(R.string.track_info_credit_lyrics_by)
        }
    val source =
        when (credit.source) {
            TrackCreditSource.VIDEO_DESCRIPTION -> stringResource(R.string.track_info_credit_source_description)
            TrackCreditSource.LYRICS_METADATA -> stringResource(R.string.track_info_credit_source_lyrics)
        }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TrackInfoValueRow(role, credit.names)
        Text(source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TrackInfoValueRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.9f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.1f),
        )
    }
}

@Composable
private fun TrackInfoCard(
    appleExperience: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape =
            if (appleExperience) {
                RoundedCornerShape(28.dp)
            } else {
                MaterialTheme.shapes.medium
            },
        colors =
            CardDefaults.elevatedCardColors(
                containerColor =
                    if (appleExperience) {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
            ),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        },
    )
}
