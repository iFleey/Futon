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

#ifndef AIDL_ME_FLEEY_FUTON_SYSTEM_STATUS_H
#define AIDL_ME_FLEEY_FUTON_SYSTEM_STATUS_H

#include <android/binder_parcel.h>
#include <string>
#include <cstdint>

namespace aidl::me::fleey::futon {

    class SystemStatus {
    public:
        // Root status
        bool rootAvailable = false;
        std::string rootType;           // "magisk", "kernelsu", "apatch", "su", "none"
        std::string rootVersion;        // e.g., "27.0" for Magisk

        // SELinux status
        int32_t selinuxMode = 0;        // 0=unknown, 1=disabled, 2=permissive, 3=enforcing
        bool inputAccessAllowed = false;

        // Input device status
        bool canAccessDevInput = false;
        std::string touchDevicePath;    // e.g., "/dev/input/event3"
        int32_t maxTouchPoints = 1;
        std::string inputError;         // Error message if access denied

        // Daemon runtime info
        int32_t daemonPid = 0;
        int64_t uptimeMs = 0;
        std::string daemonVersion;

        binder_status_t readFromParcel(const AParcel *parcel) {
            // Read size prefix (Java AIDL format)
            int32_t parcelableSize = 0;
            AParcel_readInt32(parcel, &parcelableSize);

            // Read boolean as int32 (Java format: 0=false, non-zero=true)
            int32_t boolVal = 0;
            AParcel_readInt32(parcel, &boolVal);
            rootAvailable = (boolVal != 0);

            char *str = nullptr;
            auto readStringLambda = [](void *, int32_t len, char **buffer) -> bool {
                if (len < 0) return false;
                *buffer = new char[len + 1];
                return true;
            };

            AParcel_readString(parcel, &str, readStringLambda);
            if (str) {
                rootType = str;
                delete[] str;
                str = nullptr;
            }

            AParcel_readString(parcel, &str, readStringLambda);
            if (str) {
                rootVersion = str;
                delete[] str;
                str = nullptr;
            }

            AParcel_readInt32(parcel, &selinuxMode);

            AParcel_readInt32(parcel, &boolVal);
            inputAccessAllowed = (boolVal != 0);

            AParcel_readInt32(parcel, &boolVal);
            canAccessDevInput = (boolVal != 0);

            AParcel_readString(parcel, &str, readStringLambda);
            if (str) {
                touchDevicePath = str;
                delete[] str;
                str = nullptr;
            }

            AParcel_readInt32(parcel, &maxTouchPoints);

            AParcel_readString(parcel, &str, readStringLambda);
            if (str) {
                inputError = str;
                delete[] str;
                str = nullptr;
            }

            AParcel_readInt32(parcel, &daemonPid);
            AParcel_readInt64(parcel, &uptimeMs);

            AParcel_readString(parcel, &str, readStringLambda);
            if (str) {
                daemonVersion = str;
                delete[] str;
                str = nullptr;
            }

            return STATUS_OK;
        }

        binder_status_t writeToParcel(AParcel *parcel) const {
            // Get start position for size calculation
            int32_t startPos = AParcel_getDataPosition(parcel);

            // Write placeholder for size (will be updated at the end)
            AParcel_writeInt32(parcel, 0);

            // Write boolean as int32 (Java AIDL format)
            AParcel_writeInt32(parcel, rootAvailable ? 1 : 0);
            AParcel_writeString(parcel, rootType.c_str(), static_cast<int32_t>(rootType.length()));
            AParcel_writeString(parcel, rootVersion.c_str(),
                                static_cast<int32_t>(rootVersion.length()));

            AParcel_writeInt32(parcel, selinuxMode);
            AParcel_writeInt32(parcel, inputAccessAllowed ? 1 : 0);

            AParcel_writeInt32(parcel, canAccessDevInput ? 1 : 0);
            AParcel_writeString(parcel, touchDevicePath.c_str(),
                                static_cast<int32_t>(touchDevicePath.length()));
            AParcel_writeInt32(parcel, maxTouchPoints);
            AParcel_writeString(parcel, inputError.c_str(),
                                static_cast<int32_t>(inputError.length()));

            AParcel_writeInt32(parcel, daemonPid);
            AParcel_writeInt64(parcel, uptimeMs);
            AParcel_writeString(parcel, daemonVersion.c_str(),
                                static_cast<int32_t>(daemonVersion.length()));

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

#endif  // AIDL_ME_FLEEY_FUTON_SYSTEM_STATUS_H
