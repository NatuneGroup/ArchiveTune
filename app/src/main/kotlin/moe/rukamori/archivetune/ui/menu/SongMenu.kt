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
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.datastore.preferences.core.edit
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.datasource.cache.CacheSpan
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.LocalDatabase
import moe.rukamori.archivetune.LocalDownloadUtil
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.LocalSyncUtils
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.canvas.SpotifyCanvasProvider
import moe.rukamori.archivetune.canvas.models.CanvasArtwork
import moe.rukamori.archivetune.constants.ArtistSeparatorsKey
import moe.rukamori.archivetune.constants.ExternalDownloaderEnabledKey
import moe.rukamori.archivetune.constants.ExternalDownloaderPackageKey
import moe.rukamori.archivetune.constants.ListThumbnailSize
import moe.rukamori.archivetune.constants.SpeedDialSongIdsKey
import moe.rukamori.archivetune.constants.SpotifyCanvasKey
import moe.rukamori.archivetune.db.entities.ArtistEntity
import moe.rukamori.archivetune.db.entities.Event
import moe.rukamori.archivetune.db.entities.fileExtension
import moe.rukamori.archivetune.db.entities.detectAudioExtensionFromSpans
import moe.rukamori.archivetune.db.entities.extensionToMimeType
import moe.rukamori.archivetune.db.entities.PlaylistSong
import moe.rukamori.archivetune.db.entities.Song
import moe.rukamori.archivetune.extensions.toMediaItem
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.models.toMediaMetadata
import moe.rukamori.archivetune.ui.player.CanvasArtworkPlaybackCache
import moe.rukamori.archivetune.ui.player.fetchCanvasArtworkForPlayback
import moe.rukamori.archivetune.playback.ExoDownloadService
import moe.rukamori.archivetune.playback.queues.YouTubeQueue
import moe.rukamori.archivetune.telegram.isTelegramMediaId
import moe.rukamori.archivetune.ui.component.MenuHeaderCard
import moe.rukamori.archivetune.ui.component.ListDialog
import moe.rukamori.archivetune.ui.component.LocalBottomSheetPageState
import moe.rukamori.archivetune.ui.component.MenuSurfaceSection
import moe.rukamori.archivetune.ui.component.NewAction
import moe.rukamori.archivetune.ui.component.NewActionGrid
import moe.rukamori.archivetune.ui.component.SongListItem
import moe.rukamori.archivetune.ui.component.TextFieldDialog
import moe.rukamori.archivetune.ui.utils.TrackInfoAndSpecs
import moe.rukamori.archivetune.ui.utils.YtimgResizePolicy
import moe.rukamori.archivetune.ui.utils.resize
import moe.rukamori.archivetune.utils.SpeedDialPin
import moe.rukamori.archivetune.utils.SpeedDialPinType
import moe.rukamori.archivetune.utils.parseSpeedDialPins
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.utils.serializeSpeedDialPins
import moe.rukamori.archivetune.utils.shareLocalAudio
import moe.rukamori.archivetune.utils.toggleSpeedDialPin
import moe.rukamori.archivetune.viewmodels.CachePlaylistViewModel
import timber.log.Timber

private data class CanvasSourceOption(
    val label: String,
    val artwork: CanvasArtwork,
)

@Composable
fun SongMenu(
    originalSong: Song,
    event: Event? = null,
    navController: NavController,
    playlistSong: PlaylistSong? = null,
    playlistBrowseId: String? = null,
    onDismiss: () -> Unit,
    isFromCache: Boolean = false,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val songState = database.song(originalSong.id).collectAsStateWithLifecycle(initialValue = originalSong)
    val song = songState.value ?: originalSong
    val download by LocalDownloadUtil.current
        .getDownload(originalSong.id)
        .collectAsStateWithLifecycle(initialValue = null)
    val coroutineScope = rememberCoroutineScope()
    val syncUtils = LocalSyncUtils.current
    var refetchIconDegree by remember { mutableFloatStateOf(0f) }

    val cacheViewModel = hiltViewModel<CachePlaylistViewModel>()

    val downloadUtil = LocalDownloadUtil.current

    // Direct export to the device's Downloads folder (via SAF CreateDocument).
    // The MIME type hint is derived from the *actual* cached audio bytes
    // (preferred) or the FormatEntity stored at download time, so a FLAC
    // stream from Qobuz exports with audio/flac rather than the previous
    // audio/mpeg fallback. The file extension is always detected from
    // magic bytes to avoid exporting lossy data with a .flac extension
    // (and vice versa).
    val songFormat by database.format(song.id).collectAsStateWithLifecycle(initialValue = null)
    val detectedExt by produceState(
        initialValue = songFormat?.fileExtension() ?: "mp3",
        song.id,
    ) {
        withContext(Dispatchers.IO) {
            val cache = downloadUtil.downloadCache
            val spans = getCachedSpansForKey(cache, song.id)
            if (spans.isNotEmpty()) {
                value = detectAudioExtensionFromSpans(spans)
            }
        }
    }
    val exportMimeType = extensionToMimeType(detectedExt)
    val exportToDownloadsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(exportMimeType)) { destUri ->
            if (destUri == null) return@rememberLauncherForActivityResult
            val songId = song.id
            val songTitle = song.song.title
            coroutineScope.launch {
                val result = exportDownloadedSongToUri(context, downloadUtil, destUri, songId, songTitle)
                val msgResId = result.fold(
                    onSuccess = { R.string.export_to_downloads_success },
                    onFailure = { R.string.export_to_folder_failed },
                )
                Toast.makeText(context, context.getString(msgResId), Toast.LENGTH_SHORT).show()
            }
        }

    val rotationAnimation by animateFloatAsState(
        targetValue = refetchIconDegree,
        animationSpec = tween(durationMillis = 800),
        label = "",
    )

    // Artist separators for splitting artist names
    val (artistSeparators) = rememberPreference(ArtistSeparatorsKey, defaultValue = ",;/&")
    val (externalDownloaderEnabled) = rememberPreference(ExternalDownloaderEnabledKey, defaultValue = false)
    val (externalDownloaderPackage) = rememberPreference(ExternalDownloaderPackageKey, defaultValue = "")
    val (speedDialSongIds, onSpeedDialSongIdsChange) = rememberPreference(SpeedDialSongIdsKey, "")
    val (spotifyCanvasEnabled) = rememberPreference(SpotifyCanvasKey, false)
    val speedDialPins = remember(speedDialSongIds) { parseSpeedDialPins(speedDialSongIds) }
    val songPin = remember(song.id) { SpeedDialPin(type = SpeedDialPinType.SONG, id = song.id) }
    val isInSpeedDial =
        remember(speedDialPins, songPin) {
            speedDialPins.any { it.type == songPin.type && it.id == songPin.id }
        }

    val orderedArtists by produceState(initialValue = emptyList<ArtistEntity>(), song) {
        withContext(Dispatchers.IO) {
            val artistMaps = database.songArtistMap(song.id).sortedBy { it.position }
            val sorted =
                artistMaps.mapNotNull { map ->
                    song.artists.firstOrNull { it.id == map.artistId }
                }
            value = sorted
        }
    }

    // Split artists by configured separators
    data class SplitArtist(
        val name: String,
        val originalArtist: ArtistEntity?,
    )

    val splitArtists =
        remember(orderedArtists, artistSeparators) {
            if (artistSeparators.isEmpty()) {
                orderedArtists.map { SplitArtist(it.name, it) }
            } else {
                val separatorRegex = "[${Regex.escape(artistSeparators)}]".toRegex()
                orderedArtists.flatMap { artist ->
                    val parts =
                        artist.name
                            .split(separatorRegex)
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                    if (parts.size > 1) {
                        // If the name contains separators, create split artists
                        // The first part keeps the original artist reference for navigation
                        parts.mapIndexed { index, name ->
                            SplitArtist(name, if (index == 0) artist else null)
                        }
                    } else {
                        listOf(SplitArtist(artist.name, artist))
                    }
                }
            }
        }

    var showEditDialog by rememberSaveable {
        mutableStateOf(false)
    }

    // Apple Music–style sleep timer sheet. Rendered inline at the top of the
    // menu (replacing the rest of the body) so the user can pick a duration
    // without leaving the song's overflow menu — mirrors the behaviour of
    // PlayerMenu's sleep timer entry.
    var showSleepTimerSheet by rememberSaveable { mutableStateOf(false) }

    val TextFieldValueSaver: Saver<TextFieldValue, *> =
        Saver(
            save = { it.text },
            restore = { text -> TextFieldValue(text, TextRange(text.length)) },
        )

    var titleField by rememberSaveable(stateSaver = TextFieldValueSaver) {
        mutableStateOf(TextFieldValue(song.song.title))
    }

    var artistField by rememberSaveable(stateSaver = TextFieldValueSaver) {
        mutableStateOf(
            TextFieldValue(
                song.artists
                    .firstOrNull()
                    ?.name
                    .orEmpty(),
            ),
        )
    }

    if (showEditDialog) {
        TextFieldDialog(
            icon = {
                Icon(
                    painter = painterResource(R.drawable.edit),
                    contentDescription = null,
                )
            },
            title = {
                Text(text = stringResource(R.string.edit_song))
            },
            textFields =
                listOf(
                    stringResource(R.string.song_title) to titleField,
                    stringResource(R.string.artist_name) to artistField,
                ),
            onTextFieldsChange = { index, newValue ->
                if (index == 0) {
                    titleField = newValue
                } else {
                    artistField = newValue
                }
            },
            onDoneMultiple = { values ->
                val newTitle = values[0]
                val newArtist = values[1]

                coroutineScope.launch {
                    database.query {
                        update(song.song.copy(title = newTitle, titleOverride = true))
                        val artist = song.artists.firstOrNull()
                        if (artist != null) {
                            update(artist.copy(name = newArtist))
                        }
                    }

                    showEditDialog = false
                    onDismiss()
                }
            },
            onDismiss = { showEditDialog = false },
        )
    }

    var showChoosePlaylistDialog by rememberSaveable {
        mutableStateOf(false)
    }

    var showErrorPlaylistAddDialog by rememberSaveable {
        mutableStateOf(false)
    }
    var showCanvasSourceDialog by rememberSaveable { mutableStateOf(false) }
    var canvasSources by remember(song.id) { mutableStateOf<List<CanvasSourceOption>>(emptyList()) }
    var canvasSourcesLoading by remember(song.id) { mutableStateOf(false) }
    var canvasSaving by remember(song.id) { mutableStateOf(false) }

    fun loadCanvasSources() {
        if (canvasSourcesLoading || canvasSaving) return
        canvasSourcesLoading = true
        coroutineScope.launch {
            val sources = withContext(Dispatchers.IO) {
                val byUrl = linkedMapOf<String, CanvasSourceOption>()
                val title = song.song.title
                val artist = song.artists.firstOrNull()?.name.orEmpty()
                val storefront = java.util.Locale.getDefault().country.lowercase().ifBlank { "us" }
                fetchCanvasArtworkForPlayback(
                    songTitleRaw = title,
                    artistNameRaw = artist,
                    storefront = storefront,
                    requireVertical = false,
                    forceRefresh = true,
                    strictIdentity = !song.song.isLocal,
                    albumTitle = song.song.albumName,
                )?.let { artwork ->
                    artwork.preferredAnimationUrl?.takeIf { it.isNotBlank() }?.let { url ->
                        byUrl[url] = CanvasSourceOption("ArchiveTune / Apple Music", artwork)
                    }
                }
                if (spotifyCanvasEnabled && !song.song.isLocal) {
                    runCatching {
                        SpotifyCanvasProvider.getByVideoId(
                            videoId = song.id,
                            songTitle = title,
                            artistName = artist,
                        )
                    }
                        .getOrNull()
                        ?.let { artwork ->
                            artwork.preferredAnimationUrl?.takeIf { it.isNotBlank() }?.let { url ->
                                byUrl.putIfAbsent(url, CanvasSourceOption("Spotify", artwork))
                            }
                        }
                }
                byUrl.values.toList()
            }
            canvasSources = sources
            canvasSourcesLoading = false
            if (sources.isEmpty()) {
                Toast.makeText(context, context.getString(R.string.canvas_unavailable), Toast.LENGTH_SHORT).show()
            } else {
                showCanvasSourceDialog = true
            }
        }
    }

    fun saveCanvasSource(source: CanvasSourceOption) {
        showCanvasSourceDialog = false
        canvasSaving = true
        coroutineScope.launch {
            val saved = withContext(Dispatchers.IO) {
                CanvasArtworkPlaybackCache.save(song.id, source.artwork)
            }
            canvasSaving = false
            Toast.makeText(
                context,
                context.getString(if (saved) R.string.canvas_saved else R.string.canvas_save_failed),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    if (showCanvasSourceDialog) {
        ListDialog(onDismiss = { showCanvasSourceDialog = false }) {
            item {
                ListItem(
                    headlineContent = { Text(text = stringResource(R.string.canvas_source_title)) },
                    leadingContent = {
                        Icon(painter = painterResource(R.drawable.image), contentDescription = null)
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            items(canvasSources, key = { it.label }) { source ->
                ListItem(
                    headlineContent = { Text(text = source.label) },
                    leadingContent = {
                        Icon(painter = painterResource(R.drawable.download), contentDescription = null)
                    },
                    modifier = Modifier.fillMaxWidth().clickable { saveCanvasSource(source) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }

    AddToPlaylistDialog(
        isVisible = showChoosePlaylistDialog,
        onGetSong = {
            listOf(song.id)
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

    if (showErrorPlaylistAddDialog) {
        ListDialog(
            onDismiss = {
                showErrorPlaylistAddDialog = false
                onDismiss()
            },
        ) {
            item {
                ListItem(
                    headlineContent = { Text(text = stringResource(R.string.already_in_playlist)) },
                    leadingContent = {
                        Image(
                            painter = painterResource(R.drawable.close),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
                            modifier = Modifier.size(ListThumbnailSize),
                        )
                    },
                    modifier = Modifier.clickable { showErrorPlaylistAddDialog = false },
                )
            }

            items(listOf(song)) { song ->
                SongListItem(song = song)
            }
        }
    }

    var showSelectArtistDialog by rememberSaveable {
        mutableStateOf(false)
    }

    if (showSelectArtistDialog) {
        ListDialog(
            onDismiss = { showSelectArtistDialog = false },
        ) {
            items(
                items = splitArtists.distinctBy { it.name },
                key = { it.name },
            ) { splitArtist ->
                ListItem(
                    headlineContent = {
                        Text(
                            text = splitArtist.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingContent = {
                        AsyncImage(
                            model =
                                splitArtist.originalArtist?.thumbnailUrl?.resize(
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
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                splitArtist.originalArtist?.let { artist ->
                                    navController.navigate("artist/${artist.id}")
                                    showSelectArtistDialog = false
                                    onDismiss()
                                }
                            },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }

    MenuHeaderCard {
        SongListItem(
            song = song,
            badges = {},
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            trailingContent = {
                IconButton(
                    onClick = {
                        coroutineScope.launch(Dispatchers.IO) {
                            try {
                                val requestedSong =
                                    database.withTransaction {
                                        val currentSong =
                                            getSongById(song.id)
                                                ?: run {
                                                    insert(song.toMediaMetadata())
                                                    getSongById(song.id)
                                                }
                                                ?: return@withTransaction null
                                        currentSong.song.toggleLike()
                                    } ?: return@launch
                                syncUtils.likeSong(requestedSong).onFailure { error ->
                                    Timber.w(error, "Failed to update liked song ${song.id}")
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, R.string.error_unknown, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                Timber.e(error, "Failed to prepare liked song ${song.id}")
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, R.string.error_unknown, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                ) {
                    Icon(
                        painter = painterResource(if (song.song.liked) R.drawable.favorite else R.drawable.favorite_border),
                        tint = if (song.song.liked) MaterialTheme.colorScheme.error else LocalContentColor.current,
                        contentDescription = null,
                    )
                }
            },
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    val bottomSheetPageState = LocalBottomSheetPageState.current
    val isLocalSong = song.song.isLocal
    // Telegram tracks have no YouTube watch endpoint, so YouTube-only actions (e.g. Start radio)
    // are hidden for them — they still support play next / add to queue / add to playlist.
    val isTelegramSong = song.song.id.isTelegramMediaId()

    val startRadioText = stringResource(R.string.start_radio)
    val playNextText = stringResource(R.string.play_next)
    val addToQueueText = stringResource(R.string.add_to_queue)
    val addToPlaylistText = stringResource(R.string.add_to_playlist)
    val shareText = stringResource(R.string.share)
    val editText = stringResource(R.string.edit)

    val primaryActions =
        remember(
            song,
            startRadioText,
            playNextText,
            addToQueueText,
            shareText,
            editText,
            isLocalSong,
            isTelegramSong,
            onDismiss,
            playerConnection,
        ) {
            buildList {
                if (!isLocalSong && !isTelegramSong) {
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
                            text = startRadioText,
                            onClick = {
                                onDismiss()
                                playerConnection.playQueue(YouTubeQueue.radio(song.toMediaMetadata()))
                            },
                        ),
                    )
                }
                add(
                    NewAction(
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.playlist_play),
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        text = playNextText,
                        onClick = {
                            onDismiss()
                            playerConnection.playNext(song.toMediaItem())
                        },
                    ),
                )
                add(
                    NewAction(
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.queue_music),
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        text = addToQueueText,
                        onClick = {
                            onDismiss()
                            playerConnection.addToQueue(song.toMediaItem())
                        },
                    ),
                )
                add(
                    NewAction(
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.share),
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        text = shareText,
                        onClick = {
                            onDismiss()
                            if (isLocalSong) {
                                shareLocalAudio(context, song.id, song.format?.mimeType)
                            } else {
                                val intent =
                                    Intent().apply {
                                        action = Intent.ACTION_SEND
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, "https://music.youtube.com/watch?v=${song.id}")
                                    }
                                context.startActivity(Intent.createChooser(intent, null))
                            }
                        },
                    ),
                )
                add(
                    NewAction(
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.edit),
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        text = editText,
                        onClick = { showEditDialog = true },
                    ),
                )
            }
        }

    val showMutationSection = event != null || playlistSong != null || isFromCache || !isLocalSong

    LazyColumn(
        contentPadding =
            PaddingValues(
                start = 0.dp,
                top = 0.dp,
                end = 0.dp,
                bottom = 8.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding(),
            ),
    ) {
        // When the user taps "Sleep timer", replace the menu body with the
        // Apple Music–style picker sheet. Keeping the song header above gives
        // the user context that this sheet still belongs to the current song,
        // while the rest of the menu items are hidden so the sheet is
        // immediately visible without scrolling.
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
                Spacer(modifier = Modifier.height(8.dp))
            }

            item {
                MenuSurfaceSection(modifier = Modifier.padding(vertical = 6.dp)) {
                    NewActionGrid(
                        actions = primaryActions,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                    )
                }
            }

        item {
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (!isLocalSong) {
            item {
                MenuSurfaceSection(modifier = Modifier.padding(vertical = 6.dp)) {
                    ListItem(
                        headlineContent = {
                            Text(
                                text =
                                    stringResource(
                                        if (song.song.inLibrary == null) {
                                            R.string.add_to_library
                                        } else {
                                            R.string.remove_from_library
                                        },
                                    ),
                            )
                        },
                        leadingContent = {
                            Icon(
                                painter =
                                    painterResource(
                                        if (song.song.inLibrary == null) {
                                            R.drawable.library_add
                                        } else {
                                            R.drawable.library_add_check
                                        },
                                    ),
                                contentDescription = null,
                            )
                        },
                        modifier =
                            Modifier.clickable {
                                onDismiss()
                                database.query {
                                    update(song.song.toggleLibrary())
                                }
                            },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        item {
            MenuSurfaceSection(modifier = Modifier.padding(vertical = 6.dp)) {
                ListItem(
                    headlineContent = { Text(text = addToPlaylistText) },
                    leadingContent = {
                        Icon(
                            painter = painterResource(R.drawable.playlist_add),
                            contentDescription = null,
                        )
                    },
                    modifier = Modifier.clickable { showChoosePlaylistDialog = true },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(4.dp))
        }

        item {
            MenuSurfaceSection(modifier = Modifier.padding(vertical = 6.dp)) {
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
                            painter = painterResource(if (isInSpeedDial) R.drawable.bookmark_filled else R.drawable.bookmark),
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

        item {
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (showMutationSection) {
            item {
                MenuSurfaceSection(modifier = Modifier.padding(vertical = 6.dp)) {
                    val dividerModifier = Modifier.padding(start = 56.dp)
                    Column {
                        if (event != null) {
                            ListItem(
                                headlineContent = {
                                    Text(
                                        text = stringResource(R.string.remove_from_history),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                leadingContent = {
                                    Icon(
                                        painter = painterResource(R.drawable.delete),
                                        tint = MaterialTheme.colorScheme.error,
                                        contentDescription = null,
                                    )
                                },
                                modifier =
                                    Modifier.clickable {
                                        onDismiss()
                                        database.query {
                                            delete(event)
                                        }
                                    },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        }

                        if (event != null) {
                            HorizontalDivider(
                                modifier = dividerModifier,
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }

                        if (playlistSong != null) {
                            ListItem(
                                headlineContent = {
                                    Text(
                                        text = stringResource(R.string.remove_from_playlist),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                leadingContent = {
                                    Icon(
                                        painter = painterResource(R.drawable.delete),
                                        tint = MaterialTheme.colorScheme.error,
                                        contentDescription = null,
                                    )
                                },
                                modifier =
                                    Modifier.clickable {
                                        val map = playlistSong.map
                                        coroutineScope.launch(Dispatchers.IO) {
                                            val browseId = playlistBrowseId
                                            if (browseId != null) {
                                                val remoteResult = removeSongFromRemotePlaylist(browseId, map)
                                                if (remoteResult.isFailure) {
                                                    withContext(Dispatchers.Main) {
                                                        Toast
                                                            .makeText(
                                                                context,
                                                                context.getString(R.string.error_unknown),
                                                                Toast.LENGTH_SHORT,
                                                            ).show()
                                                        onDismiss()
                                                    }
                                                    return@launch
                                                }
                                            }
                                            database.withTransaction {
                                                val maxPosition = maxPlaylistSongPosition(map.playlistId) ?: map.position
                                                if (map.position < maxPosition) {
                                                    move(map.playlistId, map.position, maxPosition)
                                                }
                                                delete(map)
                                            }
                                            withContext(Dispatchers.Main) {
                                                onDismiss()
                                            }
                                        }
                                    },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )

                            HorizontalDivider(
                                modifier = dividerModifier,
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }

                        if (isFromCache) {
                            ListItem(
                                headlineContent = {
                                    Text(
                                        text = stringResource(R.string.remove_from_cache),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                leadingContent = {
                                    Icon(
                                        painter = painterResource(R.drawable.delete),
                                        tint = MaterialTheme.colorScheme.error,
                                        contentDescription = null,
                                    )
                                },
                                modifier =
                                    Modifier.clickable {
                                        onDismiss()
                                        cacheViewModel.removeSongFromCache(song.id)
                                    },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )

                            HorizontalDivider(
                                modifier = dividerModifier,
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }

                        if (!isLocalSong) {
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
                                                tint = MaterialTheme.colorScheme.error,
                                                contentDescription = null,
                                            )
                                        },
                                        modifier =
                                            Modifier.clickable {
                                                DownloadService.sendRemoveDownload(
                                                    context,
                                                    ExoDownloadService::class.java,
                                                    song.id,
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
                                                    song.id,
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
                                                // Remove any existing failed/queued download
                                                // before starting a fresh one. Stale entries
                                                // in the download cache can cause HTTP 416
                                                // (Range Not Satisfiable) errors when the
                                                // stream URL or content-length changes
                                                // between attempts.
                                                val dl = download
                                                if (dl != null &&
                                                    dl.state != Download.STATE_COMPLETED
                                                ) {
                                                    DownloadService.sendRemoveDownload(
                                                        context,
                                                        ExoDownloadService::class.java,
                                                        song.id,
                                                        false,
                                                    )
                                                }
                                                // Also clear any partial cached data from the
                                                // download cache. Stale bytes can cause HTTP 416
                                                // (Range Not Satisfiable) when the stream URL or
                                                // content-length changes between attempts.
                                                downloadUtil.downloadCache.removeResource(song.id)
                                                val downloadRequest =
                                                    DownloadRequest
                                                        .Builder(song.id, song.id.toUri())
                                                        .setCustomCacheKey(song.id)
                                                        .setData(song.song.title.toByteArray())
                                                        .build()
                                                DownloadService.sendAddDownload(
                                                    context,
                                                    ExoDownloadService::class.java,
                                                    downloadRequest,
                                                    false,
                                                )
                                            },
                                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    )
                                }
                            }
                            // Export — only shown when the download has actually completed.
                            // Uses the correct file extension based on the audio codec
                            // (FLAC for lossless, OPUS/M4A for lossy, etc.).
                            if (download?.state == Download.STATE_COMPLETED) {
                                val safeTitle = song.song.title.trim()
                                    .replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "audio" }
                                val ext = detectedExt
                                ListItem(
                                    headlineContent = {
                                        Text(text = stringResource(R.string.export))
                                    },
                                    leadingContent = {
                                        Icon(
                                            painter = painterResource(R.drawable.download),
                                            contentDescription = null,
                                        )
                                    },
                                    modifier =
                                        Modifier.clickable {
                                            exportToDownloadsLauncher.launch("$safeTitle.$ext")
                                        },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                )
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
                                            val url = "https://music.youtube.com/watch?v=${song.id}"
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
            }

            item {
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        item {
            MenuSurfaceSection(modifier = Modifier.padding(vertical = 6.dp)) {
                Column {
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
                                    navController.navigate("artist/${splitArtists[0].originalArtist!!.id}")
                                    onDismiss()
                                } else {
                                    showSelectArtistDialog = true
                                }
                            },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )

                    if (song.song.albumId != null) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )

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
                                    navController.navigate("album/${song.song.albumId}")
                                },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )

                    // Sleep timer row — appears in the secondary section alongside
                    // View Artist / View Album. Tapping it opens the inline Apple
                    // Music–style sheet at the top of the menu with a 0..120 min
                    // slider and the standard preset chips.
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
                }
            }
        }

        // "Don't recommend this song again" — blocks the song from the discovery/recommendation
        // feeds without blocking the artist. The user can still play it manually and undo the
        // block at any time by tapping the same menu item (which then reads "Allow recommendations
        // for this song again"). Excluded from local songs because recommendations never include
        // local tracks anyway.
        if (!song.song.isLocal) item {
            val blockedSongIds by database.blockedSongIds().collectAsState(initial = emptyList())
            val isSongBlocked = remember(blockedSongIds, song.id) { song.id in blockedSongIds }
            MenuSurfaceSection(modifier = Modifier.padding(vertical = 6.dp)) {
                Column {
                    ListItem(
                        headlineContent = {
                            Text(
                                text =
                                    stringResource(
                                        if (isSongBlocked) {
                                            R.string.undo_dont_recommend_song_again
                                        } else {
                                            R.string.dont_recommend_song_again
                                        },
                                    ),
                            )
                        },
                        leadingContent = {
                            Icon(
                                painter = painterResource(R.drawable.block),
                                contentDescription = null,
                            )
                        },
                        modifier =
                            Modifier.clickable {
                                coroutineScope.launch {
                                    database.setSongBlockedAt(
                                        songId = song.id,
                                        blockedAt = if (isSongBlocked) null else java.time.LocalDateTime.now(),
                                    )
                                    Toast
                                        .makeText(
                                            context,
                                            context.getString(
                                                if (isSongBlocked) {
                                                    R.string.song_unblocked_success
                                                } else {
                                                    R.string.song_blocked_success
                                                },
                                            ),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    onDismiss()
                                }
                            },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(12.dp))
        }

        item {
            MenuSurfaceSection(modifier = Modifier.padding(vertical = 6.dp)) {
                Column {
                    ListItem(
                        headlineContent = {
                            Text(
                                text = stringResource(
                                    if (canvasSaving) R.string.canvas_saving else R.string.save_canvas,
                                ),
                            )
                        },
                        leadingContent = {
                            if (canvasSaving) {
                                CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                            } else {
                                Icon(
                                    painter = painterResource(R.drawable.image),
                                    contentDescription = null,
                                )
                            }
                        },
                        modifier = Modifier.clickable { loadCanvasSources() },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )

                    ListItem(
                        headlineContent = { Text(text = stringResource(R.string.download_cover)) },
                        leadingContent = {
                            Icon(
                                painter = painterResource(R.drawable.image),
                                contentDescription = null,
                            )
                        },
                        modifier =
                            Modifier.clickable {
                                val url = song.song.thumbnailUrl
                                if (url.isNullOrBlank()) {
                                    android.widget.Toast
                                        .makeText(
                                            context,
                                            context.getString(R.string.cover_save_no_artwork),
                                            android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                    return@clickable
                                }
                                android.widget.Toast
                                    .makeText(
                                        context,
                                        context.getString(R.string.cover_saving),
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                coroutineScope.launch(Dispatchers.IO) {
                                    val fileName = "cover_${song.id}".replace(Regex("[^A-Za-z0-9_\\-]"), "_")
                                    val saved = moe.rukamori.archivetune.utils.saveCoverArtworkFromUrl(
                                        context = context,
                                        thumbnailUrl = url,
                                        fileName = fileName,
                                    )
                                    val msgRes = if (saved != null) {
                                        R.string.cover_saved
                                    } else {
                                        R.string.cover_save_failed
                                    }
                                    withContext(Dispatchers.Main) {
                                        android.widget.Toast
                                            .makeText(context, context.getString(msgRes), android.widget.Toast.LENGTH_SHORT)
                                            .show()
                                    }
                                }
                            },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )

                    if (!isLocalSong) {
                        ListItem(
                            headlineContent = { Text(text = stringResource(R.string.refetch)) },
                            leadingContent = {
                                Icon(
                                    painter = painterResource(R.drawable.sync),
                                    contentDescription = null,
                                    modifier = Modifier.graphicsLayer(rotationZ = rotationAnimation),
                                )
                            },
                            modifier =
                                Modifier.clickable {
                                    refetchIconDegree -= 360
                                    coroutineScope.launch(Dispatchers.IO) {
                                        YouTube.queue(listOf(song.id)).onSuccess {
                                            val newSong = it.firstOrNull()
                                            if (newSong != null) {
                                                database.transaction {
                                                    update(song, newSong.toMediaMetadata())
                                                }
                                            }
                                        }
                                    }
                                },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }

                    ListItem(
                        headlineContent = { Text(text = stringResource(R.string.track_info_menu_action)) },
                        leadingContent = {
                            Icon(
                                painter = painterResource(R.drawable.info),
                                contentDescription = null,
                            )
                        },
                        modifier =
                            Modifier.clickable {
                                onDismiss()
                                bottomSheetPageState.show {
                                    TrackInfoAndSpecs(
                                        trackId = song.id,
                                        title = song.title,
                                        artists = song.artists.joinToString { it.name },
                                        album = song.album?.title ?: song.song.albumName,
                                        artwork = song.thumbnailUrl,
                                        durationMs = song.song.duration.takeIf { it > 0 }?.times(1_000L),
                                    )
                                }
                            },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
        } // end else (showSleepTimerSheet)
    }
}


/**
 * Exports a downloaded song to a pre-existing [destUri] (e.g. from CreateDocument).
 */
private suspend fun exportDownloadedSongToUri(
    context: android.content.Context,
    downloadUtil: moe.rukamori.archivetune.playback.DownloadUtil,
    destUri: Uri,
    songId: String,
    songTitle: String,
): Result<Uri> = runCatching {
    withContext(Dispatchers.IO) {
        val cache = downloadUtil.downloadCache
        val spans = getCachedSpansForKey(cache, songId)
        if (spans.isEmpty()) {
            throw IllegalStateException("Download cache is empty for this song")
        }
        writeSpansToUri(context, destUri, spans)
        destUri
    }
}

/**
 * Resolves cached spans for a given [songId]. Tries the key directly first,
 * then checks the source-prefixed keys used by Qobuz/Tidal downloads
 * ("qobuz:<songId>" and "tidal:<songId>") so lossless exports pull the
 * actual FLAC bytes instead of falling through to a YouTube Music stream,
 * and finally falls back to scanning all cache keys for any entry that
 * ends with the songId.
 */
private fun getCachedSpansForKey(
    cache: androidx.media3.datasource.cache.Cache,
    songId: String,
): java.util.NavigableSet<androidx.media3.datasource.cache.CacheSpan> {
    cache.getCachedSpans(songId)
        .takeIf { it.isNotEmpty() }
        ?.let { return it }

    // Source-prefixed cache keys (set by DownloadUtil.resolvePreferredDownloadDataSpec).
    for (prefix in listOf("qobuz:", "tidal:")) {
        val sourceKey = "$prefix$songId"
        cache.getCachedSpans(sourceKey)
            .takeIf { it.isNotEmpty() }
            ?.let { return it }
    }

    // Last-resort scan: the download may have been stored under a URI-derived
    // key. Match any key whose final path segment equals the songId.
    for (key in cache.keys) {
        val cleanKey = key.substringAfterLast("/")
        if (cleanKey == songId || key == songId || key.endsWith(":$songId")) {
            val spans = cache.getCachedSpans(key)
            if (spans.isNotEmpty()) return spans
        }
    }
    return java.util.TreeSet()
}

/**
 * Writes cached [spans] (sorted by position) to the output stream at [destUri].
 */
private fun writeSpansToUri(
    context: android.content.Context,
    destUri: Uri,
    spans: java.util.NavigableSet<androidx.media3.datasource.cache.CacheSpan>,
) {
    context.contentResolver.openOutputStream(destUri, "w")?.use { output ->
        spans.sortedBy { it.position }.forEach { span ->
            java.io.FileInputStream(span.file).use { input ->
                input.copyTo(output)
            }
        }
        output.flush()
    } ?: throw IllegalStateException("Could not open destination stream")
}
