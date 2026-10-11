/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 * Portions © tryptz — github.com/tryptz/Tryptify
 */

package tf.monochrome.android.audio.eq

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import moe.rukamori.archivetune.R
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.ToneControls
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SystemAudioEqController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: PreferencesManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }
    private var channelReady = false

    private var dynamics: DynamicsProcessing? = null
    private var equalizer: Equalizer? = null
    private var started = false

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    @Volatile
    private var engineActive = true

    fun setEngineActive(active: Boolean) {
        if (engineActive == active) return
        engineActive = active
        scope.launch {
            val cfg = currentCfg()
            if (cfg != null && cfg.enabled && engineActive) {
                applyGlobal(cfg.bandsJson, cfg.preamp, cfg.tone)
                updateNotification(true)
            } else {
                release()
                updateNotification(false)
            }
        }
    }

    private suspend fun currentCfg(): Cfg? =
        runCatching {
            Cfg(
                enabled = preferences.systemWideAutoEqEnabled.first(),
                bandsJson = preferences.eqBandsJson.first(),
                preamp = preferences.eqPreamp.first(),
                tone = preferences.systemToneControls.first(),
            )
        }.getOrNull()

    @OptIn(FlowPreview::class)
    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        scope.launch {
            combine(
                preferences.systemWideAutoEqEnabled,
                preferences.eqBandsJson,
                preferences.eqPreamp,
                preferences.systemToneControls,
            ) { enabled, bandsJson, preamp, tone -> Cfg(enabled, bandsJson, preamp, tone) }

                .distinctUntilChanged()

                .debounce(70L)
                .collectLatest { cfg ->
                    if (cfg.enabled && engineActive) applyGlobal(cfg.bandsJson, cfg.preamp, cfg.tone) else release()

                    updateNotification(cfg.enabled && engineActive)
                }
        }
    }

    private data class Cfg(
        val enabled: Boolean,
        val bandsJson: String?,
        val preamp: Double,
        val tone: ToneControls,
    )

    private fun applyGlobal(bandsJson: String?, preamp: Double, tone: ToneControls) {
        val bands = decodeBands(bandsJson) + tone.toBands()

        val hasEffect = preamp != 0.0 || bands.any { it.enabled && it.gain != 0f }
        synchronized(lock) {
            if (!hasEffect) {
                releaseLocked()
                _active.value = false
                return
            }
            val primary = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    applyDynamicsLocked(bands, preamp.toFloat())
                } else {
                    applyEqualizerLocked(bands, preamp.toFloat())
                }
            }.onFailure { Log.w(TAG, "system-wide EQ primary attach failed: ${it.message}") }
                .getOrDefault(false)

            val attached = if (!primary && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { applyEqualizerLocked(bands, preamp.toFloat()) }
                    .onFailure { Log.w(TAG, "system-wide EQ fallback attach failed: ${it.message}") }
                    .getOrDefault(false)
            } else {
                primary
            }
            _active.value = attached
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun applyDynamicsLocked(bands: List<EqBand>, preamp: Float): Boolean {
        val n = BAND_FREQS.size
        val channels = 2

        val dp = dynamics ?: run {
            releaseLocked()
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                channels,
                 true,  n,
                 false,  0,
                 false,  0,
                 false,
            ).build()
            DynamicsProcessing(GLOBAL_PRIORITY, GLOBAL_SESSION, config)
        }
        for (ch in 0 until channels) {
            val eq = dp.getPreEqByChannelIndex(ch)
            eq.isEnabled = true
            for (b in 0 until n) {
                val band = eq.getBand(b)
                band.isEnabled = true
                band.cutoffFrequency = BAND_FREQS[b]
                band.gain = gainAt(BAND_FREQS[b], bands, preamp)
                eq.setBand(b, band)
            }
            dp.setPreEqByChannelIndex(ch, eq)
        }
        dp.setEnabled(true)
        dynamics = dp
        return dp.enabled
    }

    private fun applyEqualizerLocked(bands: List<EqBand>, preamp: Float): Boolean {
        val eq = equalizer ?: Equalizer(GLOBAL_PRIORITY, GLOBAL_SESSION)
        val range = eq.bandLevelRange
        val minLevel = range[0].toInt()
        val maxLevel = range[1].toInt()
        val count = eq.numberOfBands.toInt()
        for (b in 0 until count) {
            val fc = eq.getCenterFreq(b.toShort()) / 1000f
            val millibels = (gainAt(fc, bands, preamp) * 100f).toInt().coerceIn(minLevel, maxLevel)
            eq.setBandLevel(b.toShort(), millibels.toShort())
        }
        eq.setEnabled(true)
        equalizer = eq
        return eq.enabled
    }

    private fun gainAt(freqHz: Float, bands: List<EqBand>, preamp: Float): Float {
        var g = preamp
        for (band in bands) g += AutoEqEngine.calculateBiquadResponse(freqHz, band)
        return g.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
    }

    private fun decodeBands(bandsJson: String?): List<EqBand> =
        if (bandsJson.isNullOrEmpty()) emptyList()
        else runCatching { json.decodeFromString<List<EqBand>>(bandsJson) }.getOrDefault(emptyList())

    private fun release() {
        synchronized(lock) {
            releaseLocked()
            _active.value = false
        }
    }

    private fun releaseLocked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { dynamics?.setEnabled(false) }
            runCatching { dynamics?.release() }
        }
        dynamics = null
        runCatching { equalizer?.setEnabled(false) }
        runCatching { equalizer?.release() }
        equalizer = null
    }

    private fun updateNotification(enabled: Boolean) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        if (!enabled) {
            runCatching { manager.cancel(NOTIFICATION_ID) }
            return
        }
        ensureChannel(manager)
        val tapIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(
                context, 0, it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.mic)
            .setContentTitle("System-wide EQ active")
            .setContentText("Your AutoEQ correction is being applied to all audio on this device.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(tapIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (channelReady) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "System-wide EQ",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shown while the system-wide equalizer is applied to all audio."
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            },
        )
        channelReady = true
    }

    private companion object {
        const val CHANNEL_ID = "system_wide_eq"
        const val NOTIFICATION_ID = 42010
        const val TAG = "SystemAudioEq"
        const val GLOBAL_SESSION = 0
        const val GLOBAL_PRIORITY = 0
        const val MIN_GAIN_DB = -24f
        const val MAX_GAIN_DB = 24f

        val BAND_FREQS = floatArrayOf(
            20f, 25f, 31.5f, 40f, 50f, 63f, 80f, 100f, 125f, 160f,
            200f, 250f, 315f, 400f, 500f, 630f, 800f, 1000f, 1250f, 1600f,
            2000f, 2500f, 3150f, 4000f, 5000f, 6300f, 8000f, 10000f, 12500f, 16000f,
            20000f,
        )
    }
}
