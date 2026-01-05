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

#include "error.h"

namespace futon::core {

    const char *error_to_string(FutonError err) {
        switch (err) {
            case FutonError::Ok:
                return "Ok";
            case FutonError::PermissionDenied:
                return "PermissionDenied";
            case FutonError::DeviceNotFound:
                return "DeviceNotFound";
            case FutonError::ResourceExhausted:
                return "ResourceExhausted";
            case FutonError::InvalidArgument:
                return "InvalidArgument";
            case FutonError::NotInitialized:
                return "NotInitialized";
            case FutonError::Timeout:
                return "Timeout";
            case FutonError::FenceTimeout:
                return "FenceTimeout";
            case FutonError::DelegateReset:
                return "DelegateReset";
            case FutonError::PrivateApiUnavailable:
                return "PrivateApiUnavailable";
            case FutonError::NotSupported:
                return "NotSupported";
            case FutonError::InternalError:
                return "InternalError";
            default:
                return "Unknown";
        }
    }

} // namespace futon::core
