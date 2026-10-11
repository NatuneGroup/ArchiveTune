package moe.rukamori.archivetune.playback.dsp

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object EnginePcmCodec {
    fun encode(
        data: ByteBuffer,
        dataEncoding: Int,
        outputEncoding: Int,
        channels: Int,
    ): ByteBuffer = when {
        dataEncoding == outputEncoding -> data
        outputEncoding == C.ENCODING_PCM_FLOAT && dataEncoding == C.ENCODING_PCM_16BIT ->
            pcm16ToFloat(data, channels)
        outputEncoding == C.ENCODING_PCM_16BIT && dataEncoding == C.ENCODING_PCM_FLOAT ->
            floatToPcm16(data, channels)
        else -> data
    }

    fun floatToPcm16(input: ByteBuffer, channels: Int): ByteBuffer {
        val frames = input.remaining() / (4 * channels)
        val samples = frames * channels
        if (downconvertScratch.capacity() < samples * 2) {
            downconvertScratch = ByteBuffer.allocateDirect(samples * 2)
                .order(ByteOrder.nativeOrder())
        } else {
            downconvertScratch.clear()
        }
        val srcPos = input.position()
        for (i in 0 until samples) {
            val f = input.getFloat(srcPos + (i shl 2))
            val clamped = if (f > 1f) 1f else if (f < -1f) -1f else f
            downconvertScratch.putShort(i shl 1, (clamped * 32767f).toInt().toShort())
        }
        downconvertScratch.position(0)
        downconvertScratch.limit(samples * 2)
        return downconvertScratch
    }

    fun pcm16ToFloat(input: ByteBuffer, channels: Int): ByteBuffer {
        val frames = input.remaining() / (2 * channels)
        val samples = frames * channels
        if (widenScratch.capacity() < samples * 4) {
            widenScratch = ByteBuffer.allocateDirect(samples * 4)
                .order(ByteOrder.nativeOrder())
        } else {
            widenScratch.clear()
        }
        val srcPos = input.position()
        for (i in 0 until samples) {
            val s = input.getShort(srcPos + (i shl 1))
            widenScratch.putFloat(i shl 2, s / 32768f)
        }
        widenScratch.position(0)
        widenScratch.limit(samples * 4)
        return widenScratch
    }

    private var downconvertScratch: ByteBuffer = ByteBuffer.allocateDirect(0)
        .order(ByteOrder.nativeOrder())

    private var widenScratch: ByteBuffer = ByteBuffer.allocateDirect(0)
        .order(ByteOrder.nativeOrder())
}
