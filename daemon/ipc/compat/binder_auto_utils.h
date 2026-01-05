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

#ifndef FUTON_IPC_COMPAT_BINDER_AUTO_UTILS_H
#define FUTON_IPC_COMPAT_BINDER_AUTO_UTILS_H

#include <android/binder_ibinder.h>
#include <android/binder_parcel.h>
#include <android/binder_status.h>

#include <unistd.h>
#include <utility>

namespace ndk {

// ScopedAStatus - RAII wrapper for AStatus
    class ScopedAStatus {
    public:
        ScopedAStatus() : status_(nullptr) {}

        explicit ScopedAStatus(AStatus *status) : status_(status) {}

        ~ScopedAStatus() {
            if (status_) {
                AStatus_delete(status_);
            }
        }

        ScopedAStatus(ScopedAStatus &&other) noexcept: status_(other.status_) {
            other.status_ = nullptr;
        }

        ScopedAStatus &operator=(ScopedAStatus &&other) noexcept {
            if (this != &other) {
                if (status_) AStatus_delete(status_);
                status_ = other.status_;
                other.status_ = nullptr;
            }
            return *this;
        }

        ScopedAStatus(const ScopedAStatus &) = delete;

        ScopedAStatus &operator=(const ScopedAStatus &) = delete;

        AStatus *get() const { return status_; }

        AStatus *release() {
            AStatus *s = status_;
            status_ = nullptr;
            return s;
        }

        bool isOk() const { return status_ != nullptr && AStatus_isOk(status_); }

        binder_exception_t getExceptionCode() const { return AStatus_getExceptionCode(status_); }

        int32_t getServiceSpecificError() const { return AStatus_getServiceSpecificError(status_); }

        binder_status_t getStatus() const { return AStatus_getStatus(status_); }

        const char *getMessage() const { return AStatus_getMessage(status_); }

        static ScopedAStatus ok() { return ScopedAStatus(AStatus_newOk()); }

        static ScopedAStatus fromExceptionCode(binder_exception_t exception) {
            return ScopedAStatus(AStatus_fromExceptionCode(exception));
        }

        static ScopedAStatus fromExceptionCodeWithMessage(binder_exception_t exception,
                                                          const char *message) {
            return ScopedAStatus(AStatus_fromExceptionCodeWithMessage(exception, message));
        }

        static ScopedAStatus fromServiceSpecificError(int32_t serviceSpecific) {
            return ScopedAStatus(AStatus_fromServiceSpecificError(serviceSpecific));
        }

        static ScopedAStatus fromServiceSpecificErrorWithMessage(int32_t serviceSpecific,
                                                                 const char *message) {
            return ScopedAStatus(
                    AStatus_fromServiceSpecificErrorWithMessage(serviceSpecific, message));
        }

        static ScopedAStatus fromStatus(binder_status_t status) {
            return ScopedAStatus(AStatus_fromStatus(status));
        }

    private:
        AStatus *status_;
    };

// ScopedAIBinder_DeathRecipient - RAII wrapper for AIBinder_DeathRecipient
    class ScopedAIBinder_DeathRecipient {
    public:
        ScopedAIBinder_DeathRecipient() : recipient_(nullptr) {}

        explicit ScopedAIBinder_DeathRecipient(AIBinder_DeathRecipient *r) : recipient_(r) {}

        ~ScopedAIBinder_DeathRecipient() {
            if (recipient_) AIBinder_DeathRecipient_delete(recipient_);
        }

        ScopedAIBinder_DeathRecipient(ScopedAIBinder_DeathRecipient &&other) noexcept
                : recipient_(other.recipient_) {
            other.recipient_ = nullptr;
        }

        ScopedAIBinder_DeathRecipient &operator=(ScopedAIBinder_DeathRecipient &&other) noexcept {
            if (this != &other) {
                if (recipient_) AIBinder_DeathRecipient_delete(recipient_);
                recipient_ = other.recipient_;
                other.recipient_ = nullptr;
            }
            return *this;
        }

        AIBinder_DeathRecipient *get() const { return recipient_; }

    private:
        AIBinder_DeathRecipient *recipient_;
    };

// ScopedAIBinder_Weak - RAII wrapper for AIBinder_Weak
    class ScopedAIBinder_Weak {
    public:
        ScopedAIBinder_Weak() : weak_(nullptr) {}

        explicit ScopedAIBinder_Weak(AIBinder_Weak *w) : weak_(w) {}

        ~ScopedAIBinder_Weak() {
            if (weak_) AIBinder_Weak_delete(weak_);
        }

        ScopedAIBinder_Weak(ScopedAIBinder_Weak &&other) noexcept: weak_(other.weak_) {
            other.weak_ = nullptr;
        }

        ScopedAIBinder_Weak &operator=(ScopedAIBinder_Weak &&other) noexcept {
            if (this != &other) {
                if (weak_) AIBinder_Weak_delete(weak_);
                weak_ = other.weak_;
                other.weak_ = nullptr;
            }
            return *this;
        }

        AIBinder_Weak *get() const { return weak_; }

        static ScopedAIBinder_Weak fromBinder(AIBinder *binder) {
            return ScopedAIBinder_Weak(AIBinder_Weak_new(binder));
        }

    private:
        AIBinder_Weak *weak_;
    };

// ScopedAParcel - RAII wrapper for AParcel
    class ScopedAParcel {
    public:
        ScopedAParcel() : parcel_(nullptr) {}

        explicit ScopedAParcel(AParcel *p) : parcel_(p) {}

        ~ScopedAParcel() {
            if (parcel_) AParcel_delete(parcel_);
        }

        ScopedAParcel(ScopedAParcel &&other) noexcept: parcel_(other.parcel_) {
            other.parcel_ = nullptr;
        }

        ScopedAParcel &operator=(ScopedAParcel &&other) noexcept {
            if (this != &other) {
                if (parcel_) AParcel_delete(parcel_);
                parcel_ = other.parcel_;
                other.parcel_ = nullptr;
            }
            return *this;
        }

        AParcel *get() const { return parcel_; }

    private:
        AParcel *parcel_;
    };

// ScopedFileDescriptor - RAII wrapper for file descriptors
    class ScopedFileDescriptor {
    public:
        ScopedFileDescriptor() : fd_(-1) {}

        explicit ScopedFileDescriptor(int fd) : fd_(fd) {}

        ~ScopedFileDescriptor() { reset(); }

        ScopedFileDescriptor(ScopedFileDescriptor &&other) noexcept: fd_(other.fd_) {
            other.fd_ = -1;
        }

        ScopedFileDescriptor &operator=(ScopedFileDescriptor &&other) noexcept {
            if (this != &other) {
                reset();
                fd_ = other.fd_;
                other.fd_ = -1;
            }
            return *this;
        }

        ScopedFileDescriptor(const ScopedFileDescriptor &) = delete;

        ScopedFileDescriptor &operator=(const ScopedFileDescriptor &) = delete;

        void reset(int fd = -1) {
            if (fd_ >= 0) ::close(fd_);
            fd_ = fd;
        }

        void set(int fd) { reset(fd); }

        int get() const { return fd_; }

        int release() {
            int fd = fd_;
            fd_ = -1;
            return fd;
        }

        explicit operator bool() const { return fd_ >= 0; }

        ScopedFileDescriptor dup() const {
            if (fd_ < 0) return ScopedFileDescriptor();
            return ScopedFileDescriptor(::dup(fd_));
        }

    private:
        int fd_;
    };

// SpAIBinder - Smart pointer wrapper for AIBinder with reference counting
    class SpAIBinder {
    public:
        SpAIBinder() : binder_(nullptr) {}

        explicit SpAIBinder(AIBinder *binder) : binder_(binder) {}

        SpAIBinder(const SpAIBinder &other) : binder_(other.binder_) {
            if (binder_) AIBinder_incStrong(binder_);
        }

        SpAIBinder(SpAIBinder &&other) noexcept: binder_(other.binder_) {
            other.binder_ = nullptr;
        }

        ~SpAIBinder() {
            if (binder_) AIBinder_decStrong(binder_);
        }

        SpAIBinder &operator=(const SpAIBinder &other) {
            if (this != &other) {
                if (binder_) AIBinder_decStrong(binder_);
                binder_ = other.binder_;
                if (binder_) AIBinder_incStrong(binder_);
            }
            return *this;
        }

        SpAIBinder &operator=(SpAIBinder &&other) noexcept {
            if (this != &other) {
                if (binder_) AIBinder_decStrong(binder_);
                binder_ = other.binder_;
                other.binder_ = nullptr;
            }
            return *this;
        }

        AIBinder *get() const { return binder_; }

        AIBinder **getR() { return &binder_; }

        explicit operator bool() const { return binder_ != nullptr; }

    private:
        AIBinder *binder_;
    };

}  // namespace ndk

#endif  // FUTON_IPC_COMPAT_BINDER_AUTO_UTILS_H
