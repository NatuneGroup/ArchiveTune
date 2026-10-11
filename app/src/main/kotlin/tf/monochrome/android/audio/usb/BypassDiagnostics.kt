package tf.monochrome.android.audio.usb

data class BypassDiagnostics(
    val sampleRateHz: Int,
    val bitsPerSample: Int,
    val channels: Int,
    val interfaceNumber: Int,
    val altSetting: Int,
    val endpointAddress: Int,
    val maxPacketSize: Int,

    val bInterval: Int,

    val uacVersion: Int,

    val clockSourceId: Int,

    val feedbackEndpointAddress: Int,
    val isHighSpeed: Boolean,
    val bytesPerSample: Int,
) {
    val hasFeedbackEndpoint: Boolean get() = feedbackEndpointAddress != 0
    val isUac2: Boolean get() = uacVersion >= 0x0200

    fun rateLabel(): String {
        val hz = sampleRateHz
        return when {
            hz <= 0 -> "—"
            hz % 1000 == 0 -> "${hz / 1000} kHz"
            else -> "%.1f kHz".format(hz / 1000.0)
        }
    }

    fun speedLabel(): String =
        if (isHighSpeed) "USB 2.0 HS" else "USB 1.1 FS"

    fun uacLabel(): String = if (isUac2) "UAC2" else "UAC1"

    companion object {
        private const val FIELD_COUNT = 13

        fun fromLongArray(packed: LongArray?): BypassDiagnostics? {
            if (packed == null || packed.size < FIELD_COUNT) return null
            return BypassDiagnostics(
                sampleRateHz = packed[0].toInt(),
                bitsPerSample = packed[1].toInt(),
                channels = packed[2].toInt(),
                interfaceNumber = packed[3].toInt(),
                altSetting = packed[4].toInt(),
                endpointAddress = packed[5].toInt(),
                maxPacketSize = packed[6].toInt(),
                bInterval = packed[7].toInt(),
                uacVersion = packed[8].toInt(),
                clockSourceId = packed[9].toInt(),
                feedbackEndpointAddress = packed[10].toInt(),
                isHighSpeed = packed[11] != 0L,
                bytesPerSample = packed[12].toInt(),
            )
        }
    }
}

data class ClockRateRange(
    val clockId: Int,
    val minHz: Int,
    val maxHz: Int,
    val resHz: Int,
) {
    val isDiscrete: Boolean get() = minHz == maxHz

    fun label(): String {
        fun fmt(hz: Int) = when {
            hz <= 0 -> "—"
            hz % 1000 == 0 -> "${hz / 1000} kHz"
            else -> "%.1f kHz".format(hz / 1000.0)
        }
        return if (isDiscrete) fmt(minHz)
        else "${fmt(minHz)}–${fmt(maxHz)}"
    }

    companion object {
        fun decodeAll(packed: IntArray?): List<ClockRateRange> {
            if (packed == null || packed.size < 4) return emptyList()
            val out = ArrayList<ClockRateRange>(packed.size / 4)
            var i = 0
            while (i + 4 <= packed.size) {
                out.add(
                    ClockRateRange(
                        clockId = packed[i],
                        minHz = packed[i + 1],
                        maxHz = packed[i + 2],
                        resHz = packed[i + 3],
                    )
                )
                i += 4
            }
            return out
        }
    }
}

enum class StartError(val code: Int) {
    Ok(0),

    NoDevice(1),

    NoMatchingAlt(2),

    ClaimInterfaceFailed(3),

    SetAltFailed(4),

    SetSampleRateFailed(5),

    IsoPumpAllocFailed(6),

    IsoPumpSubmitFailed(7);

    companion object {
        fun fromCode(c: Int): StartError = entries.firstOrNull { it.code == c } ?: Ok
    }
}

data class StartFailure(
    val code: StartError,
    val detail: String,
) {
    fun actionableMessage(): String = when (code) {
        StartError.Ok ->
            ""
        StartError.NoDevice ->
            "DAC handle isn't open yet — re-toggle Exclusive USB DAC " +
            "after the DAC is plugged in."
        StartError.NoMatchingAlt ->
            "Your DAC doesn't advertise this track's sample rate at " +
            "this bit depth. Try a track at a rate the DAC supports " +
            "(see the supported-rates list below), or fall back to " +
            "the framework router which will resample."
        StartError.ClaimInterfaceFailed ->
            "Android's audio HAL still owns the streaming interface. " +
            "Turn ON Developer Options → Disable USB audio routing, " +
            "then re-toggle Exclusive USB DAC. If the framework " +
            "routing toggle (above) is on, turn that off too — they " +
            "fight each other for the DAC."
        StartError.SetAltFailed ->
            "USB negotiation failed mid-handshake. Unplug and re-plug " +
            "the DAC, then re-toggle Exclusive USB DAC."
        StartError.SetSampleRateFailed ->
            "Your DAC's clock won't accept this rate. It probably runs " +
            "at a fixed hardware clock. Try a track at the DAC's " +
            "native rate (see supported rates below)."
        StartError.IsoPumpAllocFailed ->
            "Couldn't allocate USB transfers (memory pressure?). Close " +
            "background apps and try again."
        StartError.IsoPumpSubmitFailed ->
            "USB transfer submission failed — the device may have " +
            "been unplugged or the kernel reclaimed the interface. " +
            "Re-plug the DAC and re-toggle."
    }
}
