# libseccomp for Futon Daemon
#
# Build libseccomp from source for Android.
# libseccomp provides a high-level interface to the Linux seccomp filter.
#
# Android NDK 29+ includes linux/seccomp.h with struct definitions that conflict
# with libseccomp's internal definitions. We use a pre-patched system.h that
# conditionally uses NDK's types on Android.

cmake_minimum_required(VERSION 3.22.1)

enable_language(C)

include(FetchContent)

# Use official libseccomp release tarball
set(LIBSECCOMP_VERSION "2.5.5")
FetchContent_Declare(
        libseccomp
        URL https://github.com/seccomp/libseccomp/releases/download/v${LIBSECCOMP_VERSION}/libseccomp-${LIBSECCOMP_VERSION}.tar.gz
)

# Use MakeAvailable (CMake 3.14+) instead of deprecated Populate
FetchContent_MakeAvailable(libseccomp)

# Apply Android-patched system.h after fetch
set(PATCHED_SYSTEM_H ${CMAKE_CURRENT_SOURCE_DIR}/system.h.android)
set(TARGET_SYSTEM_H ${libseccomp_SOURCE_DIR}/src/system.h)

if (EXISTS ${PATCHED_SYSTEM_H})
    message(STATUS "Applying Android-patched system.h to libseccomp")
    file(COPY_FILE ${PATCHED_SYSTEM_H} ${TARGET_SYSTEM_H})
else ()
    message(FATAL_ERROR "Patched system.h.android not found at ${PATCHED_SYSTEM_H}")
endif ()

set(LIBSECCOMP_SOURCE_DIR ${libseccomp_SOURCE_DIR})

# Create a static library from libseccomp sources
# NOTE: syscalls.c and syscalls.perf.c are REQUIRED - they contain:
#   - syscall_resolve_name/num (gperf-generated lookup tables)
#   - abi_syscall_resolve_name/num_munge (ABI translation)
#   - abi_syscall_rewrite, abi_rule_add (rule processing)
set(LIBSECCOMP_SOURCES
        ${LIBSECCOMP_SOURCE_DIR}/src/api.c
        ${LIBSECCOMP_SOURCE_DIR}/src/arch.c
        ${LIBSECCOMP_SOURCE_DIR}/src/arch-aarch64.c
        ${LIBSECCOMP_SOURCE_DIR}/src/arch-arm.c
        ${LIBSECCOMP_SOURCE_DIR}/src/arch-x86.c
        ${LIBSECCOMP_SOURCE_DIR}/src/arch-x86_64.c
        ${LIBSECCOMP_SOURCE_DIR}/src/arch-x32.c
        ${LIBSECCOMP_SOURCE_DIR}/src/arch-mips.c
        ${LIBSECCOMP_SOURCE_DIR}/src/arch-mips64.c
        ${LIBSECCOMP_SOURCE_DIR}/src/arch-mips64n32.c
        ${LIBSECCOMP_SOURCE_DIR}/src/arch-parisc.c
        ${LIBSECCOMP_SOURCE_DIR}/src/arch-parisc64.c
        ${LIBSECCOMP_SOURCE_DIR}/src/arch-ppc.c
        ${LIBSECCOMP_SOURCE_DIR}/src/arch-ppc64.c
        ${LIBSECCOMP_SOURCE_DIR}/src/arch-riscv64.c
        ${LIBSECCOMP_SOURCE_DIR}/src/arch-s390.c
        ${LIBSECCOMP_SOURCE_DIR}/src/arch-s390x.c
        ${LIBSECCOMP_SOURCE_DIR}/src/db.c
        ${LIBSECCOMP_SOURCE_DIR}/src/gen_bpf.c
        ${LIBSECCOMP_SOURCE_DIR}/src/gen_pfc.c
        ${LIBSECCOMP_SOURCE_DIR}/src/hash.c
        ${LIBSECCOMP_SOURCE_DIR}/src/helper.c
        ${LIBSECCOMP_SOURCE_DIR}/src/system.c
        ${LIBSECCOMP_SOURCE_DIR}/src/syscalls.c
        ${LIBSECCOMP_SOURCE_DIR}/src/syscalls.perf.c
)

add_library(seccomp_lib STATIC ${LIBSECCOMP_SOURCES})

target_include_directories(seccomp_lib PUBLIC
        ${LIBSECCOMP_SOURCE_DIR}/include
)

target_include_directories(seccomp_lib PRIVATE
        ${LIBSECCOMP_SOURCE_DIR}/src
        ${CMAKE_CURRENT_BINARY_DIR}
)

# Generate configure.h with necessary defines
file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/configure.h
        "#ifndef _CONFIGURE_H
#define _CONFIGURE_H

#define PACKAGE \"libseccomp\"
#define PACKAGE_VERSION \"${LIBSECCOMP_VERSION}\"
#define VERSION \"${LIBSECCOMP_VERSION}\"

/* Android always has seccomp support in kernel */
#define HAVE_LINUX_SECCOMP_H 1

#endif
")

target_compile_definitions(seccomp_lib PRIVATE
        HAVE_CONFIG_H=0
)

# Note: _GNU_SOURCE is already defined via command line, don't redefine

# Suppress warnings in third-party code
target_compile_options(seccomp_lib PRIVATE
        -Wno-unused-parameter
        -Wno-sign-compare
        -Wno-macro-redefined
)

# Export include directory to parent scope
set(LIBSECCOMP_INCLUDE_DIR ${LIBSECCOMP_SOURCE_DIR}/include PARENT_SCOPE)

message(STATUS "libseccomp ${LIBSECCOMP_VERSION} configured from source:")
message(STATUS "  Source: ${LIBSECCOMP_SOURCE_DIR}")
message(STATUS "  Include: ${LIBSECCOMP_SOURCE_DIR}/include")
