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

#ifndef AIDL_ME_FLEEY_FUTON_INPUT_DEVICE_ENTRY_H
#define AIDL_ME_FLEEY_FUTON_INPUT_DEVICE_ENTRY_H

#include <android/binder_parcel.h>
#include <string>
#include <cstdint>

namespace aidl::me::fleey::futon {

    class InputDeviceEntry {
    public:
        std::string path;
        std::string name;
        bool isTouchscreen = false;
        bool supportsMultiTouch = false;
        int32_t mtProtocol = 0;
        int32_t maxX = 0;
        int32_t maxY = 0;
        int32_t maxTouchPoints = 0;
        int32_t touchscreenProbability = 0;
        std::string probabilityReason;

        binder_status_t readFromParcel(const AParcel *parcel) {
            // Read size prefix (Java AIDL format)
            int32_t parcelableSize = 0;
            AParcel_readInt32(parcel, &parcelableSize);

            // Read path string
            char *str = nullptr;
            AParcel_readString(parcel, &str, [](void *, int32_t len, char **buffer) -> bool {
                if (len < 0) return false;
                *buffer = new char[len + 1];
                return true;
            });
            if (str) {
                path = str;
                delete[] str;
            }

            // Read name string
            str = nullptr;
            AParcel_readString(parcel, &str, [](void *, int32_t len, char **buffer) -> bool {
                if (len < 0) return false;
                *buffer = new char[len + 1];
                return true;
            });
            if (str) {
                name = str;
                delete[] str;
            }

            // Read booleans as int32 (Java format)
            int32_t boolVal = 0;
            AParcel_readInt32(parcel, &boolVal);
            isTouchscreen = (boolVal != 0);

            AParcel_readInt32(parcel, &boolVal);
            supportsMultiTouch = (boolVal != 0);

            AParcel_readInt32(parcel, &mtProtocol);
            AParcel_readInt32(parcel, &maxX);
            AParcel_readInt32(parcel, &maxY);
            AParcel_readInt32(parcel, &maxTouchPoints);
            AParcel_readInt32(parcel, &touchscreenProbability);

            // Read probabilityReason string
            str = nullptr;
            AParcel_readString(parcel, &str, [](void *, int32_t len, char **buffer) -> bool {
                if (len < 0) return false;
                *buffer = new char[len + 1];
                return true;
            });
            if (str) {
                probabilityReason = str;
                delete[] str;
            }

            return STATUS_OK;
        }

        binder_status_t writeToParcel(AParcel *parcel) const {
            // Get start position for size calculation
            int32_t startPos = AParcel_getDataPosition(parcel);

            // Write placeholder for size (will be updated at the end)
            AParcel_writeInt32(parcel, 0);

            AParcel_writeString(parcel, path.c_str(), static_cast<int32_t>(path.length()));
            AParcel_writeString(parcel, name.c_str(), static_cast<int32_t>(name.length()));

            // Write booleans as int32 (Java AIDL format)
            AParcel_writeInt32(parcel, isTouchscreen ? 1 : 0);
            AParcel_writeInt32(parcel, supportsMultiTouch ? 1 : 0);

            AParcel_writeInt32(parcel, mtProtocol);
            AParcel_writeInt32(parcel, maxX);
            AParcel_writeInt32(parcel, maxY);
            AParcel_writeInt32(parcel, maxTouchPoints);
            AParcel_writeInt32(parcel, touchscreenProbability);
            AParcel_writeString(parcel, probabilityReason.c_str(),
                                static_cast<int32_t>(probabilityReason.length()));

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

#endif  // AIDL_ME_FLEEY_FUTON_INPUT_DEVICE_ENTRY_H
