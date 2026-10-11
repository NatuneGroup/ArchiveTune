package moe.rukamori.archivetune.playback.dsp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import tf.monochrome.android.audio.usb.BypassDiagnostics

object EngineRuntime {
    @Volatile
    var activeEngine: AudioEngineRouterProcessor.Engine = AudioEngineRouterProcessor.Engine.NONE

    @Volatile
    var outputFloat: Boolean = false

    @Volatile
    var usbExclusiveActive: Boolean = false

    @Volatile
    var tryptifyUsbStream: BypassDiagnostics? = null

    @Volatile
    var lastwaveUsbRateHz: Int = 0

    @Volatile
    var lastwaveUsbBitsPerSample: Int = 0

    @Volatile
    var tryptifyUsbPinActive: Boolean = false

    @Volatile
    var lastwaveMixerBitPerfectActive: Boolean = false

    @Volatile
    var bitPerfectSinkRouteActive: Boolean = false

    @Volatile
    var sinkDecodedEncoding: Int = C.ENCODING_PCM_16BIT

    var activeEngineState by mutableStateOf(AudioEngineRouterProcessor.Engine.NONE)
        private set

    var wantedEngineState by mutableStateOf(AudioEngineRouterProcessor.Engine.NONE)
        private set

    var tryptifyAvailableState by mutableStateOf(false)
        private set

    var lastwaveAvailableState by mutableStateOf(false)
        private set

    var outputFloatState by mutableStateOf(false)
        private set

    var revision by mutableStateOf(0)
        private set

    fun revisionBump() {
        revision++
    }

    fun publishActiveEngine(engine: AudioEngineRouterProcessor.Engine) {
        activeEngine = engine
        activeEngineState = engine
        revision++
    }

    fun publishWantedEngine(engine: AudioEngineRouterProcessor.Engine) {
        wantedEngineState = engine
        revision++
    }

    fun publishEngineAvailability(tryptify: Boolean, lastwave: Boolean) {
        tryptifyAvailableState = tryptify
        lastwaveAvailableState = lastwave
        revision++
    }

    fun publishOutputFloat(float: Boolean) {
        outputFloat = float
        outputFloatState = float
        revision++
    }

    fun publishUsbExclusive(active: Boolean) {
        usbExclusiveActive = active
        revision++
    }

    fun publishTryptifyUsbPin(active: Boolean) {
        tryptifyUsbPinActive = active
        revision++
    }

    fun publishLastwaveMixerBitPerfect(active: Boolean) {
        lastwaveMixerBitPerfectActive = active
        revision++
    }

    fun publishSinkRoute(floatRoute: Boolean, decodedEncoding: Int) {
        bitPerfectSinkRouteActive = floatRoute
        sinkDecodedEncoding = decodedEncoding
        revision++
    }

    fun publishUsbWire(rateHz: Int, bits: Int) {
        lastwaveUsbRateHz = rateHz
        lastwaveUsbBitsPerSample = bits
        revision++
    }
}
