# CMakeLists.txt for Futon Native
#
# This module provides:
# - JNI bindings for llama.cpp to enable on-device LLM inference
#
# All other native functionality (input injection, perception, daemon) has been
# migrated to the standalone futon_daemon process in the daemon/ directory.

cmake_minimum_required(VERSION 3.22.1)

project("futon_native" LANGUAGES CXX)

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Enable optimizations for release builds
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG "-g -O0")

# Find required libraries
find_library(log-lib log)
find_library(nativewindow-lib nativewindow)
find_library(android-lib android)

# llama.cpp Integration (llama_android)
# This is the only native library built for the app module.
# It provides on-device LLM inference for AI decision making.

set(LLAMA_CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)

if (EXISTS ${LLAMA_CPP_DIR}/CMakeLists.txt)
    message(STATUS "=== llama.cpp Integration ===")
    message(STATUS "llama.cpp found at ${LLAMA_CPP_DIR}")

    # llama.cpp build options for Android (CPU only)
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(LLAMA_NATIVE OFF CACHE BOOL "" FORCE)
    set(LLAMA_METAL OFF CACHE BOOL "" FORCE)
    set(LLAMA_CUDA OFF CACHE BOOL "" FORCE)
    set(LLAMA_VULKAN OFF CACHE BOOL "" FORCE)
    set(LLAMA_HIPBLAS OFF CACHE BOOL "" FORCE)
    set(LLAMA_SYCL OFF CACHE BOOL "" FORCE)
    set(LLAMA_KOMPUTE OFF CACHE BOOL "" FORCE)
    set(LLAMA_BLAS OFF CACHE BOOL "" FORCE)
    set(LLAMA_ACCELERATE OFF CACHE BOOL "" FORCE)
    set(LLAMA_OPENMP OFF CACHE BOOL "" FORCE)
    set(LLAMA_RPC OFF CACHE BOOL "" FORCE)
    set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
    set(LLAMA_LTO OFF CACHE BOOL "" FORCE)

    # Add llama.cpp as subdirectory
    add_subdirectory(${LLAMA_CPP_DIR} llama.cpp)

    # Create JNI bridge library
    add_library(llama_android SHARED llama_jni.cpp)

    # Define that llama.cpp is available
    target_compile_definitions(llama_android PRIVATE LLAMA_AVAILABLE=1)

    # Link llama.cpp libraries, nativewindow and android for HardwareBuffer support
    target_link_libraries(llama_android llama ggml ${log-lib} ${nativewindow-lib} ${android-lib})

    # Include directories
    target_include_directories(llama_android PRIVATE
            ${CMAKE_CURRENT_SOURCE_DIR}
            ${LLAMA_CPP_DIR}/include
            ${LLAMA_CPP_DIR}/ggml/include
    )

    # Check for CLIP/LLaVA support
    if (EXISTS ${LLAMA_CPP_DIR}/examples/llava/clip.h)
        message(STATUS "CLIP/LLaVA support available")
        target_compile_definitions(llama_android PRIVATE CLIP_AVAILABLE=1)
        target_include_directories(llama_android PRIVATE ${LLAMA_CPP_DIR}/examples/llava)
    else ()
        message(STATUS "CLIP/LLaVA support not available")
    endif ()

    # Compiler options
    target_compile_options(llama_android PRIVATE -Wall -Wextra -Werror=return-type -fexceptions -frtti)

    message(STATUS "llama_android JNI bridge configured")

else ()
    message(WARNING "llama.cpp not found at ${LLAMA_CPP_DIR}")
    message(WARNING "Building stub library without llama.cpp support")
    message(WARNING "To enable local model support, run:")
    message(WARNING "  git submodule add https://github.com/ggerganov/llama.cpp.git app/src/main/cpp/llama.cpp")

    # Create stub library without llama.cpp
    add_library(llama_android SHARED llama_jni.cpp)
    target_compile_definitions(llama_android PRIVATE LLAMA_AVAILABLE=0 CLIP_AVAILABLE=0)
    target_link_libraries(llama_android ${log-lib} ${nativewindow-lib} ${android-lib})
    target_include_directories(llama_android PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
    target_compile_options(llama_android PRIVATE -Wall -Wextra -Werror=return-type)
endif ()
