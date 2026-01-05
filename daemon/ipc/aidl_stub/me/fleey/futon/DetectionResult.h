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

#ifndef AIDL_ME_FLEEY_FUTON_DETECTION_RESULT_H
#define AIDL_ME_FLEEY_FUTON_DETECTION_RESULT_H

#include <android/binder_parcel.h>
#include <string>
#include <cstdint>

namespace aidl::me::fleey::futon {

    // String allocator for DetectionResult - stores directly into std::string
    inline bool DetectionResultStringAllocator(void *stringData, int32_t length, char **buffer) {
        if (!stringData) return false;
        std::string *str = static_cast<std::string *>(stringData);

        if (length < 0) {
            // Null string
            str->clear();
            return true;
        }

        if (length == 0) {
            str->clear();
            if (buffer) *buffer = nullptr;
            return true;
        }

        str->resize(static_cast<size_t>(length));
        if (buffer) *buffer = &(*str)[0];
        return true;
    }

    class DetectionResult {
    public:
        float x1 = 0.0f;
        float y1 = 0.0f;
        float x2 = 0.0f;
        float y2 = 0.0f;
        float confidence = 0.0f;
        int32_t classId = 0;
        std::string className;
        std::string text;
        float textConfidence = 0.0f;

        binder_status_t readFromParcel(const AParcel *parcel) {
            // Read size prefix (Java AIDL format)
            int32_t parcelableSize = 0;
            if (AParcel_readInt32(parcel, &parcelableSize) != STATUS_OK) return STATUS_BAD_VALUE;
            if (parcelableSize < 4) return STATUS_BAD_VALUE;

            AParcel_readFloat(parcel, &x1);
            AParcel_readFloat(parcel, &y1);
            AParcel_readFloat(parcel, &x2);
            AParcel_readFloat(parcel, &y2);
            AParcel_readFloat(parcel, &confidence);
            AParcel_readInt32(parcel, &classId);

            // Read className
            className.clear();
            binder_status_t status = AParcel_readString(parcel, &className, DetectionResultStringAllocator);
            if (status != STATUS_OK) return status;
            // Remove trailing null if present
            if (!className.empty() && className.back() == '\0') {
                className.pop_back();
            }

            // Read text
            text.clear();
            status = AParcel_readString(parcel, &text, DetectionResultStringAllocator);
            if (status != STATUS_OK) return status;
            // Remove trailing null if present
            if (!text.empty() && text.back() == '\0') {
                text.pop_back();
            }

            AParcel_readFloat(parcel, &textConfidence);

            return STATUS_OK;
        }

        binder_status_t writeToParcel(AParcel *parcel) const {
            // Get start position for size calculation
            int32_t startPos = AParcel_getDataPosition(parcel);

            // Write placeholder for size (will be updated at the end)
            AParcel_writeInt32(parcel, 0);

            AParcel_writeFloat(parcel, x1);
            AParcel_writeFloat(parcel, y1);
            AParcel_writeFloat(parcel, x2);
            AParcel_writeFloat(parcel, y2);
            AParcel_writeFloat(parcel, confidence);
            AParcel_writeInt32(parcel, classId);
            AParcel_writeString(parcel, className.c_str(),
                                static_cast<int32_t>(className.length()));
            AParcel_writeString(parcel, text.c_str(), static_cast<int32_t>(text.length()));
            AParcel_writeFloat(parcel, textConfidence);

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

#endif  // AIDL_ME_FLEEY_FUTON_DETECTION_RESULT_H
