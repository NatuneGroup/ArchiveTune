/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 *
 * 32-bit float DSP chain — implementation. See float_dsp.h for the contract.
 */

#include "float_dsp.h"

#include <atomic>
#include <cmath>
#include <algorithm>

namespace archivetune::dsp {

namespace {

constexpr float kDcCutoffHz = 5.0f;
constexpr float kDefaultCeilingDb = -0.5f;
constexpr float kKneeWidthDb = 6.0f;
constexpr float kAttackMs = 5.0f;
constexpr float kReleaseMs = 150.0f;

inline float onePoleCoeff(float timeConstantMs, int sampleRate) {
    // a = exp(-1 / (tau * fs)); a full-window smoothing coefficient.
    const float tauSamples = timeConstantMs * 0.001f * static_cast<float>(sampleRate);
    return std::exp(-1.0f / std::max(tauSamples, 1.0f));
}

inline float clamp1(float v) {
    return v < -1.0f ? -1.0f : (v > 1.0f ? 1.0f : v);
}

}  // namespace

FloatDsp::FloatDsp(int sampleRate, int channels)
    : sampleRate_(std::max(sampleRate, 8000)),
      channels_(std::max(channels, 1)),
      engaged_(false),
      ceilingDb_(kDefaultCeilingDb),
      envelope_(0.0f),
      rngState_(0x9E3779B9u) {
    dcXm1_.assign(channels_, 0.0f);
    dcYm1_.assign(channels_, 0.0f);
    // DC blocker pole: y[n] = x[n] - x[n-1] + R * y[n-1],
    // R = 1 - 2*pi*fc/fs (valid for fc << fs).
    dcR_ = 1.0f - 6.2831853f * kDcCutoffHz / static_cast<float>(sampleRate_);
    dcR_ = std::min(std::max(dcR_, 0.0f), 0.999f);
    attackCoeff_ = onePoleCoeff(kAttackMs, sampleRate_);
    releaseCoeff_ = onePoleCoeff(kReleaseMs, sampleRate_);
}

void FloatDsp::setLimiterCeilingDb(float ceilingDb) {
    ceilingDb_ = std::min(ceilingDb, 0.0f);
}

void FloatDsp::reset() {
    std::fill(dcXm1_.begin(), dcXm1_.end(), 0.0f);
    std::fill(dcYm1_.begin(), dcYm1_.end(), 0.0f);
    envelope_ = 0.0f;
    // Keep the RNG running so dither never restarts its sequence mid-track.
}

float FloatDsp::limiterGainFor(float envelopeDb) const {
    // Soft knee in dB domain:
    //   level <= ceiling - knee            -> 0 dB gain
    //   ceiling - knee < level < ceiling   -> -(level - (ceiling - knee))^2 / (2 * knee)
    //   level >= ceiling                   -> ceiling - level - knee / 2
    const float kneeStart = ceilingDb_ - kKneeWidthDb;
    if (envelopeDb <= kneeStart) {
        return 1.0f;
    }
    float gainDb;
    if (envelopeDb < ceilingDb_) {
        const float over = envelopeDb - kneeStart;
        gainDb = -(over * over) / (2.0f * kKneeWidthDb);
    } else {
        gainDb = ceilingDb_ - envelopeDb - kKneeWidthDb / 2.0f;
    }
    return std::exp(gainDb * 0.11512925f);  // 10^(dB/20) = e^(dB * ln10/20)
}

void FloatDsp::process(float* data, size_t frames, int channels) {
    if (!engaged_ || frames == 0 || channels_ <= 0) {
        return;
    }
    // Layout guard: the per-channel state vectors are sized for channels_ at
    // construction. A buffer whose interleave differs (a format change the
    // Kotlin owner missed) must never be walked with the stale stride —
    // frames * channels_ floats could overrun a frames * channels buffer.
    if (channels != channels_) {
        return;
    }

    const size_t samples = frames * static_cast<size_t>(channels_);
    float gain = 1.0f;
    bool gainComputed = false;

    // Pass 1: DC-block into a peak envelope over the whole block (blocks are
    // ~20-40 ms, short enough for one gain decision per block with the
    // attack/release smoothing doing the interpolation).
    float blockPeak = 0.0f;
    for (size_t i = 0; i < samples; i += static_cast<size_t>(channels_)) {
        for (int c = 0; c < channels_; ++c) {
            const float x = data[i + c];
            const float y = x - dcXm1_[c] + dcR_ * dcYm1_[c];
            dcXm1_[c] = x;
            dcYm1_[c] = y;
            const float magnitude = std::fabs(y);
            if (magnitude > blockPeak) blockPeak = magnitude;
        }
    }

    // Envelope follower + gain.
    const float attackOrRelease = (blockPeak > envelope_) ? attackCoeff_ : releaseCoeff_;
    envelope_ = attackOrRelease * envelope_ + (1.0f - attackOrRelease) * blockPeak;
    if (envelope_ > 1e-6f) {
        const float envelopeDb = 8.6858896f * std::log(envelope_);  // 20*log10(e) per ln
        gain = limiterGainFor(envelopeDb);
        gainComputed = true;
    }

    // Pass 2: apply gain when it does anything (the common case is gain==1
    // exactly; skipping the multiply keeps the path bit-transparent).
    if (gainComputed && gain < 0.999999f) {
        for (size_t i = 0; i < samples; ++i) {
            data[i] *= gain;
        }
    }
}

void FloatDsp::toShort(const float* in, int16_t* out, size_t samples) {
    for (size_t i = 0; i < samples; ++i) {
        // xorshift32 -> two uniforms in [-0.5, 0.5); their difference is TPDF.
        rngState_ ^= rngState_ << 13;
        rngState_ ^= rngState_ >> 17;
        rngState_ ^= rngState_ << 5;
        const float r1 = (static_cast<float>(rngState_ >> 8) / 8388608.0f) - 1.0f;  // [0,1) - 0.5
        rngState_ ^= rngState_ << 13;
        rngState_ ^= rngState_ >> 17;
        rngState_ ^= rngState_ << 5;
        const float r2 = (static_cast<float>(rngState_ >> 8) / 8388608.0f) - 1.0f;
        // Scale to int16 range, add one LSB of TPDF noise, round to nearest.
        const float scaled = clamp1(in[i]) * 32767.0f;
        const float dithered = scaled + (r1 - r2);
        out[i] = static_cast<int16_t>(std::lrintf(dithered));
    }
}

}  // namespace archivetune::dsp
