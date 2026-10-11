# Native analysis front end for Automix (ported from BitChord, which itself
# derives the analyzer from Orchard — see the headers in ../../../native).
#
# Phase 1: whole-track DSP analysis (tempo/key/energy/structure via
# autocorrelation, no ML model). Phase 2: the log-mel front end the Beat
# This! ONNX beat/downbeat model reads (mel_spectrogram.cpp / mel_jni.cpp).
# Phase 3: the linear-frequency STFT the open-unmix vocal-separation model
# reads (vocal_spectrogram.cpp / vocal_jni.cpp). Both models themselves run
# through ONNX Runtime on the Kotlin side, not here. The shared analyzer
# sources live under the repo's root `native/` tree.

cmake_minimum_required(VERSION 3.22.1)
project(archivetune_analysis LANGUAGES C CXX)

get_filename_component(ARCHIVETUNE_NATIVE_DIR
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../native"
    ABSOLUTE)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(archivetune_analysis SHARED
    ${ARCHIVETUNE_NATIVE_DIR}/analyzer/audio_analysis.cpp
    ${ARCHIVETUNE_NATIVE_DIR}/analyzer/tempo_analysis.cpp
    ${ARCHIVETUNE_NATIVE_DIR}/analyzer/resampler.cpp
    ${ARCHIVETUNE_NATIVE_DIR}/analyzer/mel_spectrogram.cpp
    ${ARCHIVETUNE_NATIVE_DIR}/analyzer/vocal_spectrogram.cpp
    jni/analysis_jni.cpp
    jni/mel_jni.cpp
    jni/vocal_jni.cpp
)

target_include_directories(archivetune_analysis PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${ARCHIVETUNE_NATIVE_DIR}
)

# -ffast-math is deliberately NOT enabled: the analyzer's log/mel-adjacent
# arithmetic (see audio_analysis.cpp) is tuned against exact IEEE behaviour.
# -fexceptions is explicit because the JNI bridges catch std::bad_alloc so an
# allocation failure degrades to "no analysis" instead of aborting the process
# (an exception unwinding out of a JNI frame is a hard SIGABRT with no log).
target_compile_options(archivetune_analysis PRIVATE -O3 -fvisibility=hidden -fexceptions)

find_library(log-lib log)
target_link_libraries(archivetune_analysis ${log-lib})

# 16KB page-size support (ported from BitChord's own fix, 77cd233): Android
# 15+ 16KB-page kernels refuse to map LOAD segments aligned only to 4KB — on
# those devices an unaligned libarchivetune_analysis.so either fails to load
# (analysis silently unavailable) or takes the process down at dlopen time.
# -z max-page-size=16384 is a superset of 4KB alignment, so 4KB devices are
# unaffected; APK-side alignment of the uncompressed .so is handled by AGP.
target_link_options(archivetune_analysis PRIVATE "-Wl,-z,max-page-size=16384")

# ---------------------------------------------------------------------------
# Tryptify audio engine DSP (vendored under tryptify/dsp, ported from
# https://github.com/tryptz/Tryptify). Loaded by DspNativeLoader as
# "monochrome_dsp"; the Kotlin keeps the tf.monochrome.android.audio.dsp
# package so the JNI symbols bind unchanged. Default off in the app.
# ---------------------------------------------------------------------------
add_library(monochrome_dsp SHARED
    tryptify/dsp/dsp_engine.cpp
    tryptify/dsp/dsp_jni.cpp
    tryptify/dsp/oxford/oxford_jni.cpp
)
target_include_directories(monochrome_dsp PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/tryptify/dsp
    ${CMAKE_CURRENT_SOURCE_DIR}/tryptify/dsp/util
    ${CMAKE_CURRENT_SOURCE_DIR}/tryptify/dsp/snapins
    ${CMAKE_CURRENT_SOURCE_DIR}/tryptify/dsp/oxford
)
target_compile_features(monochrome_dsp PRIVATE cxx_std_17)
target_compile_options(monochrome_dsp PRIVATE
    -O3 -ffast-math -fno-exceptions -fno-rtti
    $<$<STREQUAL:${ANDROID_ABI},arm64-v8a>:-march=armv8-a+simd>
)
set_target_properties(monochrome_dsp PROPERTIES DEBUG_POSTFIX "")
find_library(log-lib-monochrome-dsp log)
target_link_libraries(monochrome_dsp android ${log-lib-monochrome-dsp})
target_link_options(monochrome_dsp PRIVATE "-Wl,-z,max-page-size=16384")

# Pitch shifter (signalsmith-stretch, header-only, third_party/). Loaded as
# "monochrome_stretch" by StretchNative.
add_library(monochrome_stretch SHARED
    tryptify/stretch/stretch_jni.cpp
)
target_include_directories(monochrome_stretch PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/signalsmith-stretch
)
target_compile_features(monochrome_stretch PRIVATE cxx_std_17)
target_compile_options(monochrome_stretch PRIVATE
    -O3 -fno-exceptions -fno-rtti
    $<$<STREQUAL:${ANDROID_ABI},arm64-v8a>:-march=armv8-a+simd>
)
set_target_properties(monochrome_stretch PROPERTIES DEBUG_POSTFIX "")
target_link_libraries(monochrome_stretch log)
target_link_options(monochrome_stretch PRIVATE "-Wl,-z,max-page-size=16384")

# libusb UAC bit-perfect driver. The vendored libusb is built as a static
# archive and linked into monochrome_usb (loaded as "monochrome_usb").
set(LIBUSB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/libusb)
add_library(usb1.0 STATIC
    ${LIBUSB_DIR}/libusb/core.c
    ${LIBUSB_DIR}/libusb/descriptor.c
    ${LIBUSB_DIR}/libusb/hotplug.c
    ${LIBUSB_DIR}/libusb/io.c
    ${LIBUSB_DIR}/libusb/sync.c
    ${LIBUSB_DIR}/libusb/strerror.c
    ${LIBUSB_DIR}/libusb/os/linux_usbfs.c
    ${LIBUSB_DIR}/libusb/os/events_posix.c
    ${LIBUSB_DIR}/libusb/os/threads_posix.c
    ${LIBUSB_DIR}/libusb/os/linux_netlink.c
)
target_include_directories(usb1.0 PUBLIC
    ${LIBUSB_DIR}/libusb
    ${LIBUSB_DIR}/libusb/os
    ${LIBUSB_DIR}/android
)
target_compile_options(usb1.0 PRIVATE
    -fvisibility=hidden -Wno-unused-parameter -Wno-unused-but-set-variable
)
set_target_properties(usb1.0 PROPERTIES POSITION_INDEPENDENT_CODE ON)

add_library(monochrome_usb SHARED
    tryptify/usb/usb_jni.cpp
    tryptify/usb/libusb_uac_driver.cpp
)
target_include_directories(monochrome_usb PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/tryptify/usb
    ${LIBUSB_DIR}/libusb
)
target_compile_features(monochrome_usb PRIVATE cxx_std_17)
target_compile_options(monochrome_usb PRIVATE -O3 -fno-exceptions -fno-rtti)
set_target_properties(monochrome_usb PROPERTIES DEBUG_POSTFIX "")
target_link_libraries(monochrome_usb android log usb1.0)
target_link_options(monochrome_usb PRIVATE "-Wl,-z,max-page-size=16384")

# ---------------------------------------------------------------------------
# LastWave audio engine (ported from https://github.com/Clash-Projects/
# LastWave-native, vendored under lastwave/). Oboe/AAudio output + the
# Studio Master Clarity DSP chain + libsoxr HQ resampling. Loaded by
# NativeAudioEngine as "lastwave_audio". libsoxr 0.1.3 is vendored at
# third_party/soxr (LGPL-2.1+), built as its own shared library.
# ---------------------------------------------------------------------------
set(SOXR_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/soxr)
list(APPEND CMAKE_MODULE_PATH "${SOXR_DIR}/cmake/Modules")
set(BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_LSR_TESTS OFF CACHE BOOL "" FORCE)
set(WITH_LSR_BINDINGS OFF CACHE BOOL "" FORCE)
set(WITH_OPENMP OFF CACHE BOOL "" FORCE)
set(WITH_CR32S OFF CACHE BOOL "" FORCE)
set(WITH_CR64S OFF CACHE BOOL "" FORCE)
set(WITH_PFFFT OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
add_subdirectory("${SOXR_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/soxr" EXCLUDE_FROM_ALL)
set_target_properties(soxr PROPERTIES VERSION "" SOVERSION "")

find_package(oboe REQUIRED CONFIG)

add_library(lastwave_audio SHARED
    lastwave/AudioEngine.cpp
    lastwave/DspProcessor.cpp
    lastwave/NativeBridge.cpp
)
target_include_directories(lastwave_audio PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/lastwave
    "${SOXR_DIR}/src"
)
target_link_libraries(lastwave_audio PRIVATE oboe::oboe soxr android log)
target_link_options(lastwave_audio PRIVATE "-Wl,-z,max-page-size=16384")
