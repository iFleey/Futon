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

#ifndef AIDL_ME_FLEEY_FUTON_CRYPTO_HANDSHAKE_H
#define AIDL_ME_FLEEY_FUTON_CRYPTO_HANDSHAKE_H

#include <android/binder_parcel.h>
#include <cstdint>
#include <string>
#include <vector>
#include <optional>

namespace aidl::me::fleey::futon {

    struct CryptoHandshake {
        std::vector<uint8_t> dhPublicKey;
        std::string sessionId;
        int64_t keyGeneration = 0;
        int32_t capabilities = 0;
        int32_t errorCode = 0;
        std::optional<std::string> errorMessage;

        binder_status_t readFromParcel(const AParcel *parcel) {
            // Read size prefix (Java AIDL format)
            int32_t parcelableSize = 0;
            if (AParcel_readInt32(parcel, &parcelableSize) != STATUS_OK) return STATUS_BAD_VALUE;
            if (parcelableSize < 4) return STATUS_BAD_VALUE;

            // Read dhPublicKey (byte array)
            dhPublicKey.clear();
            binder_status_t status = AParcel_readByteArray(parcel, &dhPublicKey,
                                                           [](void *arrayData, int32_t length,
                                                              int8_t **outBuffer) -> bool {
                                                               if (length < 0) {
                                                                   *outBuffer = nullptr;
                                                                   return true;
                                                               }
                                                               auto *vec = static_cast<std::vector<uint8_t> *>(arrayData);
                                                               vec->resize(length);
                                                               *outBuffer = reinterpret_cast<int8_t *>(vec->data());
                                                               return true;
                                                           });
            if (status != STATUS_OK) return STATUS_BAD_VALUE;

            // Read sessionId
            char *str = nullptr;
            auto readStringLambda = [](void *, int32_t len, char **buf) -> bool {
                if (len < 0) {
                    *buf = nullptr;
                    return true;
                }
                *buf = new char[len + 1];
                return true;
            };

            status = AParcel_readString(parcel, &str, readStringLambda);
            if (status != STATUS_OK) return STATUS_BAD_VALUE;
            if (str) {
                sessionId = str;
                delete[] str;
                str = nullptr;
            }

            if (AParcel_readInt64(parcel, &keyGeneration) != STATUS_OK) return STATUS_BAD_VALUE;
            if (AParcel_readInt32(parcel, &capabilities) != STATUS_OK) return STATUS_BAD_VALUE;
            if (AParcel_readInt32(parcel, &errorCode) != STATUS_OK) return STATUS_BAD_VALUE;

            // Read errorMessage (nullable string)
            char *errStr = nullptr;
            status = AParcel_readString(parcel, &errStr, readStringLambda);
            if (status != STATUS_OK) return STATUS_BAD_VALUE;
            if (errStr) {
                errorMessage = errStr;
                delete[] errStr;
            } else {
                errorMessage = std::nullopt;
            }

            return STATUS_OK;
        }

        binder_status_t writeToParcel(AParcel *parcel) const {
            // Get start position for size calculation
            int32_t startPos = AParcel_getDataPosition(parcel);

            // Write placeholder for size (will be updated at the end)
            if (AParcel_writeInt32(parcel, 0) != STATUS_OK) return STATUS_BAD_VALUE;

            // Write dhPublicKey (byte array)
            if (AParcel_writeByteArray(parcel,
                                       reinterpret_cast<const int8_t *>(dhPublicKey.data()),
                                       static_cast<int32_t>(dhPublicKey.size())) != STATUS_OK) {
                return STATUS_BAD_VALUE;
            }

            // Write sessionId
            if (AParcel_writeString(parcel, sessionId.c_str(),
                                    static_cast<int32_t>(sessionId.size())) != STATUS_OK) {
                return STATUS_BAD_VALUE;
            }

            if (AParcel_writeInt64(parcel, keyGeneration) != STATUS_OK) return STATUS_BAD_VALUE;
            if (AParcel_writeInt32(parcel, capabilities) != STATUS_OK) return STATUS_BAD_VALUE;
            if (AParcel_writeInt32(parcel, errorCode) != STATUS_OK) return STATUS_BAD_VALUE;

            // Write errorMessage (nullable string)
            if (errorMessage.has_value()) {
                if (AParcel_writeString(parcel, errorMessage->c_str(),
                                        static_cast<int32_t>(errorMessage->size())) != STATUS_OK) {
                    return STATUS_BAD_VALUE;
                }
            } else {
                // Write null string
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

#endif // AIDL_ME_FLEEY_FUTON_CRYPTO_HANDSHAKE_H
