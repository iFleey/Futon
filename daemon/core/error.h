/*
 * Futon - Android Automation Daemon
 * Copyright (C) 2025 Fleey
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

#ifndef FUTON_CORE_ERROR_H
#define FUTON_CORE_ERROR_H

#include <android/log.h>
#include <unistd.h>
#include <variant>
#include <string>
#include <cerrno>
#include <cstring>

namespace futon::core {

// Log tag for all Futon components
#define FUTON_LOG_TAG "futon_daemon"

// Logging macros
#define FUTON_LOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, FUTON_LOG_TAG, __VA_ARGS__)
#define FUTON_LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, FUTON_LOG_TAG, __VA_ARGS__)
#define FUTON_LOGI(...) __android_log_print(ANDROID_LOG_INFO, FUTON_LOG_TAG, __VA_ARGS__)
#define FUTON_LOGW(...) __android_log_print(ANDROID_LOG_WARN, FUTON_LOG_TAG, __VA_ARGS__)
#define FUTON_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, FUTON_LOG_TAG, __VA_ARGS__)
#define FUTON_LOGF(...) __android_log_print(ANDROID_LOG_FATAL, FUTON_LOG_TAG, __VA_ARGS__)

// Log with errno information
#define FUTON_LOGE_ERRNO(msg) \
    FUTON_LOGE("%s: %s (errno=%d)", msg, strerror(errno), errno)

// Error codes for Futon daemon operations
    enum class FutonError {
        Ok = 0,
        PermissionDenied = 0x4C01,
        DeviceNotFound = 0x4C02,
        ResourceExhausted = 0x4C03,
        InvalidArgument = 0x4C04,
        NotInitialized = 0x4C05,
        Timeout = 0x4C06,
        FenceTimeout = 0x4C07,
        DelegateReset = 0x4C08,
        PrivateApiUnavailable = 0x4C09,
        NotSupported = 0x4C0A,
        InternalError = 0x4CFF
    };

// Convert error code to string for logging
    const char *error_to_string(FutonError err);

// Result template class for error handling
    template<typename T>
    class Result {
    public:
        static Result<T> ok(T value) {
            Result<T> r;
            r.data_ = std::move(value);
            return r;
        }

        static Result<T> error(FutonError err) {
            Result<T> r;
            r.data_ = err;
            return r;
        }

        bool is_ok() const {
            return std::holds_alternative<T>(data_);
        }

        bool is_error() const {
            return std::holds_alternative<FutonError>(data_);
        }

        T &value() {
            return std::get<T>(data_);
        }

        const T &value() const {
            return std::get<T>(data_);
        }

        FutonError error() const {
            return std::get<FutonError>(data_);
        }

        // Convenience operator for boolean context
        explicit operator bool() const {
            return is_ok();
        }

    private:
        std::variant<T, FutonError> data_;
    };

// Specialization for void result (success/failure only)
    template<>
    class Result<void> {
    public:
        FutonError error_ = FutonError::Ok;
        std::string message;

        static Result<void> ok() {
            Result<void> r;
            r.error_ = FutonError::Ok;
            return r;
        }

        static Result<void> err(FutonError e) {
            Result<void> r;
            r.error_ = e;
            r.message = error_to_string(e);
            return r;
        }

        static Result<void> err(FutonError e, const std::string &msg) {
            Result<void> r;
            r.error_ = e;
            r.message = msg;
            return r;
        }

        bool is_ok() const {
            return error_ == FutonError::Ok;
        }

        bool is_error() const {
            return error_ != FutonError::Ok;
        }

        // API consistency with Result<T>
        FutonError error() const {
            return error_;
        }

        explicit operator bool() const {
            return is_ok();
        }
    };

// RAII wrapper for file descriptors
    class ScopedFd {
    public:
        explicit ScopedFd(int fd = -1) : fd_(fd) {}

        ~ScopedFd() {
            reset();
        }

        // Move constructor
        ScopedFd(ScopedFd &&other) noexcept: fd_(other.release()) {}

        // Move assignment
        ScopedFd &operator=(ScopedFd &&other) noexcept {
            if (this != &other) {
                reset(other.release());
            }
            return *this;
        }

        // Disable copy
        ScopedFd(const ScopedFd &) = delete;

        ScopedFd &operator=(const ScopedFd &) = delete;

        int get() const { return fd_; }

        int release() {
            int fd = fd_;
            fd_ = -1;
            return fd;
        }

        void reset(int fd = -1) {
            if (fd_ >= 0) {
                close(fd_);
            }
            fd_ = fd;
        }

        bool is_valid() const {
            return fd_ >= 0;
        }

        explicit operator bool() const {
            return is_valid();
        }

    private:
        int fd_;
    };

} // namespace futon::core

#endif // FUTON_CORE_ERROR_H
