/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

@file:Suppress("DEPRECATION")

package moe.rukamori.archivetune.playback

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.database.SQLException
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.PowerManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.EVENT_POSITION_DISCONTINUITY
import androidx.media3.common.Player.EVENT_TIMELINE_CHANGED
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import androidx.media3.common.Player.STATE_IDLE
import androidx.media3.common.Timeline
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.MediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallbackException
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlaybackStats
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.MainActivity
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.cast.CastMediaItemResolver
import moe.rukamori.archivetune.cast.CastPlaybackRepository
import moe.rukamori.archivetune.cast.CastPlaybackRepositoryLocator
import moe.rukamori.archivetune.cast.CastScreenState
import moe.rukamori.archivetune.constants.AudioNormalizationKey
import moe.rukamori.archivetune.constants.DefaultMetadataSourceKey
import moe.rukamori.archivetune.constants.MetadataSource
import moe.rukamori.archivetune.constants.AudioOffload
import moe.rukamori.archivetune.constants.BitPerfectUsbOutputKey
import moe.rukamori.archivetune.constants.QueueAddAfterManualKey
import moe.rukamori.archivetune.constants.AudioQuality
import moe.rukamori.archivetune.constants.AudioQualityKey
import moe.rukamori.archivetune.constants.AutoDownloadOnLikeKey
import moe.rukamori.archivetune.constants.AutoChoosePlaybackClientKey
import moe.rukamori.archivetune.constants.AutoLoadMoreKey
import moe.rukamori.archivetune.constants.AutoSkipNextOnErrorKey
import moe.rukamori.archivetune.constants.AutomixEnabledKey
import moe.rukamori.archivetune.constants.AutomixPerformanceMode
import moe.rukamori.archivetune.constants.AutomixPerformanceModeKey
import moe.rukamori.archivetune.constants.AutoStartOnBluetoothKey
import moe.rukamori.archivetune.constants.CrossfadeDurationKey
import moe.rukamori.archivetune.constants.CrossfadeEnabledKey
import moe.rukamori.archivetune.constants.CrossfadeGaplessKey
import moe.rukamori.archivetune.constants.DeviceMutePlaybackRecoveryVolumeKey
import moe.rukamori.archivetune.constants.RejectSuspectedUpscaledAudioKey
import moe.rukamori.archivetune.constants.DiscordShowWhenPausedKey
import moe.rukamori.archivetune.constants.DiscordTokenKey
import moe.rukamori.archivetune.constants.DownloadSourceConfig
import moe.rukamori.archivetune.constants.EnableDiscordRPCKey
import moe.rukamori.archivetune.constants.EnableLastFMScrobblingKey
import moe.rukamori.archivetune.constants.EqualizerAutoHeadroomEnabledKey
import moe.rukamori.archivetune.constants.EqualizerBandLevelsMbKey
import moe.rukamori.archivetune.constants.EqualizerBassBoostEnabledKey
import moe.rukamori.archivetune.constants.EqualizerBassBoostStrengthKey
import moe.rukamori.archivetune.constants.EqualizerEnabledKey
import moe.rukamori.archivetune.constants.EqualizerOutputGainEnabledKey
import moe.rukamori.archivetune.constants.EqualizerOutputGainMbKey
import moe.rukamori.archivetune.constants.EqualizerSelectedProfileIdKey
import moe.rukamori.archivetune.constants.EqualizerVirtualizerEnabledKey
import moe.rukamori.archivetune.constants.EqualizerVirtualizerStrengthKey
import moe.rukamori.archivetune.constants.HISTORY_DURATION_DEFAULT
import moe.rukamori.archivetune.constants.HISTORY_DURATION_MAX
import moe.rukamori.archivetune.constants.HISTORY_DURATION_MIN
import moe.rukamori.archivetune.constants.HideExplicitKey
import moe.rukamori.archivetune.constants.HideVideoKey
import moe.rukamori.archivetune.constants.HistoryDuration
import moe.rukamori.archivetune.constants.LastFMSessionKey
import moe.rukamori.archivetune.constants.LastFMUseNowPlaying
import moe.rukamori.archivetune.constants.ListenBrainzEnabledKey
import moe.rukamori.archivetune.constants.ListenBrainzTokenKey
import moe.rukamori.archivetune.constants.MaxSongCacheSizeKey
import moe.rukamori.archivetune.constants.MediaSessionConstants.CommandToggleLike
import moe.rukamori.archivetune.constants.MediaSessionConstants.CommandToggleRepeatMode
import moe.rukamori.archivetune.constants.MediaSessionConstants.CommandToggleShuffle
import moe.rukamori.archivetune.constants.MediaSessionConstants.CommandToggleStartRadio
import moe.rukamori.archivetune.constants.PauseListenHistoryKey
import moe.rukamori.archivetune.constants.SyncPlaybackToYouTubeHistoryKey
import moe.rukamori.archivetune.constants.PauseOnDeviceMuteKey
import moe.rukamori.archivetune.constants.PermanentShuffleKey
import moe.rukamori.archivetune.constants.PersistentQueueKey
import moe.rukamori.archivetune.constants.PlayerStreamClient
import moe.rukamori.archivetune.constants.PlayerStreamClientKey
import moe.rukamori.archivetune.constants.TidalAudioQuality
import moe.rukamori.archivetune.constants.AppleMusicQuality
import moe.rukamori.archivetune.constants.AppleMusicQualityKey
import moe.rukamori.archivetune.constants.TidalAudioQualityKey
import moe.rukamori.archivetune.constants.TidalEnabledKey
import moe.rukamori.archivetune.constants.UsePoolAccountsKey
import moe.rukamori.archivetune.constants.AppleMusicSourceEnabledKey
import moe.rukamori.archivetune.constants.TidalInstancesKey
import moe.rukamori.archivetune.constants.AudioSourceType
import moe.rukamori.archivetune.constants.AudioSourceOrderKey
import moe.rukamori.archivetune.constants.TidalAccountFirstKey
import moe.rukamori.archivetune.constants.TidalAccessTokenKey
import moe.rukamori.archivetune.constants.TidalLastProbeTrackKey
import moe.rukamori.archivetune.constants.TidalRefreshTokenKey
import moe.rukamori.archivetune.constants.TidalTokenExpiryKey
import moe.rukamori.archivetune.constants.TidalAuthFlowKey
import moe.rukamori.archivetune.constants.TidalCountryCodeKey
import moe.rukamori.archivetune.constants.TidalUserIdKey
import moe.rukamori.archivetune.constants.TidalNeedsReloginKey
import moe.rukamori.archivetune.constants.QobuzEnabledKey
import moe.rukamori.archivetune.constants.QobuzBackupEnabledKey
import moe.rukamori.archivetune.constants.QobuzInstancesKey
import moe.rukamori.archivetune.constants.QobuzAudioQuality
import moe.rukamori.archivetune.constants.QobuzAudioQualityKey
import moe.rukamori.archivetune.constants.QobuzLastProbeTrackKey
import moe.rukamori.archivetune.constants.QobuzTokensKey
import moe.rukamori.archivetune.constants.toFormatId
import moe.rukamori.archivetune.constants.DeezerAudioQuality
import moe.rukamori.archivetune.constants.DeezerAudioQualityKey
import moe.rukamori.archivetune.constants.DeezerEnabledKey
import moe.rukamori.archivetune.constants.AmazonEnabledKey
import moe.rukamori.archivetune.constants.AmazonAudioQuality
import moe.rukamori.archivetune.constants.AmazonAudioQualityKey
import moe.rukamori.archivetune.constants.AmazonBypassTokenKey
import moe.rukamori.archivetune.constants.AmazonInstancesKey
import moe.rukamori.archivetune.constants.AmazonTurnstileJwtExpiryMsKey
import moe.rukamori.archivetune.constants.AmazonTurnstileJwtKey
import moe.rukamori.archivetune.constants.QqMusicEnabledKey
import moe.rukamori.archivetune.constants.QqAudioQuality
import moe.rukamori.archivetune.constants.QqMusicAudioQualityKey
import moe.rukamori.archivetune.constants.YouTubeSourceEnabledKey
import moe.rukamori.archivetune.qqmusic.QqMusicAudioProvider
import moe.rukamori.archivetune.qqmusic.QqMusicSession
import moe.rukamori.archivetune.constants.JioSaavnEnabledKey
import moe.rukamori.archivetune.constants.SaavnAudioQuality
import moe.rukamori.archivetune.constants.SaavnAudioQualityKey
import moe.rukamori.archivetune.constants.toFormatName
import moe.rukamori.archivetune.deezer.DeezerAudioProvider
import moe.rukamori.archivetune.deezer.DeezerCrypto
import moe.rukamori.archivetune.deezer.DeezerDecryptingDataSource
import moe.rukamori.archivetune.jiosaavn.SaavnService
import moe.rukamori.archivetune.qobuz.QobuzAudioProvider
import moe.rukamori.archivetune.qobuz.QobuzBackupProvider
import moe.rukamori.archivetune.qobuz.QobuzToken
import moe.rukamori.archivetune.audiosource.AmazonInstances
import moe.rukamori.archivetune.audiosource.AudioSourceAttemptDeadline
import moe.rukamori.archivetune.audiosource.AudioSourceAttemptScope
import moe.rukamori.archivetune.audiosource.AudioSourceAttemptTimeouts
import moe.rukamori.archivetune.audiosource.AudioSourceAttemptTimedOutException
import moe.rukamori.archivetune.audiosource.AudioSourceConfig
import moe.rukamori.archivetune.audiosource.DirectStream
import moe.rukamori.archivetune.audiosource.SongSourceOverride
import moe.rukamori.archivetune.audiosource.SongSourceQobuzBackupVideoId
import moe.rukamori.archivetune.audiosource.SongSourceQobuzTrackId
import moe.rukamori.archivetune.audiosource.TitleMatch
import moe.rukamori.archivetune.audiosource.pcmBitrateOrNull
import moe.rukamori.archivetune.audiosource.enabledPlaybackSources
import moe.rukamori.archivetune.audiosource.canUseCachedPlaybackSource
import moe.rukamori.archivetune.audiosource.rethrowIfAudioSourceCancelled
import moe.rukamori.archivetune.applemusic.AppleMusicAudioProvider
import moe.rukamori.archivetune.audiosource.AmazonAudioProvider
import moe.rukamori.archivetune.applemusic.AppleMusicVirtualStream
import moe.rukamori.archivetune.constants.SongSourceOverrideKey
import moe.rukamori.archivetune.constants.SongSourceQobuzBackupVideoIdKey
import moe.rukamori.archivetune.constants.SongSourceQobuzTrackIdKey
import moe.rukamori.archivetune.tidal.TidalAccountManager
import moe.rukamori.archivetune.tidal.TidalArtworkProvider
import moe.rukamori.archivetune.tidal.TidalAudioProvider
import moe.rukamori.archivetune.constants.TidalArtworkFallbackEnabledKey
import moe.rukamori.archivetune.constants.ArtworkProviderOrderKey
import moe.rukamori.archivetune.constants.DefaultArtworkProviderOrder
import moe.rukamori.archivetune.constants.deserializeArtworkProviderOrder
import moe.rukamori.archivetune.utils.PoolAccountManager
import moe.rukamori.archivetune.tidal.TidalInstanceHealthManager
import moe.rukamori.archivetune.constants.PlayerVolumeKey
import moe.rukamori.archivetune.constants.RepeatModeKey
import moe.rukamori.archivetune.constants.ScrobbleDelayPercentKey
import moe.rukamori.archivetune.constants.ScrobbleDelaySecondsKey
import moe.rukamori.archivetune.constants.ScrobbleMinSongDurationKey
import moe.rukamori.archivetune.constants.ShowLyricsKey
import moe.rukamori.archivetune.constants.SkipSilenceKey
import moe.rukamori.archivetune.constants.SmartTrimmerKey
import moe.rukamori.archivetune.constants.StopMusicOnTaskClearKey
import moe.rukamori.archivetune.constants.WakelockKey
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.AlbumEntity
import moe.rukamori.archivetune.db.entities.ArtistEntity
import moe.rukamori.archivetune.db.entities.Event
import moe.rukamori.archivetune.db.entities.FormatEntity
import moe.rukamori.archivetune.db.entities.LyricsEntity
import moe.rukamori.archivetune.db.entities.RelatedSongMap
import moe.rukamori.archivetune.db.entities.Song
import moe.rukamori.archivetune.db.entities.SongEntity
import moe.rukamori.archivetune.di.DownloadCache
import moe.rukamori.archivetune.di.PlayerCache
import moe.rukamori.archivetune.echo.EchoStreamResolver
import moe.rukamori.archivetune.extensions.SilentHandler
import moe.rukamori.archivetune.extensions.collect
import moe.rukamori.archivetune.extensions.collectLatest
import moe.rukamori.archivetune.extensions.currentMetadata
import moe.rukamori.archivetune.extensions.directorySizeBytes
import moe.rukamori.archivetune.extensions.findNextMediaItemById
import moe.rukamori.archivetune.extensions.getQueueWindows
import moe.rukamori.archivetune.extensions.mediaItems
import moe.rukamori.archivetune.extensions.metadata
import moe.rukamori.archivetune.extensions.move
import moe.rukamori.archivetune.extensions.toEnum
import moe.rukamori.archivetune.extensions.setOffloadEnabled
import moe.rukamori.archivetune.extensions.toContinuationQueue
import moe.rukamori.archivetune.extensions.toMediaItem
import moe.rukamori.archivetune.extensions.toPersistQueue
import moe.rukamori.archivetune.extensions.toQueue
import moe.rukamori.archivetune.innertube.PlaybackAuthState
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.playback.artwork.ArtworkProvider
import moe.rukamori.archivetune.playback.artwork.ArtworkRequest
import moe.rukamori.archivetune.playback.artwork.ArtworkResolver
import moe.rukamori.archivetune.playback.artwork.ArtworkSettings
import moe.rukamori.archivetune.playback.artwork.ResolvedArtwork
import moe.rukamori.archivetune.playback.artwork.isLocalArtworkUri
import moe.rukamori.archivetune.playback.smart.CrossfadeMode
import moe.rukamori.archivetune.playback.smart.SmartFadeAnalyzer
import moe.rukamori.archivetune.playback.smart.SmartFadeRuntimeState
import moe.rukamori.archivetune.playback.smart.SmartFadeSettings
import moe.rukamori.archivetune.playback.smart.SmartAnalysis
import moe.rukamori.archivetune.playback.smart.TrackAnalysisState
import moe.rukamori.archivetune.playback.smart.TransitionPlan
import moe.rukamori.archivetune.playback.smart.TransitionStyle
import moe.rukamori.archivetune.playback.smart.TransitionTrackInfo
import moe.rukamori.archivetune.playback.smart.TransitionWindow
import moe.rukamori.archivetune.playback.smart.planTransition
import moe.rukamori.archivetune.innertube.models.response.PlayerResponse
import moe.rukamori.archivetune.lastfm.LastFM
import moe.rukamori.archivetune.lyrics.LyricsHelper
import moe.rukamori.archivetune.lyrics.LyricsPreloadManager
import moe.rukamori.archivetune.lyrics.LyricsUtils
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.models.PersistPlayerState
import moe.rukamori.archivetune.spotify.SpotifyLibraryRepository
import moe.rukamori.archivetune.models.PersistQueue
import moe.rukamori.archivetune.models.toMediaMetadata
import moe.rukamori.archivetune.playback.queues.EmptyQueue
import moe.rukamori.archivetune.playback.queues.ListQueue
import moe.rukamori.archivetune.playback.queues.Queue
import moe.rukamori.archivetune.playback.queues.appendableQueuePage
import moe.rukamori.archivetune.sponsorblock.SponsorBlockPlaybackController
import moe.rukamori.archivetune.playback.queues.YouTubeQueue
import moe.rukamori.archivetune.playback.queues.filterBlockedArtists
import moe.rukamori.archivetune.playback.queues.filterExplicit
import moe.rukamori.archivetune.playback.queues.filterVideo
import moe.rukamori.archivetune.playback.queues.hasBlockedArtist
import moe.rukamori.archivetune.playback.stream.codecsFromMimeType
import moe.rukamori.archivetune.scrobbling.LastFmServiceConfig
import moe.rukamori.archivetune.storage.StorageFolderKind
import moe.rukamori.archivetune.storage.StorageLocationRepository
import moe.rukamori.archivetune.ui.screens.settings.DiscordPresenceManager
import moe.rukamori.archivetune.discord.DiscordSocialPresenceClient
import moe.rukamori.archivetune.ui.screens.settings.ListenBrainzManager
import moe.rukamori.archivetune.utils.AuthScopedCacheValue
import moe.rukamori.archivetune.utils.CoilBitmapLoader
import moe.rukamori.archivetune.utils.NetworkConnectivityObserver
import moe.rukamori.archivetune.utils.StreamClientUtils
import moe.rukamori.archivetune.utils.SyncUtils
import moe.rukamori.archivetune.utils.YTPlayerUtils
import moe.rukamori.archivetune.utils.audioauthenticity.AudioAuthenticityPlaybackContext
import moe.rukamori.archivetune.utils.audioauthenticity.AudioAuthenticityPcmProcessor
import moe.rukamori.archivetune.utils.audioauthenticity.AudioAuthenticityProcessorResult
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.enumPreference
import moe.rukamori.archivetune.utils.preference
import moe.rukamori.archivetune.utils.get
import moe.rukamori.archivetune.utils.getAsync
import moe.rukamori.archivetune.telegram.TelegramDataSource
import moe.rukamori.archivetune.telegram.isTelegramMediaId
import moe.rukamori.archivetune.utils.isLocalMediaId
import moe.rukamori.archivetune.utils.isLowDataModeActive
import moe.rukamori.archivetune.utils.reportException
import moe.rukamori.archivetune.utils.retryWithoutPlaybackLoginContext
import moe.rukamori.archivetune.widget.LoadWidgetInsightsUseCase
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.io.EOFException
import java.io.FileOutputStream
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.net.ConnectException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.LocalDateTime
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.seconds

// Hoisted file-level Regex: the JioSaavn candidate penalty loop below calls
// "[^a-z0-9]".toRegex() 4 times per candidate (title, wanted title, artist,
// wanted artist), and a JioSaavn search typically returns 5-20 candidates.
// Compiling the Pattern once at class-load avoids ~20-80 Regex allocations
// per stream resolution. Same pattern, same replacement semantics.
private val JIO_SAAVN_NORMALIZE_REGEX = Regex("[^a-z0-9]")

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class, UnstableApi::class)
@AndroidEntryPoint
class MusicService :
    MediaLibraryService(),
    Player.Listener,
    PlaybackStatsListener.Callback {
    @Inject
    lateinit var spotifyLibraryRepository: SpotifyLibraryRepository
    @Inject
    lateinit var database: MusicDatabase

    @Inject
    lateinit var lyricsHelper: LyricsHelper

    @Inject
    lateinit var syncUtils: SyncUtils

    @Inject
    lateinit var mediaLibrarySessionCallback: MediaLibrarySessionCallback

    @Inject
    internal lateinit var loadWidgetInsightsUseCase: LoadWidgetInsightsUseCase

    @Inject
    lateinit var equalizerPlaybackController: EqualizerPlaybackController

    @Inject
    lateinit var listenTogetherManager: moe.rukamori.archivetune.listentogether.ListenTogetherManager

    @Inject
    lateinit var sponsorBlockPlaybackController: SponsorBlockPlaybackController

    private lateinit var audioManager: AudioManager

    /** Owned here because [HapticsPcmProcessor] taps the sink of every player this service builds. */
    var musicHapticsEngine: SpatialFlowHapticEngine? = null
        private set

    private var audioFocusRequest: AudioFocusRequest? = null
    private var lastAudioFocusState = AudioManager.AUDIOFOCUS_NONE
    private var wasPlayingBeforeAudioFocusLoss = false
    private var pauseOnDeviceMuteEnabled = false
    private var deviceMutePlaybackRecoveryVolumePercent = 0
    private var wasAutoPausedByDeviceMute = false
    private var muteRecoveryObserver: ContentObserver? = null
    private var lastDeviceMutePlaybackNoticeAtElapsedMs = 0L
    private var hasAudioFocus = false
    private var autoStartOnBluetoothEnabled = false
    private var bluetoothReceiverRegistered = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakelockEnabled = false
    private var audioDeviceCallbackRegistered = false
    private var audioRouteRecoveryJob: Job? = null
    private var audiblePlaybackRecoveryJob: Job? = null
    private var sourceSwitchPending = false
    private var sourceSwitchExpectedVolume = 1f
    private var sourceSwitchReassertJob: Job? = null
    private var pendingSeekVolumeReassert = false
    private var seekVolumeReassertJob: Job? = null
    private var lastAudioOutputDeviceSignature: String? = null
    private var lastAudioRouteRecoveryRealtimeMs = 0L

    private lateinit var audioOutputResolver: AudioOutputResolver

    val activeAudioDevice get() = audioOutputResolver.activeAudioDevice

    fun refreshActiveDevice() = audioOutputResolver.refresh()

    private val audioDeviceCallback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
                if (addedDevices.any { it.isSink }) onAudioOutputDeviceChanged()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
                if (removedDevices.any { it.isSink }) onAudioOutputDeviceChanged()
            }
        }

    private var scopeJob = SupervisorJob()
    private var scope = CoroutineScope(Dispatchers.Main + scopeJob)
    private var ioScope = CoroutineScope(Dispatchers.IO + scopeJob)
    private val binder = MusicBinder(this)
    private var hasBoundClients = false
    private var idleStopJob: Job? = null

    private lateinit var connectivityManager: ConnectivityManager
    lateinit var connectivityObserver: NetworkConnectivityObserver
    val waitingForNetworkConnection = MutableStateFlow(false)
    private val isNetworkConnected = MutableStateFlow(false)

    private val audioQuality by enumPreference(
        this,
        AudioQualityKey,
        moe.rukamori.archivetune.constants.AudioQuality.AUTO,
    )
    private val preferredStreamClient by enumPreference(
        this,
        PlayerStreamClientKey,
        PlayerStreamClient.ANDROID_VR,
    )
    private val autoChoosePlaybackClient by preference(
        this,
        AutoChoosePlaybackClientKey,
        true,
    )
    private val playbackUrlCache = ConcurrentHashMap<String, AuthScopedCacheValue>()
    private val remotePlaybackTrackingUrlCache = ConcurrentHashMap<String, String>()
    private val contentLengthCache = ConcurrentHashMap<String, Long>()
    private val mediaOkHttpClient: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .proxy(YouTube.streamOkHttpProxy)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                val host = request.url.host
                val isYouTubeMediaHost =
                    host.endsWith("googlevideo.com") ||
                        host.endsWith("googleusercontent.com") ||
                        host.endsWith("youtube.com") ||
                        host.endsWith("youtube-nocookie.com") ||
                        host.endsWith("ytimg.com")

                if (!isYouTubeMediaHost) {
                    // Qobuz backup server (mlc-ytify.kouzu.in) — used as a separate audio source
                    // when the user explicitly enables QOBUZ_BACKUP. The endpoint takes a YouTube
                    // video id as a path segment and returns a lossless stream. The
                    // x-request-source: muzo header MUST be sent on every request (including the
                    // redirected CDN fetch in some setups) — without it the server rate-limits
                    // aggressively. ExoPlayer streams through this same OkHttp client, so
                    // injecting the header here means the streaming requests (not just the
                    // initial resolve) also bypass rate-limiting.
                    if (host.endsWith("kouzu.in") && !request.header("x-request-source").isNullOrEmpty()) {
                        return@addInterceptor chain.proceed(request)
                    }
                    if (host.endsWith("kouzu.in")) {
                        val patched =
                            request
                                .newBuilder()
                                .header("x-request-source", "muzo")
                                .build()
                        return@addInterceptor chain.proceed(patched)
                    }
                    return@addInterceptor chain.proceed(request)
                }

                val requestProfile = StreamClientUtils.resolveRequestProfile(request.url)
                chain.proceed(
                    StreamClientUtils
                        .applyRequestProfile(
                            request.newBuilder(),
                            requestProfile,
                        ).build(),
                )
            }.build()
    }

    private var currentQueue: Queue = EmptyQueue
    var queueTitle: String? = null
    private var blockedArtistIds: Set<String> = emptySet()
    private var hideMusicVideos = false
    private var infiniteQueueJob: Job? = null
    private var infiniteQueueGeneration = 0L
    private var initialQueueLoadGeneration = 0L
    // True while playQueue's initial async load is still assembling the queue. While set, the
    // transition-driven infinite-radio bootstrap is suppressed so it isn't started prematurely and
    // then invalidated by the settling transitions.
    @Volatile
    private var initialQueueLoadInProgress = false
    private val persistentStateLock = Any()
    private val persistentSaveGeneration = AtomicLong(0L)

    // Persistent files whose contents this process could not read, reported once each. A file
    // written by an older build stays unreadable after a model change, and the next save replaces
    // it, so this is routine rather than an error: it says so once, quietly, instead of repeating
    // a stack trace every time the restore path runs.
    private val reportedPersistentReadFailures = mutableSetOf<String>()

    @Volatile
    private var isRestoringPersistentState = false

    @Volatile
    private var isHydratingRestoredQueue = false
    private val restoredQueueHydrationGeneration = AtomicLong(0L)
    private var restoredQueueBackfillJob: Job? = null

    @Volatile
    private var suppressAutoPlayback = false
    private var lastPresenceToken: String? = null

    @Volatile
    private var pausedPresenceGate = PausedPresenceGate.FollowPreference

    @Volatile
    private var discordServiceStopping = false

    @Volatile
    private var lastDiscordPresenceDecision: DiscordPresenceDecision? = null

    @Volatile
    private var activeDiscordHoldState: ActiveHoldState? = null

    private var activeDiscordHoldTimeoutJob: Job? = null

    @Volatile
    private var lastAppliedVisiblePresence: LastAppliedVisiblePresence? = null

    private val discordSyncEpoch = AtomicLong(0L)
    private val discordSyncRequests = Channel<DiscordSyncRequest>(Channel.CONFLATED)
    private var discordSyncWorkerJob: Job? = null
    private val pendingDiscordRefreshWaiters = mutableListOf<CompletableDeferred<Boolean>>()
    private val discordRefreshWaitersMutex = Mutex()
    private val toggleLikeMutex = Mutex()

    @Volatile
    private var lastLoginRecoveryPrompt: Pair<String, Long>? = null
    private val playbackStreamRecoveryTracker = PlaybackStreamRecoveryTracker()

    // Initial-buffer-stall recovery (ported from Metrolist).
    // A stream can stall in STATE_BUFFERING forever without surfacing an HTTP error the
    // onPlayerError recovery paths can act on — the player just spins, then the user skips.
    // Watchdog: if playback is BUFFERING with playWhenReady at the very start of a track for
    // longer than INITIAL_BUFFER_STALL_DELAY_MS, invalidate the resolved stream caches and
    // re-prepare so the resolver picks a fresh (possibly different-client) stream URL.
    private var initialBufferRecoveryJob: Job? = null
    private var initialBufferRecoveryAttemptedMediaId: String? = null

    // Codec-state recovery counter, SEPARATE from PlaybackStreamRecoveryTracker.
    @Volatile
    private var codecRecoveryMediaId: String? = null
    @Volatile
    private var codecRecoveryAttemptCount: Int = 0
    private val codecRecoveryMaxAttempts = 4
    private var nextHistorySessionToken = 0L
    private var currentHistorySessionToken = 0L
    private var currentHistoryMediaId: String? = null
    private var currentHistoryAccumulatedPlayMs = 0L
    private var currentHistoryStartedAtElapsedMs: Long? = null
    private var currentHistoryEventId: Long? = null
    private var currentHistoryRemoteRegistered = false
    private var currentHistoryImmediateAttempted = false
    private var currentHistorySessionQueued = false
    private var historyThresholdJob: Job? = null
    private val pendingHistoryFinalizations = mutableMapOf<String, MutableList<PendingHistoryFinalization>>()
    private val historyRecordingJobs = ConcurrentHashMap<Long, kotlinx.coroutines.Deferred<ImmediateHistoryResult>>()

    val currentMediaMetadata = MutableStateFlow<moe.rukamori.archivetune.models.MediaMetadata?>(null)
    val decodedPcmFormat = MutableStateFlow<DecodedPcmFormat?>(null)
    val reportedAudioFormat = MutableStateFlow<ReportedAudioFormat?>(null)
    val androidAudioOutputFormat = MutableStateFlow<AndroidAudioOutputFormat?>(null)
    val queueRestoreCompleted = MutableStateFlow(false)
    val infiniteQueueLoading = MutableStateFlow(false)
    private val playerInitialized = MutableStateFlow(false)
    private val currentSong =
        currentMediaMetadata
            .flatMapLatest { mediaMetadata ->
                database.song(mediaMetadata?.id)
            }.flowOn(Dispatchers.IO)
            .stateIn(scope, SharingStarted.Lazily, null)
    private val currentFormat =
        currentMediaMetadata
            .flatMapLatest { mediaMetadata ->
                database.format(mediaMetadata?.id)
            }.flowOn(Dispatchers.IO)

    private val normalizeFactor = MutableStateFlow(1f)
    private val audioNormalizationFactorCache = ConcurrentHashMap<String, Float>()

    // Media ids currently served by a resolved Tidal stream. Audio normalization is skipped for
    // these because the loudness metadata we have describes the YouTube stream, not the Tidal one.
    private val tidalActiveMediaIds = ConcurrentHashMap.newKeySet<String>()
    private var audioNormalizationEnabled = true
    var playerVolume = MutableStateFlow(1f)
    private val audioFocusVolumeFactor = MutableStateFlow(1f)
    private var effectiveVolumeRampJob: Job? = null
    private var crossfadeEnabled = false
    private var crossfadeDurationMs = 0L
    private var crossfadeGapless = false
    private var crossfadeTriggerJob: Job? = null
    private var crossfadeJob: Job? = null
    private var secondaryCrossfadePlayer: ExoPlayer? = null
    private var secondaryCrossfadeTarget: CrossfadeTarget? = null
    private var isCrossfading = false
    private var crossfadeHandoffInProgress = false
    private var crossfadeBaseVolume = 1f
    private var crossfadeIncomingBaseVolume = 1f
    private var crossfadeProgress = 0f
    private var crossfadeHandoffProgress = 0f
    private var crossfadePlaybackRequested = false
    private val crossfadeFailureTracker = CrossfadeFailureTracker()
    @Volatile
    private var rejectSuspectedUpscaledAudioEnabled = false

    // Monotonic operation id for crossfades. A cancelled/superseded crossfade coroutine can
    // never complete an older handoff: finishCrossfade validates its captured generation
    // before promoting the incoming player.
    private val crossfadeGeneration = AtomicLong(0)
    private var lyricsPreloadManager: LyricsPreloadManager? = null

    // ---- Automix (analysis-driven transitions) ----
    // The smart-fade mode reuses the secondary-player crossfade machinery but
    // lets the transition plan decide WHEN the blend starts, WHERE the incoming
    // track enters (cue point), HOW FAST it plays (beat-match stretch) and
    // WHICH filters ride the blend. One analysis at a time, one plan at a time.
    private var smartFadeEnabled = false
    private var smartFadeAnalyzer: SmartFadeAnalyzer? = null
    private var primaryTransitionFilter = TransitionFilterProcessor()

    @Volatile
    private var secondaryTransitionFilter: TransitionFilterProcessor? = null

    /** The user's playback parameters, captured before a smart blend stretches
     * the incoming track, restored after promotion. */
    private var crossfadeUserPlaybackParameters: androidx.media3.common.PlaybackParameters? = null

    /** True while the running blend follows a smart plan (the incoming player is stretched). */
    private var crossfadeSmartActive = false

    // The currently active session-facing player. Replaced when a crossfade promotes the
    // incoming player. Observers (PlayerConnection) must follow this flow instead of
    // capturing `player` once.
    private val _playerFlow = MutableStateFlow<Player?>(null)
    val playerFlow = _playerFlow.asStateFlow()

    // Authoritative artwork-resolution pipeline. All artwork source decisions (original
    // metadata vs Tidal fallback) go through this resolver; results are committed centrally
    // so the player UI, notification and palette extractor all see the same image.
    private val artworkSettingsFlow =
        MutableStateFlow(
            ArtworkSettings(
                tidalArtworkEnabled = false,
                tidalAvailable = false,
                providerOrder = DefaultArtworkProviderOrder,
            ),
        )
    private lateinit var artworkResolver: ArtworkResolver
    private var artworkResolveJob: Job? = null
    private var artworkTrackGeneration: Long = 0L

    private val secondaryCrossfadeListener =
        object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Timber.tag("PlaybackDiagnostics").w(
                    "Secondary player failed errorCode=%d outgoingPositionMs=%d gain=%.3f",
                    error.errorCode,
                    player.currentPosition,
                    player.volume,
                )
                val outgoingMediaId = player.currentMediaItem?.mediaId
                val incomingMediaId = secondaryCrossfadeTarget?.mediaId
                scope.launch {
                    val retryAllowed = crossfadeFailureTracker.registerFailure(outgoingMediaId, incomingMediaId)
                    recoverCrossfadeFailure(retryAllowed)
                    if (!retryAllowed) {
                        Timber.tag("PlaybackDiagnostics").w("Crossfade retry limit reached for current transition")
                    }
                }
            }
        }

    private data class CrossfadeConfig(
        val enabled: Boolean,
        val durationSeconds: Float,
        val gapless: Boolean,
        val automixEnabled: Boolean = false,
    )

    private data class DiscordSyncRequest(
        val epoch: Long,
        val reason: String,
        val force: Boolean,
    )

    private data class Quadruple<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
    )

    private class StaleDiscordSyncException : CancellationException("Stale Discord sync request")

    private data class CrossfadeTarget(
        val index: Int,
        val mediaId: String,
    )

    private data class PendingHistoryFinalization(
        val sessionToken: Long,
        val eventId: Long?,
        val remoteRegistered: Boolean,
    )

    private data class ImmediateHistoryResult(
        val eventId: Long?,
        val remoteRegistered: Boolean,
    )

    private fun PlayerResponse.PlaybackTracking.remotePlaybackTrackingUrl(): String? =
        videostatsPlaybackUrl
            ?.baseUrl
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun isAppInForeground(): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val appProcesses = activityManager.runningAppProcesses ?: return false
        return appProcesses.any { processInfo ->
            processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                processInfo.processName == packageName
        }
    }

    private fun promptLoginRecovery(
        mediaId: String,
        targetUrl: String,
    ) {
        if (!isAppInForeground()) return

        val now = System.currentTimeMillis()
        val lastPrompt = lastLoginRecoveryPrompt
        if (lastPrompt?.first == mediaId && now - lastPrompt.second < 10000L) return
        lastLoginRecoveryPrompt = mediaId to now

        val deepLink = Uri.parse("archivetune://login?url=${Uri.encode(targetUrl)}")
        val intent =
            Intent(Intent.ACTION_VIEW, deepLink, this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

        runCatching {
            startActivity(intent)
        }.onFailure {
            Timber.e(it, "Failed to open login recovery for %s", mediaId)
        }
    }

    private fun Throwable.isRequestTimeout(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is SocketTimeoutException) return true
            if (current.message?.contains("Request timeout has expired", ignoreCase = true) == true) return true
            current = current.cause
        }
        return false
    }

    private fun Throwable.isNetworkConnectionFailure(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is ConnectException || current is UnknownHostException) return true
            current = current.cause
        }
        return false
    }

    lateinit var sleepTimer: SleepTimer

    @Inject
    @PlayerCache
    lateinit var playerCache: Cache

    @Inject
    @DownloadCache
    lateinit var downloadCache: Cache

    lateinit var localPlayer: ExoPlayer
        private set
    lateinit var player: Player
        private set
    private lateinit var castPlaybackRepository: CastPlaybackRepository
    private lateinit var mediaSession: MediaLibrarySession

    private var isAudioEffectSessionOpened = false
    private var openedAudioSessionId: Int? = null
    val eqCapabilities = MutableStateFlow<EqCapabilities?>(null)
    private val desiredEqSettings =
        MutableStateFlow(
            EqSettings(
                enabled = false,
                bandLevelsMb = emptyList(),
                outputGainEnabled = false,
                outputGainMb = 0,
                bassBoostEnabled = false,
                bassBoostStrength = 0,
                virtualizerEnabled = false,
                virtualizerStrength = 0,
                autoHeadroomEnabled = false,
            ),
        )

    private var audioEffectsSessionId: Int? = null
    private var audioEffectsInitializationJob: Job? = null
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null

    /**
     * Whether this service's players were built for bit-perfect USB output. Sampled once in
     * [onCreate]: the audio sink is fixed when a player is built, so flipping the switch takes
     * effect the next time the service starts.
     */
    private var bitPerfectOutputActive: Boolean = false
    private var bitPerfectDeviceCallback: android.media.AudioDeviceCallback? = null
    private val audioEffectPlayerListener =
        object : Player.Listener {
            override fun onEvents(
                player: Player,
                events: Player.Events,
            ) {
                if (events.containsAny(
                        Player.EVENT_AUDIO_SESSION_ID,
                        Player.EVENT_PLAYBACK_STATE_CHANGED,
                        Player.EVENT_IS_PLAYING_CHANGED,
                    )
                ) {
                    reconcileAudioEffectSession()
                }
            }
        }

    private var scrobbleManager: moe.rukamori.archivetune.utils.ScrobbleManager? = null

    private lateinit var widgetUpdater: MusicServiceWidgetUpdater

    val autoAddedMediaIds: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    private var consecutivePlaybackErr = 0

    val maxSafeGainFactor = MAX_AUDIO_NORMALIZATION_FACTOR

    @Volatile
    private var hasCalledStartForeground = false

    private fun ensureStartedAsForeground() {
        if (hasCalledStartForeground) return

        val notification =
            try {
                val contentIntent =
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )

                NotificationCompat
                    .Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.small_icon)
                    .setContentTitle(getString(R.string.music_player))
                    .setContentText(getString(R.string.app_name))
                    .setContentIntent(contentIntent)
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .build()
            } catch (e: Exception) {
                reportException(e)
                return
            }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            hasCalledStartForeground = true
        } catch (e: Exception) {
            reportException(e)
        }
    }

    private fun promoteToStartedService() {
        runCatching { startService(Intent(this, MusicService::class.java)) }
            .onFailure { reportException(it) }
    }

    private fun cancelIdleStop() {
        idleStopJob?.cancel()
        idleStopJob = null
    }

    private fun hasResumablePlaybackNotification(): Boolean {
        val state = player.playbackState
        return player.mediaItemCount > 0 &&
            player.currentMediaItem != null &&
            state != Player.STATE_IDLE &&
            state != Player.STATE_ENDED
    }

    private fun stopForegroundAndSelf() {
        cancelIdleStop()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                stopForeground(true)
            }
        }
        hasCalledStartForeground = false
        stopSelf()
    }

    private fun scheduleStopIfIdle() {
        if (hasBoundClients) return
        if (hasResumablePlaybackNotification()) {
            cancelIdleStop()
            promoteToStartedService()
            ensureStartedAsForeground()
            return
        }

        val state = player.playbackState
        // When the user merely paused a song (STATE_READY with playWhenReady=false) and
        // backgrounded the app, they typically come back within a few minutes. The previous
        // 60s idle-stop deadline was too aggressive for lossless streams — it released the
        // ExoPlayer (and the underlying MediaCodec) while the renderer still had queued
        // buffers, producing the
        //   IllegalStateException: queueInputBuffer() is valid only at Executing states;
        //   currently at Released state
        // crash on Telegram ALAC files. Give paused-but-ready playback a 10-minute grace
        // period before tearing the service down.
        val delayMs =
            when (state) {
                Player.STATE_ENDED, Player.STATE_IDLE -> 30_000L
                Player.STATE_READY -> if (player.playWhenReady) 60_000L else 10 * 60_000L
                Player.STATE_BUFFERING -> 60_000L
                else -> 60_000L
            }

        cancelIdleStop()
        idleStopJob =
            scope.launch {
                delay(delayMs)
                if (hasBoundClients) return@launch
                if (hasResumablePlaybackNotification()) return@launch
                stopForegroundAndSelf()
            }
    }

    override fun onCreate() {
        super.onCreate()
        equalizerPlaybackController.attach(this)
        ensureScopesActive()

        // Reacts to its own SharedPreferences, so nothing further has to be wired here.
        musicHapticsEngine = SpatialFlowHapticEngine(this)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = getSystemService(NotificationManager::class.java)
                nm?.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.music_player),
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                )
            }
        } catch (e: Exception) {
            reportException(e)
        }

        // The sink is fixed for the life of this player, so a default read before the first
        // settings snapshot lands would silently ignore the switch until the next restart.
        // Bounded (1.5 s) and only where the feature exists.
        if (BitPerfectUsbOutput.isPlatformSupported) {
            moe.rukamori.archivetune.utils.PreferenceStore.blockUntilLoaded()
        }
        bitPerfectOutputActive =
            BitPerfectUsbOutput.isPlatformSupported &&
            dataStore.get(BitPerfectUsbOutputKey, false) &&
            !dataStore.get(CrossfadeEnabledKey, false) &&
            !dataStore.get(AutomixEnabledKey, false)
        BitPerfectUsbOutput.sinkActive = bitPerfectOutputActive
        BitPerfectUsbOutput.setEnabled(this, bitPerfectOutputActive)
        if (bitPerfectOutputActive) registerBitPerfectDeviceCallback()

        localPlayer =
            ExoPlayer
                .Builder(this)
                .setMediaSourceFactory(createMediaSourceFactory())
                .setRenderersFactory(createRenderersFactory(primaryTransitionFilter, primaryAudioAuthenticityContext))
                .setLoadControl(createPrimaryLoadControl())
                .setTrackSelector(DefaultTrackSelector(this, SafeTrackSelectionFactory()))
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .setAudioAttributes(
                    playbackAudioAttributes(),
                    false,
                ).setSeekBackIncrementMs(5000)
                .setSeekForwardIncrementMs(5000)
                .setDeviceVolumeControlEnabled(true)
                .build()
                .apply {
                    addAnalyticsListener(PlaybackStatsListener(false, this@MusicService))
                    addListener(audioEffectPlayerListener)
                    setOffloadEnabled(false)
                }
        audioAuthenticityContextByPlayer[localPlayer] = primaryAudioAuthenticityContext
        observeAudioTrackConfiguration(localPlayer)
        castPlaybackRepository = CastPlaybackRepositoryLocator.get(this)
        player =
            castPlaybackRepository
                .createPlayer(
                    context = this,
                    localPlayer = localPlayer,
                    mediaItemResolver = CastMediaItemResolver(::resolveMediaItemForCast),
                ).apply {
                    addListener(this@MusicService)
                    sleepTimer = SleepTimer(scope, this, this@MusicService)
                    addListener(sleepTimer)
                }
        _playerFlow.value = player
        playerInitialized.value = true
        scope.launch {
            dataStore.data
                .map { it[UsePoolAccountsKey] ?: true }
                .distinctUntilChanged()
                .collect { directStreamCache.clear() }
        }
        // ioScope, not scope: the lookup is network plus a JSON parse, and the controller hops to
        // the main thread itself for every player read.
        sponsorBlockPlaybackController.attach(player, ioScope)

        // The single authoritative artwork resolver. Every artwork source decision flows
        // through here so the player, notification and palette extractor can never diverge.
        artworkResolver =
            ArtworkResolver(
                tidalFetcher = TidalArtworkProvider.fetcher(),
                settings = artworkSettingsFlow,
                ioDispatcher = Dispatchers.IO,
            )
        dataStore.data
            .map { preferences ->
                val tidalEnabled = preferences[TidalEnabledKey] ?: true
                val artworkEnabled = preferences[TidalArtworkFallbackEnabledKey] ?: false
                val hasInstances =
                    !preferences[TidalInstancesKey].isNullOrBlank() ||
                        TidalAudioProvider.defaultInstanceUrls.isNotEmpty()
                val hasAccount = !preferences[TidalAccessTokenKey].isNullOrBlank()
                val providerOrder =
                    deserializeArtworkProviderOrder(preferences[ArtworkProviderOrderKey])
                ArtworkSettings(
                    tidalArtworkEnabled = artworkEnabled,
                    tidalAvailable = tidalEnabled && (hasInstances || hasAccount),
                    providerOrder = providerOrder,
                )
            }
            .distinctUntilChanged()
            .collect(scope) { settings ->
                val changed = artworkSettingsFlow.value != settings
                artworkSettingsFlow.value = settings
                if (changed) {
                    // Provider settings changed: in-flight results must no longer be committed.
                    artworkResolver.invalidate()
                    if (settings.tidalArtworkEnabled && settings.tidalAvailable) {
                        beginArtworkResolutionForCurrentTrack()
                    } else {
                        artworkResolveJob?.cancel()
                    }
                }
            }
        database
            .blockedArtistIds()
            .map { ids -> ids.toSet() }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)
            .collect(scope) { updatedBlockedArtistIds ->
                blockedArtistIds = updatedBlockedArtistIds
                removeBlockedArtistItems(updatedBlockedArtistIds)
            }
        dataStore.data
            .map { preferences -> preferences[HideVideoKey] ?: false }
            .distinctUntilChanged()
            .collect(scope) { shouldHideMusicVideos ->
                hideMusicVideos = shouldHideMusicVideos
                if (shouldHideMusicVideos) {
                    removeMusicVideoItems()
                }
            }
        widgetUpdater =
            MusicServiceWidgetUpdater(
                service = this,
                scope = scope,
                loadWidgetInsights = loadWidgetInsightsUseCase,
            )

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioOutputResolver = AudioOutputResolver(audioManager)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            audioManager.setAllowedCapturePolicy(android.media.AudioAttributes.ALLOW_CAPTURE_BY_ALL)
        }
        wakeLock =
            (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ArchiveTune:Playback")
                .also { it.setReferenceCounted(false) }
        setupAudioFocusRequest()
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, android.os.Handler(mainLooper))
        audioDeviceCallbackRegistered = true
        lastAudioOutputDeviceSignature = currentAudioOutputDeviceSignature()
        audioOutputResolver.refresh()

        mediaLibrarySessionCallback.apply {
            toggleLike = ::toggleLike
            toggleStartRadio = ::toggleStartRadio
            toggleLibrary = ::toggleLibrary
        }
        mediaSession =
            MediaLibrarySession
                .Builder(this, player, mediaLibrarySessionCallback)
                .setSessionActivity(
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                ).setBitmapLoader(CoilBitmapLoader(this, scope))
                .build()
        setMediaNotificationProvider(
            ArchiveTuneMediaNotificationProvider(
                context = this,
                smallIconResId = R.drawable.small_icon,
            ),
        )
        addSession(mediaSession)

        updateNotification()
        player.repeatMode = REPEAT_MODE_OFF

        scope.launch(Dispatchers.IO) {
            val prefs = dataStore.data.first()
            val repeatMode = prefs[RepeatModeKey] ?: REPEAT_MODE_OFF
            val volume = (prefs[PlayerVolumeKey] ?: 1f).coerceIn(0f, 1f)
            val offload = prefs[AudioOffload] ?: false
            val crossfadePrefEnabled = prefs[CrossfadeEnabledKey] ?: false
            val automixPrefEnabled = prefs[AutomixEnabledKey] ?: false
            val authenticityPrefEnabled = prefs[RejectSuspectedUpscaledAudioKey] ?: false
            withContext(Dispatchers.Main) {
                player.repeatMode = repeatMode
                playerVolume.value = volume
                updateAudioOffload(
                    resolveAudioOffloadEnabled(
                        requested = offload,
                        crossfadeEnabled = crossfadePrefEnabled,
                        automixEnabled = automixPrefEnabled,
                        authenticityRejectionEnabled = authenticityPrefEnabled,
                    ),
                )
            }
        }

        connectivityManager = getSystemService()!!
        connectivityObserver = NetworkConnectivityObserver(this)

        scope.launch {
            connectivityObserver.networkStatus.collect { isConnected ->
                isNetworkConnected.value = isConnected
                if (isConnected && waitingForNetworkConnection.value) {
                    waitingForNetworkConnection.value = false
                    if (player.currentMediaItem != null && player.playWhenReady &&
                        player.playbackState == Player.STATE_IDLE
                    ) {
                        player.prepare()
                        player.play()
                    }
                }
            }
        }

        combine(playerVolume, normalizeFactor, audioFocusVolumeFactor) { playerVolume, normalizeFactor, audioFocusVolumeFactor ->
            calculateEffectivePlayerVolume(playerVolume, normalizeFactor, audioFocusVolumeFactor)
        }.collectLatest(scope) { finalVolume ->
            updateEffectiveVolume(finalVolume)
        }

        playerVolume.debounce(1000).collect(ioScope) { volume ->
            dataStore.edit { settings ->
                settings[PlayerVolumeKey] = volume
            }
        }

        currentSong.debounce(300).collect(scope) { song ->
            updateNotification()
            requestDiscordSync(
                reason =
                    if (song == null) {
                        "current_song_cleared"
                    } else {
                        "current_song_changed"
                    },
            )
            if (song != null && player.playWhenReady && player.playbackState == Player.STATE_READY) {
                ensurePresenceManager()
            }
        }

        combine(
            currentMediaMetadata.distinctUntilChangedBy { it?.id },
            dataStore.data.map { it[ShowLyricsKey] ?: false }.distinctUntilChanged(),
        ) { mediaMetadata, _ ->
            mediaMetadata
        }.collectLatest(ioScope) { mediaMetadata ->
            if (mediaMetadata == null) return@collectLatest
            // Podcast episodes carry no lyrics catalogue entries; fetching would burn
            // provider calls for rows that can never resolve.
            if (mediaMetadata.isPodcast) return@collectLatest
            // Always attempt to fetch lyrics when a new song starts playing so the
            // lyrics panel is ready by the time the user opens it, instead of requiring
            // the user to manually open the panel and search to trigger a fetch.
            //
            // We also retry a stored LYRICS_NOT_FOUND on every play, because early
            // plays can fail while metadata/network are still settling.
            val stored = database.lyrics(mediaMetadata.id).first()
            // The word-synced toggle has to be able to *replace* a stored row, not merely fill an
            // empty one; see LyricsUtils.needsWordSyncedUpgrade for which rows are eligible and
            // LyricsHelper for why only a settled sweep consumes the attempt.
            val upgradeToWordSynced =
                lyricsHelper.shouldAttemptWordSyncedUpgrade(
                    mediaId = mediaMetadata.id,
                    stored = stored,
                )
            val shouldFetch =
                stored == null ||
                    stored.lyrics == LyricsEntity.LYRICS_NOT_FOUND ||
                    upgradeToWordSynced
            if (shouldFetch) {
                // getLyricsWithProvider (not getLyrics) preserves the providerName — with the
                // plain getter the auto-fetched lyrics were stored with a blank attribution
                // and the "Lyrics from [provider]" header never rendered until a manual
                // re-fetch from the lyrics search popup (4nx3b fix).
                val lyricsResult = lyricsHelper.getLyricsWithProvider(mediaMetadata)
                database.query {
                    if (upgradeToWordSynced &&
                        stored != null &&
                        LyricsUtils.hasWordSyncedLyrics(lyricsResult.lyrics)
                    ) {
                        // Replace the row that was inspected, not whatever sits there now: a better
                        // result written while the providers were being queried must survive.
                        upgradeLyricsIfUnchanged(
                            id = mediaMetadata.id,
                            expectedLyrics = stored.lyrics,
                            expectedSource = stored.source,
                            lyrics = lyricsResult.lyrics,
                            providerName = lyricsResult.providerName,
                        )
                    } else {
                        replaceLyricsIfAbsentOrNotFound(
                            id = mediaMetadata.id,
                            lyrics = lyricsResult.lyrics,
                            providerName = lyricsResult.providerName,
                        )
                    }
                }
                if (upgradeToWordSynced &&
                    stored != null &&
                    lyricsResult.lyrics != LyricsEntity.LYRICS_NOT_FOUND
                ) {
                    lyricsHelper.noteWordSyncedUpgradeSettled(mediaMetadata.id, stored.lyrics)
                }
            }
        }

        dataStore.data
            .map { it[SkipSilenceKey] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) {
                localPlayer.skipSilenceEnabled = it
                secondaryCrossfadePlayer?.skipSilenceEnabled = it
            }

        dataStore.data
            .map { it[PauseOnDeviceMuteKey] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) { enabled ->
                pauseOnDeviceMuteEnabled = enabled
                if (!enabled) {
                    wasAutoPausedByDeviceMute = false
                    unregisterMuteRecoveryObserver()
                } else {
                    handleDeviceMuteStateChanged()
                }
            }

        dataStore.data
            .map { (it[DeviceMutePlaybackRecoveryVolumeKey] ?: 0).coerceIn(0, 100) }
            .distinctUntilChanged()
            .collectLatest(scope) { percent ->
                deviceMutePlaybackRecoveryVolumePercent = percent
            }

        dataStore.data
            .map { it[AutoStartOnBluetoothKey] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) { enabled ->
                autoStartOnBluetoothEnabled = enabled
                if (enabled) {
                    registerBluetoothReceiver()
                } else {
                    unregisterBluetoothReceiver()
                }
            }

        combine(
            dataStore.data.map { it[AudioOffload] ?: false },
            dataStore.data.map { it[CrossfadeEnabledKey] ?: false },
            dataStore.data.map { it[AutomixEnabledKey] ?: false },
            dataStore.data.map { it[RejectSuspectedUpscaledAudioKey] ?: false },
        ) { offloadEnabled, crossfadeEnabled, automixEnabled, authenticityRejectionEnabled ->
            listOf(offloadEnabled, crossfadeEnabled, automixEnabled, authenticityRejectionEnabled)
        }.distinctUntilChanged()
            .collectLatest(scope) { (offloadEnabled, crossfadeEnabled, automixEnabled, authenticityRejectionEnabled) ->
                // Offload is incompatible with both blending engines: automix
                // exercises the same two-player + processor-chain path crossfade
                // does, plus its own filter riding.
                val effectiveOffload =
                    resolveAudioOffloadEnabled(
                        requested = offloadEnabled,
                        crossfadeEnabled = crossfadeEnabled,
                        automixEnabled = automixEnabled,
                        authenticityRejectionEnabled = authenticityRejectionEnabled,
                    )
                updateAudioOffload(effectiveOffload)
                if (effectiveOffload) {
                    val skipSilenceEnabled = dataStore.get(SkipSilenceKey, false)
                    if (skipSilenceEnabled) {
                        dataStore.edit { it[SkipSilenceKey] = false }
                        localPlayer.skipSilenceEnabled = false
                    }
                }
            }

        dataStore.data
            .map { prefs ->
                prefs[AutomixPerformanceModeKey]?.let { runCatching { AutomixPerformanceMode.valueOf(it) }.getOrNull() }
                    ?: AutomixPerformanceMode.BALANCED
            }
            .distinctUntilChanged()
            .collectLatest(scope) { mode ->
                SmartFadeSettings.performanceMode.value = mode
            }

        dataStore.data
            .map { it[RejectSuspectedUpscaledAudioKey] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) { enabled ->
                rejectSuspectedUpscaledAudioEnabled = enabled
                if (!enabled) authenticityRejectedSourcesByMediaId.clear()
                if (resolveAudioAuthenticityProbeAvailability(enabled, pcmTapAvailable = !bitPerfectOutputActive) ==
                    AudioAuthenticityProbeAvailability.UNKNOWN
                ) {
                    Timber.tag("PlaybackDiagnostics").w(
                        "Audio authenticity verdict=UNKNOWN reason=bit_perfect_usb_pcm_bypass",
                    )
                }
                audioAuthenticityContextByPlayer.values.forEach { contextRef ->
                    contextRef.get()?.playbackKey?.let { mediaId ->
                        contextRef.set(audioAuthenticityContextFor(mediaId))
                    }
                }
            }

        dataStore.data
            .map { prefs ->
                val enabled = prefs[CrossfadeEnabledKey] ?: false
                val durationSeconds = prefs[CrossfadeDurationKey] ?: 5f
                val gapless = prefs[CrossfadeGaplessKey] ?: true
                val automix = prefs[AutomixEnabledKey] ?: false
                CrossfadeConfig(
                    // Crossfade and automix are mutually exclusive; the settings screen enforces
                    // it and this guards any state written some other way (a restored backup).
                    enabled = enabled && !automix,
                    durationSeconds = durationSeconds,
                    gapless = gapless,
                    automixEnabled = automix,
                )
            }
            // Disable crossfade and automix while in a Listen Together room — the guest
            // side of the sync needs deterministic track starts, and a fade
            // tail delays them (mirrors vivi's gating).
            .combine(listenTogetherManager.roomState) { config, roomState ->
                config.copy(
                    enabled = config.enabled && roomState == null,
                    automixEnabled = config.automixEnabled && roomState == null,
                )
            }
            .distinctUntilChanged()
            .collectLatest(scope) { config ->
                crossfadeEnabled = config.enabled
                crossfadeDurationMs =
                    (config.durationSeconds.coerceIn(0f, 10f) * 1000f)
                        .roundToLong()
                        .coerceAtLeast(0L)
                crossfadeGapless = config.gapless
                smartFadeEnabled = config.automixEnabled
                SmartFadeRuntimeState.enabled.value = smartFadeEnabled
                if (!smartFadeEnabled) {
                    SmartFadeRuntimeState.transitionWindow.value = null
                    SmartFadeRuntimeState.mixing.value = false
                }
                if ((crossfadeEnabled && crossfadeDurationMs > 0L) || smartFadeEnabled) {
                    scheduleCrossfade()
                } else {
                    cancelCrossfade(resetVolume = true, resetPauseAtEnd = true)
                }
            }

        dataStore.data
            .map { it[WakelockKey] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) { enabled ->
                wakelockEnabled = enabled
                updateWakeLock()
            }

        // Initialize lyrics pre-load manager
        lyricsPreloadManager =
            LyricsPreloadManager(
                context = this,
                database = database,
                networkConnectivity = connectivityObserver,
                lyricsHelper = lyricsHelper,
            )

        dataStore.data
            .map(::readEqSettingsFromPrefs)
            .distinctUntilChanged()
            .collectLatest(scope) { settings ->
                desiredEqSettings.value = settings
                applyEqSettingsToEffects(settings)
            }

        combine(
            currentMediaMetadata
                .map { it?.id }
                .distinctUntilChanged(),
            currentFormat,
            dataStore.data
                // Bit-perfect output hands volume to the DAC path; a per-track gain there would
                // just move the user's volume around, so normalization sits out.
                .map { (it[AudioNormalizationKey] ?: true) && !bitPerfectOutputActive }
                .distinctUntilChanged(),
        ) { mediaId, format, normalizeAudio ->
            normalizeAudio to resolveAudioNormalizationFactor(mediaId, format, normalizeAudio)
        }.distinctUntilChanged()
            .collectLatest(scope) { (normalizeAudio, factor) ->
                audioNormalizationEnabled = normalizeAudio
                normalizeFactor.value = factor
            }

        dataStore.data
            .map { it[DiscordTokenKey] to (it[EnableDiscordRPCKey] ?: true) }
            .debounce(300)
            .distinctUntilChanged()
            .collectLatest(scope) { (key, enabled) ->
                requestDiscordSync(
                    reason =
                        when {
                            !enabled -> "discord_rpc_disabled"
                            key.isNullOrBlank() -> "discord_token_missing"
                            else -> "discord_token_or_toggle_changed"
                        },
                    force = !enabled || key.isNullOrBlank(),
                )
                if (!key.isNullOrBlank() && enabled) {
                    if (player.playbackState == Player.STATE_READY && player.playWhenReady) {
                        currentSong.value?.let {
                            ensurePresenceManager()
                        }
                    }
                }
            }

        dataStore.data
            .map { prefs ->
                (prefs[SmartTrimmerKey] ?: false) to (prefs[MaxSongCacheSizeKey] ?: 1024)
            }.debounce(300)
            .distinctUntilChanged()
            .collectLatest(ioScope) { (enabled, maxSongCacheSizeMb) ->
                if (!enabled) return@collectLatest
                if (maxSongCacheSizeMb <= 0 || maxSongCacheSizeMb == -1) return@collectLatest
                val bytesPerMb = 1024L * 1024L
                val safeSizeMb = maxSongCacheSizeMb.toLong().coerceAtMost(Long.MAX_VALUE / bytesPerMb)
                val limitBytes = safeSizeMb * bytesPerMb
                trimPlayerCacheToBytes(limitBytes)
            }

        dataStore.data
            .map { preferences ->
                val serviceConfig = LastFmServiceConfig.fromPreferences(preferences)
                Triple(
                    preferences[EnableLastFMScrobblingKey] ?: false,
                    !preferences[LastFMSessionKey].isNullOrBlank(),
                    serviceConfig.initialized,
                )
            }.debounce(300)
            .distinctUntilChanged()
            .collect(scope) { (enabled, hasSession, serviceConfigured) ->
                val shouldEnable = enabled && hasSession && serviceConfigured
                if (shouldEnable && scrobbleManager == null) {
                    val delayPercent = dataStore.get(ScrobbleDelayPercentKey, LastFM.DEFAULT_SCROBBLE_DELAY_PERCENT)
                    val minSongDuration = dataStore.get(ScrobbleMinSongDurationKey, LastFM.DEFAULT_SCROBBLE_MIN_SONG_DURATION)
                    val delaySeconds = dataStore.get(ScrobbleDelaySecondsKey, LastFM.DEFAULT_SCROBBLE_DELAY_SECONDS)

                    scrobbleManager =
                        moe.rukamori.archivetune.utils.ScrobbleManager(
                            ioScope,
                            minSongDuration = minSongDuration,
                            scrobbleDelayPercent = delayPercent,
                            scrobbleDelaySeconds = delaySeconds,
                        )
                    scrobbleManager?.useNowPlaying = dataStore.get(LastFMUseNowPlaying, false)
                } else if (!shouldEnable && scrobbleManager != null) {
                    scrobbleManager?.destroy()
                    scrobbleManager = null
                }
            }

        dataStore.data
            .map { it[LastFMUseNowPlaying] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) {
                scrobbleManager?.useNowPlaying = it
            }

        dataStore.data
            .map { prefs ->
                Triple(
                    prefs[ScrobbleDelayPercentKey] ?: LastFM.DEFAULT_SCROBBLE_DELAY_PERCENT,
                    prefs[ScrobbleMinSongDurationKey] ?: LastFM.DEFAULT_SCROBBLE_MIN_SONG_DURATION,
                    prefs[ScrobbleDelaySecondsKey] ?: LastFM.DEFAULT_SCROBBLE_DELAY_SECONDS,
                )
            }.distinctUntilChanged()
            .collect(scope) { (delayPercent, minSongDuration, delaySeconds) ->
                scrobbleManager?.let {
                    it.scrobbleDelayPercent = delayPercent
                    it.minSongDuration = minSongDuration
                    it.scrobbleDelaySeconds = delaySeconds
                }
            }

        scope.launch(Dispatchers.IO) {
            runCatching {
                if (dataStore.get(PersistentQueueKey, true)) {
                    playerInitialized.first { it }
                    val persistedQueue = readPersistentObject<PersistQueue>(PERSISTENT_QUEUE_FILE)
                    val persistedPlayerState = readPersistentObject<PersistPlayerState>(PERSISTENT_PLAYER_STATE_FILE)

                    if (persistedQueue != null || persistedPlayerState != null) {
                        isRestoringPersistentState = true
                    }

                    var restoredQueue = false
                    try {
                        persistedQueue?.let { queue ->
                            restorePersistentQueue(queue)
                            restoredQueue = true
                        }
                        persistedPlayerState?.let { playerState ->
                            restorePersistentPlayerState(playerState, restoredQueue)
                        }
                    } finally {
                        isRestoringPersistentState = false
                    }
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                Timber.tag(TAG).w(error, "Failed to restore persisted queue, clearing data")
                isRestoringPersistentState = false
                cancelRestoredQueueHydration()
                clearPersistedQueueFiles()
            }
            withContext(Dispatchers.Main) {
                queueRestoreCompleted.value = true
            }
        }

        // Periodic save exists only to keep the resume *position* roughly current for a process
        // death; every change to the queue itself already calls saveQueueToDisk from the eight
        // event handlers that cause one. So this writes the small player-state file only — a
        // full-queue snapshot would have to map every media item on the main thread, for a queue
        // that has usually not changed.
        scope.launch {
            while (isActive) {
                delay(PERSISTENT_POSITION_SAVE_INTERVAL)
                val shouldSave = withContext(Dispatchers.IO) { dataStore.get(PersistentQueueKey, true) }
                if (shouldSave && player.mediaItemCount > 0) {
                    savePlayerStateToDisk()
                }
            }
        }
    }

    private fun ensureScopesActive() {
        if (!scopeJob.isActive) {
            scopeJob = SupervisorJob()
        }
        if (!scope.isActive) {
            scope = CoroutineScope(Dispatchers.Main + scopeJob)
        }
        if (!ioScope.isActive) {
            ioScope = CoroutineScope(Dispatchers.IO + scopeJob)
        }
        startDiscordSyncWorker()
    }

    private fun startDiscordSyncWorker() {
        if (discordSyncWorkerJob?.isActive == true) return
        discordSyncWorkerJob =
            scope.launch(Dispatchers.IO) {
                for (request in discordSyncRequests) {
                    try {
                        syncDiscordStateInternal(request)
                    } catch (_: StaleDiscordSyncException) {
                        Timber.tag(DISCORD_SYNC_TAG).d(
                            "stale sync aborted epoch=%d reason=%s",
                            request.epoch,
                            request.reason,
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Timber.tag(DISCORD_SYNC_TAG).e(
                            error,
                            "sync failed epoch=%d reason=%s",
                            request.epoch,
                            request.reason,
                        )
                    }
                }
            }
    }

    private fun requestDiscordSync(
        reason: String,
        force: Boolean = false,
    ) {
        if (!DiscordSocialPresenceClient.isAvailable) return
        val request =
            DiscordSyncRequest(
                epoch = discordSyncEpoch.incrementAndGet(),
                reason = reason,
                force = force,
            )
        if (discordSyncRequests.trySend(request).isFailure) {
            Timber.tag(DISCORD_SYNC_TAG).w(
                "failed to enqueue sync epoch=%d reason=%s",
                request.epoch,
                request.reason,
            )
        }
    }

    fun forceDiscordSync(reason: String) {
        requestDiscordSync(
            reason = reason,
            force = true,
        )
    }

    private fun ensureDiscordSyncFresh(epoch: Long) {
        if (epoch != discordSyncEpoch.get()) {
            throw StaleDiscordSyncException()
        }
    }

    private fun updateActiveDiscordHoldState(nextHoldState: ActiveHoldState?) {
        val previousHoldState = activeDiscordHoldState
        activeDiscordHoldState = nextHoldState
        Timber.tag(DISCORD_SYNC_TAG).d(
            "hold state transition previous=%s next=%s",
            previousHoldState,
            nextHoldState,
        )
        reconcileDiscordHoldTimeoutJob(previousHoldState, nextHoldState)
    }

    private fun reconcileDiscordHoldTimeoutJob(
        previousHoldState: ActiveHoldState?,
        nextHoldState: ActiveHoldState?,
    ) {
        if (previousHoldState === nextHoldState) {
            Timber.tag(DISCORD_SYNC_TAG).v("hold timeout job unchanged for holdState=%s", nextHoldState)
            return
        }

        activeDiscordHoldTimeoutJob?.cancel()
        activeDiscordHoldTimeoutJob = null

        if (nextHoldState == null) {
            Timber.tag(DISCORD_SYNC_TAG).d("no active hold state, no timeout job scheduled")
            return
        }

        Timber.tag(DISCORD_SYNC_TAG).d(
            "scheduling hold timeout job state=%s timeoutMs=%d",
            nextHoldState,
            DISCORD_HOLD_TIMEOUT_MS,
        )
        activeDiscordHoldTimeoutJob =
            scope.launch {
                delay(DISCORD_HOLD_TIMEOUT_MS)
                Timber.tag(DISCORD_SYNC_TAG).d(
                    "hold timeout fired state=%s -> enqueue resync",
                    nextHoldState,
                )
                requestDiscordSync(
                    reason = "hold_timeout_check",
                    force = true,
                )
            }
    }

    private fun clearDiscordHoldState() {
        if (activeDiscordHoldState != null) {
            Timber.tag(DISCORD_SYNC_TAG).d("clearing active hold state=%s", activeDiscordHoldState)
        }
        updateActiveDiscordHoldState(null)
    }

    private fun markLastAppliedVisiblePresence(visibleDecision: DiscordPresenceDecision.Visible) {
        lastAppliedVisiblePresence =
            LastAppliedVisiblePresence(
                songId = visibleDecision.songId,
                mode = visibleDecision.mode,
                appliedAtMs = System.currentTimeMillis(),
            )
        Timber.tag(DISCORD_SYNC_TAG).d(
            "marked last applied visible presence songId=%s mode=%s",
            visibleDecision.songId,
            visibleDecision.mode,
        )
    }

    private suspend fun addPendingDiscordRefreshWaiter(waiter: CompletableDeferred<Boolean>) {
        discordRefreshWaitersMutex.withLock {
            pendingDiscordRefreshWaiters += waiter
        }
    }

    private suspend fun takePendingDiscordRefreshWaiters(): List<CompletableDeferred<Boolean>> =
        discordRefreshWaitersMutex.withLock {
            val snapshot = pendingDiscordRefreshWaiters.toList()
            pendingDiscordRefreshWaiters.removeAll(snapshot)
            snapshot
        }

    private suspend fun requeueDiscordRefreshWaiters(waiters: List<CompletableDeferred<Boolean>>) {
        if (waiters.isEmpty()) return
        discordRefreshWaitersMutex.withLock {
            waiters.forEach { waiter ->
                if (!waiter.isCompleted && !waiter.isCancelled) {
                    pendingDiscordRefreshWaiters += waiter
                }
            }
        }
    }

    private fun completeDiscordRefreshWaiters(
        waiters: List<CompletableDeferred<Boolean>>,
        result: Boolean,
    ) {
        waiters.forEach { waiter ->
            if (!waiter.isCompleted && !waiter.isCancelled) {
                waiter.complete(result)
            }
        }
    }

    suspend fun refreshDiscordNow(): Boolean {
        if (!DiscordSocialPresenceClient.isAvailable) return false
        val waiter = CompletableDeferred<Boolean>()
        addPendingDiscordRefreshWaiter(waiter)
        requestDiscordSync(
            reason = "manual_refresh",
            force = true,
        )
        return try {
            withTimeout(15_000L) { waiter.await() }
        } catch (error: CancellationException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun syncDiscordStateInternal(request: DiscordSyncRequest) {
        val refreshWaiters = takePendingDiscordRefreshWaiters()
        if (!DiscordSocialPresenceClient.isAvailable) {
            completeDiscordRefreshWaiters(refreshWaiters, false)
            return
        }
        try {
            ensureDiscordSyncFresh(request.epoch)

            val enabled = dataStore.get(EnableDiscordRPCKey, true)
            val token = dataStore.get(DiscordTokenKey, "")
            val hasToken = token.isNotBlank()
            val showWhenPaused = dataStore.get(DiscordShowWhenPausedKey, false)
            val (song, isPlaying, playWhenReady, playbackState) =
                withContext(Dispatchers.Main.immediate) {
                    Quadruple(
                        currentPresenceSong(),
                        player.isPlaying,
                        player.playWhenReady,
                        player.playbackState,
                    )
                }

            if (playWhenReady && pausedPresenceGate != PausedPresenceGate.FollowPreference) {
                pausedPresenceGate = PausedPresenceGate.FollowPreference
                Timber.tag(DISCORD_SYNC_TAG).d(
                    "sync epoch=%d reason=%s reset paused gate because playback intent resumed",
                    request.epoch,
                    request.reason,
                )
            }

            val inputs =
                DiscordPresenceInputs(
                    enabled = enabled,
                    hasToken = hasToken,
                    song = song,
                    isPlaying = isPlaying,
                    showWhenPaused = showWhenPaused,
                    pausedPresenceGate = pausedPresenceGate,
                    serviceStopping = discordServiceStopping,
                    playWhenReady = playWhenReady,
                    playbackState = playbackState,
                )
            val holdContext =
                DiscordHoldContext(
                    nowMs = System.currentTimeMillis(),
                    activeHoldState = activeDiscordHoldState,
                    lastAppliedVisiblePresence = lastAppliedVisiblePresence,
                    holdTimeoutMs = DISCORD_HOLD_TIMEOUT_MS,
                )
            val semanticState = derivePlaybackSemanticState(inputs)
            val rawDecision = deriveRawDiscordPresenceDecision(inputs, semanticState)
            val resolution = resolveDiscordPresenceDecision(rawDecision, holdContext)

            val decision = resolution.decision
            ensureDiscordSyncFresh(request.epoch)

            val effectiveForce = request.force || refreshWaiters.isNotEmpty()
            if (!effectiveForce && decision == lastDiscordPresenceDecision) {
                Timber.tag(DISCORD_SYNC_TAG).v(
                    "sync epoch=%d reason=%s unchanged decision=%s",
                    request.epoch,
                    request.reason,
                    decision,
                )
                completeDiscordRefreshWaiters(refreshWaiters, true)
                return
            }

            Timber.tag(DISCORD_SYNC_TAG).d(
                "sync epoch=%d reason=%s force=%s effectiveForce=%s songId=%s playWhenReady=%s playbackState=%d isPlaying=%s semantic=%s raw=%s decision=%s holdState=%s lastAppliedVisible=%s refreshWaiters=%d",
                request.epoch,
                request.reason,
                request.force,
                effectiveForce,
                song?.song?.id,
                playWhenReady,
                playbackState,
                isPlaying,
                semanticState,
                rawDecision,
                decision,
                resolution.nextHoldState,
                lastAppliedVisiblePresence,
                refreshWaiters.size,
            )

            val applied =
                applyDiscordPresenceDecision(
                    request = request,
                    resolution = resolution,
                    token = token,
                    song = song,
                )

            if (applied) {
                lastDiscordPresenceDecision = decision
            }
            if (decision is DiscordPresenceDecision.Hold) {
                requeueDiscordRefreshWaiters(refreshWaiters)
                Timber.tag(DISCORD_SYNC_TAG).d(
                    "refresh waiters requeued because decision is Hold count=%d",
                    refreshWaiters.size,
                )
            } else {
                completeDiscordRefreshWaiters(refreshWaiters, applied)
            }
        } catch (_: StaleDiscordSyncException) {
            requeueDiscordRefreshWaiters(refreshWaiters)
            Timber.tag(DISCORD_SYNC_TAG).d(
                "stale sync aborted epoch=%d reason=%s and refresh waiters requeued=%d",
                request.epoch,
                request.reason,
                refreshWaiters.size,
            )
        } catch (error: CancellationException) {
            completeDiscordRefreshWaiters(refreshWaiters, false)
            throw error
        } catch (error: Exception) {
            Timber.tag(DISCORD_SYNC_TAG).e(error, "syncDiscordStateInternal failed epoch=%d reason=%s", request.epoch, request.reason)
            completeDiscordRefreshWaiters(refreshWaiters, false)
            throw error
        }
    }

    private suspend fun applyDiscordPresenceDecision(
        request: DiscordSyncRequest,
        resolution: DiscordPresenceResolution,
        token: String,
        song: Song?,
    ): Boolean {
        ensureDiscordSyncFresh(request.epoch)

        val decision = resolution.decision
        Timber.tag(DISCORD_SYNC_TAG).d(
            "apply decision epoch=%d decision=%s tokenPresent=%s songId=%s",
            request.epoch,
            decision,
            token.isNotBlank() || !lastPresenceToken.isNullOrBlank(),
            song?.song?.id,
        )
        return when (decision) {
            is DiscordPresenceDecision.Hidden -> {
                clearDiscordHoldState()
                when (decision.reason) {
                    HiddenReason.NoSong,
                    HiddenReason.PausedByPreference,
                    HiddenReason.PausedByNotificationDismiss,
                    HiddenReason.NoStablePlaybackYet,
                    HiddenReason.PlaybackStalled,
                    -> {
                        ensureDiscordSyncFresh(request.epoch)
                        val cleared =
                            DiscordPresenceManager.clearNow(
                                context = this@MusicService,
                                token = token.takeIf { it.isNotBlank() } ?: lastPresenceToken,
                            )
                        if (!cleared) {
                            Timber.tag(DISCORD_SYNC_TAG).d(
                                "clear skipped or failed for hidden reason=%s",
                                decision.reason,
                            )
                        }
                        cleared
                    }

                    HiddenReason.Disabled -> {
                        ensureDiscordSyncFresh(request.epoch)
                        DiscordPresenceManager.stop(clearActivity = false)
                        lastPresenceToken = null
                        true
                    }

                    HiddenReason.ServiceStopping -> {
                        val clearToken = token.takeIf { it.isNotBlank() } ?: lastPresenceToken
                        ensureDiscordSyncFresh(request.epoch)
                        val cleared =
                            DiscordPresenceManager.clearNow(
                                context = this@MusicService,
                                token = clearToken,
                            )
                        if (!cleared) {
                            Timber.tag(DISCORD_SYNC_TAG).d(
                                "terminal clear skipped or failed for hidden reason=%s",
                                decision.reason,
                            )
                        }
                        ensureDiscordSyncFresh(request.epoch)
                        DiscordPresenceManager.stop()
                        lastPresenceToken = null
                        true
                    }

                    HiddenReason.NoToken -> {
                        val clearToken = token.takeIf { it.isNotBlank() } ?: lastPresenceToken
                        ensureDiscordSyncFresh(request.epoch)
                        if (clearToken.isNullOrBlank()) {
                            Timber.tag(DISCORD_SYNC_TAG).v(
                                "no token available for terminal clear; stopping manager only",
                            )
                        } else {
                            val cleared =
                                DiscordPresenceManager.clearNow(
                                    context = this@MusicService,
                                    token = clearToken,
                                )
                            if (!cleared) {
                                Timber.tag(DISCORD_SYNC_TAG).d(
                                    "terminal clear skipped or failed for hidden reason=%s",
                                    decision.reason,
                                )
                            }
                        }
                        ensureDiscordSyncFresh(request.epoch)
                        DiscordPresenceManager.stop()
                        lastPresenceToken = null
                        true
                    }
                }
            }

            is DiscordPresenceDecision.Visible -> {
                clearDiscordHoldState()
                ensureDiscordSyncFresh(request.epoch)
                val snapshot = buildDiscordPresenceSnapshot(song, decision.isPaused) ?: return false
                ensureDiscordSyncFresh(request.epoch)
                val updated =
                    DiscordPresenceManager.updateNow(
                        context = this@MusicService,
                        token = token,
                        song = snapshot.song,
                        positionMs = snapshot.positionMs,
                        isPaused = snapshot.isPaused,
                        isMusicVideo = currentMediaMetadata.value?.isMusicVideo ?: false,
                    )
                if (!updated) {
                    Timber.tag(DISCORD_SYNC_TAG).d(
                        "visible update failed songId=%s paused=%s",
                        decision.songId,
                        decision.isPaused,
                    )
                    false
                } else {
                    if (token.isNotBlank()) {
                        lastPresenceToken = token
                    }
                    markLastAppliedVisiblePresence(decision)
                    true
                }
            }

            is DiscordPresenceDecision.Hold -> {
                updateActiveDiscordHoldState(resolution.nextHoldState)
                true
            }
        }
    }

    private suspend fun buildDiscordPresenceSnapshot(
        song: Song?,
        isPaused: Boolean,
    ): DiscordPresenceSnapshot? {
        val resolvedSong = song ?: return null
        val positionMs = withContext(Dispatchers.Main.immediate) { player.currentPosition }
        return DiscordPresenceSnapshot(
            song = resolvedSong,
            positionMs = positionMs,
            isPaused = isPaused,
        )
    }

    private fun cancelRestoredQueueHydration() {
        restoredQueueHydrationGeneration.incrementAndGet()
        restoredQueueBackfillJob?.cancel()
        restoredQueueBackfillJob = null
        isHydratingRestoredQueue = false
    }

    private suspend fun Queue.Status.filterPlaybackContent(
        hideExplicit: Boolean,
        hideVideo: Boolean,
    ): Queue.Status =
        filterExplicit(hideExplicit)
            .filterVideo(hideVideo)
            .filterBlockedArtists(loadBlockedArtistIds())

    private suspend fun List<MediaItem>.filterPlaybackContent(
        hideExplicit: Boolean,
        hideVideo: Boolean,
    ): List<MediaItem> =
        filterExplicit(hideExplicit)
            .filterVideo(hideVideo)
            .filterBlockedArtists(loadBlockedArtistIds())

    private suspend fun loadBlockedArtistIds(): Set<String> =
        withContext(Dispatchers.IO) {
            database.getBlockedArtistIds().toSet()
        }

    private fun removeBlockedArtistItems(updatedBlockedArtistIds: Set<String>) {
        if (updatedBlockedArtistIds.isEmpty() || player.mediaItemCount == 0) return

        removeQueueItems { item -> item.hasBlockedArtist(updatedBlockedArtistIds) }
    }

    private fun removeMusicVideoItems() {
        removeQueueItems { item -> item.metadata?.isMusicVideo == true }
    }

    private inline fun removeQueueItems(shouldRemove: (MediaItem) -> Boolean) {
        if (player.mediaItemCount == 0) return

        var blockedRangeEnd = C.INDEX_UNSET
        for (index in player.mediaItemCount - 1 downTo 0) {
            val item = player.getMediaItemAt(index)
            if (shouldRemove(item)) {
                autoAddedMediaIds.remove(item.mediaId)
                if (blockedRangeEnd == C.INDEX_UNSET) {
                    blockedRangeEnd = index + 1
                }
            } else if (blockedRangeEnd != C.INDEX_UNSET) {
                player.removeMediaItems(index + 1, blockedRangeEnd)
                blockedRangeEnd = C.INDEX_UNSET
            }
        }
        if (blockedRangeEnd != C.INDEX_UNSET) {
            player.removeMediaItems(0, blockedRangeEnd)
        }
        if (player.mediaItemCount == 0) {
            cancelInfiniteQueueBootstrap()
            currentQueue = EmptyQueue
            queueTitle = null
        }
    }

    private suspend fun restorePersistentQueue(persistedQueue: PersistQueue) {
        cancelRestoredQueueHydration()
        val hydrationGeneration = restoredQueueHydrationGeneration.incrementAndGet()
        isHydratingRestoredQueue = true

        val itemQueue = persistedQueue.toQueue()
        val continuationQueue = persistedQueue.toContinuationQueue()
        val hideExplicit = dataStore.get(HideExplicitKey, false)
        val hideVideo = dataStore.get(HideVideoKey, false)
        val initialStatus =
            itemQueue
                .getInitialStatus()
                .filterPlaybackContent(hideExplicit, hideVideo)

        withContext(Dispatchers.Main) {
            currentQueue = continuationQueue
            queueTitle = initialStatus.title

            val items = initialStatus.items
            if (items.isEmpty()) {
                if (hydrationGeneration == restoredQueueHydrationGeneration.get()) {
                    isHydratingRestoredQueue = false
                }
                return@withContext
            }

            val fullIndex = initialStatus.mediaItemIndex.coerceIn(0, items.lastIndex)
            val windowStart = (fullIndex - 20).coerceAtLeast(0)
            val windowEnd = (fullIndex + 50).coerceAtMost(items.size)

            val initialChunk = items.subList(windowStart, windowEnd)
            val relativeIndex = (fullIndex - windowStart).coerceIn(0, initialChunk.lastIndex)

            player.setMediaItems(
                initialChunk,
                relativeIndex,
                initialStatus.position,
            )
            player.prepare()
            player.playWhenReady = false
            currentMediaMetadata.value = player.currentMetadata
            updateNotification()

            if (items.size > initialChunk.size) {
                restoredQueueBackfillJob =
                    scope.launch(SilentHandler) {
                        try {
                            delay(2000)
                            if (!isActive || player.mediaItemCount == 0) return@launch
                            if (windowStart > 0) {
                                player.addMediaItems(0, items.subList(0, windowStart))
                            }
                            if (windowEnd < items.size) {
                                player.addMediaItems(items.subList(windowEnd, items.size))
                            }
                        } finally {
                            if (hydrationGeneration == restoredQueueHydrationGeneration.get()) {
                                isHydratingRestoredQueue = false
                                restoredQueueBackfillJob = null
                                if (isActive && dataStore.get(PersistentQueueKey, true) && player.mediaItemCount > 0) {
                                    saveQueueToDisk()
                                }
                            }
                        }
                    }
            } else {
                if (hydrationGeneration == restoredQueueHydrationGeneration.get()) {
                    isHydratingRestoredQueue = false
                }
            }
        }
    }

    private suspend fun restorePersistentPlayerState(
        playerState: PersistPlayerState,
        restoredQueue: Boolean,
    ) {
        withContext(Dispatchers.Main) {
            player.repeatMode = playerState.repeatMode
            player.shuffleModeEnabled = playerState.shuffleModeEnabled
            playerVolume.value = playerState.volume.coerceIn(0f, 1f)

            if (player.mediaItemCount > 0) {
                val index =
                    when {
                        restoredQueue -> {
                            player.currentMediaItemIndex.coerceIn(0, player.mediaItemCount - 1)
                        }

                        playerState.currentMediaItemIndex in 0 until player.mediaItemCount -> {
                            playerState.currentMediaItemIndex
                        }

                        else -> {
                            player.currentMediaItemIndex.coerceIn(0, player.mediaItemCount - 1)
                        }
                    }
                player.seekTo(index, playerState.currentPosition.coerceAtLeast(0L))
            }

            player.playWhenReady = false
            abandonAudioFocus()

            currentMediaMetadata.value = player.currentMetadata.takeIf { player.mediaItemCount > 0 }
            updateNotification()
        }
    }

    private fun ensurePresenceManager() {
        if (!DiscordSocialPresenceClient.isAvailable) return
        if (DiscordPresenceManager.isRunning() && lastPresenceToken != null) return

        // Launch in scope to avoid blocking
        scope.launch {
            // Don't start if Discord RPC is disabled in settings
            if (!dataStore.get(EnableDiscordRPCKey, true)) {
                if (DiscordPresenceManager.isRunning()) {
                    Timber.tag("MusicService").d("Discord RPC disabled → stopping presence manager")
                    try {
                        DiscordPresenceManager.stop()
                    } catch (_: Exception) {
                    }
                    lastPresenceToken = null
                }
                return@launch
            }

            val key: String = dataStore.get(DiscordTokenKey, "")
            if (key.isNullOrBlank()) {
                if (DiscordPresenceManager.isRunning()) {
                    Timber.tag("MusicService").d("No Discord OAuth session -> stopping presence manager")
                    try {
                        DiscordPresenceManager.stop()
                    } catch (_: Exception) {
                    }
                    lastPresenceToken = null
                }
                return@launch
            }

            if (DiscordPresenceManager.isRunning() && lastPresenceToken == key) {
                return@launch
            }

            try {
                DiscordPresenceManager.stop()
                DiscordPresenceManager.start(
                    context = this@MusicService,
                    token = key,
                )
                DiscordPresenceManager.setOnTransportInvalidated { reason ->
                    Timber.tag(DISCORD_SYNC_TAG).w(
                        "transport invalidated reason=%s; requesting forced sync",
                        reason,
                    )
                    requestDiscordSync(
                        reason = "transport_invalidated:$reason",
                        force = true,
                    )
                }
                Timber.tag("MusicService").d("Presence manager started")
                lastPresenceToken = key
                requestDiscordSync(
                    reason = "presence_manager_started",
                    force = true,
                )
            } catch (ex: Exception) {
                Timber.tag("MusicService").e(ex, "Failed to start presence manager")
            }
        }
    }

    private fun setupAudioFocusRequest() {
        audioFocusRequest =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    android.media.AudioAttributes
                        .Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                ).setOnAudioFocusChangeListener { focusChange ->
                    handleAudioFocusChange(focusChange)
                }.setAcceptsDelayedFocusGain(true)
                .build()
    }

    private fun onAudioOutputDeviceChanged() {
        if (!::player.isInitialized) return
        val outputSignature = currentAudioOutputDeviceSignature()
        if (outputSignature == lastAudioOutputDeviceSignature) return
        lastAudioOutputDeviceSignature = outputSignature
        audioOutputResolver.refresh()
        cancelCrossfade(resetVolume = true, resetPauseAtEnd = true)
        player.setAudioAttributes(playbackAudioAttributes(), false)
        audioRouteRecoveryJob?.cancel()
        audioRouteRecoveryJob =
            scope.launch {
                delay(AUDIO_ROUTE_CHANGE_DEBOUNCE_MS)
                recoverAudioRouteAfterDeviceChange()
            }
    }

    private suspend fun recoverAudioRouteAfterDeviceChange() {
        if (!::player.isInitialized) return

        rebindAudioEffectsAfterRouteChange()

        if (!shouldRebuildPlaybackForAudioRouteChange()) return

        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastAudioRouteRecoveryRealtimeMs < AUDIO_ROUTE_RECOVERY_MIN_INTERVAL_MS) return
        lastAudioRouteRecoveryRealtimeMs = now

        val mediaItemIndex = player.currentMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: return
        val playbackPosition = player.currentPosition.coerceAtLeast(0L)
        val shouldResumePlayback = player.playWhenReady

        Timber.tag("MusicService").i(
            "Recovering audio route after output change at index=$mediaItemIndex position=$playbackPosition resume=$shouldResumePlayback",
        )

        if (shouldResumePlayback && !requestAudioFocus()) {
            wasPlayingBeforeAudioFocusLoss = true
            player.playWhenReady = false
            return
        }

        player.playWhenReady = false
        player.prepare()
        player.seekTo(mediaItemIndex, playbackPosition)
        delay(AUDIO_ROUTE_RECOVERY_RESUME_DELAY_MS)

        if (
            shouldResumePlayback &&
            player.currentMediaItem != null &&
            player.playbackState != Player.STATE_ENDED &&
            requestAudioFocus()
        ) {
            player.playWhenReady = true
        }
    }

    private suspend fun rebindAudioEffectsAfterRouteChange() {
        if (!isAudioEffectSessionOpened) return
        closeAudioEffectSession()
        if (!player.playWhenReady) return
        delay(AUDIO_EFFECT_ROUTE_REBIND_DELAY_MS)
        openAudioEffectSession()
    }

    private fun shouldRebuildPlaybackForAudioRouteChange(): Boolean {
        if (player.currentMediaItem == null) return false
        if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) return false
        return player.playWhenReady || player.playbackState == Player.STATE_BUFFERING
    }

    private fun currentAudioOutputDeviceSignature(): String =
        runCatching {
            audioManager
                .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .asSequence()
                .filter { it.isSink }
                .sortedWith(
                    compareBy<AudioDeviceInfo>(
                        { it.type },
                        { it.id },
                        { it.productName?.toString().orEmpty() },
                    ),
                ).joinToString(separator = "|") { device ->
                    "${device.type}:${device.id}:${device.productName?.toString().orEmpty()}"
                }
        }.getOrDefault("")

    private fun playbackAudioAttributes(): AudioAttributes =
        AudioAttributes
            .Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setAllowedCapturePolicy(C.ALLOW_CAPTURE_BY_ALL)
            .build()

    private fun calculateEffectivePlayerVolume(
        playerVolume: Float,
        normalizeFactor: Float,
        audioFocusVolumeFactor: Float,
    ): Float {
        val safePlayerVolume = playerVolume.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
        val safeNormalizeFactor =
            normalizeFactor.takeIf { it.isFinite() }?.coerceIn(MIN_AUDIO_NORMALIZATION_FACTOR, MAX_AUDIO_NORMALIZATION_FACTOR) ?: 1f
        val safeAudioFocusVolumeFactor =
            audioFocusVolumeFactor.takeIf { it.isFinite() }?.coerceIn(MIN_AUDIO_FOCUS_VOLUME_FACTOR, 1f) ?: 1f
        return (safePlayerVolume * safeNormalizeFactor * safeAudioFocusVolumeFactor).coerceIn(0f, maxSafeGainFactor)
    }

    private fun currentEffectivePlayerVolume(): Float =
        calculateEffectivePlayerVolume(playerVolume.value, normalizeFactor.value, audioFocusVolumeFactor.value)

    private fun currentEffectivePlayerVolumeForMediaId(mediaId: String): Float {
        val targetNormalizeFactor =
            if (audioNormalizationEnabled) {
                audioNormalizationFactorCache[mediaId] ?: 1f
            } else {
                1f
            }
        return calculateEffectivePlayerVolume(playerVolume.value, targetNormalizeFactor, audioFocusVolumeFactor.value)
    }

    private fun updateEffectiveVolume(finalVolume: Float) {
        if (!::player.isInitialized || !shouldRampEffectiveVolume(finalVolume)) {
            applyEffectiveVolumeImmediately(finalVolume)
            return
        }

        val startVolume = player.volume.takeIf { it.isFinite() }?.coerceIn(0f, maxSafeGainFactor) ?: finalVolume
        val targetVolume = finalVolume.coerceIn(0f, maxSafeGainFactor)
        if (abs(targetVolume - startVolume) <= EFFECTIVE_VOLUME_RAMP_MIN_DELTA) {
            applyEffectiveVolumeImmediately(targetVolume)
            return
        }

        effectiveVolumeRampJob?.cancel()
        effectiveVolumeRampJob =
            scope.launch {
                val durationMs =
                    if (targetVolume > startVolume) {
                        EFFECTIVE_VOLUME_RAMP_UP_MS
                    } else {
                        EFFECTIVE_VOLUME_RAMP_DOWN_MS
                    }
                val startedAtMs = android.os.SystemClock.elapsedRealtime()
                while (isActive) {
                    val elapsedMs = android.os.SystemClock.elapsedRealtime() - startedAtMs
                    val progress = (elapsedMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                    val easedProgress = progress * progress * (3f - (2f * progress))
                    val interpolatedVolume = startVolume + ((targetVolume - startVolume) * easedProgress)
                    applyEffectiveVolume(interpolatedVolume)
                    if (progress >= 1f) break
                    delay(EFFECTIVE_VOLUME_RAMP_FRAME_MS)
                }
                applyEffectiveVolume(targetVolume)
                effectiveVolumeRampJob = null
            }
    }

    private fun shouldRampEffectiveVolume(finalVolume: Float): Boolean {
        if (isCrossfading || crossfadeHandoffInProgress) return false
        if (!shouldKeepPlaybackAudible()) return false
        if (!finalVolume.isFinite()) return false
        if (player.volume <= STUCK_MUTED_VOLUME_EPSILON) return false
        return true
    }

    private fun applyEffectiveVolumeImmediately(finalVolume: Float = currentEffectivePlayerVolume()) {
        effectiveVolumeRampJob?.cancel()
        effectiveVolumeRampJob = null
        applyEffectiveVolume(finalVolume)
    }

    private fun applyEffectiveVolume(finalVolume: Float = currentEffectivePlayerVolume()) {
        crossfadeBaseVolume = finalVolume
        val incomingPlayer = secondaryCrossfadePlayer
        if (crossfadeHandoffInProgress && incomingPlayer != null) {
            val handoffBaseVolume =
                secondaryCrossfadeTarget?.let { currentEffectivePlayerVolumeForMediaId(it.mediaId) }
                    ?: finalVolume
            crossfadeIncomingBaseVolume = handoffBaseVolume
            applyCrossfadeVolumes(
                crossfadeHandoffProgress,
                handoffBaseVolume,
                handoffBaseVolume,
                incomingPlayer,
                localPlayer,
            )
            return
        }
        if (isCrossfading && incomingPlayer != null) {
            val incomingBaseVolume =
                secondaryCrossfadeTarget?.let { currentEffectivePlayerVolumeForMediaId(it.mediaId) }
                    ?: finalVolume
            crossfadeIncomingBaseVolume = incomingBaseVolume
            applyCrossfadeVolumes(crossfadeProgress, finalVolume, incomingBaseVolume, localPlayer, incomingPlayer)
            return
        }
        if (::player.isInitialized) {
            player.volume = finalVolume
        }
        incomingPlayer?.volume = 0f
    }

    private fun ensureAudiblePlaybackVolume(reason: String) {
        if (!::player.isInitialized) return
        if (isCrossfading || crossfadeHandoffInProgress) return
        if (!shouldKeepPlaybackAudible()) return
        if (playerVolume.value <= 0f) return

        val expectedVolume = currentEffectivePlayerVolume()
        if (expectedVolume <= MIN_AUDIBLE_EFFECTIVE_VOLUME) return
        if (player.volume > STUCK_MUTED_VOLUME_EPSILON) return

        Timber.tag("PlaybackDiagnostics").w(
            "Restoring muted primary player volume reason=%s expectedGain=%.3f actualGain=%.3f focusHeld=%s",
            reason,
            expectedVolume,
            player.volume,
            hasAudioFocus,
        )
        applyEffectiveVolumeImmediately(expectedVolume)
    }

    private fun updateAudiblePlaybackRecovery() {
        if (!::player.isInitialized || !shouldKeepPlaybackAudible()) {
            audiblePlaybackRecoveryJob?.cancel()
            audiblePlaybackRecoveryJob = null
            return
        }

        if (audiblePlaybackRecoveryJob?.isActive == true) return
        audiblePlaybackRecoveryJob =
            scope.launch {
                while (isActive && shouldKeepPlaybackAudible()) {
                    ensureAudiblePlaybackVolume("watchdog")
                    delay(AUDIBLE_PLAYBACK_VOLUME_CHECK_MS)
                }
                audiblePlaybackRecoveryJob = null
            }
    }

    private fun applyCrossfadeVolumes(
        progress: Float,
        outgoingBaseVolume: Float,
        incomingBaseVolume: Float,
        outgoingPlayer: ExoPlayer,
        incomingPlayer: ExoPlayer,
    ) {
        outgoingPlayer.volume = CrossfadePolicy.outgoingVolume(progress, outgoingBaseVolume, maxSafeGainFactor)
        incomingPlayer.volume = CrossfadePolicy.incomingVolume(progress, incomingBaseVolume, maxSafeGainFactor)
    }

    /** Crossfade is a local dual-player technique; it must not run while casting remotely. */
    private fun isCastSessionConnected(): Boolean {
        val state = castPlaybackRepository.screenState.value
        return (state as? CastScreenState.Success)?.uiState?.isConnected == true
    }

    fun pauseFromSleepTimer() {
        sleepTimer.clear()
        crossfadeTriggerJob?.cancel()
        crossfadeTriggerJob = null
        cancelCrossfade(resetVolume = true, resetPauseAtEnd = true)
        releaseSecondaryCrossfadePlayer()
        player.pause()
        player.playWhenReady = false
        localPlayer.pause()
        localPlayer.playWhenReady = false
    }

    private fun scheduleCrossfade() {
        if (!::player.isInitialized) return
        crossfadeTriggerJob?.cancel()
        crossfadeTriggerJob = null

        if (isCrossfading) return
        if (sourceSwitchPending) return
        if (isCastSessionConnected()) {
            localPlayer.pauseAtEndOfMediaItems = false
            releaseSecondaryCrossfadePlayer()
            return
        }
        if (!player.playWhenReady || sleepTimer.pauseWhenSongEnd) {
            localPlayer.pauseAtEndOfMediaItems = false
            releaseSecondaryCrossfadePlayer()
            return
        }

        if (smartFadeEnabled) {
            scheduleSmartFade()
            return
        }

        val target = resolveCrossfadeTarget()
        val duration = player.duration
        val effectiveDuration = effectiveCrossfadeDuration(duration)
        if (target == null || effectiveDuration == null) {
            localPlayer.pauseAtEndOfMediaItems = false
            releaseSecondaryCrossfadePlayer()
            return
        }

        val currentMediaId = player.currentMediaItem?.mediaId ?: return
        if (!crossfadeFailureTracker.canAttempt(currentMediaId, target.mediaId)) {
            localPlayer.pauseAtEndOfMediaItems = false
            releaseSecondaryCrossfadePlayer()
            Timber.tag("PlaybackDiagnostics").w("Crossfade skipped after repeated transition failures")
            return
        }
        val currentIndex = player.currentMediaItemIndex
        val triggerAt = duration - effectiveDuration - CROSSFADE_END_GUARD_MS

        crossfadeTriggerJob =
            scope.launch {
                var hasPreparedSecondaryPlayer = false
                while (isActive) {
                    if (!crossfadeEnabled || isCrossfading) return@launch
                    if (player.currentMediaItem?.mediaId != currentMediaId || player.currentMediaItemIndex != currentIndex) {
                        return@launch
                    }
                    if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
                        return@launch
                    }

                    val remainingToTrigger = triggerAt - player.currentPosition
                    if (!hasPreparedSecondaryPlayer && remainingToTrigger <= CROSSFADE_PREPARE_AHEAD_MS) {
                        prepareSecondaryCrossfadePlayer(target)
                        hasPreparedSecondaryPlayer = true
                    }
                    if (remainingToTrigger <= 0L) {
                        val adjustedDuration =
                            (duration - player.currentPosition - CROSSFADE_END_GUARD_MS)
                                .coerceAtMost(effectiveDuration)
                        if (adjustedDuration >= MIN_CROSSFADE_DURATION_MS) {
                            startCrossfade(target, adjustedDuration)
                        }
                        return@launch
                    }

                    val sleepMs =
                        when {
                            remainingToTrigger > 5_000L -> 1_000L
                            remainingToTrigger > 1_000L -> 250L
                            else -> 50L
                        }.coerceAtMost(remainingToTrigger).coerceAtLeast(1L)
                    delay(sleepMs)
                }
            }
    }

    // ------------------------------------------------------------------
    // Automix: analysis-driven transitions
    // ------------------------------------------------------------------

    /** The analysis orchestrator, created lazily once automix is ever used. */
    @UnstableApi
    private fun analyzer(): SmartFadeAnalyzer {
        smartFadeAnalyzer?.let { return it }
        val created =
            SmartFadeAnalyzer(this) { mediaId ->
                // Bounded: stream resolution can stall indefinitely (mutex
                // held, PoToken solver hang, extractor retry loops) and the
                // analysis runs on a SINGLE worker thread — an unbounded
                // resolve here parked "analysing…" on BOTH the current and
                // the next track forever. A timeout degrades to "no stream
                // this tick", which the strike counter eventually writes off.
                runBlocking {
                    runCatching {
                        withTimeout(SMART_FADE_RESOLVE_TIMEOUT_MS) {
                            runCatching {
                                YTPlayerUtils.playerResponseForPlayback(
                                    mediaId,
                                    audioQuality = AudioQuality.LOW,
                                    connectivityManager = connectivityManager,
                                    preferredStreamClient = preferredStreamClient,
                                    networkMetered = false,
                                ).getOrThrow().streamUrl
                            }.getOrNull()
                        }
                    }.getOrNull()
                }
            }
        smartFadeAnalyzer = created
        return created
    }

    /**
     * The smart-fade poll: requests analysis of the outgoing and incoming
     * tracks every tick, lets [planTransition] size and shape the transition
     * from the evidence, publishes the plan's window for the player UI, and
     * arms the secondary player at the plan's cue point before starting the
     * blend at the plan's own start time.
     */
    private fun scheduleSmartFade() {
        val target = resolveSmartFadeTarget()
        val duration = player.duration
        if (target == null || duration == C.TIME_UNSET || duration <= 0L) {
            localPlayer.pauseAtEndOfMediaItems = false
            releaseSecondaryCrossfadePlayer()
            SmartFadeRuntimeState.transitionWindow.value = null
            SmartFadeRuntimeState.analysis.value = SmartFadeRuntimeState.analysis.value.copy(
                current = TrackAnalysisState.WAITING,
                next = TrackAnalysisState.WAITING,
            )
            return
        }

        val currentMediaId = player.currentMediaItem?.mediaId ?: return
        if (!crossfadeFailureTracker.canAttempt(currentMediaId, target.mediaId)) {
            localPlayer.pauseAtEndOfMediaItems = false
            releaseSecondaryCrossfadePlayer()
            Timber.tag("PlaybackDiagnostics").w("Smart crossfade skipped after repeated transition failures")
            return
        }
        val currentIndex = player.currentMediaItemIndex

        crossfadeTriggerJob =
            scope.launch(SilentHandler) {
                var hasPreparedSecondaryPlayer = false
                var lastVerdict: String? = null
                while (isActive) {
                    if (!smartFadeEnabled || isCrossfading) return@launch
                    if (player.currentMediaItem?.mediaId != currentMediaId || player.currentMediaItemIndex != currentIndex) {
                        return@launch
                    }
                    if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
                        return@launch
                    }

                    val currentItem = player.currentMediaItem ?: return@launch
                    val nextItem = runCatching { player.getMediaItemAt(target.index) }.getOrNull() ?: return@launch

                    // Cheap no-ops once analysed or in flight; called every tick
                    // so a track that lands mid-song is picked up without a
                    // separate trigger.
                    val sm = analyzer()
                    sm.request(
                        trackId = currentItem.mediaId,
                        uri = currentItem.localConfiguration?.uri ?: android.net.Uri.EMPTY,
                        durationSeconds = duration / 1000.0,
                    )
                    val nextDurationMs = timelineDurationMsAt(target.index, nextItem)
                    sm.request(
                        trackId = nextItem.mediaId,
                        uri = nextItem.localConfiguration?.uri ?: android.net.Uri.EMPTY,
                        durationSeconds = nextDurationMs / 1000.0,
                    )

                    val currentAnalysis = sm.analysisFor(currentItem.mediaId)
                    val nextAnalysis = sm.analysisFor(nextItem.mediaId)

                    val plan =
                        planTransition(
                            analysis = currentAnalysis,
                            nextAnalysis = nextAnalysis,
                            currentTrack = currentItem.toTransitionInfo(duration),
                            nextTrack = nextItem.toTransitionInfo(nextDurationMs),
                            currentTime = player.currentPosition / 1000.0,
                            duration = duration / 1000.0,
                            // Only used before analysis lands or when the
                            // evidence supports no more than a plain fade.
                            fadeSeconds = smartFallbackFadeSeconds(),
                            minFadeSeconds = MIN_CROSSFADE_DURATION_MS / 1000.0,
                            mode = CrossfadeMode.SMART,
                            albumSequential = crossfadeGapless && isGaplessAlbumTransition(currentItem, nextItem),
                        )

                    publishSmartAnalysisState(sm, currentItem.mediaId, nextItem.mediaId)

                    // One line per distinct verdict rather than one per tick.
                    val verdict = "${plan.reason}|${plan.transitionStyle}|fade=${plan.fadeMs}"
                    if (verdict != lastVerdict) {
                        lastVerdict = verdict
                        Timber.tag(TAG).d(
                            "smartplan %s->%s: %s bpm=%.1f/%.1f conf=%.2f/%.2f",
                            currentItem.mediaId,
                            nextItem.mediaId,
                            verdict,
                            currentAnalysis.bpm,
                            nextAnalysis.bpm,
                            currentAnalysis.beatConfidence,
                            nextAnalysis.beatConfidence,
                        )
                    }

                    // The marker is gated on both sides being measured: until
                    // then the window is a moving fallback, and a marker that
                    // slides while you watch is worse than none.
                    val markable =
                        !plan.blocked &&
                            plan.markerVisible &&
                            sm.isAnalysed(currentItem.mediaId) &&
                            sm.isAnalysed(nextItem.mediaId)
                    SmartFadeRuntimeState.transitionWindow.value =
                        if (markable) {
                            val startFraction = (plan.transitionStart * 1000.0 / duration).toFloat().coerceIn(0f, 1f)
                            val endFraction = (plan.transitionEnd * 1000.0 / duration).toFloat().coerceIn(0f, 1f)
                            if (endFraction > startFraction) {
                                TransitionWindow(startFraction, endFraction)
                            } else {
                                null
                            }
                        } else {
                            null
                        }

                    if (plan.blocked) {
                        delay(SMART_FADE_POLL_MS)
                        continue
                    }

                    val transitionStartMs = (plan.transitionStart * 1000).roundToLong()
                    val remainingToStart = transitionStartMs - player.currentPosition
                    if (!hasPreparedSecondaryPlayer && remainingToStart <= CROSSFADE_PREPARE_AHEAD_MS) {
                        // A GAPLESS plan never plays the secondary — it is a
                        // near-instant handoff — so arming (and buffering) a
                        // whole second player for it would be pure waste.
                        if (plan.transitionStyle != TransitionStyle.GAPLESS) {
                            prepareSecondaryCrossfadePlayer(
                                target,
                                cueTimeMs = (plan.incomingCueTime * 1000).roundToLong().coerceAtLeast(0L),
                            )
                        }
                        hasPreparedSecondaryPlayer = true
                    }
                    if (remainingToStart <= 0L && plan.fadeMs >= MIN_CROSSFADE_DURATION_MS) {
                        // The fade may only span what is actually LEFT of the
                        // outgoing track. A user who seeks straight to the tail
                        // (or an analysis that lands late) used to start the
                        // FULL plan.fadeMs here: the outgoing hit its
                        // end-of-item pause mid-blend, the incoming kept ramping
                        // at low volume into the silence, and only the promotion
                        // seconds later "resumed" playback — the reported
                        // stutter / pause / one-second-to-continue. The classic
                        // crossfade path has always clamped like this; the smart
                        // path simply forgot to.
                        val positionMs = player.currentPosition
                        val remainingFadeMs =
                            (duration - positionMs)
                                .coerceAtMost(plan.fadeMs)
                        if (remainingFadeMs >= MIN_CROSSFADE_DURATION_MS) {
                            startCrossfade(target, remainingFadeMs, plan)
                        } else {
                            // Too little song is left to fade within: get out of
                            // the way and let the natural advance take over
                            // rather than arming a fade that outruns the track.
                            localPlayer.pauseAtEndOfMediaItems = false
                            releaseSecondaryCrossfadePlayer()
                            SmartFadeRuntimeState.transitionWindow.value = null
                            SmartFadeRuntimeState.mixing.value = false
                            SmartFadeRuntimeState.analysis.value =
                                SmartFadeRuntimeState.analysis.value.copy(
                                    current = TrackAnalysisState.WAITING,
                                    next = TrackAnalysisState.WAITING,
                                )
                        }
                        return@launch
                    }

                    val sleepMs =
                        when {
                            remainingToStart > 10_000L -> 1_000L
                            remainingToStart > 2_000L -> 250L
                            else -> 50L
                        }.coerceAtLeast(1L)
                    delay(sleepMs.coerceAtMost(SMART_FADE_POLL_MS))
                }
            }
    }

    /** Target resolution for the smart path: no fixed-duration requirement and
     * no gapless-album skip — the planner owns both of those decisions. */
    private fun resolveSmartFadeTarget(): CrossfadeTarget? {
        if (!smartFadeEnabled) return null
        if (player.mediaItemCount == 0 || player.currentTimeline.isEmpty) return null
        if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) return null

        val currentIndex = player.currentMediaItemIndex
        if (currentIndex !in 0 until player.mediaItemCount) return null

        val repeatCurrent = player.repeatMode == REPEAT_MODE_ONE
        val targetIndex =
            CrossfadePolicy.resolveTargetIndex(
                repeatOne = repeatCurrent,
                currentIndex = currentIndex,
                nextIndex = player.nextMediaItemIndex,
                itemCount = player.mediaItemCount,
                unsetIndex = C.INDEX_UNSET,
            ) ?: return null

        val targetItem = player.getMediaItemAt(targetIndex)
        return CrossfadeTarget(
            index = targetIndex,
            mediaId = targetItem.mediaId,
        )
    }

    /** The fallback overlap before analysis lands (or for a plain fade tier). */
    private fun smartFallbackFadeSeconds(): Double {
        val configured = crossfadeDurationMs.takeIf { it > 0L } ?: DEFAULT_SMART_FALLBACK_MS
        return (configured.coerceIn(MIN_CROSSFADE_DURATION_MS, 12_000L)) / 1000.0
    }

    /** The window duration at a queue index, falling back to the item's
     * metadata duration; both are gone for a not-yet-resolved item. */
    private fun timelineDurationMsAt(
        index: Int,
        item: androidx.media3.common.MediaItem,
    ): Long {
        val windowDuration =
            runCatching {
                val window = androidx.media3.common.Timeline.Window()
                player.currentTimeline.getWindow(index, window)
                window.durationMs
            }.getOrNull()
        return windowDuration?.takeIf { it != C.TIME_UNSET } ?: item.mediaMetadata.durationMs ?: 0L
    }

    private fun androidx.media3.common.MediaItem.toTransitionInfo(durationMs: Long): TransitionTrackInfo {
        val metadata = mediaMetadata
        return TransitionTrackInfo(
            id = mediaId,
            durationMs = durationMs,
            title = metadata.title?.toString().orEmpty(),
            artist = metadata.artist?.toString().orEmpty(),
            album = metadata.albumTitle?.toString().orEmpty(),
        )
    }

    /** Publishes both sides' analysis progress for the player status line. */
    private fun publishSmartAnalysisState(
        analyzer: SmartFadeAnalyzer,
        currentId: String,
        nextId: String,
    ) {
        fun stateOf(trackId: String): TrackAnalysisState =
            when {
                analyzer.isAnalysed(trackId) -> {
                    val usable = analyzer.analysisFor(trackId).isUsable
                    if (usable) TrackAnalysisState.ANALYSED else TrackAnalysisState.FAILED
                }
                analyzer.isAnalysing(trackId) -> TrackAnalysisState.ANALYSING
                else -> TrackAnalysisState.WAITING
            }
        val next = SmartFadeRuntimeState.analysis.value
        val updated =
            SmartAnalysis(
                current = stateOf(currentId),
                next = stateOf(nextId),
            )
        if (updated != next) SmartFadeRuntimeState.analysis.value = updated
    }

    /** A real mix is audible: beat-matched, cued, or filtered. */
    private fun isRealMix(plan: TransitionPlan?): Boolean {
        if (plan == null) return false
        return plan.transitionStyle == TransitionStyle.DJ_BLEND ||
            plan.transitionStyle == TransitionStyle.DJ_FILTER ||
            plan.incomingCueTime > 0.0 ||
            abs(plan.incomingPlaybackRate - 1.0) > 0.01
    }

    /**
     * Rides the transition filters over the blend, per plan style — ported
     * faithfully from BitChord's CrossfadeController:
     *  - DJ_FILTER: the outgoing low-pass sweeps closed while the incoming
     *    high-pass rides down, front-loaded;
     *  - DJ_BLEND with bassSwap: the low end changes hands once at the beat the
     *    planner chose, with a clash-scaled body lift and shallow exit roll-off;
     *  - otherwise: vocal-separation damage control scaled by the overlap.
     */
    private fun rideSmartFadeFilters(
        plan: TransitionPlan,
        progress: Float,
    ) {
        val outgoing = primaryTransitionFilter
        val incoming = secondaryTransitionFilter ?: return
        when (plan.transitionStyle) {
            TransitionStyle.DJ_FILTER -> rideFilterSweep(plan, progress, outgoing, incoming)
            TransitionStyle.DJ_BLEND ->
                if (plan.bassSwap) {
                    rideBassSwap(plan, progress, outgoing, incoming)
                } else {
                    rideVocalSeparation(plan, progress, outgoing, incoming)
                }

            else -> rideVocalSeparation(plan, progress, outgoing, incoming)
        }
    }

    private fun rideFilterSweep(
        plan: TransitionPlan,
        progress: Float,
        outgoing: TransitionFilterProcessor,
        incoming: TransitionFilterProcessor,
    ) {
        val sweep = plan.filterSweep.coerceIn(0.0, 1.0)
        if (sweep <= 0.0) {
            outgoing.open()
            incoming.open()
            return
        }
        // Both ends scaled by the sweep, so a partial sweep engages less
        // sharply AND stops short of the floor.
        val open = TransitionFilterProcessor.OPEN_HZ.toDouble()
        val entry = glide(open, FILTER_ENTRY_HZ, sweep)
        val floor = glide(open, FILTER_FLOOR_HZ, sweep)
        val cutoff = glide(entry, floor, progress.toDouble().pow(FILTER_SWEEP_SHAPE))
        outgoing.setCutoffs(cutoff.toFloat(), TransitionFilterProcessor.OFF_HZ)
        incoming.setCutoffs(
            TransitionFilterProcessor.OPEN_HZ,
            entryHighPass(progress, sweep, ENTRY_HIGH_PASS_HZ, ENTRY_OPEN_BY),
        )
    }

    /** Hands the low end from one track to the other, once, ramped over
     * [BASS_SWAP_WIDTH] of the fade at the beat the planner chose. */
    private fun rideBassSwap(
        plan: TransitionPlan,
        progress: Float,
        outgoing: TransitionFilterProcessor,
        incoming: TransitionFilterProcessor,
    ) {
        val swapAt = plan.bassSwapFraction.coerceIn(0.05, 0.95)
        // 0 before the swap window, 1 after it: how much of the low end has
        // changed hands.
        val handover = ((progress - swapAt) / BASS_SWAP_WIDTH * 0.5 + 0.5).coerceIn(0.0, 1.0)
        // The entry corner scales with how much the two actually sing over
        // each other; whichever corner sits higher is doing the work.
        val clash = plan.vocalOverlap.coerceIn(0.0, 1.0)
        val entry =
            maxOf(
                bassCutoff(1.0 - handover),
                entryHighPass(
                    progress,
                    1.0,
                    glide(BLEND_ENTRY_HIGH_PASS_HZ, BLEND_ENTRY_CLASH_HIGH_PASS_HZ, clash),
                    BLEND_ENTRY_OPEN_BY + (BLEND_ENTRY_CLASH_OPEN_BY - BLEND_ENTRY_OPEN_BY) * clash,
                ),
            )
        incoming.setCutoffs(TransitionFilterProcessor.OPEN_HZ, entry)
        outgoing.setCutoffs(blendExitLowPass(progress, clash), bassCutoff(handover))
    }

    /** The outgoing low-pass through a beat-matched blend: open until
     * [BLEND_EXIT_FROM], then closing shallowly — enough to stop competing with
     * the arriving voice, nowhere near the DJ_FILTER floor. */
    private fun blendExitLowPass(
        progress: Float,
        clash: Double,
    ): Float {
        val from = BLEND_EXIT_FROM + (BLEND_EXIT_CLASH_FROM - BLEND_EXIT_FROM) * clash
        val amount = ((progress - from) / (1.0 - from)).coerceIn(0.0, 1.0)
        val floor = glide(BLEND_EXIT_LOW_PASS_HZ, BLEND_EXIT_CLASH_LOW_PASS_HZ, clash)
        return glide(TransitionFilterProcessor.OPEN_HZ.toDouble(), floor, amount).toFloat()
    }

    /** [amount] 0 leaves the low end alone; 1 lifts it out entirely. */
    private fun bassCutoff(amount: Double): Float =
        glide(TransitionFilterProcessor.OFF_HZ.toDouble(), BASS_SWAP_HZ, amount).toFloat()

    /** Vocal-clash damage control, scaled by how much the two collide. */
    private fun rideVocalSeparation(
        plan: TransitionPlan,
        progress: Float,
        outgoing: TransitionFilterProcessor,
        incoming: TransitionFilterProcessor,
    ) {
        val amount = plan.vocalOverlap.coerceIn(0.0, 1.0)
        if (amount <= 0.0) {
            outgoing.open()
            incoming.open()
            return
        }
        val open = TransitionFilterProcessor.OPEN_HZ.toDouble()
        val floor = glide(open, VOCAL_SEPARATION_FLOOR_HZ, amount)
        outgoing.setCutoffs(
            glide(open, floor, progress.toDouble().pow(FILTER_SWEEP_SHAPE)).toFloat(),
            TransitionFilterProcessor.OFF_HZ,
        )
        incoming.setCutoffs(
            TransitionFilterProcessor.OPEN_HZ,
            entryHighPass(progress, amount, VOCAL_SEPARATION_HIGH_PASS_HZ, ENTRY_OPEN_BY),
        )
    }

    /** Where the incoming track's high-pass sits at [progress]: rides from
     * [topHz] down to nothing by [openBy] of the fade, front-loaded by
     * [ENTRY_SHAPE] so the travel is spent where a voice actually is. */
    private fun entryHighPass(
        progress: Float,
        amount: Double,
        topHz: Double,
        openBy: Double,
    ): Float {
        val remaining = (1.0 - progress / openBy).coerceIn(0.0, 1.0)
        return glide(TransitionFilterProcessor.OFF_HZ.toDouble(), topHz, amount * remaining.pow(ENTRY_SHAPE))
            .toFloat()
    }

    /** Geometric interpolation between two cutoffs: pitch is logarithmic, so
     * cutoffs must glide multiplicatively or they lurch through the bottom of
     * their range and crawl through the top. */
    private fun glide(
        from: Double,
        to: Double,
        amount: Double,
    ): Double = from * (to / from).pow(amount.coerceIn(0.0, 1.0))

    private fun resolveCrossfadeTarget(): CrossfadeTarget? {
        if (!crossfadeEnabled || crossfadeDurationMs <= 0L) return null
        if (player.mediaItemCount == 0 || player.currentTimeline.isEmpty) return null
        if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) return null

        val currentIndex = player.currentMediaItemIndex
        if (currentIndex !in 0 until player.mediaItemCount) return null

        val repeatCurrent = player.repeatMode == REPEAT_MODE_ONE
        val targetIndex =
            CrossfadePolicy.resolveTargetIndex(
                repeatOne = repeatCurrent,
                currentIndex = currentIndex,
                nextIndex = player.nextMediaItemIndex,
                itemCount = player.mediaItemCount,
                unsetIndex = C.INDEX_UNSET,
            ) ?: return null

        val currentItem = player.getMediaItemAt(currentIndex)
        val targetItem = player.getMediaItemAt(targetIndex)
        if (!repeatCurrent && crossfadeGapless && isGaplessAlbumTransition(currentItem, targetItem)) return null
        // Episodes report duration -1 until resolved: fading against an
        // unknown-length track schedules against fiction. Skip both directions.
        if (currentItem.metadata?.isPodcast == true || targetItem.metadata?.isPodcast == true) return null

        return CrossfadeTarget(
            index = targetIndex,
            mediaId = targetItem.mediaId,
        )
    }

    private fun effectiveCrossfadeDuration(duration: Long): Long? =
        CrossfadePolicy.effectiveDurationMs(
            requestedMs = crossfadeDurationMs,
            trackDurationMs = duration,
            endGuardMs = CROSSFADE_END_GUARD_MS,
            minDurationMs = MIN_CROSSFADE_DURATION_MS,
            unsetTime = C.TIME_UNSET,
        )

    private fun isGaplessAlbumTransition(
        currentItem: MediaItem,
        targetItem: MediaItem,
    ): Boolean {
        val currentAlbum =
            currentItem.metadata
                ?.album
                ?.id
                ?.takeIf { it.isNotBlank() }
                ?: currentItem.metadata
                    ?.album
                    ?.title
                    ?.takeIf { it.isNotBlank() }
                ?: currentItem.mediaMetadata.albumTitle
                    ?.toString()
                    ?.takeIf { it.isNotBlank() }
        val targetAlbum =
            targetItem.metadata
                ?.album
                ?.id
                ?.takeIf { it.isNotBlank() }
                ?: targetItem.metadata
                    ?.album
                    ?.title
                    ?.takeIf { it.isNotBlank() }
                ?: targetItem.mediaMetadata.albumTitle
                    ?.toString()
                    ?.takeIf { it.isNotBlank() }
        return currentAlbum != null && currentAlbum == targetAlbum
    }

    private fun prepareSecondaryCrossfadePlayer(
        target: CrossfadeTarget,
        cueTimeMs: Long = 0L,
    ): ExoPlayer? {
        val existingPlayer = secondaryCrossfadePlayer
        if (existingPlayer != null && secondaryCrossfadeTarget == target) {
            // Analysis can refine the cue between arming and starting; keep the
            // reused player's position honest or the ramp measures from the
            // wrong origin.
            if (cueTimeMs > 0L && existingPlayer.currentPosition != cueTimeMs) {
                runCatching { existingPlayer.seekTo(cueTimeMs) }
            }
            return existingPlayer
        }

        releaseSecondaryCrossfadePlayer()

        runCatching { player.getMediaItemAt(target.index) }
            .getOrNull()
            ?.takeIf { it.mediaId == target.mediaId }
            ?: return null

        return runCatching {
            createSecondaryCrossfadePlayer().also { secondaryPlayer ->
                secondaryCrossfadePlayer = secondaryPlayer
                secondaryCrossfadeTarget = target
                setAudioAuthenticityContext(secondaryPlayer, target.mediaId)
                // The incoming player must be promotion-capable: it receives the FULL queue
                // (not just the target item) plus the repeat/shuffle/speed state, so it can
                // become the active player at the end of the fade without any re-buffering.
                val items = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
                // A smart plan enters the incoming track at its cue point, not
                // at zero; the standard path keeps the 0L default.
                secondaryPlayer.setMediaItems(items, target.index, cueTimeMs.coerceAtLeast(0L))
                secondaryPlayer.repeatMode = player.repeatMode
                secondaryPlayer.shuffleModeEnabled = player.shuffleModeEnabled
                secondaryPlayer.playbackParameters = player.playbackParameters
                secondaryPlayer.volume = 0f
                secondaryPlayer.prepare()
            }
        }.onFailure { error ->
            Timber.tag(TAG).w(error, "Failed to prepare crossfade player")
            releaseSecondaryCrossfadePlayer()
        }.getOrNull()
    }

    private fun createSecondaryCrossfadePlayer(): ExoPlayer {
        // Each player owns its filter: an AudioProcessor may belong to one chain only.
        val secondaryTransition = TransitionFilterProcessor()
        secondaryTransitionFilter = secondaryTransition
        val authenticityContext = AtomicReference<AudioAuthenticityPlaybackContext?>(null)
        val secondaryPlayer = ExoPlayer
            .Builder(this)
            .setMediaSourceFactory(createMediaSourceFactory())
            .setRenderersFactory(createRenderersFactory(secondaryTransition, authenticityContext))
            .setLoadControl(createCrossfadeLoadControl())
            .setTrackSelector(DefaultTrackSelector(this, SafeTrackSelectionFactory()))
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setAudioAttributes(playbackAudioAttributes(), false)
            .setSeekBackIncrementMs(5000)
            .setSeekForwardIncrementMs(5000)
            .setDeviceVolumeControlEnabled(true)
            .build()
            .apply {
                addListener(secondaryCrossfadeListener)
                setOffloadEnabled(false)
                skipSilenceEnabled = localPlayer.skipSilenceEnabled
            }
        audioAuthenticityContextByPlayer[secondaryPlayer] = authenticityContext
        observeAudioTrackConfiguration(secondaryPlayer)
        return secondaryPlayer
    }

    private fun startCrossfade(
        target: CrossfadeTarget,
        durationMs: Long,
        plan: TransitionPlan? = null,
    ) {
        if (isCrossfading || (!crossfadeEnabled && !smartFadeEnabled)) return

        val smart = plan != null
        val cueTimeMs = if (smart) (plan!!.incomingCueTime * 1000).roundToLong().coerceAtLeast(0L) else 0L
        val outgoingMediaId = player.currentMediaItem?.mediaId ?: return
        if (!crossfadeFailureTracker.canAttempt(outgoingMediaId, target.mediaId)) {
            Timber.tag("PlaybackDiagnostics").w("Crossfade attempt suppressed after repeated transition failures")
            return
        }
        val incomingPlayer = prepareSecondaryCrossfadePlayer(target, cueTimeMs) ?: return
        val generation = crossfadeGeneration.incrementAndGet()

        crossfadeTriggerJob?.cancel()
        crossfadeTriggerJob = null
        crossfadeJob?.cancel()
        crossfadeJob =
            scope.launch(SilentHandler) {
                isCrossfading = true
                crossfadeProgress = 0f
                crossfadeBaseVolume = currentEffectivePlayerVolume()
                crossfadeIncomingBaseVolume = currentEffectivePlayerVolumeForMediaId(target.mediaId)
                crossfadePlaybackRequested = player.playWhenReady
                localPlayer.pauseAtEndOfMediaItems = true
                crossfadeUserPlaybackParameters = player.playbackParameters
                crossfadeSmartActive = smart
                Timber.tag("PlaybackDiagnostics").i(
                    "Crossfade start smart=%s durationMs=%d cueMs=%d positionMs=%d trackDurationMs=%d playWhenReady=%s gain=%.3f incomingGain=%.3f",
                    smart,
                    durationMs,
                    cueTimeMs,
                    player.currentPosition,
                    player.duration,
                    crossfadePlaybackRequested,
                    crossfadeBaseVolume,
                    crossfadeIncomingBaseVolume,
                )
                if (smart) {
                    SmartFadeRuntimeState.mixing.value = isRealMix(plan)
                }

                Timber.tag(TAG).d(
                    "crossfade[%d] start outgoing=%s incoming=%s durationMs=%d smart=%s",
                    generation,
                    outgoingMediaId,
                    target.mediaId,
                    durationMs,
                    smart,
                )

                // Re-clamped once the incoming player is rolling: the readiness wait spends
                // outgoing time too (4nx3b 4475f411c).
                var fadeMs = durationMs

                try {
                    // The buffer requirement may not exceed what the outgoing song still has left:
                    // a late-armed fade (seek into the tail, analysis landing late) otherwise waits
                    // for a full smooth-start buffer while the outgoing runs into its end-of-item
                    // pause (4nx3b 4475f411c).
                    val outgoingLeftMs =
                        player.duration
                            .takeIf { it != C.TIME_UNSET && it > 0L }
                            ?.let { it - player.currentPosition }
                            ?: Long.MAX_VALUE
                    val requiredBufferedMs =
                        requiredCrossfadeStartBufferMs(durationMs)
                            .coerceAtMost(outgoingLeftMs.coerceAtLeast(0L))
                    if (!awaitCrossfadePlayerReady(incomingPlayer, CROSSFADE_READY_TIMEOUT_MS, requiredBufferedMs)) {
                        Timber.tag(TAG).d("crossfade[%d] incoming player not ready in time; aborting", generation)
                        val retryAllowed =
                            incomingPlayer.playerError == null &&
                                crossfadeFailureTracker.registerFailure(outgoingMediaId, target.mediaId)
                        recoverCrossfadeFailure(retryAllowed)
                        return@launch
                    }

                    // Shrink the ramp to what is actually left after the wait so the blend still
                    // finishes inside the outgoing song. A fade that cannot fit at all is handled
                    // by the early finish in the loop below.
                    player.duration
                        .takeIf { it != C.TIME_UNSET && it > 0L }
                        ?.let { fullDuration ->
                            val endGuardMs = if (smart) 0L else CROSSFADE_END_GUARD_MS
                            val leftAfterWaitMs = fullDuration - player.currentPosition - endGuardMs
                            if (leftAfterWaitMs < fadeMs) {
                                fadeMs = leftAfterWaitMs.coerceAtLeast(MIN_CROSSFADE_DURATION_MS)
                                Timber.tag(TAG).d(
                                    "crossfade[%d] re-clamped fade to %dms (outgoing almost done)",
                                    generation,
                                    fadeMs,
                                )
                            }
                        }

                    if (smart) {
                        // Beat-match stretch: the incoming track plays at the
                        // plan's rate so its beats land on the outgoing grid.
                        // Stacked on the user's playback speed the way BitChord
                        // stacks it, so a 1.5x listener still gets a mix.
                        val userSpeed = player.playbackParameters.speed
                        incomingPlayer.playbackParameters =
                            player.playbackParameters.withSpeed(
                                (userSpeed.toDouble() * plan!!.incomingPlaybackRate)
                                    .coerceIn(0.05, 8.0)
                                    .toFloat(),
                            )
                    } else {
                        incomingPlayer.playbackParameters = player.playbackParameters
                    }
                    incomingPlayer.playWhenReady = crossfadePlaybackRequested
                    if (crossfadePlaybackRequested) {
                        incomingPlayer.play()
                        // STATE_READY + buffering do not prove that post-seek audio samples are
                        // advancing through the audio output. Require the play position to move.
                        if (!awaitAudioPositionAdvancement(incomingPlayer, CROSSFADE_AUDIO_ADVANCE_TIMEOUT_MS)) {
                            Timber.tag(TAG).w("crossfade[%d] no audio advancement on incoming player; aborting", generation)
                            val retryAllowed =
                                incomingPlayer.playerError == null &&
                                    crossfadeFailureTracker.registerFailure(outgoingMediaId, target.mediaId)
                            recoverCrossfadeFailure(retryAllowed)
                            return@launch
                        }
                    }

                    player.duration
                        .takeIf { it != C.TIME_UNSET && it > 0L }
                        ?.let { fullDuration ->
                            val endGuardMs = if (smart) 0L else CROSSFADE_END_GUARD_MS
                            val leftAtFadeStartMs = fullDuration - player.currentPosition - endGuardMs
                            if (leftAtFadeStartMs < fadeMs) {
                                fadeMs = leftAtFadeStartMs.coerceAtLeast(MIN_CROSSFADE_DURATION_MS)
                            }
                        }

                    var elapsedMs = 0L
                    var lastTickMs = android.os.SystemClock.elapsedRealtime()
                    val blendStartOutgoingPositionMs = player.currentPosition
                    var lastFadeOutgoingPositionMs = blendStartOutgoingPositionMs
                    var incomingStallStartedAtMs = -1L
                    // Set once the outgoing song has run out underneath the blend (ported from
                    // 4nx3b 998edf62c). From then on there is nothing left to mix out of, so the
                    // rest of the ramp is compressed into CROSSFADE_EARLY_FINISH_MS instead of
                    // leaving the incoming song crawling up at low volume over dead air. 4nx3b
                    // snaps straight to full volume; a short ramp avoids the audible jump.
                    var earlyFinishElapsedMs = -1L
                    var earlyFinishFromProgress = 0f
                    while (isActive && elapsedMs < fadeMs) {
                        if (player.currentMediaItem?.mediaId != outgoingMediaId) {
                            cancelCrossfade(resetVolume = true, resetPauseAtEnd = true)
                            return@launch
                        }

                        val nowMs = android.os.SystemClock.elapsedRealtime()
                        val incomingAdvancing =
                            incomingPlayer.playbackState == Player.STATE_READY && incomingPlayer.isPlaying
                        if (!crossfadePlaybackRequested || incomingAdvancing) {
                            incomingStallStartedAtMs = -1L
                        } else if (incomingStallStartedAtMs < 0L) {
                            incomingStallStartedAtMs = nowMs
                        }
                        if (CrossfadePolicy.shouldAbortForIncomingStall(
                                playbackRequested = crossfadePlaybackRequested,
                                incomingAdvancing = incomingAdvancing,
                                stallElapsedMs =
                                    if (incomingStallStartedAtMs >= 0L) {
                                        nowMs - incomingStallStartedAtMs
                                    } else {
                                        0L
                                    },
                                maximumStallMs = CROSSFADE_BUFFER_STALL_GRACE_MS,
                            )
                        ) {
                            val retryAllowed =
                                incomingPlayer.playerError == null &&
                                    incomingPlayer.playbackState != Player.STATE_ENDED &&
                                    crossfadeFailureTracker.registerFailure(outgoingMediaId, target.mediaId)
                            recoverCrossfadeFailure(retryAllowed)
                            return@launch
                        }
                        if (earlyFinishElapsedMs < 0L && crossfadePlaybackRequested) {
                            // onPlayWhenReadyChanged keeps crossfadePlaybackRequested true across
                            // the outgoing player's own end-of-item pause, so a pause by the user
                            // (which clears it) can never be mistaken for the song ending.
                            val outgoingDuration = player.duration
                            val outgoingRanOut =
                                player.playbackState == Player.STATE_ENDED ||
                                    (
                                        !player.playWhenReady &&
                                            player.playbackState == Player.STATE_READY &&
                                            outgoingDuration != C.TIME_UNSET &&
                                            player.currentPosition >= outgoingDuration - CROSSFADE_END_GUARD_MS - 100L
                                        )
                            if (outgoingRanOut) {
                                if (!incomingAdvancing) {
                                    val retryAllowed =
                                        incomingPlayer.playerError == null &&
                                            incomingPlayer.playbackState != Player.STATE_ENDED &&
                                            crossfadeFailureTracker.registerFailure(outgoingMediaId, target.mediaId)
                                    recoverCrossfadeFailure(retryAllowed)
                                    return@launch
                                }
                                earlyFinishElapsedMs = 0L
                                earlyFinishFromProgress = crossfadeProgress
                                Timber.tag(TAG).d(
                                    "crossfade[%d] outgoing ended mid-blend at %.0f%%; finishing early",
                                    generation,
                                    crossfadeProgress * 100f,
                                )
                            }
                        }
                        if (earlyFinishElapsedMs >= 0L) {
                            if (crossfadePlaybackRequested) {
                                if (!incomingAdvancing) {
                                    val retryAllowed =
                                        incomingPlayer.playerError == null &&
                                            incomingPlayer.playbackState != Player.STATE_ENDED &&
                                            crossfadeFailureTracker.registerFailure(outgoingMediaId, target.mediaId)
                                    recoverCrossfadeFailure(retryAllowed)
                                    return@launch
                                }
                                incomingPlayer.playWhenReady = true
                                earlyFinishElapsedMs =
                                    (earlyFinishElapsedMs + (nowMs - lastTickMs)).coerceAtMost(CROSSFADE_EARLY_FINISH_MS)
                                val t = earlyFinishElapsedMs.toFloat() / CROSSFADE_EARLY_FINISH_MS.toFloat()
                                crossfadeProgress =
                                    (earlyFinishFromProgress + (1f - earlyFinishFromProgress) * t).coerceIn(0f, 1f)
                                applyCrossfadeVolumes(
                                    crossfadeProgress,
                                    crossfadeBaseVolume,
                                    crossfadeIncomingBaseVolume,
                                    localPlayer,
                                    incomingPlayer,
                                )
                                if (smart) {
                                    rideSmartFadeFilters(plan!!, crossfadeProgress)
                                }
                                if (earlyFinishElapsedMs >= CROSSFADE_EARLY_FINISH_MS) break
                            } else {
                                incomingPlayer.pause()
                            }
                            lastTickMs = nowMs
                            delay(CROSSFADE_FRAME_MS)
                            continue
                        }
                        if (crossfadePlaybackRequested) {
                            incomingPlayer.playWhenReady = true
                            val outgoingPositionMs = player.currentPosition
                            elapsedMs =
                                CrossfadePolicy.advanceFadeElapsedMs(
                                    elapsedMs = elapsedMs,
                                    previousPositionMs = lastFadeOutgoingPositionMs,
                                    currentPositionMs = outgoingPositionMs,
                                    durationMs = fadeMs,
                                    incomingAdvancing = incomingAdvancing,
                                )
                            lastFadeOutgoingPositionMs = outgoingPositionMs
                            crossfadeProgress = (elapsedMs.toFloat() / fadeMs.toFloat()).coerceIn(0f, 1f)
                            applyCrossfadeVolumes(
                                crossfadeProgress,
                                crossfadeBaseVolume,
                                crossfadeIncomingBaseVolume,
                                localPlayer,
                                incomingPlayer,
                            )
                            if (smart) {
                                rideSmartFadeFilters(plan!!, crossfadeProgress)
                            }
                        } else {
                            incomingPlayer.pause()
                            lastFadeOutgoingPositionMs = player.currentPosition
                        }
                        lastTickMs = nowMs
                        delay(CROSSFADE_FRAME_MS)
                    }

                    if (smart) {
                        val tailDeadlineMs =
                            android.os.SystemClock.elapsedRealtime() + SMART_FADE_TAIL_WAIT_MS
                        while (isActive && player.currentMediaItem?.mediaId == outgoingMediaId) {
                            if (!crossfadePlaybackRequested) {
                                Timber.tag("PlaybackDiagnostics").d("Smart fade tail wait canceled after playback pause")
                                cancelCrossfade(resetVolume = true, resetPauseAtEnd = true)
                                return@launch
                            }
                            val outgoingDuration = player.duration
                            if (outgoingDuration == C.TIME_UNSET || outgoingDuration <= 0L) {
                                Timber.tag("PlaybackDiagnostics").w("Smart fade tail wait canceled because duration became unknown")
                                recoverCrossfadeFailure()
                                return@launch
                            }
                            if (incomingPlayer.playbackState != Player.STATE_READY || !incomingPlayer.isPlaying) {
                                val retryAllowed =
                                    incomingPlayer.playerError == null &&
                                        incomingPlayer.playbackState != Player.STATE_ENDED &&
                                        crossfadeFailureTracker.registerFailure(outgoingMediaId, target.mediaId)
                                recoverCrossfadeFailure(retryAllowed)
                                return@launch
                            }
                            val outgoingPosition = player.currentPosition
                            if (player.playbackState == Player.STATE_ENDED ||
                                outgoingPosition >= outgoingDuration - SMART_FADE_TAIL_EPSILON_MS
                            ) {
                                break
                            }
                            if (!player.playWhenReady &&
                                player.playbackState == Player.STATE_READY &&
                                outgoingPosition >= outgoingDuration - 250L
                            ) {
                                break
                            }
                            if (android.os.SystemClock.elapsedRealtime() >= tailDeadlineMs) {
                                val retryAllowed = crossfadeFailureTracker.registerFailure(outgoingMediaId, target.mediaId)
                                Timber.tag("PlaybackDiagnostics").w(
                                    "Smart fade tail wait timed out positionMs=%d durationMs=%d; restoring outgoing gain",
                                    outgoingPosition,
                                    outgoingDuration,
                                )
                                recoverCrossfadeFailure(retryAllowed)
                                return@launch
                            }
                            applyCrossfadeVolumes(
                                1f,
                                crossfadeBaseVolume,
                                crossfadeIncomingBaseVolume,
                                localPlayer,
                                incomingPlayer,
                            )
                            delay(CROSSFADE_FRAME_MS)
                        }
                        if (player.currentMediaItem?.mediaId != outgoingMediaId) {
                            cancelCrossfade(resetVolume = true, resetPauseAtEnd = true)
                            return@launch
                        }
                    }

                    finishCrossfade(target, incomingPlayer, generation)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    // Throwable (4nx3b 998edf62c): an Error such as OOM escaping the ramp used to
                    // skip this cleanup, leaving isCrossfading set, pauseAtEndOfMediaItems armed
                    // and the secondary player alive at half-faded volume. SilentHandler would
                    // have swallowed it, so nothing else noticed.
                    runCatching {
                        if (error is Exception) {
                            Timber.tag(TAG).w(error, "Crossfade failed")
                        } else {
                            Timber.tag(TAG).w("Crossfade failed with %s", error::class.java.simpleName)
                        }
                    }
                    runCatching { recoverCrossfadeFailure() }
                        .onFailure {
                            runCatching { isCrossfading = false }
                            runCatching { localPlayer.pauseAtEndOfMediaItems = false }
                        }
                }
            }
    }

    private suspend fun awaitCrossfadePlayerReady(
        crossfadePlayer: ExoPlayer,
        timeoutMs: Long,
        minimumBufferedMs: Long,
    ): Boolean {
        val deadlineMs = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (kotlinx.coroutines.currentCoroutineContext().isActive && android.os.SystemClock.elapsedRealtime() < deadlineMs) {
            val snapshot =
                CrossfadePolicy.ReadinessSnapshot(
                    isReady = crossfadePlayer.playbackState == Player.STATE_READY,
                    isIdle = crossfadePlayer.playbackState == Player.STATE_IDLE,
                    isEnded = crossfadePlayer.playbackState == Player.STATE_ENDED,
                    hasError = crossfadePlayer.playerError != null,
                    bufferedEnough = hasBufferedForSmoothStart(crossfadePlayer, minimumBufferedMs),
                )
            when {
                CrossfadePolicy.mustAbortReadiness(snapshot) -> return false
                CrossfadePolicy.isReadyForFadeStart(snapshot) -> return true
                snapshot.isIdle -> crossfadePlayer.prepare()
            }
            delay(50L)
        }
        return crossfadePlayer.playbackState == Player.STATE_READY &&
            crossfadePlayer.playerError == null &&
            hasBufferedForSmoothStart(crossfadePlayer, minimumBufferedMs)
    }

    /**
     * Waits for proof that audio is actually advancing through the output: STATE_READY +
     * isPlaying + the play position moving between polls. Buffering and readiness alone do
     * not prove post-seek samples are being rendered.
     */
    private suspend fun awaitAudioPositionAdvancement(
        targetPlayer: ExoPlayer,
        timeoutMs: Long,
    ): Boolean {
        val deadlineMs = android.os.SystemClock.elapsedRealtime() + timeoutMs
        var lastPositionMs = targetPlayer.currentPosition
        while (kotlinx.coroutines.currentCoroutineContext().isActive && android.os.SystemClock.elapsedRealtime() < deadlineMs) {
            if (targetPlayer.playerError != null) return false
            delay(CROSSFADE_AUDIO_ADVANCE_POLL_MS)
            val positionMs = targetPlayer.currentPosition
            if (CrossfadePolicy.hasAudioAdvanced(
                    CrossfadePolicy.AudioAdvancementSnapshot(
                        isReady = targetPlayer.playbackState == Player.STATE_READY,
                        isPlaying = targetPlayer.isPlaying,
                        hasError = targetPlayer.playerError != null,
                        positionMs = positionMs,
                        previousPositionMs = lastPositionMs,
                    ),
                )
            ) {
                return true
            }
            lastPositionMs = positionMs
        }
        return false
    }

    /**
     * Completes a crossfade by PROMOTING the already-playing incoming player to be the
     * active player. The old outgoing player is the only one released; the incoming player
     * keeps its decoder, buffered data, audio sink and playback clock.
     */
    private suspend fun finishCrossfade(
        target: CrossfadeTarget,
        incomingPlayer: ExoPlayer,
        generation: Long,
    ) {
        // A cancelled/superseded crossfade must never complete an older handoff.
        if (generation != crossfadeGeneration.get()) {
            Timber.tag(TAG).d("crossfade[%d] stale generation at promotion; ignoring", generation)
            return
        }
        val targetIndex = resolveCrossfadeTargetIndex(target, incomingPlayer)
        val incomingUsable =
            CrossfadePolicy.mayPromote(
                CrossfadePolicy.PromotionSnapshot(
                    generationMatches = true,
                    targetIndex = targetIndex,
                    incomingItemMatchesTarget = incomingPlayer.currentMediaItem?.mediaId == target.mediaId,
                    hasError = incomingPlayer.playerError != null,
                    isIdle = incomingPlayer.playbackState == Player.STATE_IDLE,
                    isEnded = incomingPlayer.playbackState == Player.STATE_ENDED,
                    isReady = incomingPlayer.playbackState == Player.STATE_READY,
                    isPlaying = incomingPlayer.isPlaying,
                    playbackRequested = crossfadePlaybackRequested,
                    unsetIndex = C.INDEX_UNSET,
                ),
            )
        if (!incomingUsable) {
            // Never continue a destructive handoff when the incoming player is not valid:
            // the outgoing player remains authoritative and keeps playing.
            Timber.tag(TAG).w("crossfade[%d] incoming player unusable at promotion; keeping outgoing", generation)
            val retryAllowed =
                targetIndex != C.INDEX_UNSET &&
                    incomingPlayer.playerError == null &&
                    incomingPlayer.playbackState != Player.STATE_ENDED &&
                    crossfadeFailureTracker.registerFailure(
                        player.currentMediaItem?.mediaId,
                        target.mediaId,
                    )
            recoverCrossfadeFailure(retryAllowed)
            return
        }

        Timber.tag(TAG).d(
            "crossfade[%d] promotion start outgoing=%s incoming=%s",
            generation,
            player.currentMediaItem?.mediaId,
            target.mediaId,
        )
        val promoted = promoteIncomingCrossfadePlayer(target, incomingPlayer, targetIndex)
        Timber.tag("PlaybackDiagnostics").i(
            "Crossfade promotion success=%s playWhenReady=%s state=%d positionMs=%d gain=%.3f focusHeld=%s",
            promoted,
            player.playWhenReady,
            player.playbackState,
            player.currentPosition,
            player.volume,
            hasAudioFocus,
        )
        if (!promoted) {
            // A promotion that failed after ownership moved leaves the incoming player audible;
            // its filter becomes the primary one so the cancel below opens the right filter.
            if (localPlayer === incomingPlayer) {
                secondaryTransitionFilter?.let { primaryTransitionFilter = it }
                secondaryTransitionFilter = null
            }
            cancelCrossfade(resetVolume = true, resetPauseAtEnd = true)
            return
        }

        // Smart-blend cleanup: the promoted player's filter must finish wide
        // open, the beat-match stretch must give way to the user's speed, and
        // the filter roles transfer to the promoted player.
        val promotedFilter = secondaryTransitionFilter
        if (promotedFilter != null) {
            promotedFilter.open()
            primaryTransitionFilter = promotedFilter
        }
        secondaryTransitionFilter = null
        primaryTransitionFilter.open()
        crossfadeUserPlaybackParameters?.let { params ->
            runCatching { incomingPlayer.playbackParameters = params }
        }
        crossfadeUserPlaybackParameters = null
        crossfadeSmartActive = false
        SmartFadeRuntimeState.mixing.value = false
        SmartFadeRuntimeState.transitionWindow.value = null

        isCrossfading = false
        crossfadeHandoffInProgress = false
        crossfadeHandoffProgress = 0f
        crossfadeProgress = 0f
        crossfadeIncomingBaseVolume = 1f
        crossfadePlaybackRequested = false
        // Restore the configured final volume on the promoted player.
        applyEffectiveVolumeImmediately()
        updateAudiblePlaybackRecovery()
        scheduleCrossfade()
        Timber.tag(TAG).d("crossfade[%d] promotion success; active mediaId=%s", generation, player.currentMediaItem?.mediaId)
    }

    /**
     * Promotes [incomingPlayer] to active-player ownership: it becomes `localPlayer`, the
     * session-facing player is rebuilt/re-pointed, listeners, MediaSession, Cast delegation,
     * queue/metadata state and audio effects all follow the promoted player, and only the old
     * outgoing player is faded out and released.
     */
    private fun promoteIncomingCrossfadePlayer(
        target: CrossfadeTarget,
        incomingPlayer: ExoPlayer,
        targetIndex: Int,
    ): Boolean {
        val outgoingPlayer = localPlayer
        val oldSessionPlayer = player
        // Whether playback should still be running once ownership has moved. Captured BEFORE the
        // session player is rebuilt below: that rebuild constructs a fresh wrapper around the
        // promoted ExoPlayer, and a fresh wrapper does not inherit playWhenReady. Nothing used to
        // re-assert it, so the promoted player came up paused and the incoming song stopped a few
        // seconds in — right at the end of the fade, which is what made it look random.
        //
        // The intent itself is crossfadePlaybackRequested, kept live by onPlayWhenReadyChanged for
        // the whole fade and promotion window and cleared by any pause that is not the outgoing
        // player's own end-of-item auto-pause — so a pause (user tap or audio-focus loss) landing
        // mid-fade is no longer overridden by the stale `oldSessionPlayer.playWhenReady` the
        // outgoing player still reports. Focus is a precondition too: when playback was paused for
        // audio-focus loss the GAIN handler resumes it via wasPlayingBeforeAudioFocusLoss, so
        // promotion must not force play while the system has not returned focus.
        val shouldKeepPlaying = crossfadePlaybackRequested && hasAudioFocusForPlayback()
        crossfadeHandoffInProgress = true
        return try {
            incomingPlayer.removeListener(secondaryCrossfadeListener)
            incomingPlayer.pauseAtEndOfMediaItems = false

            // 1. Ownership: the incoming player becomes the active local player.
            localPlayer = incomingPlayer
            secondaryCrossfadePlayer = null
            secondaryCrossfadeTarget = null

            // The incoming player's queue was snapshotted at prepare time; items appended to
            // the live queue since then (e.g. auto-load-more) must follow the promoted player.
            val incomingItemIds =
                (0 until incomingPlayer.mediaItemCount)
                    .mapTo(HashSet()) { incomingPlayer.getMediaItemAt(it).mediaId }
            val missingItems =
                (0 until oldSessionPlayer.mediaItemCount)
                    .map { oldSessionPlayer.getMediaItemAt(it) }
                    .filter { it.mediaId !in incomingItemIds }
            if (missingItems.isNotEmpty()) {
                incomingPlayer.addMediaItems(missingItems)
            }

            // 2. Session-facing player + Cast delegation. CastPlayer pins its local player at
            // build time, so the wrapper must be rebuilt around the promoted player; on foss
            // builds the session player simply IS the new local player.
            oldSessionPlayer.removeListener(this@MusicService)
            runCatching { oldSessionPlayer.removeListener(sleepTimer) }
            player =
                castPlaybackRepository
                    .createPlayer(
                        context = this,
                        localPlayer = localPlayer,
                        mediaItemResolver = CastMediaItemResolver(::resolveMediaItemForCast),
                    ).apply {
                        addListener(this@MusicService)
                    }
            sleepTimer.player = player
            player.addListener(sleepTimer)
            // Promotion builds a new session player and releases the old one, so anything holding a
            // listener on it has to be moved across or it goes deaf for the rest of the session.
            sponsorBlockPlaybackController.attach(player, ioScope)
            castPlaybackRepository.releasePlayer(oldSessionPlayer)

            // 3. Listeners/analytics that were attached to the old local player.
            runCatching { outgoingPlayer.removeListener(audioEffectPlayerListener) }
            incomingPlayer.addListener(audioEffectPlayerListener)
            incomingPlayer.addAnalyticsListener(PlaybackStatsListener(false, this@MusicService))

            // 4. MediaSession + observable player state.
            mediaSession.player = player
            _playerFlow.value = player

            // The rebuilt wrapper starts with playWhenReady=false regardless of what the promoted
            // ExoPlayer was doing, so the intent captured before the rebuild is re-applied here —
            // before the metadata replay below, so listeners never observe a spurious pause.
            if (shouldKeepPlaying && !player.playWhenReady) {
                player.playWhenReady = true
            }

            // 5. Audio effects follow the promoted player's audio session.
            rebindAudioEffectSession(localPlayer.audioSessionId)

            // 6. Queue/metadata state. The incoming player's transition into the target item
            // happened before it was the service player, so the bookkeeping is replayed
            // manually now that ownership has moved.
            val promotedItem = incomingPlayer.getMediaItemAt(targetIndex)
            currentMediaMetadata.value = promotedItem.metadata
            onMediaItemTransition(promotedItem, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)

            // 7. Fade, stop and release ONLY the old outgoing player.
            releaseOutgoingCrossfadePlayer(outgoingPlayer)
            true
        } catch (error: Throwable) {
            Timber.tag(TAG).w(error, "Crossfade promotion failed; incoming player stays active if it is the only audible one")
            // Failure safety: never release the only player that can produce valid audio.
            if (localPlayer !== incomingPlayer) {
                // Promotion did not complete ownership transfer: the outgoing player remains
                // authoritative; detach the incoming one quietly.
                runCatching { incomingPlayer.stop() }
                runCatching { incomingPlayer.release() }
                secondaryCrossfadePlayer = null
                secondaryCrossfadeTarget = null
            }
            false
        } finally {
            crossfadeHandoffInProgress = false
        }
    }

    private fun releaseOutgoingCrossfadePlayer(outgoingPlayer: ExoPlayer) {
        runCatching { outgoingPlayer.volume = 0f }
        runCatching { outgoingPlayer.stop() }
        runCatching { outgoingPlayer.clearMediaItems() }
        removeAudioAuthenticityContext(outgoingPlayer)
        runCatching { outgoingPlayer.release() }
    }

    private fun resolveCrossfadeTargetIndex(
        target: CrossfadeTarget,
        targetPlayer: Player,
    ): Int {
        if (target.index in 0 until targetPlayer.mediaItemCount &&
            targetPlayer.getMediaItemAt(target.index).mediaId == target.mediaId
        ) {
            return target.index
        }

        for (index in 0 until targetPlayer.mediaItemCount) {
            if (targetPlayer.getMediaItemAt(index).mediaId == target.mediaId) {
                return index
            }
        }
        return C.INDEX_UNSET
    }

    private fun requiredCrossfadeStartBufferMs(durationMs: Long): Long =
        (durationMs + CROSSFADE_HANDOFF_BUFFER_MS)
            .coerceAtLeast(CROSSFADE_MIN_BUFFER_BEFORE_START_MS)
            .coerceAtMost(CROSSFADE_MAX_BUFFER_BEFORE_START_MS)

    private fun hasBufferedForSmoothStart(
        targetPlayer: ExoPlayer,
        minimumBufferedMs: Long,
    ): Boolean {
        if (minimumBufferedMs <= 0L) return true
        if (targetPlayer.currentMediaItem
                ?.localConfiguration
                ?.uri
                ?.shouldBypassPlayerCache() == true
        ) {
            return true
        }

        val duration = targetPlayer.duration
        val currentPosition = targetPlayer.currentPosition.coerceAtLeast(0L)
        val remainingDuration =
            if (duration != C.TIME_UNSET && duration > currentPosition) {
                duration - currentPosition
            } else {
                Long.MAX_VALUE
            }
        val requiredBufferedMs = minimumBufferedMs.coerceAtMost(remainingDuration)
        if (requiredBufferedMs <= 0L) return true

        val bufferedDuration = targetPlayer.totalBufferedDuration.coerceAtLeast(0L)
        if (bufferedDuration >= requiredBufferedMs) return true

        return duration != C.TIME_UNSET &&
            targetPlayer.bufferedPosition >= duration - CROSSFADE_END_GUARD_MS
    }

    /** Fully tears down any in-flight crossfade before a user-initiated skip (next/previous). */
    fun prepareForManualSkip() {
        if (!::player.isInitialized) return
        if (!isCrossfading && !crossfadeHandoffInProgress && secondaryCrossfadePlayer == null) return
        cancelCrossfade(resetVolume = true, resetPauseAtEnd = true)
    }

    private fun cancelCrossfade(
        resetVolume: Boolean,
        resetPauseAtEnd: Boolean,
    ) {
        if (isCrossfading || crossfadeHandoffInProgress || secondaryCrossfadePlayer != null) {
            Timber.tag("PlaybackDiagnostics").d(
                "Crossfade canceled progress=%.3f handoffProgress=%.3f resetVolume=%s resetPauseAtEnd=%s",
                crossfadeProgress,
                crossfadeHandoffProgress,
                resetVolume,
                resetPauseAtEnd,
            )
        }
        // Invalidate any in-flight operation so a cancelled crossfade coroutine can never
        // complete an older handoff later.
        crossfadeGeneration.incrementAndGet()
        crossfadeTriggerJob?.cancel()
        crossfadeTriggerJob = null
        crossfadeJob?.cancel()
        crossfadeJob = null
        isCrossfading = false
        crossfadeHandoffInProgress = false
        crossfadeHandoffProgress = 0f
        crossfadeProgress = 0f
        crossfadeIncomingBaseVolume = 1f
        crossfadePlaybackRequested = false
        if (::player.isInitialized && resetPauseAtEnd) {
            localPlayer.pauseAtEndOfMediaItems = false
        }
        releaseSecondaryCrossfadePlayer()
        // A cancel mid-ride can leave the audible player's filter partway closed; open it or the
        // rest of the song plays muffled. A stretch the plan put on the incoming player died with
        // it, so the saved user parameters are simply dropped.
        primaryTransitionFilter.open()
        crossfadeUserPlaybackParameters = null
        crossfadeSmartActive = false
        SmartFadeRuntimeState.mixing.value = false
        SmartFadeRuntimeState.transitionWindow.value = null
        if (resetVolume && ::player.isInitialized) {
            applyEffectiveVolumeImmediately()
        }
    }

    private fun recoverCrossfadeFailure(retryAllowed: Boolean = false) {
        val shouldResumeOutgoing =
            CrossfadePolicy.shouldResumeAfterEnded(
                playbackRequested = crossfadePlaybackRequested,
                hasNextItem = player.hasNextMediaItem(),
                repeatOne = player.repeatMode == REPEAT_MODE_ONE,
                suppressAutoPlayback = suppressAutoPlayback,
            )
        cancelCrossfade(resetVolume = true, resetPauseAtEnd = true)
        if (shouldResumeOutgoing && !player.playWhenReady) {
            player.play()
        } else if (retryAllowed) {
            scheduleCrossfade()
        }
    }

    private fun releaseSecondaryCrossfadePlayer() {
        val playerToRelease = secondaryCrossfadePlayer ?: run {
            secondaryTransitionFilter = null
            return
        }
        secondaryCrossfadePlayer = null
        secondaryCrossfadeTarget = null
        removeAudioAuthenticityContext(playerToRelease)
        secondaryTransitionFilter?.open()
        secondaryTransitionFilter = null
        runCatching { playerToRelease.removeListener(secondaryCrossfadeListener) }
        runCatching { playerToRelease.stop() }
        runCatching { playerToRelease.clearMediaItems() }
        runCatching { playerToRelease.release() }
    }

    private fun calculateAudioNormalizationFactor(
        format: FormatEntity?,
        normalizeAudio: Boolean,
    ): Float {
        Timber.tag("AudioNormalization").d("Audio normalization enabled: $normalizeAudio")
        Timber
            .tag(
                "AudioNormalization",
            ).d("Format loudnessDb: ${format?.loudnessDb}, perceptualLoudnessDb: ${format?.perceptualLoudnessDb}")

        if (!normalizeAudio) {
            Timber.tag("AudioNormalization").d("Normalization disabled - using factor 1.0")
            return 1f
        }

        val loudnessDb = format?.normalizationLoudnessDb()
        if (loudnessDb == null || !loudnessDb.isFinite()) {
            // Downgraded from WARN to DEBUG: this is the expected code path for
            // any non-YouTube source (Tidal, Qobuz, Deezer, JioSaavn, Telegram,
            // local files) because FormatEntity only carries loudness metadata
            // for YouTube streams — for everything else the field is hardcoded
            // to null at the FormatEntity creation site (see the comment at
            // `resolveAudioNormalizationFactor` for the rationale). Logging this
            // at WARN produced 5+ noisy entries per playback session in
            // production logs for every Qobuz track, making it harder to spot
            // real warnings.
            Timber.tag("AudioNormalization").d("Normalization enabled but no valid loudness data available - no normalization applied")
            return 1f
        }

        val rawFactor = 10f.pow(-loudnessDb / 20)
        val factor =
            if (rawFactor.isFinite()) {
                rawFactor.coerceIn(MIN_AUDIO_NORMALIZATION_FACTOR, MAX_AUDIO_NORMALIZATION_FACTOR)
            } else {
                1f
            }

        if (factor != rawFactor) {
            Timber.tag("AudioNormalization").d("Normalization factor clamped from $rawFactor to $factor")
        }
        Timber.tag("AudioNormalization").i("Applying normalization factor: $factor")
        return factor
    }

    private fun resolveAudioNormalizationFactor(
        mediaId: String?,
        format: FormatEntity?,
        normalizeAudio: Boolean,
    ): Float {
        val currentMediaId = mediaId?.takeIf { it.isNotBlank() } ?: return 1f
        if (!normalizeAudio) {
            return 1f
        }

        // A Tidal-served track has no matching loudness metadata (the stored format describes the
        // YouTube stream), so applying it would set the wrong volume. Skip normalization for it.
        if (currentMediaId in tidalActiveMediaIds) {
            audioNormalizationFactorCache[currentMediaId] = 1f
            return 1f
        }

        if (format?.id == currentMediaId) {
            val factor = calculateAudioNormalizationFactor(format, normalizeAudio = true)
            audioNormalizationFactorCache[currentMediaId] = factor
            return factor
        }

        return audioNormalizationFactorCache[currentMediaId] ?: 1f
    }

    private fun FormatEntity.normalizationLoudnessDb(): Float? =
        sequenceOf(perceptualLoudnessDb, loudnessDb)
            .mapNotNull { it?.toFloat() }
            .firstOrNull { it.isFinite() }

    private fun shouldKeepPlaybackAudible(): Boolean {
        if (!::player.isInitialized) return false
        if (player.currentMediaItem == null || !player.playWhenReady) return false
        return player.playbackState != Player.STATE_IDLE && player.playbackState != Player.STATE_ENDED
    }

    private fun restoreAudioFocusVolume() {
        audioFocusVolumeFactor.value = 1f
        hasAudioFocus = true
        lastAudioFocusState = AudioManager.AUDIOFOCUS_GAIN
    }

    private fun pauseForAudioFocusLoss(resumeWhenFocusReturns: Boolean) {
        audioFocusVolumeFactor.value = 1f
        wasPlayingBeforeAudioFocusLoss = resumeWhenFocusReturns && player.playWhenReady
        if (player.playWhenReady) {
            player.pause()
        }
    }

    private fun ensureAudioFocusForActivePlayback(): Boolean {
        if (!player.playWhenReady) return true
        if (requestAudioFocus()) return true
        pauseForAudioFocusLoss(resumeWhenFocusReturns = true)
        return false
    }

    private fun handleAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                audioFocusVolumeFactor.value = 1f

                if (wasPlayingBeforeAudioFocusLoss) {
                    player.play()
                    wasPlayingBeforeAudioFocusLoss = false
                }

                lastAudioFocusState = focusChange
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                pauseForAudioFocusLoss(resumeWhenFocusReturns = false)

                abandonAudioFocus()

                lastAudioFocusState = focusChange
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasAudioFocus = false
                pauseForAudioFocusLoss(resumeWhenFocusReturns = true)

                lastAudioFocusState = focusChange
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Duck instead of pausing. CAN_DUCK losses arrive for route changes (Bluetooth
                // reconnects, assistant beeps, navigation prompts) where stopping playback
                // mid-song — and then racing a crossfade promotion that force-resumes — reads as
                // "the app muted itself". The stream stays alive at MIN_AUDIO_FOCUS_VOLUME_FACTOR;
                // AUDIOFOCUS_GAIN restores the factor to 1f.
                //
                // Focus itself is still held (the system only asked us to lower the volume), so
                // hasAudioFocus stays true: a re-request during the duck must not read as a
                // re-gain and cancel it, and playback paused mid-crossfade must not be forced
                // back on the assumption that focus was lost.
                hasAudioFocus = true
                audioFocusVolumeFactor.value = MIN_AUDIO_FOCUS_VOLUME_FACTOR

                lastAudioFocusState = focusChange
            }

            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> {
                hasAudioFocus = true
                audioFocusVolumeFactor.value = 1f

                if (wasPlayingBeforeAudioFocusLoss) {
                    player.play()
                    wasPlayingBeforeAudioFocusLoss = false
                }

                lastAudioFocusState = focusChange
            }

            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> {
                hasAudioFocus = true
                audioFocusVolumeFactor.value = 1f

                lastAudioFocusState = focusChange
            }
        }
        Timber.tag("PlaybackDiagnostics").d(
            "Audio focus event=%d held=%s playWhenReady=%s isPlaying=%s state=%d gain=%.3f expectedGain=%.3f",
            focusChange,
            hasAudioFocus,
            player.playWhenReady,
            player.isPlaying,
            player.playbackState,
            player.volume,
            currentEffectivePlayerVolume(),
        )
    }

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) {
            // A CAN_DUCK loss is still in effect while the factor is lowered: re-requesting focus
            // (every playback-state event funnels through ensureAudioFocusForActivePlayback) must
            // not restore full volume behind the duck. Only the real re-gain paths clear it.
            if (audioFocusVolumeFactor.value != 1f &&
                lastAudioFocusState != AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
            ) {
                restoreAudioFocusVolume()
            }
            return true
        }

        audioFocusRequest?.let { request ->
            val result = audioManager.requestAudioFocus(request)
            hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (hasAudioFocus) {
                restoreAudioFocusVolume()
            }
            return hasAudioFocus
        }
        return false
    }

    private fun abandonAudioFocus() {
        if (hasAudioFocus) {
            audioFocusRequest?.let { request ->
                audioManager.abandonAudioFocusRequest(request)
                hasAudioFocus = false
            }
        }
    }

    fun hasAudioFocusForPlayback(): Boolean = hasAudioFocus

    private fun isDeviceMutedNow(): Boolean {
        val streamVolume =
            runCatching {
                audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            }.getOrElse { error ->
                reportException(error)
                return player.isDeviceMuted || player.deviceVolume <= 0
            }
        val isStreamMuted =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                runCatching {
                    audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
                }.getOrElse { error ->
                    reportException(error)
                    false
                }

        return isStreamMuted || streamVolume <= 0
    }

    private fun registerMuteRecoveryObserver() {
        if (muteRecoveryObserver != null) return
        val observer =
            object : ContentObserver(Handler(mainLooper)) {
                override fun onChange(selfChange: Boolean) {
                    if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) > 0) {
                        handleDeviceMuteStateChanged()
                    }
                }
            }
        contentResolver.registerContentObserver(
            android.provider.Settings.System.CONTENT_URI,
            true,
            observer,
        )
        muteRecoveryObserver = observer
    }

    private fun unregisterMuteRecoveryObserver() {
        muteRecoveryObserver?.let { contentResolver.unregisterContentObserver(it) }
        muteRecoveryObserver = null
    }

    private fun handleDeviceMuteStateChanged(playbackRequestedWhileMuted: Boolean = false) {
        if (!pauseOnDeviceMuteEnabled) {
            wasAutoPausedByDeviceMute = false
            unregisterMuteRecoveryObserver()
            return
        }

        if (isDeviceMutedNow()) {
            if (playbackRequestedWhileMuted && restoreDeviceMusicVolumeForPlayback()) {
                wasAutoPausedByDeviceMute = false
                unregisterMuteRecoveryObserver()
                return
            }

            val canPauseNow =
                player.currentMediaItem != null &&
                    player.playWhenReady &&
                    player.playbackState != Player.STATE_IDLE &&
                    player.playbackState != Player.STATE_ENDED

            if (canPauseNow) {
                player.pause()
                wasAutoPausedByDeviceMute = true
                registerMuteRecoveryObserver()
                if (playbackRequestedWhileMuted) {
                    showDeviceMutePlaybackNotice()
                }
            }
            return
        }

        unregisterMuteRecoveryObserver()

        if (!wasAutoPausedByDeviceMute) return

        wasAutoPausedByDeviceMute = false
        val canResumeNow =
            player.currentMediaItem != null &&
                player.playbackState != Player.STATE_IDLE &&
                player.playbackState != Player.STATE_ENDED
        if (canResumeNow) {
            player.play()
        }
    }

    private fun restoreDeviceMusicVolumeForPlayback(): Boolean {
        val recoveryPercent = deviceMutePlaybackRecoveryVolumePercent.coerceIn(0, 100)
        if (recoveryPercent <= 0) return false

        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maxVolume <= 0) return false

        val targetVolume =
            ceil(maxVolume * (recoveryPercent / 100.0))
                .toInt()
                .coerceIn(1, maxVolume)

        return runCatching {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) > 0
        }.getOrElse {
            reportException(it)
            false
        }
    }

    private fun showDeviceMutePlaybackNotice() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastDeviceMutePlaybackNoticeAtElapsedMs < DEVICE_MUTE_PLAYBACK_NOTICE_INTERVAL_MS) return
        lastDeviceMutePlaybackNoticeAtElapsedMs = now
        scope.launch(SilentHandler) {
            Toast
                .makeText(
                    this@MusicService,
                    R.string.device_volume_zero_playback_paused,
                    Toast.LENGTH_SHORT,
                ).show()
        }
    }

    private val bluetoothReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
                if (!autoStartOnBluetoothEnabled) return

                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return

                val isAudioDevice =
                    try {
                        val majorClass = device.bluetoothClass?.majorDeviceClass
                        majorClass == BluetoothClass.Device.Major.AUDIO_VIDEO ||
                            majorClass == BluetoothClass.Device.Major.WEARABLE
                    } catch (_: SecurityException) {
                        true
                    }

                if (!isAudioDevice) return

                scope.launch {
                    delay(1500)
                    handleBluetoothAutoStart()
                }
            }
        }

    private fun handleBluetoothAutoStart() {
        if (player.currentMediaItem != null &&
            player.playbackState != Player.STATE_IDLE &&
            player.playbackState != Player.STATE_ENDED
        ) {
            if (!player.playWhenReady) {
                player.play()
            }
            return
        }

        if (player.mediaItemCount > 0) {
            player.prepare()
            player.play()
        }
    }

    @Suppress("DEPRECATION")
    private fun registerBluetoothReceiver() {
        if (bluetoothReceiverRegistered) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val filter = IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(bluetoothReceiver, filter)
        }
        bluetoothReceiverRegistered = true
    }

    private fun unregisterBluetoothReceiver() {
        if (!bluetoothReceiverRegistered) return
        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (_: Exception) {
        }
        bluetoothReceiverRegistered = false
    }

    private fun waitOnNetworkError() {
        waitingForNetworkConnection.value = true
    }

    private fun skipOnError() {
        /**
         * Auto skip to the next media item on error.
         *
         * To prevent a "runaway diesel engine" scenario, force the user to take action after
         * too many errors come up too quickly. Pause to show player "stopped" state
         */
        consecutivePlaybackErr += 2
        val nextWindowIndex = player.nextMediaItemIndex

        if (consecutivePlaybackErr <= MAX_CONSECUTIVE_ERR && nextWindowIndex != C.INDEX_UNSET) {
            player.seekTo(nextWindowIndex, C.TIME_UNSET)
            player.prepare()
            player.play()
            return
        }

        player.pause()
        consecutivePlaybackErr = 0
    }

    private fun stopOnError() {
        player.pause()
    }

    private fun findStreamHttpFailure(
        error: PlaybackException,
    ): androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException? {
        var throwable: Throwable? = error.cause
        while (throwable != null) {
            if (throwable is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                return throwable
            }
            throwable = throwable.cause
        }
        return null
    }

    private fun isRetryableRemoteParserFailure(error: PlaybackException): Boolean {
        if (
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
        ) {
            return true
        }

        var throwable: Throwable? = error.cause
        while (throwable != null) {
            if (
                throwable.message?.contains("Skipping atom with length", ignoreCase = true) == true ||
                    throwable.isMedia3ExtractorBoundsFailure()
            ) {
                return true
            }
            throwable = throwable.cause
        }
        return false
    }

    private fun isCacheCorruptionError(
        error: PlaybackException,
        isContentCached: Boolean,
    ): Boolean {
        val isIoError =
            error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
        val isContainerParseError =
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED

        if (!isIoError && !isContainerParseError) {
            return false
        }

        var throwable: Throwable? = error.cause
        while (throwable != null) {
            when {
                throwable is EOFException -> {
                    return true
                }

                throwable is IOException &&
                    throwable.message?.contains("unexpected end of stream", ignoreCase = true) == true -> {
                    return true
                }

                throwable is IllegalStateException || throwable is IllegalArgumentException -> {
                    if (throwable.stackTrace.any { it.className.startsWith("androidx.media3.extractor") }) {
                        return true
                    }
                }

                isContentCached && throwable.isMedia3ExtractorBoundsFailure() -> {
                    return true
                }

                isContainerParseError && isContentCached && throwable is ParserException -> {
                    return true
                }

                isContainerParseError && isContentCached &&
                    throwable.message?.let {
                        it.contains("Invalid integer size", ignoreCase = true) ||
                            it.contains("Skipping atom with length", ignoreCase = true) ||
                            it.contains("contentIsMalformed=true", ignoreCase = true)
                    } == true -> {
                    return true
                }
            }
            throwable = throwable.cause
        }
        return false
    }

    private fun Throwable.isMedia3ExtractorBoundsFailure(): Boolean =
        this is ArrayIndexOutOfBoundsException &&
            stackTrace.any { it.className.startsWith("androidx.media3.extractor") }

    private fun retryPlaybackAfterStreamFailure(
        mediaId: String,
        isFullyDownloadedMedia: Boolean,
        responseException: androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException,
    ): Boolean {
        if (isFullyDownloadedMedia) return false

        val failedUrl = responseException.dataSpec.uri.toString()
        val requestProfile = StreamClientUtils.resolveRequestProfile(failedUrl)
        val authFingerprint = YouTube.currentPlaybackAuthState().fingerprint
        val cachedFailedUrl = playbackUrlCache[mediaId]?.takeIf { it.url == failedUrl }
        val failedExpiredUrl =
            YTPlayerUtils.isExpiredOrNearExpiredStreamUrl(failedUrl) ||
                (
                    cachedFailedUrl?.let {
                        !it.isValidFor(
                            authFingerprint = authFingerprint,
                            minimumRemainingMs = YTPlayerUtils.STREAM_URL_EXPIRY_SAFETY_MS,
                        )
                    } == true
                )

        playbackUrlCache.remove(mediaId)
        YTPlayerUtils.invalidateCachedStreamUrls(mediaId)
        if (!failedExpiredUrl && requestProfile.clientKey.isNotEmpty()) {
            YTPlayerUtils.markStreamClientFailed(mediaId, requestProfile.clientKey, responseException.responseCode)
        }

        if (!playbackStreamRecoveryTracker.registerRetryAttempt(mediaId)) {
            return false
        }

        Timber.tag("MusicService").i(
            "Retrying playback for %s after stream HTTP %d from %s failed",
            mediaId,
            responseException.responseCode,
            requestProfile.variantLabel,
        )
        val shouldResume = player.playWhenReady
        player.prepare()
        if (shouldResume) player.play()
        return true
    }

    private fun updateInitialBufferRecovery(@Player.State playbackState: Int) {
        val mediaId = player.currentMediaItem?.mediaId
        val shouldWatch =
            playbackState == Player.STATE_BUFFERING &&
                player.playWhenReady &&
                player.currentPosition < INITIAL_BUFFER_STALL_POSITION_MS &&
                mediaId != null &&
                initialBufferRecoveryAttemptedMediaId != mediaId

        if (!shouldWatch) {
            initialBufferRecoveryJob?.cancel()
            initialBufferRecoveryJob = null
            return
        }
        if (initialBufferRecoveryJob?.isActive == true) return

        initialBufferRecoveryJob =
            scope.launch {
                delay(INITIAL_BUFFER_STALL_DELAY_MS)
                if (player.playbackState != Player.STATE_BUFFERING ||
                    !player.playWhenReady ||
                    player.currentPosition >= INITIAL_BUFFER_STALL_POSITION_MS ||
                    player.currentMediaItem?.mediaId != mediaId
                ) {
                    return@launch
                }

                initialBufferRecoveryAttemptedMediaId = mediaId
                Timber.tag(TAG).w(
                    "Initial buffer stalled for %s; invalidating stream caches and re-preparing",
                    mediaId,
                )
                playbackUrlCache.remove(mediaId)
                YTPlayerUtils.invalidateCachedStreamUrls(mediaId)
                if (playbackStreamRecoveryTracker.registerRetryAttempt(mediaId)) {
                    player.prepare()
                    if (player.playWhenReady) player.play()
                }
            }
    }



    private fun updateNotification() {
        try {
            val customLayout =
                listOf(
                    CommandButton
                        .Builder()
                        .setDisplayName(
                            getString(
                                if (currentSong.value?.song?.liked == true) {
                                    R.string.action_remove_like
                                } else {
                                    R.string.action_like
                                },
                            ),
                        ).setIconResId(if (currentSong.value?.song?.liked == true) R.drawable.favorite else R.drawable.favorite_border)
                        .setSessionCommand(CommandToggleLike)
                        .setEnabled(currentSong.value != null)
                        .build(),
                    CommandButton
                        .Builder()
                        .setDisplayName(
                            getString(
                                when (player.repeatMode) {
                                    REPEAT_MODE_OFF -> R.string.repeat_mode_off
                                    REPEAT_MODE_ONE -> R.string.repeat_mode_one
                                    REPEAT_MODE_ALL -> R.string.repeat_mode_all
                                    else -> R.string.repeat_mode_off
                                },
                            ),
                        ).setIconResId(
                            when (player.repeatMode) {
                                REPEAT_MODE_OFF -> R.drawable.repeat
                                REPEAT_MODE_ONE -> R.drawable.repeat_one_on
                                REPEAT_MODE_ALL -> R.drawable.repeat_on
                                else -> R.drawable.repeat
                            },
                        ).setSessionCommand(CommandToggleRepeatMode)
                        .build(),
                    CommandButton
                        .Builder()
                        .setDisplayName(
                            getString(if (player.shuffleModeEnabled) R.string.action_shuffle_off else R.string.action_shuffle_on),
                        ).setIconResId(if (player.shuffleModeEnabled) R.drawable.shuffle_on else R.drawable.shuffle)
                        .setSessionCommand(CommandToggleShuffle)
                        .build(),
                    CommandButton
                        .Builder()
                        .setDisplayName(getString(R.string.start_radio))
                        .setIconResId(R.drawable.radio)
                        .setSessionCommand(CommandToggleStartRadio)
                        .setEnabled(currentSong.value != null)
                        .build(),
                )
            mediaSession.setCustomLayout(customLayout)
        } catch (e: Exception) {
            reportException(e)
        }
    }

    fun refreshPlaybackNotification() {
        updateNotification()
        onUpdateNotification(mediaSession, hasResumablePlaybackNotification())
    }

    private suspend fun recoverSong(
        mediaId: String,
        playbackData: YTPlayerUtils.PlaybackData? = null,
    ) {
        val song = database.song(mediaId).first()
        val mediaMetadata =
            withContext(Dispatchers.Main) {
                player.findNextMediaItemById(mediaId)?.metadata
            } ?: return
        val metadataSource =
            dataStore
                .get(DefaultMetadataSourceKey, MetadataSource.YOUTUBE.name)
                .toEnum(MetadataSource.YOUTUBE)
        val effectiveMetadata =
            if (metadataSource == MetadataSource.SPOTIFY) {
                withTimeoutOrNull(5_000L) {
                    spotifyLibraryRepository.enrichMetadata(mediaMetadata)
                } ?: mediaMetadata
            } else {
                mediaMetadata
            }
        if (effectiveMetadata != mediaMetadata) {
            withContext(Dispatchers.Main) {
                commitResolvedMetadata(mediaId, effectiveMetadata)
            }
        }
        val duration =
            song?.song?.duration?.takeIf { it != -1 }
                ?: effectiveMetadata.duration.takeIf { it != -1 }
                ?: (
                    playbackData?.videoDetails ?: YTPlayerUtils
                        .playerResponseForMetadata(mediaId)
                        .getOrNull()
                        ?.videoDetails
                )?.lengthSeconds?.toInt()
                ?: -1
        database.query {
            if (song == null) {
                insert(effectiveMetadata.copy(duration = duration))
            } else if (song.song.duration == -1) {
                update(song.song.copy(duration = duration))
            }
        }
        if (!database.hasRelatedSongs(mediaId)) {
            val relatedEndpoint =
                YouTube.next(WatchEndpoint(videoId = mediaId)).getOrNull()?.relatedEndpoint
                    ?: return
            val relatedPage = YouTube.related(relatedEndpoint).getOrNull() ?: return
            database.query {
                relatedPage.songs
                    .map(SongItem::toMediaMetadata)
                    .onEach(::insert)
                    .map {
                        RelatedSongMap(
                            songId = mediaId,
                            relatedSongId = it.id,
                        )
                    }.forEach(::insert)
            }
        }
    }

    fun playQueue(
        queue: Queue,
        playWhenReady: Boolean = true,
    ) {
        if (playWhenReady) {
            cancelIdleStop()
            promoteToStartedService()
            ensureStartedAsForeground()
        }
        cancelRestoredQueueHydration()
        ensureScopesActive()
        cancelCrossfade(resetVolume = true, resetPauseAtEnd = true)
        cancelInfiniteQueueBootstrap()
        suppressAutoPlayback = false
        val initialLoadGeneration = ++initialQueueLoadGeneration
        initialQueueLoadInProgress = true
        currentQueue = queue
        queueTitle = null
        val permanentShuffle = dataStore.get(PermanentShuffleKey, false)
        if (!permanentShuffle) {
            player.shuffleModeEnabled = false
        }

        clearAutomix()
        autoAddedMediaIds.clear()
        scope.launch(SilentHandler) {
            var autoLoadMoreEnabled = true
            try {
                autoLoadMoreEnabled = dataStore.getAsync(AutoLoadMoreKey, true)
                val hideExplicit = dataStore.get(HideExplicitKey, false)
                val hideVideo = dataStore.get(HideVideoKey, false)
                val preloadItem =
                    queue.preloadItem
                        ?.toMediaItem()
                        ?.takeUnless { item ->
                            item.hasBlockedArtist(loadBlockedArtistIds())
                        }
                if (preloadItem != null) {
                    player.setMediaItem(preloadItem)
                    player.prepare()
                    player.playWhenReady = playWhenReady
                }
                var initialStatus =
                    withContext(Dispatchers.IO) {
                        queue
                            .getInitialStatus()
                            .filterPlaybackContent(hideExplicit, hideVideo)
                    }
                if (!autoLoadMoreEnabled && queue.shouldExpandToFullQueueWhenAutoLoadMoreDisabled() && queue.hasNextPage()) {
                    val expandedItems = initialStatus.items.toMutableList()
                    var pagesLoaded = 0
                    while (queue.hasNextPage() && pagesLoaded < 200) {
                        pagesLoaded++
                        val nextItems =
                            withContext(Dispatchers.IO) {
                                queue
                                    .nextPage()
                                    .filterPlaybackContent(hideExplicit, hideVideo)
                            }
                        if (nextItems.isNotEmpty()) {
                            expandedItems += nextItems
                        }
                    }
                    initialStatus = initialStatus.copy(items = expandedItems)
                }
                if (initialLoadGeneration != initialQueueLoadGeneration) return@launch
                if (initialStatus.title != null) {
                    queueTitle = initialStatus.title
                }
                if (initialStatus.items.isEmpty()) return@launch
                if (preloadItem != null) {
                    val preloadMediaId = preloadItem.mediaId.trim()
                    val insertionIndex =
                        initialStatus.mediaItemIndex.coerceIn(0, initialStatus.items.size)
                    val itemsBeforeCurrent =
                        initialStatus.items
                            .subList(0, insertionIndex)
                            .filterNot { preloadMediaId.isNotEmpty() && it.mediaId.trim() == preloadMediaId }
                    val itemsAfterCurrent =
                        initialStatus.items
                            .subList(insertionIndex, initialStatus.items.size)
                            .filterNot { preloadMediaId.isNotEmpty() && it.mediaId.trim() == preloadMediaId }

                    player.addMediaItems(0, itemsBeforeCurrent)
                    player.addMediaItems(itemsAfterCurrent)
                    if (player.shuffleModeEnabled) {
                        applyCurrentFirstShuffleOrder()
                    }
                } else {
                    val items = initialStatus.items
                    val index = initialStatus.mediaItemIndex

                    player.setMediaItems(items, index, initialStatus.position)
                    player.prepare()
                    player.playWhenReady = playWhenReady
                    if (player.shuffleModeEnabled) {
                        applyCurrentFirstShuffleOrder()
                    }
                }
            } finally {
                // A superseded load must not clear the guard belonging to the newer queue.
                if (initialLoadGeneration == initialQueueLoadGeneration) {
                    initialQueueLoadInProgress = false
                }
            }

            if (initialLoadGeneration != initialQueueLoadGeneration) return@launch

            // Infinite Queue is a global, persisted choice. Song-start queues should seed radio
            // immediately so the toggle behaves consistently across search/library entry points.
            // For longer queues we still wait until the queue itself runs out of pages.
            if (autoLoadMoreEnabled &&
                player.repeatMode == REPEAT_MODE_OFF &&
                queue.shouldBootstrapInfiniteQueue()
            ) {
                onInfiniteQueueEnabled(queue.infiniteQueueSeedMediaId())
            }
        }
    }

    private fun applyCurrentFirstShuffleOrder() {
        val count = player.mediaItemCount
        if (count <= 1) return
        val currentIndex = player.currentMediaItemIndex.coerceIn(0, count - 1)
        val shuffledIndices = IntArray(count) { it }
        shuffledIndices.shuffle()
        val currentPos = shuffledIndices.indexOf(currentIndex)
        if (currentPos >= 0) {
            shuffledIndices[currentPos] = shuffledIndices[0]
        }
        shuffledIndices[0] = currentIndex
        localPlayer.setShuffleOrder(DefaultShuffleOrder(shuffledIndices, System.currentTimeMillis()))
    }

    private fun buildPlayNextShuffleOrder(
        currentIndex: Int,
        insertionIndex: Int,
        insertionCount: Int,
        // How many of the upcoming (shuffled) items the insertion goes after. 0 = straight after the
        // current song ("Play next"); >0 keeps earlier user-added songs ahead of it (#172).
        skipUpcoming: Int = 0,
    ): DefaultShuffleOrder? {
        if (insertionCount <= 0 || player.currentTimeline.isEmpty) return null

        fun adjustedIndex(index: Int): Int =
            if (index >= insertionIndex) {
                index + insertionCount
            } else {
                index
            }

        val timeline = player.currentTimeline
        val previousIndices = ArrayDeque<Int>()
        var traversalIndex = currentIndex
        while (true) {
            traversalIndex = timeline.getPreviousWindowIndex(traversalIndex, REPEAT_MODE_OFF, true)
            if (traversalIndex == C.INDEX_UNSET) {
                break
            }
            previousIndices.addFirst(adjustedIndex(traversalIndex))
        }

        val nextIndices = mutableListOf<Int>()
        traversalIndex = currentIndex
        while (true) {
            traversalIndex = timeline.getNextWindowIndex(traversalIndex, REPEAT_MODE_OFF, true)
            if (traversalIndex == C.INDEX_UNSET) {
                break
            }
            nextIndices += adjustedIndex(traversalIndex)
        }

        val skip = skipUpcoming.coerceIn(0, nextIndices.size)
        val shuffledIndices =
            buildList(player.mediaItemCount + insertionCount) {
                addAll(previousIndices)
                add(adjustedIndex(currentIndex).takeIf { currentIndex >= insertionIndex } ?: currentIndex)
                addAll(nextIndices.subList(0, skip))
                repeat(insertionCount) { offset ->
                    add(insertionIndex + offset)
                }
                addAll(nextIndices.subList(skip, nextIndices.size))
            }.toIntArray()

        return DefaultShuffleOrder(shuffledIndices, System.currentTimeMillis())
    }

    private fun MediaItem.userQueuedKind(): UserQueuedKind? =
        mediaMetadata.extras
            ?.getInt(EXTRA_USER_QUEUED_KIND, -1)
            ?.let { UserQueuedKind.entries.getOrNull(it) }

    /** Marks an item as added by the user (Play next / Add to queue), so later adds line up behind it. */
    private fun MediaItem.markUserQueued(kind: UserQueuedKind): MediaItem {
        val extras = android.os.Bundle(mediaMetadata.extras ?: android.os.Bundle.EMPTY)
        extras.putInt(EXTRA_USER_QUEUED_KIND, kind.ordinal)
        return buildUpon()
            .setMediaMetadata(mediaMetadata.buildUpon().setExtras(extras).build())
            .build()
    }

    /**
     * The songs straight after the current one (in play order) that the user added by hand, up to
     * the first radio or autoplay song. Ended there, so earlier plays of the same queue never count.
     */
    private fun upcomingUserQueuedKinds(currentIndex: Int): List<UserQueuedKind> {
        val timeline = player.currentTimeline
        if (timeline.isEmpty || currentIndex == C.INDEX_UNSET) return emptyList()
        val kinds = mutableListOf<UserQueuedKind>()
        var index = currentIndex
        while (true) {
            index = timeline.getNextWindowIndex(index, REPEAT_MODE_OFF, player.shuffleModeEnabled)
            if (index == C.INDEX_UNSET || index >= player.mediaItemCount) break
            kinds += player.getMediaItemAt(index).userQueuedKind() ?: break
        }
        return kinds
    }

    fun startRadioSeamlessly() {
        cancelInfiniteQueueBootstrap()
        initialQueueLoadGeneration++
        initialQueueLoadInProgress = false
        suppressAutoPlayback = false
        val currentMediaMetadata = player.currentMetadata ?: return

        val currentIndex = player.currentMediaItemIndex
        val currentMediaId = currentMediaMetadata.id
        if (currentSong.value?.song?.isLocal == true ||
            currentMediaId.isLocalMediaId() ||
            currentMediaId.isTelegramMediaId()
        ) {
            return
        }

        scope.launch(SilentHandler) {
            val radioQueue =
                YouTubeQueue(
                    endpoint = WatchEndpoint(videoId = currentMediaId),
                    followAutomixPreview = true,
                )
            val initialStatus =
                withContext(Dispatchers.IO) {
                    radioQueue
                        .getInitialStatus()
                        .filterPlaybackContent(
                            hideExplicit = dataStore.get(HideExplicitKey, false),
                            hideVideo = dataStore.get(HideVideoKey, false),
                        )
                }

            if (initialStatus.title != null) {
                queueTitle = initialStatus.title
            }

            val radioItems =
                initialStatus.items.filter { item ->
                    item.mediaId != currentMediaId
                }

            if (radioItems.isNotEmpty()) {
                val itemCount = player.mediaItemCount

                if (itemCount > currentIndex + 1) {
                    player.removeMediaItems(currentIndex + 1, itemCount)
                }

                player.addMediaItems(currentIndex + 1, radioItems)
            }

            currentQueue = radioQueue
        }
    }

    fun clearAutomix() {
        autoAddedMediaIds.clear()
    }

    fun onInfiniteQueueDisabled() {
        cancelInfiniteQueueBootstrap()
        val currentIndex = player.currentMediaItemIndex
        val idsToRemove = synchronized(autoAddedMediaIds) { autoAddedMediaIds.toSet() }
        if (idsToRemove.isEmpty()) {
            return
        }
        for (i in player.mediaItemCount - 1 downTo 0) {
            if (i == currentIndex) continue
            val item = player.getMediaItemAt(i)
            if (item.mediaId in idsToRemove) {
                player.removeMediaItem(i)
            }
        }
        autoAddedMediaIds.clear()
        currentQueue = EmptyQueue
    }

    fun onInfiniteQueueEnabled(seedMediaId: String? = null) {
        val currentMeta = player.currentMetadata
        val resolvedSeedMediaId =
            seedMediaId?.trim()?.takeIf { it.isNotBlank() }
                ?: currentMeta?.id?.trim()?.takeIf { it.isNotBlank() }
                ?: return
        if (currentMeta != null && isCurrentPlaybackItemLocal(currentMeta)) return
        if (infiniteQueueJob?.isActive == true) return
        val generation = ++infiniteQueueGeneration
        infiniteQueueLoading.value = true

        infiniteQueueJob =
            scope.launch(SilentHandler) {
                try {
                    val hideExplicit = dataStore.get(HideExplicitKey, false)
                    val hideVideo = dataStore.get(HideVideoKey, false)
                    val radioQueue =
                        YouTubeQueue(
                            WatchEndpoint(videoId = resolvedSeedMediaId),
                            followAutomixPreview = true,
                        )
                    val status =
                        withContext(Dispatchers.IO) {
                            radioQueue
                                .getInitialStatus()
                                .filterPlaybackContent(hideExplicit, hideVideo)
                        }
                    val knownIds =
                        (0 until player.mediaItemCount)
                            .mapTo(mutableSetOf()) { player.getMediaItemAt(it).mediaId }
                    val newItems = status.items.filter { knownIds.add(it.mediaId) }.toMutableList()
                    var loadedPageCount = 1

                    while (
                        newItems.isEmpty() &&
                        radioQueue.hasNextPage() &&
                        loadedPageCount < INFINITE_QUEUE_MAX_BOOTSTRAP_PAGES
                    ) {
                        loadedPageCount++
                        val page =
                            withContext(Dispatchers.IO) {
                                radioQueue
                                    .nextPage()
                                    .filterPlaybackContent(hideExplicit, hideVideo)
                            }
                        newItems += page.filter { knownIds.add(it.mediaId) }
                    }

                    if (generation != infiniteQueueGeneration) return@launch

                    if (newItems.isNotEmpty()) {
                        player.addMediaItems(newItems)
                        newItems.forEach { autoAddedMediaIds.add(it.mediaId) }
                    }

                    currentQueue = radioQueue

                    if (player.playbackState == Player.STATE_ENDED ||
                        player.mediaItemCount == player.currentMediaItemIndex + 1
                    ) {
                        player.seekToNext()
                        player.play()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Failed to bootstrap auto-queue")
                } finally {
                    if (generation == infiniteQueueGeneration) {
                        infiniteQueueJob = null
                        infiniteQueueLoading.value = false
                    }
                }
            }
    }

    private fun cancelInfiniteQueueBootstrap() {
        infiniteQueueGeneration++
        infiniteQueueJob?.cancel()
        infiniteQueueJob = null
        infiniteQueueLoading.value = false
    }

    fun stopAndClearPlayback(clearPersistentState: Boolean = false) {
        cancelRestoredQueueHydration()
        cancelInfiniteQueueBootstrap()
        initialQueueLoadGeneration++
        initialQueueLoadInProgress = false
        suppressAutoPlayback = true
        cancelCrossfade(resetVolume = true, resetPauseAtEnd = true)
        clearAutomix()
        currentQueue = EmptyQueue
        queueTitle = null
        waitingForNetworkConnection.value = false
        currentMediaMetadata.value = null
        player.playWhenReady = false
        player.stop()
        player.clearMediaItems()
        abandonAudioFocus()
        closeAudioEffectSession()
        consecutivePlaybackErr = 0
        // Per-media-id resolution caches grow one entry per unique track played and were never
        // pruned, so a long listening session leaked them indefinitely. A full stop clears the
        // queue and leaves no active track, so every entry is now stale — drop them here. They
        // repopulate on the next resolve at no correctness cost.
        playbackUrlCache.clear()
        remotePlaybackTrackingUrlCache.clear()
        contentLengthCache.clear()
        directStreamCache.clear()
        audioNormalizationFactorCache.clear()
        resolvedSourcesByMediaId.clear()
        if (clearPersistentState) {
            clearPersistedQueueFiles()
        }
    }

    fun playNext(items: List<MediaItem>) = insertUserQueued(items, UserQueuedKind.PLAY_NEXT)

    fun addToQueue(items: List<MediaItem>) = insertUserQueued(items, UserQueuedKind.ADD_TO_QUEUE)

    /**
     * Places hand-queued songs behind earlier ones: Play next behind earlier Play next songs, Add to
     * queue behind every hand-queued song, both ahead of radio and autoplay songs (#172). With the
     * setting off, Play next goes straight after the current song and Add to queue to the very end.
     */
    private fun insertUserQueued(
        items: List<MediaItem>,
        kind: UserQueuedKind,
    ) {
        val allowedItems =
            items
                .filterBlockedArtists(blockedArtistIds)
                .filterVideo(hideMusicVideos)
        if (allowedItems.isEmpty()) return
        suppressAutoPlayback = false
        val keepInOrder = dataStore.get(QueueAddAfterManualKey, true)
        if (!keepInOrder && kind == UserQueuedKind.ADD_TO_QUEUE) {
            player.addMediaItems(allowedItems)
            player.prepare()
            return
        }
        val queuedItems = if (keepInOrder) allowedItems.map { it.markUserQueued(kind) } else allowedItems
        val currentIndex = player.currentMediaItemIndex
        val hasQueue = player.mediaItemCount > 0 && currentIndex != C.INDEX_UNSET
        val skip =
            if (keepInOrder && hasQueue) {
                userQueueSkipCount(upcomingUserQueuedKinds(currentIndex), kind)
            } else {
                0
            }
        val shuffled = player.shuffleModeEnabled && hasQueue
        // In shuffle the play order is the shuffle order, so the physical slot only has to be free.
        val insertionIndex =
            when {
                !hasQueue -> player.mediaItemCount
                shuffled && kind == UserQueuedKind.ADD_TO_QUEUE -> player.mediaItemCount
                shuffled -> currentIndex + 1
                else -> (currentIndex + 1 + skip).coerceAtMost(player.mediaItemCount)
            }
        val shuffleOrder =
            if (shuffled) {
                buildPlayNextShuffleOrder(
                    currentIndex = currentIndex,
                    insertionIndex = insertionIndex,
                    insertionCount = queuedItems.size,
                    skipUpcoming = skip,
                )
            } else {
                null
            }

        player.addMediaItems(insertionIndex, queuedItems)
        shuffleOrder?.let(localPlayer::setShuffleOrder)
        player.prepare()
    }

    fun moveQueueItemToNext(mediaItemIndex: Int) {
        val currentIndex = player.currentMediaItemIndex
        if (
            player.mediaItemCount < 2 ||
            currentIndex == C.INDEX_UNSET ||
            mediaItemIndex !in 0 until player.mediaItemCount ||
            mediaItemIndex == currentIndex
        ) {
            return
        }

        if (player.shuffleModeEnabled) {
            val shuffledIndices = player.getQueueWindows().map { it.firstPeriodIndex }.toMutableList()
            val sourcePosition = shuffledIndices.indexOf(mediaItemIndex)
            val currentPosition = shuffledIndices.indexOf(currentIndex)
            if (sourcePosition == -1 || currentPosition == -1) return

            val destinationPosition = (currentPosition + 1).coerceAtMost(shuffledIndices.lastIndex)
            if (sourcePosition == destinationPosition) return

            shuffledIndices.move(sourcePosition, destinationPosition)
            localPlayer.setShuffleOrder(
                DefaultShuffleOrder(
                    shuffledIndices.toIntArray(),
                    System.currentTimeMillis(),
                ),
            )
            return
        }

        val destinationIndex = (currentIndex + 1).coerceAtMost(player.mediaItemCount - 1)
        if (mediaItemIndex != destinationIndex) {
            player.moveMediaItem(mediaItemIndex, destinationIndex)
        }
    }

    fun playFromVoiceSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        ensureScopesActive()
        scope.launch(SilentHandler) {
            val mediaItems =
                withContext(Dispatchers.IO) {
                    mediaLibrarySessionCallback.resolveVoiceMediaItems(trimmed)
                }
            if (mediaItems.isEmpty()) return@launch
            playQueue(ListQueue(items = mediaItems))
        }
    }

    private fun toggleLibrary() {
        database.query {
            currentSong.value?.let {
                update(it.song.toggleLibrary())
            }
        }
    }

    fun toggleLike() {
        val mediaMetadata = currentMediaMetadata.value ?: return
        ioScope.launch {
            try {
                val result =
                    toggleLikeMutex.withLock {
                        val requestedSong =
                            database.withTransaction {
                                val currentSongEntity =
                                    getSongById(mediaMetadata.id)
                                        ?: run {
                                            insert(mediaMetadata) {
                                                it.copy(isLocal = mediaMetadata.id.isLocalMediaId())
                                            }
                                            getSongById(mediaMetadata.id)
                                        }
                                        ?: return@withTransaction null
                                // State change only: SyncUtils.likeSong persists the row and
                                // drives the remote like, so writing it here too would race
                                // the sync path and write it twice.
                                currentSongEntity.song.toggleLike()
                            } ?: return@withLock null
                        syncUtils.likeSong(requestedSong)
                    } ?: return@launch
                val song =
                    result.getOrElse { error ->
                        reportException(error)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MusicService, R.string.error_unknown, Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }

                // Check if auto-download on like is enabled and the song is now liked
                if (!song.isLocal && dataStore.get(AutoDownloadOnLikeKey, false) && song.liked) {
                    // Trigger download for the liked song
                    val downloadRequest =
                        androidx.media3.exoplayer.offline.DownloadRequest
                            .Builder(song.id, song.id.toUri())
                            .setCustomCacheKey(song.id)
                            .setData(song.title.toByteArray())
                            .build()
                    androidx.media3.exoplayer.offline.DownloadService.sendAddDownload(
                        this@MusicService,
                        ExoDownloadService::class.java,
                        downloadRequest,
                        false,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                reportException(error)
            }
        }
    }

    fun toggleStartRadio() {
        startRadioSeamlessly()
    }

    private fun decodeBandLevelsMb(raw: String?): List<Int> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { EqualizerJson.json.decodeFromString<List<Int>>(raw) }.getOrNull() ?: emptyList()
    }

    private fun encodeBandLevelsMb(levelsMb: List<Int>): String =
        runCatching {
            EqualizerJson.json.encodeToString(levelsMb)
        }.getOrNull().orEmpty()

    private fun readEqSettingsFromPrefs(prefs: Preferences): EqSettings {
        val levels = decodeBandLevelsMb(prefs[EqualizerBandLevelsMbKey])
        return EqSettings(
            enabled = prefs[EqualizerEnabledKey] ?: false,
            bandLevelsMb = levels,
            outputGainEnabled = prefs[EqualizerOutputGainEnabledKey] ?: false,
            outputGainMb = prefs[EqualizerOutputGainMbKey] ?: 0,
            bassBoostEnabled = prefs[EqualizerBassBoostEnabledKey] ?: false,
            bassBoostStrength = (prefs[EqualizerBassBoostStrengthKey] ?: 0).coerceIn(0, 1000),
            virtualizerEnabled = prefs[EqualizerVirtualizerEnabledKey] ?: false,
            virtualizerStrength = (prefs[EqualizerVirtualizerStrengthKey] ?: 0).coerceIn(0, 1000),
            autoHeadroomEnabled = prefs[EqualizerAutoHeadroomEnabledKey] ?: false,
        )
    }

    fun applyEqFlatPreset() {
        ioScope.launch {
            val caps = eqCapabilities.value
            val bandCount =
                caps?.bandCount ?: equalizer?.let { readAudioEffectValue("equalizer band count") { it.numberOfBands.toInt() } } ?: 0
            val encoded = encodeBandLevelsMb(List(bandCount.coerceAtLeast(0)) { 0 })
            dataStore.edit { prefs ->
                prefs[EqualizerEnabledKey] = true
                prefs[EqualizerBandLevelsMbKey] = encoded
                prefs[EqualizerSelectedProfileIdKey] = "flat"
            }
        }
    }

    fun applySystemEqPreset(presetIndex: Int) {
        scope.launch {
            ensureAudioEffects(localPlayer.audioSessionId)
            val eq = equalizer ?: return@launch
            val maxPreset = readAudioEffectValue("equalizer preset count") { eq.numberOfPresets.toInt() } ?: 0
            if (presetIndex !in 0 until maxPreset) return@launch

            runCatching { eq.usePreset(presetIndex.toShort()) }.getOrNull() ?: return@launch

            val bandCount = readAudioEffectValue("equalizer band count") { eq.numberOfBands.toInt() } ?: 0
            val levels =
                (0 until bandCount).map { band ->
                    readAudioEffectValue("equalizer band level for band $band") {
                        eq.getBandLevel(band.toShort()).toInt()
                    } ?: 0
                }

            val encoded = encodeBandLevelsMb(levels)
            if (encoded.isBlank()) return@launch

            ioScope.launch {
                dataStore.edit { prefs ->
                    prefs[EqualizerEnabledKey] = true
                    prefs[EqualizerBandLevelsMbKey] = encoded
                    prefs[EqualizerSelectedProfileIdKey] = "system:$presetIndex"
                }
            }
        }
    }

    private fun resampleLevelsByIndex(
        levelsMb: List<Int>,
        targetCount: Int,
    ): List<Int> {
        if (targetCount <= 0) return emptyList()
        if (levelsMb.isEmpty()) return List(targetCount) { 0 }
        if (levelsMb.size == targetCount) return levelsMb
        if (targetCount == 1) return listOf(levelsMb.sum() / levelsMb.size)

        val lastIndex = levelsMb.lastIndex.toFloat().coerceAtLeast(1f)
        return List(targetCount) { i ->
            val pos = i.toFloat() * lastIndex / (targetCount - 1).toFloat()
            val lo =
                kotlin.math
                    .floor(pos)
                    .toInt()
                    .coerceIn(0, levelsMb.lastIndex)
            val hi =
                kotlin.math
                    .ceil(pos)
                    .toInt()
                    .coerceIn(0, levelsMb.lastIndex)
            val t = (pos - lo.toFloat()).coerceIn(0f, 1f)
            val a = levelsMb[lo]
            val b = levelsMb[hi]
            (a + ((b - a) * t)).toInt()
        }
    }

    private inline fun <T> readAudioEffectValue(
        operation: String,
        block: () -> T,
    ): T? =
        runCatching(block)
            .onFailure { error ->
                Timber.tag("MusicService").w(error, "Audio effect query failed: %s", operation)
            }.getOrNull()

    private fun updateEqCapabilitiesFromEffect(eq: Equalizer) {
        val bandCount = readAudioEffectValue("equalizer band count") { eq.numberOfBands.toInt().coerceAtLeast(0) } ?: 0
        val range = readAudioEffectValue("equalizer band range") { eq.bandLevelRange }
        val minMb = range?.getOrNull(0)?.toInt() ?: -1500
        val maxMb = range?.getOrNull(1)?.toInt() ?: 1500
        val center =
            (0 until bandCount).map { band ->
                (
                    readAudioEffectValue("equalizer center frequency for band $band") {
                        eq.getCenterFreq(band.toShort())
                    } ?: 0
                ) / 1000
            }
        val presetCount = readAudioEffectValue("equalizer preset count") { eq.numberOfPresets.toInt().coerceAtLeast(0) } ?: 0
        val presets =
            (0 until presetCount).map { idx ->
                readAudioEffectValue("equalizer preset name for preset $idx") {
                    eq.getPresetName(idx.toShort()).toString()
                } ?: "Preset ${idx + 1}"
            }
        val capabilities =
            EqCapabilities(
                bandCount = bandCount,
                minBandLevelMb = minMb,
                maxBandLevelMb = maxMb,
                centerFreqHz = center,
                systemPresets = presets,
            )
        eqCapabilities.value = capabilities
        equalizerPlaybackController.updateCapabilities(capabilities)
    }

    private fun releaseAudioEffectInstances() {
        audioEffectsSessionId = null
        try {
            equalizer?.release()
        } catch (_: Exception) {
        }
        try {
            bassBoost?.release()
        } catch (_: Exception) {
        }
        try {
            virtualizer?.release()
        } catch (_: Exception) {
        }
        try {
            loudnessEnhancer?.release()
        } catch (_: Exception) {
        }
        equalizer = null
        bassBoost = null
        virtualizer = null
        loudnessEnhancer = null
        eqCapabilities.value = null
        equalizerPlaybackController.updateCapabilities(null)
    }

    private fun releaseAudioEffects() {
        audioEffectsInitializationJob?.cancel()
        audioEffectsInitializationJob = null
        releaseAudioEffectInstances()
    }

    /**
     * Keeps the bit-perfect status current across USB plugs. A DAC attached mid-track engages on the
     * next AudioTrack (the next song or seek); one removed mid-track falls back to the phone's
     * normal output through the platform's own rerouting.
     */
    private fun registerBitPerfectDeviceCallback() {
        val manager = getSystemService(AudioManager::class.java) ?: return
        val callback =
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                    BitPerfectUsbOutput.refreshDeviceStatus(this@MusicService)
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                    if (!BitPerfectUsbOutput.hasUsbOutput(this@MusicService)) {
                        BitPerfectUsbOutput.clear(this@MusicService)
                    }
                    BitPerfectUsbOutput.refreshDeviceStatus(this@MusicService)
                }
            }
        manager.registerAudioDeviceCallback(callback, Handler(mainLooper))
        bitPerfectDeviceCallback = callback
    }

    private fun ensureAudioEffects(sessionId: Int) {
        if (sessionId <= 0) return
        if (bitPerfectOutputActive && BitPerfectUsbOutput.enabled) {
            // Session effects (EQ, bass boost, virtualizer, loudness) would either alter the
            // samples or make the platform refuse the bit-perfect path, so none are attached.
            releaseAudioEffectInstances()
            return
        }
        if (audioEffectsSessionId == sessionId && equalizer != null) return

        audioEffectsInitializationJob?.cancel()
        audioEffectsInitializationJob = null
        if (initializeAudioEffects(sessionId)) return

        audioEffectsInitializationJob =
            scope.launch {
                repeat(AUDIO_EFFECT_INITIALIZATION_MAX_ATTEMPTS - 1) {
                    delay(AUDIO_EFFECT_INITIALIZATION_RETRY_DELAY_MS)
                    if (localPlayer.audioSessionId != sessionId || !shouldKeepAudioEffectSessionOpen()) {
                        return@launch
                    }
                    if (initializeAudioEffects(sessionId)) return@launch
                }
            }
    }

    private fun initializeAudioEffects(sessionId: Int): Boolean {
        releaseAudioEffectInstances()
        audioEffectsSessionId = sessionId

        equalizer = createAudioEffect("Equalizer", sessionId) { Equalizer(0, sessionId) }
        bassBoost = createAudioEffect("BassBoost", sessionId) { BassBoost(0, sessionId) }
        virtualizer = createAudioEffect("Virtualizer", sessionId) { Virtualizer(0, sessionId) }
        loudnessEnhancer = createAudioEffect("LoudnessEnhancer", sessionId) { LoudnessEnhancer(sessionId) }

        equalizer?.let(::updateEqCapabilitiesFromEffect)
        applyEqSettingsToEffects(desiredEqSettings.value)
        return equalizer != null
    }

    private inline fun <T> createAudioEffect(
        name: String,
        sessionId: Int,
        factory: () -> T,
    ): T? =
        runCatching(factory)
            .onFailure { error ->
                Timber.tag(TAG).w(error, "%s initialization failed for audio session %d", name, sessionId)
            }.getOrNull()

    private fun applyEqSettingsToEffects(settings: EqSettings) {
        val eq = equalizer ?: return
        val caps = eqCapabilities.value
        val bandCount = caps?.bandCount ?: readAudioEffectValue("equalizer band count") { eq.numberOfBands.toInt() } ?: 0
        val minMb =
            caps?.minBandLevelMb ?: readAudioEffectValue("equalizer minimum band level") { eq.bandLevelRange.getOrNull(0)?.toInt() }
                ?: -1500
        val maxMb =
            caps?.maxBandLevelMb ?: readAudioEffectValue("equalizer maximum band level") { eq.bandLevelRange.getOrNull(1)?.toInt() } ?: 1500

        val levels = resampleLevelsByIndex(settings.bandLevelsMb, bandCount)
        runCatching { eq.enabled = settings.enabled }

        for (band in 0 until bandCount) {
            val levelMb = levels.getOrNull(band)?.coerceIn(minMb, maxMb) ?: 0
            runCatching { eq.setBandLevel(band.toShort(), levelMb.toShort()) }
        }

        bassBoost?.let { bb ->
            runCatching { bb.enabled = settings.enabled && settings.bassBoostEnabled }
            runCatching { bb.setStrength(settings.bassBoostStrength.toShort()) }
        }

        virtualizer?.let { v ->
            runCatching { v.enabled = settings.enabled && settings.virtualizerEnabled }
            runCatching { v.setStrength(settings.virtualizerStrength.toShort()) }
        }

        loudnessEnhancer?.let { le ->
            val automaticHeadroomMb = -(levels.maxOrNull()?.coerceAtLeast(0) ?: 0)
            val gainMb =
                when {
                    settings.autoHeadroomEnabled -> automaticHeadroomMb
                    settings.outputGainEnabled -> settings.outputGainMb.coerceIn(-1500, 1500)
                    else -> 0
                }
            runCatching { le.setTargetGain(gainMb) }
            runCatching { le.enabled = settings.enabled && (settings.autoHeadroomEnabled || settings.outputGainEnabled) }
        }
    }

    private fun shouldKeepAudioEffectSessionOpen(): Boolean {
        val playbackState = localPlayer.playbackState
        return playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_READY
    }

    private fun reconcileAudioEffectSession() {
        if (!shouldKeepAudioEffectSessionOpen()) {
            closeAudioEffectSession()
            return
        }

        val sessionId = localPlayer.audioSessionId
        if (sessionId > 0) {
            rebindAudioEffectSession(sessionId)
        }
    }

    private fun openAudioEffectSession() {
        if (isAudioEffectSessionOpened) return
        val sessionId = localPlayer.audioSessionId
        if (sessionId <= 0) return
        isAudioEffectSessionOpened = true
        openedAudioSessionId = sessionId
        ensureAudioEffects(sessionId)
        sendOpenAudioEffectSessionBroadcast(sessionId)
    }

    private fun closeAudioEffectSession() {
        if (!isAudioEffectSessionOpened) return
        isAudioEffectSessionOpened = false
        val sessionId = openedAudioSessionId ?: localPlayer.audioSessionId
        openedAudioSessionId = null
        releaseAudioEffects()
        if (sessionId <= 0) return
        sendCloseAudioEffectSessionBroadcast(sessionId)
    }

    private fun rebindAudioEffectSession(newSessionId: Int) {
        if (newSessionId <= 0 || !shouldKeepAudioEffectSessionOpen()) return
        val oldSessionId = openedAudioSessionId
        if (!isAudioEffectSessionOpened) {
            openAudioEffectSession()
            return
        }
        if (oldSessionId == newSessionId) {
            ensureAudioEffects(newSessionId)
            return
        }

        if (oldSessionId != null && oldSessionId > 0) {
            sendCloseAudioEffectSessionBroadcast(oldSessionId)
        }
        openedAudioSessionId = newSessionId
        ensureAudioEffects(newSessionId)
        sendOpenAudioEffectSessionBroadcast(newSessionId)
    }

    private fun sendOpenAudioEffectSessionBroadcast(sessionId: Int) {
        sendBroadcast(
            Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            },
        )
    }

    private fun sendCloseAudioEffectSessionBroadcast(sessionId: Int) {
        sendBroadcast(
            Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            },
        )
    }

    private fun historyThresholdMs(): Long =
        (runCatching { dataStore[HistoryDuration] }.getOrNull() ?: HISTORY_DURATION_DEFAULT)
            .coerceIn(HISTORY_DURATION_MIN, HISTORY_DURATION_MAX)
            .toLong() * 1000L

    private fun currentHistoryPlayedMs(nowElapsedMs: Long = android.os.SystemClock.elapsedRealtime()): Long {
        val runningPlayMs =
            currentHistoryStartedAtElapsedMs
                ?.let { (nowElapsedMs - it).coerceAtLeast(0L) }
                ?: 0L
        return currentHistoryAccumulatedPlayMs + runningPlayMs
    }

    private fun flushCurrentHistoryPlayedTime(nowElapsedMs: Long = android.os.SystemClock.elapsedRealtime()) {
        currentHistoryAccumulatedPlayMs = currentHistoryPlayedMs(nowElapsedMs)
        currentHistoryStartedAtElapsedMs = null
    }

    private fun updatePendingHistoryFinalization(
        mediaId: String,
        sessionToken: Long,
        result: ImmediateHistoryResult,
    ) {
        val pendingSessions = pendingHistoryFinalizations[mediaId] ?: return
        val index = pendingSessions.indexOfFirst { it.sessionToken == sessionToken }
        if (index == -1) return

        val existing = pendingSessions[index]
        pendingSessions[index] =
            existing.copy(
                eventId = result.eventId ?: existing.eventId,
                remoteRegistered = existing.remoteRegistered || result.remoteRegistered,
            )
    }

    private fun enqueueCurrentHistorySessionForFinalization() {
        val mediaId = currentHistoryMediaId ?: return
        if (currentHistorySessionQueued) return

        pendingHistoryFinalizations
            .getOrPut(mediaId) { mutableListOf() }
            .add(
                PendingHistoryFinalization(
                    sessionToken = currentHistorySessionToken,
                    eventId = currentHistoryEventId,
                    remoteRegistered = currentHistoryRemoteRegistered,
                ),
            )
        currentHistorySessionQueued = true
    }

    private fun popPendingHistoryFinalization(mediaId: String): PendingHistoryFinalization? {
        val pendingSessions = pendingHistoryFinalizations[mediaId] ?: return null
        val pending = pendingSessions.firstOrNull() ?: return null
        pendingSessions.removeAt(0)
        if (pendingSessions.isEmpty()) {
            pendingHistoryFinalizations.remove(mediaId)
        }
        return pending
    }

    private fun beginHistorySession(
        mediaId: String?,
        forceNew: Boolean = false,
    ) {
        val normalizedMediaId = mediaId?.trim()?.takeIf { it.isNotEmpty() }
        if (!forceNew && currentHistoryMediaId == normalizedMediaId && currentHistorySessionToken != 0L) {
            updateHistoryTrackingPlaybackState()
            return
        }

        historyThresholdJob?.cancel()
        historyThresholdJob = null
        flushCurrentHistoryPlayedTime()
        enqueueCurrentHistorySessionForFinalization()

        currentHistorySessionToken = ++nextHistorySessionToken
        currentHistoryMediaId = normalizedMediaId
        currentHistoryAccumulatedPlayMs = 0L
        currentHistoryStartedAtElapsedMs = null
        currentHistoryEventId = null
        currentHistoryRemoteRegistered = false
        currentHistoryImmediateAttempted = false
        currentHistorySessionQueued = false

        updateHistoryTrackingPlaybackState()
    }

    private fun updateHistoryTrackingPlaybackState() {
        val mediaId = currentHistoryMediaId
        if (mediaId == null || currentHistorySessionQueued) {
            historyThresholdJob?.cancel()
            historyThresholdJob = null
            currentHistoryStartedAtElapsedMs = null
            return
        }

        if (player.isPlaying) {
            if (currentHistoryStartedAtElapsedMs == null) {
                currentHistoryStartedAtElapsedMs = android.os.SystemClock.elapsedRealtime()
            }
        } else {
            flushCurrentHistoryPlayedTime()
        }

        syncHistoryThresholdJob()
    }

    private fun syncHistoryThresholdJob() {
        historyThresholdJob?.cancel()
        historyThresholdJob = null

        val mediaId = currentHistoryMediaId ?: return
        if (currentHistorySessionQueued) return
        if (dataStore.get(PauseListenHistoryKey, false)) return
        if (currentHistoryEventId != null && currentHistoryRemoteRegistered) return

        val thresholdMs = historyThresholdMs()
        val playedMs = currentHistoryPlayedMs()
        if (playedMs >= thresholdMs) {
            if (!currentHistoryImmediateAttempted) {
                maybeRecordCurrentPlaybackHistory()
            }
            return
        }
        if (!player.isPlaying) return

        historyThresholdJob =
            scope.launch {
                delay((thresholdMs - playedMs).coerceAtLeast(0L))
                maybeRecordCurrentPlaybackHistory()
            }
    }

    private fun maybeRecordCurrentPlaybackHistory() {
        val mediaId = currentHistoryMediaId ?: return
        if (currentHistorySessionQueued) return
        if (dataStore.get(PauseListenHistoryKey, false)) return
        // Episodes are not songs: recording them would insert podcast rows into the
        // song table (losing the marker on read-back) and pollute listening stats.
        if (player.currentMetadata?.takeIf { it.id == mediaId }?.isPodcast == true) return

        val thresholdMs = historyThresholdMs()
        val playedMs = currentHistoryPlayedMs()
        if (playedMs < thresholdMs) {
            syncHistoryThresholdJob()
            return
        }

        val sessionToken = currentHistorySessionToken
        if (historyRecordingJobs.containsKey(sessionToken)) return
        currentHistoryImmediateAttempted = true

        val eventIdSnapshot = currentHistoryEventId
        val remoteRegisteredSnapshot = currentHistoryRemoteRegistered
        val mediaMetadataSnapshot = player.currentMetadata?.takeIf { it.id == mediaId }

        val deferred =
            scope.async {
                withContext(Dispatchers.IO) {
                    val resolvedEventId =
                        eventIdSnapshot
                            ?: insertPlaybackHistoryEvent(
                                mediaId = mediaId,
                                playTimeMs = playedMs,
                                mediaMetadata = mediaMetadataSnapshot,
                            )
                    val remoteRegistered = remoteRegisteredSnapshot || registerRemotePlaybackHistory(mediaId)
                    ImmediateHistoryResult(
                        eventId = resolvedEventId,
                        remoteRegistered = remoteRegistered,
                    )
                }
            }

        historyRecordingJobs[sessionToken] = deferred
        scope.launch {
            val result =
                runCatching { deferred.await() }
                    .onFailure(::reportException)
                    .getOrNull()

            historyRecordingJobs.remove(sessionToken)

            if (result != null) {
                if (currentHistorySessionToken == sessionToken &&
                    !currentHistorySessionQueued &&
                    currentHistoryMediaId == mediaId
                ) {
                    currentHistoryEventId = result.eventId ?: currentHistoryEventId
                    currentHistoryRemoteRegistered = currentHistoryRemoteRegistered || result.remoteRegistered
                } else {
                    updatePendingHistoryFinalization(mediaId, sessionToken, result)
                }
            }

            syncHistoryThresholdJob()
        }
    }

    private suspend fun insertPlaybackHistoryEvent(
        mediaId: String,
        playTimeMs: Long,
        mediaMetadata: moe.rukamori.archivetune.models.MediaMetadata?,
    ): Long? =
        try {
            database.withTransaction {
                if (song(mediaId).first() == null && mediaMetadata != null) {
                    insert(mediaMetadata)
                }

                insert(
                    Event(
                        songId = mediaId,
                        timestamp = LocalDateTime.now(),
                        playTime = playTimeMs,
                    ),
                ).takeIf { it > 0L }
            }
        } catch (_: SQLException) {
            null
        } catch (throwable: Throwable) {
            reportException(throwable)
            null
        }

    private suspend fun registerRemotePlaybackHistory(mediaId: String): Boolean {
        // Reporting to the YouTube account's listen history is keyed off the YouTube video id, so it
        // works regardless of which source (YouTube/Tidal/Qobuz) actually streamed the audio. This
        // opt-out toggle disables only the remote report; the local play-history DB is unaffected
        // (that stays governed by PauseListenHistoryKey).
        if (!dataStore.get(SyncPlaybackToYouTubeHistoryKey, true)) {
            Timber.tag("MusicService").d("Skipping remote YouTube history for %s (sync disabled)", mediaId)
            return false
        }
        if (database
                .song(mediaId)
                .first()
                ?.song
                ?.isLocal == true
        ) {
            return false
        }

        suspend fun registerTracking(playbackTrackingUrl: String): Boolean =
            YouTube
                .registerPlayback(
                    playlistId = null,
                    playbackTracking = playbackTrackingUrl,
                ).onFailure { throwable ->
                    if (throwable is CancellationException) {
                        throw throwable
                    }
                    Timber.tag("MusicService").w(
                        throwable,
                        "Failed to register remote playback history for %s",
                        mediaId,
                    )
                }.onSuccess {
                    YouTube.notifyHistorySynced()
                }.isSuccess

        remotePlaybackTrackingUrlCache[mediaId]?.let { cachedPlaybackTrackingUrl ->
            if (registerTracking(cachedPlaybackTrackingUrl)) {
                return true
            }
            remotePlaybackTrackingUrlCache.remove(mediaId, cachedPlaybackTrackingUrl)
        }

        val remotePlaybackTracking =
            retryWithoutPlaybackLoginContext {
                YTPlayerUtils.playerResponseForMetadata(mediaId)
            }.onFailure { throwable ->
                if (throwable is CancellationException) {
                    throw throwable
                }
                when (throwable) {
                    is YTPlayerUtils.InvalidPlaybackLoginContextException -> {
                        promptLoginRecovery(mediaId, throwable.targetUrl)
                    }

                    is YTPlayerUtils.LoginRequiredForPlaybackException -> {
                        Timber.tag("MusicService").w(
                            throwable,
                            "Playback confirmation is required before refreshing remote playback tracking for %s",
                            mediaId,
                        )
                    }

                    else -> {
                        Timber.tag("MusicService").w(
                            throwable,
                            "Failed to refresh remote playback tracking for %s",
                            mediaId,
                        )
                    }
                }
            }.getOrNull()
                ?.playbackTracking

        val refreshedPlaybackTrackingUrl = remotePlaybackTracking?.remotePlaybackTrackingUrl()
        if (refreshedPlaybackTrackingUrl != null) {
            remotePlaybackTrackingUrlCache[mediaId] = refreshedPlaybackTrackingUrl
            return registerTracking(refreshedPlaybackTrackingUrl)
        }

        return false
    }

    /**
     * Starts (or refreshes) artwork resolution for the now-current track. Tracks that already
     * carry artwork short-circuit inside the resolver, so this only does network work when the
     * current item has no artwork and the Tidal fallback is enabled. Results are committed
     * centrally here so the player UI, notification and palette extractor share one image.
     */
    private fun beginArtworkResolutionForCurrentTrack() {
        val metadata = currentMediaMetadata.value ?: return
        artworkTrackGeneration = artworkResolver.beginTrack(metadata.id)
        if (!metadata.thumbnailUrl.isNullOrBlank()) return
        val generation = artworkTrackGeneration
        artworkResolveJob?.cancel()
        artworkResolveJob =
            scope.launch(SilentHandler) {
                val request =
                    ArtworkRequest(
                        mediaId = metadata.id,
                        title = metadata.title,
                        artists = metadata.artists.map { it.name },
                        album = metadata.album?.title,
                        // ISRC is not tracked in MediaMetadata; Tidal matching uses normalized
                        // artist/album/title + duration scoring instead.
                        isrc = null,
                        durationMs = metadata.duration.takeIf { it > 0 }?.times(1000L),
                        originalArtworkUrl = metadata.thumbnailUrl,
                        isLocal = metadata.thumbnailUrl.isLocalArtworkUri(),
                    )
                val resolved = artworkResolver.resolve(request)
                if (resolved.provider != ArtworkProvider.TIDAL || resolved.url == null) return@launch
                if (!artworkResolver.isCurrent(metadata.id, generation)) {
                    Timber.tag(TAG).d(
                        "artwork commit rejected: stale generation mediaId=%s generation=%d",
                        metadata.id,
                        generation,
                    )
                    return@launch
                }
                commitResolvedArtwork(metadata.id, resolved)
            }
    }

    /** Commits catalog metadata without changing the playable media URI or source cache key. */
    private fun commitResolvedMetadata(
        mediaId: String,
        resolved: MediaMetadata,
    ) {
        if (currentMediaMetadata.value?.id == mediaId) {
            currentMediaMetadata.value = resolved
        }
        queuedMetadataByMediaId[mediaId] = resolved
        val index =
            (0 until player.mediaItemCount).firstOrNull {
                player.getMediaItemAt(it).mediaId == mediaId
            } ?: return
        val item = player.getMediaItemAt(index)
        val platformMetadata =
            item.mediaMetadata
                .buildUpon()
                .setTitle(resolved.title)
                .setSubtitle(resolved.artists.joinToString { it.name })
                .setArtist(resolved.artists.joinToString { it.name })
                .setAlbumTitle(resolved.album?.title)
                .apply {
                    resolved.thumbnailUrl?.toUri()?.let(::setArtworkUri)
                }.build()
        val updated =
            item
                .buildUpon()
                .setTag(resolved)
                .setMediaMetadata(platformMetadata)
                .build()
        runCatching { player.replaceMediaItem(index, updated) }
            .onFailure {
                Timber.tag(TAG).w(it, "metadata: failed to update MediaItem metadata mediaId=%s", mediaId)
            }
    }

    /** Commits a resolved fallback artwork to the metadata flow and the queued MediaItem. */
    private fun commitResolvedArtwork(
        mediaId: String,
        resolved: ResolvedArtwork,
    ) {
        val url = resolved.url ?: return
        Timber.tag(TAG).d(
            "artwork commit mediaId=%s provider=%s identity=%s confidence=%s",
            mediaId,
            resolved.provider,
            resolved.artworkIdentity,
            resolved.matchConfidence?.let { "%.2f".format(it) } ?: "-",
        )
        // 1. The exposed metadata flow drives player UI, mini player and palette extraction.
        currentMediaMetadata.value
            ?.takeIf { it.id == mediaId }
            ?.let { current -> currentMediaMetadata.value = current.copy(thumbnailUrl = url) }
        // 2. The queued MediaItem drives notification, widgets, Android Auto and Cast metadata.
        val index =
            (0 until player.mediaItemCount).firstOrNull {
                player.getMediaItemAt(it).mediaId == mediaId
            } ?: return
        val item = player.getMediaItemAt(index)
        val updated =
            item
                .buildUpon()
                .setMediaMetadata(
                    item.mediaMetadata
                        .buildUpon()
                        .setArtworkUri(url.toUri())
                        .build(),
                ).build()
        runCatching { player.replaceMediaItem(index, updated) }
            .onFailure {
                Timber.tag(TAG).w(it, "artwork: failed to update MediaItem artwork mediaId=%s", mediaId)
            }
    }

    override fun onTimelineChanged(
        timeline: Timeline,
        reason: Int,
    ) {
        super.onTimelineChanged(timeline, reason)
        cacheQueuedMetadata()
    }

    override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
        super.onDeviceInfoChanged(deviceInfo)
        publishDecodedPcmFormat()
    }

    override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int,
    ) {
        super.onMediaItemTransition(mediaItem, reason)
        crossfadeFailureTracker.reset()
        setAudioAuthenticityContext(localPlayer, mediaItem?.mediaId)
        Timber.tag("PlaybackDiagnostics").d(
            "Media transition reason=%d state=%d playWhenReady=%s positionMs=%d gain=%.3f",
            reason,
            player.playbackState,
            player.playWhenReady,
            player.currentPosition,
            player.volume,
        )
        mediaItem?.metadata?.let { queuedMetadataByMediaId[mediaItem.mediaId] = it }

        // New item: reset the initial-buffer-stall watchdog for the next track.
        initialBufferRecoveryJob?.cancel()
        initialBufferRecoveryJob = null
        initialBufferRecoveryAttemptedMediaId = null
        updateInitialBufferRecovery(player.playbackState)

        // Catch-all for user-initiated skips that bypass PlayerConnection (notification, lock
        // screen, Bluetooth, Android Auto): a SEEK transition while a crossfade fade loop is still
        // running — but NOT the crossfade's own handoff seek (crossfadeHandoffInProgress) — means
        // the user skipped mid-crossfade. Tear the crossfade down so we don't leak the secondary
        // player or leave the primary muted with pauseAtEndOfMediaItems stuck on.
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK &&
            isCrossfading &&
            !crossfadeHandoffInProgress
        ) {
            cancelCrossfade(resetVolume = true, resetPauseAtEnd = true)
        }

        if (sleepTimer.pauseWhenSongEnd) {
            pauseFromSleepTimer()
            return
        }

        beginHistorySession(mediaItem?.mediaId, forceNew = true)

        val currentIndex = player.currentMediaItemIndex
        val queue = player.mediaItems.map { it.metadata }
        if (queue.any { it != null }) {
            lyricsPreloadManager?.onSongChanged(currentIndex, queue)
        }

        val timelineEmpty = player.currentTimeline.isEmpty || player.mediaItemCount == 0 || player.currentMediaItem == null
        currentMediaMetadata.value = if (timelineEmpty) null else (mediaItem?.metadata ?: player.currentMetadata)

        beginArtworkResolutionForCurrentTrack()

        widgetUpdater.update()

        scrobbleManager?.onSongStop()

        // Auto-load more from queue if available
        if (!suppressAutoPlayback &&
            !timelineEmpty &&
            dataStore.get(AutoLoadMoreKey, true) &&
            reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT &&
            player.mediaItemCount - player.currentMediaItemIndex <= 5 &&
            currentQueue.hasNextPage() &&
            player.repeatMode == REPEAT_MODE_OFF
        ) {
            val pageQueue = currentQueue
            val pageGeneration = initialQueueLoadGeneration
            scope.launch(SilentHandler) {
                val mediaItems =
                    pageQueue
                        .nextPage()
                        .filterPlaybackContent(
                            hideExplicit = dataStore.get(HideExplicitKey, false),
                            hideVideo = dataStore.get(HideVideoKey, false),
                        )
                if (currentQueue !== pageQueue || initialQueueLoadGeneration != pageGeneration) return@launch
                if (player.playbackState != STATE_IDLE) {
                    player.addMediaItems(appendableQueuePage(mediaItems, pageQueue.nextPageRepeatsCurrentItem))
                } else {
                    requestDiscordSync(
                        reason = "player_idle_after_queue_extension",
                        force = true,
                    )
                }
            }
        }

        if (!suppressAutoPlayback &&
            !initialQueueLoadInProgress &&
            !timelineEmpty &&
            dataStore.get(AutoLoadMoreKey, true) &&
            reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT &&
            player.repeatMode == REPEAT_MODE_OFF &&
            player.mediaItemCount - player.currentMediaItemIndex <= 3 &&
            currentQueue.shouldBootstrapInfiniteQueue()
        ) {
            onInfiniteQueueEnabled(currentQueue.infiniteQueueSeedMediaId())
        }

        if (player.playWhenReady && player.playbackState == Player.STATE_READY) {
            scrobbleManager?.onSongStart(player.currentMetadata, duration = player.duration)
        }

        scope.launch {
            val shouldSave = withContext(Dispatchers.IO) { dataStore.get(PersistentQueueKey, true) }
            if (shouldSave) {
                saveQueueToDisk()
            }
        }
        ensurePresenceManager()
        if (!isCrossfading) {
            scheduleCrossfade()
        }
        // Prefetch the stream URL for the next-up song so the user-visible
        // "skip to next" feels instant. The prefetch runs on a background IO
        // coroutine and is cancelled/replaced whenever a new transition fires.
        // Skipped in low-data mode to honor the user's data-saver preference.
        prefetchNextMediaItemStream()
    }

    private fun isCurrentPlaybackItemLocal(currentMediaMetadata: MediaMetadata): Boolean =
        currentSong.value?.song?.isLocal == true ||
            currentMediaMetadata.id.trim().isLocalMediaId() ||
            player.currentMediaItem
                ?.localConfiguration
                ?.uri
                ?.shouldBypassPlayerCache() == true

    /**
     * In-memory cache of resolved [DirectStream]s (Qobuz / Tidal) keyed by media id. Populated by
     * [prefetchNextMediaItemStream] so the next song's lossless stream URL is already known when
     * the user skips to it — turning a ~1-3 second Qobuz/Tidal resolution into a cache hit.
     */
    private val directStreamCache = ConcurrentHashMap<String, CachedDirectStream>()
    private val selectedPlaybackSourceByMediaId = ConcurrentHashMap<String, AudioSourceType>()
    private val authenticitySampleRateByMediaId = ConcurrentHashMap<String, Int>()
    private val authenticityClaimedLosslessByMediaId = ConcurrentHashMap<String, Boolean>()
    private val authenticityRejectedSourcesByMediaId = ConcurrentHashMap<String, MutableSet<AudioSourceType>>()
    private val primaryAudioAuthenticityContext = AtomicReference<AudioAuthenticityPlaybackContext?>(null)
    private val audioAuthenticityContextByPlayer =
        ConcurrentHashMap<ExoPlayer, AtomicReference<AudioAuthenticityPlaybackContext?>>()
    private val decodedPcmFormatsByContext =
        ConcurrentHashMap<AtomicReference<AudioAuthenticityPlaybackContext?>, DecodedPcmFormat>()
    private val reportedAudioFormatsByMediaId = android.util.LruCache<String, ReportedAudioFormat>(64)
    private val audioTrackConfigurationsByPlayer =
        ConcurrentHashMap<ExoPlayer, androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig>()
    private var nextMediaItemPrefetchJob: kotlinx.coroutines.Job? = null

    /**
     * Media id [nextMediaItemPrefetchJob] is resolving, so a still-wanted job is not cancelled.
     * Only meaningful while that job is active — every read is guarded on `isActive`, so a stale
     * id left behind by a finished job is inert and needs no clearing.
     */
    @Volatile
    private var prefetchingMediaId: String? = null

    private data class CachedDirectStream(
        val stream: DirectStream,
        val expiresAtMs: Long,
    )

    /**
     * Prefetches the stream URL for the next-up media item in the queue so that when the user skips
     * to it (or auto-advance fires), the URL is already in [playbackUrlCache] (YouTube) or
     * [directStreamCache] (Qobuz / Tidal) and playback starts within ~100ms instead of the usual
     * 1-3 second resolution delay.
     */
    private fun prefetchNextMediaItemStream() {
        if (player.mediaItemCount == 0 || player.currentTimeline.isEmpty) return
        val nextIndex = player.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET || nextIndex < 0 || nextIndex >= player.mediaItemCount) return
        val nextItem = player.getMediaItemAt(nextIndex)
        val mediaId = nextItem.mediaId.trim().takeIf { it.isNotBlank() } ?: return

        // Don't cancel a prefetch whose result is still wanted. Skipping twice inside the
        // resolve window would kill the in-flight job for the very track being skipped onto — the
        // one case where the work was about to pay off — and that track would then resolve from
        // scratch, synchronously, while the user waited.
        // A job for the now-current item is finishing into the same caches the resolver reads.
        val inFlight = prefetchingMediaId
        if (nextMediaItemPrefetchJob?.isActive == true && inFlight != null) {
            val currentId = player.currentMediaItem?.mediaId?.trim()
            if (inFlight == mediaId || inFlight == currentId) return
        }
        nextMediaItemPrefetchJob?.cancel()
        // Skip prefetch for local/telegram files (no network resolution needed).
        if (mediaId.isLocalMediaId() || mediaId.isTelegramMediaId()) return
        // Skip if we already have a fresh cached entry.
        if (playbackUrlCache[mediaId] != null) return
        if (directStreamCache[mediaId]?.let { it.expiresAtMs > System.currentTimeMillis() } == true) return
        // Skip in low-data mode (user wants minimal background data).
        if (isLowDataModeActive()) return

        prefetchingMediaId = mediaId
        nextMediaItemPrefetchJob =
            scope.launch(Dispatchers.IO + SilentHandler) {
                runCatching {
                    Timber.tag(TAG).d("Prefetching stream URL for next media item: %s", mediaId)
                    // First, try multi-source (Qobuz / Tidal) resolution.
                    // If a lossless source is configured and matches, cache it.
                    val lowData = isLowDataModeActive()
                    if (!lowData) {
                        val dataSpec = DataSpec.Builder()
                            .setUri("placeholder:$mediaId".toUri())
                            .setKey(mediaId)
                            .build()
                        val resolved = resolveMultiSourceDataSpec(dataSpec, mediaId, lowData, isPrefetch = true)
                        if (resolved != null) {
                            // resolveMultiSourceDataSpec already cached the
                            // stream URL via applyDirectStream; we just need
                            // to record it in directStreamCache so the next
                            // call can find it without re-resolving.
                            // The cache is implicit in tidalActiveMediaIds +
                            // sourceCacheKey() — we don't need to duplicate it.
                            Timber.tag(TAG).d("Prefetch: lossless stream resolved for %s", mediaId)
                            return@runCatching
                        }
                    }
                    // Fall back to YouTube stream URL prefetch — unless the user has turned the
                    // YouTube source off, in which case there is nothing to prefetch: the
                    // playback path will not use a YouTube URL for this track.
                    if (!isSourceEnabled(AudioSourceType.YOUTUBE)) return@runCatching

                    val result =
                        retryWithoutPlaybackLoginContext {
                            YTPlayerUtils.playerResponseForPlayback(
                                mediaId,
                                audioQuality = if (lowData) AudioQuality.LOW else audioQuality,
                                connectivityManager = connectivityManager,
                                preferredStreamClient = preferredStreamClient,
                                networkMetered = lowData,
                            )
                        }
                    result.onSuccess { playbackData ->
                        val expiresAtMs = System.currentTimeMillis() +
                            (playbackData.streamExpiresInSeconds.coerceAtLeast(1) * 1000L)
                        playbackUrlCache[mediaId] = AuthScopedCacheValue(
                            url = playbackData.streamUrl,
                            expiresAtMs = expiresAtMs,
                            authFingerprint = playbackData.authFingerprint,
                        )
                        Timber.tag(TAG).d(
                            "Prefetch: YouTube stream URL cached for %s (expires in %ds)",
                            mediaId,
                            playbackData.streamExpiresInSeconds,
                        )
                    }
                }.onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    Timber.tag(TAG).d(error, "Prefetch failed for %s (will resolve on play)", mediaId)
                }
            }
    }

    private fun Queue.shouldBootstrapInfiniteQueue(): Boolean =
        preloadItem != null || !hasNextPage()

    private fun Queue.infiniteQueueSeedMediaId(): String? =
        preloadItem?.id?.trim()?.takeIf { it.isNotBlank() }

    override fun onPlaybackStateChanged(
        @Player.State playbackState: Int,
    ) {
        super.onPlaybackStateChanged(playbackState)
        if (playbackState == Player.STATE_READY) {
            setAudioAuthenticityContext(localPlayer, player.currentMediaItem?.mediaId)
        }
        Timber.tag("PlaybackDiagnostics").d(
            "Playback state=%d buffering=%s playWhenReady=%s isPlaying=%s positionMs=%d bufferedPositionMs=%d durationMs=%d gain=%.3f expectedGain=%.3f focusHeld=%s",
            playbackState,
            playbackState == Player.STATE_BUFFERING,
            player.playWhenReady,
            player.isPlaying,
            player.currentPosition,
            player.bufferedPosition,
            player.duration,
            player.volume,
            currentEffectivePlayerVolume(),
            hasAudioFocus,
        )

        updateHistoryTrackingPlaybackState()
        updateInitialBufferRecovery(playbackState)
        if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
            enqueueCurrentHistorySessionForFinalization()
            val playbackWasRequested = player.playWhenReady || crossfadePlaybackRequested
            if (!isCrossfading || playbackState == Player.STATE_IDLE) {
                cancelCrossfade(resetVolume = true, resetPauseAtEnd = true)
            } else if (playbackState == Player.STATE_ENDED) {
                // The crossfade was supposed to hand off to the next item before STATE_ENDED
                // fired, but it didn't complete in time. A stale `isCrossfading = true` here
                // means `pauseAtEndOfMediaItems` is still `true`, which prevents ExoPlayer's
                // auto-advance — the player stops at the end of the current song and the user
                // has to press Play to continue. Tear the crossfade down so the player can
                // auto-advance normally. The crossfade's secondary player (if any) was either
                // already promoting itself or never became ready — either way, releasing it
                // here is safe and prevents a stuck `pauseAtEndOfMediaItems`.
                cancelCrossfade(resetVolume = true, resetPauseAtEnd = true)
            }
            if (playbackState == Player.STATE_ENDED &&
                CrossfadePolicy.shouldResumeAfterEnded(
                    playbackRequested = playbackWasRequested,
                    hasNextItem = player.hasNextMediaItem(),
                    repeatOne = player.repeatMode == REPEAT_MODE_ONE,
                    suppressAutoPlayback = suppressAutoPlayback,
                )
            ) {
                player.seekToNextMediaItem()
                player.play()
            }
            if (playbackState == Player.STATE_ENDED &&
                !suppressAutoPlayback &&
                dataStore.get(AutoLoadMoreKey, true) &&
                player.repeatMode == REPEAT_MODE_OFF &&
                player.currentMediaItem != null &&
                currentQueue.shouldBootstrapInfiniteQueue()
            ) {
                onInfiniteQueueEnabled(currentQueue.infiniteQueueSeedMediaId())
            }
        } else if (playbackState == Player.STATE_READY) {
            // Source-switch completion hook: once the deterministic re-create reaches READY,
            // the new MediaSource is fully prepared and the live flows are authoritative again.
            // Apply the volume captured before the switch, cancel the delayed reassert (it
            // already did its job / is redundant now), and re-arm the watchdog — the watchdog
            // self-cancels while the player is IDLE mid-switch, so READY is where it must be
            // restarted.
            if (sourceSwitchPending) {
                sourceSwitchPending = false
                sourceSwitchReassertJob?.cancel()
                sourceSwitchReassertJob = null
                Timber.tag(TAG).d(
                    "source switch READY: captured=%s live=%s actual=%s state=%s",
                    sourceSwitchExpectedVolume,
                    currentEffectivePlayerVolume(),
                    player.volume,
                    player.playWhenReady,
                )
                applyEffectiveVolumeImmediately(sourceSwitchExpectedVolume)
                ensureAudiblePlaybackVolume("source_switch_ready")
            }
            // A seek's re-buffer has just completed. The reactive volume pipeline re-fired during
            // BUFFERING->READY and may have pinned the primary player low; restore it now instead
            // of waiting up to 15s for the audible-volume watchdog. Guarded + idempotent.
            if (pendingSeekVolumeReassert) {
                pendingSeekVolumeReassert = false
                seekVolumeReassertJob?.cancel()
                seekVolumeReassertJob = null
                ensureAudiblePlaybackVolume("seek_ready")
            }
            updateAudiblePlaybackRecovery()
            scheduleCrossfade()
        }

        widgetUpdater.update()
        widgetUpdater.updateProgressTracking()

        scope.launch {
            val shouldSave = withContext(Dispatchers.IO) { dataStore.get(PersistentQueueKey, true) }
            if (shouldSave) {
                saveQueueToDisk()
            }
        }
    }

    override fun onPlayWhenReadyChanged(
        playWhenReady: Boolean,
        reason: Int,
    ) {
        super.onPlayWhenReadyChanged(playWhenReady, reason)
        val isEndOfOutgoingItemPause =
            !playWhenReady &&
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM &&
                localPlayer.pauseAtEndOfMediaItems
        if (isCrossfading || crossfadeHandoffInProgress) {
            // Track the live playback intent across the whole crossfade, including the promotion
            // window: there secondaryCrossfadePlayer is already null while the session player is
            // being swapped, so tracking inside that null-check silently dropped a pause landing
            // in the window and the freshly promoted player resumed on its own. The outgoing
            // player's end-of-item auto-pause is not user intent and keeps the intent unchanged.
            if (!isEndOfOutgoingItemPause) {
                crossfadePlaybackRequested = playWhenReady
            }
        }
        secondaryCrossfadePlayer?.let { secondaryPlayer ->
            if (isCrossfading) {
                if (!isEndOfOutgoingItemPause) {
                    secondaryPlayer.playWhenReady = crossfadePlaybackRequested
                    if (crossfadePlaybackRequested) {
                        secondaryPlayer.play()
                    } else {
                        secondaryPlayer.pause()
                    }
                } else if (!crossfadeHandoffInProgress) {
                    secondaryPlayer.playWhenReady = crossfadePlaybackRequested
                    if (crossfadePlaybackRequested) {
                        secondaryPlayer.play()
                    }
                }
            }
        }
        if (playWhenReady && !isCrossfading) {
            scheduleCrossfade()
        } else if (!playWhenReady && !isCrossfading) {
            crossfadeTriggerJob?.cancel()
            crossfadeTriggerJob = null
            localPlayer.pauseAtEndOfMediaItems = false
            releaseSecondaryCrossfadePlayer()
        }
    }

    override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
        super.onPlaybackParametersChanged(playbackParameters)
        // A speed change mid-blend is the user's newest choice, so it is what promotion restores
        // (4nx3b restored the value captured at the start, undoing it). During a smart blend the
        // incoming player keeps the plan's beat-match stretch until then; copying the raw session
        // parameters over it would knock the mix off its grid.
        if (isCrossfading) {
            crossfadeUserPlaybackParameters = playbackParameters
            if (crossfadeSmartActive) return
        }
        secondaryCrossfadePlayer?.playbackParameters = playbackParameters
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        super.onIsPlayingChanged(isPlaying)
        updateInitialBufferRecovery(player.playbackState)
        secondaryCrossfadePlayer?.let { secondaryPlayer ->
            if (isCrossfading && !crossfadeHandoffInProgress) {
                if (isPlaying) {
                    secondaryPlayer.play()
                } else {
                    secondaryPlayer.pause()
                }
            }
        }
        if (isPlaying && !isCrossfading) {
            scheduleCrossfade()
        }
        updateAudiblePlaybackRecovery()

        widgetUpdater.update()
        widgetUpdater.updateProgressTracking()
    }

    override fun onEvents(
        player: Player,
        events: Player.Events,
    ) {
        val currentMediaId = player.currentMediaItem?.mediaId
        if (currentMediaId == null && currentHistoryMediaId != null) {
            beginHistorySession(null, forceNew = true)
        } else if (currentHistoryMediaId == null && currentMediaId != null) {
            beginHistorySession(currentMediaId)
        }
        if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
            playbackStreamRecoveryTracker.onMediaItemChanged(currentMediaId)
            // Reset codec-recovery budget for the new media item.
            if (currentMediaId != codecRecoveryMediaId) {
                codecRecoveryMediaId = currentMediaId
                codecRecoveryAttemptCount = 0
            }
        }
        if (
            (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) && player.playbackState == Player.STATE_READY) ||
            (events.contains(Player.EVENT_IS_PLAYING_CHANGED) && player.isPlaying)
        ) {
            playbackStreamRecoveryTracker.onPlaybackRecovered(currentMediaId)
            // Codec successfully reached READY / isPlaying after a recovery re-prepare —
            // the current codec instance is healthy, so reset the per-media counter so the
            // NEXT background+pause cycle gets a fresh budget.
            if (codecRecoveryAttemptCount > 0) {
                Timber.tag("MusicService").i(
                    "Codec recovery succeeded for %s after %d attempt(s); resetting codec-recovery budget",
                    currentMediaId,
                    codecRecoveryAttemptCount,
                )
                codecRecoveryAttemptCount = 0
            }
            currentMediaId
                ?.let(playbackUrlCache::get)
                ?.url
                ?.let(YTPlayerUtils::markStreamUrlSuccessful)
            ensureAudiblePlaybackVolume("player_event")
        }
        if (events.containsAny(
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_PLAY_WHEN_READY_CHANGED,
                Player.EVENT_IS_PLAYING_CHANGED,
            )
        ) {
            updateAudiblePlaybackRecovery()
        }
        if (events.contains(Player.EVENT_MEDIA_METADATA_CHANGED)) {
            currentMediaMetadata.value = player.currentMetadata
        }
        if (events.containsAny(
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_PLAY_WHEN_READY_CHANGED,
                Player.EVENT_IS_PLAYING_CHANGED,
            )
        ) {
            updateHistoryTrackingPlaybackState()
        }
        if (events.contains(Player.EVENT_DEVICE_VOLUME_CHANGED)) {
            handleDeviceMuteStateChanged()
        }
        if (events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) && isDeviceMutedNow() && this.player.playWhenReady) {
            handleDeviceMuteStateChanged(playbackRequestedWhileMuted = true)
        }
        if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) &&
            (this.player.playbackState == Player.STATE_IDLE || this.player.playbackState == Player.STATE_ENDED)
        ) {
            wasAutoPausedByDeviceMute = false
            unregisterMuteRecoveryObserver()
            updateAudiblePlaybackRecovery()
        }
        if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) &&
            isDeviceMutedNow() &&
            this.player.playWhenReady
        ) {
            handleDeviceMuteStateChanged(playbackRequestedWhileMuted = true)
        }
        if (events.containsAny(
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_PLAY_WHEN_READY_CHANGED,
            )
        ) {
            if (player.playWhenReady && shouldKeepAudioEffectSessionOpen()) {
                ensureAudioFocusForActivePlayback()
            }
            updateWakeLock()
            if (hasResumablePlaybackNotification()) {
                cancelIdleStop()
                promoteToStartedService()
                ensureStartedAsForeground()
            } else {
                scheduleStopIfIdle()
            }
        }

        if (events.containsAny(EVENT_TIMELINE_CHANGED, EVENT_POSITION_DISCONTINUITY)) {
            currentMediaMetadata.value = player.currentMetadata
            requestDiscordSync(
                reason = "timeline_or_position_discontinuity",
                force = true,
            )
            // Snapshot player state on the Main thread up front. Media3's player must only be
            // accessed on its application (main) thread; reading player.* inside the IO coroutine
            // below throws "Player is accessed on the wrong thread" and silently breaks scrobbling.
            val timelineMediaId = player.currentMediaItem?.mediaId
            val timelineMetadata = player.currentMetadata
            val timelineDuration = player.duration
            val timelinePosition = player.currentPosition
            scope.launch {
                try {
                    val song =
                        if (timelineMediaId != null) {
                            withContext(Dispatchers.IO) { database.song(timelineMediaId).first() }
                        } else {
                            null
                        }
                    val finalSong =
                        resolvePresenceSong(
                            dbSong = song,
                            mediaMetadata = timelineMetadata,
                            durationMs = timelineDuration,
                        ) ?: return@launch
                    try {
                        val lbEnabled = dataStore.get(ListenBrainzEnabledKey, false)
                        val lbToken = dataStore.get(ListenBrainzTokenKey, "")
                        if (lbEnabled && !lbToken.isNullOrBlank()) {
                            scope.launch(Dispatchers.IO) {
                                try {
                                    ListenBrainzManager.submitPlayingNow(
                                        this@MusicService,
                                        lbToken,
                                        finalSong,
                                        timelinePosition,
                                    )
                                } catch (ie: Exception) {
                                    Timber.tag("MusicService").v(ie, "ListenBrainz playing_now submit failed on transition")
                                }
                            }
                        }

                        // Last.fm now playing - handled by ScrobbleManager
                    } catch (_: Exception) {
                    }
                } catch (e: Exception) {
                    Timber.tag("MusicService").v(e, "timeline/position follow-up work failed")
                }
            }
        }
        if (events.contains(EVENT_TIMELINE_CHANGED) && !isCrossfading) {
            scheduleCrossfade()
        }

        // Also handle immediate update for play state and media item transition events explicitly
        if (events.containsAny(
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_PLAY_WHEN_READY_CHANGED,
                Player.EVENT_IS_PLAYING_CHANGED,
                Player.EVENT_MEDIA_ITEM_TRANSITION,
            )
        ) {
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                currentMediaMetadata.value = player.currentMetadata
            }
            requestDiscordSync(
                reason = "is_playing_or_media_item_transition",
                force = true,
            )
            // Capture player state on Main thread
            val currentMediaId = player.currentMediaItem?.mediaId
            val currentMetadata = player.currentMetadata
            val currentPosition = player.currentPosition
            val currentDuration = player.duration
            val isPlaying = player.isPlaying

            scope.launch {
                try {
                    val song =
                        if (currentMediaId !=
                            null
                        ) {
                            withContext(Dispatchers.IO) { database.song(currentMediaId).first() }
                        } else {
                            null
                        }
                    val finalSong =
                        resolvePresenceSong(
                            dbSong = song,
                            mediaMetadata = currentMetadata,
                            durationMs = currentDuration,
                        ) ?: return@launch
                    try {
                        val lbEnabled = withContext(Dispatchers.IO) { dataStore.get(ListenBrainzEnabledKey, false) }
                        val lbToken = withContext(Dispatchers.IO) { dataStore.get(ListenBrainzTokenKey, "") }
                        if (lbEnabled && !lbToken.isNullOrBlank()) {
                            scope.launch(Dispatchers.IO) {
                                try {
                                    ListenBrainzManager.submitPlayingNow(this@MusicService, lbToken, finalSong, currentPosition)
                                } catch (ie: Exception) {
                                    Timber
                                        .tag(
                                            "MusicService",
                                        ).v(ie, "ListenBrainz playing_now submit failed for isPlaying/mediaTransition")
                                }
                            }
                        }

                        // Last.fm now playing - handled by ScrobbleManager
                    } catch (_: Exception) {
                    }
                } catch (e: Exception) {
                    Timber.tag("MusicService").v(e, "isPlaying/mediaTransition follow-up work failed")
                }
            }
        }

        if (events.containsAny(Player.EVENT_IS_PLAYING_CHANGED)) {
            // Scrobble: Track play/pause state
            scrobbleManager?.onPlayerStateChanged(player.isPlaying, player.currentMetadata, duration = player.duration)
        }

        // Persist queue on play/pause so a force-stop right after pausing still restores the correct position
        if (events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) && player.mediaItemCount > 0) {
            scope.launch(SilentHandler) {
                if (withContext(Dispatchers.IO) { dataStore.get(PersistentQueueKey, true) }) {
                    saveQueueToDisk()
                }
            }
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        super.onPositionDiscontinuity(oldPosition, newPosition, reason)
        val isSeekDiscontinuity =
            reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
        if (isSeekDiscontinuity) {
            Timber.tag("PlaybackDiagnostics").d(
                "Seek reason=%d fromMs=%d toMs=%d state=%d playWhenReady=%s isPlaying=%s gain=%.3f expectedGain=%.3f focusHeld=%s crossfadeProgress=%.3f",
                reason,
                oldPosition.positionMs,
                newPosition.positionMs,
                player.playbackState,
                player.playWhenReady,
                player.isPlaying,
                player.volume,
                currentEffectivePlayerVolume(),
                hasAudioFocus,
                crossfadeProgress,
            )
            if (!crossfadeHandoffInProgress) {
                cancelCrossfade(resetVolume = true, resetPauseAtEnd = true)
            }
            // A seek forces a re-buffer; the BUFFERING->READY transition re-fires the reactive
            // volume pipeline (playerVolume x normalize x focus), which can pin the primary
            // player's volume low AFTER the reset above already ran — the same re-fire the
            // source-switch path guards against, but seeks had none, so the stream stayed silent
            // until the 15s audible-volume watchdog. Reassert at the seek's READY (below) and,
            // for an in-buffer seek that never leaves READY, once shortly after.
            pendingSeekVolumeReassert = true
            scheduleSeekVolumeReassert()
        }
        if (!isCrossfading && !crossfadeHandoffInProgress) {
            scheduleCrossfade()
        }
    }

    /**
     * Fast-path recovery for a seek that stays within the buffered region: no BUFFERING->READY
     * fires, so the STATE_READY seek hook never runs. [ensureAudiblePlaybackVolume] only restores
     * a primary player that is muted but should be audible, and no-ops during a real crossfade, so
     * this cannot introduce a spurious volume change.
     */
    private fun scheduleSeekVolumeReassert() {
        seekVolumeReassertJob?.cancel()
        seekVolumeReassertJob =
            scope.launch {
                delay(SEEK_VOLUME_REASSERT_MS)
                ensureAudiblePlaybackVolume("seek_reassert")
            }
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        updateNotification()
        if (shuffleModeEnabled) {
            applyCurrentFirstShuffleOrder()
        }

        // Save state when shuffle mode changes - must be on Main thread to access player
        scope.launch {
            if (dataStore.get(PersistentQueueKey, true)) {
                saveQueueToDisk()
            }
        }
        if (!isCrossfading) {
            scheduleCrossfade()
        }
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        updateNotification()
        scope.launch {
            dataStore.edit { settings ->
                settings[RepeatModeKey] = repeatMode
            }
        }

        // Save state when repeat mode changes - must be on Main thread to access player
        scope.launch {
            if (dataStore.get(PersistentQueueKey, true)) {
                saveQueueToDisk()
            }
        }
        if (!isCrossfading) {
            scheduleCrossfade()
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)
        Timber.tag("PlaybackDiagnostics").w(
            "Playback error code=%d state=%d playWhenReady=%s isPlaying=%s positionMs=%d bufferedPositionMs=%d gain=%.3f expectedGain=%.3f focusHeld=%s",
            error.errorCode,
            player.playbackState,
            player.playWhenReady,
            player.isPlaying,
            player.currentPosition,
            player.bufferedPosition,
            player.volume,
            currentEffectivePlayerVolume(),
            hasAudioFocus,
        )

        val currentMediaId = player.currentMediaItem?.mediaId ?: return
        val isLocalMedia = currentMediaId.isLocalMediaId()

        val isFullyDownloadedMedia =
            runCatching {
                val contentLength =
                    downloadCache
                        .getContentMetadata(currentMediaId)
                        .get(ContentMetadata.KEY_CONTENT_LENGTH, -1L)
                contentLength > 0L && downloadCache.isCached(currentMediaId, 0L, contentLength)
            }.getOrDefault(false)

        // Check for cached spans across all source-prefixed keys. Even if the
        // cache metadata is missing the content length (which would make
        // `isFullyDownloadedMedia` false), having cached spans means the song
        // was at least partially downloaded and we should try to play it from
        // cache instead of waiting for network: the metadata can lack the
        // persisted content length while the bytes are still there.
        val hasAnyCachedData =
            isFullyDownloadedMedia ||
                runCatching {
                    // Every key a download can use (all source prefixes, then the bare id): a copy
                    // under one this sweep skipped was treated as not cached at all.
                    DownloadSourceConfig.cacheKeysFor(currentMediaId).any { key ->
                        downloadCache.getCachedSpans(key).isNotEmpty() ||
                            playerCache.getCachedSpans(key).isNotEmpty()
                    }
                }.getOrDefault(false)

        val isConnectionError =
            (error.cause?.cause is PlaybackException) &&
                (error.cause?.cause as PlaybackException).errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED

        // Offline bypass: if we have ANY cached data for this mediaId (a
        // complete download, or even partial cached spans under the bare or
        // source-prefixed keys), do NOT call `waitOnNetworkError()` — the
        // cache resolver in `resolveCachedDataSpec` will serve the cached
        // bytes on the next prepare(). Calling `waitOnNetworkError()` here
        // would freeze playback indefinitely even though the song is fully
        // available locally.
        if (!isLocalMedia && !isFullyDownloadedMedia && !hasAnyCachedData &&
            (!isNetworkConnected.value || isConnectionError)
        ) {
            waitOnNetworkError()
            return
        }

        // If we have cached data but the player still errored (typically
        // because the resolver tried the network first and failed), force a
        // re-prepare so the cache resolver gets a chance to serve the bytes.
        // This handles the case where the initial resolvePlaybackDataSpec
        // call fell through to the YouTube resolver before the cache was
        // consulted, and the YouTube resolver failed because we're offline.
        if (!isLocalMedia && hasAnyCachedData && (!isNetworkConnected.value || isConnectionError)) {
            Timber.tag("MusicService").i(
                "Offline playback recovery for %s (fullyCached=%b, hasSpans=%b); re-preparing to force cache read",
                currentMediaId,
                isFullyDownloadedMedia,
                hasAnyCachedData,
            )
            // Invalidate any stale stream URL cache so the resolver is forced
            // to consult the cache first on the next prepare.
            playbackUrlCache.remove(currentMediaId)
            if (playbackStreamRecoveryTracker.registerRetryAttempt(currentMediaId)) {
                player.prepare()
                return
            }
        }

        if (error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND) {
            scope.launch(Dispatchers.IO) {
                runCatching { downloadCache.removeResource(currentMediaId) }
                runCatching { playerCache.removeResource(currentMediaId) }
            }
        }

        val streamHttpFailure = findStreamHttpFailure(error)
        if (streamHttpFailure != null) {
            if (streamHttpFailure.responseCode in RETRYABLE_STREAM_RESPONSE_CODES &&
                retryPlaybackAfterStreamFailure(currentMediaId, isFullyDownloadedMedia, streamHttpFailure)
            ) {
                return
            }
        }

        if (!isLocalMedia && isCacheCorruptionError(error, hasAnyCachedData)) {
            // Snapshot on the Main thread before dispatching; these can change.
            val mediaItemIndex = player.currentMediaItemIndex
            val resumePosition = player.currentPosition.coerceAtLeast(0L)

            Timber.tag("MusicService").w(
                "Cache corruption / truncated stream for %s (fullyCached=%b); purging caches then retrying",
                currentMediaId,
                isFullyDownloadedMedia,
            )

            playbackUrlCache.remove(currentMediaId)
            contentLengthCache.remove(currentMediaId)
            YTPlayerUtils.invalidateCachedStreamUrls(currentMediaId)

            scope.launch(Dispatchers.IO) {
                // Every key a download can use, not just the bare id: the bytes that broke the
                // extractor are normally the source-prefixed ones ("qobuz:<id>", "tidal:<id>"),
                // and purging only the bare key left them in place. The next prepare re-selected the
                // same corrupt span, the track spent its whole retry budget and auto-skipped — which
                // is what read as "songs keep skipping near the end".
                val purgeKeys = DownloadSourceConfig.cacheKeysFor(currentMediaId)
                purgeKeys.forEach { key -> runCatching { playerCache.removeResource(key) } }
                // Keep a complete offline download in place; deleting a user's saved download to
                // recover from a read error is surprising. Completeness is checked per key, because
                // the complete download is often the source-prefixed one rather than the bare id.
                var keptCompleteDownload = false
                purgeKeys.forEach { key ->
                    val complete =
                        runCatching {
                            val length =
                                downloadCache
                                    .getContentMetadata(key)
                                    .get(ContentMetadata.KEY_CONTENT_LENGTH, -1L)
                            length > 0L && downloadCache.isCached(key, 0L, length)
                        }.getOrDefault(false)
                    if (complete) {
                        keptCompleteDownload = true
                    } else {
                        runCatching { downloadCache.removeResource(key) }
                    }
                }
                if (keptCompleteDownload) {
                    Timber.tag("MusicService").w(
                        "Keeping offline download for %s; corruption may require manual re-download",
                        currentMediaId,
                    )
                }

                // Re-prepare ONLY after the purge completes, back on the Main thread, so the
                // fresh prepare cannot re-read the spans we just deleted.
                withContext(Dispatchers.Main) {
                    if (playbackStreamRecoveryTracker.registerRetryAttempt(currentMediaId)) {
                        player.seekTo(mediaItemIndex, resumePosition)
                        player.prepare()
                    } else {
                        // Retry budget for this item is spent; fall back to configured behavior.
                        if (dataStore.get(AutoSkipNextOnErrorKey, false)) skipOnError() else stopOnError()
                    }
                }
            }
            return
        }

        if (!isLocalMedia && !isFullyDownloadedMedia && YTPlayerUtils.isBotDetectionException(error)) {
            playbackUrlCache.remove(currentMediaId)
            YTPlayerUtils.invalidateCachedStreamUrls(currentMediaId)
            YTPlayerUtils.clearPlaybackAuthCaches()
            if (playbackStreamRecoveryTracker.registerRetryAttempt(currentMediaId)) {
                Timber.tag("MusicService").i("Retrying playback for %s after bot-detection source error", currentMediaId)
                player.prepare()
                return
            }
        }

        if (!isLocalMedia && !isFullyDownloadedMedia && YTPlayerUtils.isBadStreamPlayerResponseException(error)) {
            playbackUrlCache.remove(currentMediaId)
            YTPlayerUtils.invalidateCachedStreamUrls(currentMediaId)
            if (playbackStreamRecoveryTracker.registerRetryAttempt(currentMediaId)) {
                scope.launch(Dispatchers.IO) {
                    runCatching {
                        YTPlayerUtils.recoverFromBadStreamPlayerResponse(currentMediaId)
                    }.onFailure {
                        Timber.tag("MusicService").w(
                            it,
                            "Failed to refresh stream session for %s after all stream clients failed",
                            currentMediaId,
                        )
                        reportException(it)
                    }
                    withContext(Dispatchers.Main) {
                        if (player.currentMediaItem?.mediaId == currentMediaId) {
                            Timber.tag("MusicService").i(
                                "Retrying playback for %s after refreshing stream session",
                                currentMediaId,
                            )
                            player.prepare()
                        }
                    }
                }
                return
            }
        }

        if (!isLocalMedia && !isFullyDownloadedMedia && isRetryableRemoteParserFailure(error)) {
            val failedUrl =
                playbackUrlCache[currentMediaId]?.url
            playbackUrlCache.remove(currentMediaId)
            contentLengthCache.remove(currentMediaId)
            YTPlayerUtils.invalidateCachedStreamUrls(currentMediaId)
            failedUrl
                ?.let(StreamClientUtils::resolveRequestProfile)
                ?.clientKey
                ?.takeIf(String::isNotEmpty)
                ?.let { clientKey ->
                    YTPlayerUtils.markStreamClientFailed(
                        videoId = currentMediaId,
                        clientKey = clientKey,
                        httpStatusCode = null,
                    )
                }
            if (playbackStreamRecoveryTracker.registerRetryAttempt(currentMediaId)) {
                Timber.tag("MusicService").i(
                    "Retrying playback for %s after parser source error %d",
                    currentMediaId,
                    error.errorCode,
                )
                player.prepare()
                return
            }
        }

        // MediaCodec decoder-state recovery.
        if (isMediaCodecStateError(error)) {
            val resumePosition = player.currentPosition.coerceAtLeast(0L)
            val mediaItemIndex = player.currentMediaItemIndex

            // Reset budget if we've moved to a different media item since the last attempt.
            if (currentMediaId != codecRecoveryMediaId) {
                codecRecoveryMediaId = currentMediaId
                codecRecoveryAttemptCount = 0
            }
            val attemptNumber = codecRecoveryAttemptCount + 1
            val withinBudget = attemptNumber <= codecRecoveryMaxAttempts

            Timber.tag("MusicService").w(
                "MediaCodec state error for %s (errorCode=%s, causeChain=%s); recovery attempt %d/%d",
                currentMediaId,
                error.errorCodeName,
                describeCauseChain(error),
                attemptNumber,
                codecRecoveryMaxAttempts,
            )

            if (withinBudget) {
                codecRecoveryAttemptCount = attemptNumber
                scope.launch(Dispatchers.Main) {
                    try {
                        // Give the OS a moment to finish releasing the dead codec instance
                        // before we ask ExoPlayer to instantiate a fresh one. Without this,
                        // the new codec can race the old one's teardown and fail with the
                        // same 0x80000000 / Released-state signature on the very first
                        // queueInputBuffer() call.
                        if (attemptNumber > 1) {
                            kotlinx.coroutines.delay(400L)
                        }
                        player.seekTo(mediaItemIndex, resumePosition)
                        player.prepare()
                        // Honor the user's prior play/pause intent — if they had paused,
                        // keep it paused after recovery so we don't surprise-start playback.
                        if (!player.playWhenReady) {
                            player.pause()
                        }
                    } catch (recoveryThrowable: Throwable) {
                        Timber.tag("MusicService").e(
                            recoveryThrowable,
                            "Recovery re-prepare failed for %s (attempt %d); falling back to stop-on-error",
                            currentMediaId,
                            attemptNumber,
                        )
                        stopOnError()
                    }
                }
                return
            } else {
                Timber.tag("MusicService").w(
                    "Codec-recovery budget exhausted for %s after %d attempts; giving up",
                    currentMediaId,
                    attemptNumber - 1,
                )
            }
        }
        // One final bounded retry covers transient decoder/container/network failures that do not
        // match the specialised branches above. Retrying the same media item before honoring
        // AutoSkipNextOnError prevents a single short-lived failure from silently advancing the
        // queue. The tracker keeps this from looping forever.
        if (
            !isLocalMedia &&
                !isFullyDownloadedMedia &&
                playbackStreamRecoveryTracker.registerRetryAttempt(currentMediaId)
        ) {
            val shouldResume = player.playWhenReady
            playbackUrlCache.remove(currentMediaId)
            directStreamCache.remove(currentMediaId)
            contentLengthCache.remove(currentMediaId)
            YTPlayerUtils.invalidateCachedStreamUrls(currentMediaId)
            Timber.tag("MusicService").w(
                "Retrying remote playback for %s after unclassified error %s (%s)",
                currentMediaId,
                error.errorCodeName,
                describeCauseChain(error),
            )
            player.prepare()
            if (shouldResume) player.play()
            return
        }


        if (dataStore.get(AutoSkipNextOnErrorKey, false)) {
            skipOnError()
        } else {
            stopOnError()
        }
    }

    private fun isMediaCodecStateError(error: PlaybackException): Boolean =
        isRecoverableMediaCodecStateError(error)

    /** Debug-only helper: short human-readable description of the cause chain for logging. */
    private fun describeCauseChain(error: Throwable): String {
        val causeChain = generateSequence<Throwable>(error) { it.cause }
        return causeChain
            .take(5)
            .joinToString(" -> ") { t ->
                val cls = t.javaClass.simpleName
                val msg = t.message?.take(80)?.replace("\n", " ")?.trim().orEmpty()
                if (msg.isEmpty()) cls else "$cls($msg)"
            }
    }

    private suspend fun trimPlayerCacheToBytes(limitBytes: Long) {
        if (limitBytes <= 0L) return

        withContext(Dispatchers.IO) {
            val cacheDir = StorageLocationRepository.cacheDirectory(this@MusicService, StorageFolderKind.SONG_CACHE)
            val currentSpace = runCatching { playerCache.cacheSpace }.getOrNull() ?: 0L
            var totalBytes = if (currentSpace > 0L) currentSpace else cacheDir.directorySizeBytes()
            if (totalBytes <= limitBytes) return@withContext

            data class Candidate(
                val key: String,
                val lastTouchTimestamp: Long,
                val sizeBytes: Long,
            )

            val candidates =
                runCatching {
                    playerCache.keys
                        .mapNotNull { key ->
                            runCatching {
                                val spans = playerCache.getCachedSpans(key)
                                if (spans.isEmpty()) return@runCatching null
                                val oldestTouch = spans.minOf { it.lastTouchTimestamp }
                                val sizeBytes = spans.sumOf { it.length }
                                Candidate(key = key, lastTouchTimestamp = oldestTouch, sizeBytes = sizeBytes)
                            }.getOrNull()
                        }.sortedBy { it.lastTouchTimestamp }
                }.getOrNull().orEmpty()

            for (candidate in candidates) {
                if (totalBytes <= limitBytes) break
                val removedSize = candidate.sizeBytes.coerceAtLeast(0L)
                runCatching { playerCache.removeResource(candidate.key) }
                totalBytes -= removedSize
            }
        }
    }

    private fun createPlayerCacheDataSourceFactory(cacheWriteEnabled: Boolean): CacheDataSource.Factory =
        CacheDataSource
            .Factory()
            .setCache(playerCache)
            .setUpstreamDataSourceFactory(createResolvedUpstreamDataSourceFactory())
            .apply {
                if (!cacheWriteEnabled) {
                    setCacheWriteDataSinkFactory(null)
                }
            }.setFlags(FLAG_IGNORE_CACHE_ON_ERROR)

    private fun createCacheDataSource(): CacheDataSource.Factory =
        CacheDataSource
            .Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(
                DataSource.Factory {
                    createPlayerCacheDataSourceFactory(
                        cacheWriteEnabled = !isLowDataModeActive(),
                    ).createDataSource()
                },
            ).setCacheWriteDataSinkFactory(null)
            .setFlags(FLAG_IGNORE_CACHE_ON_ERROR)

    private fun createDataSourceFactory(): DataSource.Factory {
        val cachedFactory =
            ResolvingDataSource.Factory(createCacheDataSource()) { dataSpec ->
                resolvePlaybackDataSpec(
                    dataSpec = dataSpec,
                    allowCacheShortCircuit = true,
                )
            }
        val directFactory = createResolvedUpstreamDataSourceFactory()
        val telegramFactory = TelegramDataSource.Factory()
        val tidalProgressiveDashFactory = TidalProgressiveDashDataSource.Factory(mediaOkHttpClient)
        // Deezer needs its own chain rather than cachedFactory's: that one runs the YouTube
        // resolver over the dataSpec, which would rewrite our deezer:// URI. Decryption sits below
        // the cache so what gets cached is already-decrypted audio, letting a replay skip both the
        // CDN fetch and the Blowfish work.
        val deezerFactory =
            CacheDataSource
                .Factory()
                .setCache(playerCache)
                .setUpstreamDataSourceFactory(
                    DeezerDecryptingDataSource.Factory(OkHttpDataSource.Factory(mediaOkHttpClient)),
                ).setCacheWriteDataSinkFactory(
                    CacheDataSink.Factory().setCache(playerCache).setFragmentSize(C.LENGTH_UNSET.toLong()),
                ).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        return DataSource.Factory {
            SchemeRoutingDataSource(
                cachedFactory = cachedFactory,
                directFactory = directFactory,
                telegramFactory = telegramFactory,
                deezerFactory = deezerFactory,
                tidalProgressiveDashFactory = tidalProgressiveDashFactory,
            )
        }
    }

    private fun createResolvedUpstreamDataSourceFactory(): DataSource.Factory {
        val youtubeMediaFactory =
            DefaultDataSource.Factory(
                this,
                OkHttpDataSource.Factory(mediaOkHttpClient),
            )
        // Resolved source URIs pass through this factory a second time after the outer resolver has
        // selected a provider. Route those private schemes here instead of asking DefaultDataSource
        // to handle an unknown `deezer://` or `tidal-dash://` URI.
        val routingFactory =
            DataSource.Factory {
                ResolvedSchemeRoutingDataSource(
                    defaultFactory = youtubeMediaFactory,
                    deezerFactory = DeezerDecryptingDataSource.Factory(OkHttpDataSource.Factory(mediaOkHttpClient)),
                    tidalProgressiveDashFactory = TidalProgressiveDashDataSource.Factory(mediaOkHttpClient),
                )
            }

        return ResolvingDataSource.Factory(routingFactory) { dataSpec ->
            val scheme = dataSpec.uri.scheme?.lowercase(Locale.US)
            if (scheme == DeezerCrypto.SCHEME || scheme == TidalAudioProvider.PROGRESSIVE_DASH_SCHEME) {
                dataSpec
            } else {
                resolvePlaybackDataSpec(
                    dataSpec = dataSpec,
                    allowCacheShortCircuit = false,
                )
            }
        }
    }

    private fun resolveMediaItemForCast(mediaItem: MediaItem): MediaItem {
        val localConfiguration = mediaItem.localConfiguration ?: return mediaItem
        val uri = localConfiguration.uri
        if (uri.shouldBypassYouTubeResolver()) return mediaItem
        val mediaId = localConfiguration.customCacheKey ?: mediaItem.mediaId
        val dataSpec =
            DataSpec
                .Builder()
                .setUri(uri)
                .setKey(mediaId)
                .build()
        val resolvedDataSpec =
            resolvePlaybackDataSpec(
                dataSpec = dataSpec,
                allowCacheShortCircuit = false,
            )
        val resolvedMimeType =
            localConfiguration.mimeType
                ?.substringBefore(";")
                ?.takeIf { it.isNotBlank() && !it.endsWith("/*") }
                ?: runBlocking(Dispatchers.IO) {
                    database.format(mediaId).first()?.mimeType?.substringBefore(";")
                }
        return mediaItem
            .buildUpon()
            .setUri(resolvedDataSpec.uri)
            .apply { resolvedMimeType?.let(::setMimeType) }
            .build()
    }

    private fun parseTidalAudioQuality(): TidalAudioQuality =
        dataStore.get(TidalAudioQualityKey, TidalAudioQuality.FLAC.name).toEnum(TidalAudioQuality.FLAC)

    private fun parseAppleMusicQuality(): AppleMusicQuality {
        val stored = dataStore.get(AppleMusicQualityKey, AppleMusicQuality.LOSSLESS.name)
        return runCatching { AppleMusicQuality.valueOf(stored) }.getOrDefault(AppleMusicQuality.LOSSLESS)
    }

    /** Instance lists are stored one URL per line, blank lines ignored. Shared by Tidal and Qobuz. */
    private fun parseInstances(key: Preferences.Key<String>): List<String> =
        dataStore
            .get(key, "")
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun parseTidalInstances(): List<String> = parseInstances(TidalInstancesKey)

    /** Metadata used to look a track up across the configured audio sources. */
    private data class SourceQuery(
        val mediaId: String,
        val title: String,
        val artists: List<String>,
        val album: String?,
        val durationMs: Long?,
        /**
         * ISRC of the wanted recording, when the queue item carried one (catalogue imports only).
         * A source that can look up by ISRC uses it for an exact match and skips its text search.
         */
        val isrc: String? = null,
        /**
         * When non-null, the Qobuz resolver skips its title/artist search and
         * downloads this exact trackId. Set when the user picks a specific
         * Qobuz track from the "Play from" source-search popup — the mediaId
         * encodes the trackId as "qobuz:{trackId}" and [resolveMultiSourceDataSpec]
         * extracts it into this field.
         */
        val directQobuzTrackId: String? = null,
        /**
         * When non-null, the Qobuz-backup resolver asks the mirror for THIS video id
         * instead of [mediaId]. Set when the user picks a specific row in the
         * "Play from" search popup — the mirror is keyed by YouTube video id, and the
         * row they chose is a different catalogue entry from the song that is playing.
         * Keeping [mediaId] untouched is what makes the switch audio-only.
         */
        val directQobuzBackupVideoId: String? = null,
    )

    /**
     * The ordered list of enabled non-YouTube sources to try for [resolveMultiSourceDataSpec],
     * with the user's primary/search source pinned first and YouTube (implicit fallback) excluded.
     * YouTube's own enable state is still read here so the chain builder and the settings toggles
     * agree on which sources exist; the resolver's terminal YouTube fallback is additionally
     * gated in [resolvePlaybackDataSpec].
     */
    private fun sourceResolutionChain(): List<AudioSourceType> {
        val enabledDefaults =
            mapOf(
                // Defaults MUST match the toggle defaults in PlaybackSourceSections so the
                // UI and the resolver agree on which sources are active out of the box.
                AudioSourceType.TIDAL to dataStore.get(TidalEnabledKey, true),
                AudioSourceType.QOBUZ to dataStore.get(QobuzEnabledKey, false),
                AudioSourceType.QOBUZ_BACKUP to dataStore.get(QobuzBackupEnabledKey, false),
                AudioSourceType.DEEZER to dataStore.get(DeezerEnabledKey, false),
                AudioSourceType.JIOSAAVN to dataStore.get(JioSaavnEnabledKey, false),
                AudioSourceType.APPLE to dataStore.get(AppleMusicSourceEnabledKey, false),
                AudioSourceType.AMAZON to dataStore.get(AmazonEnabledKey, false),
                AudioSourceType.QQ to dataStore.get(QqMusicEnabledKey, false),
                AudioSourceType.YOUTUBE to dataStore.get(YouTubeSourceEnabledKey, true),
            )
        // The single stored order is authoritative (top = preferred). Only sources listed BEFORE
        // YouTube act as lossless overrides; once YouTube is reached the user has chosen to use
        // YouTube's own stream, so any sources after it are not attempted as overrides.
        return AudioSourceConfig
            .resolutionChain(
                rawOrder = dataStore.get(AudioSourceOrderKey, "").ifBlank { null },
                enabledSet = null,
                defaults = enabledDefaults,
            ).takeWhile { it != AudioSourceType.YOUTUBE }
    }

    /**
     * Returns true if [source] is currently enabled in the user's preferences. YouTube is enabled
     * unless the user has turned it off ([YouTubeSourceEnabledKey], default true). Used by
     * [resolveMultiSourceDataSpec] to:
     *   1. Decide whether to honor a per-song override whose source has since been disabled
     *      (fall through to the chain instead of trying only the disabled source).
     *   2. Guard the auto-pin write so we never pin to a source the user just turned off.
     */
    private fun isSourceEnabled(source: AudioSourceType): Boolean =
        when (source) {
            AudioSourceType.YOUTUBE -> dataStore.get(YouTubeSourceEnabledKey, true)
            AudioSourceType.TIDAL -> dataStore.get(TidalEnabledKey, true)
            AudioSourceType.QOBUZ -> dataStore.get(QobuzEnabledKey, false)
            AudioSourceType.QOBUZ_BACKUP -> dataStore.get(QobuzBackupEnabledKey, false)
            AudioSourceType.DEEZER -> dataStore.get(DeezerEnabledKey, false)
            AudioSourceType.APPLE -> dataStore.get(AppleMusicSourceEnabledKey, false)
            AudioSourceType.AMAZON -> dataStore.get(AmazonEnabledKey, false)
            AudioSourceType.QQ -> dataStore.get(QqMusicEnabledKey, false)
            AudioSourceType.JIOSAAVN -> dataStore.get(JioSaavnEnabledKey, false)
        }

    /** Builds the shared lookup metadata for [mediaId] from the database / queue metadata. */
    private fun buildSourceQuery(mediaId: String): SourceQuery? {
        val song =
            runCatching {
                runBlocking(Dispatchers.IO) { database.song(mediaId).first() }
            }.getOrNull()
        val queuedMetadata =
            currentMediaMetadata.value?.takeIf { it.id == mediaId }
                ?: queuedMetadataByMediaId[mediaId]
        val title =
            song?.song?.title
                ?: queuedMetadata?.title
                ?: return null
        val artists =
            song?.artists?.map { it.name }?.takeIf { it.isNotEmpty() }
                ?: queuedMetadata?.artists?.map { it.name }.orEmpty()
        val album =
            song?.song?.albumName
                ?: song?.album?.title
                ?: queuedMetadata?.album?.title
        // ISRC comes only from the in-memory queue metadata: the song table has no ISRC column, and
        // it is only ever set for catalogue-sourced items (Spotify import), which is exactly where
        // an exact-recording match beats a title/artist search.
        val isrc = queuedMetadata?.isrc?.takeIf { it.isNotBlank() }
        val durationMs =
            song?.song?.duration
                ?.takeIf { it > 0 }
                ?.toLong()
                ?.times(1000L)
                ?: queuedMetadata?.duration?.takeIf { it > 0 }?.toLong()?.times(1000L)
        // Read the per-song Qobuz trackId override (set when the user picked a
        // specific Qobuz track from the "Play from" source-search popup). When
        // set, the Qobuz resolver downloads the exact track instead of
        // re-searching by title+artist.
        //
        // IMPORTANT: read directly from dataStore.data.first() instead of the
        // cached dataStore.get(). The cached PreferenceStore._prefs may be
        // stale right after setSongSourceOverrideWithQobuzTrackId wrote the
        // new trackId — the PreferenceStore collector is async and may not
        // have received the emission yet. Reading from data.first() guarantees
        // we see the write that just happened.
        val qobuzTrackIdRaw = runCatching {
            runBlocking { dataStore.data.first()[SongSourceQobuzTrackIdKey] }
        }.getOrNull()
        val directQobuzTrackId = SongSourceQobuzTrackId.get(qobuzTrackIdRaw, mediaId)
        // Same treatment for the Qobuz-backup mirror's video id (see the field docs
        // on SourceQuery.directQobuzBackupVideoId), read fresh for the same reason.
        val qobuzBackupVideoIdRaw = runCatching {
            runBlocking { dataStore.data.first()[SongSourceQobuzBackupVideoIdKey] }
        }.getOrNull()
        val directQobuzBackupVideoId = SongSourceQobuzBackupVideoId.get(qobuzBackupVideoIdRaw, mediaId)
        return SourceQuery(
            mediaId = mediaId,
            title = title,
            artists = artists,
            album = album,
            durationMs = durationMs,
            isrc = isrc,
            directQobuzTrackId = directQobuzTrackId,
            directQobuzBackupVideoId = directQobuzBackupVideoId,
        )
    }

    // mediaId -> set of sources known to have this track (passed the metadata match gate during a recent
    // resolution). Used by the player's Source chooser to only offer sources that actually have the
    // song. Process-lived only; YouTube is always available as the fallback and is added implicitly.
    /** mediaId -> MediaMetadata for every item currently in the queue. */
    private val queuedMetadataByMediaId = ConcurrentHashMap<String, MediaMetadata>()

    private fun cacheQueuedMetadata() {
        if (player.mediaItemCount == 0) return
        val present = HashSet<String>(player.mediaItemCount)
        for (index in 0 until player.mediaItemCount) {
            val item = runCatching { player.getMediaItemAt(index) }.getOrNull() ?: continue
            present.add(item.mediaId)
            item.metadata?.let { queuedMetadataByMediaId[item.mediaId] = it }
        }
        queuedMetadataByMediaId.keys.retainAll(present)
    }

    private val resolvedSourcesByMediaId = ConcurrentHashMap<String, MutableSet<AudioSourceType>>()

    /**
     * Called on app foreground (MainActivity.onStart) for the currently-playing song (or null to
     * clear all). Drops the in-memory record of which lossless sources last resolved successfully
     * so the "Play from" picker re-resolves fresh instead of returning a stale cached set. This
     * pairs with [moe.rukamori.archivetune.qobuz.QobuzAudioProvider.clearTransientCaches]: the
     * provider's own transient failures have to be dropped too, or the re-resolve reuses them.
     */
    fun clearResolvedSources(mediaId: String?) {
        if (mediaId == null) {
            resolvedSourcesByMediaId.clear()
        } else {
            resolvedSourcesByMediaId.remove(mediaId)
        }
        _resolvedSourcesRevision.value = _resolvedSourcesRevision.value + 1L
    }

    /**
     * Observable wrapper around [resolvedSourcesByMediaId] so the Source chooser UI can recompose
     * when a refresh completes. Updated whenever [recordResolvedSource] adds a source.
     */
    private val _resolvedSourcesRevision = MutableStateFlow(0L)

    private fun recordResolvedSource(
        mediaId: String,
        source: AudioSourceType,
    ) {
        Timber.tag("PlaybackDiagnostics").i("Source selected provider=%s", source.name)
        val set = resolvedSourcesByMediaId.getOrPut(mediaId) { java.util.concurrent.ConcurrentHashMap.newKeySet() }
        if (set.add(source)) {
            _resolvedSourcesRevision.value = _resolvedSourcesRevision.value + 1L
        }
    }

    /**
     * Sources the player's "Play from" chooser should offer for [mediaId], based on the last
     * resolution result: any lossless source that matched the track, plus YouTube (always available
     * as the fallback), plus the user's per-song override source (so the user can always see, change
     * or clear their pinned choice even if the last resolution attempt failed transiently — e.g.
     * "Qobuz token returned preview-only; skipping"). Returned in the canonical Tidal, Qobuz, YouTube
     * order.
     */
    fun availableSourcesForSong(mediaId: String): List<AudioSourceType> {
        val resolved = resolvedSourcesByMediaId[mediaId].orEmpty()
        val override = SongSourceOverride.get(dataStore.get(SongSourceOverrideKey, ""), mediaId)
        // Sources are surfaced in the Source chooser when ANY of:
        //   - Always-available: YouTube (the implicit fallback), provided the user has not
        //     turned the YouTube source off.
        //   - The source was previously resolved successfully for this media id.
        //   - The user previously pinned this song to this source (override).
        //   - The source is GLOBALLY ENABLED — the user has explicitly turned the source
        //     on in Settings, so they should be able to pick it per-song even if the
        //     initial resolution hasn't completed (or failed transiently).
        // Opt-in sources (AMAZON, QQ) join the chain only after the user enables them; they are
        // appended above YouTube so the chooser can surface them once enabled, without changing
        // the default resolution order for everyone else.
        val chooserOrder =
            (AudioSourceConfig.DEFAULT_ORDER.filterNot { it == AudioSourceType.YOUTUBE } +
                listOf(AudioSourceType.AMAZON, AudioSourceType.QQ) +
                AudioSourceType.YOUTUBE).distinct()
        return enabledPlaybackSources(
            candidates = chooserOrder.filter {
                it in resolved || it == override || isSourceEnabled(it)
            },
            enabledSources = AudioSourceType.entries.filter(::isSourceEnabled).toSet(),
        )
    }

    /**
     * Triggers a fresh resolution of every enabled non-YouTube source for [mediaId], bypassing the
     * in-memory DirectStream cache.
     */
    fun refreshSourcesForSong(mediaId: String) {
        if (mediaId.isLocalMediaId() || mediaId.isTelegramMediaId()) return
        scope.launch(Dispatchers.IO) {
            // Evict the in-memory cache so the slow path runs again. Without this, the fast
            // path in resolveMultiSourceDataSpec would short-circuit to the previously cached
            // (YouTube) stream and never re-try Qobuz/Tidal.
            directStreamCache.remove(mediaId)
            // Re-resolve by calling the same slow-path logic the player uses. We pass a dummy
            // DataSpec because resolveMultiSourceDataSpec only uses it as a buildUpon base —
            // the URI and key are overwritten inside. The returned DataSpec is discarded; we
            // only care about the side effect of recording sources into resolvedSourcesByMediaId.
            runCatching {
                val dummySpec = DataSpec.Builder().setUri(mediaId.toUri()).build()
                resolveMultiSourceDataSpec(dummySpec, mediaId, lowDataModeActive = false)
            }.onFailure { error ->
                Timber.tag("MusicService").w(error, "refreshSourcesForSong: resolution failed for %s", mediaId)
            }
            // Bump the revision even on failure so the UI can re-render with whatever sources
            // were recorded (if any) and stop showing the loading state.
            _resolvedSourcesRevision.value = _resolvedSourcesRevision.value + 1L
        }
    }

    /**
     * A monotonically increasing counter that bumps whenever [recordResolvedSource] or
     * [refreshSourcesForSong] updates [resolvedSourcesByMediaId]. The Source dialog collects
     * this flow to know when to re-query [availableSourcesForSong].
     */
    val resolvedSourcesRevision: StateFlow<Long> get() = _resolvedSourcesRevision

    /**
     * Applies a per-song "play from" override and, if [mediaId] is the current item, re-resolves it
     * immediately so the change takes effect without the user having to skip the track. Passing a
     * null [source] clears the override for that song.
     */
    fun setSongSourceOverride(
        mediaId: String,
        source: AudioSourceType?,
    ) {
        clearAudioAuthenticitySourceFailures(mediaId)
        setSongSourceOverrideInternal(mediaId, source, qobuzTrackId = null, qobuzBackupVideoId = null)
    }

    /** Sets a per-song source override AND persists the chosen Qobuz trackId. */
    fun setSongSourceOverrideWithQobuzTrackId(
        mediaId: String,
        source: AudioSourceType?,
        qobuzTrackId: String?,
    ) {
        clearAudioAuthenticitySourceFailures(mediaId)
        setSongSourceOverrideInternal(mediaId, source, qobuzTrackId, qobuzBackupVideoId = null)
    }

    /**
     * Sets a per-song source override AND persists the chosen Qobuz-**backup** mirror video id —
     * the counterpart of [setSongSourceOverrideWithQobuzTrackId] for the kouzu.in mirror, which is
     * keyed by YouTube video id rather than a catalogue track id.
     */
    fun setSongSourceOverrideWithQobuzBackupVideoId(
        mediaId: String,
        source: AudioSourceType?,
        qobuzBackupVideoId: String?,
    ) {
        clearAudioAuthenticitySourceFailures(mediaId)
        setSongSourceOverrideInternal(mediaId, source, qobuzTrackId = null, qobuzBackupVideoId = qobuzBackupVideoId)
    }

    private fun clearAudioAuthenticitySourceFailures(mediaId: String) {
        authenticityRejectedSourcesByMediaId.remove(mediaId)
        selectedPlaybackSourceByMediaId.remove(mediaId)
        authenticitySampleRateByMediaId.remove(mediaId)
        authenticityClaimedLosslessByMediaId.remove(mediaId)
        refreshAudioAuthenticityContexts(mediaId)
    }

    private fun setSongSourceOverrideInternal(
        mediaId: String,
        source: AudioSourceType?,
        qobuzTrackId: String?,
        qobuzBackupVideoId: String?,
        authenticityRejectedSource: AudioSourceType? = null,
        preservedPositionMs: Long? = null,
    ) {
        Timber.tag("PlaybackDiagnostics").i(
            "Source override changed provider=%s currentItem=%s state=%d positionMs=%d gain=%.3f",
            source?.name ?: "automatic",
            ::player.isInitialized && player.currentMediaItem?.mediaId == mediaId,
            if (::player.isInitialized) player.playbackState else Player.STATE_IDLE,
            if (::player.isInitialized) player.currentPosition else 0L,
            if (::player.isInitialized) player.volume else 0f,
        )
        // A direct Qobuz selection is an explicit user choice. A failed
        // background probe may have left the provider's negative cache or an
        // instance cooldown in place, causing this fresh selection to resolve
        // as YouTube instead. Clear only transient failures before recreating
        // the media source; successful stream/search caches remain intact.
        if (authenticityRejectedSource == null && source == AudioSourceType.QOBUZ && !qobuzTrackId.isNullOrBlank()) {
            QobuzAudioProvider.clearTransientCaches()
        }
        // Persist the exact-track ids (if any) BEFORE triggering re-resolution.
        //
        // Both are always written, with null for whichever is not part of this
        // selection: a song can only play from one source at a time, so leaving a
        // previous pick's id behind would keep pinning the resolver to the old source
        // even after the user chose a different one.
        runCatching {
            runBlocking {
                dataStore.edit { prefs ->
                    prefs[SongSourceQobuzTrackIdKey] = SongSourceQobuzTrackId.withOverride(
                        prefs[SongSourceQobuzTrackIdKey],
                        mediaId,
                        qobuzTrackId,
                    )
                    prefs[SongSourceQobuzBackupVideoIdKey] = SongSourceQobuzBackupVideoId.withOverride(
                        prefs[SongSourceQobuzBackupVideoIdKey],
                        mediaId,
                        qobuzBackupVideoId,
                    )
                }
            }
        }
        runCatching {
            runBlocking {
                dataStore.edit { prefs ->
                    prefs[SongSourceOverrideKey] =
                        SongSourceOverride.withOverride(prefs[SongSourceOverrideKey], mediaId, source)
                }
            }
        }
        if (player.currentMediaItem?.mediaId == mediaId) {

            // Capture the item FIRST: clearMediaItems() empties the timeline, so
            // currentMediaItem would be null afterwards. Bail before touching any state if it
            // somehow is null here.
            val item = player.currentMediaItem ?: return
            // Capture the expected volume BEFORE any state churn. The teardown below (cache
            // eviction, crossfade cancel, audio-focus re-claim, playlist re-create) makes the
            // reactive volume pipeline (playerVolume x normalizeFactor x focus factor) re-fire
            // mid-transition; recomputing the effective volume from the live flows at that
            // point can pin the player at a stale/ducked value. We re-apply this captured
            // baseline at every recovery point until the switch has deterministically reached
            // STATE_READY, after which the pipeline's live values are authoritative again.
            val expectedVolume = currentEffectivePlayerVolume()
            val wasPlaying = player.playWhenReady
            sourceSwitchPending = true
            sourceSwitchExpectedVolume = expectedVolume

            if (authenticityRejectedSource != null) {
                evictAudioAuthenticityRejectedSource(mediaId, authenticityRejectedSource)
            } else {
                playbackUrlCache.remove(mediaId)
                YTPlayerUtils.invalidateCachedStreamUrls(mediaId)
                tidalActiveMediaIds.remove(mediaId)
                directStreamCache.remove(mediaId)
                contentLengthCache.remove(mediaId)
                runCatching { playerCache.removeResource(mediaId) }
                runCatching { downloadCache.removeResource(mediaId) }
                AudioSourceType.entries.forEach { src ->
                    val key = sourceCacheKey(src, mediaId)
                    runCatching { playerCache.removeResource(key) }
                    runCatching { downloadCache.removeResource(key) }
                    contentLengthCache.remove(key)
                }
            }
            // Cancel any in-flight crossfade so its volume ramp / secondary player can't pin the
            // primary player's volume low while the new source is preparing.
            cancelCrossfade(resetVolume = true, resetPauseAtEnd = true)
            // Re-claim audio focus BEFORE computing/applying the effective volume: applying the
            // volume first multiplies by a stale ducked audioFocusVolumeFactor when focus had been
            // transiently lost, which pins the player at the ducked volume until the reactive
            // volume flow happens to re-fire. Restoring focus first means the captured baseline
            // reflects the restored 1.0 factor.
            ensureAudioFocusForActivePlayback()

            // Deterministic re-create: replace ONLY the current media item with a fresh one (so the
            // new source's MediaSource is built from scratch) while preserving the rest of the
            // queue.
            val currentIndex = player.currentMediaItemIndex
            val capturedPositionMs =
                preservedPositionMs?.let(::resolveAudioAuthenticityRetryPositionMs)
                    ?: player.currentPosition.coerceAtLeast(0L)
            val allItems = ArrayList(player.mediaItems)
            if (currentIndex in allItems.indices) {
                allItems[currentIndex] = item
            } else {
                allItems.add(item)
            }
            player.setMediaItems(
                allItems,
                currentIndex.coerceIn(0, allItems.lastIndex),
                capturedPositionMs,
            )
            player.prepare()
            player.playWhenReady = wasPlaying
            // Restore the effective volume immediately (bypass the smooth ramp) so the new source
            // doesn't briefly play muted while the ramp catches up. Start the audible-playback
            // watchdog so any later volume dip gets corrected within the next polling window.
            applyEffectiveVolumeImmediately(expectedVolume)
            updateAudiblePlaybackRecovery()
            // Defensive delayed re-apply: player.prepare() is async, and the state transitions
            // it triggers (BUFFERING -> READY, audio session ID change, audio-effect rebind,
            // reactive volume flow re-firing) can interleave with the synchronous restore above
            // and re-pin the volume low. Re-apply the CAPTURED baseline once more after the
            // prepare pipeline has settled (or when STATE_READY arrives — whichever fires
            // last), so the user never hears a muted stream. The READY hook cancels this job.
            sourceSwitchReassertJob?.cancel()
            sourceSwitchReassertJob =
                scope.launch {
                    delay(SOURCE_SWITCH_VOLUME_REASSERT_MS)
                    if (sourceSwitchPending &&
                        player.currentMediaItem?.mediaId == mediaId &&
                        player.playWhenReady
                    ) {
                        Timber.tag(TAG).d(
                            "source switch delayed reassert: captured=%s live=%s actual=%s",
                            expectedVolume,
                            currentEffectivePlayerVolume(),
                            player.volume,
                        )
                        ensureAudioFocusForActivePlayback()
                        applyEffectiveVolumeImmediately(expectedVolume)
                        ensureAudiblePlaybackVolume("source_switch_reassert")
                    }
                }
            Timber.tag(TAG).d(
                "setSongSourceOverride: mediaId=%s source=%s capturedVolume=%s wasPlaying=%s state=%s",
                mediaId,
                source,
                expectedVolume,
                wasPlaying,
                player.playbackState,
            )
        }
    }

    /** True when [mediaId] is a music video (OMV/UGC) rather than an audio-only track: the queued
     *  metadata carries the flag straight from the search result, and the DB row covers items
     *  restored from a saved queue. */
    private fun isMusicVideoPlayback(mediaId: String): Boolean {
        val queuedMetadata =
            currentMediaMetadata.value?.takeIf { it.id == mediaId }
                ?: queuedMetadataByMediaId[mediaId]
        if (queuedMetadata?.isMusicVideo == true) return true
        // A direct blocking read on purpose: the Flow variant spins up a Room observer only to take
        // its first emission, and every caller of this gate is already off the main thread.
        return runCatching { database.getSongByIdBlocking(mediaId) }.getOrNull()?.song?.isMusicVideo == true
    }

    /**
     * Attempts to resolve the current media item through the configured audio sources, in the
     * user's chosen priority order. Returns a [DataSpec] pointing at the first source that
     * resolves a stream, or null so playback falls through to the YouTube resolver.
     *
     * Resolves lossless audio through the configured sources (currently Tidal), falling through to
     * the YouTube resolver when none produce a stream.
     */
    private fun resolveMultiSourceDataSpec(
        dataSpec: DataSpec,
        mediaId: String,
        lowDataModeActive: Boolean,
        isPrefetch: Boolean = false,
    ): DataSpec? =
        AudioSourceAttemptScope.within(AudioSourceAttemptTimeouts.FALLBACK_CHAIN_MS) {
            resolveMultiSourceDataSpecWithinDeadline(dataSpec, mediaId, lowDataModeActive, isPrefetch)
        }

    private fun resolveMultiSourceDataSpecWithinDeadline(
        dataSpec: DataSpec,
        mediaId: String,
        lowDataModeActive: Boolean,
        isPrefetch: Boolean,
    ): DataSpec? {
        if (mediaId.isLocalMediaId() || mediaId.isTelegramMediaId()) {
            Timber.tag("MusicService").d("Multi-source skip: %s is a local/telegram media id", mediaId)
            return null
        }
        // A music video must stream its audio from the very same YouTube video it plays the video
        // layer of: every alternative source (Qobuz, JioSaavn, Tidal…) resolves a DIFFERENT master
        // of the song, which drifts out of sync with the video. Pin videos to YouTube outright.
        if (isMusicVideoPlayback(mediaId)) {
            Timber.tag("MusicService").d("Multi-source skip: %s is a music video — audio pinned to YouTube", mediaId)
            return null
        }
        // Direct Qobuz track playback: when the user picks a specific Qobuz
        // track from the "Play from" source-search popup, a per-song Qobuz
        // trackId override is persisted in SongSourceQobuzTrackIdKey. We
        // detect that here so we can force the chain to [QOBUZ] and bypass
        // the metadata match gate — the user explicitly chose this track.
        // The mediaId is NOT changed (it stays as the song's existing YouTube
        // id), so the song is not registered as a duplicate in the playback
        // history.
        //
        // IMPORTANT: read directly from dataStore.data.first() instead of the
        // cached dataStore.get() — see buildSourceQuery for the rationale.
        val qobuzTrackIdRaw = runCatching {
            runBlocking { dataStore.data.first()[SongSourceQobuzTrackIdKey] }
        }.getOrNull()
        val directQobuzTrackId = SongSourceQobuzTrackId.get(qobuzTrackIdRaw, mediaId)
        val isDirectQobuzTrack = directQobuzTrackId != null
        // Same for an explicit Qobuz-backup pick: the mirror is addressed by video id,
        // so a chosen row is only reachable through the stored id. Treat it exactly like
        // a direct Qobuz track — force the chain to that one source, skip the
        // DirectStream cache (which may hold the previous source's URL for this media
        // id) and let it through low-data mode, because it is an explicit user choice.
        val qobuzBackupVideoIdRaw = runCatching {
            runBlocking { dataStore.data.first()[SongSourceQobuzBackupVideoIdKey] }
        }.getOrNull()
        val directQobuzBackupVideoId = SongSourceQobuzBackupVideoId.get(qobuzBackupVideoIdRaw, mediaId)
        val isDirectQobuzBackupTrack = directQobuzBackupVideoId != null
        // A song can only be pinned to one source at a time, so at most one of these is
        // set; `isDirectPick` is the shared "the user chose this exact track" signal.
        val isDirectPick = isDirectQobuzTrack || isDirectQobuzBackupTrack
        val authenticityRejectedSources = authenticityRejectedSourcesByMediaId[mediaId].orEmpty()
        // Read the source override FRESH from DataStore — not from the stale
        // PreferenceStore cache. The PreferenceStore collector is async and
        // may not have received the write from setSongSourceOverride yet.
        // This must use data.first() to see the latest value.
        val sourceOverrideRaw = runCatching {
            runBlocking { dataStore.data.first()[SongSourceOverrideKey] }
        }.getOrNull()
        // Fast path: serve from the in-memory DirectStream cache if we have a
        // fresh entry. This makes "skip to next" instant when the next song
        // was prefetched (see prefetchNextMediaItemStream).
        //
        // SKIP the fast path entirely when a direct Qobuz track override is
        // set — the user just picked a specific Qobuz track, so we must
        // re-resolve through Qobuz even if a cached YouTube stream exists.
        val now = System.currentTimeMillis()
        val cached = directStreamCache[mediaId]
        if (!isDirectPick && cached != null && cached.expiresAtMs > now &&
            canUseCachedPlaybackSource(cached.stream.source, isSourceEnabled(cached.stream.source), authenticityRejectedSources)
        ) {
            val override = SongSourceOverride.get(sourceOverrideRaw, mediaId)
            val cacheHitsOverride =
                if (override != null) {
                    override == cached.stream.source
                } else {
                    cached.stream.source == sourceResolutionChain().firstOrNull()
                }
            if (cacheHitsOverride && !lowDataModeActive) {
                Timber.tag("MusicService").d(
                    "Multi-source cache HIT for %s: %s [%s]",
                    mediaId,
                    cached.stream.source.name,
                    cached.stream.label,
                )
                tidalActiveMediaIds.add(mediaId)
                audioNormalizationFactorCache[mediaId] = 1f
                selectedPlaybackSourceByMediaId[mediaId] = cached.stream.source
                authenticityClaimedLosslessByMediaId[mediaId] =
                    isClaimedLosslessFormat(cached.stream.mimeType, cached.stream.codecs, cached.stream.label)
                resolveAudioAuthenticitySampleRateHz(cached.stream.sampleRate)
                    ?.let { authenticitySampleRateByMediaId[mediaId] = it }
                    ?: authenticitySampleRateByMediaId.remove(mediaId)
                refreshAudioAuthenticityContexts(mediaId)
                recordResolvedSource(mediaId, cached.stream.source)
                val cacheKey = sourceCacheKey(cached.stream.source, mediaId)
                retainReportedAudioFormat(mediaId, CachedAudioMetadata.from(cached.stream))
                recordCachedAudioMetadata(cacheKey, cached.stream)
                cached.stream.contentLength?.takeIf { it > 0L }?.let { contentLengthCache[cacheKey] = it }
                return dataSpec
                    .buildUpon()
                    .setUri(cached.stream.uri.toUri())
                    .setKey(cacheKey)
                    .build()
            }
            // Cache entry doesn't match the per-song override or low-data
            // mode flipped on — evict and fall through to the slow path.
            directStreamCache.remove(mediaId, cached)
        } else if (cached != null) {
            // Expired entry — evict.
            directStreamCache.remove(mediaId, cached)
        }
        // A per-song "play from" override (set via the player's Source chooser, or auto-pinned by a
        // previous successful lossless resolution — see the Source WIN block below) takes
        // precedence over the global order.
        val override =
            when {
                isDirectQobuzTrack -> AudioSourceType.QOBUZ
                isDirectQobuzBackupTrack -> AudioSourceType.QOBUZ_BACKUP
                else -> SongSourceOverride.get(sourceOverrideRaw, mediaId)
            }
        val overrideStillEnabled =
            override == null ||
                isSourceEnabled(override)
        val configuredChain =
            if (isDirectPick) {
                // `override` was pinned to the picked source above.
                listOfNotNull(override)
            } else when (override) {
                null -> sourceResolutionChain()
                AudioSourceType.YOUTUBE -> {
                    if (AudioSourceType.YOUTUBE in authenticityRejectedSources) {
                        sourceResolutionChain()
                    } else if (overrideStillEnabled) {
                        Timber.tag("MusicService").d("Per-song override: %s pinned to YouTube; skipping lossless", mediaId)
                        emptyList()
                    } else {
                        // Stale pin: the user turned YouTube off after pinning this song to it.
                        // Drop the pin and use the normal chain instead of honouring it.
                        Timber.tag("MusicService").d("Per-song override: %s pinned to YouTube but the YouTube source is disabled; using normal chain", mediaId)
                        sourceResolutionChain()
                    }
                }
                else -> if (overrideStillEnabled) {
                    Timber.tag("MusicService").d("Per-song override: %s pinned to %s", mediaId, override.name)
                    listOf(override)
                } else {
                    Timber.tag("MusicService").w(
                        "Per-song override: %s pinned to %s but that source is now disabled; falling through to chain",
                        mediaId,
                        override.name,
                    )
                    sourceResolutionChain()
                }
            }
        val chain =
            AudioAuthenticitySourceRetryPolicy.nextChain(
                configuredChain = configuredChain,
                fallbackChain = sourceResolutionChain(),
                rejectedSources = authenticityRejectedSources,
            )
        Timber.tag("MusicService").d("Multi-source resolve for %s | chain=%s", mediaId, chain.joinToString(",") { it.name })
        if (chain.isEmpty()) {
            Timber.tag("MusicService").d("Multi-source skip: no sources to try (chain empty)")
            return null
        }

        // Low Data Mode normally bypasses lossless sources, but it must not discard an exact
        // Qobuz track the user explicitly selected in the Play from popup. That choice carries
        // a concrete Qobuz track id and must not silently turn into a YouTube Opus stream on a
        // metered network.
        if (lowDataModeActive && !isDirectPick) {
            tidalActiveMediaIds.remove(mediaId)
            Timber.tag("MusicService").i("Low-data mode active; skipping Tidal/Qobuz for %s", mediaId)
            return null
        }

        val query = buildSourceQuery(mediaId)
        if (query == null) {
            Timber.tag("MusicService").w("Multi-source skip: could not build source query (missing metadata) for %s", mediaId)
            return null
        }
        Timber.tag("MusicService").d("Source query built: title=\"%s\" artists=%s durationMs=%s", query.title, query.artists.joinToString("/"), query.durationMs?.toString() ?: "?")

        // Resolve each enabled lossless source and apply the shared metadata-aware safety gate.
        // Title, artist, duration, album and version markers are evaluated together; title-only
        // results must clear a stricter fallback threshold. The strongest accepted candidate wins.
        //
        // OVERRIDE BYPASS: when the user has explicitly pinned this song to a single source via
        // the player's Source chooser ("Play from"), we trust their intent — the TitleMatch gate is
        // skipped for that source's candidate. A non-YouTube candidate's title formatting often
        // differs from the YouTube-derived wanted title enough to fail the metadata gate, which
        // would silently fall back to YouTube and make the pick look like it did nothing. The
        // override is the user's manual override of the gate — we should respect it.
        val overrideIsSourceOverride = (override != null && override != AudioSourceType.YOUTUBE) || isDirectPick
        var best: DirectStream? = null
        var bestSource: AudioSourceType? = null
        var bestScore = 0.0
        val chainDeadline =
            AudioSourceAttemptScope.current()
                ?: AudioSourceAttemptDeadline.start(AudioSourceAttemptTimeouts.FALLBACK_CHAIN_MS)
        for ((sourceIndex, source) in chain.withIndex()) {
            if (chainDeadline.isExpired()) {
                Timber.tag("PlaybackDiagnostics").w(
                    "Source fallback chain timed out remainingProviders=%d",
                    chain.size - sourceIndex,
                )
                break
            }
            Timber.tag("MusicService").d("Trying source: %s for \"%s\"", source.name, query.title)
            val sourceAttemptStartedAtMs = android.os.SystemClock.elapsedRealtime()
            val attemptDeadline = chainDeadline.child(resolveAudioSourceAttemptTimeoutMs(source))
            val attemptResult =
                try {
                    AudioSourceAttemptScope.withDeadline(attemptDeadline) {
                        when (source) {
                            AudioSourceType.TIDAL -> resolveTidalStream(query)
                            AudioSourceType.QOBUZ -> resolveQobuzStream(query)
                            AudioSourceType.QOBUZ_BACKUP -> resolveQobuzBackupStream(query)
                            AudioSourceType.DEEZER -> resolveDeezerStream(query)
                            AudioSourceType.APPLE ->
                                resolveAppleStream(
                                    query,
                                    trusted = overrideIsSourceOverride && override == AudioSourceType.APPLE,
                                )
                            AudioSourceType.AMAZON ->
                                resolveAmazonStream(
                                    query,
                                    trusted = overrideIsSourceOverride && override == AudioSourceType.AMAZON,
                                )
                            AudioSourceType.JIOSAAVN -> resolveJioSaavnStream(query)
                            AudioSourceType.QQ ->
                                resolveQqStream(
                                    query,
                                    trusted = overrideIsSourceOverride && override == AudioSourceType.QQ,
                                )
                            AudioSourceType.YOUTUBE -> null
                        }
                    }
                } catch (_: AudioSourceAttemptTimedOutException) {
                    null
                }
            val attemptTimedOut = attemptDeadline.isExpired()
            val stream = attemptResult.takeUnless { attemptTimedOut }
            Timber.tag("PlaybackDiagnostics").d(
                "Source attempt provider=%s resolved=%s timedOut=%s elapsedMs=%d",
                source.name,
                stream != null,
                attemptTimedOut,
                android.os.SystemClock.elapsedRealtime() - sourceAttemptStartedAtMs,
            )
            if (stream == null) {
                Timber.tag("MusicService").d("Source %s did not resolve \"%s\"", source.name, query.title)
                continue
            }
            val match =
                if (overrideIsSourceOverride && source == override) {
                    // Explicit per-song override: trust the user's choice. Still record the source
                    // as resolved so the Source chooser remembers it for next time.
                    Timber.tag("MusicService").i(
                        "Source %s ACCEPTED for \"%s\" via per-song override (skipping metadata gate) [%s]",
                        source.name, query.title, stream.label,
                    )
                    TitleMatch.Result(true, 1.0, 1.0, 1.0, 1.0, "per-song override bypass")
                } else {
                    TitleMatch.evaluate(
                        wantedTitle = query.title,
                        wantedArtists = query.artists,
                        wantedAlbum = query.album,
                        wantedDurationMs = query.durationMs,
                        stream = stream,
                    )
                }
            if (!match.accepted) {
                Timber.tag("MusicService").i(
                    "Source %s rejected for \"%s\": %s score=%.1f%% title=%.1f%% artist=%s duration=%s matched=\"%s\"",
                    source.name,
                    query.title,
                    match.reason,
                    match.score * 100,
                    match.title * 100,
                    match.artist?.let { "%.1f%%".format(it * 100) } ?: "?",
                    match.duration?.let { "%.1f%%".format(it * 100) } ?: "?",
                    stream.matchedTitle ?: "?",
                )
                continue
            }
            Timber.tag("MusicService").d(
                "Source %s candidate for \"%s\": match %.1f%% (%s) [%s]",
                source.name, query.title, match.score * 100, match.reason, stream.label,
            )
            // Remember that this source has this song (title-gate passed) so the player's Source
            // chooser can list only the sources that actually have the track.
            recordResolvedSource(mediaId, source)
            if (match.score > bestScore) {
                best = stream
                bestSource = source
                bestScore = match.score
            }
            // The list is the user's source preference order. The first valid source wins;
            // later sources are fallbacks, not competitors selected by metadata score.
            if (best != null) break
        }

        val winningStream = best
        val winningSource = bestSource
        if (winningStream != null && winningSource != null) {
            Timber.tag("MusicService").i(
                "Source WIN: %s resolved \"%s\" [%s] metadata match %.1f%% (%s)",
                winningSource.name, query.title, winningStream.label, bestScore * 100, winningStream.uri.take(80),
            )
            // Persist the winning source's track id as a future health-probe track.
            if (winningSource == AudioSourceType.TIDAL) {
                TidalAudioProvider.lastResolvedTrackId?.takeIf { it.isNotBlank() }?.let { probe ->
                    runCatching {
                        runBlocking { dataStore.edit { prefs -> prefs[TidalLastProbeTrackKey] = probe } }
                    }
                }
            } else if (winningSource == AudioSourceType.QOBUZ) {
                QobuzAudioProvider.lastResolvedTrackId?.takeIf { it.isNotBlank() }?.let { probe ->
                    runCatching {
                        runBlocking { dataStore.edit { prefs -> prefs[QobuzLastProbeTrackKey] = probe } }
                    }
                }
            }
            return applyDirectStream(dataSpec, mediaId, winningStream)
        }

        // A non-YouTube source was requested but none matched the title accurately enough; silently
        // fall back to YouTube (no user-facing notice — the track just plays from YouTube). Kept as a
        // debug log only.
        Timber.tag("MusicService").w("No lossless source cleared the metadata match gate for \"%s\"; falling back to YouTube", query.title)
        tidalActiveMediaIds.remove(mediaId)
        return null
    }

    /** Resolves a Tidal stream, trying the signed-in account (official API) before public instances. */
    /**
     * Returns a currently-valid Tidal access token, transparently refreshing it via the stored
     * refresh token when it is missing or within 60s of expiry, and persisting the refreshed
     * token. Returns null when there is no usable token (never logged in, or the refresh token is
     * expired/revoked), so the caller falls back to public instances.
     */
    private fun ensureValidTidalToken(): String? {
        val token = dataStore.get(TidalAccessTokenKey, "")
        val expiry = dataStore.get(TidalTokenExpiryKey, 0L)
        // 60s skew so we never hand out a token that expires mid-request.
        if (token.isNotBlank() && expiry > System.currentTimeMillis() + 60_000L) return token

        val refresh = dataStore.get(TidalRefreshTokenKey, "")
        val flow = dataStore.get(TidalAuthFlowKey, TidalAccountManager.FLOW_OAUTH)
        if (refresh.isBlank()) {
            // No refresh token: either never had one (web-capture session) or it was cleared. If we
            // still hold an unexpired-ish access token, keep using it; otherwise flag re-login.
            if (token.isBlank()) markTidalNeedsRelogin()
            Timber.tag("MusicService").d("Tidal token expired/absent and no refresh token; account path unavailable")
            return token.ifBlank { null }
        }
        // Serialize with the 401 refresh path and double-check under the lock in case another thread
        // already refreshed while we waited.
        return synchronized(tidalTokenRefreshLock) {
            val currentToken = dataStore.get(TidalAccessTokenKey, "")
            val currentExpiry = dataStore.get(TidalTokenExpiryKey, 0L)
            if (currentToken.isNotBlank() && currentExpiry > System.currentTimeMillis() + 60_000L) {
                Timber.tag("MusicService").d("Tidal token already refreshed by another thread; reusing")
                return@synchronized currentToken
            }
            Timber.tag("MusicService").d("Tidal access token expired; refreshing via stored refresh token (flow=%s)", flow)
            val refreshed =
                runCatching {
                    runBlocking(Dispatchers.IO + AudioSourceAttemptScope.coroutineContextElement()) {
                        TidalAccountManager.refreshAccessToken(refresh, flow)
                    }
                }
                    .onFailure { Timber.tag("MusicService").w(it, "Tidal token refresh threw") }
                    .getOrNull()
            if (refreshed == null) {
                Timber.tag("MusicService").w("Tidal token refresh failed; flagging re-login and falling back to public instances")
                markTidalNeedsRelogin()
                return@synchronized null
            }
            persistRefreshedTidalToken(refreshed)
            Timber.tag("MusicService").i(
                "Tidal token refreshed; valid for ~%ds",
                (refreshed.expiresAtMillis - System.currentTimeMillis()) / 1000,
            )
            refreshed.accessToken
        }
    }

    /** Persists a refreshed/renewed Tidal token bundle and clears the re-login flag. */
    private fun persistRefreshedTidalToken(refreshed: TidalAccountManager.TokenResult) {
        runBlocking {
            dataStore.edit { prefs ->
                prefs[TidalAccessTokenKey] = refreshed.accessToken
                prefs[TidalTokenExpiryKey] = refreshed.expiresAtMillis
                refreshed.refreshToken?.let { prefs[TidalRefreshTokenKey] = it }
                refreshed.userId?.let { prefs[TidalUserIdKey] = it }
                refreshed.countryCode?.let { prefs[TidalCountryCodeKey] = it }
                prefs[TidalNeedsReloginKey] = false
            }
        }
    }

    /** Flags that the stored Tidal session can no longer be refreshed and needs an interactive re-login. */
    private fun markTidalNeedsRelogin() {
        runBlocking { dataStore.edit { prefs -> prefs[TidalNeedsReloginKey] = true } }
    }

    /**
     * Forces a Tidal token refresh via the stored refresh token, ignoring the cached expiry. Used
     * when the API rejects a token we believed was valid (401), e.g. after a server-side
     * invalidation. Persists and returns the new access token, or null if refresh is impossible.
     */
    /**
     * Returns true if a [TidalAccountManager.TidalUnauthorizedException] (401) appears anywhere in
     * the throwable's cause or suppressed chain. Needed because ExoPlayer can interrupt the loader
     * thread mid-request, surfacing an [InterruptedException] with the real 401 attached only as a
     * suppressed exception — in which case a naive `catch (TidalUnauthorizedException)` misses it.
     */
    private fun isTidalUnauthorized(root: Throwable?) = TidalAccountManager.isUnauthorized(root)

    /**
     * Serializes all Tidal token refreshes. Tracks resolve on parallel ExoPlayer loader threads, so
     * multiple 401s can fire at once; this lock + the double-check below guarantee exactly one
     * network refresh per expiry, and later waiters observe the already-refreshed token and reuse
     * it (avoids redundant token calls and races on the persisted session).
     */
    private val tidalTokenRefreshLock = Any()

    /**
     * Refreshes the Tidal access token after a 401. [rejectedToken] is the token the API just
     * rejected. Under the lock we re-read the stored token: if it already differs from
     * [rejectedToken], another thread refreshed while we waited, so we return that fresh token
     * WITHOUT another network call. Returns null when there is no usable refresh token or the
     * refresh genuinely fails — in which case we flag the account for interactive re-login. The
     * refresh runs against the client that issued the session (see [TidalAuthFlowKey]).
     */
    private fun refreshTidalToken(rejectedToken: String?): String? =
        synchronized(tidalTokenRefreshLock) {
            val current = dataStore.get(TidalAccessTokenKey, "")
            // Double-check: another thread already refreshed the token while we waited on the lock.
            if (current.isNotBlank() && current != rejectedToken) {
                Timber.tag("MusicService").d("Tidal token already refreshed by another thread; reusing")
                return@synchronized current
            }
            val refresh = dataStore.get(TidalRefreshTokenKey, "")
            val flow = dataStore.get(TidalAuthFlowKey, TidalAccountManager.FLOW_OAUTH)
            if (refresh.isBlank()) {
                Timber.tag("MusicService").w("401 from Tidal but no refresh token; account needs re-login")
                markTidalNeedsRelogin()
                return@synchronized null
            }
            Timber.tag("MusicService").d("Force-refreshing Tidal token after 401 (flow=%s)", flow)
            val refreshed =
                runCatching {
                    runBlocking(Dispatchers.IO + AudioSourceAttemptScope.coroutineContextElement()) {
                        TidalAccountManager.refreshAccessToken(refresh, flow)
                    }
                }
                    .onFailure { Timber.tag("MusicService").w(it, "Force refresh threw") }
                    .getOrNull()
            if (refreshed == null) {
                Timber.tag("MusicService").w("Force refresh failed; account needs re-login")
                markTidalNeedsRelogin()
                return@synchronized null
            }
            persistRefreshedTidalToken(refreshed)
            Timber.tag("MusicService").i("Tidal token force-refreshed after 401")
            refreshed.accessToken
        }

    private fun resolveAppleStream(
        query: SourceQuery,
        trusted: Boolean = false,
    ): DirectStream? {
        if (AppleMusicAudioProvider.mediaUserToken() == null || AppleMusicAudioProvider.usableDevToken() == null) {
            Timber
                .tag("MusicService")
                .d("Apple Music source: missing tokens (sign in via Settings → Apple Music)")
            return null
        }
        val appleQuality = parseAppleMusicQuality()
        val candidates =
            runBlocking(Dispatchers.IO + AudioSourceAttemptScope.coroutineContextElement()) {
                AppleMusicAudioProvider.resolveCandidates(
                    title = query.title,
                    artists = query.artists,
                    album = query.album,
                    durationMs = query.durationMs,
                    quality = appleQuality,
                )
            }
        if (candidates.isEmpty()) return null

        // Gate candidates locally (shared metadata gate) so a wrong search hit falls through
        // to the next candidate; with a per-song override the user's pick wins outright.
        var winner: Pair<AppleMusicAudioProvider.AppleMusicStream, DirectStream>? = null
        var bestScore = -1.0
        for (candidate in candidates) {
            val stream =
                DirectStream(
                    uri = "apple-pending:${candidate.songId}",
                    mimeType = "audio/mp4",
                    codecs = if (candidate.flavor.contains("ctrp", ignoreCase = true) &&
                        candidate.flavor.filter(Char::isDigit).toIntOrNull()?.let { it > 320 } == true
                    ) "alac" else "mp4a.40.2",
                    contentLength = candidate.contentLength,
                    label = "Apple Music ${candidate.flavor}",
                    source = AudioSourceType.APPLE,
                    matchedTitle = candidate.matchedTitle,
                    matchedArtist = candidate.matchedArtist,
                    matchedAlbum = candidate.matchedAlbum,
                    matchedDurationMs = candidate.matchedDurationMs,
                )
            val match =
                if (trusted) {
                    TitleMatch.Result(true, 1.0, 1.0, 1.0, 1.0, "per-song override bypass")
                } else {
                    TitleMatch.evaluate(
                        wantedTitle = query.title,
                        wantedArtists = query.artists,
                        wantedAlbum = query.album,
                        wantedDurationMs = query.durationMs,
                        stream = stream,
                    )
                }
            if (match.accepted && match.score > bestScore) {
                winner = candidate to stream
                bestScore = match.score
            }
        }
        val (candidate, placeholder) = winner ?: return null

        // Materialize the winning candidate into a cache file; playback is an ordinary
        // progressive file stream with the Widevine L3 session decrypting at the codec level.
        return try {
            val file =
                appleStreamFile(query.mediaId, appleQuality) {
                    AppleMusicVirtualStream.build(mediaOkHttpClient, candidate.playlistUrl, candidate.keyIdHex).bytes
                }
            appleDrmTrackInfo[query.mediaId] = AppleTrackDrmInfo(adamId = candidate.songId, drmUri = candidate.drmUri)
            // Nerd-info adaptation: patch the format row so the media-info sheet shows the
            // Apple codec/size for this song instead of the stale YouTube values (mirrors the
            // contentLength backfill pattern; the YouTube resolver rewrites the row whenever
            // the song is played from YouTube again).
            runCatching {
                runBlocking(Dispatchers.IO + AudioSourceAttemptScope.coroutineContextElement()) {
                    val row = database.getFormatsByIds(listOf(query.mediaId)).firstOrNull()
                    if (row != null) {
                        val bitrate = measuredBitrate(file.length(), candidate.matchedDurationMs)
                        database.query {
                            upsert(
                                row.copy(
                                    codecs = placeholder.codecs,
                                    contentLength = file.length(),
                                    bitrate = bitrate ?: row.bitrate,
                                ),
                            )
                        }
                    }
                }
            }
            Timber
                .tag("MusicService")
                .i("Apple Music resolved [%s] for \"%s\" (%d KB)", placeholder.label, query.title, file.length() / 1024)
            placeholder.copy(uri = android.net.Uri.fromFile(file).toString(), contentLength = file.length())
        } catch (err: Throwable) {
            Timber.tag("MusicService").w(err, "Apple Music virtual stream failed for \"%s\"", query.title)
            null
        }
    }

    /**
     * Amazon Music via an instance (no official Web API, no LWA sign-in).
     *
     * The instance list is the user's own entries plus whatever the pool serves for `amazon-music`
     * ([AmazonInstances.merge] settles the order and hands each host its own material), and each is
     * tried in turn, first success wins. The user's own entries come first, and a host only ever
     * receives the material issued for it — a bypass token belongs to its operator and a solved
     * Turnstile JWT to the origin that handed out the challenge. The audio tier comes from
     * [AmazonAudioQualityKey]. The provider resolves the track, downloads + decrypts the CENC stream
     * to a local file, and hands back a [DirectStream] with the matched *instance* metadata (never
     * the query's) so the match gate below sees what actually came back. The provider was restored
     * from this repo's own earlier instance-based source (see AmazonAudioProvider's header).
     */
    private fun resolveAmazonStream(
        query: SourceQuery,
        trusted: Boolean,
    ): DirectStream? {
        // One reading of the clock for both expiry decisions below.
        val nowMs = System.currentTimeMillis()
        val instances =
            AmazonInstances.merge(
                local = AmazonInstances.parseBaseUrls(dataStore.get(AmazonInstancesKey).orEmpty()),
                localBypassToken = dataStore.get(AmazonBypassTokenKey),
                // A recorded expiry that has passed means a guaranteed 428, so the token is left
                // out and the settings screen's "re-authorize" state is what the user sees. No
                // recorded expiry means the token is sent anyway and the instance judges.
                localTurnstileJwt =
                    dataStore.get(AmazonTurnstileJwtKey).takeIf {
                        !it.isNullOrBlank() &&
                            AmazonInstances.isJwtUsable(
                                expiresAtMs = dataStore.get(AmazonTurnstileJwtExpiryMsKey, 0L).takeIf { it > 0L },
                                nowMs = nowMs,
                            )
                    },
                pooled = PoolAccountManager.amazonInstances(),
                nowMs = nowMs,
            )
        if (instances.isEmpty()) {
            if (!amazonInertLogged) {
                amazonInertLogged = true
                Timber
                    .tag("MusicService")
                    .i("Amazon Music: no instance with authorization material — the source stays inert")
            }
            return null
        }

        val quality =
            runCatching {
                AmazonAudioQuality.valueOf(
                    dataStore.get(AmazonAudioQualityKey, AmazonAudioQuality.Default.name),
                )
            }.getOrDefault(AmazonAudioQuality.Default)

        for (instance in instances) {
            val stream =
                runBlocking(Dispatchers.IO + AudioSourceAttemptScope.coroutineContextElement()) {
                    AmazonAudioProvider.resolveByMetadata(
                        title = query.title,
                        artists = query.artists,
                        album = query.album,
                        durationMs = query.durationMs,
                        cacheDir = cacheDir,
                        instanceBaseUrl = instance.baseUrl,
                        bypassToken = instance.bypassToken,
                        quality = quality.name,
                        turnstileJwt = instance.turnstileJwt,
                    )
                } ?: continue
            val match =
                if (trusted) {
                    TitleMatch.Result(true, 1.0, 1.0, 1.0, 1.0, "per-song override bypass")
                } else {
                    TitleMatch.evaluate(
                        wantedTitle = query.title,
                        wantedArtists = query.artists,
                        wantedAlbum = query.album,
                        wantedDurationMs = query.durationMs,
                        stream = stream,
                    )
                }
            if (!match.accepted) continue
            Timber
                .tag("MusicService")
                .i("Amazon Music resolved \"%s\" via %s", query.title, instance.baseUrl)
            return stream
        }
        return null
    }

    /**
     * QQ Music — the signed-in user's own account.
     *
     * Declines (null) when no account is connected, when the catalogue has no hit, when no hit
     * clears the metadata gate, or when the account is not served the requested tier. That last case
     * is the ordinary one for a track outside the account's entitlements, and it falls through to
     * the next source like every other miss.
     *
     * [trusted] is the per-song override: it reaches the provider, which skips the metadata gate for
     * the catalogue hit when the user pinned this song to QQ Music by hand.
     */
    private fun resolveQqStream(
        query: SourceQuery,
        trusted: Boolean,
    ): DirectStream? {
        val session =
            runCatching {
                runBlocking(Dispatchers.IO + AudioSourceAttemptScope.coroutineContextElement()) {
                    QqMusicSession.read(dataStore)
                }
            }.getOrNull()
        if (session == null) {
            Timber.tag("MusicService").d("QQ Music: no account connected; skipping")
            return null
        }

        val quality =
            runCatching {
                QqAudioQuality.valueOf(
                    dataStore.get(QqMusicAudioQualityKey, QqAudioQuality.Default.name),
                )
            }.getOrDefault(QqAudioQuality.Default)

        return runCatching {
            runBlocking(Dispatchers.IO + AudioSourceAttemptScope.coroutineContextElement()) {
                QqMusicAudioProvider.resolveByMetadata(
                    title = query.title,
                    artists = query.artists,
                    album = query.album,
                    durationMs = query.durationMs,
                    quality = quality,
                    session = session,
                    cacheDir = cacheDir,
                    trusted = trusted,
                )
            }
        }.getOrElse { error ->
            Timber.tag("MusicService").w(error, "QQ Music resolution failed")
            null
        }
    }

    @Volatile
    private var amazonInertLogged = false

    /**
     * Cache file for an Apple stream (cacheDir/applemusic/<mediaId>.m4a), built via [build]
     * when missing, with a simple prune: oldest files go first past ~300 MB.
     */
    private fun appleStreamFile(
        mediaId: String,
        quality: AppleMusicQuality,
        build: () -> ByteArray,
    ): java.io.File {
        val dir = java.io.File(cacheDir, "applemusic").apply { mkdirs() }
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: emptyList()
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= 300L * 1024 * 1024) break
            total -= f.length()
            f.delete()
        }
        val safeId = mediaId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        // The v2 marker retires files whose Widevine key id is all-zero: they are structurally
        // valid (and therefore cached and replayed forever) but decode to silence. Bumping the name
        // is what makes that observable on a song the user has already played; the size prune above
        // retires the stale v1 files.
        val out = java.io.File(dir, "${safeId}_${quality.name.lowercase()}_v2.m4a")
        if (out.exists() && out.length() > 0) return out
        out.writeBytes(build())
        return out
    }

    private fun resolveTidalStream(query: SourceQuery): DirectStream? {
        val quality = parseTidalAudioQuality()
        val accountFirst = dataStore.get(TidalAccountFirstKey, true)
        Timber.tag("MusicService").d("Tidal resolve start | quality=%s accountFirst=%s", quality.name, accountFirst)

        // Account-first: use a real Tidal subscriber token via the official API when available —
        // first the user's own signed-in account, then shared premium accounts from the community
        // Source Pool. Both paths yield full-quality FLAC directly, so a proxy instance is only a
        // last resort.
        if (accountFirst) {
            val apiQuality =
                when (quality) {
                    TidalAudioQuality.HI_RES_LOSSLESS -> "HI_RES_LOSSLESS"
                    TidalAudioQuality.FLAC -> "LOSSLESS"
                    TidalAudioQuality.AAC_320 -> "HIGH"
                }
            fun attempt(accessToken: String, countryCode: String): DirectStream? =
                runBlocking(Dispatchers.IO + AudioSourceAttemptScope.coroutineContextElement()) {
                    TidalAccountManager.resolveDirectStream(
                        accessToken = accessToken,
                        title = query.title,
                        artists = query.artists,
                        durationMs = query.durationMs,
                        audioQuality = apiQuality,
                        cacheDir = cacheDir,
                        countryCode = countryCode,
                    )
                }

            // 1) The user's own token. Resolve, transparently refreshing and retrying once if the
            //    API rejects it (401) — the stored token may have been invalidated server-side even
            //    though it has not expired by our clock.
            var token = ensureValidTidalToken()
            Timber.tag("MusicService").d("Tidal account token available=%s", token != null)
            if (token != null) {
                val accountCountry = dataStore.get(TidalCountryCodeKey, "").ifBlank { "US" }
                val accountStream =
                    try {
                        attempt(token, accountCountry)
                    } catch (e: Throwable) {
                        // A 401 may surface directly, or wrapped as a *suppressed*/cause exception
                        // inside an InterruptedException when ExoPlayer interrupts the loader thread
                        // mid-request. Detect it anywhere in the chain so the refresh path still runs.
                        if (isTidalUnauthorized(e)) {
                            Timber.tag("MusicService").w("Tidal account 401 (possibly wrapped); refreshing token + retrying")
                            // Clear any pending interrupt on this loader thread; otherwise the
                            // runBlocking calls in refresh + retry would immediately abort.
                            Thread.interrupted()
                            val refreshed = refreshTidalToken(rejectedToken = token)
                            if (refreshed != null && refreshed != token) {
                                token = refreshed
                                runCatching { attempt(refreshed, accountCountry) }
                                    .onFailure { Timber.tag("MusicService").w(it, "Tidal account retry failed for %s", query.mediaId) }
                                    .getOrNull()
                            } else {
                                null
                            }
                        } else {
                            Timber.tag("MusicService").w(e, "Tidal account resolve failed for %s", query.mediaId)
                            null
                        }
                    }
                if (accountStream != null) return accountStream
            }

            // 2) Shared premium Tidal accounts contributed to the community Source Pool (premium
            //    first). These are genuine subscriber tokens, so they stream full-quality FLAC via
            //    the official API without anyone hosting a restream instance.
            for (poolAccount in PoolAccountManager.tidalAccounts()) {
                val poolCountry = poolAccount.countryCode?.trim()?.ifBlank { null } ?: "US"
                val poolStream =
                    runCatching { attempt(poolAccount.token, poolCountry) }
                        .onFailure {
                            if (TidalAccountManager.isUnauthorized(it)) {
                                PoolAccountManager.report("tidal", "account", poolAccount.id, "dead")
                            } else {
                                Timber.tag("MusicService").w(it, "Tidal pool account resolve failed for %s", query.mediaId)
                            }
                        }
                        .getOrNull()
                if (poolStream != null) {
                    Timber.tag("MusicService").d("Tidal resolved via pool account (premium=%s)", poolAccount.premium)
                    PoolAccountManager.noteAccountSuccess("tidal", poolAccount.id)
                    return poolStream
                }
                // Threw, or returned null because this account has no match. Either way it just
                // cost up to 20s, so sort it last for a while rather than paying that again on
                // the next track — the server's own dead-account handling is far slower than a
                // listening session.
                PoolAccountManager.noteAccountFailure("tidal", poolAccount.id)
            }
        }

        // Fallback: public HiFi/QQDL instances (empty list falls back to built-in defaults).
        // Union the user's configured instances with community instances discovered from the
        // Source Pool website (verified-healthy ones, cached by the startup scan). User entries are
        // kept and ordered first; discovery only adds, never removes.
        val configuredInstances = parseTidalInstances()
        val discoveredInstances = TidalInstanceHealthManager.healthyUrls(this)
        val mergedInstances =
            LinkedHashSet<String>().apply {
                addAll(configuredInstances)
                addAll(discoveredInstances)
            }.toList()
        Timber.tag("MusicService").d(
            "Tidal public-instance fallback | configured=%d discovered=%d merged=%d",
            configuredInstances.size,
            discoveredInstances.size,
            mergedInstances.size,
        )
        TidalAudioProvider.setInstances(mergedInstances)
        val resolved =
            runCatching {
                TidalAudioProvider.resolve(
                    query =
                        TidalAudioProvider.Query(
                            mediaId = query.mediaId,
                            title = query.title,
                            artists = query.artists,
                            album = query.album,
                            // Tidal's resolver already scores an exact-ISRC hit above any text match
                            // (see exactIsrc/exactIsrcOnly); it was only ever being handed null here.
                            isrc = query.isrc,
                            durationMs = query.durationMs,
                        ),
                    cacheDir = cacheDir,
                    preferAtmos = false,
                    preferLiveDash = true,
                    audioQuality = quality,
                )
            }.onFailure { error ->
                Timber.tag("MusicService").w(error, "TIDAL stream resolution failed for %s", query.mediaId)
            }.getOrNull() ?: return null

        return DirectStream(
            uri = resolved.mediaUri,
            mimeType = resolved.mimeType,
            codecs = resolved.codecs,
            contentLength = resolved.contentLength,
            label = "Tidal ${resolved.label}",
            source = AudioSourceType.TIDAL,
            matchedTitle = resolved.matchedTitle,
            matchedArtist = resolved.matchedArtist,
            matchedAlbum = resolved.matchedAlbum,
            matchedDurationMs = resolved.matchedDurationMs,
        )
    }

    /** Resolves a Qobuz stream through the user-provided proxy instances. */
    private fun resolveQobuzStream(query: SourceQuery): DirectStream? {
        val userInstances = parseQobuzInstances()
        // Union user-configured instances with community instances discovered from the Source Pool
        // website (cached ~10min inside the provider). User entries stay first; discovery only adds.
        val discoveredInstances = runCatching { QobuzAudioProvider.discoverInstances() }.getOrDefault(emptyList())
        val configuredInstances =
            LinkedHashSet<String>().apply {
                addAll(userInstances)
                addAll(discoveredInstances)
            }.toList()
        // Union the user's own Qobuz tokens with shared premium accounts from the community Source
        // Pool. Pool accounts carry the app_secret needed to sign stream URLs, so they resolve FLAC
        // directly against www.qobuz.com. User entries stay first; pool accounts are appended and
        // deduped by auth token.
        val poolTokens =
            PoolAccountManager.qobuzAccounts().map {
                QobuzToken(
                    token = it.token,
                    appId = it.appId,
                    appSecret = it.appSecret,
                    label = "Source Pool",
                    subscription = if (it.premium) "premium" else "",
                    poolId = it.id,
                )
            }
        val configuredTokens =
            (QobuzToken.listFromJson(dataStore.get(QobuzTokensKey, "")) + poolTokens)
                .distinctBy { it.token }
        if (configuredInstances.isEmpty() && configuredTokens.isEmpty()) {
            Timber.tag("MusicService").d("Qobuz skip: no tokens or instances configured")
            return null
        }
        val formatId = parseQobuzAudioQuality().toFormatId()
        Timber.tag("MusicService").d(
            "Qobuz resolve start | formatId=%d tokens=%d instances=%d",
            formatId,
            configuredTokens.size,
            configuredInstances.size,
        )
        QobuzAudioProvider.setTokens(configuredTokens)
        QobuzAudioProvider.setInstances(configuredInstances)
        return runCatching {
            runBlocking(Dispatchers.IO + AudioSourceAttemptScope.coroutineContextElement()) {
                QobuzAudioProvider.resolve(
                    query =
                        QobuzAudioProvider.Query(
                            mediaId = query.mediaId,
                            title = query.title,
                            artists = query.artists,
                            album = query.album,
                            durationMs = query.durationMs,
                            directTrackId = query.directQobuzTrackId,
                        ),
                    formatId = formatId,
                )
            }
        }.onFailure { error ->
            Timber.tag("MusicService").w(error, "QOBUZ stream resolution failed for %s", query.mediaId)
        }.getOrNull()
    }

    /**
     * Resolves a **Qobuz backup** stream — the community-hosted `mlc-ytify.kouzu.in` mirror, which
     * serves a FLAC per YouTube video id.
     */
    private fun resolveQobuzBackupStream(query: SourceQuery): DirectStream? {
        // An explicit pick from the "Play from" popup addresses a specific mirror entry, which is
        // usually NOT the playing song's own id — honour it when present and fall back to the media
        // id for the automatic path.
        val ytId = (query.directQobuzBackupVideoId ?: query.mediaId).trim()
        val resolved =
            runCatching {
                runBlocking(Dispatchers.IO + AudioSourceAttemptScope.coroutineContextElement()) {
                    QobuzBackupProvider.resolveStream(ytId, mediaOkHttpClient)
                }
            }.onFailure { error ->
                Timber.tag("MusicService").w(error, "Qobuz backup resolution failed for %s", ytId)
            }.getOrNull() ?: return null

        Timber.tag("MusicService").i(
            "Qobuz backup resolved \"%s\" via mlc-ytify.kouzu.in → %s [%s%s%s]",
            query.title,
            resolved.uri.take(80),
            resolved.contentType,
            if (resolved.isLossless) ", lossless" else "",
            resolved.sampleRate?.let { rate ->
                ", ${resolved.bitDepth?.toString() ?: "?"}bit/${rate / 1000f}kHz"
            }.orEmpty(),
        )
        return DirectStream(
            uri = resolved.uri,
            mimeType = resolved.mimeType,
            codecs = resolved.codecs,
            contentLength = resolved.contentLength,
            label = resolved.label,
            source = AudioSourceType.QOBUZ_BACKUP,
            sampleRate = resolved.sampleRate,
            bitDepth = resolved.bitDepth,
            trustedDirectId = true,
            matchedTitle = query.title,
            matchedArtist = query.artists.joinToString(", ").ifBlank { null },
            matchedAlbum = query.album,
            // The FLAC header's own sample count is the exact length of the file being served;
            // prefer it over the catalogue duration so the Details card and the measured bitrate
            // are both computed from the real file.
            matchedDurationMs = resolved.durationMs ?: query.durationMs,
        )
    }


    private fun parseQobuzInstances(): List<String> = parseInstances(QobuzInstancesKey)

    private fun parseQobuzAudioQuality(): QobuzAudioQuality =
        dataStore.get(QobuzAudioQualityKey, QobuzAudioQuality.FLAC.name).toEnum(QobuzAudioQuality.FLAC)

    /**
     * Resolves a Deezer stream: accounts first (manual ARL, then pooled ARLs), then Deezer API
     * instances (user-added, then the pool's instance feed). A no-op until one of those exists.
     */
    private fun resolveDeezerStream(query: SourceQuery): DirectStream? {
        // DeezerAudioProvider.accounts() merges the manually signed-in account (setManualArl) with
        // PoolAccountManager.deezerAccounts(), so the guard has to go through it: asking the pool
        // directly misses a user who signed in on the Deezer login screen, and every track then
        // silently produces null.
        if (!DeezerAudioProvider.hasBackends()) {
            Timber.tag("MusicService").d("Deezer skip: no accounts and no API instances available")
            return null
        }
        val quality = parseDeezerAudioQuality()
        Timber.tag("MusicService").d("Deezer resolve start | quality=%s", quality.name)
        return runCatching {
            runBlocking(Dispatchers.IO + AudioSourceAttemptScope.coroutineContextElement()) {
                DeezerAudioProvider
                    .resolve(
                        query =
                            DeezerAudioProvider.Query(
                                mediaId = query.mediaId,
                                title = query.title,
                                artists = query.artists,
                                album = query.album,
                                durationMs = query.durationMs,
                                isrc = query.isrc,
                            ),
                        format = quality.toFormatName(),
                    )?.let { resolved ->
                        // The provider deliberately returns its own type so it stays independent of the
                        // playback layer; map it here, at the single point where the two meet.
                        DirectStream(
                            uri = resolved.uri,
                            mimeType = resolved.mimeType,
                            codecs = resolved.codecs,
                            contentLength = resolved.contentLength,
                            label = resolved.label,
                            source = AudioSourceType.DEEZER,
                            matchedTitle = resolved.matchedTitle,
                            matchedArtist = resolved.matchedArtist,
                            matchedAlbum = resolved.matchedAlbum,
                            matchedDurationMs = resolved.matchedDurationMs,
                            sampleRate = resolved.sampleRate,
                            bitDepth = resolved.bitDepth,
                        )
                    }
            }
        }.onFailure { error ->
            Timber.tag("MusicService").w(error, "DEEZER stream resolution failed for %s", query.mediaId)
        }.getOrNull()
    }

    private fun parseDeezerAudioQuality(): DeezerAudioQuality {
        val stored = dataStore.get(DeezerAudioQualityKey, DeezerAudioQuality.FLAC.name)
        return runCatching { DeezerAudioQuality.valueOf(stored) }.getOrDefault(DeezerAudioQuality.FLAC)
    }

    /**
     * Resolves a JioSaavn stream. Ported from vivi-music (https://github.com/vivizzz007/vivi-music).
     *
     * JioSaavn is unauthenticated: we search the public JioSaavn API, pick the best match by
     * title/artist/duration, decrypt the encrypted_media_url via DES-ECB, and select the
     * stream URL matching the user's quality preference. Falls back to YouTube on any failure.
     */
    private fun resolveJioSaavnStream(query: SourceQuery): DirectStream? {
        // No global JioSaavnEnabledKey gate here on purpose: the chain (sourceResolutionChain /
        // the override list) already filters by enabled-ness, and a gate would block the per-song
        // override — a song pinned to JioSaavn via the Source chooser must resolve even when
        // JioSaavn is globally off.
        val quality = SaavnAudioQuality.fromStoredName(dataStore.get(SaavnAudioQualityKey, SaavnAudioQuality.QUALITY_320.name))
        val qualityApiValue = quality.toApiValue()
        Timber.tag("MusicService").d("JioSaavn resolve start | quality=%s", qualityApiValue)

        // Build a search query from title + primary artist (+ album as a soft hint).
        val artistHint = query.artists.firstOrNull()?.takeIf { it.isNotBlank() } ?: ""
        val albumHint = query.album?.takeIf { it.isNotBlank() } ?: ""
        val searchQuery =
            buildString {
                append(query.title)
                if (artistHint.isNotBlank()) append(" ").append(artistHint)
                if (albumHint.isNotBlank()) append(" ").append(albumHint)
            }.trim()

        return runCatching {
            runBlocking(Dispatchers.IO + AudioSourceAttemptScope.coroutineContextElement()) {
                val searchResult = SaavnService.searchSongs(searchQuery).getOrNull() ?: return@runBlocking null
                if (searchResult.isEmpty()) return@runBlocking null

                // Pick best candidate by title/artist/duration. Prefer non-Pro tracks (Pro-Only tracks
                // return a 30-second preview from JioSaavn's CDN and we cannot stream them).
                val wantedDurationSec = query.durationMs?.let { it / 1000 }
                val candidate =
                    searchResult
                        .filter { !it.isProOnly && it.downloadUrl.isNotEmpty() }
                        .minByOrNull { song ->
                            var penalty = 0
                            // Title similarity (simple normalized contains + length delta)
                            val normTitle = song.name.lowercase().replace(JIO_SAAVN_NORMALIZE_REGEX, "")
                            val normWanted = query.title.lowercase().replace(JIO_SAAVN_NORMALIZE_REGEX, "")
                            penalty += if (normTitle == normWanted) 0 else if (normTitle.contains(normWanted) || normWanted.contains(normTitle)) 1 else 5
                            // Artist match
                            val candidateArtist = song.artists.primary.firstOrNull()?.name?.lowercase()?.replace(JIO_SAAVN_NORMALIZE_REGEX, "") ?: ""
                            val wantedArtist = artistHint.lowercase().replace(JIO_SAAVN_NORMALIZE_REGEX, "")
                            if (wantedArtist.isNotBlank() && candidateArtist.isNotBlank()) {
                                penalty += if (candidateArtist == wantedArtist) 0 else if (candidateArtist.contains(wantedArtist) || wantedArtist.contains(candidateArtist)) 1 else 3
                            }
                            // Duration delta
                            if (wantedDurationSec != null && song.duration != null) {
                                val candidateDuration = song.duration!!.toLong()
                                val wantedSec = wantedDurationSec!!
                                val delta = kotlin.math.abs(candidateDuration - wantedSec)
                                penalty += when {
                                    delta <= 3 -> 0
                                    delta <= 10 -> 2
                                    else -> 6
                                }
                            }
                            penalty
                        } ?: return@runBlocking null

                val streamUrl = SaavnService.selectBestUrl(candidate.downloadUrl, qualityApiValue) ?: return@runBlocking null
                val durationMs = candidate.duration?.toLong()?.times(1000L)

                DirectStream(
                    uri = streamUrl,
                    mimeType = "audio/mp4",
                    codecs = "mp4a.40.2",
                    contentLength = null,
                    label = "JioSaavn ${quality.toLabel()}",
                    source = AudioSourceType.JIOSAAVN,
                    matchedTitle = candidate.name,
                    matchedArtist = candidate.artists.primary.firstOrNull()?.name,
                    matchedAlbum = candidate.album?.name,
                    matchedDurationMs = durationMs,
                )
            }
        }.onFailure { error ->
            Timber.tag("MusicService").w(error, "JIOSAAVN stream resolution failed for %s", query.mediaId)
        }.getOrNull()
    }

    /**
     * Applies a resolved [DirectStream] to the [dataSpec]: namespaces the cache key per-source so
     * bytes never collide with the YouTube stream (or another source) for the same media id, and
     * marks the track so YouTube-derived audio normalization is skipped for it.
     */
    private fun applyDirectStream(
        dataSpec: DataSpec,
        mediaId: String,
        stream: DirectStream,
    ): DataSpec {
        Timber.tag("MusicService").i("Using %s stream for %s: %s", stream.source, mediaId, stream.label)
        selectedPlaybackSourceByMediaId[mediaId] = stream.source
        authenticityClaimedLosslessByMediaId[mediaId] = isClaimedLosslessFormat(stream.mimeType, stream.codecs, stream.label)
        resolveAudioAuthenticitySampleRateHz(stream.sampleRate)
            ?.let { authenticitySampleRateByMediaId[mediaId] = it }
            ?: authenticitySampleRateByMediaId.remove(mediaId)
        refreshAudioAuthenticityContexts(mediaId)
        val cacheKey = sourceCacheKey(stream.source, mediaId)
        retainReportedAudioFormat(mediaId, CachedAudioMetadata.from(stream))
        recordCachedAudioMetadata(cacheKey, stream)
        stream.contentLength?.takeIf { it > 0L }?.let { contentLengthCache[cacheKey] = it }
        tidalActiveMediaIds.add(mediaId)
        audioNormalizationFactorCache[mediaId] = 1f
        // Persist a FormatEntity for the resolved external stream so the media-info "Details" tab
        // has data to show. Without this, the nerd-stats Details card spun on "please wait" forever
        // for Tidal (and any non-YouTube source), because only the YouTube resolver wrote a format.
        // itag = 0 is a sentinel for "not a YouTube itag" and is hidden in the UI.
        persistDirectStreamFormat(mediaId, stream)
        // Cache the resolved DirectStream so the next playback / prefetch for
        // this media id skips the slow Qobuz/Tidal resolution. The TTL matches
        // the typical signed-URL lifetime on those providers (~5 min).
        directStreamCache[mediaId] = CachedDirectStream(
            stream = stream,
            expiresAtMs = System.currentTimeMillis() + DIRECT_STREAM_CACHE_TTL_MS,
        )
        return dataSpec
            .buildUpon()
            .setUri(stream.uri.toUri())
            .setKey(cacheKey)
            .build()
    }

    /**
     * Writes a [FormatEntity] describing a resolved external ([DirectStream]) source so the
     * media-info "Details" tab can render technical stats instead of spinning forever.
     */
    private fun persistDirectStreamFormat(
        mediaId: String,
        stream: DirectStream,
    ) {
        val mime = stream.mimeType.substringBefore(";").ifBlank { "application/octet-stream" }
        val codecs =
            stream.codecs.ifBlank {
                stream.mimeType.substringAfter("codecs=", "").removeSurrounding("\"").ifBlank {
                    if (mime.equals("audio/flac", ignoreCase = true)) "flac" else ""
                }
            }
        val sampleRate = resolveAudioAuthenticitySampleRateHz(stream.sampleRate)
        val knownContentLength = stream.contentLength?.takeIf { it > 0L }
        val bitrate = measuredBitrate(knownContentLength, stream.matchedDurationMs) ?: 0
        val formatEntity =
            FormatEntity(
                id = mediaId,
                itag = 0,
                mimeType = mime,
                codecs = codecs,
                bitrate = bitrate,
                sampleRate = sampleRate,
                contentLength = knownContentLength ?: 0L,
                loudnessDb = null,
                perceptualLoudnessDb = null,
                playbackUrl = null,
            )
        // Update the format row with the resolved stream's codec info so the player codec badge
        // reflects what's actually playing (e.g. FLAC, not the YouTube opus row that was
        // persisted by the YouTube resolver). Preserve the authoritative itag and loudness data
        // so the details card and the normalization pipeline keep the real YouTube metadata.
        val existing =
            runBlocking(Dispatchers.IO) {
                database.getFormatsByIds(listOf(mediaId)).firstOrNull()
            }
        if (existing != null) {
            database.query {
                upsert(
                    existing.copy(
                        mimeType = mime,
                        codecs = codecs,
                        bitrate = bitrate,
                        sampleRate = sampleRate,
                        contentLength = knownContentLength ?: 0L,
                    ),
                )
            }
        } else {
            database.query { upsert(formatEntity) }
        }

        // Backfill the content length via a HEAD request if the source didn't
        // provide it. This is what makes the nerd-stats card show e.g.
        // "32.45 MB" for a FLAC track instead of "Unknown content length".
        if (knownContentLength == null) {
            ioScope.launch(SilentHandler) {
                runCatching {
                    val headRequest = okhttp3.Request.Builder()
                        .url(stream.uri)
                        .head()
                        .build()
                    mediaOkHttpClient.newCall(headRequest).execute().use { response ->
                        val len = response.header("Content-Length")?.toLongOrNull() ?: -1L
                        if (len > 0L) {
                            // Re-read the row so we don't clobber any concurrent
                            // updates to other fields (e.g. loudness) — only
                            // patch the contentLength column, plus the bitrate that
                            // can now be measured from it (the row was written with
                            // the tier estimate because the length was unknown).
                            val backfilledBitrate = measuredBitrate(len, stream.matchedDurationMs)
                            val refreshed =
                                runBlocking(Dispatchers.IO) {
                                    database.getFormatsByIds(listOf(mediaId)).firstOrNull()
                                }?.let { row ->
                                    row.copy(
                                        contentLength = len,
                                        bitrate = backfilledBitrate ?: row.bitrate,
                                    )
                                }
                            if (refreshed != null) {
                                database.query { upsert(refreshed) }
                            }
                            // Also surface the size in the in-memory cache so the
                            // download resolver picks it up immediately for the
                            // cache-first prewarm partial-cache check.
                            contentLengthCache[sourceCacheKey(stream.source, mediaId)] = len
                        }
                    }
                }.onFailure { err ->
                    Timber.tag(TAG).d(err, "HEAD request for content length failed: %s", stream.uri)
                }
            }
        }
    }

    /**
     * The stream's true average bitrate in bits/sec, derived from its byte length and playing time,
     * or null when either is unknown.
     */
    private fun measuredBitrate(
        contentLength: Long?,
        durationMs: Long?,
    ): Int? {
        val bytes = contentLength?.takeIf { it > 0L } ?: return null
        val millis = durationMs?.takeIf { it > 0L } ?: return null
        val bitsPerSecond = bytes * 8_000L / millis
        // Guard against a bogus duration producing an absurd rate; 50 Mbps is far above
        // anything a music file reaches, so treat it as "unknown" instead of showing it.
        return bitsPerSecond.takeIf { it in 1L..50_000_000L }?.toInt()
    }

    private fun sourceCacheKey(
        source: AudioSourceType,
        mediaId: String,
    ): String =
        when (source) {
            AudioSourceType.TIDAL -> "$TIDAL_CACHE_KEY_PREFIX$mediaId"
            AudioSourceType.QOBUZ -> "qobuz:$mediaId"
            else -> "${source.name.lowercase()}:$mediaId"
        }

    private fun evictAudioAuthenticityRejectedSource(mediaId: String, source: AudioSourceType) {
        directStreamCache.computeIfPresent(mediaId) { _, cached ->
            cached.takeUnless { it.stream.source == source }
        }
        if (source == AudioSourceType.YOUTUBE) {
            playbackUrlCache.remove(mediaId)
            YTPlayerUtils.invalidateCachedStreamUrls(mediaId)
            contentLengthCache.remove(mediaId)
            runCatching { playerCache.removeResource(mediaId) }
        }
        val sourceKey = sourceCacheKey(source, mediaId)
        contentLengthCache.remove(sourceKey)
        runCatching { playerCache.removeResource(sourceKey) }
        if (source == AudioSourceType.TIDAL) tidalActiveMediaIds.remove(mediaId)
    }

    private fun sourceForCacheKey(key: String, mediaId: String): AudioSourceType? {
        if (key == mediaId) return AudioSourceType.YOUTUBE
        return AudioSourceType.values().firstOrNull { sourceCacheKey(it, mediaId) == key }
    }

    /**
     * Cheap check (no network) for whether an external lossless source (Tidal) should be preferred
     * for [mediaId]. Gates the ephemeral YouTube player-cache short-circuit so enabling a
     * source takes effect immediately instead of replaying previously cached YouTube bytes.
     */
    private fun tidalSourceApplies(mediaId: String): Boolean {
        if (mediaId.isLocalMediaId()) return false
        // Defaults MUST match PlaybackSourceSections + sourceResolutionChain(). Any enabled
        // external lossless source (Tidal, Qobuz, Qobuz backup, Deezer, Apple Music) must bypass
        // the ephemeral YouTube player cache so toggling a source on takes effect immediately
        // instead of replaying cached YT bytes.
        return dataStore.get(TidalEnabledKey, true) ||
            dataStore.get(QobuzEnabledKey, false) ||
            dataStore.get(QobuzBackupEnabledKey, false) ||
            dataStore.get(DeezerEnabledKey, false) ||
            dataStore.get(AppleMusicSourceEnabledKey, false) ||
            dataStore.get(AmazonEnabledKey, false) ||
            dataStore.get(QqMusicEnabledKey, false)
    }

    private fun resolvePlaybackDataSpec(
        dataSpec: DataSpec,
        allowCacheShortCircuit: Boolean,
    ): DataSpec {
        if (dataSpec.uri.shouldBypassYouTubeResolver()) {
            return dataSpec
        }
        val mediaId = dataSpec.key ?: return dataSpec
        val lowDataModeActive = isLowDataModeActive()
        val authenticityRejectedSources = authenticityRejectedSourcesByMediaId[mediaId].orEmpty()
        val storedFormat =
            runBlocking(Dispatchers.IO) {
                database.format(mediaId).first()
            }
        storedFormat?.let { format ->
            audioNormalizationFactorCache[mediaId] = resolveAudioNormalizationFactor(mediaId, storedFormat, normalizeAudio = true)
        }
        val knownContentLength =
            contentLengthCache[mediaId] ?: storedFormat?.contentLength?.takeIf { it > 0L } ?: runCatching {
                downloadCache
                    .getContentMetadata(mediaId)
                    .get(ContentMetadata.KEY_CONTENT_LENGTH, -1L)
            }.getOrNull()?.takeIf { it > 0L } ?: runCatching {
                playerCache
                    .getContentMetadata(mediaId)
                    .get(ContentMetadata.KEY_CONTENT_LENGTH, -1L)
            }.getOrNull()?.takeIf { it > 0L }

        knownContentLength?.takeIf { it > 0L }?.let { contentLengthCache[mediaId] = it }

        // When the Tidal source is enabled for this track, the ephemeral YouTube player cache
        // must not short-circuit playback (otherwise toggling Tidal on would keep replaying the
        // previously cached YouTube bytes). Persistent downloads still win so offline playback
        // and explicit downloads are unaffected.
        val tidalApplies = !lowDataModeActive && tidalSourceApplies(mediaId)
        val allowPlayerCacheShortCircuit = !tidalApplies

        if (allowCacheShortCircuit) {
            resolveCachedDataSpec(
                dataSpec = dataSpec,
                mediaId = mediaId,
                includePlayerCache = allowPlayerCacheShortCircuit,
                youtubeSampleRateHz = storedFormat?.sampleRate,
            )?.let { cachedDataSpec ->
                Timber.tag("PlaybackDiagnostics").d(
                    "Cached source selected position=%d length=%d playerCacheAllowed=%s",
                    cachedDataSpec.position,
                    cachedDataSpec.length,
                    allowPlayerCacheShortCircuit,
                )
                scope.launch(Dispatchers.IO) { recoverSong(mediaId) }
                return cachedDataSpec
            }
        }

        val requiredCachedLength =
            if (dataSpec.length >= 0) {
                dataSpec.length
            } else {
                knownContentLength?.let { nonNullContentLength ->
                    (nonNullContentLength - dataSpec.position).takeIf { it > 0L }
                }
            }

        if (allowCacheShortCircuit && AudioSourceType.YOUTUBE !in authenticityRejectedSources && requiredCachedLength != null) {
            val isFullyCached =
                downloadCache.isCached(mediaId, dataSpec.position, requiredCachedLength) ||
                    (
                        allowPlayerCacheShortCircuit &&
                            playerCache.isCached(mediaId, dataSpec.position, requiredCachedLength)
                    )
            if (isFullyCached) {
                scope.launch(Dispatchers.IO) { recoverSong(mediaId) }
                return dataSpec
            }
        }

        // Multi-source: attempt to resolve a lossless stream (Tidal) before YouTube.
        resolveMultiSourceDataSpec(dataSpec, mediaId, lowDataModeActive)?.let { sourceDataSpec ->
            scope.launch(Dispatchers.IO) { recoverSong(mediaId) }
            return sourceDataSpec
        }

        // No lossless source matched. YouTube is the terminal fallback — and it is a toggleable
        // source like the others, so a user who turned it off gets the standard no-stream failure
        // here rather than a YouTube stream they explicitly declined. Everything below this gate
        // is YouTube resolution (cached URL short-circuit or a fresh player response).
        if (!isSourceEnabled(AudioSourceType.YOUTUBE) || AudioSourceType.YOUTUBE in authenticityRejectedSources) {
            Timber.tag("PlaybackDiagnostics").w(
                "Terminal source unavailable provider=youtube enabled=%s previouslyRejected=%s",
                isSourceEnabled(AudioSourceType.YOUTUBE),
                AudioSourceType.YOUTUBE in authenticityRejectedSources,
            )
            throw PlaybackException(
                getString(R.string.error_no_stream),
                null,
                PlaybackException.ERROR_CODE_REMOTE_ERROR,
            )
        }
        selectedPlaybackSourceByMediaId[mediaId] = AudioSourceType.YOUTUBE
        authenticityClaimedLosslessByMediaId.remove(mediaId)
        storedFormat?.sampleRate?.takeIf { it > 0 }?.let { authenticitySampleRateByMediaId[mediaId] = it }
            ?: authenticitySampleRateByMediaId.remove(mediaId)
        refreshAudioAuthenticityContexts(mediaId)
        Timber.tag("PlaybackDiagnostics").i("Source selected provider=youtube route=terminal_fallback")


        val authFingerprint = YouTube.currentPlaybackAuthState().fingerprint
        playbackUrlCache[mediaId]
            ?.takeUnless { lowDataModeActive }
            ?.takeIf {
                it.isValidFor(
                    authFingerprint = authFingerprint,
                    minimumRemainingMs = YTPlayerUtils.STREAM_URL_EXPIRY_SAFETY_MS,
                )
            }?.let {
                scope.launch(Dispatchers.IO) { recoverSong(mediaId) }
                val resolvedDataSpec = dataSpec.withUri(it.url.toUri())
                val length =
                    resolveStreamChunkLength(
                        requestedLength = dataSpec.length,
                        position = dataSpec.position,
                        knownContentLength = knownContentLength,
                        chunkLength = CHUNK_LENGTH,
                        mimeType = storedFormat?.mimeType,
                    )
                return length?.let { nonNullLength ->
                    resolvedDataSpec.subrange(0L, nonNullLength)
                } ?: resolvedDataSpec
            }

        val playbackData =
            runBlocking(Dispatchers.IO) {
                retryWithoutPlaybackLoginContext {
                    YTPlayerUtils.playerResponseForPlayback(
                        mediaId,
                        audioQuality = if (lowDataModeActive) AudioQuality.LOW else audioQuality,
                        connectivityManager = connectivityManager,
                        preferredStreamClient = preferredStreamClient,
                        networkMetered = lowDataModeActive,
                    )
                }.recoverCatching { youtubeFailure ->
                    if (youtubeFailure !is YTPlayerUtils.BotDetectionPlaybackException) throw youtubeFailure

                    Timber.tag("MusicService").w(
                        youtubeFailure,
                        "YouTube stream clients hit bot detection for %s; trying external audio fallback",
                        mediaId,
                    )
                    throw youtubeFailure
                }.recoverCatching { primaryFailure ->
                    // Last resort: the echo resolver. It parses the player script with a real
                    // JavaScript parser and regenerates the n-parameter transform, so it can still
                    // produce a URL after a player change the shipped path was not written for.
                    //
                    // Only reached once the shipped path has already failed, so the worst case is
                    // the same failure a moment later. A login or confirmation failure is skipped
                    // outright: those are actionable, echo cannot fix them, and it would only
                    // replace a message the reader can act on with one they cannot.
                    if (primaryFailure is YTPlayerUtils.InvalidPlaybackLoginContextException ||
                        primaryFailure is YTPlayerUtils.LoginRequiredForPlaybackException
                    ) {
                        throw primaryFailure
                    }
                    Timber.tag("MusicService").w(
                        primaryFailure,
                        "Shipped resolver produced no stream for %s; trying the echo resolver",
                        mediaId,
                    )
                    runCatching {
                        EchoStreamResolver.playerResponseForPlayback(
                            videoId = mediaId,
                            audioQuality = if (lowDataModeActive) AudioQuality.LOW else audioQuality,
                            connectivityManager = connectivityManager,
                        )
                    }.getOrElse { echoFailure ->
                        Timber.tag("MusicService").w(echoFailure, "Echo resolver also failed for %s", mediaId)
                        // The original failure carries the message worth showing.
                        throw primaryFailure
                    }
                }
            }.getOrElse { throwable ->
                when {
                    throwable is YTPlayerUtils.InvalidPlaybackLoginContextException -> {
                        promptLoginRecovery(mediaId, throwable.targetUrl)
                        throw PlaybackException(
                            getString(R.string.playback_requires_youtube_music_login_refresh),
                            throwable,
                            PlaybackException.ERROR_CODE_REMOTE_ERROR,
                        )
                    }

                    throwable is YTPlayerUtils.LoginRequiredForPlaybackException -> {
                        throw PlaybackException(
                            getString(R.string.playback_requires_youtube_music_confirmation),
                            throwable,
                            PlaybackException.ERROR_CODE_REMOTE_ERROR,
                        )
                    }

                    throwable is YTPlayerUtils.BotDetectionPlaybackException -> {
                        throw PlaybackException(
                            getString(R.string.error_no_stream),
                            throwable,
                            PlaybackException.ERROR_CODE_REMOTE_ERROR,
                        )
                    }

                    throwable is YTPlayerUtils.BadStreamPlayerResponseException -> {
                        throw PlaybackException(
                            getString(R.string.error_no_stream),
                            throwable,
                            PlaybackException.ERROR_CODE_REMOTE_ERROR,
                        )
                    }

                    throwable is PlaybackException -> {
                        throw throwable
                    }

                    throwable.isNetworkConnectionFailure() -> {
                        throw PlaybackException(
                            getString(R.string.error_no_internet),
                            throwable,
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                        )
                    }

                    throwable.isRequestTimeout() -> {
                        throw PlaybackException(
                            getString(R.string.error_timeout),
                            throwable,
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                        )
                    }

                    else -> {
                        throw PlaybackException(
                            getString(R.string.error_unknown),
                            throwable,
                            PlaybackException.ERROR_CODE_REMOTE_ERROR,
                        )
                    }
                }
            }

        val nonNullPlayback =
            requireNotNull(playbackData) {
                getString(R.string.error_unknown)
            }
        nonNullPlayback.playbackTracking
            ?.remotePlaybackTrackingUrl()
            ?.let { remotePlaybackTrackingUrlCache[mediaId] = it }
        val format = nonNullPlayback.format
        selectedPlaybackSourceByMediaId[mediaId] = AudioSourceType.YOUTUBE
        authenticityClaimedLosslessByMediaId.remove(mediaId)
        format.audioSampleRate?.takeIf { it > 0 }?.let { authenticitySampleRateByMediaId[mediaId] = it }
            ?: authenticitySampleRateByMediaId.remove(mediaId)
        refreshAudioAuthenticityContexts(mediaId)
        val loudnessDb = nonNullPlayback.audioConfig?.loudnessDb
        val perceptualLoudnessDb = nonNullPlayback.audioConfig?.perceptualLoudnessDb
        val resolvedContentLength = format.contentLength ?: 0L
        val resolvedCodecs = format.mimeType.codecsFromMimeType()
        resolvedContentLength.takeIf { it > 0L }?.let { contentLengthCache[mediaId] = it }

        Timber
            .tag(
                "AudioNormalization",
            ).d("Storing format for $mediaId with loudnessDb: $loudnessDb, perceptualLoudnessDb: $perceptualLoudnessDb")
        if (loudnessDb == null && perceptualLoudnessDb == null) {
            Timber.tag("AudioNormalization").w("No loudness data available from YouTube for video: $mediaId")
        }

        val formatEntity =
            FormatEntity(
                id = mediaId,
                itag = format.itag,
                mimeType = format.mimeType.split(";")[0],
                codecs = resolvedCodecs,
                bitrate = format.bitrate,
                sampleRate = format.audioSampleRate,
                contentLength = resolvedContentLength,
                loudnessDb = loudnessDb,
                perceptualLoudnessDb = perceptualLoudnessDb,
                playbackUrl = nonNullPlayback.playbackTracking?.videostatsPlaybackUrl?.baseUrl,
            )
        val resolvedNormalizationFactor = calculateAudioNormalizationFactor(formatEntity, normalizeAudio = true)
        audioNormalizationFactorCache[mediaId] = resolvedNormalizationFactor
        scope.launch {
            if (currentMediaMetadata.value?.id == mediaId &&
                dataStore.get(AudioNormalizationKey, true)
            ) {
                normalizeFactor.value = resolvedNormalizationFactor
            }
        }

        database.query {
            upsert(
                formatEntity,
            )
        }
        scope.launch(Dispatchers.IO) { recoverSong(mediaId, nonNullPlayback) }

        val streamUrl = nonNullPlayback.streamUrl

        val trackingExpiryMs = System.currentTimeMillis() + (nonNullPlayback.streamExpiresInSeconds * 1000L)

        if (!lowDataModeActive) {
            playbackUrlCache[mediaId] =
                AuthScopedCacheValue(
                    url = streamUrl,
                    expiresAtMs = trackingExpiryMs,
                    authFingerprint = nonNullPlayback.authFingerprint,
                )
        }
        val resolvedDataSpec = dataSpec.withUri(streamUrl.toUri())
        val length =
            resolveStreamChunkLength(
                requestedLength = dataSpec.length,
                position = dataSpec.position,
                knownContentLength = format.contentLength,
                chunkLength = CHUNK_LENGTH,
                mimeType = format.mimeType,
            )
        return length?.let { nonNullLength ->
            resolvedDataSpec.subrange(0L, nonNullLength)
        } ?: resolvedDataSpec
    }



    private fun resolveCachedDataSpec(
        dataSpec: DataSpec,
        mediaId: String,
        includePlayerCache: Boolean = true,
        youtubeSampleRateHz: Int? = null,
    ): DataSpec? {
        // Find the first key (every download source's prefix, then the bare mediaId, as
        // DownloadSourceConfig.cacheKeysFor orders them) that has the requested byte range fully
        // cached. Returning the DataSpec with the matching key is critical — without it the
        // CacheDataSource would look up the bytes under the bare mediaId and miss the lossless
        // FLAC bytes cached under "qobuz:$mediaId" / "tidal:$mediaId", then fall through to the
        // YouTube resolver and serve a lossy MP3 stream — producing Code 3003 when the Media3
        // extractors then tried to read MP3 bytes under a FLAC FormatEntity. The hardcoded list
        // this replaces skipped qobuz_backup: and jiosaavn:, so those downloads never played
        // offline.
        val candidateKeys = DownloadSourceConfig.cacheKeysFor(mediaId)
        val authenticityRejectedSources = authenticityRejectedSourcesByMediaId[mediaId].orEmpty()
        val (matchingKey, matchingWindow) =
            candidateKeys.firstNotNullOfOrNull { key ->
                val cacheSource = sourceForCacheKey(key, mediaId)
                if (cacheSource != null && cacheSource in authenticityRejectedSources) {
                    return@firstNotNullOfOrNull null
                }
                val recordedLength = recordedContentLength(key, includePlayerCache)
                val keyWindow =
                    resolveCachedReadWindow(
                        position = dataSpec.position,
                        requestedLength = dataSpec.length,
                        knownContentLength = if (dataSpec.length >= 0L) null else recordedLength,
                    ) ?: return@firstNotNullOfOrNull null
                val cachedLength =
                    getContinuousCachedLengthForKey(
                        key = key,
                        position = keyWindow.position,
                        requestedLength = keyWindow.length,
                        includePlayerCache = includePlayerCache,
                    )
                if (cachedLength >= keyWindow.length) key to keyWindow else null
            } ?: return null

        sourceForCacheKey(matchingKey, mediaId)?.let { source ->
            selectedPlaybackSourceByMediaId[mediaId] = source
            val caches = if (includePlayerCache) listOf(downloadCache, playerCache) else listOf(downloadCache)
            val metadata = caches.firstNotNullOfOrNull { cache ->
                CachedAudioMetadata.read(cache.getContentMetadata(matchingKey), source)
            }
            if (metadata != null) retainReportedAudioFormat(mediaId, metadata) else reportedAudioFormatsByMediaId.remove(mediaId)
            authenticityClaimedLosslessByMediaId[mediaId] = source != AudioSourceType.YOUTUBE && metadata?.claimedLossless == true
            val reportedRate = if (source == AudioSourceType.YOUTUBE) youtubeSampleRateHz else metadata?.sampleRateHz
            resolveAudioAuthenticitySampleRateHz(reportedRate)?.let { authenticitySampleRateByMediaId[mediaId] = it }
                ?: authenticitySampleRateByMediaId.remove(mediaId)
            refreshAudioAuthenticityContexts(mediaId)
        }

        return dataSpec.buildUpon()
            .setKey(matchingKey)
            .setPosition(matchingWindow.position)
            .setLength(matchingWindow.length)
            .build()
    }

    private fun recordCachedAudioMetadata(cacheKey: String, stream: DirectStream) {
        val mutations = CachedAudioMetadata.from(stream).toMutations()
        runCatching {
            listOf(downloadCache, playerCache).forEach { cache ->
                if (cache.getCachedSpans(cacheKey).isEmpty()) {
                    cache.applyContentMetadataMutations(cacheKey, mutations)
                }
            }
        }.onFailure { error ->
            error.rethrowIfAudioSourceCancelled()
            Timber.tag("MusicService").w("Audio cache metadata could not be retained: %s", error.javaClass.simpleName)
        }
    }

    private fun recordedContentLength(
        key: String,
        includePlayerCache: Boolean,
    ): Long? {
        val caches = if (includePlayerCache) listOf(downloadCache, playerCache) else listOf(downloadCache)
        return caches
            .mapNotNull { cache ->
                runCatching { cache.getContentMetadata(key).get(ContentMetadata.KEY_CONTENT_LENGTH, -1L) }
                    .getOrNull()
                    ?.takeIf { it > 0L }
            }.maxOrNull()
    }

    /**
     * Same as [getContinuousCachedLength] but for a specific cache key.
     * Used by [resolveCachedDataSpec] to find the source-prefixed key
     * whose cache spans fully cover the requested range.
     */
    private fun getContinuousCachedLengthForKey(
        key: String,
        position: Long,
        requestedLength: Long,
        includePlayerCache: Boolean = true,
    ): Long {
        val playerCacheSpans =
            if (includePlayerCache) cachedSpansOf(playerCache, key) else emptyList()
        return continuousCachedLength(
            spans = cachedSpansOf(downloadCache, key) + playerCacheSpans,
            position = position,
            requestedLength = requestedLength,
        )
    }

    /**
     * [Cache.getCachedSpans] as coverage windows. Holes ride along with `isCached = false` so
     * [continuousCachedLength] can drop them: a range the index knows is missing must never be
     * offered to ExoPlayer as cached bytes.
     */
    private fun cachedSpansOf(cache: Cache, key: String): List<CachedSpan> =
        runCatching { cache.getCachedSpans(key).toList() }
            .getOrNull()
            .orEmpty()
            .map { span ->
                CachedSpan(
                    position = span.position,
                    length = span.length,
                    isCached = span.isCached,
                )
            }


    private fun Uri.shouldBypassYouTubeResolver(): Boolean {
        val normalizedScheme = scheme?.lowercase(Locale.US)
        return normalizedScheme == "content" ||
            normalizedScheme == "file" ||
            normalizedScheme == "android.resource" ||
            normalizedScheme == "telegram" ||
            normalizedScheme == DeezerCrypto.SCHEME ||
            normalizedScheme == TidalAudioProvider.PROGRESSIVE_DASH_SCHEME ||
            normalizedScheme == "http" ||
            normalizedScheme == "https"
    }

    private fun Uri.shouldBypassPlayerCache(): Boolean {
        val normalizedScheme = scheme?.lowercase(Locale.US)
        return normalizedScheme == "content" ||
            normalizedScheme == "file" ||
            normalizedScheme == "android.resource" ||
            normalizedScheme == "telegram"
    }

    private fun createMediaSourceFactory() =
        DefaultMediaSourceFactory(
            createDataSourceFactory(),
            DefaultExtractorsFactory()
                // Enable constant-bitrate seeking for CBR streams (most MP3/AAC files) so
                // the user can seek to any position without needing a seek table. Without
                // this, ExoPlayer can only seek to keyframes, making seeking in .m4a/.mp3
                // files imprecise or impossible.
                .setConstantBitrateSeekingEnabled(true),
        )
            // Apple Music full-track streams are CENC-encrypted fMP4; the Widevine L3
            // session (license exchanged against Apple's acquireWebPlaybackLicense) is
            // attached only for media ids resolved through the Apple source — everything
            // else stays DRM-free with the default behavior.
            .setDrmSessionManagerProvider { mediaItem ->
                val appleTrack = mediaItem.mediaId?.let { appleDrmTrackInfo[it] }
                when {
                    appleTrack != null -> buildAppleDrmSessionManager(appleTrack) ?: DrmSessionManager.DRM_UNSUPPORTED
                    else -> DrmSessionManager.DRM_UNSUPPORTED
                }
            }

    /**
     * Media ids resolved through the Apple Music source, with the per-track values Apple's
     * license exchange needs (adamId + the playlist's raw EXT-X-KEY `uri`). Registered when
     * the resolver returns an Apple stream so the DRM provider knows which media items need
     * the Widevine session. Entries stay for the process lifetime (tiny; ids only).
     */
    private class AppleTrackDrmInfo(
        val adamId: String,
        val drmUri: String,
    )

    private val appleDrmTrackInfo: MutableMap<String, AppleTrackDrmInfo> = ConcurrentHashMap()

    private fun buildAppleDrmSessionManager(track: AppleTrackDrmInfo): DrmSessionManager? {
        val mediaToken = AppleMusicAudioProvider.mediaUserToken() ?: return null
        val devToken = AppleMusicAudioProvider.usableDevToken()
        val callback = AppleLicenseCallback(track, devToken, mediaToken)
        return DefaultDrmSessionManager
            .Builder()
            .setUuidAndExoMediaDrmProvider(
                C.WIDEVINE_UUID,
                ExoMediaDrm.Provider { uuid ->
                    // FrameworkMediaDrm's constructor is private — use newInstance and force
                    // software-level L3: Apple's license server issues keys for the web
                    // playback pipeline, which is L3-shaped. On failure fall back to the
                    // default provider (handles unsupported-scheme gracefully).
                    val created =
                        runCatching { FrameworkMediaDrm.newInstance(uuid) }.getOrNull()?.apply {
                            // The exact property value differs across vendors; try both and
                            // ignore failures — worst case the device default level is used.
                            runCatching { setPropertyString("securityLevel", "L3") }
                                .recoverCatching { setPropertyString("securityLevel", "3") }
                        }
                    created ?: FrameworkMediaDrm.DEFAULT_PROVIDER.acquireExoMediaDrm(uuid)
                },
            )
            .build(callback)
    }

    /**
     * Speaks Apple's license-exchange protocol (verified against gamdl): the Widevine challenge
     * travels BASE64 inside a JSON envelope — `{"challenge", "key-system", "uri", "adamId",
     * "isLibrary", "user-initiated"}` — and the response is JSON whose `license` field carries the
     * base64 license bytes.
     */
    private inner class AppleLicenseCallback(
        private val track: AppleTrackDrmInfo,
        private val devToken: String?,
        private val mediaToken: String,
    ) : MediaDrmCallback {
        // Provisioning uses Widevine's normal server, not Apple's.
        private val provisionFallback = HttpMediaDrmCallback(null, OkHttpDataSource.Factory(mediaOkHttpClient))

        private fun fail(message: String): Nothing {
            Timber.tag("MusicService").w(message)
            val uri = android.net.Uri.parse(APPLE_LICENSE_URL)
            throw MediaDrmCallbackException(
                DataSpec(uri),
                uri,
                emptyMap(),
                0L,
                java.io.IOException(message),
            )
        }

        override fun executeProvisionRequest(
            uuid: java.util.UUID,
            request: ExoMediaDrm.ProvisionRequest,
        ): MediaDrmCallback.Response = provisionFallback.executeProvisionRequest(uuid, request)

        override fun executeKeyRequest(
            uuid: java.util.UUID,
            request: ExoMediaDrm.KeyRequest,
        ): MediaDrmCallback.Response {
            val body =
                JSONObject()
                    .put("challenge", android.util.Base64.encodeToString(request.data, android.util.Base64.NO_WRAP))
                    .put("key-system", "com.widevine.alpha")
                    .put("uri", track.drmUri)
                    .put("adamId", track.adamId)
                    .put("isLibrary", false)
                    .put("user-initiated", true)
            val builder =
                Request
                    .Builder()
                    .url(APPLE_LICENSE_URL)
                    .header("Content-Type", "application/json")
                    .header("Origin", "https://music.apple.com")
                    .header("Referer", "https://music.apple.com/")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36")
                    .post(body.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            devToken?.let { builder.header("Authorization", "Bearer $it") }
            builder.header("Media-User-Token", mediaToken)
            mediaOkHttpClient.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    fail("Apple license exchange failed: HTTP ${response.code} ${text.take(200)}")
                }
                val json =
                    runCatching { JSONObject(text) }.getOrElse {
                        fail("Apple license response is not JSON: ${text.take(200)}")
                    }
                val status = json.optInt("status", -1)
                if (status != 0) {
                    val customer = json.optString("customerMessage").ifBlank { text.take(200) }
                    fail("Apple license exchange rejected (status=$status): $customer")
                }
                val license = json.optString("license")
                if (license.isBlank()) fail("Apple license response has no license field")
                val decoded =
                    runCatching { android.util.Base64.decode(license, android.util.Base64.DEFAULT) }.getOrElse {
                        fail("Apple license base64 decode failed: ${it.message}")
                    }
                return MediaDrmCallback.Response(decoded)
            }
        }
    }

    private class SchemeRoutingDataSource(
        private val cachedFactory: DataSource.Factory,
        private val directFactory: DataSource.Factory,
        private val telegramFactory: DataSource.Factory,
        private val deezerFactory: DataSource.Factory,
        private val tidalProgressiveDashFactory: DataSource.Factory,
    ) : DataSource {
        private val transferListeners = mutableListOf<TransferListener>()
        private var delegate: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            transferListeners += transferListener
            delegate?.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val normalizedScheme = dataSpec.uri.scheme?.lowercase(Locale.US)
            val selectedFactory =
                if (normalizedScheme == "telegram") {
                    // Telegram tracks stream through TDLib's own partial-file cache; Media3's
                    // caches and the YouTube resolver chain must both stay out of the way.
                    telegramFactory
                } else if (normalizedScheme == DeezerCrypto.SCHEME) {
                    // Carries its own cache; the YouTube resolver would rewrite the URI.
                    deezerFactory
                } else if (normalizedScheme == TidalAudioProvider.PROGRESSIVE_DASH_SCHEME) {
                    // Streams through its own progressive-DASH source; the YouTube resolver
                    // would rewrite the URI.
                    tidalProgressiveDashFactory
                } else if (
                    normalizedScheme == "content" ||
                    normalizedScheme == "file" ||
                    normalizedScheme == "android.resource"
                ) {
                    directFactory
                } else {
                    cachedFactory
                }
            val selectedDataSource = selectedFactory.createDataSource()
            transferListeners.forEach(selectedDataSource::addTransferListener)
            delegate = selectedDataSource
            return selectedDataSource.open(dataSpec)
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int = checkNotNull(delegate).read(buffer, offset, length)

        override fun getUri(): Uri? = delegate?.uri

        override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders ?: emptyMap()

        override fun close() {
            delegate?.close()
            delegate = null
        }
    }

    /** Routes provider URIs after the resolving/cache layers have already selected a stream. */
    private class ResolvedSchemeRoutingDataSource(
        private val defaultFactory: DataSource.Factory,
        private val deezerFactory: DataSource.Factory,
        private val tidalProgressiveDashFactory: DataSource.Factory,
    ) : DataSource {
        private val transferListeners = mutableListOf<TransferListener>()
        private var delegate: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            transferListeners += transferListener
            delegate?.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val scheme = dataSpec.uri.scheme?.lowercase(Locale.US)
            val factory =
                when (scheme) {
                    DeezerCrypto.SCHEME -> deezerFactory
                    TidalAudioProvider.PROGRESSIVE_DASH_SCHEME -> tidalProgressiveDashFactory
                    else -> defaultFactory
                }
            val selected = factory.createDataSource()
            transferListeners.forEach(selected::addTransferListener)
            delegate = selected
            return selected.open(dataSpec)
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int = checkNotNull(delegate).read(buffer, offset, length)

        override fun getUri(): Uri? = delegate?.uri

        override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders ?: emptyMap()

        override fun close() {
            delegate?.close()
            delegate = null
        }
    }

    private fun updateAudioOffload(enabled: Boolean) {
        val effectiveEnabled = enabled && !crossfadeEnabled
        runCatching {
            val builder = localPlayer.trackSelectionParameters.buildUpon()
            val audioOffloadPrefsClass = Class.forName("androidx.media3.common.AudioOffloadPreferences")
            val audioOffloadPrefsBuilderClass = Class.forName("androidx.media3.common.AudioOffloadPreferences\$Builder")

            val modeFieldName = if (effectiveEnabled) "AUDIO_OFFLOAD_MODE_ENABLED" else "AUDIO_OFFLOAD_MODE_DISABLED"
            val mode = audioOffloadPrefsClass.getField(modeFieldName).getInt(null)

            val prefsBuilder = audioOffloadPrefsBuilderClass.getDeclaredConstructor().newInstance()
            audioOffloadPrefsBuilderClass.getMethod("setAudioOffloadMode", Int::class.javaPrimitiveType).invoke(prefsBuilder, mode)
            val prefs = audioOffloadPrefsBuilderClass.getMethod("build").invoke(prefsBuilder)

            val setMethod =
                builder.javaClass.methods.firstOrNull { method ->
                    method.name == "setAudioOffloadPreferences" && method.parameterTypes.size == 1
                }
            if (setMethod != null) {
                setMethod.invoke(builder, prefs)
                localPlayer.trackSelectionParameters = builder.build()
            }
        }
        localPlayer.setOffloadEnabled(effectiveEnabled)
    }

    private fun updateWakeLock() {
        val wl = wakeLock ?: return
        val shouldHold = wakelockEnabled && player.isPlaying
        if (shouldHold && !wl.isHeld) {
            Timber.tag("PlaybackDiagnostics").d(
                "Playback wake lock acquired state=%d playWhenReady=%s isPlaying=%s focusHeld=%s gain=%.3f",
                player.playbackState,
                player.playWhenReady,
                player.isPlaying,
                hasAudioFocus,
                player.volume,
            )
            wl.acquire()
        } else if (!shouldHold && wl.isHeld) {
            Timber.tag("PlaybackDiagnostics").d(
                "Playback wake lock released state=%d playWhenReady=%s isPlaying=%s focusHeld=%s gain=%.3f",
                player.playbackState,
                player.playWhenReady,
                player.isPlaying,
                hasAudioFocus,
                player.volume,
            )
            wl.release()
        }
    }

    private fun createPrimaryLoadControl(): DefaultLoadControl {
        val lean = useConservativePlaybackProfile()
        return DefaultLoadControl
            .Builder()
            .setBufferDurationsMs(
                if (lean) CONSERVATIVE_MIN_BUFFER_MS else PRIMARY_MIN_BUFFER_MS,
                if (lean) CONSERVATIVE_MAX_BUFFER_MS else PRIMARY_MAX_BUFFER_MS,
                if (lean) CONSERVATIVE_BUFFER_FOR_PLAYBACK_MS else PRIMARY_BUFFER_FOR_PLAYBACK_MS,
                if (lean) CONSERVATIVE_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS else PRIMARY_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            ).setPrioritizeTimeOverSizeThresholds(true)
            .build()
    }

    private fun createCrossfadeLoadControl(): DefaultLoadControl {
        val lean = useConservativePlaybackProfile()
        val bufferBeforeStart =
            if (lean) CONSERVATIVE_CROSSFADE_BUFFER_BEFORE_START_MS else CROSSFADE_MIN_BUFFER_BEFORE_START_MS.toInt()
        return DefaultLoadControl
            .Builder()
            .setBufferDurationsMs(
                if (lean) CONSERVATIVE_CROSSFADE_MIN_BUFFER_MS else CROSSFADE_MIN_BUFFER_MS,
                if (lean) CONSERVATIVE_CROSSFADE_MAX_BUFFER_MS else CROSSFADE_MAX_BUFFER_MS,
                bufferBeforeStart,
                bufferBeforeStart,
            ).setPrioritizeTimeOverSizeThresholds(true)
            .build()
    }

    /**
     * Low-RAM devices and battery saver get shorter buffers: the default 60s ceiling is a lot of
     * heap to hold on a 1GB device, and a saver-mode radio wakes less often than the prefetch
     * assumes. Sampled once per player build, so toggling saver mid-track takes effect on the next
     * player.
     */
    private fun useConservativePlaybackProfile(): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        return activityManager?.isLowRamDevice == true || powerManager?.isPowerSaveMode == true
    }

    private fun audioAuthenticityContextFor(mediaId: String): AudioAuthenticityPlaybackContext =
        AudioAuthenticityPlaybackContext(
            playbackKey = mediaId,
            sourceId = selectedPlaybackSourceByMediaId[mediaId]?.name.orEmpty(),
            rejectSuspectedUpscaled =
                resolveAudioAuthenticityProbeAvailability(
                    rejectionEnabled = rejectSuspectedUpscaledAudioEnabled,
                    pcmTapAvailable = !bitPerfectOutputActive,
                ) == AudioAuthenticityProbeAvailability.AVAILABLE,
            advertisedSampleRateHz = authenticitySampleRateByMediaId[mediaId],
            claimedLossless = authenticityClaimedLosslessByMediaId[mediaId] == true,
        )

    private fun refreshAudioAuthenticityContexts(mediaId: String) {
        val updatedContext = audioAuthenticityContextFor(mediaId)
        audioAuthenticityContextByPlayer.values.forEach { contextRef ->
            if (contextRef.get()?.playbackKey == mediaId) contextRef.set(updatedContext)
        }
        scope.launch(Dispatchers.Main) { publishDecodedPcmFormat() }
    }

    private fun setAudioAuthenticityContext(player: ExoPlayer, mediaId: String?) {
        val contextRef = audioAuthenticityContextByPlayer[player] ?: return
        contextRef.set(mediaId?.let(::audioAuthenticityContextFor))
        scope.launch(Dispatchers.Main) { publishDecodedPcmFormat() }
    }

    private fun publishDecodedPcmFormat() {
        if (!::player.isInitialized) {
            decodedPcmFormat.value = null
            reportedAudioFormat.value = null
            androidAudioOutputFormat.value = null
            return
        }
        val mediaId =
            localAudioObservationMediaId(
                sessionMediaId = player.currentMediaItem?.mediaId,
                localMediaId = localPlayer.currentMediaItem?.mediaId,
                playbackType = player.deviceInfo.playbackType,
            )
        val activePlayer = localPlayer.takeIf { mediaId != null }
        val context = activePlayer?.let(audioAuthenticityContextByPlayer::get)
        decodedPcmFormat.value =
            if (bitPerfectOutputActive) null else context?.let(decodedPcmFormatsByContext::get)?.takeIf { it.mediaId == mediaId }
        reportedAudioFormat.value =
            reportedAudioFormatForPlayback(
                mediaId = mediaId,
                source = mediaId?.let(selectedPlaybackSourceByMediaId::get),
                format = mediaId?.let(reportedAudioFormatsByMediaId::get),
            )
        androidAudioOutputFormat.value =
            androidAudioOutputFormatForPlayback(
                mediaId = mediaId,
                configuration = activePlayer?.let(audioTrackConfigurationsByPlayer::get),
                bitPerfectOutputActive = bitPerfectOutputActive,
            )
    }

    private fun removeAudioAuthenticityContext(releasedPlayer: ExoPlayer) {
        audioAuthenticityContextByPlayer.remove(releasedPlayer)?.let(decodedPcmFormatsByContext::remove)
        audioTrackConfigurationsByPlayer.remove(releasedPlayer)
        scope.launch(Dispatchers.Main) { publishDecodedPcmFormat() }
    }

    private fun retainReportedAudioFormat(mediaId: String, metadata: CachedAudioMetadata) {
        reportedAudioFormatsByMediaId.put(
            mediaId,
            ReportedAudioFormat(mediaId, metadata.source, metadata.sampleRateHz, metadata.bitDepth, metadata.channelCount),
        )
        scope.launch(Dispatchers.Main) { publishDecodedPcmFormat() }
    }

    private fun observeAudioTrackConfiguration(observedPlayer: ExoPlayer) {
        observedPlayer.addAnalyticsListener(
            object : androidx.media3.exoplayer.analytics.AnalyticsListener {
                override fun onAudioInputFormatChanged(
                    eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                    format: androidx.media3.common.Format,
                    decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
                ) {
                    val periodId = eventTime.mediaPeriodId
                    if (periodId != null && observedPlayer.currentTimeline.getIndexOfPeriod(periodId.periodUid) == C.INDEX_UNSET) return
                    val mediaId =
                        if (eventTime.windowIndex in 0 until eventTime.timeline.windowCount) {
                            eventTime.timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem.mediaId
                        } else {
                            observedPlayer.currentMediaItem?.mediaId ?: return
                        }
                    val source = selectedPlaybackSourceByMediaId[mediaId]
                    val rate = format.sampleRate.takeIf { it > 0 }
                    val lossless = isClaimedLosslessFormat(format.sampleMimeType.orEmpty(), format.codecs.orEmpty(), "")
                    val depth =
                        if (lossless) DecodedPcmFormat(mediaId, format.sampleRate, format.channelCount, format.pcmEncoding).bitsPerSample else null
                    val previous = reportedAudioFormatsByMediaId[mediaId]?.takeIf { it.source == source }
                    reportedAudioFormatsByMediaId.put(
                        mediaId,
                        ReportedAudioFormat(mediaId, source, rate ?: previous?.sampleRateHz, depth ?: previous?.bitDepth, format.channelCount.takeIf { it > 0 }),
                    )
                    if (source != null && source != AudioSourceType.YOUTUBE && rate != null) {
                        authenticitySampleRateByMediaId[mediaId] = rate
                        val claimedLossless = authenticityClaimedLosslessByMediaId[mediaId] == true || lossless
                        authenticityClaimedLosslessByMediaId[mediaId] = claimedLossless
                        refreshAudioAuthenticityContexts(mediaId)
                        val metadata = CachedAudioMetadata(source, rate, claimedLossless, depth ?: previous?.bitDepth, format.channelCount.takeIf { it > 0 })
                        scope.launch(Dispatchers.IO) {
                            val key = sourceCacheKey(source, mediaId)
                            runCatching {
                                listOf(downloadCache, playerCache).forEach { cache ->
                                    cache.applyContentMetadataMutations(key, metadata.toMutations())
                                }
                            }.onFailure { it.rethrowIfAudioSourceCancelled() }
                        }
                    }
                    publishDecodedPcmFormat()
                }

                override fun onAudioTrackInitialized(
                    eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                    audioTrackConfig: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig,
                ) {
                    audioTrackConfigurationsByPlayer[observedPlayer] = audioTrackConfig
                    publishDecodedPcmFormat()
                }

                override fun onAudioTrackReleased(
                    eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                    audioTrackConfig: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig,
                ) {
                    audioTrackConfigurationsByPlayer.remove(observedPlayer, audioTrackConfig)
                    publishDecodedPcmFormat()
                }
            },
        )
    }

    private fun handleAudioAuthenticityResult(
        contextRef: AtomicReference<AudioAuthenticityPlaybackContext?>,
        result: AudioAuthenticityProcessorResult,
    ) {
        scope.launch {
            val owner =
                audioAuthenticityContextByPlayer.entries
                    .firstOrNull { it.value === contextRef }
                    ?.key ?: return@launch
            val activeContext = contextRef.get()
            if (activeContext?.playbackKey != result.playbackKey || activeContext.sourceId != result.sourceId) return@launch
            if (result.shouldReject && !activeContext.rejectSuspectedUpscaled) return@launch
            if (owner.currentMediaItem?.mediaId != result.playbackKey) return@launch
            val source = runCatching { AudioSourceType.valueOf(result.sourceId) }.getOrNull() ?: return@launch
            if (selectedPlaybackSourceByMediaId[result.playbackKey] != source) return@launch
            Timber.tag("PlaybackDiagnostics").i(
                "Audio authenticity verdict=%s provider=%s confidence=%.3f cutoffHz=%s activeWindows=%d rejected=%s",
                result.assessment.verdict.name,
                source.name,
                result.assessment.confidence,
                result.assessment.cutoffFrequencyHz,
                result.assessment.activeWindows,
                result.shouldReject,
            )
            if (!result.shouldReject) return@launch

            val isSecondaryPlayer = owner === secondaryCrossfadePlayer
            if (isSecondaryPlayer && secondaryCrossfadeTarget?.mediaId != result.playbackKey) return@launch
            if (!isSecondaryPlayer && (owner !== localPlayer || player.currentMediaItem?.mediaId != result.playbackKey)) {
                return@launch
            }
            val rejectedSources =
                authenticityRejectedSourcesByMediaId.getOrPut(result.playbackKey) {
                    ConcurrentHashMap.newKeySet()
                }
            if (!rejectedSources.add(source)) return@launch

            selectedPlaybackSourceByMediaId.remove(result.playbackKey, source)
            authenticitySampleRateByMediaId.remove(result.playbackKey)
            authenticityClaimedLosslessByMediaId.remove(result.playbackKey)
            refreshAudioAuthenticityContexts(result.playbackKey)
            Timber.tag("PlaybackDiagnostics").w(
                "Rejected suspected upscaled audio provider=%s confidence=%.3f; retrying another source",
                source.name,
                result.assessment.confidence,
            )

            if (isSecondaryPlayer) {
                evictAudioAuthenticityRejectedSource(result.playbackKey, source)
                cancelCrossfade(resetVolume = true, resetPauseAtEnd = true)
                scheduleCrossfade()
                return@launch
            }

            val sourceOverride =
                SongSourceOverride.get(dataStore.get(SongSourceOverrideKey, ""), result.playbackKey)
            val qobuzTrackId =
                SongSourceQobuzTrackId.get(dataStore.get(SongSourceQobuzTrackIdKey, ""), result.playbackKey)
            val qobuzBackupVideoId =
                SongSourceQobuzBackupVideoId.get(dataStore.get(SongSourceQobuzBackupVideoIdKey, ""), result.playbackKey)
            setSongSourceOverrideInternal(
                mediaId = result.playbackKey,
                source = sourceOverride,
                qobuzTrackId = qobuzTrackId,
                qobuzBackupVideoId = qobuzBackupVideoId,
                authenticityRejectedSource = source,
                preservedPositionMs = resolveAudioAuthenticityRetryPositionMs(player.currentPosition),
            )
        }
    }

    private fun createRenderersFactory(
        transitionFilter: TransitionFilterProcessor,
        authenticityContext: AtomicReference<AudioAuthenticityPlaybackContext?>,
    ) =
        object : DefaultRenderersFactory(this) {
            init {
                // Enable decoder fallback so that when a primary (typically hardware) decoder fails
                // — e.g. MediaTek's c2.mtk.alac.decoder on .m4a/ALAC files (error 0x80000000) —
                // ExoPlayer can fall back to any alternative decoder advertised by the platform,
                // including any extension software decoder that may be registered.
                // Also opt in to extension renderers as a fallback tier (EXTENSION_RENDERER_MODE_ON):
                // extension decoders come AFTER the platform MediaCodec ones, so default hardware
                // acceleration is preserved but a software fallback becomes reachable.
                setEnableDecoderFallback(true)
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            }

            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): DefaultAudioSink {
                if (bitPerfectOutputActive) {
                    // Bit-perfect USB: decoders hand over float (exact for 16/24-bit sources),
                    // the float path skips every processor in the chain, and the provider
                    // negotiates the DAC's native container per AudioTrack. No speed, silence
                    // skipping, haptics or transition filtering can touch the samples here.
                    return DefaultAudioSink
                        .Builder(context)
                        .setEnableFloatOutput(true)
                        .setEnableAudioTrackPlaybackParams(false)
                        .setAudioOutputProvider(
                            BitPerfectAudioOutputProvider(
                                context,
                                AudioTrackAudioOutputProvider.Builder(context).build(),
                            ),
                        ).build()
                }
                return buildStandardAudioSink(context, enableAudioTrackPlaybackParams)
            }

            private fun buildStandardAudioSink(
                context: Context,
                enableAudioTrackPlaybackParams: Boolean,
            ) = DefaultAudioSink
                .Builder(context)
                .setEnableFloatOutput(false)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessorChain(
                    DefaultAudioSink.DefaultAudioProcessorChain(
                        SonicAudioProcessor(),
                        // Analyses the decoded PCM for the haptics engine and passes it through
                        // untouched. A fresh instance per sink — an AudioProcessor may belong to
                        // only one chain.
                        HapticsPcmProcessor(engineProvider = { musicHapticsEngine }),
                        AudioAuthenticityPcmProcessor(
                            playbackContextProvider = { authenticityContext.get() },
                                resultListener = { result -> handleAudioAuthenticityResult(authenticityContext, result) },
                            ),
                            DecodedPcmObserver(
                                onFormatObserved = { format ->
                                    val mediaId = authenticityContext.get()?.playbackKey
                                    if (format == null || mediaId == null || format.sampleRate <= 0 || format.channelCount <= 0) {
                                        decodedPcmFormatsByContext.remove(authenticityContext)
                                    } else {
                                        decodedPcmFormatsByContext[authenticityContext] =
                                            DecodedPcmFormat(mediaId, format.sampleRate, format.channelCount, format.encoding)
                                    }
                                    scope.launch(Dispatchers.Main) { publishDecodedPcmFormat() }
                                },
                                playbackKeyProvider = { authenticityContext.get()?.playbackKey },
                            ),
                        // Automix's transition filters ride last in the chain, as in 4nx3b and
                        // BitChord. Wide open (a pass-through) whenever no smart blend runs.
                        transitionFilter,
                    ),
                ).build()
        }

    override fun onPlaybackStatsReady(
        eventTime: AnalyticsListener.EventTime,
        playbackStats: PlaybackStats,
    ) {
        val mediaItem = eventTime.timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem
        val mediaId = mediaItem.mediaId
        // Episodes must not feed song stats: no history event, play-time, count,
        // or remote registration. Matches the maybeRecord guard above.
        if ((mediaItem.localConfiguration?.tag as? moe.rukamori.archivetune.models.MediaMetadata)?.isPodcast == true) return
        val thresholdMs = historyThresholdMs()
        val pendingSession = popPendingHistoryFinalization(mediaId)
        val alreadyPersistedForSession = pendingSession?.eventId != null || pendingSession?.remoteRegistered == true
        val reachedHistoryThreshold =
            playbackStats.totalPlayTimeMs >= thresholdMs &&
                !dataStore.get(PauseListenHistoryKey, false)
        val shouldPersistHistory = alreadyPersistedForSession || reachedHistoryThreshold

        if (shouldPersistHistory) {
            ioScope.launch {
                val pendingResult =
                    pendingSession?.let { session ->
                        historyRecordingJobs[session.sessionToken]
                            ?.let { deferred ->
                                runCatching { deferred.await() }
                                    .onFailure(::reportException)
                                    .getOrNull()
                            }?.let { result ->
                                session.copy(
                                    eventId = result.eventId ?: session.eventId,
                                    remoteRegistered = session.remoteRegistered || result.remoteRegistered,
                                )
                            }
                            ?: session
                    }

                val fallbackMetadata = mediaItem.metadata
                val eventId =
                    pendingResult?.eventId ?: insertPlaybackHistoryEvent(
                        mediaId = mediaId,
                        playTimeMs = playbackStats.totalPlayTimeMs,
                        mediaMetadata = fallbackMetadata,
                    )

                if (eventId != null) {
                    runCatching {
                        database.updateEventPlayTime(eventId, playbackStats.totalPlayTimeMs)
                    }.onFailure(::reportException)
                }

                try {
                    database.withTransaction {
                        incrementTotalPlayTime(mediaId, playbackStats.totalPlayTimeMs)
                    }
                } catch (_: SQLException) {
                } catch (throwable: Throwable) {
                    reportException(throwable)
                }

                if (pendingResult?.remoteRegistered != true) {
                    registerRemotePlaybackHistory(mediaId)
                }
            }

            ioScope.launch {
                try {
                    val song =
                        database.song(mediaId).first()
                            ?: return@launch

                    val lbEnabled = dataStore.get(ListenBrainzEnabledKey, false)
                    val lbToken = dataStore.get(ListenBrainzTokenKey, "")
                    if (lbEnabled && !lbToken.isNullOrBlank()) {
                        val endMs = System.currentTimeMillis()
                        val startMs = endMs - playbackStats.totalPlayTimeMs
                        try {
                            ListenBrainzManager.submitFinished(this@MusicService, lbToken, song, startMs, endMs)
                        } catch (ie: Exception) {
                            Timber.tag("MusicService").v(ie, "ListenBrainz finished submit failed")
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun currentPresenceSong(): Song? =
        resolvePresenceSong(
            dbSong = currentSong.value,
            mediaMetadata = player.currentMetadata,
            durationMs = player.duration,
        )

    private fun resolvePresenceSong(
        dbSong: Song?,
        mediaMetadata: MediaMetadata?,
        durationMs: Long,
    ): Song? {
        val metadataSong = mediaMetadata?.let { createTransientSongFromMedia(it) }
        val song =
            when {
                dbSong == null -> metadataSong
                metadataSong == null -> dbSong
                else -> dbSong.withPresenceMetadata(metadataSong)
            }

        return song.withResolvedPresenceDuration(durationMs)
    }

    private fun Song.withPresenceMetadata(metadataSong: Song): Song {
        val resolvedArtists =
            metadataSong.artists.takeIf { metadataArtists ->
                metadataArtists.any { it.hasRemotePresenceId() }
            } ?: artists

        return copy(
            song =
                song.copy(
                    thumbnailUrl = song.thumbnailUrl ?: metadataSong.song.thumbnailUrl,
                    albumId = song.albumId ?: metadataSong.song.albumId,
                    albumName = song.albumName ?: metadataSong.song.albumName,
                ),
            artists = resolvedArtists,
            album = album ?: metadataSong.album,
        )
    }

    private fun Song?.withResolvedPresenceDuration(durationMs: Long): Song? {
        val song = this ?: return null
        if (song.song.duration > 0 || durationMs <= 0) return song
        val durationSeconds =
            (durationMs / 1000L)
                .coerceAtLeast(1L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        return song.copy(song = song.song.copy(duration = durationSeconds))
    }

    private fun ArtistEntity.hasRemotePresenceId(): Boolean = channelId.isRemotePresenceId() || id.isRemotePresenceId()

    private fun String?.isRemotePresenceId(): Boolean {
        val id = this?.trim()?.takeIf { it.isNotBlank() } ?: return false
        return !id.isLocalMediaId() &&
            !id.isTelegramMediaId() &&
            !id.startsWith("LOCAL_ARTIST_") &&
            !id.startsWith("LA") &&
            !id.contains("privately_owned_artist", ignoreCase = true)
    }

    // Create a transient Song object from current Player MediaMetadata when the DB doesn't have it.
    private fun createTransientSongFromMedia(media: MediaMetadata): Song {
        val songEntity =
            SongEntity(
                id = media.id,
                title = media.title,
                duration = media.duration,
                thumbnailUrl = media.thumbnailUrl,
                albumId = media.album?.id,
                albumName = media.album?.title,
                explicit = media.explicit,
                isMusicVideo = media.isMusicVideo,
                isLocal = media.id.isLocalMediaId(),
            )

        val artists =
            media.artists.map { artist ->
                ArtistEntity(
                    id = artist.id ?: "LA_unknown_${artist.name}",
                    name = artist.name,
                    thumbnailUrl = if (!artist.thumbnailUrl.isNullOrBlank()) artist.thumbnailUrl else media.thumbnailUrl,
                    isLocal = artist.id == null || artist.id.isLocalMediaId(),
                )
            }

        val album =
            media.album?.let { alb ->
                AlbumEntity(
                    id = alb.id,
                    playlistId = null,
                    title = alb.title,
                    year = null,
                    thumbnailUrl = media.thumbnailUrl,
                    themeColor = null,
                    songCount = 1,
                    duration = media.duration,
                    isLocal = media.id.isLocalMediaId(),
                )
            }

        return Song(
            song = songEntity,
            artists = artists,
            album = album,
            format = null,
        )
    }

    private inline fun <reified T> readPersistentObject(fileName: String): T? {
        val persistentFile = filesDir.resolve(fileName)
        if (!persistentFile.exists() || !persistentFile.isFile) return null

        return synchronized(persistentStateLock) {
            runCatching {
                persistentFile.inputStream().use { fis ->
                    ObjectInputStream(fis).use { input ->
                        val payload = input.readObject()
                        check(payload is T) { "Unexpected persistent payload type for $fileName" }
                        payload
                    }
                }
            }.onFailure { error ->
                if (reportedPersistentReadFailures.add(fileName)) {
                    Timber.tag(TAG).d("Ignoring unreadable persistent file $fileName (${error::class.java.simpleName})")
                }
            }.getOrNull()
        }
    }

    private fun clearPersistedQueueFiles() {
        persistentSaveGeneration.incrementAndGet()
        synchronized(persistentStateLock) {
            listOf(
                PERSISTENT_QUEUE_FILE,
                PERSISTENT_PLAYER_STATE_FILE,
                PERSISTENT_AUTOMIX_FILE,
            ).forEach { fileName ->
                val persistentFile = filesDir.resolve(fileName)
                val tempFile = filesDir.resolve("$fileName.tmp")
                runCatching {
                    if (persistentFile.exists() && !persistentFile.delete()) {
                        Timber.tag(TAG).w("Failed to delete persistent file: $fileName")
                    }
                    if (tempFile.exists() && !tempFile.delete()) {
                        Timber.tag(TAG).w("Failed to delete temporary persistent file: $fileName")
                    }
                }.onFailure {
                    Timber.tag(TAG).w(it, "Failed to clear persistent file: $fileName")
                }
            }
        }
    }

    private fun writePersistentObject(
        fileName: String,
        payload: Serializable,
    ) {
        val persistentFile = filesDir.resolve(fileName)
        val tempFile = filesDir.resolve("$fileName.tmp")

        synchronized(persistentStateLock) {
            runCatching {
                FileOutputStream(tempFile).use { fos ->
                    ObjectOutputStream(fos).use { output ->
                        output.writeObject(payload)
                        output.flush()
                    }
                }

                if (!tempFile.renameTo(persistentFile)) {
                    if (persistentFile.exists() && !persistentFile.delete()) {
                        error("Could not replace $fileName")
                    }
                    if (!tempFile.renameTo(persistentFile)) {
                        error("Could not atomically move $fileName")
                    }
                }
            }.onFailure {
                runCatching { tempFile.delete() }
                reportException(it)
            }
        }
    }

    private fun MediaItem.toPersistableMetadata(): moe.rukamori.archivetune.models.MediaMetadata? {
        val tagged = metadata
        if (tagged != null) return tagged

        val id =
            mediaId
                .trim()
                .ifBlank {
                    localConfiguration
                        ?.uri
                        ?.toString()
                        ?.trim()
                        .orEmpty()
                }.takeIf { it.isNotBlank() } ?: return null

        val title =
            mediaMetadata.title
                ?.toString()
                ?.trim()
                .takeIf { !it.isNullOrBlank() }
                ?: id

        val artistText =
            mediaMetadata.artist
                ?.toString()
                ?.trim()
                .takeIf { !it.isNullOrBlank() }
                ?: mediaMetadata.subtitle
                    ?.toString()
                    ?.trim()
                    .takeIf { !it.isNullOrBlank() }

        val artists =
            artistText
                ?.split(",")
                ?.mapNotNull { it.trim().takeIf(String::isNotBlank) }
                ?.map { name ->
                    moe.rukamori.archivetune.models.MediaMetadata
                        .Artist(id = null, name = name)
                }.orEmpty()

        val thumbnailUrl = mediaMetadata.artworkUri?.toString()
        val albumTitle =
            mediaMetadata.albumTitle
                ?.toString()
                ?.trim()
                .takeIf { !it.isNullOrBlank() }
        val album =
            albumTitle?.let { titleValue ->
                moe.rukamori.archivetune.models.MediaMetadata
                    .Album(id = titleValue, title = titleValue)
            }

        return moe.rukamori.archivetune.models.MediaMetadata(
            id = id,
            title = title,
            artists = artists,
            duration = -1,
            thumbnailUrl = thumbnailUrl,
            album = album,
            explicit = false,
            liked = false,
            likedDate = null,
            inLibrary = null,
        )
    }

    /**
     * Last player state actually written, compared ignoring [PersistPlayerState.timestamp] so a
     * paused player — whose position does not move — stops rewriting an identical file every tick.
     */
    @Volatile
    private var lastPersistedPlayerState: PersistPlayerState? = null

    /**
     * Writes just the resume position and transport flags, skipping the queue snapshot.
     *
     * Deliberately not [saveQueueToDisk]: that maps every media item to persistable metadata on
     * the main thread and writes two files. For the periodic save none of that changes between
     * ticks — only the position does — and the queue file is already written by every handler that
     * can change it.
     */
    private suspend fun savePlayerStateToDisk() {
        val saveGeneration = persistentSaveGeneration.get()
        val state =
            withContext(Dispatchers.Main.immediate) {
                if (
                    saveGeneration != persistentSaveGeneration.get() ||
                    isRestoringPersistentState ||
                    isHydratingRestoredQueue ||
                    player.mediaItemCount == 0
                ) {
                    return@withContext null
                }
                PersistPlayerState(
                    playWhenReady = player.playWhenReady,
                    repeatMode = player.repeatMode,
                    shuffleModeEnabled = player.shuffleModeEnabled,
                    volume = playerVolume.value,
                    currentPosition = player.currentPosition,
                    currentMediaItemIndex = player.currentMediaItemIndex,
                    playbackState = player.playbackState,
                )
            } ?: return

        // timestamp defaults to now, so it always differs — compare everything else.
        val previous = lastPersistedPlayerState
        if (previous != null && previous.copy(timestamp = 0L) == state.copy(timestamp = 0L)) return

        withContext(Dispatchers.IO) {
            if (saveGeneration != persistentSaveGeneration.get()) return@withContext
            writePersistentObject(PERSISTENT_PLAYER_STATE_FILE, state)
            lastPersistedPlayerState = state
        }
    }

    private suspend fun saveQueueToDisk() {
        val saveGeneration = persistentSaveGeneration.get()
        val snapshot =
            withContext(Dispatchers.Main.immediate) {
                if (
                    saveGeneration != persistentSaveGeneration.get() ||
                    isRestoringPersistentState ||
                    isHydratingRestoredQueue
                ) {
                    return@withContext null
                }

                val mediaItemsSnapshot = player.mediaItems.mapNotNull { it.toPersistableMetadata() }
                if (mediaItemsSnapshot.isEmpty()) return@withContext null

                val currentMediaItemIndex = player.currentMediaItemIndex
                val currentPosition = player.currentPosition
                val persistQueue =
                    currentQueue.toPersistQueue(
                        title = queueTitle,
                        items = mediaItemsSnapshot,
                        mediaItemIndex = currentMediaItemIndex,
                        position = currentPosition,
                    )
                val persistPlayerState =
                    PersistPlayerState(
                        playWhenReady = player.playWhenReady,
                        repeatMode = player.repeatMode,
                        shuffleModeEnabled = player.shuffleModeEnabled,
                        volume = playerVolume.value,
                        currentPosition = currentPosition,
                        currentMediaItemIndex = currentMediaItemIndex,
                        playbackState = player.playbackState,
                    )

                persistQueue to persistPlayerState
            } ?: return

        withContext(Dispatchers.IO) {
            if (saveGeneration != persistentSaveGeneration.get()) return@withContext
            writePersistentObject(PERSISTENT_QUEUE_FILE, snapshot.first)
            if (saveGeneration != persistentSaveGeneration.get()) return@withContext
            writePersistentObject(PERSISTENT_PLAYER_STATE_FILE, snapshot.second)
        }
    }

    override fun onDestroy() {
        // Released before anything else is torn down: once destruction has started no bound client
        // may obtain this service again (and the binder must stop pinning it).
        binder.release()
        runCatching { listenTogetherManager.onMusicServiceDestroyed(this) }
        equalizerPlaybackController.detach(this)
        sponsorBlockPlaybackController.detach()
        musicHapticsEngine?.release()
        musicHapticsEngine = null
        discordServiceStopping = true
        requestDiscordSync(
            reason = "service_destroy",
            force = true,
        )
        super.onDestroy()
        effectiveVolumeRampJob?.cancel()
        effectiveVolumeRampJob = null
        cancelCrossfade(resetVolume = false, resetPauseAtEnd = true)
        audioRouteRecoveryJob?.cancel()
        if (audioDeviceCallbackRegistered) {
            audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
            audioDeviceCallbackRegistered = false
        }
        bitPerfectDeviceCallback?.let { callback ->
            runCatching { getSystemService(AudioManager::class.java)?.unregisterAudioDeviceCallback(callback) }
        }
        bitPerfectDeviceCallback = null
        // Hands the DAC back to the shared mixer; the preference would otherwise outlive the app.
        BitPerfectUsbOutput.clear(this)
        BitPerfectUsbOutput.sinkActive = false
        unregisterBluetoothReceiver()
        unregisterMuteRecoveryObserver()
        try {
            connectivityObserver.unregister()
        } catch (_: Exception) {
        }
        lyricsPreloadManager?.destroy()
        lyricsPreloadManager = null
        abandonAudioFocus()
        try {
            releaseAudioEffects()
        } catch (_: Exception) {
        }
        try {
            if (dataStore.get(PersistentQueueKey, true) && player.mediaItemCount > 0) {
                runBlocking {
                    saveQueueToDisk()
                }
            }
        } catch (_: Exception) {
        }
        try {
            mediaSession.release()
        } catch (_: Exception) {
        }
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        try {
            localPlayer.removeListener(audioEffectPlayerListener)
            player.removeListener(this)
            player.removeListener(sleepTimer)
            initialBufferRecoveryJob?.cancel()
            player.release()
            castPlaybackRepository.releasePlayer(player)
        } catch (_: Exception) {
        }
        // The sync worker is a child of scopeJob and may be cancelled at the
        // receive below before it drains the service_destroy request. Stop the
        // manager directly so the static holder drops its listener (which
        // captures this@MusicService) even if that race is lost. Idempotent.
        try {
            DiscordPresenceManager.stop()
        } catch (_: Exception) {
        }
        runCatching { smartFadeAnalyzer?.release() }
        smartFadeAnalyzer = null
        scopeJob.cancel()
    }

    override fun onBind(intent: Intent?): android.os.IBinder? {
        hasBoundClients = true
        cancelIdleStop()
        val result = super.onBind(intent) ?: binder
        if (player.mediaItemCount > 0 && player.currentMediaItem != null) {
            currentMediaMetadata.value = player.currentMetadata
            scope.launch {
                delay(50)
                updateNotification()
            }
        }
        return result
    }

    override fun onUnbind(intent: Intent?): Boolean {
        hasBoundClients = false
        scheduleStopIfIdle()
        return super.onUnbind(intent)
    }

    override fun onRebind(intent: Intent?) {
        hasBoundClients = true
        cancelIdleStop()
        super.onRebind(intent)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)

        val stopMusicOnTaskClearEnabled = dataStore.get(StopMusicOnTaskClearKey, false)

        try {
            if (stopMusicOnTaskClearEnabled) {
                discordServiceStopping = true
                requestDiscordSync(
                    reason = "task_removed_stop_music_on_task_clear",
                    force = true,
                )
                runCatching { stopAndClearPlayback(clearPersistentState = true) }
                stopForegroundAndSelf()
                return
            }

            if (dataStore.get(PersistentQueueKey, true) && player.mediaItemCount > 0) {
                runBlocking { saveQueueToDisk() }
            }
        } catch (_: Exception) {
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    private fun handleMediaNotificationDismissed(intent: Intent) {
        val originalDeleteIntent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(
                    EXTRA_MEDIA_NOTIFICATION_DELETE_INTENT,
                    PendingIntent::class.java,
                )
            } else {
                intent.getParcelableExtra(EXTRA_MEDIA_NOTIFICATION_DELETE_INTENT)
            }

        val isForeground = isAppInForeground()
        if (!player.isPlaying && !isForeground) {
            pausedPresenceGate = PausedPresenceGate.HiddenByNotificationDismiss
            requestDiscordSync(
                reason = "notification_dismissed_while_paused_background",
                force = true,
            )
        } else if (!player.isPlaying) {
            Timber.tag(DISCORD_SYNC_TAG).d(
                "notification dismissed while paused but app is foreground; keeping paused RPC visible",
            )
        }

        runCatching {
            originalDeleteIntent?.send()
        }.onFailure {
            Timber.tag(DISCORD_SYNC_TAG).w(it, "failed to forward original notification delete intent")
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_MEDIA_NOTIFICATION_DISMISSED) {
            handleMediaNotificationDismissed(intent)
            return START_NOT_STICKY
        }

        ensureStartedAsForeground()
        when (intent?.action) {
            "moe.rukamori.archivetune.WIDGET_PLAY_PAUSE" -> {
                if (player.isPlaying) player.pause() else player.play()
            }

            "moe.rukamori.archivetune.WIDGET_SKIP_NEXT" -> {
                if (player.hasNextMediaItem()) {
                    prepareForManualSkip()
                    player.seekToNext()
                    player.prepare()
                    player.play()
                }
            }

            "moe.rukamori.archivetune.WIDGET_SKIP_PREV" -> {
                if (player.hasPreviousMediaItem()) {
                    prepareForManualSkip()
                    player.seekToPrevious()
                    player.prepare()
                    player.play()
                }
            }
        }
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    override fun onUpdateNotification(
        session: MediaSession,
        startInForegroundRequired: Boolean,
    ) {
        val shouldShowNotification =
            shouldShowPlaybackNotification(
                startInForegroundRequired = startInForegroundRequired,
                hasResumablePlayback = hasResumablePlaybackNotification(),
            )
        if (!shouldShowNotification) return

        ensureStartedAsForeground()
        runCatching { super.onUpdateNotification(session, true) }
            .onFailure { reportException(it) }
    }

    // ���─ Widget Support ──────────────────────────────���─────────────────────────────

    fun updateWidget() {
        widgetUpdater.update()
        widgetUpdater.updateProgressTracking()
    }

    /**
     * Hands [service] to bound clients. The reference is dropped by [MusicService.onDestroy] so
     * that a client which kept the binder — a widget update, a lingering ServiceConnection, an
     * Android Auto consumer — can no longer reach, and therefore cannot pin, a service whose
     * players, media session and scopes have already been released. Deliberately NOT an inner
     * class: an inner binder would hold a second, implicit reference to the service.
     */
    class MusicBinder internal constructor(service: MusicService) : Binder() {
        @Volatile
        private var serviceReference: MusicService? = service

        val service: MusicService
            get() = checkNotNull(serviceReference) { "MusicService has been destroyed" }

        internal fun release() {
            serviceReference = null
        }
    }

    companion object {
        internal fun shouldShowPlaybackNotification(
            startInForegroundRequired: Boolean,
            hasResumablePlayback: Boolean,
        ): Boolean = startInForegroundRequired || hasResumablePlayback

        const val ROOT = "root"
        const val HOME = "home"
        const val HOME_QUICK_PICKS = "home_quick_picks"

        // Prefix for the dedicated player-cache key used by Tidal streams so their bytes never
        // collide with the YouTube stream cached under the bare media id.
        private const val TIDAL_CACHE_KEY_PREFIX = "tidal:"

        /** Apple Music license endpoint (Widevine L3 key exchange, verified live). */
        private const val APPLE_LICENSE_URL =
            "https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/acquireWebPlaybackLicense"
        const val HOME_FORGOTTEN_FAVORITES = "home_forgotten_favorites"
        const val HOME_KEEP_LISTENING = "home_keep_listening"
        const val HOME_SUGGESTED_SONGS = "home_suggested_songs"
        const val HOME_MIXES_AND_RADIOS = "home_mixes_and_radios"
        const val QUICK_PICKS = "quick_picks"
        const val RECENT = "recent"
        const val LIKED = "liked"
        const val DOWNLOADED = "downloaded"
        const val SONG = "song"
        const val ARTIST = "artist"
        const val ALBUM = "album"
        const val PLAYLIST = "playlist"
        const val SPOTIFY_PLAYLIST = "spotify_playlist"
        const val SPOTIFY_LIKED = "spotify_liked"
        const val ONLINE_PLAYLIST = "online_playlist"

        private const val TAG = "MusicService"
        private const val AUDIO_EFFECT_INITIALIZATION_MAX_ATTEMPTS = 4
        private const val AUDIO_EFFECT_INITIALIZATION_RETRY_DELAY_MS = 250L
        private const val INFINITE_QUEUE_MAX_BOOTSTRAP_PAGES = 3
        private const val DISCORD_SYNC_TAG = "DiscordSync"
        private const val DISCORD_HOLD_TIMEOUT_MS = 7_000L
        const val CHANNEL_ID = "music_channel_01"
        const val ACTION_MEDIA_NOTIFICATION_DISMISSED =
            "moe.rukamori.archivetune.action.MEDIA_NOTIFICATION_DISMISSED"
        const val EXTRA_MEDIA_NOTIFICATION_DELETE_INTENT =
            "moe.rukamori.archivetune.extra.MEDIA_NOTIFICATION_DELETE_INTENT"
        const val NOTIFICATION_ID = 888
        const val ERROR_CODE_NO_STREAM = 1000001
        const val CHUNK_LENGTH = 8 * 1024 * 1024L
        val RETRYABLE_STREAM_RESPONSE_CODES = setOf(403, 404, 410, 416)
        private const val INITIAL_BUFFER_STALL_DELAY_MS = 15_000L
        private const val INITIAL_BUFFER_STALL_POSITION_MS = 5_000L
        const val PERSISTENT_QUEUE_FILE = "persistent_queue.data"
        const val PERSISTENT_AUTOMIX_FILE = "persistent_automix.data"
        const val PERSISTENT_PLAYER_STATE_FILE = "persistent_player_state.data"

        /**
         * How often the resume position is written while the service is alive.
         *
         * This only bounds how far back a restore lands after a process death, so 30s of accuracy
         * is plenty. Every event that changes the queue saves it directly, so nothing is lost by
         * ticking less often.
         */
        private val PERSISTENT_POSITION_SAVE_INTERVAL = 30.seconds
        const val MAX_CONSECUTIVE_ERR = 5
        const val AUDIO_ROUTE_CHANGE_DEBOUNCE_MS = 350L
        const val AUDIO_EFFECT_ROUTE_REBIND_DELAY_MS = 200L
        const val AUDIO_ROUTE_RECOVERY_MIN_INTERVAL_MS = 1_500L
        const val AUDIO_ROUTE_RECOVERY_RESUME_DELAY_MS = 150L
        const val DEVICE_MUTE_PLAYBACK_NOTICE_INTERVAL_MS = 1_200L
        // Delay before re-asserting the captured baseline volume after a source switch's
        // player.prepare() call. Long enough that the prepare pipeline has settled (and any
        // async state transitions complete within one frame after that) without being so long
        // that the user perceives a muted gap.
        const val SOURCE_SWITCH_VOLUME_REASSERT_MS = 250L
        // Fast-path reassert for a seek that stays within the buffered region (no
        // BUFFERING->READY, so the STATE_READY seek hook never fires). Covers the case the
        // 15s audible-volume watchdog would otherwise be the only recovery for.
        const val SEEK_VOLUME_REASSERT_MS = 300L
        const val MIN_AUDIO_FOCUS_VOLUME_FACTOR = 0.2f
        const val MIN_AUDIO_NORMALIZATION_FACTOR = 0.25f
        const val MAX_AUDIO_NORMALIZATION_FACTOR = 1.414f
        const val EFFECTIVE_VOLUME_RAMP_FRAME_MS = 16L
        const val EFFECTIVE_VOLUME_RAMP_UP_MS = 350L
        const val EFFECTIVE_VOLUME_RAMP_DOWN_MS = 180L
        const val EFFECTIVE_VOLUME_RAMP_MIN_DELTA = 0.015f
        const val MIN_CROSSFADE_DURATION_MS = 500L
        const val CROSSFADE_END_GUARD_MS = 150L
        const val CROSSFADE_EARLY_FINISH_MS = 350L
        const val CROSSFADE_BUFFER_STALL_GRACE_MS = 250L
        const val SMART_FADE_TAIL_WAIT_MS = 2_000L
        const val SMART_FADE_TAIL_EPSILON_MS = 8L
        const val CROSSFADE_PREPARE_AHEAD_MS = 30_000L
        const val CROSSFADE_READY_TIMEOUT_MS = 5_000L
        const val CROSSFADE_HANDOFF_BUFFER_MS = 5_000L
        const val CROSSFADE_AUDIO_ADVANCE_TIMEOUT_MS = 2_000L
        const val CROSSFADE_AUDIO_ADVANCE_POLL_MS = 80L
        const val CROSSFADE_MIN_BUFFER_BEFORE_START_MS = 5_000L
        const val CROSSFADE_MAX_BUFFER_BEFORE_START_MS = 12_500L
        const val PRIMARY_MIN_BUFFER_MS = 20_000
        const val PRIMARY_MAX_BUFFER_MS = 60_000
        // Reduced from 750ms / 2_500ms → 150ms / 750ms for faster song-start latency.
        const val PRIMARY_BUFFER_FOR_PLAYBACK_MS = 150
        const val PRIMARY_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 750
        const val CROSSFADE_MIN_BUFFER_MS = 15_000
        const val CROSSFADE_MAX_BUFFER_MS = 45_000
        private const val CONSERVATIVE_MIN_BUFFER_MS = 8_000
        private const val CONSERVATIVE_MAX_BUFFER_MS = 24_000
        private const val CONSERVATIVE_BUFFER_FOR_PLAYBACK_MS = 250
        private const val CONSERVATIVE_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 1_000
        private const val CONSERVATIVE_CROSSFADE_MIN_BUFFER_MS = 8_000
        private const val CONSERVATIVE_CROSSFADE_MAX_BUFFER_MS = 24_000
        private const val CONSERVATIVE_CROSSFADE_BUFFER_BEFORE_START_MS = 3_000
        const val CROSSFADE_FRAME_MS = 32L

        // ---- Automix (smart fade): poll cadence, fallback overlap and the
        // filter-ride constants, all ported from BitChord's CrossfadeController.
        const val SMART_FADE_POLL_MS = 1_000L
        /** Hard ceiling for one automix audio-source resolve: the analysis worker is
         * single-threaded, so an unbounded resolve starves both tracks' status (4nx3b b0250307a). */
        const val SMART_FADE_RESOLVE_TIMEOUT_MS = 45_000L
        const val DEFAULT_SMART_FALLBACK_MS = 6_000L
        const val FILTER_ENTRY_HZ = 7_000.0
        const val FILTER_FLOOR_HZ = 300.0
        const val FILTER_SWEEP_SHAPE = 0.75
        const val ENTRY_HIGH_PASS_HZ = 1_200.0
        const val ENTRY_OPEN_BY = 0.6
        const val ENTRY_SHAPE = 0.35
        const val BASS_SWAP_HZ = 200.0
        const val BASS_SWAP_WIDTH = 0.10
        const val VOCAL_SEPARATION_FLOOR_HZ = 1_600.0
        const val VOCAL_SEPARATION_HIGH_PASS_HZ = 700.0
        const val BLEND_ENTRY_HIGH_PASS_HZ = 520.0
        const val BLEND_ENTRY_CLASH_HIGH_PASS_HZ = 950.0
        const val BLEND_ENTRY_OPEN_BY = 0.45
        const val BLEND_ENTRY_CLASH_OPEN_BY = 0.7
        const val BLEND_EXIT_FROM = 0.3
        const val BLEND_EXIT_CLASH_FROM = 0.12
        const val BLEND_EXIT_LOW_PASS_HZ = 2_200.0
        const val BLEND_EXIT_CLASH_LOW_PASS_HZ = 1_100.0
        const val MIN_AUDIBLE_EFFECTIVE_VOLUME = 0.01f
        const val STUCK_MUTED_VOLUME_EPSILON = 0.001f
        /** How often the stuck-mute watchdog re-checks the player volume during active playback. */
        const val AUDIBLE_PLAYBACK_VOLUME_CHECK_MS = 15_000L

        /**
         * TTL for cached Qobuz/Tidal DirectStreams. Set to 5 minutes to match the
         * typical signed-URL lifetime on those providers (4-6 minutes). After this,
         * a cached stream is considered stale and re-resolved from the source.
         */
        private const val DIRECT_STREAM_CACHE_TTL_MS = 5L * 60L * 1000L
    }
}
