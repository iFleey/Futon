# Futon - AI Agent Daemon
# Copyright (C) 2025 Fleey
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <https://www.gnu.org/licenses/>.

cmake_minimum_required(VERSION 3.22.1)
project(futon_daemon VERSION 1.0.0 LANGUAGES C CXX)

# =============================================================================
# Compiler Cache (ccache) Support for Faster Incremental Builds
# =============================================================================
find_program(CCACHE_PROGRAM ccache)
if (CCACHE_PROGRAM)
    message(STATUS "Found ccache: ${CCACHE_PROGRAM}")
    set(CMAKE_C_COMPILER_LAUNCHER "${CCACHE_PROGRAM}")
    set(CMAKE_CXX_COMPILER_LAUNCHER "${CCACHE_PROGRAM}")
endif ()

# =============================================================================
# Git Version Info with Caching for Incremental Builds
# =============================================================================
# Cache git version info to avoid re-running git commands on every configure
set(GIT_VERSION_CACHE_FILE "${CMAKE_BINARY_DIR}/git_version_cache.txt")
set(GIT_HASH_CACHE_FILE "${CMAKE_BINARY_DIR}/git_hash_cache.txt")

# Option to force refresh git info (useful for release builds)
option(FUTON_REFRESH_GIT_INFO "Force refresh git version info" OFF)

# Check if we need to refresh git info
set(NEED_GIT_REFRESH FALSE)
if (FUTON_REFRESH_GIT_INFO)
    set(NEED_GIT_REFRESH TRUE)
    message(STATUS "Git info refresh forced by FUTON_REFRESH_GIT_INFO")
elseif (NOT EXISTS "${GIT_VERSION_CACHE_FILE}" OR NOT EXISTS "${GIT_HASH_CACHE_FILE}")
    set(NEED_GIT_REFRESH TRUE)
    message(STATUS "Git info cache not found, will fetch")
endif ()

if (NEED_GIT_REFRESH)
    execute_process(
            COMMAND git describe --tags --always --dirty
            WORKING_DIRECTORY ${CMAKE_SOURCE_DIR}
            OUTPUT_VARIABLE GIT_VERSION
            OUTPUT_STRIP_TRAILING_WHITESPACE
            ERROR_QUIET
            RESULT_VARIABLE GIT_VERSION_RESULT
    )
    execute_process(
            COMMAND git rev-parse --short HEAD
            WORKING_DIRECTORY ${CMAKE_SOURCE_DIR}
            OUTPUT_VARIABLE GIT_HASH
            OUTPUT_STRIP_TRAILING_WHITESPACE
            ERROR_QUIET
            RESULT_VARIABLE GIT_HASH_RESULT
    )

    # Cache the results
    if (GIT_VERSION_RESULT EQUAL 0 AND GIT_VERSION)
        file(WRITE "${GIT_VERSION_CACHE_FILE}" "${GIT_VERSION}")
    else ()
        set(GIT_VERSION "${PROJECT_VERSION}")
        file(WRITE "${GIT_VERSION_CACHE_FILE}" "${GIT_VERSION}")
    endif ()

    if (GIT_HASH_RESULT EQUAL 0 AND GIT_HASH)
        file(WRITE "${GIT_HASH_CACHE_FILE}" "${GIT_HASH}")
    else ()
        set(GIT_HASH "unknown")
        file(WRITE "${GIT_HASH_CACHE_FILE}" "${GIT_HASH}")
    endif ()

    message(STATUS "Git version info cached: ${GIT_VERSION} (${GIT_HASH})")
else ()
    # Read from cache
    file(READ "${GIT_VERSION_CACHE_FILE}" GIT_VERSION)
    file(READ "${GIT_HASH_CACHE_FILE}" GIT_HASH)
    string(STRIP "${GIT_VERSION}" GIT_VERSION)
    string(STRIP "${GIT_HASH}" GIT_HASH)
    message(STATUS "Git version info from cache: ${GIT_VERSION} (${GIT_HASH})")
endif ()

if (GIT_VERSION)
    add_compile_definitions(FUTON_VERSION="${GIT_VERSION}")
else ()
    add_compile_definitions(FUTON_VERSION="${PROJECT_VERSION}")
endif ()

if (GIT_HASH)
    add_compile_definitions(FUTON_GIT_HASH="${GIT_HASH}")
endif ()

# =============================================================================
# NDK API Level Configuration
# =============================================================================
# Minimum API level for core functionality
# HardwareBuffer Parcel APIs (API 34+) are loaded dynamically at runtime
# This allows the daemon to run on older devices with graceful degradation
set(FUTON_MIN_API_LEVEL 30)

if (NOT DEFINED ANDROID_NATIVE_API_LEVEL)
    # Default to API 30 for broader compatibility
    set(ANDROID_NATIVE_API_LEVEL ${FUTON_MIN_API_LEVEL})
    set(ANDROID_PLATFORM "android-${FUTON_MIN_API_LEVEL}")
    message(STATUS "ANDROID_NATIVE_API_LEVEL not specified, defaulting to ${FUTON_MIN_API_LEVEL}")
endif ()

if (ANDROID_NATIVE_API_LEVEL LESS ${FUTON_MIN_API_LEVEL})
    message(FATAL_ERROR
            "NDK API level must be ${FUTON_MIN_API_LEVEL} or higher.\n"
            "Current: ${ANDROID_NATIVE_API_LEVEL}\n"
            "Use: -DANDROID_PLATFORM=android-${FUTON_MIN_API_LEVEL}")
endif ()

message(STATUS "NDK API Level: ${ANDROID_NATIVE_API_LEVEL} (minimum: ${FUTON_MIN_API_LEVEL})")

# Force rebuild when API level changes by adding it to compile definitions
# This ensures __ANDROID_API__ dependent code is recompiled correctly
add_compile_definitions(FUTON_NDK_API_LEVEL=${ANDROID_NATIVE_API_LEVEL})

# C++17 standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# =============================================================================
# Abseil for LiteRT C++ API (PPOCRv5)
# =============================================================================
include(FetchContent)
set(ABSL_PROPAGATE_CXX_STD ON)
set(ABSL_BUILD_TESTING OFF)
set(ABSL_ENABLE_INSTALL OFF)

FetchContent_Declare(
        abseil-cpp
        GIT_REPOSITORY https://github.com/abseil/abseil-cpp.git
        GIT_TAG 20250127.1
        GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(abseil-cpp)

# Compiler flags
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fvisibility=hidden")
set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -fPIE -pie")

# Build type configuration
if (CMAKE_BUILD_TYPE STREQUAL "Release")
    set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -O3 -DNDEBUG")
else ()
    set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -g -O0")
endif ()

# Find required Android libraries
find_library(LOG_LIB log)
find_library(ANDROID_LIB android)
find_library(BINDER_NDK_LIB binder_ndk)
find_library(EGL_LIB EGL)
find_library(GLESV3_LIB GLESv3)

# =============================================================================
# Crypto Library Configuration
# =============================================================================
# Option to skip crypto entirely (for quick testing)
option(FUTON_SKIP_CRYPTO "Skip crypto library (disables encryption features)" OFF)

if (FUTON_SKIP_CRYPTO)
    message(STATUS "Crypto: SKIPPED (FUTON_SKIP_CRYPTO=ON)")
    set(CRYPTO_LIB "")
    set(SSL_LIB "")
else ()
    # Try to find system crypto library first, fall back to BoringSSL
    # For NDK cross-compilation, crypto is in the sysroot
    find_library(CRYPTO_LIB crypto
            PATHS ${CMAKE_SYSROOT}/usr/lib/${CMAKE_LIBRARY_ARCHITECTURE}
            ${CMAKE_SYSROOT}/usr/lib
            NO_DEFAULT_PATH
    )
    find_library(SSL_LIB ssl
            PATHS ${CMAKE_SYSROOT}/usr/lib/${CMAKE_LIBRARY_ARCHITECTURE}
            ${CMAKE_SYSROOT}/usr/lib
            NO_DEFAULT_PATH
    )

    # Fallback: try default search
    if (NOT CRYPTO_LIB)
        find_library(CRYPTO_LIB crypto)
    endif ()
    if (NOT SSL_LIB)
        find_library(SSL_LIB ssl)
    endif ()

    # If still not found, use bundled BoringSSL
    if (NOT CRYPTO_LIB)
        message(STATUS "System crypto library not found, building BoringSSL from source")
        message(STATUS "  (Use -DFUTON_SKIP_CRYPTO=ON to skip for faster builds)")
        add_subdirectory(third_party/boringssl)
        set(CRYPTO_LIB crypto)
        set(SSL_LIB ssl)
        set(USING_BORINGSSL TRUE)
        include_directories(${BORINGSSL_INCLUDE_DIR})
    else ()
        message(STATUS "Using system crypto library: ${CRYPTO_LIB}")
    endif ()
endif ()

# libseccomp for Seccomp-BPF filtering
# Build from source if not found
find_library(SECCOMP_LIB seccomp)
if (NOT SECCOMP_LIB)
    message(STATUS "System libseccomp not found, building from source")
    add_subdirectory(third_party/libseccomp)
    set(SECCOMP_LIB seccomp_lib)
    set(USING_BUNDLED_SECCOMP TRUE)
    include_directories(${LIBSECCOMP_INCLUDE_DIR})
endif ()

# Verify required libraries
if (NOT LOG_LIB)
    message(FATAL_ERROR "log library not found")
endif ()
if (NOT ANDROID_LIB)
    message(FATAL_ERROR "android library not found")
endif ()
if (NOT BINDER_NDK_LIB)
    message(FATAL_ERROR "binder_ndk library not found")
endif ()
if (NOT EGL_LIB)
    message(FATAL_ERROR "EGL library not found")
endif ()
if (NOT GLESV3_LIB)
    message(FATAL_ERROR "GLESv3 library not found")
endif ()
# CRYPTO_LIB is now guaranteed to be set (either system or BoringSSL)

# Common libraries for all modules
set(FUTON_COMMON_LIBS
        ${LOG_LIB}
        ${ANDROID_LIB}
)


# AIDL Code Generation
# Generate C++ stubs and proxies from AIDL interface definitions

set(AIDL_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../aidl")
set(AIDL_GEN_DIR "${CMAKE_CURRENT_BINARY_DIR}/aidl_gen")
set(AIDL_PACKAGE_DIR "me/fleey/futon")

# Create output directory
file(MAKE_DIRECTORY ${AIDL_GEN_DIR})

# AIDL source files (order matters: parcelables before interfaces that use them)
set(AIDL_SOURCES
        ${AIDL_DIR}/${AIDL_PACKAGE_DIR}/DetectionResult.aidl
        ${AIDL_DIR}/${AIDL_PACKAGE_DIR}/DaemonStatus.aidl
        ${AIDL_DIR}/${AIDL_PACKAGE_DIR}/FutonConfig.aidl
        ${AIDL_DIR}/${AIDL_PACKAGE_DIR}/CryptoHandshake.aidl
        ${AIDL_DIR}/${AIDL_PACKAGE_DIR}/IStatusCallback.aidl
        ${AIDL_DIR}/${AIDL_PACKAGE_DIR}/IFutonDaemon.aidl
)

# Generated C++ files
set(AIDL_GEN_SOURCES
        ${AIDL_GEN_DIR}/${AIDL_PACKAGE_DIR}/DetectionResult.cpp
        ${AIDL_GEN_DIR}/${AIDL_PACKAGE_DIR}/DaemonStatus.cpp
        ${AIDL_GEN_DIR}/${AIDL_PACKAGE_DIR}/FutonConfig.cpp
        ${AIDL_GEN_DIR}/${AIDL_PACKAGE_DIR}/CryptoHandshake.cpp
        ${AIDL_GEN_DIR}/${AIDL_PACKAGE_DIR}/IStatusCallback.cpp
        ${AIDL_GEN_DIR}/${AIDL_PACKAGE_DIR}/IFutonDaemon.cpp
)

set(AIDL_GEN_HEADERS
        ${AIDL_GEN_DIR}/${AIDL_PACKAGE_DIR}/DetectionResult.h
        ${AIDL_GEN_DIR}/${AIDL_PACKAGE_DIR}/DaemonStatus.h
        ${AIDL_GEN_DIR}/${AIDL_PACKAGE_DIR}/FutonConfig.h
        ${AIDL_GEN_DIR}/${AIDL_PACKAGE_DIR}/CryptoHandshake.h
        ${AIDL_GEN_DIR}/${AIDL_PACKAGE_DIR}/IStatusCallback.h
        ${AIDL_GEN_DIR}/${AIDL_PACKAGE_DIR}/IFutonDaemon.h
        ${AIDL_GEN_DIR}/${AIDL_PACKAGE_DIR}/BnFutonDaemon.h
        ${AIDL_GEN_DIR}/${AIDL_PACKAGE_DIR}/BpFutonDaemon.h
        ${AIDL_GEN_DIR}/${AIDL_PACKAGE_DIR}/BnStatusCallback.h
        ${AIDL_GEN_DIR}/${AIDL_PACKAGE_DIR}/BpStatusCallback.h
)

# Find aidl compiler from NDK
find_program(AIDL_COMPILER aidl
        HINTS
        ${ANDROID_NDK}/build/tools
        ${ANDROID_NDK}/toolchains/llvm/prebuilt/*/bin
        $ENV{ANDROID_NDK}/build/tools
        $ENV{ANDROID_NDK}/toolchains/llvm/prebuilt/*/bin
)

if (AIDL_COMPILER)
    message(STATUS "AIDL compiler found: ${AIDL_COMPILER}")

    # Create output package directory
    file(MAKE_DIRECTORY ${AIDL_GEN_DIR}/${AIDL_PACKAGE_DIR})

    # Custom command to generate C++ from AIDL
    add_custom_command(
            OUTPUT ${AIDL_GEN_SOURCES} ${AIDL_GEN_HEADERS}
            COMMAND ${AIDL_COMPILER}
            --lang=ndk
            -I${AIDL_DIR}
            -o${AIDL_GEN_DIR}
            -h${AIDL_GEN_DIR}
            ${AIDL_SOURCES}
            DEPENDS ${AIDL_SOURCES}
            COMMENT "Generating C++ from AIDL interfaces"
            VERBATIM
    )

    # Create a library for generated AIDL code
    add_library(futon_aidl STATIC
            ${AIDL_GEN_SOURCES}
    )
    target_include_directories(futon_aidl PUBLIC
            ${AIDL_GEN_DIR}
    )
    target_link_libraries(futon_aidl
            ${BINDER_NDK_LIB}
            ${LOG_LIB}
    )

    set(AIDL_AVAILABLE TRUE)
else ()
    message(WARNING "AIDL compiler not found. AIDL interfaces will not be generated.")
    message(WARNING "Set ANDROID_NDK environment variable or provide pre-generated files.")
    set(AIDL_AVAILABLE FALSE)
endif ()


# WebSocket Library Configuration
# Using built-in lightweight WebSocket implementation (RFC 6455)
# No external dependencies required

# =============================================================================
# Security Module Configuration
# =============================================================================
# These options allow users to disable security features as permitted by GPLv3.
# The GPL grants users the right to modify the software, including disabling
# technical protection measures. These options make that process straightforward.
#
# IMPORTANT: Disabling security features may expose the daemon to attacks.
# Only disable these for development, debugging, or if you understand the risks.
# =============================================================================

option(FUTON_ENABLE_SECURITY "Enable all security modules (master switch)" ON)
option(FUTON_ENABLE_SECCOMP "Enable Seccomp-BPF syscall filtering (kernel-level)" ON)
option(FUTON_ENABLE_ANTI_DEBUG "Enable anti-debugging detection" ON)
option(FUTON_ENABLE_DEVICE_BINDING "Enable device fingerprint binding" ON)
option(FUTON_ENABLE_INTEGRITY_CHECK "Enable code integrity verification" ON)
option(FUTON_ENABLE_WATERMARK "Enable build watermarking (non-tracking)" ON)

# Apply security configuration
if (FUTON_ENABLE_SECURITY)
    add_compile_definitions(FUTON_SECURITY_ENABLED=1)

    if (FUTON_ENABLE_SECCOMP)
        add_compile_definitions(FUTON_SECCOMP_ENABLED=1)
    else ()
        add_compile_definitions(FUTON_SECCOMP_ENABLED=0)
        message(STATUS "Security: Seccomp-BPF DISABLED")
    endif ()

    if (FUTON_ENABLE_ANTI_DEBUG)
        add_compile_definitions(FUTON_ANTI_DEBUG_ENABLED=1)
    else ()
        add_compile_definitions(FUTON_ANTI_DEBUG_ENABLED=0)
        message(STATUS "Security: Anti-debugging DISABLED")
    endif ()

    if (FUTON_ENABLE_DEVICE_BINDING)
        add_compile_definitions(FUTON_DEVICE_BINDING_ENABLED=1)
    else ()
        add_compile_definitions(FUTON_DEVICE_BINDING_ENABLED=0)
        message(STATUS "Security: Device binding DISABLED")
    endif ()

    if (FUTON_ENABLE_INTEGRITY_CHECK)
        add_compile_definitions(FUTON_INTEGRITY_CHECK_ENABLED=1)
    else ()
        add_compile_definitions(FUTON_INTEGRITY_CHECK_ENABLED=0)
        message(STATUS "Security: Integrity checking DISABLED")
    endif ()

    if (FUTON_ENABLE_WATERMARK)
        add_compile_definitions(FUTON_WATERMARK_ENABLED=1)
    else ()
        add_compile_definitions(FUTON_WATERMARK_ENABLED=0)
        message(STATUS "Security: Watermarking DISABLED")
    endif ()
else ()
    add_compile_definitions(FUTON_SECURITY_ENABLED=0)
    add_compile_definitions(FUTON_SECCOMP_ENABLED=0)
    add_compile_definitions(FUTON_ANTI_DEBUG_ENABLED=0)
    add_compile_definitions(FUTON_DEVICE_BINDING_ENABLED=0)
    add_compile_definitions(FUTON_INTEGRITY_CHECK_ENABLED=0)
    add_compile_definitions(FUTON_WATERMARK_ENABLED=0)
    message(STATUS "Security: ALL MODULES DISABLED")
endif ()

# =============================================================================

option(FUTON_ENABLE_DEBUG_STREAM "Enable WebSocket debug stream" ON)

if (FUTON_ENABLE_DEBUG_STREAM)
    message(STATUS "Debug stream enabled with built-in WebSocket implementation")
    set(WEBSOCKETS_AVAILABLE TRUE)
else ()
    set(WEBSOCKETS_AVAILABLE FALSE)
    message(STATUS "Debug stream disabled by configuration")
endif ()

# Include directories
# Root daemon directory for module-level includes (e.g., #include "core/error.h")
include_directories(
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${AIDL_GEN_DIR}
)

# =============================================================================
# LiteRT SDK for PPOCRv5 (prebuilt libraries)
# =============================================================================
# Disable optional hardware support that requires external headers
add_compile_definitions(
        LITERT_DISABLE_OPENCL_SUPPORT
        LITERT_DISABLE_VULKAN_SUPPORT
        LITERT_DISABLE_WEBGPU_SUPPORT
)

add_subdirectory("inference/litert_cc_sdk" "litert_cc_sdk/build")
include_directories("${CMAKE_CURRENT_SOURCE_DIR}/inference/litert_cc_sdk/include")


# Library: futon_core
# Core utilities, error handling, process initialization, main loop

add_library(futon_core STATIC
        core/error.cpp
        core/process_init.cpp
        core/main_loop.cpp
        core/watchdog.cpp
        core/system_status.cpp
        # Sandbox (kernel-level syscall filtering)
        core/sandbox/seccomp_filter.cpp
        # Authentication core
        core/auth/crypto_utils.cpp
        core/auth/session_manager.cpp
        core/auth/auth_manager.cpp
        core/auth/rate_limiter.cpp
        core/auth/security_audit.cpp
        core/auth/caller_verifier.cpp
        # User-Provisioned PKI (Phase 2)
        core/auth/key_whitelist.cpp
        core/auth/attestation_verifier.cpp
        # Security modules (GPLv3 compliant - no obscurity-based security)
        core/auth/device_fingerprint.cpp
        core/auth/integrity_checker.cpp
        core/auth/hardened_config.cpp
        core/auth/security.cpp
        # Dual-channel crypto (Phase 3: Double Ratchet + Stream Cipher)
        core/crypto/double_ratchet.cpp
        core/crypto/stream_cipher.cpp
)
target_include_directories(futon_core PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/core
        ${CMAKE_CURRENT_SOURCE_DIR}/core/auth
        ${CMAKE_CURRENT_SOURCE_DIR}/core/sandbox
        ${CMAKE_CURRENT_SOURCE_DIR}/core/crypto
)
target_link_libraries(futon_core
        ${FUTON_COMMON_LIBS}
        ${BINDER_NDK_LIB}
        ${CRYPTO_LIB}
)

# Link libseccomp if available and enabled
if (FUTON_ENABLE_SECCOMP AND SECCOMP_LIB)
    target_link_libraries(futon_core ${SECCOMP_LIB})
    message(STATUS "Seccomp: Using libseccomp")
elseif (FUTON_ENABLE_SECCOMP)
    message(WARNING "Seccomp: libseccomp not found, using fallback BPF")
    target_compile_definitions(futon_core PRIVATE FUTON_SECCOMP_USE_FALLBACK=1)
endif ()


# Library: futon_vision
# Zero-copy vision pipeline with AHardwareBuffer

add_library(futon_vision STATIC
        # Buffer management
        vision/buffer/hardware_buffer_wrapper.cpp

        # Symbol loading and resolution
        vision/loader/symbol_resolver.cpp
        vision/loader/elf_symbol_scanner.cpp
        vision/loader/surface_control_loader.cpp

        # Display adapter
        vision/display/display_adapter.cpp
        vision/display/display_transaction.cpp

        # Screen capture
        vision/capture/virtual_display.cpp
        vision/capture/surface_control_capture.cpp
        vision/capture/vision_pipeline.cpp

        # BufferQueue pipeline
        vision/pipeline/buffer_queue_pipeline.cpp
        vision/pipeline/gl_consumer_wrapper.cpp

        # EGL/GPU
        vision/egl/egl_environment.cpp
        vision/egl/gpu_preprocessor.cpp

        # Java fallback
        vision/fallback/java_fallback.cpp
        vision/fallback/java_helper_receiver.cpp
)
target_include_directories(futon_vision PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/vision
        ${CMAKE_CURRENT_SOURCE_DIR}/vision/buffer
        ${CMAKE_CURRENT_SOURCE_DIR}/vision/capture
        ${CMAKE_CURRENT_SOURCE_DIR}/vision/display
        ${CMAKE_CURRENT_SOURCE_DIR}/vision/egl
        ${CMAKE_CURRENT_SOURCE_DIR}/vision/fallback
        ${CMAKE_CURRENT_SOURCE_DIR}/vision/loader
        ${CMAKE_CURRENT_SOURCE_DIR}/vision/pipeline
)
target_link_libraries(futon_vision
        futon_core
        ${FUTON_COMMON_LIBS}
        ${EGL_LIB}
        ${GLESV3_LIB}
        ${BINDER_NDK_LIB}
        dl
)


# Library: futon_inference
# GPU fence synchronization and hardware buffer utilities

add_library(futon_inference STATIC
        inference/fence_sync.cpp
        inference/hardware_buffer_bridge.cpp
        inference/nnapi_bridge.cpp
        inference/bounding_box.cpp
)
target_include_directories(futon_inference PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/inference
)
target_link_libraries(futon_inference
        futon_core
        futon_vision
        ${FUTON_COMMON_LIBS}
        ${EGL_LIB}
        ${GLESV3_LIB}
        dl
        neuralnetworks
)


# Library: futon_ppocrv5
# PPOCRv5 OCR engine with LiteRT C++ API

# LiteRT C++ wrapper sources
set(LITERT_CC_SOURCES
        # Core C++ wrappers from SDK
        ${CMAKE_CURRENT_SOURCE_DIR}/inference/litert_cc_sdk/include/litert/cc/litert_compiled_model.cc
        ${CMAKE_CURRENT_SOURCE_DIR}/inference/litert_cc_sdk/include/litert/cc/litert_macros.cc
        ${CMAKE_CURRENT_SOURCE_DIR}/inference/litert_cc_sdk/include/litert/cc/litert_model.cc
        ${CMAKE_CURRENT_SOURCE_DIR}/inference/litert_cc_sdk/include/litert/cc/litert_tensor_buffer.cc
        ${CMAKE_CURRENT_SOURCE_DIR}/inference/litert_cc_sdk/include/litert/cc/litert_opaque_options.cc
        ${CMAKE_CURRENT_SOURCE_DIR}/inference/litert_cc_sdk/include/litert/cc/litert_custom_op_kernel.cc
        # Simplified local implementations
        ${CMAKE_CURRENT_SOURCE_DIR}/inference/ppocrv5/litert_options_lite.cc
        ${CMAKE_CURRENT_SOURCE_DIR}/inference/ppocrv5/litert_tensor_buffer_types_lite.cc
        # C++ options wrappers
        ${CMAKE_CURRENT_SOURCE_DIR}/inference/litert_cc_sdk/include/litert/cc/options/litert_compiler_options.cc
        ${CMAKE_CURRENT_SOURCE_DIR}/inference/litert_cc_sdk/include/litert/cc/options/litert_cpu_options.cc
        ${CMAKE_CURRENT_SOURCE_DIR}/inference/litert_cc_sdk/include/litert/cc/options/litert_gpu_options.cc
        ${CMAKE_CURRENT_SOURCE_DIR}/inference/litert_cc_sdk/include/litert/cc/options/litert_runtime_options.cc
        # C++ internal utilities
        ${CMAKE_CURRENT_SOURCE_DIR}/inference/litert_cc_sdk/include/litert/cc/internal/scoped_file.cc
        ${CMAKE_CURRENT_SOURCE_DIR}/inference/litert_cc_sdk/include/litert/cc/internal/scoped_file_posix.cc
)

add_library(futon_ppocrv5 STATIC
        inference/ppocrv5/image_utils.cpp
        inference/ppocrv5/postprocess.cpp
        inference/ppocrv5/text_detector.cpp
        inference/ppocrv5/text_recognizer.cpp
        inference/ppocrv5/ocr_engine.cpp
        ${LITERT_CC_SOURCES}
)
target_include_directories(futon_ppocrv5 PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/inference
        ${CMAKE_CURRENT_SOURCE_DIR}/inference/ppocrv5
        ${CMAKE_CURRENT_SOURCE_DIR}/inference/litert_cc_sdk/include
)
target_link_libraries(futon_ppocrv5
        futon_core
        litert_cc_api
        litert_opencl
        ${FUTON_COMMON_LIBS}
        absl::span
        absl::flat_hash_map
        absl::strings
        absl::status
        absl::statusor
        absl::cleanup
        absl::log
        absl::log_internal_check_op
        absl::log_internal_message
        absl::algorithm_container
        absl::any_invocable
        absl::string_view
)


# Library: futon_input
# Touch device discovery, device cloning, and input injection

add_library(futon_input STATIC
        input/touch_device.cpp
        input/input_injector.cpp
        input/text_injector.cpp
        input/ime_controller.cpp
        input/shell_executor.cpp
        input/input_device_discovery.cpp
)
target_include_directories(futon_input PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/input
)
target_link_libraries(futon_input
        futon_core
        ${FUTON_COMMON_LIBS}
)

# ============================================================================= Library: futon_ipc
# Binder IPC service implementation

# Collect AIDL stub headers for dependency tracking
file(GLOB AIDL_STUB_HEADERS
        ${CMAKE_CURRENT_SOURCE_DIR}/ipc/aidl_stub/me/fleey/futon/*.h
)

add_library(futon_ipc STATIC
        ipc/futon_daemon_impl.cpp
        ipc/binder_service.cpp
        ${AIDL_STUB_HEADERS}  # Include headers for dependency tracking
)
target_include_directories(futon_ipc PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/ipc
        ${CMAKE_CURRENT_SOURCE_DIR}/ipc/aidl_stub
        ${CMAKE_CURRENT_SOURCE_DIR}/ipc/compat
)
target_link_libraries(futon_ipc
        futon_core
        futon_vision
        futon_inference
        futon_ppocrv5
        futon_input
        ${FUTON_COMMON_LIBS}
        ${BINDER_NDK_LIB}
        nativewindow
)

# Link AIDL generated code if available
if (AIDL_AVAILABLE)
    target_link_libraries(futon_ipc futon_aidl)
    target_include_directories(futon_ipc PUBLIC ${AIDL_GEN_DIR})
endif ()


# Library: futon_debug
# WebSocket debug stream for real-time telemetry

add_library(futon_debug STATIC
        debug/ws_frame.cpp
        debug/debug_stream.cpp
        debug/websocket_server.cpp
)
target_include_directories(futon_debug PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/debug
)
target_link_libraries(futon_debug
        futon_core
        ${FUTON_COMMON_LIBS}
)

# Link WebSocket library if available
if (WEBSOCKETS_AVAILABLE)
    target_compile_definitions(futon_debug PRIVATE FUTON_WEBSOCKETS_ENABLED=1)
else ()
    target_compile_definitions(futon_debug PRIVATE FUTON_WEBSOCKETS_ENABLED=0)
endif ()


# Library: futon_hotpath
# Hot-path autonomous decision router

add_library(futon_hotpath STATIC
        hotpath/hotpath_router.cpp
        hotpath/rule_parser.cpp
)
target_include_directories(futon_hotpath PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/hotpath
)
target_link_libraries(futon_hotpath
        futon_core
        ${FUTON_COMMON_LIBS}
)


# Executable: futon_daemon
# Main daemon executable

add_executable(futon_daemon
        main.cpp
)
target_link_libraries(futon_daemon
        futon_core
        futon_vision
        futon_inference
        futon_ppocrv5
        futon_input
        futon_ipc
        futon_debug
        futon_hotpath
        ${FUTON_COMMON_LIBS}
        ${BINDER_NDK_LIB}
        ${EGL_LIB}
        ${GLESV3_LIB}
)

# Set executable properties
set_target_properties(futon_daemon PROPERTIES
        POSITION_INDEPENDENT_CODE ON
)


# Tests (host build only, not for Android cross-compilation)

option(FUTON_BUILD_TESTS "Build unit tests (requires GTest and RapidCheck)" OFF)

if (FUTON_BUILD_TESTS AND NOT ANDROID)
    message(STATUS "Building tests...")
    add_subdirectory(test)
elseif (FUTON_BUILD_TESTS AND ANDROID)
    message(WARNING "Tests are not supported for Android cross-compilation. Use host build for testing.")
endif ()

# Print configuration summary
message(STATUS "=== Futon Daemon Build Configuration ===")
message(STATUS "Build type: ${CMAKE_BUILD_TYPE}")
message(STATUS "C++ standard: ${CMAKE_CXX_STANDARD}")
message(STATUS "NDK API level: ${ANDROID_NATIVE_API_LEVEL}")
message(STATUS "Libraries found:")
message(STATUS "  log: ${LOG_LIB}")
message(STATUS "  android: ${ANDROID_LIB}")
message(STATUS "  binder_ndk: ${BINDER_NDK_LIB}")
message(STATUS "  EGL: ${EGL_LIB}")
message(STATUS "  GLESv3: ${GLESV3_LIB}")
if (WEBSOCKETS_AVAILABLE)
    message(STATUS "  websockets: BUILT-IN (RFC 6455)")
else ()
    message(STATUS "  websockets: DISABLED")
endif ()
if (AIDL_AVAILABLE)
    message(STATUS "AIDL: ${AIDL_COMPILER}")
    message(STATUS "AIDL output: ${AIDL_GEN_DIR}")
else ()
    message(STATUS "AIDL: NOT AVAILABLE")
endif ()
message(STATUS "Modules:")
message(STATUS "  futon_core: ENABLED")
message(STATUS "  futon_vision: ENABLED")
message(STATUS "  futon_inference: ENABLED (utilities only, no TFLite)")
message(STATUS "  futon_ppocrv5: ENABLED (LiteRT C++ API, GPU accelerated)")
message(STATUS "  futon_input: ENABLED")
message(STATUS "  futon_ipc: ENABLED")
message(STATUS "  futon_debug: ENABLED (WebSocket: ${WEBSOCKETS_AVAILABLE})")
message(STATUS "  futon_hotpath: ENABLED")
message(STATUS "Security:")
message(STATUS "  Master switch: ${FUTON_ENABLE_SECURITY}")
if (FUTON_ENABLE_SECURITY)
    message(STATUS "  Anti-debugging: ${FUTON_ENABLE_ANTI_DEBUG}")
    message(STATUS "  Device binding: ${FUTON_ENABLE_DEVICE_BINDING}")
    message(STATUS "  Integrity check: ${FUTON_ENABLE_INTEGRITY_CHECK}")
    message(STATUS "  Watermarking: ${FUTON_ENABLE_WATERMARK}")
endif ()
if (FUTON_BUILD_TESTS AND NOT ANDROID)
    message(STATUS "Tests: ENABLED (host build)")
else ()
    message(STATUS "Tests: DISABLED (use -DFUTON_BUILD_TESTS=ON on host)")
endif ()
message(STATUS "=========================================")
