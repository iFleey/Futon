/*
 * Auto-generated AIDL stub - IBufferReleaseCallback interface
 * This is a simplified implementation for NDK Binder
 */

#ifndef AIDL_ME_FLEEY_FUTON_I_BUFFER_RELEASE_CALLBACK_H
#define AIDL_ME_FLEEY_FUTON_I_BUFFER_RELEASE_CALLBACK_H

#include <android/binder_ibinder.h>
#include <android/binder_parcel.h>
#include <memory>
#include "ipc/compat/binder_auto_utils.h"

namespace aidl::me::fleey::futon {

// Transaction codes for IBufferReleaseCallback
    enum class IBufferReleaseCallbackTransaction : int32_t {
        ON_BUFFER_RELEASE_REQUESTED = 1,
    };

    class IBufferReleaseCallback {
    public:
        virtual ~IBufferReleaseCallback() = default;

        // oneway void onBufferReleaseRequested(int bufferId, int timeoutMs)
        virtual ndk::ScopedAStatus
        onBufferReleaseRequested(int32_t bufferId, int32_t timeoutMs) = 0;

        virtual ndk::SpAIBinder asBinder() = 0;

        // Create proxy from binder
        static std::shared_ptr<IBufferReleaseCallback> fromBinder(const ndk::SpAIBinder &binder);

        static const char *descriptor;
    };

    inline const char *IBufferReleaseCallback::descriptor = "me.fleey.futon.IBufferReleaseCallback";

// Client proxy for IBufferReleaseCallback
    class BpBufferReleaseCallback : public IBufferReleaseCallback {
    public:
        explicit BpBufferReleaseCallback(const ndk::SpAIBinder &binder) : binder_(binder) {}

        ndk::ScopedAStatus onBufferReleaseRequested(int32_t bufferId, int32_t timeoutMs) override {
            AParcel *parcel_in = nullptr;
            AParcel *parcel_out = nullptr;

            binder_status_t status = AIBinder_prepareTransaction(binder_.get(), &parcel_in);
            if (status != STATUS_OK) {
                return ndk::ScopedAStatus::fromStatus(status);
            }

            AParcel_writeInt32(parcel_in, bufferId);
            AParcel_writeInt32(parcel_in, timeoutMs);

            // oneway call - FLAG_ONEWAY
            status = AIBinder_transact(
                    binder_.get(),
                    static_cast<transaction_code_t>(IBufferReleaseCallbackTransaction::ON_BUFFER_RELEASE_REQUESTED),
                    &parcel_in,
                    &parcel_out,
                    FLAG_ONEWAY
            );

            if (parcel_in) AParcel_delete(parcel_in);
            if (parcel_out) AParcel_delete(parcel_out);

            return ndk::ScopedAStatus::fromStatus(status);
        }

        ndk::SpAIBinder asBinder() override {
            return binder_;
        }

    private:
        ndk::SpAIBinder binder_;
    };

// Factory function to create proxy from binder
    inline std::shared_ptr<IBufferReleaseCallback> IBufferReleaseCallback_fromBinder(
            const ndk::SpAIBinder &binder
    ) {
        if (!binder.get()) {
            return nullptr;
        }
        return std::make_shared<BpBufferReleaseCallback>(binder);
    }

// Static method implementation
    inline std::shared_ptr<IBufferReleaseCallback> IBufferReleaseCallback::fromBinder(
            const ndk::SpAIBinder &binder
    ) {
        return IBufferReleaseCallback_fromBinder(binder);
    }

} // namespace aidl::me::fleey::futon

#endif // AIDL_ME_FLEEY_FUTON_I_BUFFER_RELEASE_CALLBACK_H
