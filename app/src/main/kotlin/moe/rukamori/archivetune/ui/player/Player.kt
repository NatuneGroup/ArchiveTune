/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.player

import android.content.Context
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Player.STATE_BUFFERING
import androidx.media3.common.Player.STATE_READY
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.palette.graphics.Palette
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.materialkolor.ktx.toColor
import com.materialkolor.ktx.toHct
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.LocalAnimationsDisabled
import moe.rukamori.archivetune.LocalDownloadUtil
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.LocalStableSystemBarsTopPadding
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.canvas.models.CanvasArtwork
import moe.rukamori.archivetune.canvas.CanvasVideo
import moe.rukamori.archivetune.canvas.CanvasSource
import moe.rukamori.archivetune.ui.player.immersive.ImmersivePlayerScreen
import moe.rukamori.archivetune.ui.player.immersive.ImmersivePlayerEvent
import moe.rukamori.archivetune.viewmodels.ImmersivePlayerViewModel
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import moe.rukamori.archivetune.constants.ArchiveTuneCanvasKey
import moe.rukamori.archivetune.constants.BackdropBlurAmountKey
import moe.rukamori.archivetune.constants.BackdropEnabledKey
import moe.rukamori.archivetune.constants.BlurRadiusKey
import moe.rukamori.archivetune.constants.DarkModeKey
import moe.rukamori.archivetune.constants.DisableBlurKey
import moe.rukamori.archivetune.constants.EnableHapticFeedbackKey
import moe.rukamori.archivetune.constants.EnableVideoPlaybackKey
import moe.rukamori.archivetune.constants.MaxCanvasCacheSizeKey
import moe.rukamori.archivetune.constants.PlayerBackgroundStyle
import moe.rukamori.archivetune.constants.PlayerBackgroundStyleKey
import moe.rukamori.archivetune.constants.PlayerButtonsStyle
import moe.rukamori.archivetune.constants.PlayerButtonsStyleKey
import moe.rukamori.archivetune.constants.PlayerCustomBlurKey
import moe.rukamori.archivetune.constants.PlayerCustomBrightnessKey
import moe.rukamori.archivetune.constants.PlayerCustomContrastKey
import moe.rukamori.archivetune.constants.PlayerCustomImageUriKey
import moe.rukamori.archivetune.constants.PlayerDesignStyle
import moe.rukamori.archivetune.constants.PlayerDesignStyleKey
import moe.rukamori.archivetune.constants.PoTokenGvsKey
import moe.rukamori.archivetune.constants.PoTokenPlayerKey
import moe.rukamori.archivetune.constants.QueuePeekHeight
import moe.rukamori.archivetune.constants.ShowCodecOnPlayerKey
import moe.rukamori.archivetune.constants.ShowPlayerVolumeBarKey
import moe.rukamori.archivetune.constants.SliderStyle
import moe.rukamori.archivetune.constants.SliderStyleKey
import moe.rukamori.archivetune.constants.SpotifyCanvasKey
import moe.rukamori.archivetune.constants.ThumbnailCornerRadiusKey
import moe.rukamori.archivetune.extensions.metadata
import moe.rukamori.archivetune.extensions.togglePlayPause
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.playback.artwork.PlayerPaletteCacheKey
import moe.rukamori.archivetune.playback.artwork.guessArtworkProvider
import moe.rukamori.archivetune.ui.component.BottomSheet
import moe.rukamori.archivetune.ui.component.BottomSheetState
import moe.rukamori.archivetune.ui.component.COLLAPSED_ANCHOR
import moe.rukamori.archivetune.ui.component.LocalBottomSheetPageState
import moe.rukamori.archivetune.ui.component.LocalMenuState
import moe.rukamori.archivetune.ui.component.rememberBottomSheetState
import moe.rukamori.archivetune.ui.menu.PlayerMenu
import moe.rukamori.archivetune.ui.player.bitchord.BitChordPlayerContent
import moe.rukamori.archivetune.ui.player.looper.LooperPlayerContent
import moe.rukamori.archivetune.ui.player.simpmusic.SimpMusicPlayerContent
import moe.rukamori.archivetune.ui.player.spatialflow.SpatialFlowPlayerContent
import moe.rukamori.archivetune.ui.player.tiktok.TikTokPlayerContent
import moe.rukamori.archivetune.ui.screens.LOGIN_ROUTE
import moe.rukamori.archivetune.ui.screens.buildLoginRoute
import moe.rukamori.archivetune.ui.screens.settings.DarkMode
import moe.rukamori.archivetune.ui.screens.settings.PO_TOKEN_ROUTE
import moe.rukamori.archivetune.ui.theme.PlayerColorExtractor
import moe.rukamori.archivetune.ui.theme.PlayerPaletteCache
import moe.rukamori.archivetune.ui.utils.ShowMediaInfo
import moe.rukamori.archivetune.ui.utils.YtimgResizePolicy
import moe.rukamori.archivetune.ui.utils.getNextFallbackUrl
import moe.rukamori.archivetune.ui.utils.resize
import moe.rukamori.archivetune.utils.defaultStorefront
import moe.rukamori.archivetune.utils.ImageBlurUtils
import moe.rukamori.archivetune.utils.isLocalMediaId
import moe.rukamori.archivetune.utils.makeTimeString
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberLowDataModeActive
import moe.rukamori.archivetune.utils.rememberPreference

private const val SeekbarSettleToleranceMs = 1_500L
private const val V7BackdropMinArtworkSizePx = 1_024
private const val V7BackdropMaxArtworkSizePx = 2_048
private const val V7BackdropBlurDp = 44
private const val V7BackdropBlurScale = 1.18f
private const val V7BackdropArtworkOverscanFactor = 1.15f
private const val V7SharpStagePortraitFraction = 0.62f
private const val V7SharpStageLandscapeFraction = 0.58f
private const val V7BackdropOverlapDp = 72
private const val V7SharpStageBottomScrimStartFraction = 0.40f
private const val V7BackdropFloorBlackStartFraction = 0.88f
private const val V8BackdropArtworkSizePx = 1_024

@Stable
internal class DeviceMusicVolumeController(
    private val audioManager: AudioManager,
) {
    private var minVolume by mutableIntStateOf(readMinVolume())
    private var maxVolume by mutableIntStateOf(readMaxVolume())
    var volumeFraction by mutableFloatStateOf(readVolumeFraction())
        private set

    fun refresh() {
        minVolume = readMinVolume()
        maxVolume = readMaxVolume()
        volumeFraction = readVolumeFraction()
    }

    @JvmName("setDeviceMusicVolumeFraction")
    fun setVolumeFraction(fraction: Float) {
        val safeFraction = fraction.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: volumeFraction
        val volumeRange = (maxVolume - minVolume).coerceAtLeast(1)
        val targetVolume =
            (minVolume + (safeFraction * volumeRange).roundToInt())
                .coerceIn(minVolume, maxVolume)
        val adjustedTarget =
            if (safeFraction > 0f && targetVolume <= minVolume) {
                (minVolume + 1).coerceAtMost(maxVolume)
            } else {
                targetVolume
            }

        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, adjustedTarget, 0)
        refresh()
    }

    private fun readVolumeFraction(): Float {
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val volumeRange = (maxVolume - minVolume).coerceAtLeast(1)
        return ((currentVolume - minVolume).toFloat() / volumeRange.toFloat()).coerceIn(0f, 1f)
    }

    private fun readMaxVolume(): Int {
        val streamMinVolume = readMinVolume()
        return audioManager
            .getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            .coerceAtLeast(streamMinVolume + 1)
    }

    private fun readMinVolume(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        } else {
            0
        }
}

@Composable
internal fun rememberDeviceMusicVolumeController(): DeviceMusicVolumeController {
    val context = LocalContext.current
    val audioManager =
        remember(context) {
            context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        }
    val controller =
        remember(audioManager) {
            DeviceMusicVolumeController(audioManager)
        }

    DisposableEffect(context, controller) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    controller.refresh()
                }
            }
        val contentResolver = context.applicationContext.contentResolver
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        controller.refresh()
        onDispose {
            contentResolver.unregisterContentObserver(observer)
        }
    }

    return controller
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomSheetPlayer(
    state: BottomSheetState,
    navController: NavController,
    modifier: Modifier = Modifier,
    pureBlack: Boolean,
    navigationProximityProvider: () -> Float = { 0f },
    onLyricsVisibilityChange: (Boolean) -> Unit = {},
    immersivePlayerViewModel: ImmersivePlayerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val menuState = LocalMenuState.current

    val bottomSheetPageState = LocalBottomSheetPageState.current

    val playerConnection = LocalPlayerConnection.current ?: return
    val playbackError by playerConnection.error.collectAsStateWithLifecycle()
    val (poTokenGvs) = rememberPreference(PoTokenGvsKey, defaultValue = "")
    val (poTokenPlayer) = rememberPreference(PoTokenPlayerKey, defaultValue = "")
    val isPoTokenLoggedIn =
        remember(poTokenGvs, poTokenPlayer) {
            poTokenGvs.isNotBlank() && poTokenPlayer.isNotBlank()
        }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val retryPlayback =
        remember(playerConnection) {
            {
                playerConnection.player.prepare()
                playerConnection.player.play()
            }
        }
    val dismissPlaybackError =
        remember(playerConnection) {
            playerConnection::dismissPlaybackError
        }
    val navigateToLogin: (String?) -> Unit =
        remember(navController) {
            { recoveryUrl ->
                navController.navigate(buildLoginRoute(recoveryUrl)) {
                    launchSingleTop = true
                }
            }
        }
    val navigateToPoTokenLogin =
        remember(navController) {
            {
                navController.navigate(PO_TOKEN_ROUTE) {
                    launchSingleTop = true
                }
            }
        }

    val playerDesignStyle by rememberEnumPreference(
        key = PlayerDesignStyleKey,
        defaultValue = PlayerDesignStyle.Default,
    )
    val showPlayerVolumeBar by rememberPreference(
        key = ShowPlayerVolumeBarKey,
        defaultValue = true,
    )

    val storedPlayerBackground by rememberEnumPreference(
        key = PlayerBackgroundStyleKey,
        defaultValue = PlayerBackgroundStyle.DEFAULT,
    )
    // The style itself says whether the reader's pick applies; see PlayerDesignStyle.
    val playerBackground = playerDesignStyle.resolveBackground(storedPlayerBackground)

    // Custom background preferences (image + effects)
    val (playerCustomImageUri) = rememberPreference(PlayerCustomImageUriKey, "")
    val (playerCustomBlur) = rememberPreference(PlayerCustomBlurKey, 0f)
    val (playerCustomContrast) = rememberPreference(PlayerCustomContrastKey, 1f)
    val (playerCustomBrightness) = rememberPreference(PlayerCustomBrightnessKey, 1f)

    val (disableBlur) = rememberPreference(DisableBlurKey, false)
    val (blurRadius) = rememberPreference(BlurRadiusKey, 48f)
    val (backdropEnabled) = rememberPreference(BackdropEnabledKey, defaultValue = true)
    val (backdropBlurAmount) = rememberPreference(BackdropBlurAmountKey, defaultValue = 60)
    val (showCodecOnPlayer) = rememberPreference(ShowCodecOnPlayerKey, true)
    val (incrementalSeekSkipEnabled) = rememberPreference(moe.rukamori.archivetune.constants.SeekExtraSeconds, defaultValue = false)
    val enableVideoPlayback by rememberPreference(EnableVideoPlaybackKey, defaultValue = false)
    var keyboardSkipMultiplier by remember { mutableStateOf(1) }
    var lastKeyboardTapTime by remember { mutableLongStateOf(0L) }

    val playerButtonsStyle by rememberEnumPreference(
        key = PlayerButtonsStyleKey,
        defaultValue = PlayerButtonsStyle.DEFAULT,
    )

    val isSystemInDarkTheme = isSystemInDarkTheme()
    val darkTheme by rememberEnumPreference(DarkModeKey, defaultValue = DarkMode.AUTO)
    val useDarkTheme =
        remember(darkTheme, isSystemInDarkTheme) {
            if (darkTheme == DarkMode.AUTO) isSystemInDarkTheme else darkTheme == DarkMode.ON
        }
    val onBackgroundColor =
        when (playerBackground) {
            PlayerBackgroundStyle.DEFAULT -> {
                MaterialTheme.colorScheme.secondary
            }

            else -> {
                if (useDarkTheme) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onPrimary
                }
            }
        }
    val useBlackBackground =
        remember(isSystemInDarkTheme, darkTheme, pureBlack) {
            val useDarkTheme =
                if (darkTheme == DarkMode.AUTO) isSystemInDarkTheme else darkTheme == DarkMode.ON
            useDarkTheme && pureBlack
        }
    val backgroundColor =
        if (useBlackBackground && state.value > state.collapsedBound) {
            val progress =
                ((state.value - state.collapsedBound) / (state.expandedBound - state.collapsedBound))
                    .coerceIn(0f, 1f)
            Color.Black.copy(alpha = progress)
        } else {
            val progress =
                ((state.value - state.collapsedBound) / (state.expandedBound - state.collapsedBound))
                    .coerceIn(0f, 1f)
            MaterialTheme.colorScheme.surfaceContainer.copy(alpha = progress)
        }

    val playbackState by playerConnection.playbackState.collectAsStateWithLifecycle()
    val isPlaying by playerConnection.isPlaying.collectAsStateWithLifecycle()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val currentSong by playerConnection.currentSong.collectAsStateWithLifecycle(initialValue = null)
    val currentSongLiked = currentSong?.song?.liked == true
    val queueTitle by playerConnection.queueTitle.collectAsStateWithLifecycle()
    val currentFormat by playerConnection.currentFormat.collectAsStateWithLifecycle(initialValue = null)
    // Snapshot the lyrics entity for the AOD screen — AOD shows only the current line, so we
    // pass the raw text down rather than the full Lyrics composable tree (cheaper to render,
    // and matches the "dim, low-power" goal of always-on display).
    val currentLyricsEntity by playerConnection.currentLyrics.collectAsStateWithLifecycle(initialValue = null)
    val queueWindows by playerConnection.queueWindows.collectAsStateWithLifecycle()
    val currentWindowIndex by playerConnection.currentWindowIndex.collectAsStateWithLifecycle()
    val deviceMusicVolumeController = rememberDeviceMusicVolumeController()
    val onPlayerVolumeChange =
        remember(deviceMusicVolumeController) {
            { volume: Float ->
                deviceMusicVolumeController.setVolumeFraction(volume)
            }
        }

    val repeatMode by playerConnection.repeatMode.collectAsStateWithLifecycle()

    val canSkipPrevious by playerConnection.canSkipPrevious.collectAsStateWithLifecycle()
    val canSkipNext by playerConnection.canSkipNext.collectAsStateWithLifecycle()

    val aodModeEnabled by playerConnection.aodModeEnabled.collectAsStateWithLifecycle()

    // The Immersive style (rukamori's V7 rewrite) runs off its own view model. It is only bound
    // while the style is selected, so its flows and position polling stay idle otherwise.
    val immersivePlayerState by immersivePlayerViewModel.state.collectAsStateWithLifecycle()
    val immersiveStyleActive = playerDesignStyle == PlayerDesignStyle.V7
    DisposableEffect(immersivePlayerViewModel, playerConnection, immersiveStyleActive) {
        if (immersiveStyleActive) immersivePlayerViewModel.bind(playerConnection)
        onDispose { immersivePlayerViewModel.unbind(playerConnection) }
    }
    LaunchedEffect(immersivePlayerViewModel, immersiveStyleActive, state.isCollapsed) {
        immersivePlayerViewModel.setProgressActive(immersiveStyleActive && !state.isCollapsed)
    }
    LaunchedEffect(immersivePlayerViewModel, navController, state, menuState, bottomSheetPageState) {
        immersivePlayerViewModel.events.collect { event ->
            when (event) {
                is ImmersivePlayerEvent.OpenAlbum -> {
                    state.collapseSoft()
                    navController.navigate("album/${event.id}")
                }

                is ImmersivePlayerEvent.OpenArtist -> {
                    state.collapseSoft()
                    navController.navigate("artist/${event.id}")
                }

                ImmersivePlayerEvent.OpenMenu -> {
                    val metadata = playerConnection.mediaMetadata.value ?: return@collect
                    menuState.show {
                        PlayerMenu(
                            mediaMetadata = metadata,
                            navController = navController,
                            playerBottomSheetState = state,
                            onShowDetailsDialog = {
                                bottomSheetPageState.show { ShowMediaInfo(metadata.id) }
                            },
                            onDismiss = menuState::dismiss,
                        )
                    }
                }
            }
        }
    }
    val (thumbnailCornerRadius) = rememberPreference(ThumbnailCornerRadiusKey, defaultValue = 8f)
    val archiveTuneCanvasEnabled by rememberPreference(ArchiveTuneCanvasKey, false)
    val spotifyCanvasEnabled by rememberPreference(SpotifyCanvasKey, false)
    val lowDataModeActive = rememberLowDataModeActive()
    val (maxCanvasCacheSize, _) =
        rememberPreference(
            key = MaxCanvasCacheSizeKey,
            defaultValue = 256,
        )

    val sliderStyle by rememberEnumPreference(SliderStyleKey, SliderStyle.Standard)

    LaunchedEffect(maxCanvasCacheSize) {
        CanvasArtworkPlaybackCache.setMaxSize(maxCanvasCacheSize)
    }

    var position by rememberSaveable(mediaMetadata?.id) {
        // Seed from the live position ONLY when the player already holds this very item and is
        // ready to play it: otherwise the outgoing song's position (20-30 s in) leaked into the
        // fresh song's seekbar until the first poll tick caught up.
        val player = playerConnection.player
        val seededPosition =
            if (player.playbackState == Player.STATE_READY &&
                player.currentMediaItem?.mediaId == mediaMetadata?.id
            ) {
                player.currentPosition.coerceAtLeast(0L)
            } else {
                0L
            }
        mutableLongStateOf(seededPosition)
    }
    // Wrap `position` in a stable provider so AppleMusicPlayerContent does NOT
    // recompose on every 100ms poll tick. The provider lambda is remembered
    // (stable identity) and reads the latest position via the updated state.
    // Only AppleMusicControlsColumn reads it — and only when it's actually
    // composed (visible). When lyrics is open and controls auto-hide, no
    // recomposition happens at all, freeing the frame budget for the karaoke
    // syllable animation in the inline Enhanced lyrics view.
    val positionUpdatedState = rememberUpdatedState(position)
    val positionProvider = remember { { positionUpdatedState.value } }
    var duration by rememberSaveable(mediaMetadata?.id) {
        mutableLongStateOf(playerConnection.player.duration)
    }
    var lyricsSyncOffset by rememberSaveable(mediaMetadata?.id) {
        mutableIntStateOf(0)
    }
    var sliderPosition by remember(mediaMetadata?.id) {
        mutableStateOf<Long?>(null)
    }
    var isUserSeeking by remember(mediaMetadata?.id) {
        mutableStateOf(false)
    }

    // Buffering, or waiting for a committed seek to land. Deliberately not while a finger is still
    // down: dragging is not loading, and treating it as such turned the transport into a spinner
    // for the whole gesture.
    val isLoading = playbackState == STATE_BUFFERING || (sliderPosition != null && !isUserSeeking)

    // Palette state. The previous valid palette stays visible while the next track's artwork
    // loads; it is only replaced by a successfully extracted palette (or kept on failure).
    // A theme-grey fallback is used only when no valid palette has ever existed.
    var gradientColors by remember {
        mutableStateOf<List<Color>>(emptyList())
    }
    var hasValidGradientPalette by remember { mutableStateOf(false) }

    // Default gradient colors for the initial fallback
    val defaultGradientColors = listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surfaceVariant)
    val fallbackColor = MaterialTheme.colorScheme.surface.toArgb()

    // The palette source is exactly the artwork the player displays: the metadata thumbnail
    // (kept in sync with the authoritative artwork resolver, including Tidal fallback commits).
    val paletteArtworkUrl = mediaMetadata?.thumbnailUrl

    LaunchedEffect(mediaMetadata?.id, paletteArtworkUrl, playerBackground, useDarkTheme, playerDesignStyle) {
        if (aodModeEnabled) return@LaunchedEffect
        val wantsPalette =
            playerBackground == PlayerBackgroundStyle.GRADIENT || playerBackground == PlayerBackgroundStyle.COLORING ||
                playerBackground == PlayerBackgroundStyle.BLUR ||
                playerBackground == PlayerBackgroundStyle.BLUR_GRADIENT ||
                playerBackground == PlayerBackgroundStyle.GLOW ||
                playerBackground == PlayerBackgroundStyle.GLOW_ANIMATED ||
                playerDesignStyle == PlayerDesignStyle.V9 || playerDesignStyle == PlayerDesignStyle.V10
        if (!wantsPalette) {
            gradientColors = emptyList()
            hasValidGradientPalette = false
            return@LaunchedEffect
        }
        val currentMetadata = mediaMetadata
        val artworkUrl = currentMetadata?.thumbnailUrl
        if (currentMetadata == null || artworkUrl.isNullOrBlank()) {
            if (!hasValidGradientPalette) gradientColors = emptyList()
            return@LaunchedEffect
        }

        val cacheKey =
            PlayerPaletteCacheKey(
                mediaId = currentMetadata.id,
                provider = guessArtworkProvider(artworkUrl),
                artworkIdentity = artworkUrl,
                backgroundMode = playerBackground.name,
                darkTheme = useDarkTheme,
            )
        PlayerPaletteCache.get(cacheKey)?.let { cachedColors ->
            gradientColors = cachedColors
            hasValidGradientPalette = true
            return@LaunchedEffect
        }

        val request =
            ImageRequest
                .Builder(context)
                .data(artworkUrl)
                .memoryCacheKey(artworkUrl)
                .diskCacheKey(artworkUrl)
                .diskCachePolicy(CachePolicy.ENABLED)
                .networkCachePolicy(CachePolicy.ENABLED)
                .size(PlayerColorExtractor.Config.IMAGE_SIZE, PlayerColorExtractor.Config.IMAGE_SIZE)
                .allowHardware(false)
                .build()

        val result =
            try {
                withContext(Dispatchers.IO) { context.imageLoader.execute(request) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                null
            }

        // Only successful image results may feed the palette. On failure keep the previous
        // valid palette; use the theme fallback only if no palette has ever succeeded.
        if (result !is SuccessResult) {
            if (!hasValidGradientPalette) gradientColors = defaultGradientColors
            return@LaunchedEffect
        }
        val bitmap = result.image?.toBitmap()
        if (bitmap == null) {
            if (!hasValidGradientPalette) gradientColors = defaultGradientColors
            return@LaunchedEffect
        }

        val palette =
            withContext(Dispatchers.Default) {
                Palette
                    .from(bitmap)
                    .maximumColorCount(PlayerColorExtractor.Config.MAX_COLOR_COUNT)
                    .resizeBitmapArea(PlayerColorExtractor.Config.BITMAP_AREA)
                    .generate()
            }

        val extractedColors =
            PlayerColorExtractor.extractGradientColors(
                palette = palette,
                fallbackColor = fallbackColor,
            )

        // Stale-result guard: the artwork identity must still be what the player displays.
        val stillCurrent =
            mediaMetadata?.id == currentMetadata.id &&
                mediaMetadata?.thumbnailUrl == artworkUrl
        if (stillCurrent) {
            PlayerPaletteCache.put(cacheKey, extractedColors)
            gradientColors = extractedColors
            hasValidGradientPalette = true
        }
    }

    val changeBound = state.expandedBound / 3

    // ── V9 "Material Extended" dynamic color system ──
    // Derives background/accent/text/icon-button colors from the artwork's dominant
    // palette color and animates between songs so the controls blend smoothly with
    // the artwork (no blur behind the controls — pure color gradient instead).
    val dominantColor = gradientColors.firstOrNull() ?: MaterialTheme.colorScheme.primary

    // ── V10 "Editorial" dominant-artwork control theme ── Both halves of the two-tone contract
    // (field + accent) are derived from the artwork's DOMINANT palette color —
    // gradientColors.first(), the highest- weighted swatch of the cover — HCT-toned per theme so
    // they always keep proper contrast (dark: light accent over dark field; light: dark accent over
    // light field).
    val targetV10FieldColor =
        remember(dominantColor, useDarkTheme) {
            val hct = dominantColor.toHct()
            if (useDarkTheme) hct.withTone(30.0).toColor() else hct.withTone(90.0).toColor()
        }
    val dynamicV10FieldColor by animateColorAsState(
        targetValue = targetV10FieldColor,
        animationSpec = tween(durationMillis = 800),
        label = "dynamicV10FieldColor",
    )
    val targetV10AccentColor =
        remember(dominantColor, useDarkTheme) {
            val hct = dominantColor.toHct()
            if (useDarkTheme) hct.withTone(90.0).toColor() else hct.withTone(10.0).toColor()
        }
    val dynamicV10AccentColor by animateColorAsState(
        targetValue = targetV10AccentColor,
        animationSpec = tween(durationMillis = 800),
        label = "dynamicV10AccentColor",
    )
    val targetBgColor = remember(dominantColor, useDarkTheme) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(dominantColor.toArgb(), hsv)
        if (useDarkTheme) {
            hsv[1] = hsv[1].coerceIn(0.12f, 0.35f)
            hsv[2] = 0.08f
        } else {
            hsv[1] = hsv[1].coerceIn(0.04f, 0.12f)
            hsv[2] = 0.96f
        }
        Color(android.graphics.Color.HSVToColor(hsv))
    }
    val dynamicBgColor by animateColorAsState(
        targetValue = targetBgColor,
        animationSpec = tween(durationMillis = 800),
        label = "dynamicBgColor",
    )

    val targetAccentColor = dominantColor
    val dynamicAccentColor by animateColorAsState(
        targetValue = targetAccentColor,
        animationSpec = tween(durationMillis = 800),
        label = "dynamicAccentColor",
    )

    val targetTextColor = remember(dominantColor, useDarkTheme) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(dominantColor.toArgb(), hsv)
        if (useDarkTheme) {
            hsv[1] = hsv[1].coerceAtMost(0.12f)
            hsv[2] = 0.96f
        } else {
            hsv[1] = hsv[1].coerceIn(0.12f, 0.35f)
            hsv[2] = 0.08f
        }
        Color(android.graphics.Color.HSVToColor(hsv))
    }
    val dynamicTextColor by animateColorAsState(
        targetValue = targetTextColor,
        animationSpec = tween(durationMillis = 800),
        label = "dynamicTextColor",
    )

    val targetIconButtonColor = remember(dynamicAccentColor) {
        val luminance =
            0.299f * dynamicAccentColor.red +
                0.587f * dynamicAccentColor.green +
                0.114f * dynamicAccentColor.blue
        if (luminance > 0.5f) Color.Black else Color.White
    }
    val dynamicIconButtonColor by animateColorAsState(
        targetValue = targetIconButtonColor,
        animationSpec = tween(durationMillis = 800),
        label = "dynamicIconButtonColor",
    )

    val TextBackgroundColor =
        if (playerDesignStyle == PlayerDesignStyle.V9) {
            dynamicTextColor
        } else if (playerDesignStyle.forcesMonochromeChrome) {
            Color.White
        } else {
            when (playerBackground) {
                PlayerBackgroundStyle.DEFAULT -> MaterialTheme.colorScheme.onBackground
                PlayerBackgroundStyle.BLUR -> Color.White
                PlayerBackgroundStyle.GRADIENT -> Color.White
                PlayerBackgroundStyle.COLORING -> Color.White
                PlayerBackgroundStyle.BLUR_GRADIENT -> Color.White
                PlayerBackgroundStyle.GLOW -> Color.White
                PlayerBackgroundStyle.GLOW_ANIMATED -> Color.White
                PlayerBackgroundStyle.CUSTOM -> Color.White
            }
        }

    val icBackgroundColor =
        if (playerDesignStyle == PlayerDesignStyle.V9) {
            dynamicBgColor
        } else if (playerDesignStyle.forcesMonochromeChrome) {
            Color.Black
        } else {
            when (playerBackground) {
                PlayerBackgroundStyle.DEFAULT -> MaterialTheme.colorScheme.surface
                PlayerBackgroundStyle.BLUR -> Color.Black
                PlayerBackgroundStyle.GRADIENT -> Color.Black
                PlayerBackgroundStyle.COLORING -> Color.Black
                PlayerBackgroundStyle.BLUR_GRADIENT -> Color.Black
                PlayerBackgroundStyle.GLOW -> Color.Black
                PlayerBackgroundStyle.GLOW_ANIMATED -> Color.Black
                PlayerBackgroundStyle.CUSTOM -> Color.Black
            }
        }

    val (textButtonColor, iconButtonColor) =
        when (playerButtonsStyle) {
            PlayerButtonsStyle.DEFAULT -> {
                Pair(TextBackgroundColor, icBackgroundColor)
            }

            PlayerButtonsStyle.SECONDARY -> {
                Pair(
                    MaterialTheme.colorScheme.secondary,
                    MaterialTheme.colorScheme.onSecondary,
                )
            }
        }.let { (tb, ib) ->
            if (playerDesignStyle.forcesMonochromeChrome) {
                Pair(Color.White, Color.Black)
            } else if (playerDesignStyle == PlayerDesignStyle.V9) {
                Pair(dynamicAccentColor, dynamicIconButtonColor)
            } else {
                Pair(tb, ib)
            }
        }

    val download by LocalDownloadUtil.current
        .getDownload(mediaMetadata?.id ?: "")
        .collectAsStateWithLifecycle(initialValue = null)

    val sleepTimerEnabled =
        remember(
            playerConnection.service.sleepTimer.triggerTime,
            playerConnection.service.sleepTimer.pauseWhenSongEnd,
        ) {
            playerConnection.service.sleepTimer.isActive
        }

    var sleepTimerTimeLeft by remember {
        mutableLongStateOf(0L)
    }

    LaunchedEffect(sleepTimerEnabled) {
        if (sleepTimerEnabled) {
            while (isActive) {
                sleepTimerTimeLeft =
                    if (playerConnection.service.sleepTimer.pauseWhenSongEnd) {
                        playerConnection.player.duration - playerConnection.player.currentPosition
                    } else {
                        playerConnection.service.sleepTimer.triggerTime - System.currentTimeMillis()
                    }
                delay(1000L)
            }
        }
    }

    var showSleepTimerDialog by remember {
        mutableStateOf(false)
    }

    var sleepTimerValue by remember {
        mutableFloatStateOf(30f)
    }
    if (showSleepTimerDialog) {
        AlertDialog(
            properties = DialogProperties(usePlatformDefaultWidth = false),
            onDismissRequest = { showSleepTimerDialog = false },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.player_bedtime),
                    contentDescription = null,
                )
            },
            title = { Text(stringResource(R.string.sleep_timer)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSleepTimerDialog = false
                        playerConnection.service.sleepTimer.start(sleepTimerValue.roundToInt())
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showSleepTimerDialog = false },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text =
                            pluralStringResource(
                                R.plurals.minute,
                                sleepTimerValue.roundToInt(),
                                sleepTimerValue.roundToInt(),
                            ),
                        style = MaterialTheme.typography.bodyLarge,
                    )

                    Slider(
                        value = sleepTimerValue,
                        onValueChange = { sleepTimerValue = it },
                        valueRange = 5f..120f,
                        steps = (120 - 5) / 5 - 1,
                    )

                    OutlinedIconButton(
                        onClick = {
                            showSleepTimerDialog = false
                            playerConnection.service.sleepTimer.start(-1)
                        },
                    ) {
                        Text(stringResource(R.string.end_of_song))
                    }
                }
            },
        )
    }

    var showChoosePlaylistDialog by rememberSaveable {
        mutableStateOf(false)
    }

    LaunchedEffect(mediaMetadata?.id, playbackState, aodModeEnabled) {
        val startTime = SystemClock.elapsedRealtime()
        if (playbackState == STATE_READY) {
            while (isActive) {
                delay(if (aodModeEnabled) 500L else 100L)
                val isTransitioning = playerConnection.player.currentMediaItem?.mediaId != mediaMetadata?.id
                val currentPlayerPosition = playerConnection.player.currentPosition
                val currentPlayerDuration = playerConnection.player.duration

                if (isTransitioning) {
                    val elapsedSinceStart = SystemClock.elapsedRealtime() - startTime
                    position = elapsedSinceStart
                    mediaMetadata?.let {
                        val metaDuration = it.duration.toLong() * 1000
                        duration = if (metaDuration > 0) metaDuration else 0L
                    }
                } else {
                    // Only while no scrub is outstanding. seekTo is asynchronous, so for the ticks
                    // between lifting a finger and the player arriving, currentPosition still
                    // reports where the song WAS — writing it here snapped the elapsed time (and
                    // anything else reading position rather than the slider override) back to the
                    // old spot before it jumped forward again. The other branch below already
                    // guards this way.
                    if (sliderPosition == null) {
                        position = currentPlayerPosition
                    }
                    if (currentPlayerDuration > 0L && currentPlayerDuration != C.TIME_UNSET) {
                        duration = currentPlayerDuration
                    } else if (duration <= 0L || duration == C.TIME_UNSET) {
                        mediaMetadata?.let {
                            val metadataDuration = it.duration.toLong() * 1000
                            if (metadataDuration > 0L) duration = metadataDuration
                        }
                    }
                    if (!isUserSeeking) {
                        sliderPosition?.let { targetPosition ->
                            val clampedTargetPosition =
                                when {
                                    currentPlayerDuration > 0L && currentPlayerDuration != C.TIME_UNSET -> {
                                        targetPosition.coerceIn(0L, currentPlayerDuration)
                                    }

                                    else -> {
                                        targetPosition.coerceAtLeast(0L)
                                    }
                                }
                            if (abs(currentPlayerPosition - clampedTargetPosition) <= SeekbarSettleToleranceMs) {
                                sliderPosition = null
                            }
                        }
                    }
                }
            }
        } else {
            mediaMetadata?.let {
                val metaDuration = it.duration.toLong() * 1000
                duration = if (metaDuration > 0) metaDuration else 0L
            }
            val player = playerConnection.player
            val playerMatchesMetadata = player.currentMediaItem?.mediaId == mediaMetadata?.id
            val currentPlayerPosition = player.currentPosition
            if (sliderPosition == null && playerMatchesMetadata && currentPlayerPosition > 0L) {
                position = currentPlayerPosition
            }
        }
    }

    val dynamicQueuePeekHeight =
        // V5 is named on its own because it hides the peek without painting a backdrop of its own —
        // it is the one style here that is not simply "has a custom backdrop".
        if (playerDesignStyle == PlayerDesignStyle.V5 || playerDesignStyle.hasCustomBackdrop) {
            0.dp
        } else if (playerDesignStyle == PlayerDesignStyle.V9) {
            88.dp +
                (if (showCodecOnPlayer) 24.dp else 0.dp) +
                (if (sleepTimerEnabled) 42.dp else 0.dp)
        } else if (showCodecOnPlayer) {
            88.dp
        } else {
            QueuePeekHeight
        }

    val systemBarsBottom = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()

    val dismissedBound = 0.dp
    val collapsedBound = dynamicQueuePeekHeight + systemBarsBottom

    val queueSheetState =
        rememberBottomSheetState(
            dismissedBound = dismissedBound,
            expandedBound = state.expandedBound,
            collapsedBound = collapsedBound,
            initialAnchor = COLLAPSED_ANCHOR,
        )

    // Haze state for the frosted-glass blur behind the queue sheet.

    LaunchedEffect(state.isExpandedOrExpanding) {
        if (state.isExpandedOrExpanding && !queueSheetState.isCollapsed) {
            queueSheetState.collapseSoft()
        }
    }

    // Collapse the queue sheet whenever the app goes to the background (ON_STOP), so that
    // re-entering the app doesn't re-open a queue the user had previously expanded. Without
    // this, `queueSheetState.previousAnchor` stays at EXPANDED across the ON_PAUSE/ON_RESUME
    // roundtrip, so the user has to manually close the queue sheet every time they return to
    // the app. Collapsing on ON_STOP writes COLLAPSED_ANCHOR into previousAnchor via the
    // sheet's onAnchorChanged callback, so the queue stays collapsed on return.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, queueSheetState) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !queueSheetState.isCollapsed) {
                queueSheetState.collapseSoft()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var isLyricsScreenVisible by rememberSaveable {
        mutableStateOf(false)
    }

    // The lyrics overlay belongs to the EXPANDED player: it is opened from it, drawn over it, and
    // every way out of it — the BackHandler below, LyricsScreen's own, and the top-edge dismiss
    // drag — is gated on `state.isExpandedOrExpanding`. Nothing tied the overlay's own lifetime to
    // that, so collapsing the player while lyrics were open (a drag on the sheet, ON_STOP, or a
    // restore from process death, since the flag is rememberSaveable) left the overlay covering
    // the whole app with all three of those disabled at once: back did nothing at all and there
    // was no way back to the player. It closes with the player it was opened from now.
    LaunchedEffect(state.isExpandedOrExpanding) {
        if (!state.isExpandedOrExpanding) isLyricsScreenVisible = false
    }

    // Track Apple Music's INLINE lyrics open state separately from the standalone lyrics overlay
    // (isLyricsScreenVisible). Both feed into lyricsFullScreenActive so back-stack screens suspend
    // GPU work during ANY lyrics morph — but only isLyricsScreenVisible triggers the standalone
    // MikoLyricsTransition overlay. Without the inline state propagating, back-stack
    // LiquidGlass/Canvas GPU work competes with the sharedBounds morph and stutters.
    var isAppleMusicInlineLyricsOpen by rememberSaveable { mutableStateOf(false) }

    // Report full-screen lyrics visibility upward so the status bar can be hidden for every player
    // style while the lyrics overlay is showing, not only for the Immersive style.
    val lyricsFullScreenActive =
        (isLyricsScreenVisible || isAppleMusicInlineLyricsOpen) && state.isExpandedOrExpanding
    LaunchedEffect(lyricsFullScreenActive) {
        onLyricsVisibilityChange(lyricsFullScreenActive)
    }
    DisposableEffect(Unit) {
        onDispose { onLyricsVisibilityChange(false) }
    }

    val openQueue =
        remember(state, queueSheetState) {
            {
                isLyricsScreenVisible = false
                if (!state.isExpandedOrExpanding) {
                    state.expandSoft()
                }
                queueSheetState.expandSoft()
            }
        }

    if (!aodModeEnabled) {
        BackHandler(
            enabled =
                queueSheetState.isExpandedOrExpanding ||
                    state.isExpandedOrExpanding,
        ) {
            when {
                isLyricsScreenVisible && state.isExpandedOrExpanding -> isLyricsScreenVisible = false
                queueSheetState.isExpandedOrExpanding -> queueSheetState.collapseSoft()
                state.isExpandedOrExpanding -> state.collapseSoft()
            }
        }
    }

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(state.isExpanded) {
        if (state.isExpanded) {
            focusRequester.requestFocus()
        }
        if (!state.isExpanded && aodModeEnabled) {
            playerConnection.aodModeEnabled.value = false
        }
    }

    val videoFullscreenHolder = LocalVideoFullscreenState.current
    val videoMediaId =
        mediaMetadata
            ?.takeIf { enableVideoPlayback && it.isMusicVideo == true && !it.id.isLocalMediaId() }
            ?.id
    var videoPreferredHeight by rememberSaveable { mutableStateOf<Int?>(null) }
    var videoAvailableHeights by remember { mutableStateOf<List<Int>>(emptyList()) }
    // The resolution actually resolved for the current stream, so the quality UI can report what
    // Auto / Data saver / High quality picked instead of only naming the mode.
    var videoSelectedHeight by remember { mutableStateOf<Int?>(null) }
    var videoPlaybackFailed by remember { mutableStateOf(false) }

    // Reset the failure flag when the media changes — a new song gets a fresh
    // attempt at video playback even if the previous song's video failed.
    LaunchedEffect(videoMediaId) {
        videoPlaybackFailed = false
    }

    val videoState =
        rememberVideoArtworkStateOrNull(
            videoId = videoMediaId,
            isPlaying = isPlaying,
            positionProvider = { playerConnection.player.currentPosition },
            preferredHeight = videoPreferredHeight,
            holdAudioUntilVideoReady = true,
            onStreamResolved = { info ->
                videoAvailableHeights = info?.availableHeights.orEmpty()
                videoSelectedHeight = info?.selectedHeight
            },
            onPlaybackFailed = { videoPlaybackFailed = true },
            onLoadingStateChange = { /* loading is computed from state directly in InlineVideoPlayer */ },
            onRequestPauseMain = {
                if (videoMediaId != null && playerConnection.player.currentMediaItem?.mediaId == videoMediaId) {
                    playerConnection.player.pause()
                }
            },
            onRequestResumeMain = {
                if (videoMediaId != null && playerConnection.player.currentMediaItem?.mediaId == videoMediaId) {
                    playerConnection.player.play()
                }
            },
        )

    CompositionLocalProvider(
        LocalVideoArtworkState provides videoState,
        LocalVideoPreferredHeight provides videoPreferredHeight,
        LocalVideoOnPreferredHeightChange provides { videoPreferredHeight = it },
        LocalVideoAvailableHeights provides videoAvailableHeights,
        LocalVideoSelectedHeight provides videoSelectedHeight,
    ) {
    Box(modifier = Modifier.fillMaxSize()) {
    BottomSheet(
        state = state,
        modifier =
            modifier
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { keyEvent ->
                    if (keyEvent.type != KeyEventType.KeyDown || state.isCollapsed) return@onKeyEvent false

                    when (keyEvent.key) {
                        Key.DirectionLeft -> {
                            val now = SystemClock.uptimeMillis()
                            if (incrementalSeekSkipEnabled && now - lastKeyboardTapTime < 1000) {
                                keyboardSkipMultiplier++
                            } else {
                                keyboardSkipMultiplier = 1
                            }
                            lastKeyboardTapTime = now
                            val skipAmount = 5000L * keyboardSkipMultiplier
                            playerConnection.player.seekTo((playerConnection.player.currentPosition - skipAmount).coerceAtLeast(0))
                            true
                        }

                        Key.DirectionRight -> {
                            val now = SystemClock.uptimeMillis()
                            if (incrementalSeekSkipEnabled && now - lastKeyboardTapTime < 1000) {
                                keyboardSkipMultiplier++
                            } else {
                                keyboardSkipMultiplier = 1
                            }
                            lastKeyboardTapTime = now
                            val skipAmount = 5000L * keyboardSkipMultiplier
                            playerConnection.player.seekTo(
                                (playerConnection.player.currentPosition + skipAmount).coerceAtMost(playerConnection.player.duration),
                            )
                            true
                        }

                        Key.DirectionUp -> {
                            deviceMusicVolumeController.setVolumeFraction(
                                (deviceMusicVolumeController.volumeFraction + 0.05f).coerceAtMost(1f),
                            )
                            true
                        }

                        Key.DirectionDown -> {
                            deviceMusicVolumeController.setVolumeFraction(
                                (deviceMusicVolumeController.volumeFraction - 0.05f).coerceAtLeast(0f),
                            )
                            true
                        }

                        Key.Spacebar -> {
                            playerConnection.player.togglePlayPause()
                            true
                        }

                        Key.N -> {
                            if (keyEvent.isShiftPressed) {
                                playerConnection.seekToNext()
                                true
                            } else {
                                false
                            }
                        }

                        Key.P -> {
                            if (keyEvent.isShiftPressed) {
                                playerConnection.seekToPrevious()
                                true
                            } else {
                                false
                            }
                        }

                        Key.L -> {
                            playerConnection.toggleLike()
                            true
                        }

                        else -> {
                            false
                        }
                    }
                },
        backgroundColor =
            if (playerDesignStyle == PlayerDesignStyle.V9) {
                // V9 "Material Extended": animate the sheet background to the artwork's
                // dominant color so the controls blend smoothly with the artwork (no blur).
                val progress =
                    ((state.value - state.collapsedBound) / (state.expandedBound - state.collapsedBound))
                        .coerceIn(0f, 1f)
                val fadeProgress =
                    if (progress < 0.2f) {
                        ((0.2f - progress) / 0.2f).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                dynamicBgColor.copy(alpha = 1f - fadeProgress)
            } else if (playerDesignStyle.forcesMonochromeChrome) {
                val progress =
                    ((state.value - state.collapsedBound) / (state.expandedBound - state.collapsedBound))
                        .coerceIn(0f, 1f)
                val fadeProgress =
                    if (progress < 0.2f) {
                        ((0.2f - progress) / 0.2f).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                Color.Black.copy(alpha = 1f - fadeProgress)
            } else {
                when (playerBackground) {
                    PlayerBackgroundStyle.BLUR, PlayerBackgroundStyle.GRADIENT -> {
                        // Apply same enhanced fade logic to blur/gradient backgrounds
                        val progress =
                            ((state.value - state.collapsedBound) / (state.expandedBound - state.collapsedBound))
                                .coerceIn(0f, 1f)

                        // Only start fading when very close to dismissal (last 20%)
                        val fadeProgress =
                            if (progress < 0.2f) {
                                ((0.2f - progress) / 0.2f).coerceIn(0f, 1f)
                            } else {
                                0f
                            }

                        MaterialTheme.colorScheme.surface.copy(alpha = 1f - fadeProgress)
                    }

                    else -> {
                        // Enhanced background - stable until last 20% of drag (both normal and pure black)
                        // Calculate progress for fade effect
                        val progress =
                            ((state.value - state.collapsedBound) / (state.expandedBound - state.collapsedBound))
                                .coerceIn(0f, 1f)

                        // Only start fading when very close to dismissal (last 20%)
                        val fadeProgress =
                            if (progress < 0.2f) {
                                ((0.2f - progress) / 0.2f).coerceIn(0f, 1f)
                            } else {
                                0f
                            }

                        if (useBlackBackground) {
                            // Apply same logic to pure black background
                            Color.Black.copy(alpha = 1f - fadeProgress)
                        } else {
                            // Apply same logic to normal theme
                            MaterialTheme.colorScheme.surface.copy(alpha = 1f - fadeProgress)
                        }
                    }
                }
            },
        onDismiss = {
            playerConnection.service.stopAndClearPlayback(clearPersistentState = true)
        },
        backHandlerEnabled = !aodModeEnabled && !isLyricsScreenVisible,
        keepContentAlive = true,
        collapsedContent = {
            MiniPlayer(
                position = position,
                duration = duration,
                pureBlack = pureBlack,
                navigationProximityProvider = navigationProximityProvider,
            )
        },
    ) {
        val onSliderValueChange: (Long) -> Unit = {
            isUserSeeking = true
            sliderPosition = it
        }
        val onSliderValueChangeFinished: () -> Unit = {
            sliderPosition?.let {
                val isTransitioning = playerConnection.player.currentMediaItem?.mediaId != mediaMetadata?.id
                if (isTransitioning) {
                    // During crossfade, we want to seek in the NEXT song (the one UI is showing)
                    // The easiest way is to skip to it and then seek
                    playerConnection.player.seekToNext()
                    playerConnection.player.seekTo(it)
                } else {
                    playerConnection.player.seekTo(it)
                }
                position = it
                videoState?.requestResync(it, isPlaying)
            }
            isUserSeeking = false
        }
        val seekEnabled = duration > 0L && duration != C.TIME_UNSET
        val updatedOnSliderValueChange by rememberUpdatedState(onSliderValueChange)
        val updatedOnSliderValueChangeFinished by rememberUpdatedState(onSliderValueChangeFinished)

        val nextUpMetadata =
            remember(queueWindows, currentWindowIndex) {
                queueWindows.getOrNull(currentWindowIndex + 1)?.mediaItem?.metadata
            }

        val enrichedMetadata =
            remember(mediaMetadata, currentSong) {
                val meta = mediaMetadata ?: return@remember null
                if (meta.album != null) return@remember meta
                val dbAlbum = currentSong?.album
                val dbAlbumId = currentSong?.song?.albumId
                when {
                    dbAlbum != null -> {
                        meta.copy(
                            album = MediaMetadata.Album(id = dbAlbum.id, title = dbAlbum.title),
                        )
                    }

                    dbAlbumId != null -> {
                        meta.copy(
                            album =
                                MediaMetadata.Album(
                                    id = dbAlbumId,
                                    title = currentSong?.song?.albumName.orEmpty(),
                                ),
                        )
                    }

                    else -> {
                        meta
                    }
                }
            }

        val storefront = remember { defaultStorefront() }
        val shouldUseV7Canvas =
            (archiveTuneCanvasEnabled || spotifyCanvasEnabled) &&
                playerDesignStyle == PlayerDesignStyle.V7 &&
                !aodModeEnabled
        val shouldUseArtworkCanvas =
            (archiveTuneCanvasEnabled || spotifyCanvasEnabled) &&
                playerDesignStyle == PlayerDesignStyle.APPLE_MUSIC &&
                !aodModeEnabled
        val shouldFetchV7Canvas = shouldUseV7Canvas && !lowDataModeActive
        val shouldFetchArtworkCanvas = shouldUseArtworkCanvas && !lowDataModeActive
        var v7CanvasArtwork by remember(mediaMetadata?.id) {
            mutableStateOf<CanvasArtwork?>(null)
        }
        var v7CanvasFetchInFlight by remember(mediaMetadata?.id) {
            mutableStateOf(false)
        }
        var artworkCanvas by remember(mediaMetadata?.id) {
            mutableStateOf<CanvasArtwork?>(null)
        }
        var artworkCanvasFetchInFlight by remember(mediaMetadata?.id) {
            mutableStateOf(false)
        }
        var canvasArtworkRevision by remember(mediaMetadata?.id) {
            mutableIntStateOf(0)
        }

        LaunchedEffect(nextUpMetadata?.id, shouldUseV7Canvas, shouldUseArtworkCanvas, lowDataModeActive) {
            val next = nextUpMetadata ?: return@LaunchedEffect
            val nextMediaId = next.id.trim().takeIf { it.isNotBlank() } ?: return@LaunchedEffect
            if (!shouldUseV7Canvas && !shouldUseArtworkCanvas) return@LaunchedEffect
            if (lowDataModeActive) return@LaunchedEffect
            // Skip if already cached — CanvasArtworkPlaybackCache.hasEntry is
            // a cheap in-memory map lookup, no I/O.
            if (CanvasArtworkPlaybackCache.hasEntry(nextMediaId)) return@LaunchedEffect
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                runCatching {
                    resolveCanvasArtworkForPlayback(
                        mediaId = nextMediaId,
                        songTitleRaw = next.title,
                        artistNameRaw = next.artists.firstOrNull()?.name.orEmpty(),
                        storefront = storefront,
                        requireVertical = shouldUseV7Canvas,
                        allowNetwork = true,
                        albumTitle = next.album?.title,
                        trySpotifyCanvas = spotifyCanvasEnabled,
                    )
                }
            }
        }

        LaunchedEffect(playerConnection, mediaMetadata?.id) {
            playerConnection.canvasArtworkUpdates.collect { update ->
                if (update.mediaId != mediaMetadata?.id) return@collect

                canvasArtworkRevision += 1
                if (!update.artwork.preferredVerticalAnimationUrl.isNullOrBlank()) {
                    v7CanvasArtwork = update.artwork
                }
                if (!update.artwork.preferredAnimationUrl.isNullOrBlank()) {
                    artworkCanvas = update.artwork
                }
            }
        }

        LaunchedEffect(shouldUseV7Canvas, shouldFetchV7Canvas, mediaMetadata?.id) {
            val metadata = mediaMetadata
            if (!shouldUseV7Canvas || metadata == null) {
                v7CanvasArtwork = null
                v7CanvasFetchInFlight = false
                return@LaunchedEffect
            }

            val artistNameRaw =
                metadata.artists
                    .firstOrNull()
                    ?.name
                    .orEmpty()
            if (v7CanvasFetchInFlight) {
                return@LaunchedEffect
            }

            v7CanvasFetchInFlight = true
            try {
                val requestRevision = canvasArtworkRevision
                val resolvedArtwork =
                    resolveCanvasArtworkForPlayback(
                        mediaId = metadata.id,
                        songTitleRaw = metadata.title,
                        artistNameRaw = artistNameRaw,
                        storefront = storefront,
                        requireVertical = true,
                        allowNetwork = shouldFetchV7Canvas,
                        albumTitle = metadata.album?.title,
                        trySpotifyCanvas = spotifyCanvasEnabled,
                    )
                if (requestRevision == canvasArtworkRevision) {
                    v7CanvasArtwork = resolvedArtwork
                }
            } finally {
                v7CanvasFetchInFlight = false
            }
        }

        // The Immersive style reads the canvas this sheet already resolves (prefetch, refetch
        // updates and low-data gating included) instead of running a second resolver.
        LaunchedEffect(v7CanvasArtwork, mediaMetadata?.id) {
            val mediaId = mediaMetadata?.id ?: return@LaunchedEffect
            immersivePlayerViewModel.setCanvas(mediaId, v7CanvasArtwork?.toImmersiveCanvasVideo())
        }

        LaunchedEffect(shouldUseArtworkCanvas, shouldFetchArtworkCanvas, mediaMetadata?.id) {
            val metadata = mediaMetadata
            if (!shouldUseArtworkCanvas || metadata == null) {
                artworkCanvas = null
                artworkCanvasFetchInFlight = false
                return@LaunchedEffect
            }

            val artistNameRaw =
                metadata.artists
                    .firstOrNull()
                    ?.name
                    .orEmpty()
            if (artworkCanvasFetchInFlight) {
                return@LaunchedEffect
            }

            artworkCanvasFetchInFlight = true
            try {
                val requestRevision = canvasArtworkRevision
                val resolvedArtwork =
                    resolveCanvasArtworkForPlayback(
                        mediaId = metadata.id,
                        songTitleRaw = metadata.title,
                        artistNameRaw = artistNameRaw,
                        storefront = storefront,
                        requireVertical = false,
                        allowNetwork = shouldFetchArtworkCanvas,
                        albumTitle = metadata.album?.title,
                        trySpotifyCanvas = spotifyCanvasEnabled,
                    )
                if (requestRevision == canvasArtworkRevision) {
                    artworkCanvas = resolvedArtwork
                }
            } finally {
                artworkCanvasFetchInFlight = false
            }
        }

        val controlsContent: @Composable ColumnScope.(MediaMetadata) -> Unit = { mediaMetadata ->
            PlayerControlsContent(
                mediaMetadata = mediaMetadata,
                playerDesignStyle = playerDesignStyle,
                sliderStyle = sliderStyle,
                playbackState = playbackState,
                isPlaying = isPlaying,
                isLoading = isLoading,
                repeatMode = repeatMode,
                canSkipPrevious = canSkipPrevious,
                canSkipNext = canSkipNext,
                textButtonColor = textButtonColor,
                iconButtonColor = iconButtonColor,
                textBackgroundColor = TextBackgroundColor,
                icBackgroundColor = icBackgroundColor,
                sliderPosition = sliderPosition,
                position = position,
                duration = duration,
                playerConnection = playerConnection,
                navController = navController,
                state = state,
                menuState = menuState,
                bottomSheetPageState = bottomSheetPageState,
                context = context,
                onSliderValueChange = onSliderValueChange,
                onSliderValueChangeFinished = onSliderValueChangeFinished,
                currentFormat = if (playerDesignStyle == PlayerDesignStyle.V7) currentFormat else null,
            )
        }

        Box(
            modifier =
                Modifier
                    .fillMaxSize(),
        ) {
        if (!state.isCollapsed &&
            !aodModeEnabled &&
            playerDesignStyle != PlayerDesignStyle.V5 &&
            playerDesignStyle != PlayerDesignStyle.V7 &&
            playerDesignStyle != PlayerDesignStyle.V8 &&
            playerDesignStyle != PlayerDesignStyle.V9 &&
            playerDesignStyle != PlayerDesignStyle.APPLE_MUSIC &&
            playerDesignStyle != PlayerDesignStyle.BITCHORD &&
            playerDesignStyle != PlayerDesignStyle.TIKTOK &&
            playerDesignStyle != PlayerDesignStyle.SIMPMUSIC &&
            playerDesignStyle != PlayerDesignStyle.SPATIALFLOW
        ) {
            PlayerBackground(
                playerBackground = playerBackground,
                mediaMetadata = mediaMetadata,
                gradientColors = gradientColors,
                disableBlur = disableBlur,
                blurRadius = blurRadius,
                playerCustomImageUri = playerCustomImageUri,
                playerCustomBlur = playerCustomBlur,
                playerCustomContrast = playerCustomContrast,
                playerCustomBrightness = playerCustomBrightness,
            )
        }

        when (LocalConfiguration.current.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> {
                if (playerDesignStyle == PlayerDesignStyle.V5) {
                    val littleBackground = MaterialTheme.colorScheme.primaryContainer
                    val littleTextColor = MaterialTheme.colorScheme.onPrimaryContainer
                    val displayPositionMs = sliderPosition ?: position
                    val progressFraction =
                        remember(displayPositionMs, duration) {
                            if (duration <= 0L || duration == C.TIME_UNSET) {
                                0f
                            } else {
                                (displayPositionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                            }
                        }
                    val progressOverlayColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)

                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(littleBackground),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(progressFraction)
                                    .align(Alignment.TopStart)
                                    .background(progressOverlayColor),
                        )
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .littlePlayerOverlayGestures(
                                        seekEnabled = seekEnabled,
                                        durationMs = duration,
                                        progressFraction = progressFraction,
                                        canSkipPrevious = canSkipPrevious,
                                        canSkipNext = canSkipNext,
                                        onSeekToPositionMs = updatedOnSliderValueChange,
                                        onSeekFinished = updatedOnSliderValueChangeFinished,
                                        onSkipPrevious = playerConnection::seekToPrevious,
                                        onSkipNext = playerConnection::seekToNext,
                                    ).windowInsetsPadding(
                                        WindowInsets(top = LocalStableSystemBarsTopPadding.current)
                                            .union(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
                                    ),
                        ) {
                            enrichedMetadata?.let { metadata ->
                                LittlePlayerContent(
                                    mediaMetadata = metadata,
                                    sliderPosition = sliderPosition,
                                    positionMs = position,
                                    durationMs = duration,
                                    textColor = littleTextColor,
                                    liked = currentSongLiked,
                                    onCollapse = state::collapseSoft,
                                    onToggleLike = playerConnection::toggleLike,
                                    onExpandQueue = openQueue,
                                    onMenuClick = {
                                        menuState.show {
                                            PlayerMenu(
                                                mediaMetadata = metadata,
                                                navController = navController,
                                                playerBottomSheetState = state,
                                                onShowDetailsDialog = {
                                                    bottomSheetPageState.show {
                                                        ShowMediaInfo(metadata.id)
                                                    }
                                                },
                                                onDismiss = menuState::dismiss,
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                } else if (playerDesignStyle == PlayerDesignStyle.V7) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize(),
                    ) {
                        val v7VideoMetadata = mediaMetadata
                        val v7VideoShowing =
                            videoState != null &&
                                v7VideoMetadata?.isMusicVideo == true &&
                                !v7VideoMetadata.id.isLocalMediaId() &&
                                !aodModeEnabled &&
                                !isLyricsScreenVisible &&
                                !videoPlaybackFailed

                        // Music videos keep this fork's video mode; everything else is
                        // rukamori's Immersive layout.
                        if (!v7VideoShowing || v7VideoMetadata == null) {
                            ImmersivePlayerScreen(
                                state = immersivePlayerState,
                                disableBlur = disableBlur,
                                backdropBlurAmount = backdropBlurAmount,
                                showVolumeBar = showPlayerVolumeBar,
                                showCodecOnPlayer = showCodecOnPlayer,
                                contentBottomPadding = queueSheetState.collapsedBound,
                                onAction = immersivePlayerViewModel::onAction,
                                pauseCanvas = isLyricsScreenVisible,
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .nestedScroll(state.preUpPostDownNestedScrollConnection),
                            )
                        } else {
                            // Pure-black backdrop so the video sits in a
                            // dark frame instead of a blurred thumbnail.
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .background(Color.Black),
                            )

                            if (v7VideoShowing && v7VideoMetadata != null) {
                                InlineVideoPlayer(
                                    state = videoState,
                                    preferredHeight = videoPreferredHeight,
                                    onPreferredHeightChange = { videoPreferredHeight = it },
                                    availableHeights = videoAvailableHeights,
                                    selectedHeight = videoSelectedHeight,
                                    modifier =
                                        Modifier
                                            .align(Alignment.Center)
                                            .padding(bottom = 180.dp)
                                            .fillMaxWidth()
                                            .aspectRatio(16f / 9f)
                                            .clip(RoundedCornerShape(16.dp)),
                                )
                            }

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier =
                                    Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = queueSheetState.collapsedBound)
                                        .windowInsetsPadding(
                                            WindowInsets.systemBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                                        ).nestedScroll(state.preUpPostDownNestedScrollConnection),
                            ) {
                                enrichedMetadata?.let { metadata ->
                                    V8PlayerControlsContent(
                                        mediaMetadata = metadata,
                                        queueTitle = "",
                                        playbackState = playbackState,
                                        isPlaying = isPlaying,
                                        isLoading = isLoading,
                                        canSkipPrevious = canSkipPrevious,
                                        canSkipNext = canSkipNext,
                                        currentSongLiked = currentSongLiked,
                                        sliderPosition = sliderPosition,
                                        position = position,
                                        duration = duration,
                                        volume = deviceMusicVolumeController.volumeFraction,
                                        showVolumeBar = showPlayerVolumeBar,
                                        currentFormat = currentFormat,
                                        playerConnection = playerConnection,
                                        navController = navController,
                                        state = state,
                                        onSliderValueChange = onSliderValueChange,
                                        onSliderValueChangeFinished = onSliderValueChangeFinished,
                                        onVolumeChange = onPlayerVolumeChange,
                                        landscape = true,
                                    )
                                }

                                Spacer(Modifier.height(16.dp))
                            }
                        }
                    }
                } else if (playerDesignStyle == PlayerDesignStyle.V8) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        val v8SwapState =
                            rememberThumbnailSwapState(
                                videoId = mediaMetadata?.id,
                                ytmUrl = mediaMetadata?.thumbnailUrl,
                                lowDataMode = lowDataModeActive,
                                isMusicVideo = mediaMetadata?.isMusicVideo ?: false,
                            )
                        val v8VideoMetadata = mediaMetadata
                        val v8VideoShowing =
                            videoState != null &&
                                v8VideoMetadata?.isMusicVideo == true &&
                                !v8VideoMetadata.id.isLocalMediaId() &&
                                !aodModeEnabled &&
                                !isLyricsScreenVisible &&
                                !videoPlaybackFailed
                        if (v8VideoShowing) {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .background(Color.Black),
                            )
                        } else {
                            V8PlayerBackdrop(
                                thumbnailUrl = v8SwapState.displayUrl,
                                backdropBlurAmount = backdropBlurAmount,
                            )
                        }

                        enrichedMetadata?.let { metadata ->
                            V8PlayerContent(
                                mediaMetadata = metadata,
                                queueTitle = queueTitle,
                                playbackState = playbackState,
                                isPlaying = isPlaying,
                                isLoading = isLoading,
                                canSkipPrevious = canSkipPrevious,
                                canSkipNext = canSkipNext,
                                currentSongLiked = currentSongLiked,
                                sliderPosition = sliderPosition,
                                position = position,
                                duration = duration,
                                volume = deviceMusicVolumeController.volumeFraction,
                                showVolumeBar = showPlayerVolumeBar,
                                playerConnection = playerConnection,
                                navController = navController,
                                state = state,
                                currentFormat = currentFormat,
                                canvasPrimaryUrl = artworkCanvas?.animated,
                                canvasFallbackUrl = artworkCanvas?.videoUrl,
                                onSliderValueChange = onSliderValueChange,
                                onSliderValueChangeFinished = onSliderValueChangeFinished,
                                onVolumeChange = onPlayerVolumeChange,
                                landscape = true,
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .padding(bottom = queueSheetState.collapsedBound)
                                        .windowInsetsPadding(
                                            WindowInsets(top = LocalStableSystemBarsTopPadding.current)
                                                .union(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
                                        ).nestedScroll(state.preUpPostDownNestedScrollConnection),
                            )
                        }
                    }
                } else if (playerDesignStyle == PlayerDesignStyle.V9) {
                    enrichedMetadata?.let { metadata ->
                        V9PlayerContent(
                            mediaMetadata = metadata,
                            playbackState = playbackState,
                            isPlaying = isPlaying,
                            isLoading = isLoading,
                            canSkipPrevious = canSkipPrevious,
                            canSkipNext = canSkipNext,
                            sliderPosition = sliderPosition,
                            position = position,
                            duration = duration,
                            playerConnection = playerConnection,
                            navController = navController,
                            state = state,
                            textBackgroundColor = TextBackgroundColor,
                            textButtonColor = textButtonColor,
                            iconButtonColor = iconButtonColor,
                            canvasPrimaryUrl = artworkCanvas?.animated,
                            canvasFallbackUrl = artworkCanvas?.videoUrl,
                            onCollapseClick = { state.collapseSoft() },
                            onQueueClick = openQueue,
                            onLyricsClick = { isLyricsScreenVisible = true },
                            onSliderValueChange = onSliderValueChange,
                            onSliderValueChangeFinished = onSliderValueChangeFinished,
                            landscape = true,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(bottom = queueSheetState.collapsedBound)
                                    .windowInsetsPadding(
                                        WindowInsets(top = LocalStableSystemBarsTopPadding.current)
                                            .union(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
                                    ).nestedScroll(state.preUpPostDownNestedScrollConnection),
                        )
                    }
                } else if (playerDesignStyle == PlayerDesignStyle.V10) {
                    enrichedMetadata?.let { metadata ->
                        V10PlayerContent(
                            mediaMetadata = metadata,
                            playbackState = playbackState,
                            isPlaying = isPlaying,
                            isLoading = isLoading,
                            canSkipPrevious = canSkipPrevious,
                            canSkipNext = canSkipNext,
                            sliderPosition = sliderPosition,
                            position = position,
                            duration = duration,
                            playerConnection = playerConnection,
                            navController = navController,
                            state = state,
                            textBackgroundColor = dynamicV10AccentColor,
                            textButtonColor = dynamicV10FieldColor,
                            iconButtonColor = iconButtonColor,
                            onCollapseClick = { state.collapseSoft() },
                            onQueueClick = openQueue,
                            onLyricsClick = { isLyricsScreenVisible = true },
                            onSliderValueChange = onSliderValueChange,
                            onSliderValueChangeFinished = onSliderValueChangeFinished,
                            onSleepTimerClick = {
                                if (sleepTimerTimeLeft > 0L) {
                                    playerConnection.service.sleepTimer.clear()
                                } else {
                                    showSleepTimerDialog = true
                                }
                            },
                            sleepTimerEnabled = sleepTimerTimeLeft > 0L,
                            sleepTimerTimeLeft = sleepTimerTimeLeft,
                            onMenuClick = {
                                menuState.show {
                                    PlayerMenu(
                                        mediaMetadata = metadata,
                                        navController = navController,
                                        playerBottomSheetState = state,
                                        onShowDetailsDialog = {
                                            bottomSheetPageState.show {
                                                ShowMediaInfo(metadata.id)
                                            }
                                        },
                                        onDismiss = menuState::dismiss,
                                    )
                                }
                            },
                            onAddToPlaylistClick = {
                                showChoosePlaylistDialog = true
                            },
                            landscape = true,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(bottom = queueSheetState.collapsedBound)
                                    .windowInsetsPadding(
                                        WindowInsets(top = LocalStableSystemBarsTopPadding.current)
                                            .union(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
                                    ).nestedScroll(state.preUpPostDownNestedScrollConnection),
                        )
                    }
                } else if (playerDesignStyle == PlayerDesignStyle.BITCHORD) {
                    // A self-contained port of BitChord's NowPlayingScreen: mesh backdrop, sleeve,
                    // hairline scrubber, inline queue and lyrics. Its content column is width-capped,
                    // so the same portrait-first layout degrades gracefully on a wide screen rather
                    // than stretching — which is why it is not orientation-branched.
                    enrichedMetadata?.let { metadata ->
                        BitChordPlayerContent(
                            mediaMetadata = metadata,
                            isPlaying = isPlaying,
                            isLoading = isLoading,
                            canSkipPrevious = canSkipPrevious,
                            canSkipNext = canSkipNext,
                            positionProvider = positionProvider,
                            duration = duration,
                            playerConnection = playerConnection,
                            navController = navController,
                            state = state,
                            menuState = menuState,
                            bottomSheetPageState = bottomSheetPageState,
                            currentFormat = currentFormat,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .nestedScroll(state.preUpPostDownNestedScrollConnection),
                        )
                    }
                } else if (playerDesignStyle == PlayerDesignStyle.TIKTOK) {
                    // A full-screen vertical feed over the real queue — swipe up for the next song,
                    // down for the previous. The page's hero sizes itself to the middle zone, so the
                    // same layout is width-limited in portrait and height-limited in landscape, and
                    // the pager plus the sheet's nested-scroll connection divide vertical drags
                    // between paging and collapsing the player.
                    //
                    // No onOpenQueue: the feed opens the Apple Music queue sheet in place itself.
                    enrichedMetadata?.let { metadata ->
                        TikTokPlayerContent(
                            mediaMetadata = metadata,
                            isPlaying = isPlaying,
                            isLoading = isLoading,
                            canSkipPrevious = canSkipPrevious,
                            canSkipNext = canSkipNext,
                            sliderPosition = sliderPosition,
                            position = position,
                            duration = duration,
                            playerConnection = playerConnection,
                            navController = navController,
                            state = state,
                            menuState = menuState,
                            bottomSheetPageState = bottomSheetPageState,
                            lyricsVisible = isLyricsScreenVisible,
                            lyricsSyncOffset = lyricsSyncOffset,
                            onLyricsSyncOffsetChange = { lyricsSyncOffset = it },
                            onSeek = onSliderValueChange,
                            onSeekFinished = onSliderValueChangeFinished,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .nestedScroll(state.preUpPostDownNestedScrollConnection),
                        )
                    }
                } else if (playerDesignStyle == PlayerDesignStyle.SPATIALFLOW) {
                    // SpatialFlow: artwork pager, pill control row, wavy seek bar, sliding queue
                    // drawer and a circular-reveal lyrics overlay. Like the styles above it fills
                    // whatever box it is handed, so it is not orientation-branched.
                    enrichedMetadata?.let { metadata ->
                        SpatialFlowPlayerContent(
                            mediaMetadata = metadata,
                            isPlaying = isPlaying,
                            isLoading = isLoading,
                            canSkipPrevious = canSkipPrevious,
                            canSkipNext = canSkipNext,
                            position = position,
                            duration = duration,
                            playerConnection = playerConnection,
                            navController = navController,
                            state = state,
                            menuState = menuState,
                            bottomSheetPageState = bottomSheetPageState,
                            currentFormat = currentFormat,
                            positionProvider = { position },
                            canvasPrimaryUrl = artworkCanvas?.animated,
                            canvasFallbackUrl = artworkCanvas?.videoUrl,
                            onSeek = onSliderValueChange,
                            onSeekFinished = onSliderValueChangeFinished,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .windowInsetsPadding(
                                        WindowInsets.systemBars.only(WindowInsetsSides.Horizontal),
                                    ).nestedScroll(state.preUpPostDownNestedScrollConnection),
                        )
                    }
                } else if (playerDesignStyle == PlayerDesignStyle.LOOPER) {
                    // Looper: editorial large-type layout, Jost family, wavy progress ring and a
                    // drag-scrubbable transport. Fills its box, so it is not orientation-branched.
                    enrichedMetadata?.let { metadata ->
                        LooperPlayerContent(
                            mediaMetadata = metadata,
                            isPlaying = isPlaying,
                            isLoading = isLoading,
                            canSkipPrevious = canSkipPrevious,
                            canSkipNext = canSkipNext,
                            sliderPosition = sliderPosition,
                            position = position,
                            duration = duration,
                            playerConnection = playerConnection,
                            navController = navController,
                            state = state,
                            menuState = menuState,
                            bottomSheetPageState = bottomSheetPageState,
                            currentFormat = currentFormat,
                            canvasPrimaryUrl = artworkCanvas?.animated,
                            canvasFallbackUrl = artworkCanvas?.videoUrl,
                            onSeek = onSliderValueChange,
                            onSeekFinished = onSliderValueChangeFinished,
                            onLyricsClick = { isLyricsScreenVisible = true },
                            onQueueClick = openQueue,
                            lyricsVisible = false,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .nestedScroll(state.preUpPostDownNestedScrollConnection),
                        )
                    }
                } else if (playerDesignStyle == PlayerDesignStyle.SIMPMUSIC) {
                    // SimpMusic's default now-playing screen: a diagonal wash pulled from the
                    // artwork palette, the sleeve on a pager backed by the real queue, then the
                    // info row, scrubber and transport. Like the two styles above it sizes itself
                    // to whatever box it is given, so it is not orientation-branched.
                    enrichedMetadata?.let { metadata ->
                        SimpMusicPlayerContent(
                            mediaMetadata = metadata,
                            isPlaying = isPlaying,
                            isLoading = isLoading,
                            canSkipPrevious = canSkipPrevious,
                            canSkipNext = canSkipNext,
                            sliderPosition = sliderPosition,
                            position = position,
                            duration = duration,
                            playerConnection = playerConnection,
                            navController = navController,
                            state = state,
                            menuState = menuState,
                            bottomSheetPageState = bottomSheetPageState,
                            currentFormat = currentFormat,
                            onSeek = onSliderValueChange,
                            onSeekFinished = onSliderValueChangeFinished,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .nestedScroll(state.preUpPostDownNestedScrollConnection),
                        )
                    }
                } else if (playerDesignStyle == PlayerDesignStyle.APPLE_MUSIC) {
                    enrichedMetadata?.let { metadata ->
                        AppleMusicPlayerContent(
                            mediaMetadata = metadata,
                            playbackState = playbackState,
                            isPlaying = isPlaying && !isLyricsScreenVisible,
                            isLoading = isLoading,
                            canSkipPrevious = canSkipPrevious,
                            canSkipNext = canSkipNext,
                            sliderPosition = sliderPosition,
                            positionProvider = positionProvider,
                            duration = duration,
                            playerConnection = playerConnection,
                            navController = navController,
                            state = state,
                            bottomSheetPageState = bottomSheetPageState,
                            currentSongLiked = currentSongLiked,
                            volume = deviceMusicVolumeController.volumeFraction,
                            onVolumeChange = onPlayerVolumeChange,
                            canvasPrimaryUrl = artworkCanvas?.animated,
                            canvasFallbackUrl = artworkCanvas?.videoUrl,
                            currentFormat = currentFormat,
                            contentBottomPadding = queueSheetState.collapsedBound + 20.dp,
                            onQueueClick = openQueue,
                            onLyricsClick = { isLyricsScreenVisible = true },
                            onSliderValueChange = onSliderValueChange,
                            onSliderValueChangeFinished = onSliderValueChangeFinished,
                            lyricsSyncOffset = lyricsSyncOffset,
                            onLyricsSyncOffsetChange = { lyricsSyncOffset = it },
                            onLyricsVisibilityChange = { isAppleMusicInlineLyricsOpen = it },
                            landscape = true,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .nestedScroll(state.preUpPostDownNestedScrollConnection),
                        )
                    }
            } else {
                    Row(
                        modifier =
                            Modifier
                                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
                                .padding(bottom = queueSheetState.collapsedBound + 48.dp),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.weight(1f),
                        ) {
                            val screenWidth = LocalConfiguration.current.screenWidthDp
                            val thumbnailSize = (screenWidth * 0.4).dp
                            Thumbnail(
                                sliderPositionProvider = { sliderPosition },
                                modifier = Modifier.size(thumbnailSize),
                                isPlayerExpanded = state.isExpanded,
                                onOpenLyrics = { isLyricsScreenVisible = true },
                            )
                        }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .windowInsetsPadding(WindowInsets(top = LocalStableSystemBarsTopPadding.current)),
                        ) {
                            Spacer(Modifier.weight(1f))

                            enrichedMetadata?.let {
                                controlsContent(it)
                            }

                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            else -> {
                if (playerDesignStyle == PlayerDesignStyle.V5) {
                    val littleBackground = MaterialTheme.colorScheme.primaryContainer
                    val littleTextColor = MaterialTheme.colorScheme.onPrimaryContainer
                    val displayPositionMs = sliderPosition ?: position
                    val progressFraction =
                        remember(displayPositionMs, duration) {
                            if (duration <= 0L || duration == C.TIME_UNSET) {
                                0f
                            } else {
                                (displayPositionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                            }
                        }
                    val progressOverlayColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                    val seekEnabled = duration > 0L && duration != C.TIME_UNSET

                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(littleBackground),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(progressFraction)
                                    .align(Alignment.TopStart)
                                    .background(progressOverlayColor),
                        )
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .littlePlayerOverlayGestures(
                                        seekEnabled = seekEnabled,
                                        durationMs = duration,
                                        progressFraction = progressFraction,
                                        canSkipPrevious = canSkipPrevious,
                                        canSkipNext = canSkipNext,
                                        onSeekToPositionMs = updatedOnSliderValueChange,
                                        onSeekFinished = updatedOnSliderValueChangeFinished,
                                        onSkipPrevious = playerConnection::seekToPrevious,
                                        onSkipNext = playerConnection::seekToNext,
                                    ).windowInsetsPadding(
                                        WindowInsets(top = LocalStableSystemBarsTopPadding.current)
                                            .union(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
                                    ),
                        ) {
                            enrichedMetadata?.let { metadata ->
                                LandscapeLikeBox(modifier = Modifier.fillMaxSize()) {
                                    LittlePlayerContent(
                                        mediaMetadata = metadata,
                                        sliderPosition = sliderPosition,
                                        positionMs = position,
                                        durationMs = duration,
                                        textColor = littleTextColor,
                                        liked = currentSongLiked,
                                        onCollapse = state::collapseSoft,
                                        onToggleLike = playerConnection::toggleLike,
                                        onExpandQueue = openQueue,
                                        onMenuClick = {
                                            menuState.show {
                                                PlayerMenu(
                                                    mediaMetadata = metadata,
                                                    navController = navController,
                                                    playerBottomSheetState = state,
                                                    onShowDetailsDialog = {
                                                        bottomSheetPageState.show {
                                                            ShowMediaInfo(metadata.id)
                                                        }
                                                    },
                                                    onDismiss = menuState::dismiss,
                                                )
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                } else if (playerDesignStyle == PlayerDesignStyle.V7) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize(),
                    ) {
                        val v7VideoMetadata = mediaMetadata
                        val v7VideoShowing =
                            videoState != null &&
                                v7VideoMetadata?.isMusicVideo == true &&
                                !v7VideoMetadata.id.isLocalMediaId() &&
                                !aodModeEnabled &&
                                !isLyricsScreenVisible &&
                                !videoPlaybackFailed

                        // Music videos keep this fork's video mode; everything else is
                        // rukamori's Immersive layout.
                        if (!v7VideoShowing || v7VideoMetadata == null) {
                            ImmersivePlayerScreen(
                                state = immersivePlayerState,
                                disableBlur = disableBlur,
                                backdropBlurAmount = backdropBlurAmount,
                                showVolumeBar = showPlayerVolumeBar,
                                showCodecOnPlayer = showCodecOnPlayer,
                                contentBottomPadding = queueSheetState.collapsedBound,
                                onAction = immersivePlayerViewModel::onAction,
                                pauseCanvas = isLyricsScreenVisible,
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .nestedScroll(state.preUpPostDownNestedScrollConnection),
                            )
                        } else {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .background(Color.Black),
                            )

                            if (v7VideoShowing && v7VideoMetadata != null) {
                                InlineVideoPlayer(
                                    state = videoState,
                                    preferredHeight = videoPreferredHeight,
                                    onPreferredHeightChange = { videoPreferredHeight = it },
                                    availableHeights = videoAvailableHeights,
                                    selectedHeight = videoSelectedHeight,
                                    modifier =
                                        Modifier
                                            .align(Alignment.Center)
                                            .padding(bottom = 180.dp)
                                            .fillMaxWidth()
                                            .aspectRatio(16f / 9f)
                                            .clip(RoundedCornerShape(16.dp)),
                                )
                            }

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier =
                                    Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = queueSheetState.collapsedBound)
                                        .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
                                        .nestedScroll(state.preUpPostDownNestedScrollConnection),
                            ) {
                                enrichedMetadata?.let { metadata ->
                                    V8PlayerControlsContent(
                                        mediaMetadata = metadata,
                                        queueTitle = "",
                                        playbackState = playbackState,
                                        isPlaying = isPlaying,
                                        isLoading = isLoading,
                                        canSkipPrevious = canSkipPrevious,
                                        canSkipNext = canSkipNext,
                                        currentSongLiked = currentSongLiked,
                                        sliderPosition = sliderPosition,
                                        position = position,
                                        duration = duration,
                                        volume = deviceMusicVolumeController.volumeFraction,
                                        showVolumeBar = showPlayerVolumeBar,
                                        currentFormat = currentFormat,
                                        playerConnection = playerConnection,
                                        navController = navController,
                                        state = state,
                                        onSliderValueChange = onSliderValueChange,
                                        onSliderValueChangeFinished = onSliderValueChangeFinished,
                                        onVolumeChange = onPlayerVolumeChange,
                                    )
                                }

                                Spacer(Modifier.height(24.dp))
                            }
                        }
                    }
                } else if (playerDesignStyle == PlayerDesignStyle.V8) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        val v8SwapState =
                            rememberThumbnailSwapState(
                                videoId = mediaMetadata?.id,
                                ytmUrl = mediaMetadata?.thumbnailUrl,
                                lowDataMode = lowDataModeActive,
                                isMusicVideo = mediaMetadata?.isMusicVideo ?: false,
                            )
                        val v8VideoMetadata = mediaMetadata
                        val v8VideoShowing =
                            videoState != null &&
                                v8VideoMetadata?.isMusicVideo == true &&
                                !v8VideoMetadata.id.isLocalMediaId() &&
                                !aodModeEnabled &&
                                !isLyricsScreenVisible &&
                                !videoPlaybackFailed
                        if (v8VideoShowing) {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .background(Color.Black),
                            )
                        } else {
                            V8PlayerBackdrop(
                                thumbnailUrl = v8SwapState.displayUrl,
                                backdropBlurAmount = backdropBlurAmount,
                            )
                        }

                        enrichedMetadata?.let { metadata ->
                            V8PlayerContent(
                                mediaMetadata = metadata,
                                queueTitle = queueTitle,
                                playbackState = playbackState,
                                isPlaying = isPlaying,
                                isLoading = isLoading,
                                canSkipPrevious = canSkipPrevious,
                                canSkipNext = canSkipNext,
                                currentSongLiked = currentSongLiked,
                                sliderPosition = sliderPosition,
                                position = position,
                                duration = duration,
                                volume = deviceMusicVolumeController.volumeFraction,
                                showVolumeBar = showPlayerVolumeBar,
                                playerConnection = playerConnection,
                                navController = navController,
                                state = state,
                                currentFormat = currentFormat,
                                canvasPrimaryUrl = artworkCanvas?.animated,
                                canvasFallbackUrl = artworkCanvas?.videoUrl,
                                onSliderValueChange = onSliderValueChange,
                                onSliderValueChangeFinished = onSliderValueChangeFinished,
                                onVolumeChange = onPlayerVolumeChange,
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .padding(bottom = queueSheetState.collapsedBound)
                                        .windowInsetsPadding(
                                            WindowInsets(top = LocalStableSystemBarsTopPadding.current)
                                                .union(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal)),
                                        ).nestedScroll(state.preUpPostDownNestedScrollConnection),
                            )
                        }
                    }
                } else if (playerDesignStyle == PlayerDesignStyle.V9) {
                    enrichedMetadata?.let { metadata ->
                        V9PlayerContent(
                            mediaMetadata = metadata,
                            playbackState = playbackState,
                            isPlaying = isPlaying,
                            isLoading = isLoading,
                            canSkipPrevious = canSkipPrevious,
                            canSkipNext = canSkipNext,
                            sliderPosition = sliderPosition,
                            position = position,
                            duration = duration,
                            playerConnection = playerConnection,
                            navController = navController,
                            state = state,
                            textBackgroundColor = TextBackgroundColor,
                            textButtonColor = textButtonColor,
                            iconButtonColor = iconButtonColor,
                            canvasPrimaryUrl = artworkCanvas?.animated,
                            canvasFallbackUrl = artworkCanvas?.videoUrl,
                            onCollapseClick = { state.collapseSoft() },
                            onQueueClick = openQueue,
                            onLyricsClick = { isLyricsScreenVisible = true },
                            onSliderValueChange = onSliderValueChange,
                            onSliderValueChangeFinished = onSliderValueChangeFinished,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(bottom = queueSheetState.collapsedBound)
                                    .windowInsetsPadding(
                                        WindowInsets(top = LocalStableSystemBarsTopPadding.current)
                                            .union(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal)),
                                    ).nestedScroll(state.preUpPostDownNestedScrollConnection),
                        )
                    }
                } else if (playerDesignStyle == PlayerDesignStyle.V10) {
                    enrichedMetadata?.let { metadata ->
                        V10PlayerContent(
                            mediaMetadata = metadata,
                            playbackState = playbackState,
                            isPlaying = isPlaying,
                            isLoading = isLoading,
                            canSkipPrevious = canSkipPrevious,
                            canSkipNext = canSkipNext,
                            sliderPosition = sliderPosition,
                            position = position,
                            duration = duration,
                            playerConnection = playerConnection,
                            navController = navController,
                            state = state,
                            textBackgroundColor = dynamicV10AccentColor,
                            textButtonColor = dynamicV10FieldColor,
                            iconButtonColor = iconButtonColor,
                            onCollapseClick = { state.collapseSoft() },
                            onQueueClick = openQueue,
                            onLyricsClick = { isLyricsScreenVisible = true },
                            onSliderValueChange = onSliderValueChange,
                            onSliderValueChangeFinished = onSliderValueChangeFinished,
                            onSleepTimerClick = {
                                if (sleepTimerTimeLeft > 0L) {
                                    playerConnection.service.sleepTimer.clear()
                                } else {
                                    showSleepTimerDialog = true
                                }
                            },
                            sleepTimerEnabled = sleepTimerTimeLeft > 0L,
                            sleepTimerTimeLeft = sleepTimerTimeLeft,
                            onMenuClick = {
                                menuState.show {
                                    PlayerMenu(
                                        mediaMetadata = metadata,
                                        navController = navController,
                                        playerBottomSheetState = state,
                                        onShowDetailsDialog = {
                                            bottomSheetPageState.show {
                                                ShowMediaInfo(metadata.id)
                                            }
                                        },
                                        onDismiss = menuState::dismiss,
                                    )
                                }
                            },
                            onAddToPlaylistClick = {
                                showChoosePlaylistDialog = true
                            },
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(bottom = queueSheetState.collapsedBound)
                                    .windowInsetsPadding(
                                        WindowInsets(top = LocalStableSystemBarsTopPadding.current)
                                            .union(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal)),
                                    ).nestedScroll(state.preUpPostDownNestedScrollConnection),
                        )
                    }

                } else if (playerDesignStyle == PlayerDesignStyle.BITCHORD) {
                    // A self-contained port of BitChord's NowPlayingScreen: mesh backdrop, sleeve,
                    // hairline scrubber, inline queue and lyrics. Its content column is width-capped,
                    // so the same portrait-first layout degrades gracefully on a wide screen rather
                    // than stretching — which is why it is not orientation-branched.
                    enrichedMetadata?.let { metadata ->
                        BitChordPlayerContent(
                            mediaMetadata = metadata,
                            isPlaying = isPlaying,
                            isLoading = isLoading,
                            canSkipPrevious = canSkipPrevious,
                            canSkipNext = canSkipNext,
                            positionProvider = positionProvider,
                            duration = duration,
                            playerConnection = playerConnection,
                            navController = navController,
                            state = state,
                            menuState = menuState,
                            bottomSheetPageState = bottomSheetPageState,
                            currentFormat = currentFormat,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .nestedScroll(state.preUpPostDownNestedScrollConnection),
                        )
                    }
                } else if (playerDesignStyle == PlayerDesignStyle.TIKTOK) {
                    // A full-screen vertical feed over the real queue — swipe up for the next song,
                    // down for the previous. The page's hero sizes itself to the middle zone, so the
                    // same layout is width-limited in portrait and height-limited in landscape, and
                    // the pager plus the sheet's nested-scroll connection divide vertical drags
                    // between paging and collapsing the player.
                    //
                    // No onOpenQueue: the feed opens the Apple Music queue sheet in place itself.
                    enrichedMetadata?.let { metadata ->
                        TikTokPlayerContent(
                            mediaMetadata = metadata,
                            isPlaying = isPlaying,
                            isLoading = isLoading,
                            canSkipPrevious = canSkipPrevious,
                            canSkipNext = canSkipNext,
                            sliderPosition = sliderPosition,
                            position = position,
                            duration = duration,
                            playerConnection = playerConnection,
                            navController = navController,
                            state = state,
                            menuState = menuState,
                            bottomSheetPageState = bottomSheetPageState,
                            lyricsVisible = isLyricsScreenVisible,
                            lyricsSyncOffset = lyricsSyncOffset,
                            onLyricsSyncOffsetChange = { lyricsSyncOffset = it },
                            onSeek = onSliderValueChange,
                            onSeekFinished = onSliderValueChangeFinished,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .nestedScroll(state.preUpPostDownNestedScrollConnection),
                        )
                    }
                } else if (playerDesignStyle == PlayerDesignStyle.SPATIALFLOW) {
                    // SpatialFlow: artwork pager, pill control row, wavy seek bar, sliding queue
                    // drawer and a circular-reveal lyrics overlay. Like the styles above it fills
                    // whatever box it is handed, so it is not orientation-branched.
                    enrichedMetadata?.let { metadata ->
                        SpatialFlowPlayerContent(
                            mediaMetadata = metadata,
                            isPlaying = isPlaying,
                            isLoading = isLoading,
                            canSkipPrevious = canSkipPrevious,
                            canSkipNext = canSkipNext,
                            position = position,
                            duration = duration,
                            playerConnection = playerConnection,
                            navController = navController,
                            state = state,
                            menuState = menuState,
                            bottomSheetPageState = bottomSheetPageState,
                            currentFormat = currentFormat,
                            positionProvider = { position },
                            canvasPrimaryUrl = artworkCanvas?.animated,
                            canvasFallbackUrl = artworkCanvas?.videoUrl,
                            onSeek = onSliderValueChange,
                            onSeekFinished = onSliderValueChangeFinished,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .windowInsetsPadding(
                                        WindowInsets.systemBars.only(WindowInsetsSides.Horizontal),
                                    ).nestedScroll(state.preUpPostDownNestedScrollConnection),
                        )
                    }
                } else if (playerDesignStyle == PlayerDesignStyle.LOOPER) {
                    // Looper: editorial large-type layout, Jost family, wavy progress ring and a
                    // drag-scrubbable transport. Fills its box, so it is not orientation-branched.
                    enrichedMetadata?.let { metadata ->
                        LooperPlayerContent(
                            mediaMetadata = metadata,
                            isPlaying = isPlaying,
                            isLoading = isLoading,
                            canSkipPrevious = canSkipPrevious,
                            canSkipNext = canSkipNext,
                            sliderPosition = sliderPosition,
                            position = position,
                            duration = duration,
                            playerConnection = playerConnection,
                            navController = navController,
                            state = state,
                            menuState = menuState,
                            bottomSheetPageState = bottomSheetPageState,
                            currentFormat = currentFormat,
                            canvasPrimaryUrl = artworkCanvas?.animated,
                            canvasFallbackUrl = artworkCanvas?.videoUrl,
                            onSeek = onSliderValueChange,
                            onSeekFinished = onSliderValueChangeFinished,
                            onLyricsClick = { isLyricsScreenVisible = true },
                            onQueueClick = openQueue,
                            lyricsVisible = false,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .nestedScroll(state.preUpPostDownNestedScrollConnection),
                        )
                    }
                } else if (playerDesignStyle == PlayerDesignStyle.SIMPMUSIC) {
                    // SimpMusic's default now-playing screen: a diagonal wash pulled from the
                    // artwork palette, the sleeve on a pager backed by the real queue, then the
                    // info row, scrubber and transport. Like the two styles above it sizes itself
                    // to whatever box it is given, so it is not orientation-branched.
                    enrichedMetadata?.let { metadata ->
                        SimpMusicPlayerContent(
                            mediaMetadata = metadata,
                            isPlaying = isPlaying,
                            isLoading = isLoading,
                            canSkipPrevious = canSkipPrevious,
                            canSkipNext = canSkipNext,
                            sliderPosition = sliderPosition,
                            position = position,
                            duration = duration,
                            playerConnection = playerConnection,
                            navController = navController,
                            state = state,
                            menuState = menuState,
                            bottomSheetPageState = bottomSheetPageState,
                            currentFormat = currentFormat,
                            onSeek = onSliderValueChange,
                            onSeekFinished = onSliderValueChangeFinished,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .nestedScroll(state.preUpPostDownNestedScrollConnection),
                        )
                    }
                } else if (playerDesignStyle == PlayerDesignStyle.APPLE_MUSIC) {
                    enrichedMetadata?.let { metadata ->
                        AppleMusicPlayerContent(
                            mediaMetadata = metadata,
                            playbackState = playbackState,
                            isPlaying = isPlaying && !isLyricsScreenVisible,
                            isLoading = isLoading,
                            canSkipPrevious = canSkipPrevious,
                            canSkipNext = canSkipNext,
                            sliderPosition = sliderPosition,
                            positionProvider = positionProvider,
                            duration = duration,
                            playerConnection = playerConnection,
                            navController = navController,
                            state = state,
                            bottomSheetPageState = bottomSheetPageState,
                            currentSongLiked = currentSongLiked,
                            volume = deviceMusicVolumeController.volumeFraction,
                            onVolumeChange = onPlayerVolumeChange,
                            canvasPrimaryUrl = artworkCanvas?.animated,
                            canvasFallbackUrl = artworkCanvas?.videoUrl,
                            currentFormat = currentFormat,
                            contentBottomPadding = queueSheetState.collapsedBound + 20.dp,
                            onQueueClick = openQueue,
                            onLyricsClick = { isLyricsScreenVisible = true },
                            onSliderValueChange = onSliderValueChange,
                            onSliderValueChangeFinished = onSliderValueChangeFinished,
                            lyricsSyncOffset = lyricsSyncOffset,
                            onLyricsSyncOffsetChange = { lyricsSyncOffset = it },
                            onLyricsVisibilityChange = { isAppleMusicInlineLyricsOpen = it },
                            // Full-bleed: the artwork runs under the status bar by design, so no
                            // top inset here (mirrors the reference layout).
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .nestedScroll(state.preUpPostDownNestedScrollConnection),
                        )
                    }
            } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier =
                            Modifier
                                .windowInsetsPadding(
                                    WindowInsets.systemBars.only(
                                        WindowInsetsSides.Horizontal,
                                    ),
                                ).padding(bottom = queueSheetState.collapsedBound),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.weight(1f),
                        ) {
                            Thumbnail(
                                sliderPositionProvider = { sliderPosition },
                                modifier = Modifier.nestedScroll(state.preUpPostDownNestedScrollConnection),
                                isPlayerExpanded = state.isExpanded,
                                onOpenLyrics = { isLyricsScreenVisible = true },
                            )
                        }

                        enrichedMetadata?.let {
                            controlsContent(it)
                        }

                        Spacer(Modifier.height(30.dp))
                    }
                }
            }
        }
        }

        // Queue text color policy:
        //  - Apple Music style keeps a dark frosted backdrop in both light & dark
        //    themes, so its queue text is pinned to white (matches AM visual language).
        //  - All other styles follow the surface color: white when the user has
        //    opted into useBlackBackground, otherwise MaterialTheme.colorScheme.onSurface
        //    so titles/artists/dividers/pill outlines stay visible against the
        //    (possibly light, dynamic-themed) surface. This mirrors upstream
        //    rukamori/ArchiveTune Player.kt.
        val queueOnBackgroundColor =
            if (playerDesignStyle.hasCustomBackdrop || useBlackBackground) {
                Color.White
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        val queueSurfaceColor = if (useBlackBackground) Color.Black else MaterialTheme.colorScheme.surface

        val (queueTextButtonColor, queueIconButtonColor) =
            when (playerButtonsStyle) {
                PlayerButtonsStyle.DEFAULT -> {
                    Pair(queueOnBackgroundColor, queueSurfaceColor)
                }

                PlayerButtonsStyle.SECONDARY -> {
                    Pair(
                        MaterialTheme.colorScheme.secondary,
                        MaterialTheme.colorScheme.onSecondary,
                    )
                }
            }

        // Opaque backdrop that fades in with queueSheetState.progress.
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        queueSurfaceColor.copy(
                            alpha = queueSurfaceColor.alpha * queueSheetState.progress.coerceIn(0f, 1f),
                        ),
                    ),
        )

        // Queue sheet — wrapped in AnimatedVisibility with slide+fade so it
        // slides in from below. Hidden while the lyrics screen is on top.
        //
        // The Queue's backgroundColor is queueSurfaceColor (opaque) so the
        // sheet itself is solid — combined with opaqueBackground = true in
        // Queue.kt, this fully covers the player's zoomed artwork behind the
        // queue list during the slide-up drag. The opaque backdrop above
        // covers the artwork in the exposed area above the queue sheet's top
        // edge.
        AnimatedVisibility(
            visible = !isLyricsScreenVisible,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit =
                shrinkVertically(shrinkTowards = Alignment.Top) +
                    slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        ) {
            Queue(
                state = queueSheetState,
                playerBottomSheetState = state,
                navController = navController,
                backgroundColor = queueSurfaceColor,
                onBackgroundColor = queueOnBackgroundColor,
                TextBackgroundColor = TextBackgroundColor,
                textButtonColor = textButtonColor,
                iconButtonColor = iconButtonColor,
                onShowLyrics = { isLyricsScreenVisible = true },
                pureBlack = pureBlack,
            )
        }

        mediaMetadata?.let { metadata ->
            MikoLyricsTransition(
                visible = isLyricsScreenVisible,
                // Only Apple Music style intentionally suppresses the back handler
                // (its lyrics sheet is part of the same collapsed/expanded sheet flow).
                // All other player styles must allow the system back button (and the
                // close affordance in the lyrics top bar) to dismiss the lyrics sheet.
                backHandlerEnabled =
                    isLyricsScreenVisible &&
                        state.isExpandedOrExpanding,
                mediaMetadata = metadata,
                navController = navController,
                lyricsSyncOffset = lyricsSyncOffset,
                onLyricsSyncOffsetChange = { lyricsSyncOffset = it },
                onDismiss = { isLyricsScreenVisible = false },
                onQueueClick = openQueue,
            )
        }

        AnimatedVisibility(
            visible = aodModeEnabled,
            enter = fadeIn(tween(300)),
            exit = fadeOut(tween(300)),
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black),
        ) {
            mediaMetadata?.let { metadata ->
                AodPlayerScreen(
                    mediaMetadata = metadata,
                    isPlaying = isPlaying,
                    position = position,
                    duration = duration,
                    sliderPosition = sliderPosition,
                    canSkipPrevious = canSkipPrevious,
                    canSkipNext = canSkipNext,
                    thumbnailCornerRadius = thumbnailCornerRadius,
                    lyricsText = currentLyricsEntity?.lyrics,
                    onPlayPause = { playerConnection.player.togglePlayPause() },
                    onSkipPrevious = playerConnection::seekToPrevious,
                    onSkipNext = playerConnection::seekToNext,
                    onSeek = { sliderPosition = it },
                    onSeekFinished = onSliderValueChangeFinished,
                    onExit = { playerConnection.aodModeEnabled.value = false },
                )
            }
        }
    }

        val isInPipMode = moe.rukamori.archivetune.ui.player.LocalIsInPipMode.current
        LaunchedEffect(isInPipMode, videoState) {
            if (isInPipMode && videoState != null) {
                videoFullscreenHolder.isFullscreen = true
            }
        }
        videoState?.let { vs ->
            if (videoFullscreenHolder.isFullscreen) {
                FullscreenVideoOverlay(
                    state = vs,
                    preferredHeight = videoPreferredHeight,
                    onPreferredHeightChange = { videoPreferredHeight = it },
                    availableHeights = videoAvailableHeights,
                    selectedHeight = videoSelectedHeight,
                    onDismiss = { videoFullscreenHolder.isFullscreen = false },
                )
            }
        }
    } // close Box(Modifier.fillMaxSize())
    } // close CompositionLocalProvider

    val activePlaybackError = playbackError
    val isRecoveryDestination =
        currentRoute?.startsWith(LOGIN_ROUTE) == true || currentRoute == PO_TOKEN_ROUTE
    if (activePlaybackError != null && !isRecoveryDestination) {
        val errorInfo = remember(activePlaybackError) { activePlaybackError.toPlaybackErrorInfo() }
        val loginClick =
            remember(errorInfo.loginRecoveryUrl, navigateToLogin) {
                { navigateToLogin(errorInfo.loginRecoveryUrl) }
            }

        PlaybackErrorDialog(
            error = activePlaybackError,
            showLoginAction = errorInfo.loginRecoveryUrl != null,
            showPoTokenLoginAction = !isPoTokenLoggedIn,
            onRetry = retryPlayback,
            onClose = dismissPlaybackError,
            onLogin = loginClick,
            onPoTokenLogin = navigateToPoTokenLogin,
        )
    }
}

@Composable
private fun MikoLyricsTransition(
    visible: Boolean,
    backHandlerEnabled: Boolean,
    mediaMetadata: MediaMetadata,
    navController: NavController,
    lyricsSyncOffset: Int,
    onLyricsSyncOffsetChange: (Int) -> Unit,
    onDismiss: () -> Unit,
    onQueueClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val animationsDisabled = LocalAnimationsDisabled.current
    val progress = remember { Animatable(initialValue = 0f) }
    LaunchedEffect(visible, animationsDisabled) {
        if (animationsDisabled) {
            progress.snapTo(if (visible) 1f else 0f)
        } else {
            progress.animateTo(
                targetValue = if (visible) 1f else 0f,
                animationSpec =
                    if (visible) {
                        // OPEN — keep the premium slow glide (~500 ms).
                        spring(
                            dampingRatio = 1f,
                            stiffness = 160f,
                            visibilityThreshold = 0.001f,
                        )
                    } else {
                        // CLOSE — slower by ~40% (~700 ms).
                        spring(
                            dampingRatio = 1f,
                            stiffness = 80f,
                            visibilityThreshold = 0.001f,
                        )
                    },
            )
        }
    }
    val progressState = progress.asState()
    val showContent by remember(visible) {
        derivedStateOf { visible || progressState.value > 0f }
    }

    if (showContent) {
        val surfaceColor = MaterialTheme.colorScheme.surface
        Box(
            modifier =
                modifier
                    .fillMaxSize()
                    .drawBehind {
                        // Use drawRect's built-in alpha parameter instead of
                        // Color.Black.copy(alpha = ...) — avoids allocating a
                        // new Color object on every frame of the slide animation.
                        drawRect(
                            color = Color.Black,
                            alpha = 0.32f * progressState.value.coerceIn(0f, 1f),
                        )
                    },
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val p = progressState.value.coerceIn(0f, 1f)
                            // Pure slide-up: the whole sheet travels from just below the screen to
                            // its resting position, with a small rounded top lip while in transit.
                            translationY = size.height * (1f - p)
                            val corner = 28.dp.toPx() * (1f - p)
                            shape = RoundedCornerShape(topStart = corner, topEnd = corner)
                            clip = true
                        }.background(surfaceColor),
            ) {
                LyricsScreen(
                    mediaMetadata = mediaMetadata,
                    onBackClick = onDismiss,
                    navController = navController,
                    lyricsSyncOffset = lyricsSyncOffset,
                    onLyricsSyncOffsetChange = onLyricsSyncOffsetChange,
                    onQueueClick = onQueueClick,
                    backHandlerEnabled = backHandlerEnabled,
                )
            }
        }
    }
}

@Composable
private fun V8PlayerBackdrop(
    thumbnailUrl: String?,
    backdropBlurAmount: Int,
    modifier: Modifier = Modifier,
) {
    var currentUrl by remember(thumbnailUrl) {
        mutableStateOf(
            thumbnailUrl?.resize(
                width = V8BackdropArtworkSizePx,
                height = V8BackdropArtworkSizePx,
                maxresAllowed = true,
                ytimgResizePolicy = YtimgResizePolicy.AllowAnyAspect,
            ),
        )
    }
    val backdropRequest = rememberOfflineArtworkImageRequest(currentUrl)
    val blurRadiusDp = 44.dp * (backdropBlurAmount.toFloat() / 100f)

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color.Black),
    ) {
        if (currentUrl != null) {
            val backdropHasBlur = backdropBlurAmount > 0
            if (backdropHasBlur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AsyncImage(
                    model = backdropRequest,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .then(if (blurRadiusDp > 0.dp) Modifier.blur(blurRadiusDp) else Modifier)
                            .graphicsLayer {
                                scaleX = 1.16f
                                scaleY = 1.16f
                                alpha = 0.66f
                            },
                    onState = { state ->
                        if (state is coil3.compose.AsyncImagePainter.State.Error) {
                            getNextFallbackUrl(currentUrl)?.let { currentUrl = it }
                        }
                    },
                )
            } else if (backdropHasBlur) {
                BackdropBlurApi30(
                    model = currentUrl,
                    blurAmount = backdropBlurAmount,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = 1.16f
                                scaleY = 1.16f
                                alpha = 0.66f
                            },
                    onError = { failedUrl ->
                        getNextFallbackUrl(failedUrl)?.let { currentUrl = it }
                    },
                )
            } else {
                AsyncImage(
                    model = backdropRequest,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = 1.16f
                                scaleY = 1.16f
                                alpha = 0.66f
                            },
                    onState = { state ->
                        if (state is coil3.compose.AsyncImagePainter.State.Error) {
                            getNextFallbackUrl(currentUrl)?.let { currentUrl = it }
                        }
                    },
                )
            }
        }

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.52f)),
        )
    }
}

@Composable
private fun BackdropBlurApi30(
    model: String?,
    blurAmount: Int,
    modifier: Modifier = Modifier,
    onError: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val imageLoader = context.imageLoader

    val blurredBitmap by produceState<Bitmap?>(null, model, blurAmount) {
        if (model == null) return@produceState
        value =
            withContext(Dispatchers.IO) {
                try {
                    val request =
                        ImageRequest
                            .Builder(context)
                            .data(model)
                            .memoryCacheKey(model)
                            .diskCacheKey(model)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .networkCachePolicy(CachePolicy.ENABLED)
                            .allowHardware(false)
                            .size(500)
                            .build()
                    val result = imageLoader.execute(request)
                    when (result) {
                        is SuccessResult -> {
                            val bitmap = result.image.toBitmap().copy(Bitmap.Config.ARGB_8888, true)
                            val radius = (blurAmount * 25 / 100f).coerceIn(1f, 25f)
                            ImageBlurUtils.blur(bitmap, radius)
                        }

                        else -> {
                            if (onError != null) {
                                withContext(Dispatchers.Main) {
                                    onError(model)
                                }
                            }
                            null
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (onError != null) {
                        withContext(Dispatchers.Main) {
                            onError(model)
                        }
                    }
                    null
                }
            }
    }

    val loadedBitmap = blurredBitmap
    if (loadedBitmap != null) {
        Image(
            painter = BitmapPainter(loadedBitmap.asImageBitmap()),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        AsyncImage(
            model = rememberOfflineArtworkImageRequest(model),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
            onState = { state ->
                if (state is coil3.compose.AsyncImagePainter.State.Error && model != null) {
                    onError?.invoke(model)
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LittlePlayerContent(
    mediaMetadata: MediaMetadata,
    sliderPosition: Long?,
    positionMs: Long,
    durationMs: Long,
    textColor: Color,
    liked: Boolean,
    onCollapse: () -> Unit,
    onToggleLike: () -> Unit,
    onExpandQueue: () -> Unit,
    onMenuClick: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val titleColor = textColor.copy(alpha = 0.95f)
        val secondaryColor = textColor.copy(alpha = 0.6f)
        val timeColor = textColor.copy(alpha = 0.85f)

        val scale =
            minOf(maxWidth / 420.dp, maxHeight / 260.dp)
                .coerceIn(0.78f, 1.15f)

        val titleSize = (56f * scale).sp
        val timeSize = (44f * scale).sp
        val iconSize = (26f * scale).dp
        val collapseIconSize = (28f * scale).dp
        val horizontalPadding = (18f * scale).dp
        val verticalPadding = (10f * scale).dp

        val displayPositionMs = sliderPosition ?: positionMs

        val timeText =
            remember(displayPositionMs, durationMs) {
                val positionText = makeTimeString(displayPositionMs)
                val durationText = if (durationMs != C.TIME_UNSET) makeTimeString(durationMs) else ""
                if (durationText.isBlank()) positionText else "$positionText/$durationText"
            }

        val artistsText =
            remember(mediaMetadata.artists) {
                mediaMetadata.artists.joinToString(separator = ", ") { artist -> artist.name }
            }

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        ) {
            Spacer(Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                PlayerTextBackdrop(
                    textColor = textColor,
                    modifier = Modifier.weight(1f),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        AnimatedContent(
                            targetState = mediaMetadata.title,
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "little_title",
                        ) { title ->
                            PlayerTitleText(
                                title = title,
                                explicit = mediaMetadata.explicit,
                                color = titleColor,
                                style = LocalTextStyle.current,
                                fontSize = titleSize,
                                fontWeight = FontWeight.Bold,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .basicMarquee(),
                            )
                        }

                        Spacer(Modifier.height((10f * scale).dp))

                        mediaMetadata.album?.title?.takeIf { it.isNotBlank() }?.let { albumTitle ->
                            AnimatedContent(
                                targetState = albumTitle,
                                transitionSpec = { fadeIn() togetherWith fadeOut() },
                                label = "little_album",
                            ) { album ->
                                Text(
                                    text = album,
                                    color = secondaryColor,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .basicMarquee(),
                                )
                            }
                        }

                        artistsText.takeIf { it.isNotBlank() }?.let { artists ->
                            AnimatedContent(
                                targetState = artists,
                                transitionSpec = { fadeIn() togetherWith fadeOut() },
                                label = "little_artists",
                            ) { artistLine ->
                                Text(
                                    text = "by - $artistLine",
                                    color = secondaryColor,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .basicMarquee(),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.width((16f * scale).dp))

                Text(
                    text = timeText,
                    color = timeColor,
                    fontSize = timeSize,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.widthIn(min = (140f * scale).dp),
                )
            }

            Spacer(Modifier.height((14f * scale).dp))

            Spacer(Modifier.height((6f * scale).dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.expand_more),
                    contentDescription = null,
                    tint = textColor.copy(alpha = 0.8f),
                    modifier =
                        Modifier
                            .size(collapseIconSize)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onCollapse,
                            ),
                )

                Spacer(Modifier.weight(1f))

                Icon(
                    painter = painterResource(if (liked) R.drawable.favorite else R.drawable.favorite_border),
                    contentDescription = null,
                    tint =
                        if (liked) {
                            MaterialTheme.colorScheme.error.copy(alpha = 0.9f)
                        } else {
                            textColor.copy(alpha = 0.78f)
                        },
                    modifier =
                        Modifier
                            .size(iconSize)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onToggleLike,
                            ),
                )

                Spacer(Modifier.width((18f * scale).dp))

                Icon(
                    painter = painterResource(R.drawable.queue_music),
                    contentDescription = null,
                    tint = textColor.copy(alpha = 0.78f),
                    modifier =
                        Modifier
                            .size(iconSize)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onExpandQueue,
                            ),
                )

                Spacer(Modifier.width((18f * scale).dp))

                Icon(
                    painter = painterResource(R.drawable.more_vert),
                    contentDescription = null,
                    tint = textColor.copy(alpha = 0.78f),
                    modifier =
                        Modifier
                            .size(iconSize)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onMenuClick,
                            ),
                )
            }
        }
    }
}

@Composable
private fun LandscapeLikeBox(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(
        content = content,
        modifier = modifier.graphicsLayer { clip = true },
    ) { measurables, constraints ->
        val measurable = measurables.firstOrNull()
        if (measurable == null) {
            layout(constraints.minWidth, constraints.minHeight) {}
        } else {
            val swappedConstraints =
                Constraints(
                    minWidth = constraints.minHeight,
                    maxWidth = constraints.maxHeight,
                    minHeight = constraints.minWidth,
                    maxHeight = constraints.maxWidth,
                )

            val placeable = measurable.measure(swappedConstraints)
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val rotatedWidth = placeable.height
            val rotatedHeight = placeable.width

            val x = ((width - rotatedWidth) / 2).coerceAtLeast(0)
            val y = ((height - rotatedHeight) / 2).coerceAtLeast(0)

            layout(width, height) {
                placeable.placeWithLayer(x, y) {
                    transformOrigin = TransformOrigin(0f, 0f)
                    rotationZ = 90f
                    translationX = placeable.height.toFloat()
                }
            }
        }
    }
}

private fun Modifier.littlePlayerOverlayGestures(
    seekEnabled: Boolean,
    durationMs: Long,
    progressFraction: Float,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    onSeekToPositionMs: (Long) -> Unit,
    onSeekFinished: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
): Modifier =
    composed {
        val view = LocalView.current
        val (enableHapticFeedback) = rememberPreference(EnableHapticFeedbackKey, true)

        pointerInput(seekEnabled, durationMs, canSkipPrevious, canSkipNext) {
            var lastTapUptimeMs = 0L
            var lastTapPosition: Offset? = null
            val doubleTapTimeoutMs = viewConfiguration.doubleTapTimeoutMillis.toLong()
            val touchSlop = viewConfiguration.touchSlop

            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = true)
                val pointerId = down.id

                var upPosition = down.position
                val minOverlayHeightPx = 24.dp.toPx()
                val overlayHeightPx =
                    (progressFraction * size.height).coerceAtLeast(minOverlayHeightPx)
                val seekAllowedFromDown =
                    seekEnabled &&
                        durationMs > 0L &&
                        durationMs != C.TIME_UNSET &&
                        down.position.y <= overlayHeightPx

                var isSeeking = false

                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val change = event.changes.firstOrNull { it.id == pointerId } ?: continue
                    upPosition = change.position

                    if (!change.pressed) break

                    if (!isSeeking && seekAllowedFromDown) {
                        val distanceFromDown = (change.position - down.position).getDistance()
                        if (distanceFromDown > touchSlop) isSeeking = true
                    }

                    if (isSeeking) {
                        val fraction =
                            if (size.height > 0) (change.position.y / size.height.toFloat()) else 0f
                        val clampedFraction = fraction.coerceIn(0f, 1f)

                        val targetMs =
                            (durationMs.toDouble() * clampedFraction.toDouble()).roundToLong().coerceIn(0L, durationMs)
                        onSeekToPositionMs(targetMs)
                        change.consume()
                    }
                }

                if (isSeeking) {
                    onSeekFinished()
                    lastTapUptimeMs = 0L
                    lastTapPosition = null
                } else {
                    val now = SystemClock.uptimeMillis()
                    val previousTapPosition = lastTapPosition
                    val isDoubleTap =
                        previousTapPosition != null &&
                            (now - lastTapUptimeMs) <= doubleTapTimeoutMs &&
                            (upPosition - previousTapPosition).getDistance() <= (touchSlop * 2f)

                    if (isDoubleTap) {
                        val isTopSide = upPosition.y < size.height / 2f
                        if (isTopSide) {
                            if (canSkipPrevious) {
                                if (enableHapticFeedback) {
                                    view.performHapticFeedback(
                                        android.view.HapticFeedbackConstants.CONTEXT_CLICK,
                                        android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
                                    )
                                }
                                onSkipPrevious()
                            }
                        } else {
                            if (canSkipNext) {
                                if (enableHapticFeedback) {
                                    view.performHapticFeedback(
                                        android.view.HapticFeedbackConstants.CONTEXT_CLICK,
                                        android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
                                    )
                                }
                                onSkipNext()
                            }
                        }
                        lastTapUptimeMs = 0L
                        lastTapPosition = null
                    } else {
                        lastTapUptimeMs = now
                        lastTapPosition = upPosition
                    }
                }
            }
        }
    }

/** The Immersive style's view of a resolved canvas. Null when there is no loop to play. */
private fun CanvasArtwork.toImmersiveCanvasVideo(): CanvasVideo? {
    if (preferredVerticalAnimationUrl.isNullOrBlank() && preferredAnimationUrl.isNullOrBlank()) return null
    return CanvasVideo(
        // The style never reads the provider; canary's canvas player picks its own proxy.
        source = source ?: CanvasSource.ALL,
        static = static,
        animated = animated,
        videoUrl = videoUrl,
        animatedVertical = animatedVertical,
        videoUrlVertical = videoUrlVertical,
    )
}
