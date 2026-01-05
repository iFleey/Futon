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


#ifndef AIDL_ME_FLEEY_FUTON_AUTHENTICATE_RESULT_H
#define AIDL_ME_FLEEY_FUTON_AUTHENTICATE_RESULT_H

#include <android/binder_parcel.h>
#include <cstdint>
#include <string>
#include <optional>

namespace aidl::me::fleey::futon {

    struct AuthenticateResult {
        bool success = false;
        bool requiresAttestation = false;
        std::optional<std::string> keyId;

        binder_status_t readFromParcel(const AParcel *parcel) {
            // Read size prefix (Java AIDL format)
            int32_t parcelableSize = 0;
            if (AParcel_readInt32(parcel, &parcelableSize) != STATUS_OK) return STATUS_BAD_VALUE;
            if (parcelableSize < 4) return STATUS_BAD_VALUE;

            // Read boolean as int32 (Java format)
            int32_t successVal = 0;
            int32_t attestVal = 0;

            if (AParcel_readInt32(parcel, &successVal) != STATUS_OK) return STATUS_BAD_VALUE;
            if (AParcel_readInt32(parcel, &attestVal) != STATUS_OK) return STATUS_BAD_VALUE;

            success = (successVal != 0);
            requiresAttestation = (attestVal != 0);

            // Read nullable string (keyId)
            char *str = nullptr;
            binder_status_t status = AParcel_readString(parcel, &str,
                                                        [](void *, int32_t len,
                                                           char **buf) -> bool {
                                                            if (len < 0) {
                                                                *buf = nullptr;
                                                                return true;  // null string is valid
                                                            }
                                                            *buf = new char[len + 1];
                                                            return true;
                                                        });
            if (status != STATUS_OK) return STATUS_BAD_VALUE;
            if (str) {
                keyId = str;
                delete[] str;
            } else {
                keyId = std::nullopt;
            }

            return STATUS_OK;
        }

        binder_status_t writeToParcel(AParcel *parcel) const {
            // Get start position for size calculation
            int32_t startPos = AParcel_getDataPosition(parcel);

            // Write placeholder for size (will be updated at the end)
            if (AParcel_writeInt32(parcel, 0) != STATUS_OK) return STATUS_BAD_VALUE;

            // Write boolean as int32 (Java AIDL format)
            if (AParcel_writeInt32(parcel, success ? 1 : 0) != STATUS_OK) return STATUS_BAD_VALUE;
            if (AParcel_writeInt32(parcel, requiresAttestation ? 1 : 0) != STATUS_OK)
                return STATUS_BAD_VALUE;

            // Write nullable string
            if (keyId.has_value()) {
                if (AParcel_writeString(parcel, keyId->c_str(),
                                        static_cast<int32_t>(keyId->size())) != STATUS_OK) {
                    return STATUS_BAD_VALUE;
                }
            } else {
                // Write null string (length = -1 in Java Parcel)
                if (AParcel_writeString(parcel, nullptr, -1) != STATUS_OK) {
                    return STATUS_BAD_VALUE;
                }
            }

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

#endif // AIDL_ME_FLEEY_FUTON_AUTHENTICATE_RESULT_H
