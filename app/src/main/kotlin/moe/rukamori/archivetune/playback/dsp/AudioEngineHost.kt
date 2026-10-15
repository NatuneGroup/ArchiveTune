/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 * Portions © 4nx3b — github.com/4nx3b/ArchiveTune (GPL-3.0)
 */

@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package moe.rukamori.archivetune.playback.dsp

import android.content.Context
import android.util.Log
import com.lastwave.app.data.local.EqualizerPreferences
import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.playback.NativeAudioEngine
import com.lastwave.app.playback.NativePcmAudioProcessor
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import moe.rukamori.archivetune.constants.FloatDspEnabledKey
import moe.rukamori.archivetune.constants.LastwaveAudioProcessingKey
import moe.rukamori.archivetune.constants.TryptifyAudioProcessingKey
import moe.rukamori.archivetune.utils.PreferenceStore
import moe.rukamori.archivetune.utils.dataStore
import tf.monochrome.android.audio.dsp.ChannelDetectorProcessor
import tf.monochrome.android.audio.dsp.DownmixProcessor
import tf.monochrome.android.audio.dsp.DspEngineManager
import tf.monochrome.android.audio.dsp.MixBusProcessor
import tf.monochrome.android.audio.dsp.crossfeed.CrossfeedEffect
import tf.monochrome.android.audio.dsp.oxford.CompressorEffect
import tf.monochrome.android.audio.dsp.oxford.InflatorEffect
import tf.monochrome.android.audio.eq.AutoEqProcessor
import tf.monochrome.android.audio.eq.ParametricEqProcessor
import tf.monochrome.android.audio.eq.SpectrumAnalyzerTap
import tf.monochrome.android.audio.eq.SystemAudioEqController
import tf.monochrome.android.audio.resample.VariRateAudioProcessor
import tf.monochrome.android.audio.stretch.StretchAudioProcessor
import tf.monochrome.android.audio.usb.AudioProcessorChain
import tf.monochrome.android.audio.usb.ToFloatPcmAudioProcessor
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.performance.PerformanceProfile

/**
 * Owns the Tryptify and LastWave engines for every player sink.
 *
 * Nothing native is loaded or constructed until a switch asks for an engine, so with every switch
 * off this class only watches three preferences.
 *
 * The engines carry filter and limiter state, so only one sink may run audio through them at a
 * time. A crossfade keeps two players alive; the one that holds the lease keeps the engine and the
 * other plays through the stock path until the lease is released (on its next track boundary with
 * the engine off, or when its player is released).
 */
@Singleton
class AudioEngineHost @Inject constructor(
    @ApplicationContext private val context: Context,
    private val channelDetector: ChannelDetectorProcessor,
    private val appScope: CoroutineScope,
) {
    private class TryptifyStack(
        val chain: AudioProcessorChain,
        val controller: TryptifyEngineController,
    )

    private val lease = AtomicReference<Any?>(null)

    /** Bumped on every engine-switch change so an engaged router can re-route mid-track. */
    @Volatile
    var settingsGeneration: Int = 0
        private set

    @Volatile
    private var tryptifyStackBuilt = false

    @Volatile
    private var lastwaveBuilt = false

    private val tryptifyStack: TryptifyStack? by lazy {
        runCatching {
            if (!AudioEngineRouterProcessor.tryptifyNativeAvailable()) return@runCatching null
            tf.monochrome.android.audio.eq.FrequencyTargets.init(context)
            val preferences = PreferencesManager(context)
            val mixBus = MixBusProcessor(InflatorEffect(), CompressorEffect(), CrossfeedEffect())
            val autoEq = AutoEqProcessor()
            val paramEq = ParametricEqProcessor()
            val downmix = DownmixProcessor()
            val controller =
                TryptifyEngineController(
                    scope = appScope,
                    mixBus = mixBus,
                    autoEq = autoEq,
                    paramEq = paramEq,
                    preferences = preferences,
                    dspManager = DspEngineManager(mixBus, preferences),
                    systemEq = SystemAudioEqController(context, preferences),
                )
            controller.start()
            preferences.multichannelDownmixEnabled
                .distinctUntilChanged()
                .onEach { downmix.setEnabled(it) }
                .launchIn(appScope)
            val chain =
                AudioProcessorChain(
                    listOf(
                        ToFloatPcmAudioProcessor(),
                        channelDetector,
                        downmix,
                        mixBus,
                        autoEq,
                        paramEq,
                        SpectrumAnalyzerTap(PerformanceProfile.HIGH),
                        VariRateAudioProcessor(),
                        StretchAudioProcessor(),
                    ),
                )
            TryptifyStack(chain, controller)
        }.onFailure { Log.w(TAG, "Tryptify engine unavailable", it) }
            .getOrNull()
            .also { tryptifyStackBuilt = true }
    }

    private val lastwaveProcessor: NativePcmAudioProcessor? by lazy {
        runCatching {
            val dataStore = context.applicationContext.dataStore
            val engine =
                NativeAudioEngine(SettingsPreferences(dataStore), EqualizerPreferences(dataStore), appScope)
            if (engine.isAvailable) NativePcmAudioProcessor(engine) else null
        }.onFailure { Log.w(TAG, "LastWave engine unavailable", it) }
            .getOrNull()
            .also { lastwaveBuilt = true }
    }

    init {
        context.applicationContext.dataStore.data
            .map {
                Triple(
                    it[TryptifyAudioProcessingKey] ?: false,
                    it[LastwaveAudioProcessingKey] ?: false,
                    it[FloatDspEnabledKey] ?: false,
                )
            }.distinctUntilChanged()
            .onEach { (tryptify, lastwave, _) ->
                runCatching { onSwitchesChanged(tryptify, lastwave) }
                    .onFailure { Log.w(TAG, "Audio engine switch handling failed", it) }
            }
            .launchIn(appScope)
    }

    private fun onSwitchesChanged(tryptify: Boolean, lastwave: Boolean) {
        val wanted =
            when {
                tryptify -> AudioEngineRouterProcessor.Engine.TRYPTIFY
                lastwave -> AudioEngineRouterProcessor.Engine.LASTWAVE
                else -> AudioEngineRouterProcessor.Engine.NONE
            }
        EngineRuntime.publishWantedEngine(wanted)
        // Builds the engines here, off the playback thread, so the first configure that wants
        // one does not pay for the native load.
        if (tryptify || tryptifyStackBuilt) {
            val stack = tryptifyStack
            runCatching { stack?.controller?.setEngineActive(tryptify) }
                .onFailure { Log.w(TAG, "Tryptify engine gate failed", it) }
        }
        val lastwaveAvailable =
            (wanted == AudioEngineRouterProcessor.Engine.LASTWAVE || lastwaveBuilt) && lastwaveProcessor != null
        EngineRuntime.publishEngineAvailability(
            tryptify = tryptifyStackBuilt && tryptifyStack != null,
            lastwave = lastwaveAvailable,
        )
        settingsGeneration++
    }

    /** Read on the playback thread from the in-memory snapshot, never from disk. */
    fun wantedEngine(): AudioEngineRouterProcessor.Engine =
        when {
            PreferenceStore.get(TryptifyAudioProcessingKey) == true ->
                AudioEngineRouterProcessor.Engine.TRYPTIFY
            PreferenceStore.get(LastwaveAudioProcessingKey) == true ->
                AudioEngineRouterProcessor.Engine.LASTWAVE
            else -> AudioEngineRouterProcessor.Engine.NONE
        }

    fun floatDspWanted(): Boolean = PreferenceStore.get(FloatDspEnabledKey) == true

    internal fun tryptifyChain(): AudioProcessorChain? = tryptifyStack?.chain

    internal fun builtTryptifyChain(): AudioProcessorChain? =
        if (tryptifyStackBuilt) tryptifyStack?.chain else null

    internal fun lastwave(): NativePcmAudioProcessor? = lastwaveProcessor

    internal fun builtLastwave(): NativePcmAudioProcessor? = if (lastwaveBuilt) lastwaveProcessor else null

    fun acquire(owner: Any): Boolean = lease.compareAndSet(null, owner) || lease.get() === owner

    fun release(owner: Any) {
        lease.compareAndSet(owner, null)
    }

    fun owns(owner: Any): Boolean = lease.get() === owner

    /** Diagnostics follow the sink that holds the engines, or any sink while none does. */
    fun mayPublish(owner: Any): Boolean = lease.get().let { it == null || it === owner }

    fun newRouter(): AudioEngineRouterProcessor = AudioEngineRouterProcessor(this, FloatDspProcessor())

    private companion object {
        const val TAG = "AudioEngineHost"
    }
}
