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

#ifndef AIDL_ME_FLEEY_FUTON_ISTATUS_CALLBACK_H
#define AIDL_ME_FLEEY_FUTON_ISTATUS_CALLBACK_H

#include <android/binder_ibinder.h>
#include <android/binder_parcel.h>
#include <memory>
#include <vector>
#include <string>

#include "DaemonStatus.h"
#include "DetectionResult.h"
#include "ipc/compat/binder_auto_utils.h"

namespace aidl::me::fleey::futon {

// Forward declaration
    class BpStatusCallback;

    class IStatusCallback {
    public:
        virtual ~IStatusCallback() = default;

        virtual ndk::ScopedAStatus onStatusUpdate(const DaemonStatus &status) = 0;

        virtual ndk::ScopedAStatus
        onDetectionResult(const std::vector<DetectionResult> &results) = 0;

        virtual ndk::ScopedAStatus
        onAutomationComplete(bool success, const std::string &message) = 0;

        virtual ndk::ScopedAStatus onError(int32_t code, const std::string &message) = 0;

        virtual ndk::ScopedAStatus onLoopDetected(int64_t stateHash, int32_t consecutiveCount) = 0;

        virtual ndk::ScopedAStatus onMemoryPressure(int32_t level) = 0;

        virtual ndk::ScopedAStatus
        onAsyncResult(int64_t requestId, const std::vector<uint8_t> &result) = 0;

        virtual ndk::SpAIBinder asBinder() = 0;

        // Create proxy from binder - defined after BpStatusCallback
        static std::shared_ptr<IStatusCallback> fromBinder(ndk::SpAIBinder binder);

        static const char *descriptor;
    };

    inline const char *IStatusCallback::descriptor = "me.fleey.futon.IStatusCallback";

// BpStatusCallback - Proxy implementation for client-side
    class BpStatusCallback : public IStatusCallback {
    public:
        explicit BpStatusCallback(AIBinder *binder) : binder_(binder) {
            if (binder_) AIBinder_incStrong(binder_);
        }

        ~BpStatusCallback() override {
            if (binder_) AIBinder_decStrong(binder_);
        }

        ndk::SpAIBinder asBinder() override { return ndk::SpAIBinder(binder_); }

        ndk::ScopedAStatus onStatusUpdate(const DaemonStatus &status) override {
            // Oneway call - fire and forget
            AParcel *parcel = nullptr;
            AIBinder_prepareTransaction(binder_, &parcel);

            // Write as typed object (null marker + parcelable)
            AParcel_writeInt32(parcel, 1);  // non-null marker
            status.writeToParcel(parcel);

            AIBinder_transact(binder_, 1, &parcel, nullptr, FLAG_ONEWAY);
            return ndk::ScopedAStatus::ok();
        }

        ndk::ScopedAStatus onDetectionResult(const std::vector<DetectionResult> &results) override {
            AParcel *parcel = nullptr;
            AIBinder_prepareTransaction(binder_, &parcel);

            // Write as typed array (length + each element with null marker)
            AParcel_writeInt32(parcel, static_cast<int32_t>(results.size()));
            for (const auto &r: results) {
                AParcel_writeInt32(parcel, 1);  // non-null marker for each element
                r.writeToParcel(parcel);
            }

            AIBinder_transact(binder_, 2, &parcel, nullptr, FLAG_ONEWAY);
            return ndk::ScopedAStatus::ok();
        }

        ndk::ScopedAStatus onAutomationComplete(bool success, const std::string &message) override {
            AParcel *parcel = nullptr;
            AIBinder_prepareTransaction(binder_, &parcel);

            // Write boolean as int32 (Java AIDL format)
            AParcel_writeInt32(parcel, success ? 1 : 0);
            AParcel_writeString(parcel, message.c_str(), static_cast<int32_t>(message.length()));

            AIBinder_transact(binder_, 3, &parcel, nullptr, FLAG_ONEWAY);
            return ndk::ScopedAStatus::ok();
        }

        ndk::ScopedAStatus onError(int32_t code, const std::string &message) override {
            AParcel *parcel = nullptr;
            AIBinder_prepareTransaction(binder_, &parcel);
            AParcel_writeInt32(parcel, code);
            AParcel_writeString(parcel, message.c_str(), static_cast<int32_t>(message.length()));
            AIBinder_transact(binder_, 4, &parcel, nullptr, FLAG_ONEWAY);
            return ndk::ScopedAStatus::ok();
        }

        ndk::ScopedAStatus onLoopDetected(int64_t stateHash, int32_t consecutiveCount) override {
            AParcel *parcel = nullptr;
            AIBinder_prepareTransaction(binder_, &parcel);
            AParcel_writeInt64(parcel, stateHash);
            AParcel_writeInt32(parcel, consecutiveCount);
            AIBinder_transact(binder_, 5, &parcel, nullptr, FLAG_ONEWAY);
            return ndk::ScopedAStatus::ok();
        }

        ndk::ScopedAStatus onMemoryPressure(int32_t level) override {
            AParcel *parcel = nullptr;
            AIBinder_prepareTransaction(binder_, &parcel);
            AParcel_writeInt32(parcel, level);
            AIBinder_transact(binder_, 6, &parcel, nullptr, FLAG_ONEWAY);
            return ndk::ScopedAStatus::ok();
        }

        ndk::ScopedAStatus
        onAsyncResult(int64_t requestId, const std::vector<uint8_t> &result) override {
            AParcel *parcel = nullptr;
            AIBinder_prepareTransaction(binder_, &parcel);
            AParcel_writeInt64(parcel, requestId);
            AParcel_writeByteArray(parcel,
                                   reinterpret_cast<const int8_t *>(result.data()),
                                   static_cast<int32_t>(result.size()));
            AIBinder_transact(binder_, 7, &parcel, nullptr, FLAG_ONEWAY);
            return ndk::ScopedAStatus::ok();
        }

    private:
        AIBinder *binder_;
    };

// Define fromBinder after BpStatusCallback is complete
    inline std::shared_ptr<IStatusCallback> IStatusCallback::fromBinder(ndk::SpAIBinder binder) {
        if (!binder.get()) return nullptr;
        return std::make_shared<BpStatusCallback>(binder.get());
    }

}  // namespace aidl::me::fleey::futon

#endif  // AIDL_ME_FLEEY_FUTON_ISTATUS_CALLBACK_H
