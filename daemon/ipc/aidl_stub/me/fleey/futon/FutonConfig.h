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

#ifndef AIDL_ME_FLEEY_FUTON_FUTON_CONFIG_H
#define AIDL_ME_FLEEY_FUTON_FUTON_CONFIG_H

#include <android/binder_parcel.h>
#include <string>
#include <cstdint>

namespace aidl::me::fleey::futon {

    class FutonConfig {
    public:
        int32_t captureWidth = 0;
        int32_t captureHeight = 0;
        int32_t targetFps = 0;
        std::string modelPath;
        std::string ocrDetModelPath;
        std::string ocrRecModelPath;
        std::string ocrKeysPath;
        float minConfidence = 0.0f;
        bool enableDebugStream = false;
        int32_t debugStreamPort = 0;
        int32_t statusUpdateIntervalMs = 0;
        int32_t bufferPoolSize = 0;
        int32_t hotPathNoMatchThreshold = 0;
        std::string touchDevicePath;  // User-selected touch device, empty = auto-detect

        binder_status_t readFromParcel(const AParcel *parcel) {
            // Read size prefix (Java AIDL format)
            int32_t parcelableSize = 0;
            binder_status_t status = AParcel_readInt32(parcel, &parcelableSize);
            if (status != STATUS_OK) return status;

            status = AParcel_readInt32(parcel, &captureWidth);
            if (status != STATUS_OK) return status;

            status = AParcel_readInt32(parcel, &captureHeight);
            if (status != STATUS_OK) return status;

            status = AParcel_readInt32(parcel, &targetFps);
            if (status != STATUS_OK) return status;

            // Lambda that handles both null and non-null strings
            auto readStringLambda = [](void *, int32_t len, char **buffer) -> bool {
                if (len < 0) {
                    // Null string - this is valid
                    *buffer = nullptr;
                    return true;
                }
                *buffer = new char[len + 1];
                return true;
            };

            char *str = nullptr;

            status = AParcel_readString(parcel, &str, readStringLambda);
            if (status != STATUS_OK) return status;
            if (str) {
                modelPath = str;
                delete[] str;
                str = nullptr;
            } else { modelPath.clear(); }

            status = AParcel_readString(parcel, &str, readStringLambda);
            if (status != STATUS_OK) return status;
            if (str) {
                ocrDetModelPath = str;
                delete[] str;
                str = nullptr;
            } else { ocrDetModelPath.clear(); }

            status = AParcel_readString(parcel, &str, readStringLambda);
            if (status != STATUS_OK) return status;
            if (str) {
                ocrRecModelPath = str;
                delete[] str;
                str = nullptr;
            } else { ocrRecModelPath.clear(); }

            status = AParcel_readString(parcel, &str, readStringLambda);
            if (status != STATUS_OK) return status;
            if (str) {
                ocrKeysPath = str;
                delete[] str;
                str = nullptr;
            } else { ocrKeysPath.clear(); }

            status = AParcel_readFloat(parcel, &minConfidence);
            if (status != STATUS_OK) return status;

            // Read boolean as int32 (Java format)
            int32_t boolVal = 0;
            status = AParcel_readInt32(parcel, &boolVal);
            if (status != STATUS_OK) return status;
            enableDebugStream = (boolVal != 0);

            status = AParcel_readInt32(parcel, &debugStreamPort);
            if (status != STATUS_OK) return status;

            status = AParcel_readInt32(parcel, &statusUpdateIntervalMs);
            if (status != STATUS_OK) return status;

            status = AParcel_readInt32(parcel, &bufferPoolSize);
            if (status != STATUS_OK) return status;

            status = AParcel_readInt32(parcel, &hotPathNoMatchThreshold);
            if (status != STATUS_OK) return status;

            // Read touchDevicePath
            status = AParcel_readString(parcel, &str, readStringLambda);
            if (status != STATUS_OK) return status;
            if (str) {
                touchDevicePath = str;
                delete[] str;
                str = nullptr;
            } else { touchDevicePath.clear(); }

            return STATUS_OK;
        }

        binder_status_t writeToParcel(AParcel *parcel) const {
            // Get start position for size calculation
            int32_t startPos = AParcel_getDataPosition(parcel);

            // Write placeholder for size (will be updated at the end)
            AParcel_writeInt32(parcel, 0);

            AParcel_writeInt32(parcel, captureWidth);
            AParcel_writeInt32(parcel, captureHeight);
            AParcel_writeInt32(parcel, targetFps);
            AParcel_writeString(parcel, modelPath.c_str(),
                                static_cast<int32_t>(modelPath.length()));
            AParcel_writeString(parcel, ocrDetModelPath.c_str(),
                                static_cast<int32_t>(ocrDetModelPath.length()));
            AParcel_writeString(parcel, ocrRecModelPath.c_str(),
                                static_cast<int32_t>(ocrRecModelPath.length()));
            AParcel_writeString(parcel, ocrKeysPath.c_str(),
                                static_cast<int32_t>(ocrKeysPath.length()));
            AParcel_writeFloat(parcel, minConfidence);

            // Write boolean as int32 (Java AIDL format)
            AParcel_writeInt32(parcel, enableDebugStream ? 1 : 0);

            AParcel_writeInt32(parcel, debugStreamPort);
            AParcel_writeInt32(parcel, statusUpdateIntervalMs);
            AParcel_writeInt32(parcel, bufferPoolSize);
            AParcel_writeInt32(parcel, hotPathNoMatchThreshold);
            AParcel_writeString(parcel, touchDevicePath.c_str(),
                                static_cast<int32_t>(touchDevicePath.length()));

            // Calculate and write actual size
            int32_t endPos = AParcel_getDataPosition(parcel);
            int32_t parcelableSize = endPos - startPos;

            // Go back to start and write the actual size
            AParcel_setDataPosition(parcel, startPos);
            AParcel_writeInt32(parcel, parcelableSize);

            // Restore position to end
            AParcel_setDataPosition(parcel, endPos);

            return STATUS_OK;
        }
    };

}  // namespace aidl::me::fleey::futon

#endif  // AIDL_ME_FLEEY_FUTON_FUTON_CONFIG_H
