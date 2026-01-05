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

#ifndef AIDL_ME_FLEEY_FUTON_SESSION_STATUS_H
#define AIDL_ME_FLEEY_FUTON_SESSION_STATUS_H

#include <android/binder_parcel.h>
#include <cstdint>

namespace aidl::me::fleey::futon {

    struct SessionStatus {
        bool hasActiveSession = false;
        bool isOwnSession = false;
        int64_t remainingTimeoutMs = 0;

        binder_status_t readFromParcel(const AParcel *parcel) {
            // Read size prefix (Java AIDL format)
            int32_t parcelableSize = 0;
            if (AParcel_readInt32(parcel, &parcelableSize) != STATUS_OK) return STATUS_BAD_VALUE;
            if (parcelableSize < 4) return STATUS_BAD_VALUE;

            // Read boolean as int32 (Java format)
            int32_t hasActive = 0;
            int32_t isOwn = 0;

            if (AParcel_readInt32(parcel, &hasActive) != STATUS_OK) return STATUS_BAD_VALUE;
            if (AParcel_readInt32(parcel, &isOwn) != STATUS_OK) return STATUS_BAD_VALUE;
            if (AParcel_readInt64(parcel, &remainingTimeoutMs) != STATUS_OK)
                return STATUS_BAD_VALUE;

            hasActiveSession = (hasActive != 0);
            isOwnSession = (isOwn != 0);
            return STATUS_OK;
        }

        binder_status_t writeToParcel(AParcel *parcel) const {
            // Get start position for size calculation
            int32_t startPos = AParcel_getDataPosition(parcel);

            // Write placeholder for size (will be updated at the end)
            if (AParcel_writeInt32(parcel, 0) != STATUS_OK) return STATUS_BAD_VALUE;

            // Write boolean as int32 (Java AIDL format)
            if (AParcel_writeInt32(parcel, hasActiveSession ? 1 : 0) != STATUS_OK)
                return STATUS_BAD_VALUE;
            if (AParcel_writeInt32(parcel, isOwnSession ? 1 : 0) != STATUS_OK)
                return STATUS_BAD_VALUE;
            if (AParcel_writeInt64(parcel, remainingTimeoutMs) != STATUS_OK)
                return STATUS_BAD_VALUE;

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

} // namespace aidl::me::fleey::futon

#endif // AIDL_ME_FLEEY_FUTON_SESSION_STATUS_H
