/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 *
 * AAudio exclusive-mode float output — implementation.
 */

#include "aaudio_output.h"

#include <algorithm>

namespace archivetune::dsp {

AaudioExclusiveStream::~AaudioExclusiveStream() {
    close();
}

int32_t AaudioExclusiveStream::open(int32_t sampleRate,
                                     int32_t channels,
                                     int32_t deviceId,
                                     bool exclusive,
                                     int32_t usage,
                                     int32_t contentType,
                                     int32_t bufferCapacityFrames) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (open_) {
        close();
    }

    AAudioStreamBuilder* builder = nullptr;
    aaudio_result_t result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK || builder == nullptr) {
        return result != AAUDIO_OK ? result : AAUDIO_ERROR_INTERNAL;
    }

    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setSampleRate(builder, sampleRate);
    AAudioStreamBuilder_setChannelCount(builder, channels);
    AAudioStreamBuilder_setSharingMode(builder,
                                       exclusive ? AAUDIO_SHARING_MODE_EXCLUSIVE
                                                 : AAUDIO_SHARING_MODE_SHARED);
    // LowLatency routes through the FastMixer path when the HAL grants it;
    // exclusive already bypasses the mixer entirely on most devices.
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    if (deviceId > 0) {
        AAudioStreamBuilder_setDeviceId(builder, deviceId);
    }
#if __ANDROID_API__ >= 28
    // Usage/content-type setters are API 28+; below that the stream routes
    // with the framework's media defaults, which match anyway.
    if (usage >= 0) {
        AAudioStreamBuilder_setUsage(builder, static_cast<aaudio_usage_t>(usage));
    }
    if (contentType >= 0) {
        AAudioStreamBuilder_setContentType(builder, static_cast<aaudio_content_type_t>(contentType));
    }
#endif
    if (bufferCapacityFrames > 0) {
        AAudioStreamBuilder_setBufferCapacityInFrames(builder, bufferCapacityFrames);
    }

    AAudioStream* stream = nullptr;
    result = AAudioStreamBuilder_openStream(builder, &stream);
    AAudioStreamBuilder_delete(builder);
    if (result != AAUDIO_OK) {
        return result;
    }

    stream_ = stream;
    sampleRate_ = AAudioStream_getSampleRate(stream);
    channels_ = AAudioStream_getChannelCount(stream);
    exclusiveGranted_ = AAudioStream_getSharingMode(stream) == AAUDIO_SHARING_MODE_EXCLUSIVE;
    framesWritten_ = 0;
    open_ = true;

    if (exclusive && !exclusiveGranted_) {
        // The caller asked for exclusive and the HAL demoted us to shared:
        // report failure so the caller falls back to the standard sink
        // instead of silently running through the mixer anyway.
        close();
        return AAUDIO_ERROR_INVALID_STATE;
    }
    return AAUDIO_OK;
}

int64_t AaudioExclusiveStream::write(const float* data, size_t frames, int32_t channels, int64_t timeoutMs) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!open_ || stream_ == nullptr) {
        return AAUDIO_ERROR_DISCONNECTED;
    }
    // The caller's interleave governs the pointer stride. A stream whose
    // granted layout disagrees with the buffer's cannot be written safely —
    // striding by the stream's own count would read past the caller's buffer.
    if (channels <= 0 || channels != channels_) {
        return AAUDIO_ERROR_ILLEGAL_ARGUMENT;
    }
    int64_t written = 0;
    while (written < static_cast<int64_t>(frames)) {
        aaudio_result_t result = AAudioStream_write(
            stream_,
            data + written * static_cast<size_t>(channels),
            static_cast<int32_t>(frames - static_cast<size_t>(written)),
            static_cast<int64_t>(timeoutMs) * 1000000LL);
        if (result < 0) {
            return result;
        }
        if (result == 0) {
            // Timeout with nothing accepted — surface as a short write so the
            // caller can distinguish a stall from an error.
            return written;
        }
        written += result;
        framesWritten_ += result;
    }
    return written;
}

int32_t AaudioExclusiveStream::requestStart() {
    std::lock_guard<std::mutex> lock(mutex_);
    return open_ && stream_ != nullptr ? AAudioStream_requestStart(stream_) : AAUDIO_ERROR_INVALID_STATE;
}

int32_t AaudioExclusiveStream::requestPause() {
    std::lock_guard<std::mutex> lock(mutex_);
    return open_ && stream_ != nullptr ? AAudioStream_requestPause(stream_) : AAUDIO_ERROR_INVALID_STATE;
}

int32_t AaudioExclusiveStream::requestFlush() {
    std::lock_guard<std::mutex> lock(mutex_);
    // AAudio flushes the buffer of a PAUSED stream; requesting flush on a
    // running stream is an error, so the pause-then-flush idiom is the only
    // correct one (the caller drives pause() first).
    return open_ && stream_ != nullptr ? AAudioStream_requestFlush(stream_) : AAUDIO_ERROR_INVALID_STATE;
}

int32_t AaudioExclusiveStream::requestStop() {
    std::lock_guard<std::mutex> lock(mutex_);
    return open_ && stream_ != nullptr ? AAudioStream_requestStop(stream_) : AAUDIO_ERROR_INVALID_STATE;
}

void AaudioExclusiveStream::close() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (stream_ != nullptr) {
        AAudioStream_close(stream_);
        stream_ = nullptr;
    }
    open_ = false;
    exclusiveGranted_ = false;
}

AaudioExclusiveStream::Timestamp AaudioExclusiveStream::timestamp() const {
    std::lock_guard<std::mutex> lock(mutex_);
    Timestamp ts{0, 0, false};
    if (!open_ || stream_ == nullptr) {
        return ts;
    }
    int64_t frames = 0;
    int64_t nanos = 0;
    aaudio_result_t result =
        AAudioStream_getTimestamp(stream_, CLOCK_MONOTONIC, &frames, &nanos);
    ts.valid = result == AAUDIO_OK;
    ts.framePosition = frames;
    ts.timeNanos = nanos;
    return ts;
}

int32_t AaudioExclusiveStream::sampleRate() const {
    return sampleRate_;
}

int32_t AaudioExclusiveStream::channelCount() const {
    return channels_;
}

int32_t AaudioExclusiveStream::sessionId() const {
    std::lock_guard<std::mutex> lock(mutex_);
#if __ANDROID_API__ >= 28
    return open_ && stream_ != nullptr ? AAudioStream_getSessionId(stream_) : 0;
#else
    return 0;
#endif
}

int64_t AaudioExclusiveStream::framesWritten() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return framesWritten_;
}

int32_t AaudioExclusiveStream::xRunCount() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return open_ && stream_ != nullptr ? AAudioStream_getXRunCount(stream_) : 0;
}

int64_t AaudioExclusiveStream::bufferCapacityFrames() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return open_ && stream_ != nullptr ? AAudioStream_getBufferCapacityInFrames(stream_) : 0;
}

int64_t AaudioExclusiveStream::bufferSizeFrames() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return open_ && stream_ != nullptr ? AAudioStream_getBufferSizeInFrames(stream_) : 0;
}

bool AaudioExclusiveStream::isExclusive() const {
    return exclusiveGranted_;
}

bool AaudioExclusiveStream::isOpen() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return open_;
}

}  // namespace archivetune::dsp
