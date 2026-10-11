@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package moe.rukamori.archivetune.playback.dsp

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import tf.monochrome.android.audio.dsp.DspEngineManager
import tf.monochrome.android.audio.dsp.MixBusProcessor
import tf.monochrome.android.audio.eq.AutoEqProcessor
import tf.monochrome.android.audio.eq.ParametricEqProcessor
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.ToneControls

class TryptifyEngineController(
    private val scope: CoroutineScope,
    val mixBus: MixBusProcessor,
    val autoEq: AutoEqProcessor,
    val paramEq: ParametricEqProcessor,
    val preferences: PreferencesManager,
    val dspManager: DspEngineManager,

    private val systemEq: tf.monochrome.android.audio.eq.SystemAudioEqController,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private var restoreJob: Job? = null
    private var engineEverReady = false

    fun start() {
        runCatching { systemEq.start() }
            .onFailure { Log.w(TAG, "system-wide AutoEQ controller failed to start", it) }

        val eqCore =
            combine(
                preferences.eqEnabled,
                preferences.eqBandsJson,
                preferences.eqBandsRJson,
                preferences.eqStereoMode,
                preferences.eqPreamp,
            ) { enabled, bandsJson, bandsRJson, stereo, preamp ->
                EqCore(enabled, bandsJson, bandsRJson, stereo, preamp)
            }
        combine(
            eqCore,
            preferences.systemToneControls,
            preferences.systemWideAutoEqEnabled,
        ) { core, tone, systemWide ->
            EqApply(core.enabled, core.bandsJson, core.bandsRJson, core.stereo, core.preamp, tone, systemWide)
        }.distinctUntilChanged()
            .onEach { applyEqSettings(it) }
            .launchIn(scope)

        combine(
            preferences.paramEqEnabled,
            preferences.paramEqBandsJson,
            preferences.paramEqPreamp,
        ) { enabled, bandsJson, preamp ->
            Triple(enabled, bandsJson, preamp)
        }.distinctUntilChanged()
            .onEach { (enabled, bandsJson, preamp) ->
                applyParametricEqSettings(enabled, bandsJson, preamp)
            }
            .launchIn(scope)

        mixBus.engineReady
            .onEach { ready ->
                if (!ready) return@onEach
                if (!engineEverReady) {
                    engineEverReady = true
                    restoreJob?.cancel()
                    restoreJob = scope.launch {
                        runCatching { dspManager.restoreState() }
                            .onFailure { Log.w(TAG, "DSP state restore failed", it) }
                    }
                } else {
                    restoreJob?.cancel()
                    restoreJob = scope.launch {
                        runCatching { dspManager.reapplyAfterEngineRecreated() }
                            .onFailure { Log.w(TAG, "DSP state reapply failed", it) }
                    }
                }
            }
            .launchIn(scope)
    }

    fun setEngineActive(active: Boolean) {
        runCatching { systemEq.setEngineActive(active) }
            .onFailure { Log.w(TAG, "system-wide AutoEQ gate failed", it) }
        if (active) {
            scope.launch {
                if (!preferences.dspEnabled.first()) {
                    preferences.setDspEnabled(true)
                }
            }
        }
    }

    private fun applyEqSettings(cfg: EqApply) {
        runCatching {
            if (cfg.systemWide) {
                autoEq.applyBands(emptyList(), 0f, false)
                return
            }
            fun decode(bandsJson: String?): List<EqBand> =
                if (cfg.enabled && !bandsJson.isNullOrEmpty()) {
                    json.decodeFromString(bandsJson)
                } else {
                    emptyList()
                }
            val autoL = decode(cfg.bandsJson)

            val autoR = if (cfg.stereo) decode(cfg.bandsRJson).ifEmpty { autoL } else autoL

            val toneBands = cfg.tone.toBands()
            val bandsL = autoL + toneBands
            val bandsR = autoR + toneBands
            val preamp = if (cfg.enabled) cfg.preamp else 0.0
            val active = bandsL.any { it.enabled } || bandsR.any { it.enabled }
            autoEq.applyBands(bandsL, bandsR, preamp.toFloat(), active)
        }
    }

    private fun applyParametricEqSettings(enabled: Boolean, bandsJson: String?, preamp: Double) {
        runCatching {
            val bands = if (!bandsJson.isNullOrEmpty()) {
                json.decodeFromString<List<EqBand>>(bandsJson)
            } else {
                emptyList()
            }
            paramEq.applyBands(bands, preamp.toFloat(), enabled)
        }
    }

    private data class EqCore(
        val enabled: Boolean,
        val bandsJson: String?,
        val bandsRJson: String?,
        val stereo: Boolean,
        val preamp: Double,
    )

    private data class EqApply(
        val enabled: Boolean,
        val bandsJson: String?,
        val bandsRJson: String?,
        val stereo: Boolean,
        val preamp: Double,
        val tone: ToneControls,
        val systemWide: Boolean,
    )

    private companion object {
        const val TAG = "TryptifyEngine"
    }
}
