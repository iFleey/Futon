# BoringSSL for Futon Daemon
#
# Build BoringSSL from source for Android.
# BoringSSL is Google's fork of OpenSSL, used in Android and Chrome.

cmake_minimum_required(VERSION 3.22.1)

include(FetchContent)

# Use official BoringSSL from Google (main branch)
FetchContent_Declare(
        boringssl
        GIT_REPOSITORY https://boringssl.googlesource.com/boringssl
        GIT_TAG main
        GIT_SHALLOW TRUE
)

# Configure BoringSSL build options before fetching
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

FetchContent_MakeAvailable(boringssl)

# Export include directory to parent scope
set(BORINGSSL_INCLUDE_DIR ${boringssl_SOURCE_DIR}/include PARENT_SCOPE)

message(STATUS "BoringSSL configured from source:")
message(STATUS "  Source: ${boringssl_SOURCE_DIR}")
message(STATUS "  Include: ${boringssl_SOURCE_DIR}/include")
