/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * The Home tab's QQ Music page.
 * It is the third of the Home pages, and the only one whose content belongs to the account rather
 * than to the app: each section is one of QQ Music's charts, and its songs are played by the
 * account's own QQ Music source. The sections are drawn with the app's own rows — the same
 * thumbnail-over-title list every song list in the app uses — so a QQ song sits in the queue and in
 * the player exactly like a song from anywhere else, and tapping one plays its section from there,
 * which is what every other song list does.
 * Nothing on this page is required to exist: QQ can answer with a feed this build cannot read, or
 * not answer at all, and both are states with a retry rather than an empty page or a crash.
 */



package moe.rukamori.archivetune.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.extensions.toMediaItem
import moe.rukamori.archivetune.extensions.togglePlayPause
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.playback.queues.ListQueue
import moe.rukamori.archivetune.qqmusic.QqHomeScreenState
import moe.rukamori.archivetune.qqmusic.QqHomeSection
import moe.rukamori.archivetune.qqmusic.QqHomeViewModel
import moe.rukamori.archivetune.ui.component.ExpressivePullToRefreshBox
import moe.rukamori.archivetune.ui.component.MediaMetadataListItem
import moe.rukamori.archivetune.ui.component.pressScaleClickable

/**
 * The QQ Music Home page: the signed-in account's charts, one section each.
 *
 * There is no navigation of its own — a section is played rather than opened — so this page is the
 * whole tab while it is showing, and the top app bar's Home switcher is the way off it.
 */
@Composable
fun QqHomeScreen(
    headerScrollConnection: NestedScrollConnection? = null,
    viewModel: QqHomeViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val screenState by viewModel.screenState.collectAsStateWithLifecycle()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val isPlaying by playerConnection.isPlaying.collectAsStateWithLifecycle()

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .then(
                    if (headerScrollConnection != null) {
                        Modifier.nestedScroll(headerScrollConnection)
                    } else {
                        Modifier
                    },
                ),
    ) {
        when (val state = screenState) {
            QqHomeScreenState.Loading -> {
                QqHomeStatePane(
                    iconResId = null,
                    messageResId = null,
                    showLoadingIndicator = true,
                )
            }

            QqHomeScreenState.Empty -> {
                QqHomeStatePane(
                    iconResId = R.drawable.music_note,
                    messageResId = R.string.no_results_found,
                    actionResId = R.string.retry,
                    onAction = viewModel::refresh,
                )
            }

            is QqHomeScreenState.Error -> {
                QqHomeStatePane(
                    iconResId = R.drawable.ic_about,
                    messageResId = state.messageResId,
                    actionResId = R.string.retry,
                    onAction = viewModel::refresh,
                )
            }

            is QqHomeScreenState.Success -> {
                QqHomeFeed(
                    sections = state.sections,
                    activeTrackId = mediaMetadata?.id,
                    isPlaying = isPlaying,
                    onTrackClick = { section, track ->
                        // Tapping what is already playing is a pause, as it is everywhere else in
                        // the app; anything else replaces the queue with the section it came from.
                        if (mediaMetadata?.id == track.id) {
                            playerConnection.player.togglePlayPause()
                        } else {
                            playerConnection.playQueue(
                                ListQueue(
                                    title = section.title,
                                    items = section.tracks.map { it.toMediaItem() },
                                    startIndex = section.tracks.indexOf(track).coerceAtLeast(0),
                                ),
                            )
                        }
                    },
                    onRefresh = viewModel::refresh,
                )
            }
        }
    }
}

/** The sections, as a header and its songs per chart. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QqHomeFeed(
    sections: List<QqHomeSection>,
    activeTrackId: String?,
    isPlaying: Boolean,
    onTrackClick: (QqHomeSection, MediaMetadata) -> Unit,
    onRefresh: () -> Unit,
) {
    ExpressivePullToRefreshBox(
        // A refresh goes through the loading state above like any other load, so this box carries
        // the gesture rather than an indicator of its own.
        isRefreshing = false,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
            modifier = Modifier.fillMaxSize(),
        ) {
            sections.forEachIndexed { sectionIndex, section ->
                item(
                    key = "qq_home_section_$sectionIndex",
                    contentType = "section_header",
                ) {
                    HomeSectionHeader(
                        title = section.title,
                        label = section.label,
                        modifier = Modifier.animateItem(),
                    )
                }
                // Keyed by section as well as position: the same song can be ranked by several
                // charts, and a duplicated key would be a crash rather than a repeated row.
                itemsIndexed(
                    items = section.tracks,
                    key = { trackIndex, _ -> "qq_home_track_${sectionIndex}_$trackIndex" },
                    contentType = { _, _ -> "qq_home_track" },
                ) { _, track ->
                    MediaMetadataListItem(
                        mediaMetadata = track,
                        isActive = track.id == activeTrackId,
                        isPlaying = isPlaying,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .animateItem()
                                .pressScaleClickable { onTrackClick(section, track) },
                    )
                }
            }
        }
    }
}

/**
 * The page's own loading, empty and error pane, shaped like the other Home pages' so a QQ failure
 * reads exactly like a YouTube or Spotify one.
 */
@Composable
private fun QqHomeStatePane(
    iconResId: Int?,
    messageResId: Int?,
    modifier: Modifier = Modifier,
    actionResId: Int? = null,
    showLoadingIndicator: Boolean = false,
    onAction: (() -> Unit)? = null,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .fillMaxSize()
                .padding(LocalPlayerAwareWindowInsets.current.asPaddingValues()),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            if (showLoadingIndicator) {
                LoadingIndicator()
            } else {
                iconResId?.let {
                    Icon(
                        painter = painterResource(it),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp),
                    )
                }
                messageResId?.let {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(it),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (actionResId != null && onAction != null) {
                    Spacer(Modifier.height(20.dp))
                    FilledTonalButton(onClick = onAction) {
                        Text(stringResource(actionResId))
                    }
                }
            }
        }
    }
}
