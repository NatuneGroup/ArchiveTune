/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 *
 * RAII wrapper over an AAudio output stream in EXCLUSIVE sharing mode with
 * 32-bit float PCM — the USB-direct path (no AudioFlinger mixer between the
 * app and the USB DAC).
 */

#ifndef ARCHIVETUNE_NATIVE_DSP_AAUDIO_OUTPUT_H_
#define ARCHIVETUNE_NATIVE_DSP_AAUDIO_OUTPUT_H_

#include <aaudio/AAudio.h>
#include <cstdint>
#include <mutex>

namespace archivetune::dsp {

class AaudioExclusiveStream {
public:
    AaudioExclusiveStream() = default;
    ~AaudioExclusiveStream();

    AaudioExclusiveStream(const AaudioExclusiveStream&) = delete;
    AaudioExclusiveStream& operator=(const AaudioExclusiveStream&) = delete;

    struct Timestamp {
        int64_t framePosition;
        int64_t timeNanos;
        bool valid;
    };

    /**
     * Opens an output stream. Returns an AAudio result code (AAUDIO_OK == 0).
     *
     * @param deviceId 0 routes to the default device; a positive AudioManager
     *        AudioDeviceInfo id pins the stream to that device (a USB DAC).
     * @param exclusive when true the stream requests EXCLUSIVE sharing; the
     *        open fails if the HAL cannot grant it (caller falls back).
     */
    int32_t open(int32_t sampleRate,
                 int32_t channels,
                 int32_t deviceId,
                 bool exclusive,
                 int32_t usage,
                 int32_t contentType,
                 int32_t bufferCapacityFrames);

    /**
     * Blocking write of interleaved float PCM already scaled by volume.
     * Returns frames written, or a negative AAudio error.
     *
     * @param channels the interleaved layout of @p data. The pointer stride
     *        between consecutive frames comes from THIS argument, not the
     *        stream's own channel count — the two are equal in every correct
     *        configuration, and a mismatch is surfaced as an error instead of
     *        walked past the end of the caller's buffer.
     */
    int64_t write(const float* data, size_t frames, int32_t channels, int64_t timeoutMs);

    int32_t requestStart();
    int32_t requestPause();
    int32_t requestFlush();
    int32_t requestStop();

    void close();

    Timestamp timestamp() const;

    int32_t sampleRate() const;
    int32_t channelCount() const;
    int32_t sessionId() const;
    int64_t framesWritten() const;
    int32_t xRunCount() const;
    int64_t bufferCapacityFrames() const;
    int64_t bufferSizeFrames() const;
    /** True when the HAL actually granted EXCLUSIVE sharing. */
    bool isExclusive() const;
    bool isOpen() const;

private:
    mutable std::mutex mutex_;
    AAudioStream* stream_ = nullptr;
    bool exclusiveGranted_ = false;
    bool open_ = false;
    int32_t sampleRate_ = 0;
    int32_t channels_ = 0;
    int64_t framesWritten_ = 0;
};

}  // namespace archivetune::dsp

#endif  // ARCHIVETUNE_NATIVE_DSP_AAUDIO_OUTPUT_H_
