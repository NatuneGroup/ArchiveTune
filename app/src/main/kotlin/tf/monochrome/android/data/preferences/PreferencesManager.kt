

package tf.monochrome.android.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import moe.rukamori.archivetune.utils.dataStore
import tf.monochrome.android.domain.model.ToneControls

class PreferencesManager(
    context: Context,
) {
    private val dataStore = context.applicationContext.dataStore
    private val json = Json { ignoreUnknownKeys = true }

    val eqTutorialSeen: Flow<Boolean> = dataStore.data.map { it[EQ_TUTORIAL_SEEN] ?: false }
    suspend fun setEqTutorialSeen(seen: Boolean) {
        dataStore.edit { it[EQ_TUTORIAL_SEEN] = seen }
    }

    val eqEnabled: Flow<Boolean> = dataStore.data.map { it[EQ_ENABLED] ?: false }
    suspend fun setEqEnabled(enabled: Boolean) {
        dataStore.edit { it[EQ_ENABLED] = enabled }
    }

    val eqActivePresetId: Flow<String?> = dataStore.data.map { it[EQ_ACTIVE_PRESET_ID] }
    suspend fun setEqActivePreset(presetId: String?) {
        dataStore.edit {
            if (presetId != null) it[EQ_ACTIVE_PRESET_ID] = presetId else it.remove(EQ_ACTIVE_PRESET_ID)
        }
    }

    val eqTargetId: Flow<String> = dataStore.data.map { it[EQ_TARGET_ID] ?: "harman_oe_2018" }
    suspend fun setEqTarget(targetId: String) {
        dataStore.edit { it[EQ_TARGET_ID] = targetId }
    }

    val eqPreamp: Flow<Double> = dataStore.data.map { it[EQ_PREAMP] ?: 0.0 }
    suspend fun setEqPreamp(preamp: Double) {
        dataStore.edit { it[EQ_PREAMP] = preamp }
    }

    val eqAutoPreamp: Flow<Boolean> = dataStore.data.map { it[EQ_AUTO_PREAMP] ?: false }
    suspend fun setEqAutoPreamp(enabled: Boolean) {
        dataStore.edit { it[EQ_AUTO_PREAMP] = enabled }
    }

    val eqBandsJson: Flow<String?> = dataStore.data.map { it[EQ_BANDS_JSON] }
    suspend fun setEqBands(bandsJson: String?) {
        dataStore.edit {
            if (bandsJson != null) it[EQ_BANDS_JSON] = bandsJson else it.remove(EQ_BANDS_JSON)
        }
    }

    val eqBandsRJson: Flow<String?> = dataStore.data.map { it[EQ_BANDS_R_JSON] }
    suspend fun setEqBandsR(bandsJson: String?) {
        dataStore.edit {
            if (bandsJson != null) it[EQ_BANDS_R_JSON] = bandsJson else it.remove(EQ_BANDS_R_JSON)
        }
    }

    val eqStereoMode: Flow<Boolean> = dataStore.data.map { it[EQ_STEREO_MODE] ?: false }
    suspend fun setEqStereoMode(enabled: Boolean) {
        dataStore.edit { it[EQ_STEREO_MODE] = enabled }
    }

    val eqCustomTargetsJson: Flow<String> = dataStore.data.map { it[EQ_CUSTOM_TARGETS_JSON] ?: "[]" }
    suspend fun setEqCustomTargets(json: String) {
        dataStore.edit { it[EQ_CUSTOM_TARGETS_JSON] = json }
    }

    val eqSelectedHeadphoneId: Flow<String?> = dataStore.data.map { it[EQ_SELECTED_HEADPHONE_ID] }
    val eqSelectedHeadphoneName: Flow<String?> = dataStore.data.map { it[EQ_SELECTED_HEADPHONE_NAME] }
    suspend fun setEqSelectedHeadphone(id: String, name: String) {
        dataStore.edit {
            it[EQ_SELECTED_HEADPHONE_ID] = id
            it[EQ_SELECTED_HEADPHONE_NAME] = name
        }
    }

    suspend fun clearEqSelectedHeadphone() {
        dataStore.edit {
            it.remove(EQ_SELECTED_HEADPHONE_ID)
            it.remove(EQ_SELECTED_HEADPHONE_NAME)
        }
    }

    val eqMeasurementJson: Flow<String?> = dataStore.data.map { it[EQ_MEASUREMENT_JSON] }
    suspend fun setEqMeasurementJson(json: String?) {
        dataStore.edit {
            if (json != null) it[EQ_MEASUREMENT_JSON] = json else it.remove(EQ_MEASUREMENT_JSON)
        }
    }

    val eqMeasurementRJson: Flow<String?> = dataStore.data.map { it[EQ_MEASUREMENT_R_JSON] }
    suspend fun setEqMeasurementRJson(json: String?) {
        dataStore.edit {
            if (json != null) it[EQ_MEASUREMENT_R_JSON] = json else it.remove(EQ_MEASUREMENT_R_JSON)
        }
    }

    val eqUploadedHeadphonesJson: Flow<String> =
        dataStore.data.map { it[EQ_UPLOADED_HEADPHONES_JSON] ?: "[]" }
    suspend fun setEqUploadedHeadphonesJson(json: String) {
        dataStore.edit { it[EQ_UPLOADED_HEADPHONES_JSON] = json }
    }

    val systemWideAutoEqEnabled: Flow<Boolean> =
        dataStore.data.map { it[SYSTEM_WIDE_AUTOEQ_ENABLED] ?: false }
    suspend fun setSystemWideAutoEqEnabled(enabled: Boolean) {
        dataStore.edit { it[SYSTEM_WIDE_AUTOEQ_ENABLED] = enabled }
    }

    val systemToneControls: Flow<ToneControls> = dataStore.data
        .map { it[SYSTEM_TONE_CONTROLS_JSON] }
        .distinctUntilChanged()
        .map { raw ->
            raw?.let { jsonStr ->
                runCatching { json.decodeFromString<ToneControls>(jsonStr).clamped() }
                    .getOrDefault(ToneControls.DEFAULT)
            } ?: ToneControls.DEFAULT
        }
    suspend fun setSystemToneControls(controls: ToneControls) {
        dataStore.edit {
            it[SYSTEM_TONE_CONTROLS_JSON] = json.encodeToString(controls.clamped())
        }
    }

    val paramEqEnabled: Flow<Boolean> = dataStore.data.map { it[PARAM_EQ_ENABLED] ?: false }
    suspend fun setParamEqEnabled(enabled: Boolean) {
        dataStore.edit { it[PARAM_EQ_ENABLED] = enabled }
    }

    val paramEqActivePresetId: Flow<String?> = dataStore.data.map { it[PARAM_EQ_ACTIVE_PRESET_ID] }
    suspend fun setParamEqActivePreset(presetId: String?) {
        dataStore.edit {
            if (presetId != null) it[PARAM_EQ_ACTIVE_PRESET_ID] = presetId
            else it.remove(PARAM_EQ_ACTIVE_PRESET_ID)
        }
    }

    val paramEqPreamp: Flow<Double> = dataStore.data.map { it[PARAM_EQ_PREAMP] ?: 0.0 }
    suspend fun setParamEqPreamp(preamp: Double) {
        dataStore.edit { it[PARAM_EQ_PREAMP] = preamp }
    }

    val paramEqBandsJson: Flow<String?> = dataStore.data.map { it[PARAM_EQ_BANDS_JSON] }
    suspend fun setParamEqBands(bandsJson: String?) {
        dataStore.edit {
            if (bandsJson != null) it[PARAM_EQ_BANDS_JSON] = bandsJson else it.remove(PARAM_EQ_BANDS_JSON)
        }
    }

    val dspEnabled: Flow<Boolean> = dataStore.data.map { it[DSP_ENABLED] ?: false }
    suspend fun setDspEnabled(enabled: Boolean) {
        dataStore.edit { it[DSP_ENABLED] = enabled }
    }

    val dspStateJson: Flow<String?> = dataStore.data.map { it[DSP_STATE_JSON] }
    suspend fun setDspStateJson(json: String?) {
        dataStore.edit {
            if (json != null) it[DSP_STATE_JSON] = json else it.remove(DSP_STATE_JSON)
        }
    }

    val dspBlockSize: Flow<Int> = dataStore.data.map { prefs ->
        val v = prefs[DSP_BLOCK_SIZE] ?: 1024
        if (v in DSP_BLOCK_SIZES) v else 1024
    }
    suspend fun setDspBlockSize(value: Int) {
        if (value !in DSP_BLOCK_SIZES) return
        dataStore.edit { it[DSP_BLOCK_SIZE] = value }
    }

    val usbExclusiveBitPerfectEnabled: Flow<Boolean> =
        dataStore.data.map { it[USB_EXCLUSIVE_BIT_PERFECT_ENABLED] ?: false }
    suspend fun setUsbExclusiveBitPerfectEnabled(enabled: Boolean) {
        dataStore.edit { it[USB_EXCLUSIVE_BIT_PERFECT_ENABLED] = enabled }
    }

    val usbBitPerfectEnabled: Flow<Boolean> =
        dataStore.data.map { it[USB_BIT_PERFECT_ENABLED] ?: false }
    suspend fun setUsbBitPerfectEnabled(enabled: Boolean) {
        dataStore.edit { it[USB_BIT_PERFECT_ENABLED] = enabled }
    }

    val multichannelDownmixEnabled: Flow<Boolean> =
        dataStore.data.map { it[MULTICHANNEL_DOWNMIX_ENABLED] ?: true }
    suspend fun setMultichannelDownmixEnabled(enabled: Boolean) {
        dataStore.edit { it[MULTICHANNEL_DOWNMIX_ENABLED] = enabled }
    }

    val spectrumAnalyzerEnabled: Flow<Boolean> =
        dataStore.data.map { it[SPECTRUM_ANALYZER_ENABLED] ?: true }
    suspend fun setSpectrumAnalyzerEnabled(enabled: Boolean) {
        dataStore.edit { it[SPECTRUM_ANALYZER_ENABLED] = enabled }
    }

    val spectrumShowOnNowPlaying: Flow<Boolean> =
        dataStore.data.map { it[SPECTRUM_SHOW_ON_NOW_PLAYING] ?: true }
    suspend fun setSpectrumShowOnNowPlaying(enabled: Boolean) {
        dataStore.edit { it[SPECTRUM_SHOW_ON_NOW_PLAYING] = enabled }
    }

    val spectrumFftSize: Flow<Int> = dataStore.data.map {
        it[SPECTRUM_FFT_SIZE] ?: 8192
    }
    suspend fun setSpectrumFftSize(size: Int) {
        val clamped = when {
            size <= 4096 -> 4096
            size <= 8192 -> 8192
            else -> 16384
        }
        dataStore.edit { it[SPECTRUM_FFT_SIZE] = clamped }
    }

    companion object {
        val DSP_BLOCK_SIZES = listOf(128, 256, 512, 1024, 2048, 4096, 8192, 16384)

        private val EQ_TUTORIAL_SEEN = booleanPreferencesKey("eq_tutorial_seen")
        private val EQ_ENABLED = booleanPreferencesKey("eq_enabled")
        private val EQ_ACTIVE_PRESET_ID = stringPreferencesKey("eq_active_preset_id")
        private val EQ_TARGET_ID = stringPreferencesKey("eq_target_id")
        private val EQ_PREAMP = doublePreferencesKey("eq_preamp")
        private val EQ_BANDS_JSON = stringPreferencesKey("eq_bands_json")
        private val EQ_BANDS_R_JSON = stringPreferencesKey("eq_bands_r_json")
        private val EQ_STEREO_MODE = booleanPreferencesKey("eq_stereo_mode")
        private val EQ_MEASUREMENT_R_JSON = stringPreferencesKey("eq_measurement_r_json")
        private val EQ_CUSTOM_TARGETS_JSON = stringPreferencesKey("eq_custom_targets_json")
        private val EQ_SELECTED_HEADPHONE_ID = stringPreferencesKey("eq_selected_headphone_id")
        private val EQ_SELECTED_HEADPHONE_NAME = stringPreferencesKey("eq_selected_headphone_name")
        private val EQ_MEASUREMENT_JSON = stringPreferencesKey("eq_measurement_json")
        private val EQ_UPLOADED_HEADPHONES_JSON = stringPreferencesKey("eq_uploaded_headphones_json")
        private val EQ_AUTO_PREAMP = booleanPreferencesKey("eq_auto_preamp")
        private val SYSTEM_WIDE_AUTOEQ_ENABLED = booleanPreferencesKey("system_wide_autoeq_enabled")
        private val SYSTEM_TONE_CONTROLS_JSON = stringPreferencesKey("system_tone_controls_json")
        private val PARAM_EQ_ENABLED = booleanPreferencesKey("param_eq_enabled")
        private val PARAM_EQ_ACTIVE_PRESET_ID = stringPreferencesKey("param_eq_active_preset_id")
        private val PARAM_EQ_PREAMP = doublePreferencesKey("param_eq_preamp")
        private val PARAM_EQ_BANDS_JSON = stringPreferencesKey("param_eq_bands_json")
        private val DSP_ENABLED = booleanPreferencesKey("dsp_enabled")
        private val DSP_STATE_JSON = stringPreferencesKey("dsp_state_json")
        private val DSP_BLOCK_SIZE = intPreferencesKey("dsp_block_size")
        private val USB_EXCLUSIVE_BIT_PERFECT_ENABLED =
            booleanPreferencesKey("usb_exclusive_bit_perfect_enabled")
        private val USB_BIT_PERFECT_ENABLED = booleanPreferencesKey("usb_bit_perfect_enabled")
        private val MULTICHANNEL_DOWNMIX_ENABLED =
            booleanPreferencesKey("multichannel_downmix_enabled")
        private val SPECTRUM_ANALYZER_ENABLED = booleanPreferencesKey("spectrum_analyzer_enabled")
        private val SPECTRUM_SHOW_ON_NOW_PLAYING = booleanPreferencesKey("spectrum_show_on_now_playing")
        private val SPECTRUM_FFT_SIZE = intPreferencesKey("spectrum_fft_size")
    }
}
