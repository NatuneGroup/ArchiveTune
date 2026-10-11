package tf.monochrome.android.audio.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class LibusbUacDriver @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _isOpen = MutableStateFlow(false)
    val isOpen: StateFlow<Boolean> = _isOpen.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _lastStartError = MutableStateFlow<StartFailure?>(null)
    val lastStartError: StateFlow<StartFailure?> = _lastStartError.asStateFlow()

    private val _diagnostics = MutableStateFlow<BypassDiagnostics?>(null)
    val diagnostics: StateFlow<BypassDiagnostics?> = _diagnostics.asStateFlow()

    private val _supportedRates = MutableStateFlow<List<ClockRateRange>>(emptyList())
    val supportedRates: StateFlow<List<ClockRateRange>> = _supportedRates.asStateFlow()

    private val _device = MutableStateFlow<UsbDevice?>(null)
    val device: StateFlow<UsbDevice?> = _device.asStateFlow()

    private val _dacInfo = MutableStateFlow<DacInfo?>(null)
    val dacInfo: StateFlow<DacInfo?> = _dacInfo.asStateFlow()

    private var connection: UsbDeviceConnection? = null

    init {
        UsbNativeLoader.ensureLoaded()
        nativeInit()
    }

    suspend fun requestPermission(device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) return true
        return suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action != ACTION_USB_PERMISSION) return
                    val granted = intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED, false
                    )
                    runCatching { ctx.unregisterReceiver(this) }
                    if (cont.isActive) cont.resume(granted)
                }
            }

            val filter = IntentFilter(ACTION_USB_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                appContext.registerReceiver(receiver, filter)
            }
            cont.invokeOnCancellation {
                runCatching { appContext.unregisterReceiver(receiver) }
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi = PendingIntent.getBroadcast(
                appContext, 0, Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName), flags
            )
            usbManager.requestPermission(device, pi)
        }
    }

    fun open(device: UsbDevice): Boolean {
        if (!usbManager.hasPermission(device)) {
            Log.w(TAG, "open() called without permission for $device")
            return false
        }
        val conn = usbManager.openDevice(device) ?: run {
            Log.e(TAG, "UsbManager.openDevice returned null for $device")
            return false
        }
        val fd = conn.fileDescriptor
        if (fd < 0) {
            Log.e(TAG, "UsbDeviceConnection has invalid fd")
            conn.close()
            return false
        }
        val ok = nativeOpen(fd)
        if (!ok) {
            conn.close()
            return false
        }
        connection = conn
        _device.value = device
        _dacInfo.value = DacInfo.fromDevice(device)
        _isOpen.value = true
        return true
    }

    fun close() {
        if (connection == null && !nativeIsOpen()) return
        nativeClose()
        connection?.close()
        connection = null
        _device.value = null
        _dacInfo.value = null
        _isOpen.value = false

        _isStreaming.value = false
    }

    fun start(sampleRate: Int, bitsPerSample: Int, channels: Int): Boolean {
        val ok = nativeStart(sampleRate, bitsPerSample, channels)
        _isStreaming.value = ok

        _supportedRates.value = ClockRateRange.decodeAll(nativeSupportedRates())
        if (ok) {
            _diagnostics.value = BypassDiagnostics.fromLongArray(nativeActiveStream())
            _lastStartError.value = null
        } else {
            _diagnostics.value = null
            val code = StartError.fromCode(nativeLastErrorCode())
            val detail = nativeLastErrorDetail().orEmpty()
            _lastStartError.value = StartFailure(code, detail)
        }
        return ok
    }

    fun stop() {
        nativeStop()
        _isStreaming.value = false
        _diagnostics.value = null
        _lastStartError.value = null
    }

    fun flushRing() = nativeFlushRing()

    fun isStreamingFormat(sampleRate: Int, bitsPerSample: Int, channels: Int): Boolean =
        nativeIsStreamingFormat(sampleRate, bitsPerSample, channels)

    fun nativeStreaming(): Boolean = nativeIsStreaming()

    fun writableFrames(): Int = nativeWritableFrames()

    fun playedFrames(): Long = nativePlayedFrames()

    fun pendingFrames(): Long = nativePendingFrames()

    fun write(buffer: ByteBuffer, frames: Int): Int {
        if (!buffer.isDirect) {
            Log.w(TAG, "write: non-direct ByteBuffer — caller must copy to a direct buffer first")
            return 0
        }
        return nativeWrite(buffer, buffer.position(), frames)
    }

    private external fun nativeInit(): Boolean
    private external fun nativeOpen(fd: Int): Boolean
    private external fun nativeClose()
    private external fun nativeIsOpen(): Boolean
    private external fun nativeStart(sampleRate: Int, bitsPerSample: Int, channels: Int): Boolean
    private external fun nativeStop()
    private external fun nativeFlushRing()
    private external fun nativeIsStreaming(): Boolean
    private external fun nativeIsStreamingFormat(sampleRate: Int, bitsPerSample: Int, channels: Int): Boolean
    private external fun nativeWrite(buffer: ByteBuffer, byteOffset: Int, frames: Int): Int
    private external fun nativeWritableFrames(): Int
    private external fun nativePlayedFrames(): Long
    private external fun nativePendingFrames(): Long

    private external fun nativeLastErrorCode(): Int

    private external fun nativeLastErrorDetail(): String?

    private external fun nativeSupportedRates(): IntArray?

    private external fun nativeActiveStream(): LongArray?

    companion object {
        private const val TAG = "LibusbUacDriver"
        const val ACTION_USB_PERMISSION = "tf.monochrome.android.USB_PERMISSION"
    }
}
