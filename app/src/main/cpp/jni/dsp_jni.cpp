/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 *
 * JNI bridges for the 32-bit float DSP and the AAudio exclusive output.
 *
 * Kotlin hosts:
 *  - moe.rukamori.archivetune.playback.dsp.FloatDsp        (object, static methods)
 *  - moe.rukamori.archivetune.playback.dsp.AaudioNativeStream (instance, long handle)
 *
 * Buffer convention: media3 audio processors hand out DIRECT ByteBuffers
 * (little-endian PCM); every buffer argument here must be direct so the
 * address can be taken without a copy.
 */

#include <jni.h>

#include <cstring>
#include <memory>

#include "dsp/float_dsp.h"
#include "dsp/aaudio_output.h"

#define JNI_FUNC_FLOATDSP(name) \
    Java_moe_rukamori_archivetune_playback_dsp_FloatDsp_##name
#define JNI_FUNC_AASTREAM(name) \
    Java_moe_rukamori_archivetune_playback_dsp_AaudioNativeStream_##name

using archivetune::dsp::FloatDsp;
using archivetune::dsp::AaudioExclusiveStream;

namespace {

// Every JNI body routes through this guard so a std::bad_alloc (or anything
// else that unwinds) never crosses the JNI frame — an exception escaping a
// native frame is a hard abort with no Java-side log (the same convention as
// the analyzer bridges).

inline FloatDsp* dspOf(jlong handle) {
    return reinterpret_cast<FloatDsp*>(static_cast<intptr_t>(handle));
}

inline AaudioExclusiveStream* streamOf(jlong handle) {
    return reinterpret_cast<AaudioExclusiveStream*>(static_cast<intptr_t>(handle));
}

float* directFloats(JNIEnv* env, jobject buffer, jint samples) {
    auto* ptr = static_cast<float*>(env->GetDirectBufferAddress(buffer));
    if (ptr == nullptr) return nullptr;
    if (env->GetDirectBufferCapacity(buffer) < static_cast<jlong>(samples) * 4) return nullptr;
    return ptr;
}
int16_t* directShorts(JNIEnv* env, jobject buffer, jint samples) {
    auto* ptr = static_cast<int16_t*>(env->GetDirectBufferAddress(buffer));
    if (ptr == nullptr) return nullptr;
    if (env->GetDirectBufferCapacity(buffer) < static_cast<jlong>(samples) * 2) return nullptr;
    return ptr;
}

}  // namespace

// ---------------------------------------------------------------------------
// FloatDsp (Kotlin object — static natives)
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jlong JNICALL JNI_FUNC_FLOATDSP(nativeCreate)(
    JNIEnv* env, jclass, jint sampleRate, jint channels) {
    try {
        return reinterpret_cast<jlong>(new FloatDsp(sampleRate, channels));
    } catch (...) {
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL JNI_FUNC_FLOATDSP(nativeRelease)(
    JNIEnv*, jclass, jlong handle) {
    delete dspOf(handle);
}

extern "C" JNIEXPORT void JNICALL JNI_FUNC_FLOATDSP(nativeSetEngaged)(
    JNIEnv*, jclass, jlong handle, jboolean engaged) {
    if (FloatDsp* dsp = dspOf(handle)) {
        dsp->setEngaged(engaged != JNI_FALSE);
    }
}

extern "C" JNIEXPORT void JNICALL JNI_FUNC_FLOATDSP(nativeReset)(
    JNIEnv*, jclass, jlong handle) {
    if (FloatDsp* dsp = dspOf(handle)) {
        dsp->reset();
    }
}

extern "C" JNIEXPORT void JNICALL JNI_FUNC_FLOATDSP(nativeSetLimiterCeiling)(
    JNIEnv*, jclass, jlong handle, jfloat ceilingDb) {
    if (FloatDsp* dsp = dspOf(handle)) {
        dsp->setLimiterCeilingDb(ceilingDb);
    }
}

// 16-bit in (window starts at inOffsetBytes) -> float out. frames*channels
// samples on both sides.
extern "C" JNIEXPORT void JNICALL JNI_FUNC_FLOATDSP(nativeProcessShortToFloat)(
    JNIEnv* env, jclass, jlong handle, jobject in, jint inOffsetBytes, jobject out,
    jint frames, jint channels) {
    try {
        FloatDsp* dsp = dspOf(handle);
        const jint samples = frames * channels;
        auto* inBase = static_cast<uint8_t*>(env->GetDirectBufferAddress(in));
        auto* outPtr = directFloats(env, out, samples);
        if (dsp == nullptr || inBase == nullptr || outPtr == nullptr) return;
        const auto* inPtr = reinterpret_cast<const int16_t*>(inBase + inOffsetBytes);
        for (jint i = 0; i < samples; ++i) {
            outPtr[i] = static_cast<float>(inPtr[i]) / 32768.0f;
        }
        dsp->process(outPtr, frames, channels);
    } catch (...) {
    }
}

// float in (window starts at inOffsetBytes) -> float out.
extern "C" JNIEXPORT void JNICALL JNI_FUNC_FLOATDSP(nativeProcessFloatToFloat)(
    JNIEnv* env, jclass, jlong handle, jobject in, jint inOffsetBytes, jobject out,
    jint frames, jint channels) {
    try {
        FloatDsp* dsp = dspOf(handle);
        const jint samples = frames * channels;
        auto* inBase = static_cast<uint8_t*>(env->GetDirectBufferAddress(in));
        auto* outPtr = directFloats(env, out, samples);
        if (dsp == nullptr || inBase == nullptr || outPtr == nullptr) return;
        const auto* inPtr = reinterpret_cast<const float*>(inBase + inOffsetBytes);
        std::memcpy(outPtr, inPtr, static_cast<size_t>(samples) * sizeof(float));
        dsp->process(outPtr, frames, channels);
    } catch (...) {
    }
}

// 16-bit in (window starts at inOffsetBytes) -> dithered 16-bit out (the
// default-sink path).
extern "C" JNIEXPORT void JNICALL JNI_FUNC_FLOATDSP(nativeProcessShortToShort)(
    JNIEnv* env, jclass, jlong handle, jobject in, jint inOffsetBytes, jobject out,
    jint frames, jint channels) {
    try {
        FloatDsp* dsp = dspOf(handle);
        const jint samples = frames * channels;
        auto* inBase = static_cast<uint8_t*>(env->GetDirectBufferAddress(in));
        auto* outPtr = directShorts(env, out, samples);
        if (dsp == nullptr || inBase == nullptr || outPtr == nullptr) return;
        const auto* inPtr = reinterpret_cast<const int16_t*>(inBase + inOffsetBytes);
        // Convert up, process in 32-bit float, dither back down.
        std::vector<float> scratch(static_cast<size_t>(samples));
        for (jint i = 0; i < samples; ++i) {
            scratch[static_cast<size_t>(i)] = static_cast<float>(inPtr[i]) / 32768.0f;
        }
        dsp->process(scratch.data(), frames, channels);
        dsp->toShort(scratch.data(), outPtr, static_cast<size_t>(samples));
    } catch (...) {
    }
}

// ---------------------------------------------------------------------------
// AaudioNativeStream (Kotlin class — instance natives with a long handle)
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jlong JNICALL JNI_FUNC_AASTREAM(nativeOpen)(
    JNIEnv*, jobject, jint sampleRate, jint channels, jint deviceId,
    jboolean exclusive, jint usage, jint contentType, jint bufferCapacityFrames) {
    try {
        auto* stream = new AaudioExclusiveStream();
        const int32_t result = stream->open(
            sampleRate, channels, deviceId, exclusive != JNI_FALSE,
            usage, contentType, bufferCapacityFrames);
        if (result != 0 /* AAUDIO_OK */) {
            delete stream;
            return -(static_cast<jlong>(result));
        }
        return reinterpret_cast<jlong>(stream);
    } catch (...) {
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL JNI_FUNC_AASTREAM(nativeClose)(
    JNIEnv*, jobject, jlong handle) {
    AaudioExclusiveStream* stream = streamOf(handle);
    if (stream != nullptr) {
        stream->close();
    }
}

extern "C" JNIEXPORT void JNICALL JNI_FUNC_AASTREAM(nativeRelease)(
    JNIEnv*, jobject, jlong handle) {
    delete streamOf(handle);
}

extern "C" JNIEXPORT void JNICALL JNI_FUNC_AASTREAM(nativeStart)(
    JNIEnv*, jobject, jlong handle) {
    if (AaudioExclusiveStream* stream = streamOf(handle)) {
        stream->requestStart();
    }
}

extern "C" JNIEXPORT void JNICALL JNI_FUNC_AASTREAM(nativePause)(
    JNIEnv*, jobject, jlong handle) {
    if (AaudioExclusiveStream* stream = streamOf(handle)) {
        stream->requestPause();
    }
}

extern "C" JNIEXPORT void JNICALL JNI_FUNC_AASTREAM(nativeFlush)(
    JNIEnv*, jobject, jlong handle) {
    if (AaudioExclusiveStream* stream = streamOf(handle)) {
        stream->requestFlush();
    }
}

extern "C" JNIEXPORT void JNICALL JNI_FUNC_AASTREAM(nativeStop)(
    JNIEnv*, jobject, jlong handle) {
    if (AaudioExclusiveStream* stream = streamOf(handle)) {
        stream->requestStop();
    }
}

/**
 * Blocking write of a DIRECT PCM ByteBuffer's [position, limit) window,
 * scaled by volume. `isFloat` selects the encoding of the buffer's contents;
 * the AAudio stream itself is always float — 16-bit input is up-converted on
 * the way in. Returns frames written, or a negative AAudio error code.
 */
extern "C" JNIEXPORT jint JNICALL JNI_FUNC_AASTREAM(nativeWrite)(
    JNIEnv* env, jobject, jlong handle, jobject buffer, jint offsetBytes,
    jint frames, jint channels, jboolean isFloat, jfloat volume, jint timeoutMs) {
    try {
        AaudioExclusiveStream* stream = streamOf(handle);
        if (stream == nullptr) return -899 /* AAUDIO_ERROR_INVALID_STATE */;
        const jint samples = frames * channels;
        auto* base = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
        const jlong capacity = env->GetDirectBufferCapacity(buffer);
        if (base == nullptr ||
            capacity < static_cast<jlong>(offsetBytes) +
                           static_cast<jlong>(samples) * (isFloat != JNI_FALSE ? 4 : 2)) {
            return -898 /* AAUDIO_ERROR_ILLEGAL_ARGUMENT */;
        }
        const auto* data = base + offsetBytes;
        if (isFloat != JNI_FALSE && volume == 1.0f) {
            // Zero-copy fast path: the chain already produced float PCM at
            // unity volume — write the window as it sits.
            return static_cast<jint>(stream->write(
                reinterpret_cast<const float*>(data),
                static_cast<size_t>(frames), channels, timeoutMs));
        }
        // Convert (and scale) into scratch first.
        std::vector<float> scratch(static_cast<size_t>(samples));
        if (isFloat != JNI_FALSE) {
            auto* inPtr = reinterpret_cast<const float*>(data);
            for (jint i = 0; i < samples; ++i) {
                scratch[static_cast<size_t>(i)] = inPtr[i] * volume;
            }
        } else {
            auto* inPtr = reinterpret_cast<const int16_t*>(data);
            for (jint i = 0; i < samples; ++i) {
                scratch[static_cast<size_t>(i)] =
                    (static_cast<float>(inPtr[i]) / 32768.0f) * volume;
            }
        }
        return static_cast<jint>(stream->write(
            scratch.data(), static_cast<size_t>(frames), channels, timeoutMs));
    } catch (...) {
        return -900 /* AAUDIO_ERROR_INTERNAL */;
    }
}

extern "C" JNIEXPORT jlong JNICALL JNI_FUNC_AASTREAM(nativePositionFrames)(
    JNIEnv*, jobject, jlong handle) {
    if (AaudioExclusiveStream* stream = streamOf(handle)) {
        AaudioExclusiveStream::Timestamp ts = stream->timestamp();
        if (ts.valid) {
            return ts.framePosition;
        }
        return stream->framesWritten();
    }
    return 0;
}

extern "C" JNIEXPORT jint JNICALL JNI_FUNC_AASTREAM(nativeSessionId)(
    JNIEnv*, jobject, jlong handle) {
    if (AaudioExclusiveStream* stream = streamOf(handle)) {
        return stream->sessionId();
    }
    return 0;
}

extern "C" JNIEXPORT jint JNICALL JNI_FUNC_AASTREAM(nativeSampleRate)(
    JNIEnv*, jobject, jlong handle) {
    if (AaudioExclusiveStream* stream = streamOf(handle)) {
        return stream->sampleRate();
    }
    return 0;
}

extern "C" JNIEXPORT jint JNICALL JNI_FUNC_AASTREAM(nativeChannelCount)(
    JNIEnv*, jobject, jlong handle) {
    if (AaudioExclusiveStream* stream = streamOf(handle)) {
        return stream->channelCount();
    }
    return 0;
}

extern "C" JNIEXPORT jint JNICALL JNI_FUNC_AASTREAM(nativeXRunCount)(
    JNIEnv*, jobject, jlong handle) {
    if (AaudioExclusiveStream* stream = streamOf(handle)) {
        return stream->xRunCount();
    }
    return 0;
}

extern "C" JNIEXPORT jlong JNICALL JNI_FUNC_AASTREAM(nativeBufferCapacityFrames)(
    JNIEnv*, jobject, jlong handle) {
    if (AaudioExclusiveStream* stream = streamOf(handle)) {
        return stream->bufferCapacityFrames();
    }
    return 0;
}

extern "C" JNIEXPORT jlong JNICALL JNI_FUNC_AASTREAM(nativeBufferSizeFrames)(
    JNIEnv*, jobject, jlong handle) {
    if (AaudioExclusiveStream* stream = streamOf(handle)) {
        return stream->bufferSizeFrames();
    }
    return 0;
}

extern "C" JNIEXPORT jboolean JNICALL JNI_FUNC_AASTREAM(nativeIsExclusive)(
    JNIEnv*, jobject, jlong handle) {
    if (AaudioExclusiveStream* stream = streamOf(handle)) {
        return stream->isExclusive() ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_FALSE;
}
