/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune

import android.Manifest
import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.provider.Settings
import android.speech.RecognizerIntent
import android.util.Rational
import android.view.View
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.datastore.preferences.core.edit
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata.MEDIA_TYPE_MUSIC
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.window.core.layout.WindowSizeClass
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.valentinilk.shimmer.LocalShimmerTheme
import dagger.hilt.android.AndroidEntryPoint
import dev.chrisbanes.haze.HazeState
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.aod.ACTION_AOD_MODE
import moe.rukamori.archivetune.constants.AodAutoOnScreenDimKey
import moe.rukamori.archivetune.constants.AodAutoTimerSecondsKey
import moe.rukamori.archivetune.constants.AppBarHeight
import moe.rukamori.archivetune.constants.AppFontPreference
import moe.rukamori.archivetune.constants.AppLanguageKey
import moe.rukamori.archivetune.constants.CustomFontUriKey
import moe.rukamori.archivetune.constants.CustomThemeColorKey
import moe.rukamori.archivetune.constants.DarkModeKey
import moe.rukamori.archivetune.constants.DefaultOpenTabKey
import moe.rukamori.archivetune.constants.DefaultSearchSourceKey
import moe.rukamori.archivetune.constants.DisableAnimationsKey
import moe.rukamori.archivetune.constants.DisableScreenshotKey
import moe.rukamori.archivetune.constants.DynamicThemeKey
import moe.rukamori.archivetune.constants.EnableHapticFeedbackKey
import moe.rukamori.archivetune.constants.EnablePipModeKey
import moe.rukamori.archivetune.constants.EnableVideoPlaybackKey
import moe.rukamori.archivetune.constants.FontPreferenceKey
import moe.rukamori.archivetune.constants.HasPressedStarKey
import moe.rukamori.archivetune.constants.HideStatusBarKey
import moe.rukamori.archivetune.constants.LastShownUpdatePopupVersionKey
import moe.rukamori.archivetune.constants.LaunchCountKey
import moe.rukamori.archivetune.constants.LiquidGlassEnabledKey
import moe.rukamori.archivetune.constants.LiquidGlassNavBarEnabledKey
import moe.rukamori.archivetune.constants.MiniPlayerBackgroundStyle
import moe.rukamori.archivetune.constants.MiniPlayerBackgroundStyleKey
import moe.rukamori.archivetune.constants.MiniPlayerBottomSpacing
import moe.rukamori.archivetune.constants.MiniPlayerHeight
import moe.rukamori.archivetune.constants.MiniPlayerLastAnchorKey
import moe.rukamori.archivetune.constants.NavigationBarAnimationSpec
import moe.rukamori.archivetune.constants.NavigationBarFrostedBlurKey
import moe.rukamori.archivetune.constants.NavigationBarHeight
import moe.rukamori.archivetune.constants.NavigationBarStyle
import moe.rukamori.archivetune.constants.NavigationBarStyleKey
import moe.rukamori.archivetune.constants.NavigationBarHideOnScrollKey
import moe.rukamori.archivetune.constants.NavigationBarTintFrostedBlurKey
import moe.rukamori.archivetune.constants.NeverShowUpdatePopupKey
import moe.rukamori.archivetune.constants.PauseSearchHistoryKey
import moe.rukamori.archivetune.constants.PersistentUpdatePopupKey
import moe.rukamori.archivetune.constants.PlayerBackgroundStyle
import moe.rukamori.archivetune.constants.PlayerBackgroundStyleKey
import moe.rukamori.archivetune.constants.PlayerDesignStyle
import moe.rukamori.archivetune.constants.PlayerDesignStyleKey
import moe.rukamori.archivetune.constants.PureBlackKey
import moe.rukamori.archivetune.constants.RemindAfterKey
import moe.rukamori.archivetune.constants.SYSTEM_DEFAULT
import moe.rukamori.archivetune.constants.SearchProvider
import moe.rukamori.archivetune.constants.SearchSource
import moe.rukamori.archivetune.constants.SearchSourceKey
import moe.rukamori.archivetune.constants.SplashOverlayEnabledKey
import moe.rukamori.archivetune.constants.StopMusicOnTaskClearKey
import moe.rukamori.archivetune.constants.TabletModeEnabledKey
import moe.rukamori.archivetune.constants.UiScaleFactorKey
import moe.rukamori.archivetune.constants.UpdateChannel
import moe.rukamori.archivetune.constants.UpdateChannelKey
import moe.rukamori.archivetune.constants.UseSystemFontKey
import moe.rukamori.archivetune.constants.WallpaperExtractionFailedKey
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.SearchHistory
import moe.rukamori.archivetune.db.entities.Song
import moe.rukamori.archivetune.extensions.toMediaItem
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.models.toMediaMetadata
import moe.rukamori.archivetune.musicrecognition.ACTION_MUSIC_RECOGNITION
import moe.rukamori.archivetune.musicrecognition.MusicRecognitionRoute
import moe.rukamori.archivetune.musicrecognition.openMusicRecognition
import moe.rukamori.archivetune.onboarding.OnboardingScreenState
import moe.rukamori.archivetune.onboarding.OnboardingViewModel
import moe.rukamori.archivetune.playback.DownloadUtil
import moe.rukamori.archivetune.playback.MusicService
import moe.rukamori.archivetune.playback.MusicService.MusicBinder
import moe.rukamori.archivetune.playback.PlayerConnection
import moe.rukamori.archivetune.playback.queues.ListQueue
import moe.rukamori.archivetune.playback.queues.Queue
import moe.rukamori.archivetune.playback.queues.YouTubeQueue
import moe.rukamori.archivetune.qobuz.QobuzAudioProvider
import moe.rukamori.archivetune.systemAnimationScale
import moe.rukamori.archivetune.ui.component.AutoResizeText
import moe.rukamori.archivetune.ui.component.BottomSheetMenu
import moe.rukamori.archivetune.ui.component.BottomSheetPage
import moe.rukamori.archivetune.ui.component.COLLAPSED_ANCHOR
import moe.rukamori.archivetune.ui.component.DISMISSED_ANCHOR
import moe.rukamori.archivetune.ui.component.EXPANDED_ANCHOR
import moe.rukamori.archivetune.ui.component.FloatingNavigationToolbar
import moe.rukamori.archivetune.ui.component.FontSizeRange
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.LocalBottomSheetPageState
import moe.rukamori.archivetune.ui.component.LocalLiquidGlassBackdrop
import moe.rukamori.archivetune.ui.component.LocalMenuState
import moe.rukamori.archivetune.ui.component.LocalNavigationBarHiddenByScroll
import moe.rukamori.archivetune.ui.component.LocalNavigationBarBackdrop
import moe.rukamori.archivetune.ui.component.MarkdownText
import moe.rukamori.archivetune.ui.component.NavigationBarBackdrop
import moe.rukamori.archivetune.ui.component.NetworkStatusBanner
import moe.rukamori.archivetune.ui.component.ProfileMenuDialog
import moe.rukamori.archivetune.ui.component.ProfileMenuItem
import moe.rukamori.archivetune.ui.component.SearchSourcePicker
import moe.rukamori.archivetune.ui.component.StarDialog
import moe.rukamori.archivetune.ui.component.TopSearch
import moe.rukamori.archivetune.ui.component.TvNavigationRail
import moe.rukamori.archivetune.ui.component.pillBottomInset
import moe.rukamori.archivetune.ui.component.pillHorizontalInset
import moe.rukamori.archivetune.ui.component.rememberAppleMusicExperience
import moe.rukamori.archivetune.ui.component.rememberBottomSheetState
import moe.rukamori.archivetune.ui.component.shimmer.ShimmerTheme
import moe.rukamori.archivetune.ui.component.splash.SplashConfig
import moe.rukamori.archivetune.ui.component.splash.SplashOverlay
import moe.rukamori.archivetune.ui.component.splash.SplashSlots
import moe.rukamori.archivetune.ui.component.splash.SplashVectorLoader
import moe.rukamori.archivetune.ui.player.BottomSheetPlayer
import moe.rukamori.archivetune.ui.player.ProvideVideoFullscreenState
import moe.rukamori.archivetune.ui.screens.HomeSourceToggleButton
import moe.rukamori.archivetune.ui.screens.HomeTopFadeBlur
import moe.rukamori.archivetune.ui.screens.LOGIN_URL_ARGUMENT
import moe.rukamori.archivetune.ui.screens.LocalHomeHazeState
import moe.rukamori.archivetune.ui.screens.LoginScreen
import moe.rukamori.archivetune.ui.screens.Screens
import moe.rukamori.archivetune.ui.screens.buildLoginRoute
import moe.rukamori.archivetune.ui.screens.navigationBuilder
import moe.rukamori.archivetune.ui.screens.onboarding.OnboardingRoute
import moe.rukamori.archivetune.ui.screens.search.LocalSearchScreen
import moe.rukamori.archivetune.ui.screens.search.OnlineSearchResultArgument
import moe.rukamori.archivetune.ui.screens.search.OnlineSearchResultRoutePrefix
import moe.rukamori.archivetune.ui.screens.search.OnlineSearchScreen
import moe.rukamori.archivetune.ui.screens.search.decodeOnlineSearchQuery
import moe.rukamori.archivetune.ui.screens.search.onlineSearchResultRoute
import moe.rukamori.archivetune.ui.screens.settings.DarkMode
import moe.rukamori.archivetune.ui.screens.settings.NavigationTab
import moe.rukamori.archivetune.ui.theme.ArchiveTuneTheme
import moe.rukamori.archivetune.ui.theme.ColorSaver
import moe.rukamori.archivetune.ui.theme.DefaultThemeColor
import moe.rukamori.archivetune.ui.theme.ExpressiveThemeColor
import moe.rukamori.archivetune.ui.theme.PlayerColorExtractor
import moe.rukamori.archivetune.ui.theme.extractThemeColor
import moe.rukamori.archivetune.ui.theme.extractWallpaperThemeColor
import moe.rukamori.archivetune.ui.utils.appBarScrollBehavior
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.ui.utils.resetHeightOffset
import moe.rukamori.archivetune.utils.PoolAccountManager
import moe.rukamori.archivetune.utils.PreferenceStore
import moe.rukamori.archivetune.utils.SyncUtils
import moe.rukamori.archivetune.utils.Updater
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get
import moe.rukamori.archivetune.LocalListenTogetherManager
import moe.rukamori.archivetune.listentogether.ListenTogetherManager
import moe.rukamori.archivetune.utils.isLowRamDevice
import moe.rukamori.archivetune.utils.isLocalMediaId
import moe.rukamori.archivetune.utils.isLowRamDevice
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.utils.reportException
import moe.rukamori.archivetune.utils.setAppLocale
import moe.rukamori.archivetune.viewmodels.BackupCategory
import moe.rukamori.archivetune.viewmodels.BackupRestoreViewModel
import moe.rukamori.archivetune.viewmodels.HomeViewModel
import moe.rukamori.archivetune.viewmodels.NetworkBannerViewModel
import moe.rukamori.archivetune.viewmodels.NewsViewModel
import moe.rukamori.archivetune.viewmodels.OnlineSearchSort
import moe.rukamori.archivetune.voicesearch.VoiceSearchControllerLocator
import moe.rukamori.archivetune.voicesearch.VoiceSearchState

@Suppress("DEPRECATION", "ASSIGNED_BUT_NEVER_ACCESSED_VARIABLE")
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var database: MusicDatabase

    @Inject
    lateinit var downloadUtil: DownloadUtil

    @Inject
    lateinit var syncUtils: SyncUtils

    @Inject
    lateinit var listenTogetherManager: ListenTogetherManager

    private lateinit var navController: NavHostController
    private var pendingIntent: Intent? = null
    private var pendingDeepLinkQueue: Queue? = null
    private var pendingVoiceSearchQuery: String? = null
    private var pendingAodModeRequest = false
    private var pendingAodModeJob: Job? = null
    private var aodModeLaunchRequestCount by mutableIntStateOf(0)
    private var pendingBackupRestoreUri by mutableStateOf<Uri?>(null)
    private var latestVersionName by mutableStateOf(BuildConfig.VERSION_NAME)
    private var latestUpdateChannel by mutableStateOf(defaultUpdateChannel)

    private var playerConnection by mutableStateOf<PlayerConnection?>(null)
    private var isMusicServiceBound = false
    private var immersiveStatusBarsHidden = false

    // Held by [onCreate]'s splash screen until the composition knows what the launch is going to
    // draw — the onboarding state resolved and the preference store read. Releasing earlier is
    // what put a bare window (and with it a second logo) between the system splash and the
    // opening animation.
    private var isReady by mutableStateOf(false)

    private val serviceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                service: IBinder?,
            ) {
                isMusicServiceBound = true
                if (service is MusicBinder) {
                    playerConnection =
                        PlayerConnection(this@MainActivity, service, database, lifecycleScope)
                    // Connect the Listen Together manager to the player
                    listenTogetherManager.setPlayerConnection(playerConnection)
                    playPendingDeepLinkQueueIfReady()
                    playPendingVoiceSearchIfReady()
                    openPendingAodModeIfReady()
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                isMusicServiceBound = false
                listenTogetherManager.setPlayerConnection(null)
                disposePlayerConnection()
            }
        }

    private fun playPendingDeepLinkQueueIfReady() {
        val pending = pendingDeepLinkQueue ?: return
        val connection = playerConnection ?: return
        pendingDeepLinkQueue = null
        connection.playQueue(pending)
    }

    private fun playPendingVoiceSearchIfReady() {
        val query = pendingVoiceSearchQuery ?: return
        val connection = playerConnection ?: return
        pendingVoiceSearchQuery = null
        connection.playFromVoiceSearch(query)
    }

    private fun requestAodMode() {
        pendingAodModeRequest = true
        startMusicServiceSafely()
        openPendingAodModeIfReady()
    }

    private fun openPendingAodModeIfReady() {
        if (!pendingAodModeRequest) return
        val connection = playerConnection ?: return
        pendingAodModeRequest = false
        pendingAodModeJob?.cancel()
        pendingAodModeJob =
            lifecycleScope.launch {
                connection.queueRestoreCompleted.first { it }
                if (awaitRestorablePlayback(connection)) {
                    aodModeLaunchRequestCount++
                }
            }
    }

    private suspend fun awaitRestorablePlayback(connection: PlayerConnection): Boolean {
        repeat(15) {
            if (
                connection.player.currentMediaItem != null ||
                connection.player.mediaItemCount > 0 ||
                connection.mediaMetadata.value != null
            ) {
                return true
            }
            delay(100)
        }

        return (
            connection.player.currentMediaItem != null ||
                connection.player.mediaItemCount > 0 ||
                connection.mediaMetadata.value != null
        )
    }

    override fun onStart() {
        super.onStart()
        isMusicServiceBound =
            bindService(
                Intent(this, MusicService::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE,
            )
        playPendingDeepLinkQueueIfReady()
        openPendingAodModeIfReady()

        // Clear the transient failure cache and instance cooldowns on app foreground so Qobuz is
        // retried without requiring a force-stop.
        QobuzAudioProvider.clearTransientCaches()
        playerConnection?.service?.let { service ->
            val currentMediaId = playerConnection?.mediaMetadata?.value?.id
            service.clearResolvedSources(currentMediaId)
        }

        // Refresh pool accounts in the background (non-forced — respects
        // the 30-min throttle). This ensures Qobuz tokens are fresh when
        // the user returns to the app, without hammering the pool API.
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { PoolAccountManager.refresh(this@MainActivity, force = false) }
        }
    }

    /** Drops the current [PlayerConnection]. Safe to call repeatedly. */
    private fun disposePlayerConnection() {
        pendingAodModeJob?.cancel()
        pendingAodModeJob = null
        playerConnection?.dispose()
        playerConnection = null
    }

    private fun safeUnbindMusicService() {
        if (!isMusicServiceBound) return
        try {
            unbindService(serviceConnection)
        } catch (e: IllegalArgumentException) {
        } catch (e: Exception) {
            reportException(e)
        } finally {
            isMusicServiceBound = false
        }
        disposePlayerConnection()
    }

    override fun onStop() {
        safeUnbindMusicService()
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()

        val shouldStopOnTaskClear =
            if (!isFinishing) {
                false
            } else {
                dataStore.get(StopMusicOnTaskClearKey, false)
            }

        if (shouldStopOnTaskClear) {
            playerConnection?.service?.stopAndClearPlayback(clearPersistentState = true)
            safeUnbindMusicService()
            stopService(Intent(this, MusicService::class.java))
        }
        // onStop's unbind already disposed; safety net for any path that
        // reaches destruction with a live connection.
        disposePlayerConnection()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && immersiveStatusBarsHidden) {
            setStatusBarsHidden(true)
        }
    }

    /** Tracks whether the activity is currently in PiP mode, exposed as
     *  Compose state so the player UI can adapt (hide controls, etc.). */
    var isInPictureInPictureModeState by mutableStateOf(false)
        private set

    /**
     * Returns true if the current playback session is eligible for PiP:
     * the setting is on, video playback is on, and the current media is a
     * non-local music video that is actively playing.
     *
     * Called from [onUserLeaveHint] (and could be called from anywhere
     * else that needs to know). Safe to call from the main thread.
     */
    private fun isPipEligible(): Boolean {
        // PiP requires API 26+ (O). The app's minSdk is 26, so this check
        // is always true — kept for defensive clarity.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false
        val pipEnabled = dataStore.get(EnablePipModeKey, false)
        if (!pipEnabled) return false
        val videoPlaybackEnabled = dataStore.get(EnableVideoPlaybackKey, false)
        if (!videoPlaybackEnabled) return false
        val connection = playerConnection ?: return false
        val metadata = connection.mediaMetadata.value ?: return false
        if (metadata.isMusicVideo != true) return false
        if (metadata.id.isLocalMediaId()) return false
        // Only enter PiP if playback is actually playing — entering PiP
        // for a paused video would show a frozen frame.
        if (!connection.isPlaying.value) return false
        return true
    }

    /** Computes the [PictureInPictureParams] for the current playback. */
    private fun buildPipParams(): PictureInPictureParams =
        PictureInPictureParams
            .Builder()
            .setAspectRatio(Rational(16, 9))
            .build()

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isPipEligible()) {
            try {
                enterPictureInPictureMode(buildPipParams())
            } catch (e: IllegalStateException) {
                // Some OEMs throw ISE if PiP is attempted while the
                // activity isn't in a valid state. Swallow and continue —
                // the user simply won't get PiP this time.
                reportException(e)
            } catch (e: Exception) {
                reportException(e)
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPictureInPictureModeState = isInPictureInPictureMode
    }
    // ── End Picture-in-Picture ────────────────────────────────────────────

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (::navController.isInitialized) {
            handleIntent(intent, navController)
        } else {
            pendingIntent = intent
        }
    }

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate, and before any content is set: on API 31+ the launcher
        // icon then stays on @color/splash_window_background while the app prepares, and below 31
        // the launch window's adaptive colour is held instead, so the first thing the reader sees
        // after it is this launch's opening animation rather than a bare window. This is the
        // library db79d42ac dropped as broken: it returns because that attempt's theme switch and
        // unbounded hold are both gone — the base theme is kept, no exit listener is registered,
        // and the hold is capped at two seconds by the release effect inside setContent.
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition { !isReady }
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_LTR
        WindowCompat.setDecorFitsSystemWindows(window, false)

        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { downloadUtil.prewarmDownloadConnections() }
        }

        // Listen Together: restores a persisted room session (reconnect flow)
        // and wires preference observers before any screen renders.
        runCatching { listenTogetherManager.initialize() }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val initialLocale =
                PreferenceStore
                    .get(AppLanguageKey)
                    ?.takeUnless { it == SYSTEM_DEFAULT }
                    ?.let { Locale.forLanguageTag(it) }
                    ?: Locale.getDefault()
            setAppLocale(this, initialLocale)

            lifecycleScope.launch(Dispatchers.IO) {
                runCatching {
                    dataStore.data.first()[AppLanguageKey]
                }.onSuccess { lang ->
                    val targetLocale =
                        lang
                            ?.takeUnless { it == SYSTEM_DEFAULT }
                            ?.let { Locale.forLanguageTag(it) }
                            ?: Locale.getDefault()
                    if (targetLocale != initialLocale) {
                        withContext(Dispatchers.Main) {
                            setAppLocale(this@MainActivity, targetLocale)
                            recreate()
                        }
                    }
                }
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { it[DisableScreenshotKey] ?: false }
                .distinctUntilChanged()
                .collectLatest {
                    withContext(Dispatchers.Main) {
                        if (it) {
                            window.setFlags(
                                WindowManager.LayoutParams.FLAG_SECURE,
                                WindowManager.LayoutParams.FLAG_SECURE,
                            )
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        }
                    }
                }
        }

        // The opening animation draws the app icon as a particle formation, so publish its
        // outline up front: SplashSlots holds a plain static that is read on the first frame,
        // and the overlay rebuilds its slots the moment [SplashSlots.vectorVersion] moves if
        // this lands a frame or two late. Parsing the vector is cheap but not free, hence off
        // the main thread.
        lifecycleScope.launch(Dispatchers.Default) {
            val path = SplashVectorLoader.loadPath(this@MainActivity, R.drawable.about_splash)
            withContext(Dispatchers.Main) {
                SplashSlots.customVectorPath = path
                SplashSlots.vectorVersion++
            }
        }

        setContent {
            val updateChannel by rememberEnumPreference(UpdateChannelKey, defaultValue = defaultUpdateChannel)

            val effectiveUpdateChannel =
                when {
                    isCanaryBuild -> UpdateChannel.CANARY
                    isNightlyBuild -> UpdateChannel.NIGHTLY
                    else -> updateChannel
                }

            LaunchedEffect(Unit) {
                while (playerConnection == null) {
                    delay(100)
                }
                delay(500)

                if (
                    BuildConfig.UPDATER_AVAILABLE &&
                    System.currentTimeMillis() - Updater.lastCheckTime > 1.days.inWholeMilliseconds
                ) {
                    val channelString = withContext(Dispatchers.IO) { dataStore.data.first()[UpdateChannelKey] }
                    val userSelectedChannel = UpdateChannel.fromStoredName(channelString, defaultUpdateChannel)
                    // Lock canary builds to canary updates regardless of the user's
                    // selection — see the comment on effectiveUpdateChannel above.
                    val actualChannel =
                        when {
                            isCanaryBuild -> UpdateChannel.CANARY
                            isNightlyBuild -> UpdateChannel.NIGHTLY
                            else -> userSelectedChannel
                        }
                    val versionResult =
                        when (actualChannel) {
                            UpdateChannel.CANARY -> Updater.getLatestCanaryVersionName()
                            UpdateChannel.NIGHTLY -> Updater.getLatestNightlyVersionName()
                            UpdateChannel.STABLE -> Updater.getLatestVersionName()
                        }
                    versionResult.onSuccess {
                        if (Updater.isUpdateAvailable(it, BuildConfig.VERSION_NAME)) {
                            latestUpdateChannel = actualChannel
                            latestVersionName = it
                        }
                    }
                }
                moe.rukamori.archivetune.utils.UpdateNotificationManager
                    .checkForUpdates(this@MainActivity)
            }

            // Use remembered instances so the same state object is used everywhere: retrieving the
            // composition local directly creates a different instance per composition scope, which
            // stops the update bottom sheet from appearing and blocks overlay interactions.
            val bottomSheetPageState =
                remember {
                    moe.rukamori.archivetune.ui.component
                        .BottomSheetPageState()
                }
            val menuState =
                remember {
                    moe.rukamori.archivetune.ui.component
                        .MenuState()
                }
            // Shared Haze state for the Home route's progressive top-fade blur. HomeScreen tags
            // its root Box as the haze source; the top bar renders the blurred strip (see the
            // topBar slot). Provided to the tree via LocalHomeHazeState.
            val homeHazeState = remember { HazeState() }
            val releaseNotesState = remember { mutableStateOf<String?>(null) }
            val currentVersionMarker = remember {
                "${BuildConfig.VERSION_NAME}|${BuildConfig.VERSION_CODE}"
            }
            val (neverShowUpdatePopupMarker, onNeverShowUpdatePopupMarkerChange) =
                rememberPreference(NeverShowUpdatePopupKey, defaultValue = "")
            val neverShowUpdatePopup = neverShowUpdatePopupMarker == currentVersionMarker
            val (lastShownUpdatePopupVersion, onLastShownUpdatePopupVersionChange) =
                rememberPreference(LastShownUpdatePopupVersionKey, defaultValue = "")
            val persistentUpdatePopup by rememberPreference(PersistentUpdatePopupKey, defaultValue = false)
            val updateSheetContent: @Composable ColumnScope.() -> Unit = {
                // receiver: ColumnScope
                Text(
                    text = stringResource(R.string.new_update_available),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(top = 16.dp),
                )

                Spacer(Modifier.height(8.dp))

                androidx.compose.material3.OutlinedButton(
                    onClick = {},
                    contentPadding =
                        androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 5.dp,
                            vertical = 5.dp,
                        ),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(text = latestVersionName, style = MaterialTheme.typography.labelLarge)
                }

                Spacer(Modifier.height(12.dp))

                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                ) {
                    val notes = releaseNotesState.value
                    if (notes != null && notes.isNotBlank()) {
                        MarkdownText(
                            markdown = notes,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(end = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.release_notes_unavailable),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                androidx.compose.material3.Button(
                    onClick = {
                        bottomSheetPageState.dismiss()
                        this@MainActivity.navController.navigate("settings/update") {
                            launchSingleTop = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(text = stringResource(R.string.update_text))
                }

                Spacer(Modifier.height(8.dp))

                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    androidx.compose.material3.OutlinedButton(
                        onClick = { bottomSheetPageState.dismiss() },
                        modifier = Modifier.weight(1f),
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(text = stringResource(R.string.update_remind_later))
                    }
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            onNeverShowUpdatePopupMarkerChange(currentVersionMarker)
                            bottomSheetPageState.dismiss()
                        },
                        modifier = Modifier.weight(1f),
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(text = stringResource(R.string.update_never_show_again))
                    }
                }
            }

            // Show the sheet once per offered release unless the reader asked for the reminder.
            // Without the lastShown gate the effect re-fires on every process start and re-opens
            // the same sheet for a release already dismissed, which is a nag rather than a notice —
            // a reader who tracks a pre-release channel sees it on every launch, and a stable
            // reader pointed at one never converges to it at all.
            LaunchedEffect(
                latestVersionName,
                latestUpdateChannel,
                effectiveUpdateChannel,
                neverShowUpdatePopup,
                lastShownUpdatePopupVersion,
                persistentUpdatePopup,
            ) {
                if (
                    BuildConfig.UPDATER_AVAILABLE &&
                    !neverShowUpdatePopup &&
                    (persistentUpdatePopup || lastShownUpdatePopupVersion != latestVersionName) &&
                    latestUpdateChannel == effectiveUpdateChannel &&
                    Updater.isUpdateAvailable(latestVersionName, BuildConfig.VERSION_NAME)
                ) {
                    val releaseNotesResult =
                        when (latestUpdateChannel) {
                            UpdateChannel.CANARY -> Updater.getLatestCanaryReleaseNotes()
                            UpdateChannel.NIGHTLY -> Updater.getLatestNightlyReleaseNotes()
                            UpdateChannel.STABLE -> Updater.getLatestReleaseNotes()
                        }
                    releaseNotesResult
                        .onSuccess {
                            releaseNotesState.value = it
                        }.onFailure {
                            releaseNotesState.value = null
                        }

                    onLastShownUpdatePopupVersionChange(latestVersionName)
                    bottomSheetPageState.show(updateSheetContent)
                }
            }

            val enableDynamicTheme by rememberPreference(DynamicThemeKey, defaultValue = true)
            val customThemeColorValue by rememberPreference(CustomThemeColorKey, defaultValue = "default")
            val darkTheme by rememberEnumPreference(DarkModeKey, defaultValue = DarkMode.AUTO)
            val defaultDisableAnimations = remember(this@MainActivity) { applicationContext.isLowRamDevice() }
            // Developer options can halve or disable every animation; the springs need to know.
            val systemAnimationScale =
                remember(this@MainActivity) { applicationContext.systemAnimationScale() }
            val disableAnimations by rememberPreference(
                DisableAnimationsKey,
                defaultValue = defaultDisableAnimations,
            )
            // Task 8: app-wide scrollbar toggle. Provided via LocalHideScrollbar so any
            // composable that draws a scrollbar can opt-out when the user has hidden them.
            val hideScrollbar by rememberPreference(
                moe.rukamori.archivetune.constants.HideScrollbarKey,
                defaultValue = false,
            )
            val fontPreference by rememberEnumPreference(FontPreferenceKey, defaultValue = AppFontPreference.DEFAULT)
            val customFontUri by rememberPreference(CustomFontUriKey, defaultValue = "")
            val legacyUseSystemFont by rememberPreference(UseSystemFontKey, defaultValue = false)
            val isSystemInDarkTheme = isSystemInDarkTheme()
            val useDarkTheme =
                remember(darkTheme, isSystemInDarkTheme) {
                    if (darkTheme == DarkMode.AUTO) isSystemInDarkTheme else darkTheme == DarkMode.ON
                }
            val pureBlackEnabled by rememberPreference(PureBlackKey, defaultValue = false)
            val pureBlack = pureBlackEnabled && useDarkTheme
            val hideStatusBar by rememberPreference(HideStatusBarKey, defaultValue = false)
            val navigationBarStyleStored by rememberEnumPreference(
                NavigationBarStyleKey,
                defaultValue = NavigationBarStyle.DEFAULT,
            )
            // The Apple Music Experience carries the tab bar with it, the same way it carries the
            // iOS page headers. Written as a read-side override so turning the switch back off
            // restores whichever bar the user had chosen instead of overwriting that choice.
            val navigationBarStyle =
                if (rememberAppleMusicExperience()) {
                    NavigationBarStyle.APPLE_MUSIC
                } else {
                    navigationBarStyleStored
                }
            val navigationBarFrostedBlur by rememberPreference(
                NavigationBarFrostedBlurKey,
                defaultValue = false,
            )
            val navigationBarTintFrostedBlur by rememberPreference(
                NavigationBarTintFrostedBlurKey,
                defaultValue = false,
            )
            val navigationBarBitchordEnabled by rememberPreference(
                moe.rukamori.archivetune.constants.NavigationBarBitchordKey,
                defaultValue = false,
            )
            val liquidGlassEnabled by rememberPreference(
                LiquidGlassEnabledKey,
                defaultValue = false,
            )
            val liquidGlassNavBarEnabled by rememberPreference(
                LiquidGlassNavBarEnabledKey,
                defaultValue = false,
            )

            val customThemeSeedPalette =
                remember(customThemeColorValue) {
                    if (customThemeColorValue.startsWith("#")) {
                        null
                    } else if (customThemeColorValue.startsWith("seedPalette:")) {
                        moe.rukamori.archivetune.ui.theme.ThemeSeedPaletteCodec
                            .decodeFromPreference(customThemeColorValue)
                    } else {
                        moe.rukamori.archivetune.ui.screens.settings.ThemePalettes
                            .findById(customThemeColorValue)
                            ?.let {
                                moe.rukamori.archivetune.ui.theme.ThemeSeedPalette(
                                    primary = it.primary,
                                    secondary = it.secondary,
                                    tertiary = it.tertiary,
                                    neutral = it.neutral,
                                )
                            }
                    }
                }

            val customThemeColor =
                remember(customThemeColorValue, customThemeSeedPalette) {
                    if (customThemeColorValue.startsWith("#")) {
                        try {
                            val colorString = customThemeColorValue.removePrefix("#")
                            Color(android.graphics.Color.parseColor("#$colorString"))
                        } catch (e: Exception) {
                            DefaultThemeColor
                        }
                    } else {
                        customThemeSeedPalette?.primary ?: DefaultThemeColor
                    }
                }

            var themeColor by rememberSaveable(stateSaver = ColorSaver) {
                mutableStateOf(DefaultThemeColor)
            }

            LaunchedEffect(legacyUseSystemFont) {
                if (!legacyUseSystemFont) return@LaunchedEffect
                val preferences = dataStore.data.first()
                if (preferences[FontPreferenceKey] == null) {
                    dataStore.edit { it[FontPreferenceKey] = AppFontPreference.SYSTEM.name }
                }
            }

            LaunchedEffect(playerConnection, enableDynamicTheme, isSystemInDarkTheme, customThemeColor) {
                val playerConnection = playerConnection
                if (!enableDynamicTheme || playerConnection == null) {
                    themeColor = if (!enableDynamicTheme) customThemeColor else DefaultThemeColor
                    return@LaunchedEffect
                }
                playerConnection.service.currentMediaMetadata.collectLatest { song ->
                    if (song != null) {
                        withContext(Dispatchers.Default) {
                            try {
                                val result =
                                    imageLoader.execute(
                                        ImageRequest
                                            .Builder(this@MainActivity)
                                            .data(song.thumbnailUrl)
                                            .allowHardware(false)
                                            // Dominant-color extraction needs a
                                            // thumbnail, not the full-res image —
                                            // without this every track change
                                            // decodes a multi-MB software bitmap.
                                            .size(
                                                PlayerColorExtractor.Config.IMAGE_SIZE,
                                                PlayerColorExtractor.Config.IMAGE_SIZE,
                                            )
                                            .build(),
                                    )
                                val extractedColor =
                                    (result as? SuccessResult)?.image?.toBitmap()?.extractThemeColor()
                                withContext(Dispatchers.Main) {
                                    themeColor = extractedColor ?: DefaultThemeColor
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    themeColor = DefaultThemeColor
                                }
                            }
                        }
                    } else {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            themeColor = DefaultThemeColor
                        } else {
                            val wallpaperColor = extractWallpaperThemeColor(this@MainActivity)
                            themeColor = wallpaperColor ?: customThemeColor
                            dataStore.edit { prefs ->
                                prefs[WallpaperExtractionFailedKey] = wallpaperColor == null
                            }
                        }
                    }
                }
            }

            ArchiveTuneTheme(
                darkTheme = useDarkTheme,
                pureBlack = pureBlack,
                themeColor = themeColor,
                defaultSeed = if (rememberAppleMusicExperience()) DefaultThemeColor else ExpressiveThemeColor,
                seedPalette = if (!enableDynamicTheme) customThemeSeedPalette else null,
                disableAnimations = disableAnimations,
                fontPreference = fontPreference,
                customFontUri = customFontUri,
            ) {
                val navController = rememberNavController()
                // Keep top-level list state outside destination content. MainActivity unbinds the
                // music service in onStop; HomeScreen otherwise disappears while the connection is
                // null and is recreated at offset zero when the app returns to the foreground.
                val homeListState = rememberLazyListState()
                val searchListState = rememberLazyListState()
                val onboardingViewModel: OnboardingViewModel = hiltViewModel()
                val onboardingState by onboardingViewModel.screenState.collectAsStateWithLifecycle()
                var showOnboardingLogin by rememberSaveable { mutableStateOf(false) }
                val shouldShowOnboarding =
                    when (val state = onboardingState) {
                        OnboardingScreenState.Loading -> true
                        OnboardingScreenState.Empty -> true
                        is OnboardingScreenState.Error -> false
                        is OnboardingScreenState.Success -> state.uiState.shouldShowOnboarding
                    }

                // Let the system splash go only when this launch knows what it is about to draw:
                // onboarding out of Loading and the preference store's first disk read landed, so
                // the curtain cannot lift on values the store has yet to load. It sits above the
                // branch below so a first run — which shows onboarding rather than the animation
                // — releases the splash too. The wait is bounded because this is now the only
                // thing between the splash screen and the app: a launch that never reports its
                // state must still not be able to strand the reader, the same reason the overlay
                // caps itself. Two seconds is far beyond an honest settle (both reads are local),
                // and the read's runCatching absorbs the cancellation this file would normally
                // rethrow — the release below is unconditional, so either path still runs it and
                // neither mechanism may be dropped as redundant. The hold vetoes drawing, not
                // composition, so this launch's opening animation is already burning its timers
                // while held: only its tail is guaranteed on screen once the curtain lifts.
                LaunchedEffect(Unit) {
                    withTimeoutOrNull(2_000L) {
                        snapshotFlow { onboardingState != OnboardingScreenState.Loading }.first { it }
                        runCatching { dataStore.data.first() }
                    }
                    isReady = true
                }

                if (shouldShowOnboarding) {
                    if (showOnboardingLogin) {
                        CompositionLocalProvider(
                            LocalPlayerAwareWindowInsets provides WindowInsets.systemBars,
                        ) {
                            LoginScreen(
                                navController = navController,
                                onLoginComplete = {
                                    onboardingViewModel.onLoginCompleted()
                                    showOnboardingLogin = false
                                },
                                onNavigateBack = { showOnboardingLogin = false },
                            )
                        }
                    } else {
                        OnboardingRoute(
                            viewModel = onboardingViewModel,
                            onLoginRequested = { showOnboardingLogin = true },
                        )
                    }
                    return@ArchiveTuneTheme
                }

                // Cold-launch opening animation. While it is due, the app content stays
                // transparent and rises into place once the burst fires; with the setting off, or
                // with animations disabled, every part of this collapses to "content is already
                // visible" and the overlay is never composed at all. Latched once per process —
                // an entry animation belongs to this launch, so flipping the switch mid-session
                // must not start one.
                val splashAnimationEnabled by rememberPreference(SplashOverlayEnabledKey, defaultValue = true)
                val coldSplash = remember { splashAnimationEnabled && !disableAnimations }
                var contentVisible by remember { mutableStateOf(!coldSplash) }
                // Set by the first tap on the opening animation. The overlay owns the exit (it
                // fades itself out), this only tells it that the reader has asked for the screen.
                var splashSkipped by remember { mutableStateOf(false) }
                LaunchedEffect(splashAnimationEnabled, disableAnimations) {
                    // The preference store seeds the first frame from its hot copy, but a value
                    // that only settles on a later read must never strand the UI invisible behind
                    // an overlay that has already decided not to play.
                    if (!splashAnimationEnabled || disableAnimations) contentVisible = true
                }
                val contentAlpha by animateFloatAsState(
                    targetValue = if (contentVisible) 1f else 0f,
                    animationSpec =
                        tween(
                            durationMillis = if (disableAnimations) 0 else SplashConfig.Reveal.DURATION_MS,
                            easing = EaseOut,
                        ),
                    label = "splashContentAlpha",
                )

                // App-open animation: the first time the main UI appears it fades in while
                // gently scaling up from 96%, so launching the app feels like a smooth reveal
                // rather than an abrupt cut. Runs once per process and honors "disable animations".
                // The splash overlay owns the entry while it is due, so this stands aside then:
                // its fade would otherwise be hidden behind the overlay and scale the particle
                // field with it.
                val appOpenProgress = remember { Animatable(if (disableAnimations || coldSplash) 1f else 0f) }
                LaunchedEffect(Unit) {
                    if (!disableAnimations && appOpenProgress.value < 1f) {
                        appOpenProgress.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
                        )
                    }
                }

                BoxWithConstraints(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = appOpenProgress.value
                                val scale = 0.96f + 0.04f * appOpenProgress.value
                                scaleX = scale
                                scaleY = scale
                            }
                            .background(
                                if (pureBlack) Color.Black else MaterialTheme.colorScheme.surface,
                            ),
                ) {
                    val focusManager = LocalFocusManager.current
                    val density = LocalDensity.current
                    val windowsInsets = WindowInsets.systemBars
                    val bottomInset = with(density) { windowsInsets.getBottom(density).toDp() }
                    val bottomInsetDp = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()

                    val isTvDevice = remember { applicationContext.isTvDevice() }
                    val (tabletModeEnabled) = rememberPreference(TabletModeEnabledKey, defaultValue = false)
                    val useRail =
                        isTvDevice ||
                            tabletModeEnabled ||
                            currentWindowAdaptiveInfo()
                                .windowSizeClass
                                .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)

                    // UI scale — applied via a LocalDensity override. It scales TEXT ONLY: only
                    // `fontScale` is touched, so `dp` sizes (icons, paddings, artwork) are
                    // unchanged and text grows or shrinks within the existing layout. Scaling
                    // `density` as well would make it genuinely DPI-like, but that would resize
                    // every screen for everyone already running a non-default value, so it stays
                    // text-only.
                    //
                    // Multiplied onto the system font scale rather than replacing it, so the
                    // device's own font-size setting still applies. The slider in Appearance
                    // Settings clamps to [0.85, 1.30]; we additionally guard here in case a
                    // backup-restore injects an out-of-range value.
                    val (uiScaleRaw) = rememberPreference(UiScaleFactorKey, defaultValue = 1.0f)
                    val uiScale = uiScaleRaw.coerceIn(0.85f, 1.30f)
                    val scaledDensity = remember(density, uiScale) {
                        Density(
                            density = density.density,
                            fontScale = density.fontScale * uiScale,
                        )
                    }

                    DisposableEffect(navController) {
                        this@MainActivity.navController = navController
                        onDispose {}
                    }
                    val coroutineScope = rememberCoroutineScope()
                    val homeViewModel: HomeViewModel = hiltViewModel()
                    val networkBannerViewModel: NetworkBannerViewModel = hiltViewModel()
                    val newsViewModel: NewsViewModel = hiltViewModel()
                    val accountImageUrl by homeViewModel.accountImageUrl.collectAsStateWithLifecycle()
                    val accountName by homeViewModel.accountName.collectAsStateWithLifecycle()
                    val networkBannerState by networkBannerViewModel.bannerState.collectAsStateWithLifecycle()
                    val hasUnreadNews by newsViewModel.hasUnreadNews.collectAsStateWithLifecycle()
                    // Listen Together top-bar entry gate (vivi's pattern).
                    val (listenTogetherInTopBar) =
                        rememberPreference(
                            moe.rukamori.archivetune.constants.ListenTogetherInTopBarKey,
                            defaultValue = true,
                        )
                    // Hoisted profile-menu state.
                    var profileMenuExpanded by rememberSaveable { mutableStateOf(false) }
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentRoute = navBackStackEntry?.destination?.route
                    val onlineSearchEncodedQuery =
                        navBackStackEntry
                            ?.takeIf {
                                it.destination.route?.startsWith(OnlineSearchResultRoutePrefix) == true
                            }?.arguments
                            ?.getString(OnlineSearchResultArgument)
                    var onlineSearchSort by rememberSaveable { mutableStateOf(OnlineSearchSort.DEFAULT) }
                    val isYearInMusicScreen = currentRoute?.startsWith("year_in_music") == true

                    val navigationItems =
                        remember(isTvDevice) {
                            if (isTvDevice) Screens.TvMainScreens else Screens.MainScreens
                        }
                    val (savedMiniPlayerAnchor, setSavedMiniPlayerAnchor) =
                        rememberPreference(
                            MiniPlayerLastAnchorKey,
                            defaultValue = COLLAPSED_ANCHOR,
                        )
                    val defaultOpenTab by rememberEnumPreference(DefaultOpenTabKey, NavigationTab.HOME)
                    val pauseSearchHistory by rememberPreference(PauseSearchHistoryKey, defaultValue = false)
                    val tabOpenedFromShortcut =
                        remember {
                            when (intent?.action) {
                                ACTION_LIBRARY -> NavigationTab.LIBRARY
                                ACTION_SEARCH -> NavigationTab.SEARCH
                                else -> null
                            }
                        }
                    val launchMusicRecognitionFromShortcut =
                        remember {
                            intent?.action == ACTION_MUSIC_RECOGNITION
                        }

                    val topLevelScreens =
                        remember(navigationItems) {
                            navigationItems.map(Screens::route) + "settings"
                        }

                    val (query, onQueryChange) =
                        rememberSaveable(stateSaver = TextFieldValue.Saver) {
                            mutableStateOf(TextFieldValue())
                        }

                    var active by rememberSaveable {
                        mutableStateOf(false)
                    }

                    val onActiveChange: (Boolean) -> Unit = { newActive ->
                        active = newActive
                        if (!newActive) {
                            focusManager.clearFocus()
                            if (navigationItems.fastAny { it.route == navBackStackEntry?.destination?.route }) {
                                onQueryChange(TextFieldValue())
                            }
                        }
                    }

                    var searchSource by rememberEnumPreference(SearchSourceKey, SearchSource.ONLINE)
                    var searchProvider by rememberEnumPreference(
                        DefaultSearchSourceKey,
                        SearchProvider.YOUTUBE,
                    )

                    val searchBarFocusRequester = remember { FocusRequester() }
                    val tvRailFocusRequester = remember { FocusRequester() }
                    val contentAreaFocusRequester = remember { FocusRequester() }

                    LaunchedEffect(onlineSearchEncodedQuery) {
                        onlineSearchSort = OnlineSearchSort.DEFAULT
                    }

                    val openSearch: () -> Unit = {
                        onActiveChange(true)
                        searchBarFocusRequester.requestFocus()
                    }

                    val onSearch: (String) -> Unit = {
                        if (it.isNotEmpty()) {
                            onActiveChange(false)
                            navController.navigate(onlineSearchResultRoute(it, searchProvider))
                            if (!pauseSearchHistory) {
                                database.query {
                                    insert(SearchHistory(query = it))
                                }
                            }
                        }
                    }

                    val selectSearchSource: (SearchSource, SearchProvider) -> Unit = { scope, provider ->
                        searchSource = scope
                        if (scope == SearchSource.ONLINE) {
                            searchProvider = provider
                        }
                        if (!active && currentRoute?.startsWith(OnlineSearchResultRoutePrefix) == true) {
                            val currentQuery = onlineSearchEncodedQuery?.let(::decodeOnlineSearchQuery).orEmpty()
                            if (scope == SearchSource.LOCAL) {
                                onQueryChange(TextFieldValue(currentQuery, TextRange(currentQuery.length)))
                                active = true
                            } else if (currentQuery.isNotBlank()) {
                                val replacementRoute = onlineSearchResultRoute(currentQuery, provider)
                                val currentDestinationId = navController.currentDestination?.id
                                if (currentDestinationId != null) {
                                    navController.navigate(replacementRoute) {
                                        popUpTo(currentDestinationId) { inclusive = true }
                                        launchSingleTop = true
                                    }
                                } else {
                                    navController.navigate(replacementRoute)
                                }
                            }
                        }
                    }

                    val createVoiceSearchIntent: () -> Intent = {
                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(
                                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                            )
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                        }
                    }
                    val voiceSearchLauncher =
                        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                            val voiceQuery =
                                result.data
                                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                                    ?.firstOrNull()
                                    ?.trim()
                                    .orEmpty()
                            if (voiceQuery.isNotEmpty()) {
                                onQueryChange(TextFieldValue(voiceQuery))
                                onSearch(voiceQuery)
                            }
                        }
                    val launchSpeechRecognition: () -> Unit = {
                        val speechIntent = createVoiceSearchIntent()
                        if (speechIntent.resolveActivity(packageManager) == null) {
                            android.widget.Toast
                                .makeText(
                                    this@MainActivity,
                                    R.string.voice_search_unavailable,
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                        } else {
                            try {
                                voiceSearchLauncher.launch(speechIntent)
                            } catch (exception: ActivityNotFoundException) {
                                reportException(exception)
                                android.widget.Toast
                                    .makeText(
                                        this@MainActivity,
                                        R.string.voice_search_unavailable,
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                            }
                        }
                    }
                    val microphonePermissionLauncher =
                        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                            if (granted) {
                                launchSpeechRecognition()
                            } else {
                                android.widget.Toast
                                    .makeText(
                                        this@MainActivity,
                                        R.string.voice_search_permission_denied,
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                            }
                        }
                    val launchVoiceSearch: () -> Unit = {
                        if (
                            ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.RECORD_AUDIO,
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            launchSpeechRecognition()
                        } else {
                            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }

                    var openSearchImmediately: Boolean by remember {
                        mutableStateOf(intent?.action == ACTION_SEARCH)
                    }

                    val shouldShowSearchBar =
                        remember(active, navBackStackEntry) {
                            active ||
                                navigationItems.fastAny { it.route == navBackStackEntry?.destination?.route } ||
                                navBackStackEntry?.destination?.route?.startsWith(OnlineSearchResultRoutePrefix) == true
                        }

                    val shouldShowNavigationBar =
                        remember(navBackStackEntry, active) {
                            navBackStackEntry?.destination?.route == null ||
                                navigationItems.fastAny { it.route == navBackStackEntry?.destination?.route } &&
                                !active
                        }

                    val hideNavigationBarOnScroll by rememberPreference(
                        NavigationBarHideOnScrollKey,
                        defaultValue = false,
                    )
                    var navigationBarHiddenByScroll by remember { mutableStateOf(false) }
                    LaunchedEffect(navBackStackEntry?.destination?.route, hideNavigationBarOnScroll) {
                        navigationBarHiddenByScroll = false
                    }
                    val navigationBarScrollThresholdPx = with(density) { NavigationBarHideScrollThreshold.toPx() }
                    val navigationBarScrollConnection =
                        remember(navigationBarScrollThresholdPx) {
                            object : NestedScrollConnection {
                                private var travelled = 0f

                                override fun onPostScroll(
                                    consumed: Offset,
                                    available: Offset,
                                    source: NestedScrollSource,
                                ): Offset {
                                    if (source != NestedScrollSource.UserInput || consumed.y == 0f) return Offset.Zero
                                    if (travelled != 0f && (travelled < 0f) != (consumed.y < 0f)) travelled = 0f
                                    travelled += consumed.y
                                    if (travelled <= -navigationBarScrollThresholdPx) {
                                        navigationBarHiddenByScroll = true
                                        travelled = 0f
                                    } else if (travelled >= navigationBarScrollThresholdPx) {
                                        navigationBarHiddenByScroll = false
                                        travelled = 0f
                                    }
                                    return Offset.Zero
                                }
                            }
                        }
                    val navigationBarVisible = shouldShowNavigationBar && !navigationBarHiddenByScroll

                    fun getBottomNavPadding(): Dp =
                        if (shouldShowNavigationBar && !useRail) {
                            NavigationBarHeight
                        } else {
                            0.dp
                        }

                    // FLOATING detaches the bar into a pill: bigger bottom margin, tighter width.
                    // Every consumer below (collapsed player anchor, slide distance, insets, FAB
                    // padding) derives from these two values so the styles stay in sync.
                    val isFloatingNavBar = navigationBarStyle == NavigationBarStyle.FLOATING
                    val floatingBarsBottomPadding = navigationBarStyle.pillBottomInset
                    // Task 6: respect the user's navigation bar height multiplier so the bottom
                    // sheet anchor and the rendered bar stay aligned.
                    val (navBarHeightMultiplier) = rememberPreference(
                        moe.rukamori.archivetune.constants.NavigationBarHeightKey,
                        defaultValue = moe.rukamori.archivetune.constants.NAVIGATION_BAR_HEIGHT_DEFAULT,
                    )
                    val navVisibleHeight = NavigationBarHeight * navBarHeightMultiplier
                    val navBarHorizontalPadding = navigationBarStyle.pillHorizontalInset

                    // Frosted backdrop (nav bar + mini player + tablet rail): allocated whenever
                    // any frosted surface can run (RenderEffect available). The bottom toolbar and
                    // the tablet-mode NavigationRail both consume it via LocalNavigationBarBackdrop.
                    val miniPlayerBgStyle by rememberEnumPreference(
                        MiniPlayerBackgroundStyleKey,
                        defaultValue = MiniPlayerBackgroundStyle.THEME,
                    )
                    val navBarFrostedBackdrop =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            val frostedLayer = rememberGraphicsLayer()
                            remember(frostedLayer) { NavigationBarBackdrop(frostedLayer) }
                        } else {
                            null
                        }

                    val liquidGlassActive =
                        liquidGlassEnabled &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    val liquidGlassBackdrop: LayerBackdrop? =
                        if (liquidGlassActive) {
                            rememberLayerBackdrop()
                        } else {
                            null
                        }

                    val bottomNavigationBarHeightState =
                        animateDpAsState(
                            targetValue = if (navigationBarVisible && !useRail) navVisibleHeight else 0.dp,
                            animationSpec = if (disableAnimations) snap() else NavigationBarAnimationSpec,
                            label = "",
                        )
                    val bottomNavigationBarHeight by bottomNavigationBarHeightState

                    val playerBottomSheetState =
                        rememberBottomSheetState(
                            dismissedBound = 0.dp,
                            collapsedBound =
                                bottomInset +
                                    (if (navigationBarVisible && !useRail) floatingBarsBottomPadding + NavigationBarHeight else 0.dp) +
                                    MiniPlayerBottomSpacing +
                                    MiniPlayerHeight,
                            expandedBound = maxHeight,
                        )

                    val playerBackground by rememberEnumPreference(
                        key = PlayerBackgroundStyleKey,
                        defaultValue = PlayerBackgroundStyle.DEFAULT,
                    )
                    val playerDesignStyle by rememberEnumPreference(
                        key = PlayerDesignStyleKey,
                        defaultValue = PlayerDesignStyle.Default,
                    )

                    val aodModeEnabled by remember(playerConnection) {
                        playerConnection?.aodModeEnabled ?: MutableStateFlow(false)
                    }.collectAsStateWithLifecycle()

                    LaunchedEffect(aodModeLaunchRequestCount, playerConnection) {
                        val launchRequestCount = aodModeLaunchRequestCount
                        if (launchRequestCount == 0) return@LaunchedEffect
                        val connection = playerConnection ?: return@LaunchedEffect
                        if (!awaitRestorablePlayback(connection)) return@LaunchedEffect
                        if (!playerBottomSheetState.isExpandedOrExpanding) {
                            playerBottomSheetState.expandSoft()
                        }
                        connection.aodModeEnabled.value = true
                        if (aodModeLaunchRequestCount == launchRequestCount) {
                            aodModeLaunchRequestCount = 0
                        }
                    }

                    LaunchedEffect(aodModeEnabled) {
                        val controller = WindowCompat.getInsetsController(window, window.decorView)
                        if (aodModeEnabled) {
                            controller.systemBarsBehavior =
                                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                            controller.hide(WindowInsetsCompat.Type.systemBars())
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            controller.show(WindowInsetsCompat.Type.systemBars())
                            controller.systemBarsBehavior =
                                WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }

                    val aodAutoTimerSeconds by rememberPreference(AodAutoTimerSecondsKey, defaultValue = 0)
                    val aodAutoOnScreenDim by rememberPreference(AodAutoOnScreenDimKey, defaultValue = false)
                    // remember(playerConnection): without it every recomposition
                    // while unbound allocates a fresh MutableStateFlow and
                    // re-subscribes the collector.
                    val isPlayingFlow =
                        remember(playerConnection) {
                            playerConnection?.isPlaying ?: MutableStateFlow(false)
                        }
                    val isPlayingNow by isPlayingFlow.collectAsStateWithLifecycle()
                    LaunchedEffect(
                        aodAutoTimerSeconds,
                        isPlayingNow,
                        playerBottomSheetState.isExpanded,
                        playerBottomSheetState.isDismissed,
                        aodModeEnabled,
                    ) {
                        if (aodModeEnabled) return@LaunchedEffect
                        if (aodAutoTimerSeconds <= 0) return@LaunchedEffect
                        if (!isPlayingNow) return@LaunchedEffect
                        if (playerBottomSheetState.isExpanded) return@LaunchedEffect
                        if (playerBottomSheetState.isDismissed) return@LaunchedEffect
                        // Wait the configured number of seconds; if the user opens the player
                        // or pauses playback during the wait, the keys above change and the
                        // effect is cancelled (delay throws CancellationException internally).
                        delay(aodAutoTimerSeconds * 1000L)
                        requestAodMode()
                    }

                    LaunchedEffect(
                        aodAutoOnScreenDim,
                        isPlayingNow,
                        playerBottomSheetState.isExpanded,
                        playerBottomSheetState.isDismissed,
                        aodModeEnabled,
                    ) {
                        if (!aodAutoOnScreenDim) return@LaunchedEffect
                        if (aodModeEnabled) return@LaunchedEffect
                        if (!isPlayingNow) return@LaunchedEffect
                        if (playerBottomSheetState.isExpanded) return@LaunchedEffect
                        if (playerBottomSheetState.isDismissed) return@LaunchedEffect

                        // Read the system screen-off timeout (e.g. 30 s). On most phones this is
                        // 15 s – 2 min. Clamp to a sane range so a misconfigured device doesn't
                        // either fire instantly or wait forever.
                        val systemTimeoutMs =
                            Settings.System
                                .getLong(
                                    contentResolver,
                                    Settings.System.SCREEN_OFF_TIMEOUT,
                                    30_000L,
                                ).coerceIn(5_000L, 600_000L)

                        val triggerDelayMs = (systemTimeoutMs - 2_000L).coerceAtLeast(2_000L)
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        try {
                            delay(triggerDelayMs)
                            requestAodMode()
                        } finally {
                            // Release the flag so the OS can manage screen-off normally again.
                            // AOD's own LaunchedEffect(aodModeEnabled) will re-add it if AOD
                            // actually engaged; if the timer was cancelled (e.g. the user
                            // expanded the player sheet), we want the OS to dim/off normally.
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }

                    LaunchedEffect(useDarkTheme, playerBottomSheetState.isExpanded, playerBackground, aodModeEnabled) {
                        if (aodModeEnabled) return@LaunchedEffect
                        val isDarkStatusBar =
                            if (playerBottomSheetState.isExpanded &&
                                playerBackground != PlayerBackgroundStyle.DEFAULT
                            ) {
                                true
                            } else {
                                useDarkTheme
                            }
                        setSystemBarAppearance(isDarkStatusBar)
                    }

                    val miniPlayerAnchor by remember {
                        derivedStateOf {
                            when {
                                playerBottomSheetState.isExpanded -> EXPANDED_ANCHOR
                                playerBottomSheetState.isDismissed -> DISMISSED_ANCHOR
                                else -> COLLAPSED_ANCHOR
                            }
                        }
                    }

                    var miniPlayerAnchorPersistenceEnabled by remember(playerConnection) {
                        mutableStateOf(false)
                    }

                    LaunchedEffect(miniPlayerAnchor, isYearInMusicScreen, miniPlayerAnchorPersistenceEnabled) {
                        if (!isYearInMusicScreen && miniPlayerAnchorPersistenceEnabled) {
                            setSavedMiniPlayerAnchor(miniPlayerAnchor)
                        }
                    }

                    var yearInMusicSavedPlayerAnchor by rememberSaveable { mutableStateOf(-1) }

                    var isPlayerLyricsFullScreen by remember { mutableStateOf(false) }

                    val shouldHideStatusBars =
                        hideStatusBar ||
                            isYearInMusicScreen ||
                            bottomSheetPageState.isVisible ||
                            (
                                menuState.isVisible &&
                                    playerBottomSheetState.isExpandedOrExpanding
                            ) ||
                            (playerBottomSheetState.isExpandedOrExpanding && isPlayerLyricsFullScreen)

                    LaunchedEffect(shouldHideStatusBars, menuState.isVisible, aodModeEnabled) {
                        if (aodModeEnabled) return@LaunchedEffect
                        setStatusBarsHidden(shouldHideStatusBars)
                        if (menuState.isVisible && shouldHideStatusBars) {
                            delay(150)
                            setStatusBarsHidden(true)
                        }
                    }

                    // Cache the status bar top inset observed while the status bar is VISIBLE.
                    val currentStatusBarTopPx = WindowInsets.statusBars.getTop(density)
                    val currentDisplayCutoutTopPx = WindowInsets.displayCutout.getTop(density)
                    var cachedStatusBarTop by remember { mutableStateOf(0.dp) }
                    var cachedDisplayCutoutTop by remember { mutableStateOf(0.dp) }
                    LaunchedEffect(currentStatusBarTopPx) {
                        if (currentStatusBarTopPx > 0) {
                            cachedStatusBarTop = with(density) { currentStatusBarTopPx.toDp() }
                        }
                    }
                    LaunchedEffect(currentDisplayCutoutTopPx) {
                        if (currentDisplayCutoutTopPx > 0) {
                            cachedDisplayCutoutTop = with(density) { currentDisplayCutoutTopPx.toDp() }
                        }
                    }
                    val liveStatusBarTop = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
                    val liveDisplayCutoutTop = with(density) { WindowInsets.displayCutout.getTop(density).toDp() }
                    // Effective top inset = max of (cached-or-live status bar, cached-or-live
                    // display cutout). When the status bar is hidden, the live status-bar
                    // value drops to 0 but the cached value (or the live display-cutout
                    // value, which always tracks the physical notch) keeps the floor > 0.
                    val effectiveStatusBarTop =
                        maxOf(
                            if (shouldHideStatusBars) cachedStatusBarTop else liveStatusBarTop,
                            if (shouldHideStatusBars) cachedDisplayCutoutTop else liveDisplayCutoutTop,
                            liveDisplayCutoutTop, // displayCutout is always reported regardless of status bar hide
                        )
                    val effectiveWindowsInsets =
                        WindowInsets(
                            top = effectiveStatusBarTop,
                            bottom = with(density) { WindowInsets.systemBars.getBottom(density).toDp() },
                        )
                    val effectiveTopInset = effectiveStatusBarTop

                    LaunchedEffect(isYearInMusicScreen, playerConnection) {
                        val connection = playerConnection ?: return@LaunchedEffect
                        val player = connection.player

                        if (isYearInMusicScreen) {
                            if (yearInMusicSavedPlayerAnchor == -1) {
                                yearInMusicSavedPlayerAnchor =
                                    when {
                                        playerBottomSheetState.isExpanded -> EXPANDED_ANCHOR
                                        playerBottomSheetState.isCollapsed -> COLLAPSED_ANCHOR
                                        playerBottomSheetState.isDismissed -> DISMISSED_ANCHOR
                                        else -> COLLAPSED_ANCHOR
                                    }
                            }

                            if (!playerBottomSheetState.isDismissed) {
                                playerBottomSheetState.dismiss()
                            }
                        } else if (yearInMusicSavedPlayerAnchor != -1) {
                            val anchorToRestore = yearInMusicSavedPlayerAnchor
                            yearInMusicSavedPlayerAnchor = -1

                            if (!awaitRestorablePlayback(connection)) {
                                playerBottomSheetState.dismiss()
                            } else {
                                when (anchorToRestore) {
                                    EXPANDED_ANCHOR -> playerBottomSheetState.expandSoft()
                                    COLLAPSED_ANCHOR -> playerBottomSheetState.collapseSoft()
                                    DISMISSED_ANCHOR -> playerBottomSheetState.collapseSoft()
                                    else -> playerBottomSheetState.collapseSoft()
                                }
                            }
                        }
                    }

                    val playerAwareWindowInsets =
                        remember(
                            useRail,
                            bottomInset,
                            shouldShowNavigationBar,
                            playerBottomSheetState.isDismissed,
                            navigationBarStyle,
                            effectiveStatusBarTop,
                        ) {
                            var bottom = bottomInset
                            if (shouldShowNavigationBar && !useRail) {
                                bottom += getBottomNavPadding() + floatingBarsBottomPadding
                            }
                            if (!playerBottomSheetState.isDismissed) {
                                bottom += MiniPlayerHeight + MiniPlayerBottomSpacing
                            }
                            effectiveWindowsInsets
                                .only(
                                    if (useRail) {
                                        WindowInsetsSides.Right
                                    } else {
                                        WindowInsetsSides.Horizontal
                                    },
                                ).add(
                                    WindowInsets(
                                        top = effectiveStatusBarTop + AppBarHeight,
                                        bottom = bottom,
                                    ),
                                )
                        }

                    val homeScrollBehavior =
                        appBarScrollBehavior(
                            canScroll = {
                                navBackStackEntry?.destination?.route?.startsWith(OnlineSearchResultRoutePrefix) == false &&
                                    navBackStackEntry?.destination?.route != Screens.Library.route &&
                                    !playerBottomSheetState.isExpandedOrExpanding
                            },
                        )
                    val searchScrollBehavior =
                        appBarScrollBehavior(
                            canScroll = {
                                navBackStackEntry?.destination?.route?.startsWith(OnlineSearchResultRoutePrefix) == false &&
                                    navBackStackEntry?.destination?.route != Screens.Library.route &&
                                    !playerBottomSheetState.isExpandedOrExpanding
                            },
                        )
                    val topAppBarScrollBehavior =
                        appBarScrollBehavior(
                            canScroll = {
                                navBackStackEntry?.destination?.route?.startsWith(OnlineSearchResultRoutePrefix) == false &&
                                    navBackStackEntry?.destination?.route != Screens.Library.route &&
                                    !playerBottomSheetState.isExpandedOrExpanding
                            },
                        )

                    val handlePrimaryNavigationClick: (Screens, Boolean) -> Unit = { screen, isSelected ->
                        if (isSelected) {
                            if (screen == Screens.Search) {
                                openSearch()
                                coroutineScope.launch { searchScrollBehavior.state.resetHeightOffset() }
                            } else {
                                navController.currentBackStackEntry?.savedStateHandle?.set("scrollToTop", true)
                                when (screen) {
                                    Screens.Home -> {
                                        coroutineScope.launch { homeScrollBehavior.state.resetHeightOffset() }
                                    }

                                    else -> {}
                                }
                            }
                        } else {
                            if (navController.currentDestination?.route != screen.route) {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        }
                    }

                    LaunchedEffect(currentRoute) {
                        when (currentRoute) {
                            Screens.Home.route -> {
                                homeScrollBehavior.state.resetHeightOffset()
                            }

                            Screens.Search.route -> {
                                searchScrollBehavior.state.resetHeightOffset()
                            }

                            else -> {}
                        }
                    }

                    LaunchedEffect(navBackStackEntry) {
                        val currentRoute = navBackStackEntry?.destination?.route

                        val isEnteringSubScreen =
                            currentRoute != null &&
                                currentRoute !in topLevelScreens &&
                                currentRoute.startsWith(OnlineSearchResultRoutePrefix) != true
                        if (isEnteringSubScreen) {
                            topAppBarScrollBehavior.state.heightOffset = 0f
                            topAppBarScrollBehavior.state.contentOffset = 0f
                        }

                        if ((
                                currentRoute?.startsWith("artist/") == true ||
                                    currentRoute?.startsWith("album/") == true
                            ) &&
                            playerBottomSheetState.isExpanded
                        ) {
                            playerBottomSheetState.collapseSoft()
                        }

                        if (navBackStackEntry?.destination?.route?.startsWith(OnlineSearchResultRoutePrefix) == true) {
                            val searchQuery =
                                decodeOnlineSearchQuery(
                                    navBackStackEntry
                                        ?.arguments
                                        ?.getString(OnlineSearchResultArgument)
                                        .orEmpty(),
                                )
                            onQueryChange(
                                TextFieldValue(
                                    searchQuery,
                                    TextRange(searchQuery.length),
                                ),
                            )
                        } else if (navigationItems.fastAny { it.route == navBackStackEntry?.destination?.route } ||
                            navBackStackEntry?.destination?.route in topLevelScreens
                        ) {
                            onQueryChange(TextFieldValue())
                        }
                    }
                    LaunchedEffect(active) {
                        if (active) {
                            when (currentRoute) {
                                Screens.Home.route -> {
                                    homeScrollBehavior.state.resetHeightOffset()
                                }

                                Screens.Search.route -> {
                                    searchScrollBehavior.state.resetHeightOffset()
                                }

                                else -> {}
                            }
                            searchBarFocusRequester.requestFocus()
                        }
                    }

                    LaunchedEffect(isTvDevice, useRail, active, currentRoute, shouldShowNavigationBar) {
                        if (
                            isTvDevice &&
                            useRail &&
                            shouldShowNavigationBar &&
                            !active &&
                            currentRoute in topLevelScreens
                        ) {
                            delay(100)
                            tvRailFocusRequester.requestFocus()
                        }
                    }

                    var restoredMiniPlayerAnchor by remember(playerConnection) { mutableStateOf(false) }

                    LaunchedEffect(playerConnection, savedMiniPlayerAnchor, isYearInMusicScreen) {
                        if (restoredMiniPlayerAnchor) return@LaunchedEffect
                        val connection = playerConnection ?: return@LaunchedEffect
                        connection.queueRestoreCompleted.first { it }
                        if (!awaitRestorablePlayback(connection)) {
                            if (!playerBottomSheetState.isDismissed) {
                                playerBottomSheetState.dismiss()
                            }
                        } else {
                            if (!isYearInMusicScreen) {
                                when (savedMiniPlayerAnchor) {
                                    EXPANDED_ANCHOR -> playerBottomSheetState.expandSoft()
                                    COLLAPSED_ANCHOR -> playerBottomSheetState.collapseSoft()
                                    DISMISSED_ANCHOR -> playerBottomSheetState.collapseSoft()
                                    else -> playerBottomSheetState.collapseSoft()
                                }
                            }
                        }
                        restoredMiniPlayerAnchor = true
                        miniPlayerAnchorPersistenceEnabled = true
                    }

                    val currentPlayerBottomSheetState = rememberUpdatedState(playerBottomSheetState)
                    val currentIsYearInMusicScreen = rememberUpdatedState(isYearInMusicScreen)

                    DisposableEffect(playerConnection) {
                        val player =
                            playerConnection?.player ?: return@DisposableEffect onDispose { }
                        val listener =
                            object : Player.Listener {
                                private fun collapseDismissedMiniPlayerForActivePlayback() {
                                    if (
                                        player.mediaItemCount > 0 &&
                                        player.currentMediaItem != null &&
                                        player.playWhenReady &&
                                        player.playbackState != Player.STATE_IDLE &&
                                        player.playbackState != Player.STATE_ENDED &&
                                        currentPlayerBottomSheetState.value.isDismissed &&
                                        !currentIsYearInMusicScreen.value
                                    ) {
                                        currentPlayerBottomSheetState.value.collapseSoft()
                                    }
                                }

                                override fun onMediaItemTransition(
                                    mediaItem: MediaItem?,
                                    reason: Int,
                                ) {
                                    collapseDismissedMiniPlayerForActivePlayback()
                                }

                                override fun onTimelineChanged(
                                    timeline: Timeline,
                                    reason: Int,
                                ) {
                                    collapseDismissedMiniPlayerForActivePlayback()
                                }

                                override fun onPlaybackStateChanged(playbackState: Int) {
                                    collapseDismissedMiniPlayerForActivePlayback()
                                }

                                override fun onPlayWhenReadyChanged(
                                    playWhenReady: Boolean,
                                    reason: Int,
                                ) {
                                    collapseDismissedMiniPlayerForActivePlayback()
                                }
                            }
                        player.addListener(listener)
                        onDispose {
                            player.removeListener(listener)
                        }
                    }

                    var shouldShowTopBar by rememberSaveable { mutableStateOf(false) }

                    LaunchedEffect(navBackStackEntry) {
                        shouldShowTopBar =
                            !active && navBackStackEntry?.destination?.route in topLevelScreens &&
                            navBackStackEntry?.destination?.route != "settings"
                    }

                    LaunchedEffect(Unit) {
                        if (pendingIntent != null) {
                            handleIntent(pendingIntent, navController)
                            pendingIntent = null
                        } else {
                            handleIntent(intent, navController)
                        }
                    }

                    var showStarDialog by remember { mutableStateOf(false) }

                    LaunchedEffect(Unit) {
                        kotlinx.coroutines.delay(3000)

                        withContext(Dispatchers.IO) {
                            val current = dataStore[LaunchCountKey] ?: 0
                            val newCount = current + 1
                            dataStore.edit { prefs ->
                                prefs[LaunchCountKey] = newCount
                            }
                        }

                        val shouldShow =
                            withContext(Dispatchers.IO) {
                                val hasPressed = dataStore[HasPressedStarKey] ?: false
                                val remindAfter = dataStore[RemindAfterKey] ?: 3
                                !hasPressed && (dataStore[LaunchCountKey] ?: 0) >= remindAfter
                            }

                        if (shouldShow) {
                            var waited = 0L
                            val waitStep = 500L
                            val maxWait = 30_000L
                            while (bottomSheetPageState.isVisible && waited < maxWait) {
                                delay(waitStep)
                                waited += waitStep
                            }
                            showStarDialog = true
                        }
                    }

                    if (showStarDialog) {
                        StarDialog(
                            onDismissRequest = { showStarDialog = false },
                            onSupport = {
                                coroutineScope.launch {
                                    try {
                                        withContext(Dispatchers.IO) {
                                            dataStore.edit { prefs ->
                                                prefs[HasPressedStarKey] = true
                                                prefs[RemindAfterKey] = Int.MAX_VALUE
                                            }
                                        }
                                    } catch (e: Exception) {
                                        reportException(e)
                                    } finally {
                                        showStarDialog = false
                                    }
                                }
                            },
                            onLater = {
                                coroutineScope.launch {
                                    try {
                                        val launch = withContext(Dispatchers.IO) { dataStore[LaunchCountKey] ?: 0 }
                                        withContext(Dispatchers.IO) {
                                            dataStore.edit { prefs ->
                                                prefs[RemindAfterKey] = launch + 20
                                            }
                                        }
                                    } catch (e: Exception) {
                                        reportException(e)
                                    } finally {
                                        showStarDialog = false
                                    }
                                }
                            },
                        )
                    }

                    val currentTitleRes =
                        remember(navBackStackEntry) {
                            when (navBackStackEntry?.destination?.route) {
                                Screens.Home.route -> R.string.home
                                Screens.Search.route -> R.string.search
                                Screens.Library.route -> R.string.filter_library
                                else -> null
                            }
                        }
                    val haptic = LocalHapticFeedback.current
                    val (enableHapticFeedback) = rememberPreference(EnableHapticFeedbackKey, true)
                    val customHaptic =
                        remember(haptic, enableHapticFeedback) {
                            object : HapticFeedback {
                                override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                                    if (enableHapticFeedback) {
                                        haptic.performHapticFeedback(hapticFeedbackType)
                                    }
                                }
                            }
                        }

                    CompositionLocalProvider(
                        LocalHapticFeedback provides customHaptic,
                        LocalAnimationsDisabled provides disableAnimations,
                        moe.rukamori.archivetune.LocalAnimationScale provides systemAnimationScale,
                        moe.rukamori.archivetune.LocalHideScrollbar provides hideScrollbar,
                        LocalDatabase provides database,
                        LocalDensity provides scaledDensity,
                        LocalContentColor provides if (pureBlack) Color.White else contentColorFor(MaterialTheme.colorScheme.surface),
                        LocalPlayerConnection provides playerConnection,
                        LocalListenTogetherManager provides listenTogetherManager,
                        LocalPlayerAwareWindowInsets provides playerAwareWindowInsets,
                        LocalNavigationBarHiddenByScroll provides
                            (navigationBarHiddenByScroll && shouldShowNavigationBar && !useRail),
                        LocalStableSystemBarsTopPadding provides effectiveStatusBarTop,
                        LocalDownloadUtil provides downloadUtil,
                        LocalShimmerTheme provides ShimmerTheme,
                        LocalSyncUtils provides syncUtils,
                        // The haze state shared between HomeScreen's hazeSource and the top
                        // bar's progressive fade blur over the Home route.
                        moe.rukamori.archivetune.ui.screens.LocalHomeHazeState provides homeHazeState,
                        moe.rukamori.archivetune.ui.component.LocalBottomSheetPageState provides bottomSheetPageState,
                        moe.rukamori.archivetune.ui.component.LocalMenuState provides menuState,
                        LocalNavigationBarBackdrop provides navBarFrostedBackdrop,
                        LocalLiquidGlassBackdrop provides liquidGlassBackdrop,
                        moe.rukamori.archivetune.ui.player.LocalIsInPipMode provides isInPictureInPictureModeState,
                        moe.rukamori.archivetune.ui.player.LocalPlayerLyricsFullScreen provides isPlayerLyricsFullScreen,
                    ) {
                        // The opening animation sits under the content and over the background,
                        // so the home screen rises through the particles as the burst lands
                        // rather than wiping them away. Composed only when it is this launch's
                        // entry; the overlay gates itself on the preference and on
                        // LocalAnimationsDisabled as well.
                        if (coldSplash) {
                            SplashOverlay(
                                isDark = useDarkTheme,
                                skip = splashSkipped,
                                onBurstStart = {
                                    contentVisible = true
                                },
                                // Also the recovery path: if the overlay gives up — its ceiling,
                                // or a tap that raced the engine — the content has to come back
                                // even though no burst ever fired.
                                onDismiss = {
                                    contentVisible = true
                                },
                            )
                        }
                        Row(
                            modifier =
                                Modifier
                                    // The reveal lives on the content, not on the root: the
                                    // overlay is a sibling of this row, and an alpha on the
                                    // shared parent would fade the particles out with the UI.
                                    // ModulateAlpha keeps the row's own translucent surfaces
                                    // from compositing into a solid block while it rises.
                                    .graphicsLayer {
                                        alpha = contentAlpha
                                        translationY = (1f - contentAlpha) * SplashConfig.Reveal.RISE_DP.dp.toPx()
                                        compositingStrategy = CompositingStrategy.ModulateAlpha
                                    }
                                    .pointerInput(contentVisible) {
                                        // While the app content is invisible it must not be
                                        // reachable, and it must not hold the reader either. The
                                        // Initial pass runs outside-in, so consuming there denies
                                        // every child gesture outright instead of racing the tap
                                        // detectors inside the screen; a tap then ends the opening
                                        // early, which is the way out if a frame never arrives.
                                        if (!contentVisible) {
                                            awaitPointerEventScope {
                                                while (true) {
                                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                                    event.changes.forEach { it.consume() }
                                                    if (event.changes.any { it.changedToUp() }) {
                                                        splashSkipped = true
                                                    }
                                                }
                                            }
                                        }
                                    },
                        ) {
                            AnimatedVisibility(
                                visible =
                                    useRail &&
                                        shouldShowNavigationBar &&
                                        (isTvDevice || !playerBottomSheetState.isExpandedOrExpanding),
                                enter = fadeIn(animationSpec = tween(durationMillis = if (disableAnimations) 0 else 150)),
                                exit = fadeOut(animationSpec = tween(durationMillis = if (disableAnimations) 0 else 100)),
                            ) {
                                if (isTvDevice) {
                                    TvNavigationRail(
                                        items = navigationItems,
                                        selectedItemRoute =
                                            if (active) {
                                                Screens.Search.route
                                            } else {
                                                currentRoute
                                            },
                                        modifier = Modifier,
                                        firstItemFocusRequester = tvRailFocusRequester,
                                        contentFocusRequester =
                                            if (active ||
                                                navBackStackEntry?.destination?.route?.startsWith(OnlineSearchResultRoutePrefix) == true
                                            ) {
                                                searchBarFocusRequester
                                            } else {
                                                contentAreaFocusRequester
                                            },
                                        onItemClick = { screen ->
                                            val wasPlayerActive = playerBottomSheetState.isExpanded
                                            if (wasPlayerActive) {
                                                playerBottomSheetState.collapse(if (disableAnimations) snap() else spring())
                                            }
                                            val isSelected =
                                                navBackStackEntry?.destination?.hierarchy?.any { it.route == screen.route } == true
                                            if (wasPlayerActive && isSelected) {
                                                return@TvNavigationRail
                                            }
                                            handlePrimaryNavigationClick(screen, isSelected)
                                        },
                                    )
                                } else {
                                    // Tablet-mode rail. honour the same nav-bar style
                                    // toggles the bottom toolbar does (frosted /
                                    // tinted-frosted / liquid glass) so the user's
                                    // preference is respected in tablet mode too.
                                    val isPreS = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                                    val canRailBlur =
                                        (navigationBarFrostedBlur || navigationBarTintFrostedBlur) &&
                                            navBarFrostedBackdrop != null && !isPreS
                                    val canRailLiquidGlass =
                                        liquidGlassEnabled && liquidGlassNavBarEnabled &&
                                            liquidGlassBackdrop != null && !isPreS
                                    var railPositionInRoot by remember {
                                        mutableStateOf(Offset.Zero)
                                    }
                                    val railContainerColor =
                                        when {
                                            canRailLiquidGlass -> Color.Transparent
                                            canRailBlur && navigationBarTintFrostedBlur -> Color.Black.copy(alpha = 0.55f)
                                            canRailBlur ->
                                                if (pureBlack) Color.Black.copy(alpha = 0.45f)
                                                else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f)
                                            pureBlack -> Color.Black
                                            else -> MaterialTheme.colorScheme.surfaceContainer
                                        }
                                    val railContentColor =
                                        when {
                                            canRailLiquidGlass -> Color.White
                                            navigationBarTintFrostedBlur && canRailBlur -> Color.White
                                            pureBlack -> Color.White
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    Box(
                                        modifier =
                                            Modifier
                                                .fillMaxHeight()
                                                .onGloballyPositioned { coordinates ->
                                                    railPositionInRoot = coordinates.positionInRoot()
                                                },
                                    ) {
                                        if (canRailBlur && navBarFrostedBackdrop != null) {
                                            val overlayAlpha =
                                                if (navigationBarTintFrostedBlur) 0.45f else 0.30f
                                            Box(
                                                modifier =
                                                    Modifier
                                                        .matchParentSize()
                                                        .graphicsLayer {
                                                            renderEffect =
                                                                BlurEffect(
                                                                    radiusX = 60f,
                                                                    radiusY = 60f,
                                                                    edgeTreatment = TileMode.Clamp,
                                                                )
                                                            alpha = overlayAlpha
                                                            clip = true
                                                        }.drawBehind {
                                                            val offset =
                                                                navBarFrostedBackdrop.contentOffsetInRoot -
                                                                    railPositionInRoot
                                                            translate(offset.x, offset.y) {
                                                                drawLayer(navBarFrostedBackdrop.layer)
                                                            }
                                                        },
                                            )
                                        }
                                        NavigationRail(
                                            containerColor = railContainerColor,
                                            contentColor = railContentColor,
                                            header = { Spacer(Modifier.height(24.dp)) },
                                        ) {
                                            navigationItems.fastForEach { screen ->
                                                val isSelected =
                                                    navBackStackEntry?.destination?.hierarchy?.any { it.route == screen.route } == true

                                                NavigationRailItem(
                                                    selected = isSelected,
                                                    icon = {
                                                        Icon(
                                                            painter =
                                                                painterResource(
                                                                    id = if (isSelected) screen.iconIdActive else screen.iconIdInactive,
                                                                ),
                                                            contentDescription = null,
                                                        )
                                                    },
                                                    label = {
                                                        Text(
                                                            text = stringResource(screen.titleId),
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                        )
                                                    },
                                                    onClick = {
                                                        val wasPlayerActive = playerBottomSheetState.isExpanded

                                                        if (wasPlayerActive) {
                                                            playerBottomSheetState.collapse(if (disableAnimations) snap() else spring())
                                                        }

                                                        if (wasPlayerActive && isSelected) return@NavigationRailItem
                                                        handlePrimaryNavigationClick(screen, isSelected)
                                                    },
                                                )
                                            }
                                        }
                                        if (canRailLiquidGlass && liquidGlassBackdrop != null) {
                                            // Apply the kyant liquid-glass backdrop sampler to the
                                            // rail area so the user sees the same glassy refraction
                                            // effect as the bottom toolbar in phone mode. We use
                                            // `drawBackdrop` with a simple blur effect — the
                                            // refraction / lens effects from the bottom toolbar
                                            // require per-item geometry that doesn't translate to
                                            // the rail's vertical layout.
                                            Box(
                                                modifier =
                                                    Modifier
                                                        .matchParentSize()
                                                        .drawBackdrop(
                                                            backdrop = liquidGlassBackdrop,
                                                            effects = {
                                                                vibrancy()
                                                                blur(4f.dp.toPx())
                                                            },
                                                            onDrawBackdrop = { drawBackdrop -> drawBackdrop() },
                                                            shape = { RectangleShape },
                                                        ),
                                            )
                                        }
                                    }
                                }
                            }

                            Scaffold(
                                topBar = {
                                    if (shouldShowTopBar) {
                                        val shouldUseFloatingTopBar =
                                            remember(navBackStackEntry) {
                                                navBackStackEntry?.destination?.route == Screens.Home.route ||
                                                    navBackStackEntry?.destination?.route == Screens.Search.route ||
                                                    navBackStackEntry?.destination?.route == Screens.Library.route
                                            }
                                        val shouldShowBlurBackground =
                                            remember(navBackStackEntry) {
                                                shouldUseFloatingTopBar
                                            }

                                        val surfaceColor = MaterialTheme.colorScheme.surface
                                        val currentScrollBehavior =
                                            when (navBackStackEntry?.destination?.route) {
                                                Screens.Home.route -> homeScrollBehavior

                                                Screens.Search.route -> searchScrollBehavior

                                                // Library hits else but is offset 0 (self-contained);
                                                // sub-screens use the shared shell behavior.
                                                else -> topAppBarScrollBehavior
                                            }
                                        val isLibraryRoute = navBackStackEntry?.destination?.route == Screens.Library.route
                                        // Home keeps its bar pinned so content scrolls under it into
                                        // the progressive blur. The title stays put with it — see the
                                        // note on AutoResizeText below for why the fade that came with
                                        // this design was wrong here.
                                        val isHomeRoute = navBackStackEntry?.destination?.route == Screens.Home.route

                                        var headerHeightPx by remember { mutableIntStateOf(0) }
                                        LaunchedEffect(currentScrollBehavior, headerHeightPx) {
                                            if (headerHeightPx > 0 && !isLibraryRoute) {
                                                val limit = -headerHeightPx.toFloat()
                                                val state = currentScrollBehavior.state
                                                if (state.heightOffsetLimit != limit) {
                                                    state.heightOffsetLimit = limit
                                                    state.heightOffset = state.heightOffset.coerceIn(limit, 0f)
                                                }
                                            }
                                        }

                                        Box(
                                            modifier =
                                                Modifier
                                                    .onSizeChanged { size ->
                                                        if (size.height > 0) headerHeightPx = size.height
                                                    }
                                                    // The inline `Modifier.offset { IntOffset(...) }`
                                                    // this replaces ran in the LAYOUT phase on every
                                                    // scroll frame and invalidated the top-app-bar
                                                    // subtree for re-layout. Folding the Y translation
                                                    // into `graphicsLayer` moves the work to the DRAW
                                                    // phase; the layout pass stays cached while the
                                                    // user scrolls.
                                                    .graphicsLayer {
                                                        translationY =
                                                            if (isLibraryRoute || isHomeRoute) {
                                                                // Library and Home both keep the bar
                                                                // pinned: Home's content scrolls under
                                                                // it into the progressive blur.
                                                                0f
                                                            } else {
                                                                currentScrollBehavior.state.heightOffset
                                                            }
                                                    },
                                        ) {
                                            if (shouldShowBlurBackground) {
                                                if (isHomeRoute &&
                                                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                                                    !playerBottomSheetState.isExpandedOrExpanding
                                                ) {
                                                    // ── TopFadeBlur ────────────────────────────────────────
                                                    // Full blur along the top edge ramping to nothing on the
                                                    // way down (progressive vertical gradient, EaseOutCubic,
                                                    // peak 0.75), over the HazeState HomeScreen tags its
                                                    // content with. A modest readability scrim sits over
                                                    // the blur so the bar's glyphs keep a floor whatever
                                                    // scrolls beneath them.
                                                    HomeTopFadeBlur(
                                                        hazeState = homeHazeState,
                                                        pageColor = surfaceColor,
                                                        barHeight = AppBarHeight + effectiveStatusBarTop,
                                                    )
                                                } else {
                                                val appBarHeightPx = with(LocalDensity.current) { AppBarHeight.toPx() }
                                                Box(
                                                    modifier =
                                                        Modifier
                                                            // Same layout-phase offset → draw-phase
                                                            // graphicsLayer conversion as the outer
                                                            // Box above. This is the blur background
                                                            // overlay under the top app bar.
                                                            .graphicsLayer {
                                                                if (!isLibraryRoute && !isHomeRoute) {
                                                                    val raw = currentScrollBehavior.state.heightOffset
                                                                    val clamped = raw.coerceAtLeast(-appBarHeightPx)
                                                                    translationY = clamped - raw
                                                                }
                                                            }.fillMaxWidth()
                                                            .height(
                                                                AppBarHeight + effectiveStatusBarTop,
                                                            ).background(
                                                                Brush.verticalGradient(
                                                                    colors =
                                                                        listOf(
                                                                            surfaceColor.copy(alpha = 0.95f),
                                                                            surfaceColor.copy(alpha = 0.85f),
                                                                            surfaceColor.copy(alpha = 0.6f),
                                                                            Color.Transparent,
                                                                        ),
                                                                ),
                                                            ),
                                                )
                                                }
                                            }

                                            TopAppBar(
                                                windowInsets =
                                                    effectiveWindowsInsets.only(
                                                        (
                                                            if (useRail) {
                                                                WindowInsetsSides.Right
                                                            } else {
                                                                WindowInsetsSides.Horizontal
                                                            }
                                                        ) + WindowInsetsSides.Top,
                                                    ),
                                                title = {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        // app icon
                                                        Icon(
                                                            painter = painterResource(R.drawable.about_appbar),
                                                            contentDescription = null,
                                                            modifier =
                                                                Modifier
                                                                    .size(35.dp)
                                                                    .padding(end = 3.dp),
                                                        )
                                                        // Always visible, on Home too. The BitChord
                                                        // design this came from fades the bar title in
                                                        // only once scrolled, because there the big
                                                        // in-list header IS the page title and the two
                                                        // would otherwise say the same thing twice.
                                                        // Ours is a greeting — "Good morning, <name>" —
                                                        // so fading the bar title left nothing on the
                                                        // home screen naming the app at all.
                                                        AutoResizeText(
                                                            text = stringResource(R.string.app_name),
                                                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                                            fontSizeRange = FontSizeRange(min = 14.sp, max = 22.sp),
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Visible,
                                                            softWrap = true,
                                                            modifier = Modifier.weight(1f, fill = false),
                                                        )
                                                    }
                                                },
                                                actions = {
                                                    // Home only, and only with a Spotify session:
                                                    // the toggle swaps which home page the tab
                                                    // shows, which means nothing anywhere else.
                                                    // Sits left of the avatar, and carries the
                                                    // logo of the page it switches TO.
                                                    if (isHomeRoute) {
                                        HomeSourceToggleButton(
                                            onOpenSpotifySettings = {
                                                navController.navigate("settings/integration?scrollTo=spotify")
                                            },
                                        )
                                                    }
                                                    Box(
                                                        modifier = Modifier.padding(end = 4.dp),
                                                    ) {
                                                        IconButton(
                                                            onClick = { profileMenuExpanded = true },
                                                            colors = IconButtonDefaults.iconButtonColors(
                                                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                                                                    .copy(alpha = TopAppBarIconButtonContainerAlpha),
                                                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            ),
                                                        ) {
                                                            Surface(
                                                                modifier = Modifier.size(28.dp),
                                                                shape = CircleShape,
                                                                color = MaterialTheme.colorScheme.primaryContainer,
                                                            ) {
                                                                if (!accountImageUrl.isNullOrBlank()) {
                                                                    AsyncImage(
                                                                        model = accountImageUrl,
                                                                        contentDescription = stringResource(R.string.account),
                                                                        modifier = Modifier
                                                                            .fillMaxSize()
                                                                            .clip(CircleShape),
                                                                        contentScale = ContentScale.Crop,
                                                                    )
                                                                } else {
                                                                    Box(contentAlignment = Alignment.Center) {
                                                                        Icon(
                                                                            painter = painterResource(R.drawable.account),
                                                                            contentDescription = stringResource(R.string.account),
                                                                            modifier = Modifier.size(20.dp),
                                                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                    if (profileMenuExpanded) {
                                                        val showSettingsBadge = BuildConfig.UPDATER_AVAILABLE &&
                                                            latestUpdateChannel == effectiveUpdateChannel &&
                                                            Updater.isUpdateAvailable(latestVersionName, BuildConfig.VERSION_NAME)
                                                        ProfileMenuDialog(
                                                            accountName = accountName,
                                                            accountImageUrl = accountImageUrl,
                                                            items = listOf(
                                                                ProfileMenuItem(
                                                                    icon = R.drawable.history,
                                                                    label = stringResource(R.string.history),
                                                                    onClick = {
                                                                        profileMenuExpanded = false
                                                                        navController.navigate("history")
                                                                    },
                                                                ),
                                                                ProfileMenuItem(
                                                                    icon = R.drawable.newspaper,
                                                                    label = stringResource(R.string.news),
                                                                    showBadge = hasUnreadNews,
                                                                    onClick = {
                                                                        profileMenuExpanded = false
                                                                        navController.navigate("news")
                                                                    },
                                                                ),
                                                                ProfileMenuItem(
                                                                    icon = R.drawable.new_release,
                                                                    label = stringResource(R.string.new_release_albums),
                                                                    onClick = {
                                                                        profileMenuExpanded = false
                                                                        navController.navigate("new_release")
                                                                    },
                                                                ),
                                                                ProfileMenuItem(
                                                                    icon = R.drawable.stats,
                                                                    label = stringResource(R.string.lastfm_dashboard),
                                                                    onClick = {
                                                                        profileMenuExpanded = false
                                                                        navController.navigate("lastfm_dashboard")
                                                                    },
                                                                ),
                                                                // Task 2: Music Recognition + Listen Together moved here from the
                                                                // removed Home FAB. The third FAB action (Shuffle) is dropped entirely.
                                                                ProfileMenuItem(
                                                                    icon = R.drawable.mic,
                                                                    label = stringResource(R.string.music_recognition),
                                                                    onClick = {
                                                                        profileMenuExpanded = false
                                                                        navController.navigate(MusicRecognitionRoute)
                                                                    },
                                                                ),
                                                                // Listen Together — top-bar entry, gated
                                                                // by the ListenTogetherInTopBarKey toggle
                                                                // in its settings screen (vivi's pattern).
                                                                if (listenTogetherInTopBar) {
                                                                    ProfileMenuItem(
                                                                        icon = R.drawable.diversity_listen_together,
                                                                        label = stringResource(R.string.listen_together),
                                                                        onClick = {
                                                                            profileMenuExpanded = false
                                                                            navController.navigate("listen_together_from_topbar")
                                                                        },
                                                                    )
                                                                } else {
                                                                    null
                                                                },
                                                                // Settings entry removed — the Home route's
                                                                // top-end settings icon in liquid glass is
                                                                // now the sole entry point, and it carries
                                                                // the update-available badge.
                                                                ProfileMenuItem(
                                                                    icon = R.drawable.settings,
                                                                    label = stringResource(R.string.settings),
                                                                    showBadge = showSettingsBadge,
                                                                    onClick = {
                                                                        profileMenuExpanded = false
                                                                        navController.navigate("settings")
                                                                    },
                                                                ),
                                                            ).filterNotNull(),
                                                            onDismiss = { profileMenuExpanded = false },
                                                        )
                                                    }
                                                },
                                                scrollBehavior =
                                                    if (navBackStackEntry?.destination?.route == Screens.Library.route ||
                                                        shouldUseFloatingTopBar
                                                    ) {
                                                        null
                                                    } else {
                                                        topAppBarScrollBehavior
                                                    },
                                                colors =
                                                    TopAppBarDefaults.topAppBarColors(
                                                        containerColor =
                                                            if (shouldUseFloatingTopBar) {
                                                                Color.Transparent
                                                            } else if (pureBlack) {
                                                                Color.Black
                                                            } else {
                                                                MaterialTheme.colorScheme.surface
                                                            },
                                                        // Task 9: header should always be transparent
                                                        // when scrolling — never paint a solid color
                                                        // background. Pure-black keeps its black
                                                        // header for OLED contrast.
                                                        scrolledContainerColor =
                                                            if (pureBlack) {
                                                                Color.Black
                                                            } else {
                                                                Color.Transparent
                                                            },
                                                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                                                        actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    ),
                                            )
                                        }
                                    }
                                    AnimatedVisibility(
                                        visible =
                                            active ||
                                                navBackStackEntry?.destination?.route?.startsWith(OnlineSearchResultRoutePrefix) == true,
                                        enter = fadeIn(animationSpec = tween(durationMillis = if (disableAnimations) 0 else 300)),
                                        exit = fadeOut(animationSpec = tween(durationMillis = if (disableAnimations) 0 else 200)),
                                    ) {
                                        // ── In-app voice search side effect ──
                                        // When the GMS/Foss VoiceSearchController emits a Result, fill
                                        // the search box and submit the query. On Error, show a toast.
                                        val voiceSearchController = remember {
                                            VoiceSearchControllerLocator.get(this@MainActivity)
                                        }
                                        val voiceSearchState by voiceSearchController.state.collectAsStateWithLifecycle()
                                        LaunchedEffect(voiceSearchState) {
                                            when (val s = voiceSearchState) {
                                                is VoiceSearchState.Result -> {
                                                    onQueryChange(TextFieldValue(s.text))
                                                    onSearch(s.text)
                                                    voiceSearchController.cancel() // reset to Idle
                                                }
                                                is VoiceSearchState.Error -> {
                                                    Toast.makeText(this@MainActivity, s.message, Toast.LENGTH_SHORT).show()
                                                    voiceSearchController.cancel()
                                                }
                                                else -> Unit
                                            }
                                        }
                                        TopSearch(
                                            query = query,
                                            onQueryChange = onQueryChange,
                                            onSearch = onSearch,
                                            active = active,
                                            onActiveChange = onActiveChange,
                                            placeholder = {
                                                Text(
                                                    text =
                                                        stringResource(
                                                            when (searchSource) {
                                                                SearchSource.LOCAL -> R.string.search_library
                                                                SearchSource.ONLINE ->
                                                                    if (searchProvider == SearchProvider.SPOTIFY) {
                                                                        R.string.search_source_spotify
                                                                    } else {
                                                                        R.string.search_yt_music
                                                                    }
                                                            },
                                                        ),
                                                )
                                            },
                                            leadingIcon = {
                                                IconButton(
                                                    onClick = {
                                                        when {
                                                            active -> {
                                                                onActiveChange(false)
                                                            }

                                                            !navigationItems.fastAny {
                                                                it.route == navBackStackEntry?.destination?.route
                                                            } -> {
                                                                navController.navigateUp()
                                                            }

                                                            else -> {
                                                                onActiveChange(true)
                                                            }
                                                        }
                                                    },
                                                    onLongClick = {
                                                        when {
                                                            active -> {}

                                                            !navigationItems.fastAny {
                                                                it.route == navBackStackEntry?.destination?.route
                                                            } -> {
                                                                navController.backToMain()
                                                            }

                                                            else -> {}
                                                        }
                                                    },
                                                ) {
                                                    Icon(
                                                        painterResource(
                                                            if (active ||
                                                                !navigationItems.fastAny {
                                                                    it.route == navBackStackEntry?.destination?.route
                                                                }
                                                            ) {
                                                                R.drawable.arrow_back
                                                            } else {
                                                                R.drawable.search
                                                            },
                                                        ),
                                                        contentDescription = null,
                                                    )
                                                }
                                            },
                                            trailingIcon = {
                                                Row {
                                                    // In-app voice search mic button. Uses Google Play
                                                    // Services speech (gms flavor) so the user does NOT
                                                    // need to install the standalone Google app.
                                                    // `voiceSearchController` and `voiceSearchState` come
                                                    // from the outer scope (declared just above TopSearch).
                                                    val micPermissionLauncher = rememberLauncherForActivityResult(
                                                        ActivityResultContracts.RequestPermission(),
                                                    ) { granted ->
                                                        if (granted) {
                                                            voiceSearchController.startListening(this@MainActivity)
                                                        }
                                                    }
                                                    if (active) {
                                                        if (query.text.isNotEmpty()) {
                                                            IconButton(
                                                                onClick = {
                                                                    onQueryChange(
                                                                        TextFieldValue(
                                                                            "",
                                                                        ),
                                                                    )
                                                                },
                                                            ) {
                                                                Icon(
                                                                    painter = painterResource(R.drawable.close),
                                                                    contentDescription = null,
                                                                )
                                                            }
                                                        }
                                                        SearchSourcePicker(
                                                            currentScope = searchSource,
                                                            currentProvider = searchProvider,
                                                            onSelection = selectSearchSource,
                                                        )
                                                    } else if (currentRoute?.startsWith(OnlineSearchResultRoutePrefix) == true) {
                                                        SearchSourcePicker(
                                                            currentScope = SearchSource.ONLINE,
                                                            currentProvider = searchProvider,
                                                            onSelection = selectSearchSource,
                                                        )
                                                        OnlineSearchSortMenu(
                                                            selectedSort = onlineSearchSort,
                                                            onSortSelected = { onlineSearchSort = it },
                                                        )
                                                    }

                                                    // Mic button is always visible (collapsed and active search bar)
                                                    // so users can voice-search regardless of search state.
                                                    IconButton(
                                                        onClick = {
                                                            if (ContextCompat.checkSelfPermission(
                                                                    this@MainActivity,
                                                                    Manifest.permission.RECORD_AUDIO,
                                                                ) == PackageManager.PERMISSION_GRANTED
                                                            ) {
                                                                voiceSearchController.startListening(this@MainActivity)
                                                            } else {
                                                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                                            }
                                                        },
                                                    ) {
                                                        Icon(
                                                            painter = painterResource(R.drawable.mic),
                                                            contentDescription = stringResource(R.string.voice_search),
                                                            tint = if (voiceSearchState is VoiceSearchState.Listening) {
                                                                MaterialTheme.colorScheme.primary
                                                            } else {
                                                                LocalContentColor.current
                                                            },
                                                        )
                                                    }
                                                }
                                            },
                                            modifier =
                                                Modifier
                                                    .focusRequester(searchBarFocusRequester)
                                                    .let { with(this@BoxWithConstraints) { it.align(Alignment.TopCenter) } },
                                            focusRequester = searchBarFocusRequester,
                                            leftFocusRequester = tvRailFocusRequester,
                                            colors =
                                                if (pureBlack && active) {
                                                    SearchBarDefaults.colors(
                                                        containerColor = Color.Black,
                                                        dividerColor = Color.DarkGray,
                                                        inputFieldColors =
                                                            TextFieldDefaults.colors(
                                                                focusedTextColor = Color.White,
                                                                unfocusedTextColor = Color.Gray,
                                                                focusedContainerColor = Color.Transparent,
                                                                unfocusedContainerColor = Color.Transparent,
                                                                cursorColor = Color.White,
                                                                focusedIndicatorColor = Color.Transparent,
                                                                unfocusedIndicatorColor = Color.Transparent,
                                                            ),
                                                    )
                                                } else {
                                                    SearchBarDefaults.colors(
                                                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                                    )
                                                },
                                        ) {
                                            Crossfade(
                                                targetState = searchSource to searchProvider,
                                                animationSpec = tween(durationMillis = if (disableAnimations) 0 else 300),
                                                label = "searchSource",
                                                modifier =
                                                    Modifier
                                                        .fillMaxSize()
                                                        .padding(
                                                            bottom = if (!playerBottomSheetState.isDismissed) MiniPlayerHeight else 0.dp,
                                                        ).navigationBarsPadding(),
                                            ) { (searchScope, provider) ->
                                                when (searchScope) {
                                                    SearchSource.LOCAL -> {
                                                        LocalSearchScreen(
                                                            query = query.text,
                                                            navController = navController,
                                                            onDismiss = { onActiveChange(false) },
                                                            pureBlack = pureBlack,
                                                        )
                                                    }

                                                    SearchSource.ONLINE -> {
                                                        OnlineSearchScreen(
                                                            query = query.text,
                                                            onQueryChange = onQueryChange,
                                                            navController = navController,
                                                            onSearch = {
                                                                navController.navigate(onlineSearchResultRoute(it, provider))
                                                                if (!pauseSearchHistory) {
                                                                    database.query {
                                                                        insert(SearchHistory(query = it))
                                                                    }
                                                                }
                                                            },
                                                            onDismiss = { onActiveChange(false) },
                                                            pureBlack = pureBlack,
                                                            searchProvider = provider,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                },
                                bottomBar = {
                                    Box {
                                        // Continuous 0..1 docking factor shared by the mini player
                                        // (top corners morph toward the junction radius) and the
                                        // nav bar (top corners morph instead of its bottom ones).
                                        // Pure arithmetic over snapshot reads — no allocation — so
                                        // invoking it every sheet-drag frame stays cheap; the
                                        // consumers quantize it through derivedStateOf + remember.
                                        val showNavigationBarState = rememberUpdatedState(shouldShowNavigationBar)
                                        val useRailState = rememberUpdatedState(useRail)
                                        val navigationProximityProvider: () -> Float =
                                            remember(
                                                playerBottomSheetState,
                                                bottomNavigationBarHeightState,
                                                navVisibleHeight,
                                                isFloatingNavBar,
                                                navigationBarBitchordEnabled,
                                                navigationBarStyle,
                                            ) {
                                                {
                                                    val navRatio =
                                                        (bottomNavigationBarHeightState.value / navVisibleHeight).coerceIn(0f, 1f)
                                                    val isNavTransitioning =
                                                        bottomNavigationBarHeightState.value > 0.dp &&
                                                            bottomNavigationBarHeightState.value < navVisibleHeight
                                                    // Swipe distance over which the corners finish
                                                    // morphing once the sheet leaves collapsed.
                                                    val morphThreshold = MiniPlayerHeight + MiniPlayerBottomSpacing
                                                    val swipeDeviation =
                                                        if (isNavTransitioning && playerBottomSheetState.targetAnchor == COLLAPSED_ANCHOR) {
                                                            0.dp
                                                        } else {
                                                            playerBottomSheetState.value.let { v ->
                                                                if (v < playerBottomSheetState.collapsedBound) {
                                                                    playerBottomSheetState.collapsedBound - v
                                                                } else {
                                                                    v - playerBottomSheetState.collapsedBound
                                                                }
                                                            }
                                                        }
                                                    val sheetPresence = (1f - (swipeDeviation / morphThreshold)).coerceIn(0f, 1f)
                                                    // A floating pill never docks with the mini player, and neither
                                                    // does the Apple Music bar: it keeps its own rounded corners on
                                                    // all four sides, so pinching the mini player's bottom corners
                                                    // toward a junction left them square against nothing (#169).
                                                    // The BitChord pill is floating too, so it never docks either.
                                                    if (
                                                        !showNavigationBarState.value ||
                                                        useRailState.value ||
                                                        isFloatingNavBar ||
                                                        navigationBarBitchordEnabled ||
                                                        navigationBarStyle == NavigationBarStyle.APPLE_MUSIC
                                                    ) {
                                                        0f
                                                    } else {
                                                        navRatio * sheetPresence
                                                    }
                                                }
                                            }

                                        ProvideVideoFullscreenState {
                                            BottomSheetPlayer(
                                                state = playerBottomSheetState,
                                                navController = navController,
                                                pureBlack = pureBlack,
                                                navigationProximityProvider = navigationProximityProvider,
                                                onLyricsVisibilityChange = { isPlayerLyricsFullScreen = it },
                                            )
                                        }

                                        if (useRail) return@Box

                                        val navSlideDistance =
                                            bottomInset + floatingBarsBottomPadding + navVisibleHeight

                                        Box(
                                            modifier =
                                                Modifier
                                                    .align(Alignment.BottomCenter)
                                                    .height(navSlideDistance)
                                                    // Why `graphicsLayer.translationY` and not
                                                    // `Modifier.offset`: offset runs in the LAYOUT
                                                    // phase, so every spring frame (nav bar height
                                                    // animating when the mini player docks) and
                                                    // every sheet-drag frame (player progress)
                                                    // re-lays-out the whole
                                                    // FloatingNavigationToolbar subtree — every
                                                    // onGloballyPositioned callback cascades, the
                                                    // kyant drawBackdrop shaders re-position, and
                                                    // the app-wide layerBackdrop recording on the
                                                    // NavHost root is invalidated.
                                                    .graphicsLayer {
                                                        translationY =
                                                            if (bottomNavigationBarHeight == 0.dp) {
                                                                navSlideDistance.toPx()
                                                            } else {
                                                                val slideOffset =
                                                                    navSlideDistance.toPx() *
                                                                        playerBottomSheetState.progress.coerceIn(
                                                                            0f,
                                                                            1f,
                                                                        )
                                                                val hideOffset =
                                                                    navSlideDistance.toPx() *
                                                                        (
                                                                            1 -
                                                                                bottomNavigationBarHeight.coerceAtMost(navVisibleHeight) /
                                                                                    navVisibleHeight
                                                                        )
                                                                slideOffset + hideOffset
                                                            }
                                                    },
                                        ) {
                                            // The BitChord pill replaces the stock toolbar when its
                                            // switch is on. Apple Music keeps its coordinated bar.
                                            if (navigationBarBitchordEnabled && navigationBarStyle != NavigationBarStyle.APPLE_MUSIC) {
                                                moe.rukamori.archivetune.ui.component.BitChordNavBar(
                                                    barHeight = navVisibleHeight,
                                                    selectedRoute =
                                                        navBackStackEntry?.destination?.route,
                                                    onRouteSelected = { route ->
                                                        navigationItems
                                                            .firstOrNull { it.route == route }
                                                            ?.let { screen ->
                                                                handlePrimaryNavigationClick(
                                                                    screen,
                                                                    navBackStackEntry?.destination?.hierarchy
                                                                        ?.any { it.route == screen.route } == true,
                                                                )
                                                            }
                                                    },
                                                    itemCount = navigationItems.size,
                                                    itemRoute = { navigationItems[it].route },
                                                    itemLabel = { androidx.compose.ui.res.stringResource(navigationItems[it].titleId) },
                                                    itemIcon = { index ->
                                                        when (navigationItems[index].route) {
                                                            "home" -> moe.rukamori.archivetune.ui.component.BitChordHomeIcon
                                                            "search" -> moe.rukamori.archivetune.ui.component.BitChordSearchIcon
                                                            else -> moe.rukamori.archivetune.ui.component.BitChordLibraryIcon
                                                        }
                                                    },
                                                    modifier =
                                                        Modifier
                                                            .align(Alignment.BottomCenter)
                                                            .padding(
                                                                bottom = bottomInset + floatingBarsBottomPadding,
                                                            ),
                                                    frostedBackdrop = navBarFrostedBackdrop,
                                                )
                                            } else {
                                                FloatingNavigationToolbar(
                                                    items = navigationItems,
                                                    pureBlack = pureBlack,
                                                    miniPlayerProximityProvider = navigationProximityProvider,
                                                    style = navigationBarStyle,
                                                    frostedBlur = navigationBarFrostedBlur,
                                                    tintFrostedBlur = navigationBarTintFrostedBlur,
                                                    frostedBackdrop = navBarFrostedBackdrop,
                                                    liquidGlass = liquidGlassEnabled && liquidGlassNavBarEnabled,
                                                    liquidGlassBackdrop = liquidGlassBackdrop,
                                                    modifier =
                                                        Modifier
                                                            .align(Alignment.BottomCenter)
                                                            .padding(
                                                                start = navBarHorizontalPadding,
                                                                end = navBarHorizontalPadding,
                                                                bottom = bottomInset + floatingBarsBottomPadding,
                                                            ).height(navVisibleHeight),
                                                    isSelected = { screen ->
                                                        navBackStackEntry?.destination?.hierarchy?.any { it.route == screen.route } ==
                                                            true
                                                    },
                                                    onItemClick = { screen, isSelected ->
                                                        handlePrimaryNavigationClick(screen, isSelected)
                                                    },
                                                    onSearchItemDoubleClick = {
                                                        searchSource = SearchSource.ONLINE
                                                        openSearch()
                                                    },
                                                )
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                NavHost(
                                    navController = navController,
                                    startDestination =
                                        if (launchMusicRecognitionFromShortcut) {
                                            MusicRecognitionRoute
                                        } else {
                                            when (tabOpenedFromShortcut ?: defaultOpenTab) {
                                                NavigationTab.HOME -> Screens.Home.route
                                                NavigationTab.LIBRARY -> Screens.Library.route
                                                else -> Screens.Home.route
                                            }
                                        },
                                    enterTransition = {
                                        if (disableAnimations) {
                                            fadeIn(tween(0))
                                        } else if (initialState.destination.route in topLevelScreens &&
                                            targetState.destination.route in topLevelScreens
                                        ) {
                                            // Material "fade through" for bottom-nav switches: the
                                            // incoming screen fades in slightly delayed while gently
                                            // scaling up from 92%, so Home↔Search↔Library feels animated
                                            // instead of an imperceptible straight crossfade.
                                            fadeIn(tween(220, delayMillis = 90)) +
                                                scaleIn(
                                                    animationSpec = tween(220, delayMillis = 90),
                                                    initialScale = 0.92f,
                                                )
                                        } else {
                                            fadeIn(tween(250)) + slideInHorizontally { it / 2 }
                                        }
                                    },
                                    exitTransition = {
                                        if (disableAnimations) {
                                            fadeOut(tween(0))
                                        } else if (initialState.destination.route in topLevelScreens &&
                                            targetState.destination.route in topLevelScreens
                                        ) {
                                            fadeOut(tween(90))
                                        } else {
                                            fadeOut(tween(200)) + slideOutHorizontally { -it / 2 }
                                        }
                                    },
                                    popEnterTransition = {
                                        if (disableAnimations) {
                                            fadeIn(tween(0))
                                        } else if ((
                                                initialState.destination.route in topLevelScreens ||
                                                    initialState.destination.route?.startsWith(OnlineSearchResultRoutePrefix) == true
                                            ) &&
                                            targetState.destination.route in topLevelScreens
                                        ) {
                                            fadeIn(tween(220, delayMillis = 90)) +
                                                scaleIn(
                                                    animationSpec = tween(220, delayMillis = 90),
                                                    initialScale = 0.92f,
                                                )
                                        } else {
                                            fadeIn(tween(250)) + slideInHorizontally { -it / 2 }
                                        }
                                    },
                                    popExitTransition = {
                                        if (disableAnimations) {
                                            fadeOut(tween(0))
                                        } else if ((
                                                initialState.destination.route in topLevelScreens ||
                                                    initialState.destination.route?.startsWith(OnlineSearchResultRoutePrefix) == true
                                            ) &&
                                            targetState.destination.route in topLevelScreens
                                        ) {
                                            fadeOut(tween(90))
                                        } else {
                                            fadeOut(tween(200)) + slideOutHorizontally { it / 2 }
                                        }
                                    },
                                    modifier =
                                        Modifier
                                            .then(
                                                if (isTvDevice) {
                                                    Modifier
                                                        .focusRequester(contentAreaFocusRequester)
                                                        .focusGroup()
                                                        .focusable()
                                                } else {
                                                    Modifier
                                                },
                                            ).then(
                                                // Frosted nav bar: capture the app content into a
                                                // layer each frame so the bar can draw it blurred.
                                                if (navBarFrostedBackdrop != null) {
                                                    Modifier
                                                        .onGloballyPositioned { coordinates ->
                                                            navBarFrostedBackdrop.contentOffsetInRoot =
                                                                coordinates.positionInRoot()
                                                        }.drawWithContent {
                                                            navBarFrostedBackdrop.layer.record {
                                                                this@drawWithContent.drawContent()
                                                            }
                                                            drawLayer(navBarFrostedBackdrop.layer)
                                                        }
                                                } else {
                                                    Modifier
                                                },
                                            ).then(
                                                if (liquidGlassBackdrop != null && !isPlayerLyricsFullScreen) {
                                                    // Suspend the outer app-wide layerBackdrop while the
                                                    // full-screen lyrics overlay is open. The overlay is
                                                    // opaque, so nothing visible samples this backdrop
                                                    // (the nav bar + mini player are hidden when the
                                                    // player is expanded). Recording the entire NavHost
                                                    // into a GraphicsLayer every frame steals GPU budget
                                                    // from the 60 Hz karaoke sweep on top.
                                                    Modifier.layerBackdrop(liquidGlassBackdrop)
                                                } else {
                                                    Modifier
                                                },
                                            ).nestedScroll(
                                                topAppBarScrollBehavior.nestedScrollConnection,
                                            ).then(
                                                if (hideNavigationBarOnScroll && shouldShowNavigationBar && !useRail) {
                                                    Modifier.nestedScroll(navigationBarScrollConnection)
                                                } else {
                                                    Modifier
                                                },
                                            ),
                                ) {
                                    navigationBuilder(
                                        navController,
                                        topAppBarScrollBehavior,
                                        { latestVersionName },
                                        disableAnimations,
                                        onClearUpdateBadge = { latestVersionName = BuildConfig.VERSION_NAME },
                                        onSearchQuery = onSearch,
                                        onVoiceSearch = launchVoiceSearch,
                                        homeListState = homeListState,
                                        searchListState = searchListState,
                                        homeScrollConnection = homeScrollBehavior.nestedScrollConnection,
                                        searchScrollConnection = searchScrollBehavior.nestedScrollConnection,
                                        onlineSearchSort = onlineSearchSort,
                                    )
                                }
                            }
                        }

                        BackHandler(enabled = playerBottomSheetState.isExpanded && !isPlayerLyricsFullScreen && !aodModeEnabled) {
                            playerBottomSheetState.collapseSoft()
                        }

                        BottomSheetMenu(
                            state = LocalMenuState.current,
                            // Above the maximized player sheet, or the menu opens underneath it.
                            modifier =
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .zIndex(100f),
                        )

                        BottomSheetPage(
                            state = LocalBottomSheetPageState.current,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )

                        NetworkStatusBanner(
                            state = networkBannerState,
                            modifier =
                                Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(
                                        top = if (shouldShowTopBar) effectiveTopInset + AppBarHeight + 8.dp else effectiveTopInset + 8.dp,
                                        start = 16.dp,
                                        end = 16.dp,
                                    ).zIndex(10f),
                        )
                    }

                    pendingBackupRestoreUri?.let { uri ->
                        BackupRestoreFromIntentDialog(uri = uri)
                    }

                    LaunchedEffect(shouldShowSearchBar, openSearchImmediately) {
                        if (shouldShowSearchBar && openSearchImmediately) {
                            onActiveChange(true)
                            try {
                                delay(100)
                                searchBarFocusRequester.requestFocus()
                            } catch (_: Exception) {
                            }
                            openSearchImmediately = false
                        }
                    }

                    val openSearchFromRoute =
                        navBackStackEntry
                            ?.savedStateHandle
                            ?.getStateFlow("openSearch", false)
                            ?.collectAsStateWithLifecycle()

                    LaunchedEffect(openSearchFromRoute?.value) {
                        if (openSearchFromRoute?.value == true) {
                            navBackStackEntry?.savedStateHandle?.set("openSearch", false)
                            openSearch()
                        }
                    }
                }
            }
        }
    }

    private fun isBackupUri(uri: Uri?): Boolean {
        if (uri == null) return false
        val path = uri.lastPathSegment?.lowercase(Locale.US)
        return path?.endsWith(".backup") == true || uri.toString().lowercase(Locale.US).endsWith(".backup")
    }

    private fun handleIntent(
        intent: Intent?,
        navController: NavHostController,
    ) {
        if (intent == null) return
        intent.getStringExtra("navigate_to")?.takeIf { it.isNotBlank() }?.let { route ->
            navController.navigate(route) {
                launchSingleTop = true
            }
            intent.removeExtra("navigate_to")
            return
        }
        val dataUri = intent.data
        if (isBackupUri(dataUri)) {
            pendingBackupRestoreUri = dataUri
            return
        }
        if (intent.action == ACTION_MUSIC_RECOGNITION) {
            navController.openMusicRecognition(0L)
            return
        }
        if (intent.action == ACTION_AOD_MODE) {
            requestAodMode()
            return
        }
        if (intent.action == "android.media.action.MEDIA_PLAY_FROM_SEARCH") {
            val query =
                (
                    intent.getStringExtra("query")
                        ?: intent.getStringExtra("android.intent.extra.TITLE")
                        ?: ""
                ).trim()
            if (query.isNotBlank()) {
                pendingVoiceSearchQuery = query
                startMusicServiceSafely()
                playPendingVoiceSearchIfReady()
            }
            return
        }
        if (handleExternalAudioIntent(intent)) {
            return
        }
        handleDeepLinkIntent(intent, navController)
    }

    private fun handleExternalAudioIntent(intent: Intent): Boolean {
        val incomingUris =
            buildList {
                intent.data?.let(::add)
                when (intent.action) {
                    Intent.ACTION_SEND -> {
                        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(::add)
                    }

                    Intent.ACTION_SEND_MULTIPLE -> {
                        addAll(
                            IntentCompat
                                .getParcelableArrayListExtra(
                                    intent,
                                    Intent.EXTRA_STREAM,
                                    Uri::class.java,
                                ).orEmpty(),
                        )
                    }
                }
            }.distinct()

        if (incomingUris.isEmpty()) return false

        val fallbackMimeType = intent.type
        val playableUris =
            incomingUris.filter { uri ->
                val mimeType = contentResolver.getType(uri)
                mimeType.isAudioMimeType() || fallbackMimeType.isAudioMimeType() || uri.hasAudioExtension()
            }
        if (playableUris.isEmpty()) return false

        pendingDeepLinkQueue = ListQueue(items = playableUris.map(::toExternalAudioMediaItem))
        startMusicServiceSafely()
        playPendingDeepLinkQueueIfReady()
        return true
    }

    private fun toExternalAudioMediaItem(uri: Uri): MediaItem {
        val mediaId = uri.toString()
        val title = resolveExternalAudioTitle(uri)
        val metadata =
            moe.rukamori.archivetune.models.MediaMetadata(
                id = mediaId,
                title = title,
                artists = emptyList(),
                duration = -1,
            )
        return MediaItem
            .Builder()
            .setMediaId(mediaId)
            .setUri(uri)
            .setTag(metadata)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata
                    .Builder()
                    .setTitle(title)
                    .setIsPlayable(true)
                    .setMediaType(MEDIA_TYPE_MUSIC)
                    .build(),
            ).build()
    }

    private fun resolveExternalAudioTitle(uri: Uri): String {
        val displayName =
            runCatching {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (columnIndex >= 0 && cursor.moveToFirst()) cursor.getString(columnIndex) else null
                }
            }.getOrNull()
        return displayName
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.substringBefore('?')
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: getString(R.string.unknown)
    }

    private fun String?.isAudioMimeType(): Boolean = this?.startsWith("audio/", ignoreCase = true) == true

    private fun Uri.hasAudioExtension(): Boolean {
        val extension = MimeTypeMap.getFileExtensionFromUrl(toString()).orEmpty()
        val normalized = extension.lowercase(Locale.US)
        return normalized in setOf("aac", "flac", "m4a", "mp3", "ogg", "opus", "wav", "webm")
    }

    private fun handleDeepLinkIntent(
        intent: Intent,
        navController: NavHostController,
    ) {
        val uri = intent.data ?: intent.extras?.getString(Intent.EXTRA_TEXT)?.toUri() ?: return
        val coroutineScope = lifecycleScope

        val authority = uri.authority?.lowercase()

        if (uri.scheme.equals("archivetune", ignoreCase = true) && authority == "login") {
            navController.navigate(buildLoginRoute(uri.getQueryParameter(LOGIN_URL_ARGUMENT)))
            return
        }

        // Listen Together invite links: the share URL from the room screen
        // (https://vivimusic-listen-together.onrender.com/listen?code=X) and
        // the direct custom scheme (archivetune://listen?code=X) — joins the
        // room with the stored username.
        val listenCode =
            uri.getQueryParameter("code")
                ?: uri.getQueryParameter("room")
                ?: uri.pathSegments.getOrNull(1)
        val isListenLink =
            uri.pathSegments.firstOrNull() == "listen" ||
                uri.host?.equals("listen", ignoreCase = true) == true ||
                uri.host?.equals("vivimusic-listen-together.onrender.com", ignoreCase = true) == true
        if (!listenCode.isNullOrBlank() && isListenLink) {
            val username =
                dataStore.get(moe.rukamori.archivetune.constants.ListenTogetherUsernameKey, "")
                    .ifBlank { "Guest" }
            listenTogetherManager.joinRoom(listenCode, username)
            return
        }

        when (val path = uri.pathSegments.firstOrNull()) {
            "playlist" -> {
                uri.getQueryParameter("list")?.let { playlistId ->
                    if (playlistId.startsWith("OLAK5uy_")) {
                        coroutineScope.launch {
                            YouTube
                                .albumSongs(playlistId)
                                .onSuccess { songs ->
                                    songs.firstOrNull()?.album?.id?.let { browseId ->
                                        navController.navigate("album/$browseId")
                                    }
                                }.onFailure { reportException(it) }
                        }
                    } else {
                        navController.navigate("online_playlist/$playlistId")
                    }
                }
            }

            "browse" -> {
                uri.lastPathSegment?.let { browseId ->
                    navController.navigate("album/$browseId")
                }
            }

            "channel", "c" -> {
                uri.lastPathSegment?.let { artistId ->
                    navController.navigate("artist/$artistId")
                }
            }

            else -> {
                val videoId =
                    when {
                        path == "watch" -> uri.getQueryParameter("v")
                        uri.host == "youtu.be" -> uri.pathSegments.firstOrNull()
                        else -> null
                    }
                val playlistId = uri.getQueryParameter("list")
                val shouldShufflePlaylist = uri.requestsShuffledPlayback()

                videoId?.let { vid ->
                    coroutineScope.launch {
                        val result =
                            withContext(Dispatchers.IO) {
                                YouTube.queue(listOf(vid), playlistId)
                            }

                        result
                            .onSuccess { queued ->
                                val mediaItem =
                                    queued.firstOrNull { it.id == vid }?.toMediaItem()
                                        ?: queued.firstOrNull()?.toMediaItem()
                                        ?: MediaItem
                                            .Builder()
                                            .setMediaId(vid)
                                            .setUri(vid)
                                            .setCustomCacheKey(vid)
                                            .build()
                                pendingDeepLinkQueue = ListQueue(items = listOf(mediaItem))
                                startMusicServiceSafely()
                                playPendingDeepLinkQueueIfReady()
                            }.onFailure {
                                reportException(it)
                            }
                    }
                    return
                }

                if (path == "watch" && !playlistId.isNullOrBlank()) {
                    coroutineScope.launch {
                        val result =
                            withContext(Dispatchers.IO) {
                                YouTube.playlist(playlistId)
                            }

                        result
                            .onSuccess { playlistPage ->
                                val endpoint =
                                    when {
                                        shouldShufflePlaylist -> {
                                            playlistPage.playlist.shuffleEndpoint
                                                ?: playlistPage.playlist.playEndpoint
                                        }

                                        else -> {
                                            playlistPage.playlist.playEndpoint
                                                ?: playlistPage.playlist.shuffleEndpoint
                                        }
                                    }

                                endpoint?.let {
                                    pendingDeepLinkQueue = YouTubeQueue.playlist(it)
                                    startMusicServiceSafely()
                                    playPendingDeepLinkQueueIfReady()
                                } ?: navController.navigate("online_playlist/$playlistId")
                            }.onFailure {
                                reportException(it)
                            }
                    }
                }
            }
        }
    }

    private fun android.net.Uri.requestsShuffledPlayback(): Boolean {
        val value = getQueryParameter("shuffle")?.trim()?.lowercase(Locale.US) ?: return false
        return value == "1" || value == "true"
    }

    private fun startMusicServiceSafely() {
        runCatching { startService(Intent(this, moe.rukamori.archivetune.playback.MusicService::class.java)) }
            .onFailure { reportException(it) }
    }

    @SuppressLint("ObsoleteSdkInt")
    private fun setSystemBarAppearance(isDark: Boolean) {
        WindowCompat.getInsetsController(window, window.decorView.rootView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            window.statusBarColor =
                (if (isDark) Color.Transparent else Color.Black.copy(alpha = 0.2f)).toArgb()
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            window.navigationBarColor =
                (if (isDark) Color.Transparent else Color.Black.copy(alpha = 0.2f)).toArgb()
        }
    }

    private fun setStatusBarsHidden(hidden: Boolean) {
        immersiveStatusBarsHidden = hidden
        val controller = WindowCompat.getInsetsController(window, window.decorView)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes =
                window.attributes.apply {
                    layoutInDisplayCutoutMode =
                        if (hidden) {
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                        } else {
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                        }
                }
        }

        if (hidden) {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }

    @Composable
    private fun BackupRestoreFromIntentDialog(
        uri: Uri,
        viewModel: BackupRestoreViewModel = hiltViewModel(),
    ) {
        var selected by remember { mutableStateOf(BackupCategory.entries.toSet()) }

        AlertDialog(
            onDismissRequest = { pendingBackupRestoreUri = null },
            icon = { Icon(painterResource(R.drawable.restore), null) },
            title = { Text(stringResource(R.string.restore_options_title)) },
            text = {
                Column {
                    BackupCategory.entries.forEach { category ->
                        val isChecked = category in selected
                        val labelRes =
                            when (category) {
                                BackupCategory.LIBRARY -> R.string.backup_category_library
                                BackupCategory.ACCOUNT -> R.string.backup_category_account
                                BackupCategory.SETTINGS -> R.string.backup_category_settings
                            }
                        val descRes =
                            when (category) {
                                BackupCategory.LIBRARY -> R.string.backup_category_library_desc
                                BackupCategory.ACCOUNT -> R.string.backup_category_account_desc
                                BackupCategory.SETTINGS -> R.string.backup_category_settings_desc
                            }
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = Color.Transparent,
                            onClick = {
                                selected = if (isChecked) selected - category else selected + category
                            },
                        ) {
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 72.dp)
                                        .padding(horizontal = 4.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(labelRes),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = stringResource(descRes),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                androidx.compose.material3.Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { checked ->
                                        selected = if (checked) selected + category else selected - category
                                    },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val uri = pendingBackupRestoreUri ?: return@TextButton
                        pendingBackupRestoreUri = null
                        viewModel.restore(this@MainActivity, uri, selected)
                    },
                    enabled = selected.isNotEmpty(),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(R.string.action_restore))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingBackupRestoreUri = null },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    companion object {
        const val ACTION_SEARCH = "moe.rukamori.archivetune.action.SEARCH"
        const val ACTION_LIBRARY = "moe.rukamori.archivetune.action.LIBRARY"
    }
}

val LocalDatabase = staticCompositionLocalOf<MusicDatabase> { error("No database provided") }
val LocalPlayerConnection =
    staticCompositionLocalOf<PlayerConnection?> { error("No PlayerConnection provided") }
val LocalPlayerAwareWindowInsets =
    compositionLocalOf<WindowInsets> { error("No WindowInsets provided") }
/**
 * Status-bar top inset that does NOT collapse to 0 when the status bar is transiently hidden
 * (overflow menu, V7/APPLE_MUSIC expanded player, bottom-sheet page, etc.). Screens should use
 * this instead of `WindowInsets.systemBars.asPaddingValues().calculateTopPadding()` whenever the
 * value is meant to anchor content below a pinned TopAppBar / search bar so that the content
 * stays put when the system bars flicker. The first frame before the cache is populated falls
 * back to 0.dp, which is fine because the system bars are visible at that point.
 */
val LocalStableSystemBarsTopPadding = compositionLocalOf<Dp> { 0.dp }
val LocalDownloadUtil = staticCompositionLocalOf<DownloadUtil> { error("No DownloadUtil provided") }
val LocalSyncUtils = staticCompositionLocalOf<SyncUtils> { error("No SyncUtils provided") }

private const val TopAppBarIconButtonContainerAlpha = 0.48f

@Composable
private fun OnlineSearchSortMenu(
    selectedSort: OnlineSearchSort,
    onSortSelected: (OnlineSearchSort) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val options =
        remember {
            listOf(
                OnlineSearchSort.DEFAULT,
                OnlineSearchSort.VIEWS,
            )
        }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                painter = painterResource(R.drawable.filter_alt),
                contentDescription = null,
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { sort ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text =
                                stringResource(
                                    when (sort) {
                                        OnlineSearchSort.DEFAULT -> R.string.default_style
                                        OnlineSearchSort.VIEWS -> R.string.views
                                    },
                                ),
                        )
                    },
                    onClick = {
                        expanded = false
                        onSortSelected(sort)
                    },
                    leadingIcon = {
                        if (sort == selectedSort) {
                            Icon(
                                painter = painterResource(R.drawable.done),
                                contentDescription = null,
                            )
                        } else {
                            Spacer(Modifier.size(24.dp))
                        }
                    },
                )
            }
        }
    }
}

private fun Context.isTvDevice(): Boolean {
    val isTelevisionUiMode =
        (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION
    return isTelevisionUiMode ||
        packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
}

private val NavigationBarHideScrollThreshold = 14.dp
