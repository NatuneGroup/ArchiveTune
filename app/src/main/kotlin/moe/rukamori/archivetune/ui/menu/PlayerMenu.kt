/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.menu

import android.content.Intent
import android.media.audiofx.AudioEffect
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.LocalDatabase
import moe.rukamori.archivetune.LocalDownloadUtil
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.ArchiveTuneCanvasKey
import moe.rukamori.archivetune.constants.ArtistSeparatorsKey
import moe.rukamori.archivetune.constants.ExternalDownloaderEnabledKey
import moe.rukamori.archivetune.constants.ExternalDownloaderPackageKey
import moe.rukamori.archivetune.constants.PlayerDesignStyle
import moe.rukamori.archivetune.constants.PlayerDesignStyleKey
import moe.rukamori.archivetune.constants.SpeedDialSongIdsKey
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.models.toMediaMetadata
import moe.rukamori.archivetune.playback.CanvasArtworkRefetchResult
import moe.rukamori.archivetune.playback.ExoDownloadService
import moe.rukamori.archivetune.playback.queues.YouTubeQueue
import moe.rukamori.archivetune.extensions.toMediaItem
import moe.rukamori.archivetune.db.entities.ArtistEntity
import moe.rukamori.archivetune.deezer.DeezerAudioProvider
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.jiosaavn.SaavnService
import moe.rukamori.archivetune.tidal.TidalAudioProvider
import moe.rukamori.archivetune.qobuz.QobuzAudioProvider
import moe.rukamori.archivetune.qobuz.QobuzBackupProvider
import moe.rukamori.archivetune.ui.component.MenuHeaderCard
import moe.rukamori.archivetune.ui.component.BottomSheetState
import moe.rukamori.archivetune.ui.component.ChipsRow
import moe.rukamori.archivetune.ui.component.DefaultDialog
import moe.rukamori.archivetune.ui.component.ListDialog
import moe.rukamori.archivetune.ui.component.MenuSurfaceSection
import moe.rukamori.archivetune.ui.component.NewAction
import moe.rukamori.archivetune.ui.component.NewActionGrid
import moe.rukamori.archivetune.ui.player.rememberDeviceMusicVolumeController
import moe.rukamori.archivetune.ui.utils.YtimgResizePolicy
import moe.rukamori.archivetune.ui.utils.resize
import moe.rukamori.archivetune.ui.player.CanvasArtworkPlaybackCache
import moe.rukamori.archivetune.utils.defaultStorefront
import moe.rukamori.archivetune.utils.SpeedDialPin
import moe.rukamori.archivetune.utils.SpeedDialPinType
import moe.rukamori.archivetune.utils.isLocalMediaId
import moe.rukamori.archivetune.utils.parseSpeedDialPins
import moe.rukamori.archivetune.audiosource.SongSourceOverride
import moe.rukamori.archivetune.constants.AmazonEnabledKey
import moe.rukamori.archivetune.constants.AudioSourceType
import moe.rukamori.archivetune.applemusic.AppleMusicCatalog
import moe.rukamori.archivetune.applemusic.AppleMusicSearchItem
import moe.rukamori.archivetune.constants.SongSourceOverrideKey
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberLowDataModeActive
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.utils.serializeSpeedDialPins
import moe.rukamori.archivetune.utils.shareLocalAudio
import moe.rukamori.archivetune.utils.toggleSpeedDialPin
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt

@Composable
fun PlayerMenu(
    mediaMetadata: MediaMetadata?,
    navController: NavController,
    playerBottomSheetState: BottomSheetState,
    isQueueTrigger: Boolean? = false,
    onPlayNextFromQueue: (() -> Unit)? = null,
    onRemoveFromQueue: (() -> Unit)? = null,
    onShowDetailsDialog: () -> Unit,
    onDismiss: () -> Unit,
) {
    mediaMetadata ?: return
    val context = LocalContext.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val deviceMusicVolumeController = rememberDeviceMusicVolumeController()
    val onPlayerVolumeChange =
        remember(deviceMusicVolumeController) {
            { volume: Float -> deviceMusicVolumeController.setVolumeFraction(volume) }
        }
    val activityResultLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    val librarySong by database.song(mediaMetadata.id).collectAsStateWithLifecycle(initialValue = null)
    val coroutineScope = rememberCoroutineScope()

    val downloadUtil = LocalDownloadUtil.current
    val download by downloadUtil
        .getDownload(mediaMetadata.id)
        .collectAsStateWithLifecycle(initialValue = null)

    val artists =
        remember(mediaMetadata.artists) {
            mediaMetadata.artists.filter { it.id != null }
        }

    // Artist separators for splitting artist names
    val (artistSeparators) = rememberPreference(ArtistSeparatorsKey, defaultValue = ",;/&")
    val (externalDownloaderEnabled) = rememberPreference(ExternalDownloaderEnabledKey, defaultValue = false)
    val (externalDownloaderPackage) = rememberPreference(ExternalDownloaderPackageKey, defaultValue = "")
    val (archiveTuneCanvasEnabled) = rememberPreference(ArchiveTuneCanvasKey, defaultValue = false)
    val (amazonSourceEnabled) = rememberPreference(AmazonEnabledKey, defaultValue = false)
    val playerDesignStyle by rememberEnumPreference(PlayerDesignStyleKey, defaultValue = PlayerDesignStyle.Default)
    val lowDataModeActive = rememberLowDataModeActive()
    val isCanvasArtworkRefetching by playerConnection.isCanvasArtworkRefetching.collectAsStateWithLifecycle()
    // Only show the "Refetch canvas" overflow action when the current song
    // actually has an animated artwork entry cached. Songs that never resolved
    // a canvas (no animated artwork exists for them) wouldn't benefit from a
    // refetch and would just produce a confusing no-op button.
    var hasCanvasArtwork by remember(mediaMetadata.id) { mutableStateOf(false) }
    LaunchedEffect(mediaMetadata.id, isCanvasArtworkRefetching) {
        hasCanvasArtwork = CanvasArtworkPlaybackCache.hasEntry(mediaMetadata.id)
    }
    val (speedDialSongIds, onSpeedDialSongIdsChange) = rememberPreference(SpeedDialSongIdsKey, "")
    val speedDialPins = remember(speedDialSongIds) { parseSpeedDialPins(speedDialSongIds) }
    val songPin = remember(mediaMetadata.id) { SpeedDialPin(type = SpeedDialPinType.SONG, id = mediaMetadata.id) }
    val isInSpeedDial =
        remember(speedDialPins, songPin) {
            speedDialPins.any { it.type == songPin.type && it.id == songPin.id }
        }
    val isLocalMedia =
        remember(librarySong?.song?.isLocal, mediaMetadata.id) {
            librarySong?.song?.isLocal == true || mediaMetadata.id.isLocalMediaId()
        }
    val castPlayerMenuAction = rememberCastPlayerMenuAction()

    // Split artists by configured separators
    data class SplitArtist(
        val name: String,
        val originalArtist: MediaMetadata.Artist?,
    )

    val splitArtists =
        remember(artists, artistSeparators) {
            if (artistSeparators.isEmpty()) {
                artists.map { SplitArtist(it.name, it) }
            } else {
                val separatorRegex = "[${Regex.escape(artistSeparators)}]".toRegex()
                artists.flatMap { artist ->
                    val parts =
                        artist.name
                            .split(separatorRegex)
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                    if (parts.size > 1) {
                        // All split parts share the same originalArtist reference so the
                        // thumbnail lookup (and click-through navigation) works for every
                        // part — not just the first one. The underlying YTM channel ID is
                        // the same for all parts.
                        parts.map { name -> SplitArtist(name, artist) }
                    } else {
                        listOf(SplitArtist(artist.name, artist))
                    }
                }
            }
        }

    // Fetch artist profile-picture thumbnail URLs for the "View artist" selection dialog.
    val artistIdsKey =
        remember(splitArtists) {
            splitArtists.mapNotNull { it.originalArtist?.id }.distinct().sorted()
        }
    val artistThumbnailsByKey: Map<String?, String?> by produceState(
        initialValue = emptyMap(),
        artistIdsKey,
    ) {
        withContext(Dispatchers.IO) {
            val result = mutableMapOf<String?, String?>()
            val nameById =
                splitArtists
                    .mapNotNull { sa ->
                        sa.originalArtist?.id?.let { id -> id to sa.originalArtist.name }
                    }.toMap()
            // Update the map INCREMENTALLY per artist so the popup shows each thumbnail
            // as soon as it resolves — instead of waiting for every artist to fetch
            // before updating the UI (which left the user staring at music-note icons
            // for several seconds while slow YTM lookups completed in series).
            splitArtists.mapNotNull { it.originalArtist?.id }.distinct().forEach { artistId ->
                val dbEntity = database.getArtistById(artistId)
                val cached = dbEntity?.thumbnailUrl
                if (!cached.isNullOrBlank()) {
                    result[artistId] = cached
                    value = result.toMap()
                } else {
                    // DB miss — fetch from YouTube Music and persist so the next open
                    // is instant. `ArtistItem.thumbnail` is a `String?` (not a nested
                    // Thumbnails object), so we read it directly.
                    val fetched =
                        runCatching { YouTube.artist(artistId) }
                            .getOrNull()
                            ?.getOrNull()
                            ?.artist
                            ?.thumbnail
                    if (!fetched.isNullOrBlank()) {
                        result[artistId] = fetched
                        value = result.toMap()
                        runCatching {
                            database.query {
                                upsert(
                                    ArtistEntity(
                                        id = artistId,
                                        name = dbEntity?.name ?: nameById[artistId].orEmpty(),
                                        thumbnailUrl = fetched,
                                        channelId = dbEntity?.channelId,
                                        lastUpdateTime = dbEntity?.lastUpdateTime ?: LocalDateTime.now(),
                                        bookmarkedAt = dbEntity?.bookmarkedAt,
                                        blockedAt = dbEntity?.blockedAt,
                                        isLocal = dbEntity?.isLocal ?: false,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    var showChoosePlaylistDialog by rememberSaveable {
        mutableStateOf(false)
    }

    AddToPlaylistDialog(
        isVisible = showChoosePlaylistDialog,
        onGetSong = {
            database.withTransaction {
                insert(mediaMetadata)
            }
            listOf(mediaMetadata.id)
        },
        onDismiss = {
            showChoosePlaylistDialog = false
        },
        onAddComplete = { songCount, playlistNames ->
            val message =
                when {
                    playlistNames.size == 1 -> context.getString(R.string.added_to_playlist, playlistNames.first())
                    else -> context.getString(R.string.added_to_n_playlists, playlistNames.size)
                }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        },
    )

    // Per-song "Play from" chooser: pick which source THIS song plays from. Only sources known to
    // have the track (from the last resolution) are offered, plus YouTube (always available). The
    // choice is remembered per song via SongSourceOverrideKey (included in backups).
    val (songSourceRaw, onSongSourceChange) = rememberPreference(SongSourceOverrideKey, "")
    val currentSongSource =
        remember(songSourceRaw, mediaMetadata.id) {
            SongSourceOverride.get(songSourceRaw.ifBlank { null }, mediaMetadata.id)
        }
    var showSourceDialog by rememberSaveable { mutableStateOf(false) }

    // Trigger a fresh source resolution each time the Source dialog opens. A previous
    // resolution that failed transiently would otherwise pin the song to YouTube in the
    // in-memory cache, leaving Qobuz or Tidal out of the Sources list for the lifetime of
    // the process. The refresh evicts the cache and re-runs the lossless resolution chain
    // in the background; the resulting sources show up via resolvedSourcesRevision (a
    // StateFlow that bumps when recording completes).
    val sourceRevision by playerConnection.service.resolvedSourcesRevision.collectAsStateWithLifecycle()
    LaunchedEffect(showSourceDialog, mediaMetadata.id) {
        if (showSourceDialog) {
            playerConnection.service.refreshSourcesForSong(mediaMetadata.id)
        }
    }
    val availableSources =
        remember(mediaMetadata.id, showSourceDialog, sourceRevision) {
            playerConnection.service.availableSourcesForSong(mediaMetadata.id)
        }

    if (showSourceDialog) {
        SongSourceDialog(
            sources = availableSources,
            selected = currentSongSource,
            onDismiss = { showSourceDialog = false },
            onSelect = { source ->
                onSongSourceChange(SongSourceOverride.withOverride(songSourceRaw, mediaMetadata.id, source))
                playerConnection.service.setSongSourceOverride(mediaMetadata.id, source)
                showSourceDialog = false
            },
            onPlaySong = { song ->
                // Swapping to a different track entirely — play the chosen song via YouTube radio
                // so it seeds a fresh queue with the picked track as the first item.
                playerConnection.playQueue(YouTubeQueue.radio(song.toMediaMetadata()))
            },
            onPlayFromSource = { result ->
                // Non-YT search-result row tapped (JioSaavn / Tidal / Qobuz / Deezer). AUDIO-ONLY
                // CHANGE: only the AUDIO source changes — the song's mediaId, title, artist,
                // thumbnail, and queue position all stay exactly the same.
                val source = result.source
                val trackId = result.trackId
                if (source != AudioSourceType.YOUTUBE && trackId.isNotBlank()) {
                    // Persist the source override for the CURRENT mediaId.
                    onSongSourceChange(
                        SongSourceOverride.withOverride(songSourceRaw, mediaMetadata.id, source),
                    )
                    // Sources with an addressable per-track id get that id persisted so the
                    // resolver fetches exactly the row the user tapped:
                    //   - Qobuz     → catalogue trackId
                    //   - Qobuz Backup → the mirror's YouTube video id (its primary key)
                    // For the rest (Tidal / JioSaavn / Deezer) the resolver still searches
                    // by title + artist, which is all their APIs expose here.
                    when (source) {
                        AudioSourceType.QOBUZ ->
                            playerConnection.service.setSongSourceOverrideWithQobuzTrackId(
                                mediaId = mediaMetadata.id,
                                source = source,
                                qobuzTrackId = trackId,
                            )

                        AudioSourceType.QOBUZ_BACKUP ->
                            playerConnection.service.setSongSourceOverrideWithQobuzBackupVideoId(
                                mediaId = mediaMetadata.id,
                                source = source,
                                qobuzBackupVideoId = trackId,
                            )

                        else ->
                            playerConnection.service.setSongSourceOverride(
                                mediaId = mediaMetadata.id,
                                source = source,
                            )
                    }
                    showSourceDialog = false
                }
            },
        )
    }

    var showSelectArtistDialog by rememberSaveable {
        mutableStateOf(false)
    }

    if (showSelectArtistDialog) {
        ListDialog(
            onDismiss = { showSelectArtistDialog = false },
        ) {
            items(splitArtists.distinctBy { it.name }, key = { it.name }) { splitArtist ->
                ListItem(
                    headlineContent = {
                        Text(
                            text = splitArtist.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingContent = {
                        // Look up the artist's profile picture from the
                        // produceState above (DB → YouTube fallback). Falls
                        // back to the music-note icon only if the lookup
                        // hasn't resolved a thumbnail yet.
                        val thumbUrl =
                            splitArtist.originalArtist?.id?.let { id ->
                                artistThumbnailsByKey[id]
                            }
                        if (thumbUrl.isNullOrBlank()) {
                            Box(
                                modifier =
                                    Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.music_note),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        } else {
                            AsyncImage(
                                model =
                                    thumbUrl.resize(
                                        width = 200,
                                        height = 200,
                                        ytimgResizePolicy = YtimgResizePolicy.PreserveOriginal,
                                    ),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier =
                                    Modifier
                                        .size(40.dp)
                                        .clip(CircleShape),
                            )
                        }
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                splitArtist.originalArtist?.let { artist ->
                                    navController.navigate("artist/${artist.id}")
                                    showSelectArtistDialog = false
                                    playerBottomSheetState.collapseSoft()
                                    onDismiss()
                                }
                            },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }

    var showPitchTempoDialog by rememberSaveable {
        mutableStateOf(false)
    }

    if (showPitchTempoDialog) {
        TempoPitchDialog(
            onDismiss = { showPitchTempoDialog = false },
        )
    }

    // Apple Music–style sleep timer sheet. Rendered as an extra item at the
    // bottom of the same scrollable menu (no second modal layer) so the user
    // can pick a duration without leaving the song's overflow menu.
    var showSleepTimerSheet by rememberSaveable { mutableStateOf(false) }

    var showEqualizerDialog by rememberSaveable {
        mutableStateOf(false)
    }

    if (showEqualizerDialog) {
        EqualizerDialog(
            onDismiss = { showEqualizerDialog = false },
            openSystemEqualizer = {
                val intent =
                    Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
                        putExtra(
                            AudioEffect.EXTRA_AUDIO_SESSION,
                            playerConnection.localPlayer.audioSessionId,
                        )
                        putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                        putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                    }
                if (intent.resolveActivity(context.packageManager) != null) {
                    activityResultLauncher.launch(intent)
                }
            },
        )
    }

    var showSaveCanvasDialog by rememberSaveable { mutableStateOf(false) }

    if (showSaveCanvasDialog) {
        SaveCanvasDialog(
            mediaId = mediaMetadata.id,
            songTitle = mediaMetadata.title,
            artistName = mediaMetadata.artists.joinToString(separator = ", ") { it.name },
            albumTitle = mediaMetadata.album?.title,
            storefront = remember { defaultStorefront() },
            onDismiss = { showSaveCanvasDialog = false },
        )
    }

    val nowPlayingTitle =
        remember(mediaMetadata.title) {
            mediaMetadata.title.ifBlank { context.getString(R.string.no_title) }
        }

    val nowPlayingSubtitle =
        remember(mediaMetadata.artists) {
            mediaMetadata.artists.joinToString(separator = " • ") { it.name }
        }

    MenuHeaderCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            val thumb = mediaMetadata.thumbnailUrl
            if (thumb.isNullOrBlank()) {
                Box(
                    modifier =
                        Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.music_note),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
            } else {
                AsyncImage(
                    model = thumb,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(16.dp)),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.now_playing),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = nowPlayingTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee(),
                )
                if (nowPlayingSubtitle.isNotBlank()) {
                    Text(
                        text = nowPlayingSubtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.basicMarquee(),
                    )
                }
            }
        }
    }


    Spacer(modifier = Modifier.height(16.dp))

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding =
            PaddingValues(
                start = 0.dp,
                top = 0.dp,
                end = 0.dp,
                bottom = 8.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding(),
            ),
    ) {
        // When the user taps "Sleep timer", replace the menu body with the
        // Apple Music–style picker sheet. Keeping the surface header (album art
        // + title) above gives the user context that this sheet still belongs
        // to the current song, while the rest of the menu items are hidden so
        // the sheet is immediately visible without scrolling.
        if (showSleepTimerSheet) {
            item {
                AppleMusicSleepTimerSheet(
                    sleepTimer = playerConnection.service.sleepTimer,
                    onDismiss = {
                        showSleepTimerSheet = false
                        onDismiss()
                    },
                )
            }
        } else {
        item {
            MenuSurfaceSection(modifier = Modifier.padding(vertical = 6.dp)) {
                NewActionGrid(
                    actions =
                        buildList {
                            castPlayerMenuAction?.let(::add)
                            if (!isLocalMedia) {
                                add(
                                    NewAction(
                                        icon = {
                                            Icon(
                                                painter = painterResource(R.drawable.radio),
                                                contentDescription = null,
                                                modifier = Modifier.size(28.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        },
                                        text = stringResource(R.string.start_radio),
                                        onClick = {
                                            playerConnection.startRadio(mediaMetadata)
                                            onDismiss()
                                        },
                                    ),
                                )
                            }
                            if (
                                !isLocalMedia &&
                                isQueueTrigger != true &&
                                archiveTuneCanvasEnabled &&
                                !lowDataModeActive &&
                                playerDesignStyle != PlayerDesignStyle.V5 &&
                                hasCanvasArtwork
                            ) {
                                add(
                                    NewAction(
                                        icon = {
                                            if (isCanvasArtworkRefetching) {
                                                CircularWavyProgressIndicator(modifier = Modifier.size(28.dp))
                                            } else {
                                                Icon(
                                                    painter = painterResource(R.drawable.sync),
                                                    contentDescription = null,
                                                    modifier = Modifier.size(28.dp),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        },
                                        text = stringResource(R.string.refetch_canvas),
                                        onClick = {
                                            coroutineScope.launch {
                                                when (
                                                    playerConnection.refetchCanvasArtwork(
                                                        metadata = mediaMetadata,
                                                        requireVertical = playerDesignStyle == PlayerDesignStyle.V7,
                                                    )
                                                ) {
                                                    CanvasArtworkRefetchResult.Success -> onDismiss()
                                                    CanvasArtworkRefetchResult.Failure -> {
                                                        Toast
                                                            .makeText(
                                                                context,
                                                                R.string.canvas_refetch_failed,
                                                                Toast.LENGTH_SHORT,
                                                            ).show()
                                                    }

                                                    CanvasArtworkRefetchResult.AlreadyRunning -> Unit
                                                }
                                            }
                                        },
                                        enabled = !isCanvasArtworkRefetching,
                                    ),
                                )
                            }
                            // "Add to playlist" and "Pin to speed dial" are ListItems in the
                            // MenuSurfaceSection below, not chips in this grid.
                            add(
                                if (isLocalMedia) {
                                    NewAction(
                                        icon = {
                                            Icon(
                                                painter = painterResource(R.drawable.share),
                                                contentDescription = null,
                                                modifier = Modifier.size(28.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        },
                                        text = stringResource(R.string.share),
                                        onClick = {
                                            shareLocalAudio(context, mediaMetadata.id, librarySong?.format?.mimeType)
                                            onDismiss()
                                        },
                                    )
                                } else {
                                    NewAction(
                                        icon = {
                                            Icon(
                                                painter = painterResource(R.drawable.link),
                                                contentDescription = null,
                                                modifier = Modifier.size(28.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        },
                                        text = stringResource(R.string.copy_link),
                                        onClick = {
                                            val clipboard =
                                                context.getSystemService(
                                                    android.content.Context.CLIPBOARD_SERVICE,
                                                ) as android.content.ClipboardManager
                                            val clip =
                                                android.content.ClipData.newPlainText(
                                                    context.getString(R.string.copy_link),
                                                    "https://music.youtube.com/watch?v=${mediaMetadata.id}",
                                                )
                                            clipboard.setPrimaryClip(clip)
                                            android.widget.Toast
                                                .makeText(
                                                    context,
                                                    R.string.link_copied,
                                                    android.widget.Toast.LENGTH_SHORT,
                                                ).show()
                                            onDismiss()
                                        },
                                    )
                                },
                            )
                            if (!isLocalMedia) {
                                add(
                                    NewAction(
                                        icon = {
                                            Icon(
                                                painter = painterResource(R.drawable.tune),
                                                contentDescription = null,
                                                modifier = Modifier.size(28.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        },
                                        text = stringResource(R.string.source),
                                        onClick = { showSourceDialog = true },
                                    ),
                                )
                            }
                            if (isQueueTrigger != true) {
                                add(
                                    NewAction(
                                        icon = {
                                            Icon(
                                                painter = painterResource(R.drawable.bedtime),
                                                contentDescription = null,
                                                modifier = Modifier.size(28.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        },
                                        text = stringResource(R.string.aod_mode),
                                        onClick = {
                                            playerConnection.aodModeEnabled.value = true
                                            onDismiss()
                                        },
                                    ),
                                )
                            }
                        },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }
        }
        item {
            Spacer(modifier = Modifier.height(12.dp))
        }
        // "Save Canvas" — list-item form. Shown only when canvas artwork is
        // available for the current song. Tapping opens the SaveCanvasDialog
        // which lets the user pick from all available canvas sources (Spotify
        // Canvas / Apple Music) and saves the chosen video to
        // Movies/ArchiveTune Canvas/ via MediaStore.
        if (
            !isLocalMedia &&
            isQueueTrigger != true &&
            archiveTuneCanvasEnabled &&
            !lowDataModeActive &&
            playerDesignStyle != PlayerDesignStyle.V5 &&
            hasCanvasArtwork
        ) {
            item {
                MenuSurfaceSection(modifier = Modifier.padding(vertical = 6.dp)) {
                    ListItem(
                        headlineContent = { Text(text = stringResource(R.string.save_canvas)) },
                        leadingContent = {
                            Icon(
                                painter = painterResource(R.drawable.motion_photos_on),
                                contentDescription = null,
                            )
                        },
                        modifier =
                            Modifier.clickable {
                                showSaveCanvasDialog = true
                            },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
            item {
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
        // "Add to playlist" and "Pin to speed dial" appear here as ListItems in their own
        // MenuSurfaceSection, matching the visual style of the list items below.
        item {
            MenuSurfaceSection(modifier = Modifier.padding(vertical = 6.dp)) {
                Column {
                    ListItem(
                        headlineContent = { Text(text = stringResource(R.string.add_to_playlist)) },
                        leadingContent = {
                            Icon(
                                painter = painterResource(R.drawable.playlist_add),
                                contentDescription = null,
                            )
                        },
                        modifier =
                            Modifier.clickable {
                                showChoosePlaylistDialog = true
                            },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    ListItem(
                        headlineContent = {
                            Text(
                                text =
                                    stringResource(
                                        if (isInSpeedDial) {
                                            R.string.remove_from_speed_dial
                                        } else {
                                            R.string.pin_to_speed_dial
                                        },
                                    ),
                            )
                        },
                        leadingContent = {
                            Icon(
                                painter =
                                    painterResource(
                                        if (isInSpeedDial) R.drawable.bookmark_filled else R.drawable.bookmark,
                                    ),
                                contentDescription = null,
                            )
                        },
                        modifier =
                            Modifier.clickable {
                                val updatedPins = toggleSpeedDialPin(speedDialPins, songPin)
                                onSpeedDialSongIdsChange(serializeSpeedDialPins(updatedPins))
                                onDismiss()
                            },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
        item {
            Spacer(modifier = Modifier.height(12.dp))
        }
        if (splitArtists.isNotEmpty() || mediaMetadata.album != null) {
            item {
                MenuSurfaceSection(modifier = Modifier.padding(vertical = 6.dp)) {
                    Column {
                        if (splitArtists.isNotEmpty()) {
                            ListItem(
                                headlineContent = { Text(text = stringResource(R.string.view_artist)) },
                                leadingContent = {
                                    Icon(
                                        painter = painterResource(R.drawable.artist),
                                        contentDescription = null,
                                    )
                                },
                                modifier =
                                    Modifier.clickable {
                                        if (splitArtists.size == 1 && splitArtists[0].originalArtist != null) {
                                            onDismiss()
                                            playerBottomSheetState.snapTo(playerBottomSheetState.collapsedBound)
                                            navController.navigate("artist/${splitArtists[0].originalArtist!!.id}")
                                        } else {
                                            showSelectArtistDialog = true
                                        }
                                    },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        }

                        if (splitArtists.isNotEmpty() && mediaMetadata.album != null) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 56.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }

                        if (mediaMetadata.album != null) {
                            ListItem(
                                headlineContent = { Text(text = stringResource(R.string.view_album)) },
                                leadingContent = {
                                    Icon(
                                        painter = painterResource(R.drawable.album),
                                        contentDescription = null,
                                    )
                                },
                                modifier =
                                    Modifier.clickable {
                                        onDismiss()
                                        playerBottomSheetState.snapTo(playerBottomSheetState.collapsedBound)
                                        navController.navigate("album/${mediaMetadata.album.id}")
                                    },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        }
                    }
                }
            }
            item {
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
        if (!isLocalMedia) {
            item {
                MenuSurfaceSection(modifier = Modifier.padding(vertical = 6.dp)) {
                    when (download?.state) {
                        Download.STATE_COMPLETED -> {
                            ListItem(
                                headlineContent = {
                                    Text(
                                        text = stringResource(R.string.remove_download),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                leadingContent = {
                                    Icon(
                                        painter = painterResource(R.drawable.offline),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                                modifier =
                                    Modifier.clickable {
                                        DownloadService.sendRemoveDownload(
                                            context,
                                            ExoDownloadService::class.java,
                                            mediaMetadata.id,
                                            false,
                                        )
                                    },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        }

                        Download.STATE_QUEUED, Download.STATE_DOWNLOADING -> {
                            ListItem(
                                headlineContent = { Text(text = stringResource(R.string.downloading)) },
                                leadingContent = {
                                    CircularWavyProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                    )
                                },
                                modifier =
                                    Modifier.clickable {
                                        DownloadService.sendRemoveDownload(
                                            context,
                                            ExoDownloadService::class.java,
                                            mediaMetadata.id,
                                            false,
                                        )
                                    },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        }

                        else -> {
                            ListItem(
                                headlineContent = { Text(text = stringResource(R.string.action_download)) },
                                leadingContent = {
                                    Icon(
                                        painter = painterResource(R.drawable.download),
                                        contentDescription = null,
                                    )
                                },
                                modifier =
                                    Modifier.clickable {
                                        database.transaction {
                                            insert(mediaMetadata)
                                        }
                                        // Cache-first download: prewarm playerCache
                                        // via Qobuz/Tidal/YT before DownloadManager
                                        // opens, so the actual download reads bytes
                                        // locally instead of fetching over the network.
                                        coroutineScope.launch {
                                            runCatching {
                                                downloadUtil.prewarmSongForDownload(mediaMetadata.id)
                                            }
                                            val downloadRequest =
                                                DownloadRequest
                                                    .Builder(mediaMetadata.id, mediaMetadata.id.toUri())
                                                    .setCustomCacheKey(mediaMetadata.id)
                                                    .setData(mediaMetadata.title.toByteArray())
                                                    .build()
                                            DownloadService.sendAddDownload(
                                                context,
                                                ExoDownloadService::class.java,
                                                downloadRequest,
                                                false,
                                            )
                                        }
                                    },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        }
                    }
                    if (externalDownloaderEnabled) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        ListItem(
                            headlineContent = { Text(text = stringResource(R.string.open_with_downloader)) },
                            leadingContent = {
                                Icon(
                                    painter = painterResource(R.drawable.download),
                                    contentDescription = null,
                                )
                            },
                            modifier =
                                Modifier.clickable {
                                    onDismiss()
                                    val url = "https://music.youtube.com/watch?v=${mediaMetadata.id}"
                                    if (externalDownloaderPackage.isBlank()) {
                                        Toast
                                            .makeText(
                                                context,
                                                context.getString(R.string.external_downloader_not_configured),
                                                Toast.LENGTH_LONG,
                                            ).show()
                                        return@clickable
                                    }
                                    val intent =
                                        android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                            setPackage(externalDownloaderPackage)
                                            data = android.net.Uri.parse(url)
                                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                    try {
                                        context.startActivity(intent)
                                    } catch (e: android.content.ActivityNotFoundException) {
                                        Toast
                                            .makeText(
                                                context,
                                                context.getString(R.string.external_downloader_not_installed),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                    }
                                },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }
            }
        }
        item {
            MenuSurfaceSection(modifier = Modifier.padding(vertical = 6.dp)) {
                Column {
                    if (isQueueTrigger == true && onPlayNextFromQueue != null) {
                        ListItem(
                            headlineContent = {
                                Text(text = stringResource(R.string.play_next))
                            },
                            leadingContent = {
                                Icon(
                                    painter = painterResource(R.drawable.playlist_play),
                                    contentDescription = null,
                                )
                            },
                            modifier =
                                Modifier.clickable {
                                    onPlayNextFromQueue()
                                    onDismiss()
                                },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }

                    if (isQueueTrigger == true && onRemoveFromQueue != null) {
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = stringResource(R.string.remove_from_queue),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            leadingContent = {
                                Icon(
                                    painter = painterResource(R.drawable.delete),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            modifier =
                                Modifier.clickable {
                                    onRemoveFromQueue()
                                    onDismiss()
                                },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )

                        // "Play Next" and "Add to Queue" surface on every queue song's overflow
                        // menu so the user can re-order the queue without going back to the
                        // search/list page. Mirrors SongMenu.kt's pattern.
                        ListItem(
                            headlineContent = { Text(text = stringResource(R.string.play_next)) },
                            leadingContent = {
                                Icon(
                                    painter = painterResource(R.drawable.playlist_play),
                                    contentDescription = null,
                                )
                            },
                            modifier =
                                Modifier.clickable {
                                    mediaMetadata?.toMediaItem()?.let { playerConnection.playNext(it) }
                                    onDismiss()
                                },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                        ListItem(
                            headlineContent = { Text(text = stringResource(R.string.add_to_queue)) },
                            leadingContent = {
                                Icon(
                                    painter = painterResource(R.drawable.queue_music),
                                    contentDescription = null,
                                )
                            },
                            modifier =
                                Modifier.clickable {
                                    mediaMetadata?.toMediaItem()?.let { playerConnection.addToQueue(it) }
                                    onDismiss()
                                },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }

                    ListItem(
                        headlineContent = { Text(text = stringResource(R.string.details)) },
                        leadingContent = {
                            Icon(
                                painter = painterResource(R.drawable.info),
                                contentDescription = null,
                            )
                        },
                        modifier =
                            Modifier.clickable {
                                onShowDetailsDialog()
                                onDismiss()
                            },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )

                    if (isQueueTrigger != true) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )

                        // Sleep timer row — appears in the secondary list section alongside
                        // Equalizer and Tempo & Pitch. Tapping it opens the inline Apple
                        // Music–style sheet at the bottom of the menu.
                        //
                        // Hidden in Apple Music player style because the sleep timer is
                        // already available as a dedicated pill in the in-place queue sheet
                        // (AppleMusicQueueSheet's top pill row). Showing it here too is
                        // redundant and clutters the menu.
                        if (playerDesignStyle != PlayerDesignStyle.APPLE_MUSIC) {
                            ListItem(
                                headlineContent = { Text(text = stringResource(R.string.sleep_timer)) },
                                leadingContent = {
                                    Icon(
                                        painter = painterResource(R.drawable.bedtime),
                                        contentDescription = null,
                                    )
                                },
                                modifier =
                                    Modifier.clickable { showSleepTimerSheet = true },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )

                            HorizontalDivider(
                                modifier = Modifier.padding(start = 56.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }

                        ListItem(
                            headlineContent = { Text(text = stringResource(R.string.equalizer)) },
                            leadingContent = {
                                Icon(
                                    painter = painterResource(R.drawable.equalizer),
                                    contentDescription = null,
                                )
                            },
                            modifier = Modifier.clickable { showEqualizerDialog = true },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )

                        ListItem(
                            headlineContent = { Text(text = stringResource(R.string.tempo_and_pitch)) },
                            leadingContent = {
                                Icon(
                                    painter = painterResource(R.drawable.speed),
                                    contentDescription = null,
                                )
                            },
                            supportingContent = {
                                val playbackParameters by playerConnection.playbackParameters.collectAsStateWithLifecycle()
                                Text(
                                    text = "x${formatMultiplier(
                                        playbackParameters.speed,
                                    )} • x${formatMultiplier(playbackParameters.pitch)}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            modifier = Modifier.clickable { showPitchTempoDialog = true },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }
            }
        }
        } // end else (menu body)
    }
}

@Composable
private fun PlayerVolumeCard(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val safeVolume = volume.coerceIn(0f, 1f)

    MenuHeaderCard(
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.volume),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )

                Text(
                    text = "${(safeVolume * 100).roundToInt()}%",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.volume_off),
                        contentDescription = stringResource(R.string.minimum_volume),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )

                    VolumeSliderL(
                        value = safeVolume,
                        onValueChange = onVolumeChange,
                        modifier = Modifier.weight(1f),
                    )

                    Icon(
                        painter = painterResource(R.drawable.volume_up),
                        contentDescription = stringResource(R.string.maximum_volume),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun VolumeSliderL(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val safeValue = value.coerceIn(0f, 1f)
    var sliderValue by remember { mutableFloatStateOf(safeValue) }
    var isDragging by remember { mutableStateOf(false) }

    LaunchedEffect(safeValue) {
        if (!isDragging) sliderValue = safeValue
    }

    // Do NOT constrain the Slider's height. The Material3 Slider's internal touch
    // target is 48dp tall; forcing a smaller height clips the touch area and makes the
    // thumb impossible to drag — the value updates in state but the thumb never visibly
    // moves.
    Slider(
        value = sliderValue,
        onValueChange = { updated ->
            isDragging = true
            val coerced = updated.coerceIn(0f, 1f)
            sliderValue = coerced
            onValueChange(coerced)
        },
        onValueChangeFinished = { isDragging = false },
        valueRange = 0f..1f,
        modifier = modifier,
        thumb = {
            Box(
                modifier =
                    Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
            )
        },
        colors =
            SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
    )
}

@Composable
fun TempoPitchDialog(onDismiss: () -> Unit) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val initialSpeed = remember { playerConnection.player.playbackParameters.speed }
    val initialPitch = remember { playerConnection.player.playbackParameters.pitch }

    var tempo by remember {
        mutableFloatStateOf(initialSpeed.safeCoerceIn(TempoMin, TempoMax, fallback = 1f))
    }

    var pitch by remember {
        mutableFloatStateOf(initialPitch.safeCoerceIn(PitchMin, PitchMax, fallback = 1f))
    }

    var pitchMode by rememberSaveable {
        mutableStateOf(
            if (isPitchSemitoneAligned(pitch)) PitchMode.Semitones else PitchMode.Multiplier,
        )
    }

    val applyPlaybackParameters: (Float, Float) -> Unit = { speed, pitchMultiplier ->
        playerConnection.player.playbackParameters =
            PlaybackParameters(
                speed.coerceIn(TempoMin, TempoMax),
                pitchMultiplier.coerceIn(PitchMin, PitchMax),
            )
    }

    AlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.tempo_and_pitch))
        },
        dismissButton = {
            TextButton(
                onClick = {
                    tempo = 1f
                    pitch = 1f
                    applyPlaybackParameters(tempo, pitch)
                },
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(R.string.reset))
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.speed),
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                    )

                    Text(
                        text = stringResource(R.string.tempo),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )

                    Text(
                        text = "x${formatMultiplier(tempo)}",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.End,
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    IconButton(
                        enabled = tempo > TempoMin,
                        onClick = {
                            tempo = (tempo - 0.01f).coerceIn(TempoMin, TempoMax).quantize(0.01f)
                            applyPlaybackParameters(tempo, pitch)
                        },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.remove),
                            contentDescription = null,
                        )
                    }

                    Slider(
                        value = multiplierToSlider(tempo),
                        onValueChange = { slider ->
                            val updated = sliderToMultiplier(slider).quantize(0.01f)
                            if (abs(updated - tempo) >= 0.005f) {
                                tempo = updated
                                applyPlaybackParameters(tempo, pitch)
                            }
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(),
                    )

                    IconButton(
                        enabled = tempo < TempoMax,
                        onClick = {
                            tempo = (tempo + 0.01f).coerceIn(TempoMin, TempoMax).quantize(0.01f)
                            applyPlaybackParameters(tempo, pitch)
                        },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.add),
                            contentDescription = null,
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                ) {
                    val presets = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
                    presets.forEach { preset ->
                        val selected = abs(tempo - preset) < 0.005f
                        FilterChip(
                            selected = selected,
                            onClick = {
                                tempo = preset
                                applyPlaybackParameters(tempo, pitch)
                            },
                            label = { Text("x${formatMultiplier(preset)}") },
                        )
                    }
                }

                HorizontalDivider()

                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.discover_tune),
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                    )

                    Text(
                        text = stringResource(R.string.pitch),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )

                    Text(
                        text =
                            when (pitchMode) {
                                PitchMode.Semitones -> {
                                    val semitones = pitchToSemitones(pitch)
                                    "${if (semitones > 0) "+" else ""}$semitones"
                                }

                                PitchMode.Multiplier -> {
                                    "x${formatMultiplier(pitch)}"
                                }
                            },
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.End,
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                ) {
                    FilterChip(
                        selected = pitchMode == PitchMode.Semitones,
                        onClick = { pitchMode = PitchMode.Semitones },
                        label = { Text(stringResource(R.string.pitch_mode_semitones_short)) },
                    )
                    FilterChip(
                        selected = pitchMode == PitchMode.Multiplier,
                        onClick = { pitchMode = PitchMode.Multiplier },
                        label = { Text(stringResource(R.string.pitch_mode_multiplier_short)) },
                    )
                }

                when (pitchMode) {
                    PitchMode.Semitones -> {
                        val currentSemitones = pitchToSemitones(pitch)
                        Slider(
                            value = currentSemitones.toFloat(),
                            onValueChange = { slider ->
                                val semitones = slider.roundToInt().coerceIn(-12, 12)
                                val updated = semitonesToPitch(semitones)
                                if (abs(updated - pitch) >= 0.0005f) {
                                    pitch = updated
                                    applyPlaybackParameters(tempo, pitch)
                                }
                            },
                            valueRange = -12f..12f,
                            steps = 23,
                            modifier = Modifier.fillMaxWidth(),
                            colors = SliderDefaults.colors(),
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                        ) {
                            val presets = listOf(-12, -7, -5, 0, 5, 7, 12)
                            presets.forEach { preset ->
                                val selected = currentSemitones == preset
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        pitch = semitonesToPitch(preset)
                                        applyPlaybackParameters(tempo, pitch)
                                    },
                                    label = { Text("${if (preset > 0) "+" else ""}$preset") },
                                )
                            }
                        }
                    }

                    PitchMode.Multiplier -> {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            IconButton(
                                enabled = pitch > PitchMin,
                                onClick = {
                                    pitch = (pitch - 0.01f).coerceIn(PitchMin, PitchMax).quantize(0.01f)
                                    applyPlaybackParameters(tempo, pitch)
                                },
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.remove),
                                    contentDescription = null,
                                )
                            }

                            Slider(
                                value = multiplierToSlider(pitch),
                                onValueChange = { slider ->
                                    val updated = sliderToMultiplier(slider).quantize(0.01f)
                                    if (abs(updated - pitch) >= 0.005f) {
                                        pitch = updated
                                        applyPlaybackParameters(tempo, pitch)
                                    }
                                },
                                valueRange = 0f..1f,
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(),
                            )

                            IconButton(
                                enabled = pitch < PitchMax,
                                onClick = {
                                    pitch = (pitch + 0.01f).coerceIn(PitchMin, PitchMax).quantize(0.01f)
                                    applyPlaybackParameters(tempo, pitch)
                                },
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.add),
                                    contentDescription = null,
                                )
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                        ) {
                            val presets = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
                            presets.forEach { preset ->
                                val selected = abs(pitch - preset) < 0.005f
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        pitch = preset
                                        applyPlaybackParameters(tempo, pitch)
                                    },
                                    label = { Text("x${formatMultiplier(preset)}") },
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}

private enum class PitchMode {
    Semitones,
    Multiplier,
}

private const val TempoMin = 0.25f
private const val TempoMax = 2f
private const val PitchMin = 0.25f
private const val PitchMax = 2f

private fun Float.safeCoerceIn(
    min: Float,
    max: Float,
    fallback: Float,
): Float {
    val safe = if (this.isFinite()) this else fallback
    return safe.coerceIn(min, max)
}

private fun Float.quantize(step: Float): Float {
    if (step <= 0f) return this
    return (round(this / step) * step).coerceAtLeast(0f)
}

private fun pitchToSemitones(pitch: Float): Int {
    val safePitch = pitch.safeCoerceIn(PitchMin, PitchMax, fallback = 1f).coerceAtLeast(0.0001f)
    return (12f * log2(safePitch)).roundToInt().coerceIn(-12, 12)
}

private fun semitonesToPitch(semitones: Int): Float = 2f.pow(semitones.toFloat() / 12f).coerceIn(PitchMin, PitchMax)

private fun isPitchSemitoneAligned(pitch: Float): Boolean {
    val safePitch = pitch.safeCoerceIn(PitchMin, PitchMax, fallback = 1f).coerceAtLeast(0.0001f)
    val semitones = (12f * log2(safePitch)).roundToInt()
    val reconstructed = 2f.pow(semitones.toFloat() / 12f)
    return abs(reconstructed - pitch) < 0.0015f
}

private fun formatMultiplier(multiplier: Float): String = String.format("%.2f", multiplier)

private fun sliderToMultiplier(slider: Float): Float {
    val t = slider.coerceIn(0f, 1f)
    val y = (t - 0.5f) * 2f
    val curve = 2.2f
    val absY = abs(y).pow(curve)
    val shaped =
        when {
            y > 0f -> absY
            y < 0f -> -absY
            else -> 0f
        }
    val exponent = if (y < 0f) 2f * shaped else shaped
    return 2f.pow(exponent).coerceIn(TempoMin, TempoMax)
}

private fun multiplierToSlider(multiplier: Float): Float {
    val m = multiplier.coerceIn(TempoMin, TempoMax)
    val log = log2(m)
    val curve = 2.2f
    val shaped = if (m < 1f) (log / 2f) else log
    val absShaped = abs(shaped).pow(1f / curve)
    val y =
        when {
            shaped > 0f -> absShaped
            shaped < 0f -> -absShaped
            else -> 0f
        }
    return (0.5f + y / 2f).coerceIn(0f, 1f)
}

private fun AudioSourceType.sourceLabelRes(): Int =
    when (this) {
        AudioSourceType.TIDAL -> R.string.source_tidal
        AudioSourceType.QOBUZ -> R.string.source_qobuz
        AudioSourceType.QOBUZ_BACKUP -> R.string.source_qobuz_backup
        AudioSourceType.DEEZER -> R.string.source_deezer
        AudioSourceType.APPLE -> R.string.source_apple_music
        AudioSourceType.AMAZON -> R.string.source_amazon
        AudioSourceType.QQ -> R.string.source_qq_music
        AudioSourceType.JIOSAAVN -> R.string.source_jiosaavn
        AudioSourceType.YOUTUBE -> R.string.source_youtube
    }

private fun AudioSourceType.sourceIconRes(): Int =
    when (this) {
        AudioSourceType.TIDAL -> R.drawable.provider_tidal
        AudioSourceType.QOBUZ -> R.drawable.provider_qobuz
        AudioSourceType.QOBUZ_BACKUP -> R.drawable.provider_qobuz
        AudioSourceType.DEEZER -> R.drawable.provider_deezer
        AudioSourceType.APPLE -> R.drawable.ic_music
        // No dedicated Amazon Music mark ships in drawable/ yet; ic_music is the same
        // stand-in APPLE uses above for the same reason.
        AudioSourceType.AMAZON -> R.drawable.ic_music
        AudioSourceType.QQ -> R.drawable.ic_music
        AudioSourceType.JIOSAAVN -> R.drawable.provider_jiosaavn
        AudioSourceType.YOUTUBE -> R.drawable.play
    }

/**
 * Unified cross-provider search result row. Each provider's backend (YTM / Tidal / JioSaavn /
 * future Qobuz, Deezer) maps its own native search type into this shape so the UI can render every
 * row the same way.
 */
private data class SourceSearchResult(
    val source: AudioSourceType,
    val trackId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val durationMs: Long?,
    val qualityLabel: String?,
    val songItem: SongItem?,
)

@Composable
private fun SongSourceDialog(
    sources: List<AudioSourceType>,
    selected: AudioSourceType?,
    onDismiss: () -> Unit,
    onSelect: (AudioSourceType?) -> Unit,
    onPlaySong: (SongItem) -> Unit,
    onPlayFromSource: (SourceSearchResult) -> Unit,
) {
    var searchMode by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var sourceFilter by rememberSaveable { mutableStateOf<AudioSourceType?>(null) }

    // Strings fetched at the Composable scope so they can be referenced safely inside produceState.
    val aacLabel = stringResource(R.string.quality_badge_aac)
    val saavnLabel = stringResource(R.string.quality_badge_saavn)
    val mp3Label = stringResource(R.string.quality_badge_mp3)
    val losslessLabel = stringResource(R.string.quality_badge_lossless)
    val noResultsText = stringResource(R.string.source_search_no_results)
    val noBackendText = stringResource(R.string.source_search_no_backend)

    // Deezer's catalogue search says nothing about what an account may stream, and the FLAC tier
    // needs a paid plan — so the badge has to come from the credential rather than the hit. Premium
    // is the flag the pool and the manual sign-in both carry; without one the best Deezer will serve
    // is 320kbps MP3, and labelling those rows "Lossless" would promise a quality resolve() cannot
    // deliver.
    val deezerLabel =
        remember(losslessLabel, mp3Label) {
            val availability = DeezerAudioProvider.accountAvailability()
            if (availability.manualPremium || availability.pooledPremium > 0) losslessLabel else mp3Label
        }

    // Provider filter → "search backend not yet available" empty state.
    val searchableSources =
        setOf(
            AudioSourceType.YOUTUBE,
            AudioSourceType.TIDAL,
            AudioSourceType.QOBUZ,
            AudioSourceType.QOBUZ_BACKUP,
            AudioSourceType.DEEZER,
            AudioSourceType.JIOSAAVN,
        )
    val backendMissing = sourceFilter != null && sourceFilter !in searchableSources

    // Debounced search — only fires when searchMode is on and query length >= 2.
    val results by produceState<List<SourceSearchResult>>(
        initialValue = emptyList(),
        key1 = searchQuery,
        key2 = sourceFilter,
        key3 = searchMode,
    ) {
        if (!searchMode || searchQuery.length < 2 || backendMissing) {
            value = emptyList()
            return@produceState
        }
        delay(350L) // debounce
        val out = mutableListOf<SourceSearchResult>()
        val searchYtm = sourceFilter == null || sourceFilter == AudioSourceType.YOUTUBE
        val searchTidal = sourceFilter == null || sourceFilter == AudioSourceType.TIDAL
        val searchQobuz = sourceFilter == null || sourceFilter == AudioSourceType.QOBUZ
        val searchQobuzBackup = sourceFilter == null || sourceFilter == AudioSourceType.QOBUZ_BACKUP
        val searchDeezer = sourceFilter == null || sourceFilter == AudioSourceType.DEEZER
        val searchSaavn = sourceFilter == null || sourceFilter == AudioSourceType.JIOSAAVN
        val searchAmazon = (sourceFilter == null || sourceFilter == AudioSourceType.AMAZON) && amazonSourceEnabled

        if (searchYtm) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val ytResult = YouTube.search(searchQuery, YouTube.SearchFilter.FILTER_SONG, useAccountContext = false).getOrNull()
                    if (ytResult == null) {
                        emptyList()
                    } else {
                        val songs = mutableListOf<SongItem>()
                        for (item in ytResult.items) {
                            if (item is SongItem) songs.add(item)
                        }
                        songs
                    }
                }.getOrNull()?.forEach { song ->
                    out.add(
                        SourceSearchResult(
                            source = AudioSourceType.YOUTUBE,
                            trackId = song.id,
                            title = song.title,
                            artist = song.artists.joinToString(", ") { it.name },
                            thumbnailUrl = song.thumbnail,
                            durationMs = song.duration?.toLong()?.times(1000L),
                            qualityLabel = aacLabel,
                            songItem = song,
                        ),
                    )
                }
            }
        }
        if (searchTidal) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val tidalQuery =
                        TidalAudioProvider.Query(
                            mediaId = "",
                            title = searchQuery,
                            artists = emptyList(),
                            album = null,
                            isrc = null,
                            durationMs = null,
                        )
                    TidalAudioProvider.searchCandidates(tidalQuery, limit = 8)
                }.getOrNull()?.forEach { candidate ->
                    out.add(
                        SourceSearchResult(
                            source = AudioSourceType.TIDAL,
                            trackId = candidate.trackId,
                            title = candidate.title,
                            artist = candidate.artist,
                            thumbnailUrl = candidate.thumbnailUrl,
                            durationMs = candidate.durationMs,
                            qualityLabel = losslessLabel,
                            songItem = null,
                        ),
                    )
                }
            }
        }
        if (searchAmazon) {
            withContext(Dispatchers.IO) {
                runCatching { AppleMusicCatalog.searchTrackSuggestions(searchQuery, limit = 8) }
                    .getOrNull()
                    ?.filterIsInstance<AppleMusicSearchItem.Track>()
                    ?.forEach { track ->
                        out.add(
                            SourceSearchResult(
                                source = AudioSourceType.AMAZON,
                                trackId = track.title,
                                title = track.title,
                                artist = track.artist,
                                thumbnailUrl = track.artworkUrl,
                                durationMs = track.durationMs,
                                qualityLabel = null,
                                songItem = null,
                            ),
                        )
                    }
            }
        }
        if (searchQobuz) {
            // Qobuz search needs pool tokens or proxy instances configured. The
            // searchCandidates method handles that internally — it returns an
            // empty list when no backends are configured, in which case the user
            // just sees no Qobuz rows in the results (same as if the query had
            // no hits). Must run on IO because searchCandidates does network
            // calls (PoolAccountManager.qobuzAccounts + discoverInstances +
            // the actual search API call).
            withContext(Dispatchers.IO) {
                runCatching { QobuzAudioProvider.searchCandidates(searchQuery, limit = 8) }
                    .getOrDefault(emptyList())
                    .forEach { candidate ->
                        // If Qobuz didn't return a thumbnail, try to get one
                        // from YouTube by searching for the track.
                        val thumb = candidate.thumbnailUrl ?: run {
                            val term = listOfNotNull(candidate.artist?.takeIf(String::isNotBlank), candidate.title).joinToString(" ")
                            val ytResult = YouTube.search(term, YouTube.SearchFilter.FILTER_SONG, useAccountContext = false).getOrNull()
                            if (ytResult == null) null
                            else {
                                var found: SongItem? = null
                                for (item in ytResult.items) {
                                    if (item is SongItem) { found = item; break }
                                }
                                found?.thumbnail
                            }
                        }
                        out.add(
                            SourceSearchResult(
                                source = AudioSourceType.QOBUZ,
                                trackId = candidate.trackId,
                                title = candidate.title,
                                artist = candidate.artist.orEmpty(),
                                thumbnailUrl = thumb,
                                durationMs = candidate.durationMs,
                                qualityLabel = losslessLabel,
                                songItem = null,
                            ),
                        )
                    }
            }
        }
        if (searchQobuzBackup) {
            // The kouzu.in mirror answers with its own catalogue index; every row it
            // returns is keyed by a YouTube video id, which is both the mirror's primary
            // key and the source of the row's cover art (no artwork field in the payload).
            withContext(Dispatchers.IO) {
                runCatching { QobuzBackupProvider.searchCandidates(searchQuery, limit = 8) }
                    .getOrDefault(emptyList())
                    .forEach { candidate ->
                        out.add(
                            SourceSearchResult(
                                source = AudioSourceType.QOBUZ_BACKUP,
                                trackId = candidate.videoId,
                                title = candidate.title,
                                artist = candidate.artist.orEmpty(),
                                thumbnailUrl = candidate.thumbnailUrl,
                                // The search payload carries no duration; the row simply
                                // omits it rather than showing a made-up length.
                                durationMs = null,
                                qualityLabel = if (candidate.isLossless) losslessLabel else aacLabel,
                                songItem = null,
                            ),
                        )
                    }
            }
        }
        if (searchDeezer) {
            withContext(Dispatchers.IO) {
                DeezerAudioProvider
                    .searchCandidates(searchQuery, limit = 8)
                    .forEach { candidate ->
                        out.add(
                            SourceSearchResult(
                                source = AudioSourceType.DEEZER,
                                trackId = candidate.trackId,
                                title = candidate.title,
                                artist = candidate.artist.orEmpty(),
                                thumbnailUrl = candidate.coverUrl,
                                durationMs = candidate.durationMs,
                                qualityLabel = deezerLabel,
                                songItem = null,
                            ),
                        )
                    }
            }
        }
        if (searchSaavn) {
            withContext(Dispatchers.IO) {
                runCatching { SaavnService.searchSongs(searchQuery).getOrDefault(emptyList()) }
                    .getOrDefault(emptyList())
                    .forEach { saavnSong ->
                    val cover = saavnSong.image.maxByOrNull { runCatching { it.quality.substringBefore("x").toInt() }.getOrDefault(0) }?.url
                    out.add(
                        SourceSearchResult(
                            source = AudioSourceType.JIOSAAVN,
                            trackId = saavnSong.id,
                            title = saavnSong.name,
                            artist = saavnSong.artists.primary.joinToString(", ") { it.name },
                            thumbnailUrl = cover,
                            durationMs = saavnSong.duration?.toLong()?.times(1000L),
                            qualityLabel = saavnLabel,
                            songItem = null,
                        ),
                    )
                }
            }
        }
        value = out
    }

    DefaultDialog(
        onDismiss = onDismiss,
        buttons = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    ) {
        Column(modifier = Modifier.padding(top = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.play_from),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        searchMode = !searchMode
                        if (!searchMode) {
                            searchQuery = ""
                            sourceFilter = null
                        }
                    },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.search),
                        contentDescription = stringResource(R.string.download_source_search),
                        tint = if (searchMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            if (searchMode) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource(R.string.download_source_search_hint)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                )
                ChipsRow(
                    chips =
                        listOf(
                            null to stringResource(R.string.source_search_filter_all),
                            AudioSourceType.TIDAL to stringResource(R.string.source_tidal),
                            AudioSourceType.QOBUZ to stringResource(R.string.source_qobuz),
                            AudioSourceType.QOBUZ_BACKUP to stringResource(R.string.source_qobuz_backup),
                            AudioSourceType.DEEZER to stringResource(R.string.source_deezer),
                            AudioSourceType.APPLE to stringResource(R.string.source_apple_music),
                            AudioSourceType.AMAZON to stringResource(R.string.source_amazon),
                            AudioSourceType.QQ to stringResource(R.string.source_qq_music),
                            AudioSourceType.JIOSAAVN to stringResource(R.string.source_jiosaavn),
                            AudioSourceType.YOUTUBE to stringResource(R.string.source_youtube),
                        ),
                    currentValue = sourceFilter,
                    onValueUpdate = { sourceFilter = it },
                    icons =
                        mapOf(
                            null to R.drawable.search,
                            AudioSourceType.TIDAL to R.drawable.provider_tidal,
                            AudioSourceType.QOBUZ to R.drawable.provider_qobuz,
                            AudioSourceType.QOBUZ_BACKUP to R.drawable.provider_qobuz,
                            AudioSourceType.DEEZER to R.drawable.provider_deezer,
                            AudioSourceType.APPLE to R.drawable.ic_music,
                            AudioSourceType.AMAZON to R.drawable.ic_music,
                            AudioSourceType.QQ to R.drawable.ic_music,
                            AudioSourceType.JIOSAAVN to R.drawable.provider_jiosaavn,
                            AudioSourceType.YOUTUBE to R.drawable.play,
                        ),
                )
                when {
                    backendMissing -> {
                        Text(
                            text = noBackendText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        )
                    }
                    searchQuery.length < 2 -> {
                        Text(
                            text = noResultsText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        )
                    }
                    results.isEmpty() -> {
                        Text(
                            text = noResultsText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        )
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp),
                        ) {
                            items(results, key = { result -> "${result.source.name}:${result.trackId}" }) { result ->
                                SourceSearchResultRow(result = result) {
                                    // YTM results seed the queue directly. Non-YT results
                                    // (JioSaavn / Tidal / Qobuz / Deezer) don't carry a
                                    // YouTube-side SongItem — they go through onPlayFromSource
                                    // which searches YTM for a matching track and pins the
                                    // source override so playback resolves through the
                                    // picked source on the very first attempt.
                                    if (result.songItem != null) {
                                        onPlaySong(result.songItem)
                                    } else {
                                        onPlayFromSource(result)
                                    }
                                    onDismiss()
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            }
                        }
                    }
                }
            } else {
                // "Automatic" clears the override so the song follows the global preferred-source order.
                SongSourceRow(
                    iconRes = R.drawable.tune,
                    label = stringResource(R.string.play_from_automatic),
                    checked = selected == null,
                    onClick = { onSelect(null) },
                )
                sources.forEach { source ->
                    SongSourceRow(
                        iconRes = source.sourceIconRes(),
                        label = stringResource(source.sourceLabelRes()),
                        checked = selected == source,
                        onClick = { onSelect(source) },
                    )
                }
            }
        }
    }
}

/** One search-result row. */
@Composable
private fun SourceSearchResultRow(
    result: SourceSearchResult,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!result.thumbnailUrl.isNullOrBlank()) {
            AsyncImage(
                model = result.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)),
            )
        } else {
            Box(
                modifier =
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.music_note),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = result.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val subtitle =
                buildString {
                    append(result.artist)
                    result.durationMs?.let { ms ->
                        val totalSec = ms / 1000
                        val mm = totalSec / 60
                        val ss = totalSec % 60
                        append(" · ").append("%d:%02d".format(mm, ss))
                    }
                }
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!result.qualityLabel.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    text = result.qualityLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        Icon(
            painter = painterResource(result.source.sourceIconRes()),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun SongSourceRow(
    iconRes: Int,
    label: String,
    checked: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = checked, onClick = onClick)
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.padding(horizontal = 12.dp).size(24.dp),
        )
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}
