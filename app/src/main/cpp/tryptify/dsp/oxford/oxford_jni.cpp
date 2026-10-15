// SPDX-License-Identifier: GPL-3.0-or-later
// JNI bridge for oxford_dsp (Inflator + Compressor).
//
// Matches:
//   tf.monochrome.android.audio.dsp.oxford.InflatorNative
//   tf.monochrome.android.audio.dsp.oxford.CompressorNative
//
// Two process entry points are offered:
//   nativeProcess       — direct ByteBuffer, planar [L..., R...] float32
//   nativeProcessArrays — split L/R Java FloatArrays, matches MixBusProcessor

#include <jni.h>
#include <cstdint>
#include <cstring>
#include <algorithm>
#include "oxford_dsp.h"

using trypt::dsp::InflatorProcessor;
using trypt::dsp::CompressorProcessor;

namespace {
template <typename T>
inline T* asPtr(jlong h) { return reinterpret_cast<T*>(static_cast<uintptr_t>(h)); }

// Resolves a direct ByteBuffer holding n planar float channels of `frames`
// samples each. Fails if the buffer is not direct or is too small.
float* planarBase(JNIEnv* env, jobject buf, jint frames, int n) {
    if (!buf || frames <= 0) return nullptr;
    auto* base = static_cast<float*>(env->GetDirectBufferAddress(buf));
    if (!base) return nullptr;
    const jlong capacity = env->GetDirectBufferCapacity(buf);
    const int64_t need = static_cast<int64_t>(n) * frames * static_cast<int64_t>(sizeof(float));
    if (capacity < 0 || need > capacity) return nullptr;
    return base;
}

// Both arrays must be non-null and hold at least `frames` samples.
bool arraysCoverFrames(JNIEnv* env, jfloatArray l, jfloatArray r, jint frames) {
    if (!l || !r || frames <= 0) return false;
    return env->GetArrayLength(l) >= frames && env->GetArrayLength(r) >= frames;
}

bool validPrepare(jdouble sr, jint ch) {
    return sr > 0.0 && ch >= 1 && ch <= trypt::dsp::kMaxChannels;
}
} // anon

// ---- Inflator -------------------------------------------------------------

extern "C" JNIEXPORT jlong JNICALL
Java_tf_monochrome_android_audio_dsp_oxford_InflatorNative_nativeCreate(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(new InflatorProcessor());
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_oxford_InflatorNative_nativeDestroy(JNIEnv*, jclass, jlong h) {
    delete asPtr<InflatorProcessor>(h);
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_oxford_InflatorNative_nativePrepare(
        JNIEnv*, jclass, jlong h, jdouble sr, jint ch) {
    auto* p = asPtr<InflatorProcessor>(h);
    if (!p || !validPrepare(sr, ch)) return;
    p->prepare(sr, ch);
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_oxford_InflatorNative_nativeSetParams(
        JNIEnv*, jclass, jlong h,
        jfloat inputDb, jfloat outputDb, jfloat effect, jfloat curve,
        jboolean clipZeroDb, jboolean bandSplit, jboolean bypass) {
    auto* p = asPtr<InflatorProcessor>(h);
    if (!p) return;
    p->params.inputDb   .store(inputDb);
    p->params.outputDb  .store(outputDb);
    p->params.effect    .store(effect);
    p->params.curve     .store(curve);
    p->params.clipZeroDb.store(clipZeroDb == JNI_TRUE);
    p->params.bandSplit .store(bandSplit  == JNI_TRUE);
    p->params.bypass    .store(bypass     == JNI_TRUE);
}

// Anti-alias oversampling for the waveshaper: 1 (off), 2, or 4. Separate from
// nativeSetParams because it's an independent atomic the audio thread picks up
// on its next block, and keeping it out of the params push avoids reshuffling
// that signature.
extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_oxford_InflatorNative_nativeSetOversampling(
        JNIEnv*, jclass, jlong h, jint factor) {
    auto* p = asPtr<InflatorProcessor>(h);
    if (!p) return;
    p->params.oversampling.store(factor);
}

// Packed: top 32 bits peakL, low 32 bits peakR (raw float bits).
extern "C" JNIEXPORT jlong JNICALL
Java_tf_monochrome_android_audio_dsp_oxford_InflatorNative_nativeReadMeters(
        JNIEnv*, jclass, jlong h) {
    auto* p = asPtr<InflatorProcessor>(h);
    if (!p) return 0;
    float l = p->params.peakL.load(std::memory_order_relaxed);
    float r = p->params.peakR.load(std::memory_order_relaxed);
    uint32_t li, ri;
    std::memcpy(&li, &l, 4);
    std::memcpy(&ri, &r, 4);
    return (static_cast<jlong>(li) << 32) | static_cast<jlong>(ri);
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_oxford_InflatorNative_nativeProcess(
        JNIEnv* env, jclass, jlong h, jobject buf, jint frames, jint channels) {
    auto* p = asPtr<InflatorProcessor>(h);
    if (!p) return;
    // Planar [ch0..., ch1..., ...], [frames] each; up to kMaxChannels.
    // A mono buffer is read as both channels of a stereo pair, as before.
    const int n = std::clamp(static_cast<int>(channels), 1, trypt::dsp::kMaxChannels);
    float* base = planarBase(env, buf, frames, n);
    if (!base) return;
    float* planes[trypt::dsp::kMaxChannels];
    for (int c = 0; c < n; ++c) planes[c] = base + static_cast<size_t>(c) * frames;
    if (n == 1) planes[1] = base;
    p->process(planes, frames);
}

// FloatArray-based entry to match MixBusProcessor's deinterleaved call site.
extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_oxford_InflatorNative_nativeProcessArrays(
        JNIEnv* env, jclass, jlong h, jfloatArray l, jfloatArray r, jint frames) {
    auto* p = asPtr<InflatorProcessor>(h);
    if (!p || !arraysCoverFrames(env, l, r, frames)) return;
    float* lp = env->GetFloatArrayElements(l, nullptr);
    float* rp = env->GetFloatArrayElements(r, nullptr);
    if (!lp || !rp) {
        if (lp) env->ReleaseFloatArrayElements(l, lp, JNI_ABORT);
        if (rp) env->ReleaseFloatArrayElements(r, rp, JNI_ABORT);
        return;
    }
    float* planes[2] = { lp, rp };
    p->process(planes, frames);
    env->ReleaseFloatArrayElements(l, lp, 0);  // commit
    env->ReleaseFloatArrayElements(r, rp, 0);
}

// ---- Compressor -----------------------------------------------------------

extern "C" JNIEXPORT jlong JNICALL
Java_tf_monochrome_android_audio_dsp_oxford_CompressorNative_nativeCreate(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(new CompressorProcessor());
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_oxford_CompressorNative_nativeDestroy(JNIEnv*, jclass, jlong h) {
    delete asPtr<CompressorProcessor>(h);
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_oxford_CompressorNative_nativePrepare(
        JNIEnv*, jclass, jlong h, jdouble sr, jint ch) {
    auto* p = asPtr<CompressorProcessor>(h);
    if (!p || !validPrepare(sr, ch)) return;
    p->prepare(sr, ch);
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_oxford_CompressorNative_nativeSetParams(
        JNIEnv*, jclass, jlong h,
        jfloat thresholdDb, jfloat ratio, jfloat attackMs, jfloat releaseMs,
        jfloat kneeDb, jfloat makeupDb, jboolean bypass) {
    auto* p = asPtr<CompressorProcessor>(h);
    if (!p) return;
    p->params.thresholdDb.store(thresholdDb);
    p->params.ratio      .store(ratio);
    p->params.attackMs   .store(attackMs);
    p->params.releaseMs  .store(releaseMs);
    p->params.kneeDb     .store(kneeDb);
    p->params.makeupDb   .store(makeupDb);
    p->params.bypass     .store(bypass == JNI_TRUE);
}

// Anti-alias oversampling for the detector + gain stage: 1, 2 or 4.
extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_oxford_CompressorNative_nativeSetOversampling(
        JNIEnv*, jclass, jlong h, jint factor) {
    auto* p = asPtr<CompressorProcessor>(h);
    if (!p) return;
    p->params.oversampling.store(factor);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_tf_monochrome_android_audio_dsp_oxford_CompressorNative_nativeGainReductionDb(
        JNIEnv*, jclass, jlong h) {
    auto* p = asPtr<CompressorProcessor>(h);
    if (!p) return 0.0f;
    return p->params.gainReductionDb.load(std::memory_order_relaxed);
}

extern "C" JNIEXPORT jlong JNICALL
Java_tf_monochrome_android_audio_dsp_oxford_CompressorNative_nativeReadMeters(
        JNIEnv*, jclass, jlong h) {
    auto* p = asPtr<CompressorProcessor>(h);
    if (!p) return 0;
    float l = p->params.peakL.load(std::memory_order_relaxed);
    float r = p->params.peakR.load(std::memory_order_relaxed);
    uint32_t li, ri;
    std::memcpy(&li, &l, 4);
    std::memcpy(&ri, &r, 4);
    return (static_cast<jlong>(li) << 32) | static_cast<jlong>(ri);
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_oxford_CompressorNative_nativeProcess(
        JNIEnv* env, jclass, jlong h, jobject buf, jint frames, jint channels) {
    auto* p = asPtr<CompressorProcessor>(h);
    if (!p) return;
    // Planar [ch0..., ch1..., ...], [frames] each; up to kMaxChannels.
    // A mono buffer is read as both channels of a stereo pair, as before.
    const int n = std::clamp(static_cast<int>(channels), 1, trypt::dsp::kMaxChannels);
    float* base = planarBase(env, buf, frames, n);
    if (!base) return;
    float* planes[trypt::dsp::kMaxChannels];
    for (int c = 0; c < n; ++c) planes[c] = base + static_cast<size_t>(c) * frames;
    if (n == 1) planes[1] = base;
    p->process(planes, frames);
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_oxford_CompressorNative_nativeProcessArrays(
        JNIEnv* env, jclass, jlong h, jfloatArray l, jfloatArray r, jint frames) {
    auto* p = asPtr<CompressorProcessor>(h);
    if (!p || !arraysCoverFrames(env, l, r, frames)) return;
    float* lp = env->GetFloatArrayElements(l, nullptr);
    float* rp = env->GetFloatArrayElements(r, nullptr);
    if (!lp || !rp) {
        if (lp) env->ReleaseFloatArrayElements(l, lp, JNI_ABORT);
        if (rp) env->ReleaseFloatArrayElements(r, rp, JNI_ABORT);
        return;
    }
    float* planes[2] = { lp, rp };
    p->process(planes, frames);
    env->ReleaseFloatArrayElements(l, lp, 0);
    env->ReleaseFloatArrayElements(r, rp, 0);
}
