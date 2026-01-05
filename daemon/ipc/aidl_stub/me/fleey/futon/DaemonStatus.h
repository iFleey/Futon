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

#ifndef AIDL_ME_FLEEY_FUTON_DAEMON_STATUS_H
#define AIDL_ME_FLEEY_FUTON_DAEMON_STATUS_H

#include <android/binder_parcel.h>
#include <string>
#include <cstdint>

namespace aidl::me::fleey::futon {

    class DaemonStatus {
    public:
        int64_t timestampNs = 0;
        float fps = 0.0f;
        float captureLatencyMs = 0.0f;
        float inferenceLatencyMs = 0.0f;
        float totalLatencyMs = 0.0f;
        int32_t frameCount = 0;
        std::string activeDelegate;
        bool isRunning = false;
        int32_t hotPathProgress = 0;
        int32_t buffersInUse = 0;
        int32_t buffersAvailable = 0;

        binder_status_t readFromParcel(const AParcel *parcel) {
            // Read size prefix (Java AIDL format)
            int32_t parcelableSize = 0;
            AParcel_readInt32(parcel, &parcelableSize);

            AParcel_readInt64(parcel, &timestampNs);
            AParcel_readFloat(parcel, &fps);
            AParcel_readFloat(parcel, &captureLatencyMs);
            AParcel_readFloat(parcel, &inferenceLatencyMs);
            AParcel_readFloat(parcel, &totalLatencyMs);
            AParcel_readInt32(parcel, &frameCount);

            char *str = nullptr;
            AParcel_readString(parcel, &str, [](void *, int32_t len, char **buffer) -> bool {
                if (len < 0) return false;
                *buffer = new char[len + 1];
                return true;
            });
            if (str) {
                activeDelegate = str;
                delete[] str;
            }

            // Read boolean as int32 (Java format)
            int32_t boolVal = 0;
            AParcel_readInt32(parcel, &boolVal);
            isRunning = (boolVal != 0);

            AParcel_readInt32(parcel, &hotPathProgress);
            AParcel_readInt32(parcel, &buffersInUse);
            AParcel_readInt32(parcel, &buffersAvailable);

            return STATUS_OK;
        }

        binder_status_t writeToParcel(AParcel *parcel) const {
            // Get start position for size calculation
            int32_t startPos = AParcel_getDataPosition(parcel);

            // Write placeholder for size (will be updated at the end)
            AParcel_writeInt32(parcel, 0);

            AParcel_writeInt64(parcel, timestampNs);
            AParcel_writeFloat(parcel, fps);
            AParcel_writeFloat(parcel, captureLatencyMs);
            AParcel_writeFloat(parcel, inferenceLatencyMs);
            AParcel_writeFloat(parcel, totalLatencyMs);
            AParcel_writeInt32(parcel, frameCount);
            AParcel_writeString(parcel, activeDelegate.c_str(),
                                static_cast<int32_t>(activeDelegate.length()));

            // Write boolean as int32 (Java AIDL format)
            AParcel_writeInt32(parcel, isRunning ? 1 : 0);

            AParcel_writeInt32(parcel, hotPathProgress);
            AParcel_writeInt32(parcel, buffersInUse);
            AParcel_writeInt32(parcel, buffersAvailable);

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

#endif  // AIDL_ME_FLEEY_FUTON_DAEMON_STATUS_H
