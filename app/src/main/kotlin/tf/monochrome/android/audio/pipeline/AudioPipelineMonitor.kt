/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © 4nx3b — github.com/4nx3b (audio pipeline inspector)
 */

package tf.monochrome.android.audio.pipeline

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioPipelineMonitor @Inject constructor() {
    private val _stream = MutableStateFlow<DecodedStream?>(null)
    val stream: StateFlow<DecodedStream?> = _stream.asStateFlow()

    private val _decoderName = MutableStateFlow<String?>(null)
    val decoderName: StateFlow<String?> = _decoderName.asStateFlow()

    fun onStreamFormat(stream: DecodedStream) {
        _stream.value = stream
    }

    fun onDecoderInitialized(name: String) {
        _decoderName.value = name.takeIf { it.isNotBlank() }
    }

    fun onDecoderReleased() {
        _decoderName.value = null
    }

    fun onIdle() {
        _stream.value = null
        _decoderName.value = null
    }
}
