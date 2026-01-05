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

#ifndef AIDL_ME_FLEEY_FUTON_DAEMON_ATTESTATION_CERTIFICATE_H
#define AIDL_ME_FLEEY_FUTON_DAEMON_ATTESTATION_CERTIFICATE_H

#include <android/binder_parcel.h>
#include <cstdint>
#include <vector>

namespace aidl::me::fleey::futon {

    struct AttestationCertificate {
        std::vector<uint8_t> data;

        binder_status_t readFromParcel(const AParcel *parcel) {
            // Read size prefix (Java AIDL format)
            int32_t parcelableSize = 0;
            if (AParcel_readInt32(parcel, &parcelableSize) != STATUS_OK) return STATUS_BAD_VALUE;
            if (parcelableSize < 4) return STATUS_BAD_VALUE;

            // Read byte array
            data.clear();
            binder_status_t status = AParcel_readByteArray(parcel, &data,
                                                           [](void *arrayData, int32_t length,
                                                              int8_t **outBuffer) -> bool {
                                                               if (length < 0) {
                                                                   *outBuffer = nullptr;
                                                                   return true;
                                                               }
                                                               auto *vec = static_cast<std::vector<uint8_t> *>(arrayData);
                                                               vec->resize(
                                                                       static_cast<size_t>(length));
                                                               *outBuffer = reinterpret_cast<int8_t *>(vec->data());
                                                               return true;
                                                           });

            return status;
        }

        binder_status_t writeToParcel(AParcel *parcel) const {
            // Get start position for size calculation
            int32_t startPos = AParcel_getDataPosition(parcel);

            // Write placeholder for size (will be updated at the end)
            if (AParcel_writeInt32(parcel, 0) != STATUS_OK) return STATUS_BAD_VALUE;

            // Write byte array
            if (AParcel_writeByteArray(
                    parcel,
                    reinterpret_cast<const int8_t *>(data.data()),
                    static_cast<int32_t>(data.size())) != STATUS_OK) {
                return STATUS_BAD_VALUE;
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

#endif // AIDL_ME_FLEEY_FUTON_DAEMON_ATTESTATION_CERTIFICATE_H
