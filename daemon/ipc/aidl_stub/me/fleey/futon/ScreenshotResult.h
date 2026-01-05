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

#ifndef AIDL_ME_FLEEY_FUTON_SCREENSHOT_RESULT_H
#define AIDL_ME_FLEEY_FUTON_SCREENSHOT_RESULT_H

#include <android/binder_parcel.h>
#include <android/hardware_buffer.h>
#include <android/log.h>
#include <cstdint>
#include <dlfcn.h>

#define SR_LOG_TAG "ScreenshotResult"
#define SR_LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, SR_LOG_TAG, __VA_ARGS__)
#define SR_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, SR_LOG_TAG, __VA_ARGS__)

namespace aidl::me::fleey::futon {

    // Function pointer types for API 34+ HardwareBuffer Parcel APIs
    using AHardwareBuffer_writeToParcel_t = int (*)(AHardwareBuffer*, AParcel*);
    using AHardwareBuffer_readFromParcel_t = int (*)(const AParcel*, AHardwareBuffer**);

    // Dynamic loader for HardwareBuffer Parcel APIs (API 34+)
    class HardwareBufferParcelApi {
    public:
        static HardwareBufferParcelApi& instance() {
            static HardwareBufferParcelApi inst;
            return inst;
        }

        bool isAvailable() const { return write_fn_ != nullptr && read_fn_ != nullptr; }

        binder_status_t writeToParcel(AHardwareBuffer* buffer, AParcel* parcel) const {
            if (!write_fn_) return STATUS_INVALID_OPERATION;
            return static_cast<binder_status_t>(write_fn_(buffer, parcel));
        }

        binder_status_t readFromParcel(const AParcel* parcel, AHardwareBuffer** buffer) const {
            if (!read_fn_) return STATUS_INVALID_OPERATION;
            return static_cast<binder_status_t>(read_fn_(parcel, buffer));
        }

    private:
        HardwareBufferParcelApi() {
            // Try to load from libnativewindow.so (where these symbols live)
            void* handle = dlopen("libnativewindow.so", RTLD_NOW);
            if (handle) {
                write_fn_ = reinterpret_cast<AHardwareBuffer_writeToParcel_t>(
                    dlsym(handle, "AHardwareBuffer_writeToParcel"));
                read_fn_ = reinterpret_cast<AHardwareBuffer_readFromParcel_t>(
                    dlsym(handle, "AHardwareBuffer_readFromParcel"));
                
                if (write_fn_ && read_fn_) {
                    SR_LOGD("HardwareBuffer Parcel APIs loaded successfully (API 34+)");
                } else {
                    SR_LOGD("HardwareBuffer Parcel APIs not available (pre-API 34)");
                    write_fn_ = nullptr;
                    read_fn_ = nullptr;
                }
                // Don't dlclose - keep symbols loaded
            } else {
                SR_LOGD("libnativewindow.so not found");
            }
        }

        AHardwareBuffer_writeToParcel_t write_fn_ = nullptr;
        AHardwareBuffer_readFromParcel_t read_fn_ = nullptr;
    };

    struct ScreenshotResult {
        int32_t bufferId = -1;
        AHardwareBuffer *buffer = nullptr;
        int64_t timestampNs = 0;
        int32_t width = 0;
        int32_t height = 0;

        binder_status_t readFromParcel(const AParcel *parcel) {
            int32_t parcelableSize = 0;
            if (AParcel_readInt32(parcel, &parcelableSize) != STATUS_OK) return STATUS_BAD_VALUE;
            if (parcelableSize < 4) return STATUS_BAD_VALUE;

            if (AParcel_readInt32(parcel, &bufferId) != STATUS_OK) return STATUS_BAD_VALUE;

            int32_t hasBuffer = 0;
            if (AParcel_readInt32(parcel, &hasBuffer) != STATUS_OK) return STATUS_BAD_VALUE;
            if (hasBuffer != 0) {
                auto& api = HardwareBufferParcelApi::instance();
                if (api.isAvailable()) {
                    if (api.readFromParcel(parcel, &buffer) != STATUS_OK) {
                        buffer = nullptr;
                    }
                } else {
                    SR_LOGE("readFromParcel: HardwareBuffer Parcel API not available");
                    buffer = nullptr;
                }
            }

            if (AParcel_readInt64(parcel, &timestampNs) != STATUS_OK) return STATUS_BAD_VALUE;
            if (AParcel_readInt32(parcel, &width) != STATUS_OK) return STATUS_BAD_VALUE;
            if (AParcel_readInt32(parcel, &height) != STATUS_OK) return STATUS_BAD_VALUE;
            return STATUS_OK;
        }

        binder_status_t writeToParcel(AParcel *parcel) const {
            auto& api = HardwareBufferParcelApi::instance();
            SR_LOGD("writeToParcel: API available=%d, buffer=%p", api.isAvailable(), buffer);
            
            int32_t startPos = AParcel_getDataPosition(parcel);

            // Size placeholder
            if (AParcel_writeInt32(parcel, 0) != STATUS_OK) return STATUS_BAD_VALUE;

            if (AParcel_writeInt32(parcel, bufferId) != STATUS_OK) return STATUS_BAD_VALUE;

            // HardwareBuffer as typed object
            if (buffer && api.isAvailable()) {
                // Validate buffer before serialization
                AHardwareBuffer_Desc desc;
                AHardwareBuffer_describe(buffer, &desc);
                SR_LOGD("writeToParcel: buffer=%p, %ux%u, format=%u, usage=0x%llx",
                        buffer, desc.width, desc.height, desc.format,
                        (unsigned long long)desc.usage);

                // Write non-null marker
                if (AParcel_writeInt32(parcel, 1) != STATUS_OK) {
                    SR_LOGE("writeToParcel: failed to write null marker");
                    return STATUS_BAD_VALUE;
                }

                // Write HardwareBuffer using dynamic API
                binder_status_t status = api.writeToParcel(buffer, parcel);
                if (status != STATUS_OK) {
                    const char* errMsg = "unknown";
                    switch (status) {
                        case STATUS_BAD_VALUE: errMsg = "BAD_VALUE"; break;
                        case STATUS_NO_MEMORY: errMsg = "NO_MEMORY"; break;
                        case STATUS_FDS_NOT_ALLOWED: errMsg = "FDS_NOT_ALLOWED"; break;
                        case STATUS_INVALID_OPERATION: errMsg = "INVALID_OPERATION (API<34)"; break;
                        default: break;
                    }
                    SR_LOGE("writeToParcel: AHardwareBuffer_writeToParcel failed: %d (%s)", 
                            status, errMsg);
                    return STATUS_BAD_VALUE;
                }
                SR_LOGD("writeToParcel: HardwareBuffer written successfully");
            } else {
                // Null buffer or API not available
                if (buffer && !api.isAvailable()) {
                    SR_LOGD("writeToParcel: API < 34, cannot transfer HardwareBuffer via Binder");
                }
                if (AParcel_writeInt32(parcel, 0) != STATUS_OK) return STATUS_BAD_VALUE;
            }

            if (AParcel_writeInt64(parcel, timestampNs) != STATUS_OK) return STATUS_BAD_VALUE;
            if (AParcel_writeInt32(parcel, width) != STATUS_OK) return STATUS_BAD_VALUE;
            if (AParcel_writeInt32(parcel, height) != STATUS_OK) return STATUS_BAD_VALUE;

            // Update size
            int32_t endPos = AParcel_getDataPosition(parcel);
            int32_t parcelableSize = endPos - startPos;

            AParcel_setDataPosition(parcel, startPos);
            AParcel_writeInt32(parcel, parcelableSize);
            AParcel_setDataPosition(parcel, endPos);

            SR_LOGD("writeToParcel: total size=%d bytes", parcelableSize);
            return STATUS_OK;
        }
    };

} // namespace aidl::me::fleey::futon

#endif // AIDL_ME_FLEEY_FUTON_SCREENSHOT_RESULT_H
