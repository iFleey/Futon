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

#include "vision/fallback/java_helper_receiver.h"
#include "core/error.h"

#include <android/binder_ibinder.h>
#include <android/binder_parcel.h>
#include <android/binder_status.h>

// Forward declare ServiceManager functions (NDK API level 31+)
extern "C" {
binder_status_t
AServiceManager_addService(AIBinder *binder, const char *instance) __attribute__((weak));
}

#include <cstring>
#include <stdexcept>

using namespace futon::core;

namespace futon::vision {

// Static instance for callback routing
    JavaHelperReceiver *JavaHelperReceiver::s_instance_ = nullptr;

// AIBinder class definition for our service
    static AIBinder_Class *g_java_helper_class = nullptr;

// Class creation callback
    static void *on_create(void *args) {
        return args;  // Return the JavaHelperReceiver instance
    }

// Class destruction callback
    static void on_destroy(void *userData) {
        // Instance is managed externally, don't delete
    }

    JavaHelperReceiver::JavaHelperReceiver() = default;

    JavaHelperReceiver::~JavaHelperReceiver() {
        shutdown();
    }

    bool JavaHelperReceiver::initialize(const char *service_name) {
        if (initialized_) {
            FUTON_LOGW("JavaHelperReceiver: already initialized");
            return true;
        }

        if (!service_name || strlen(service_name) == 0) {
            FUTON_LOGE("JavaHelperReceiver: invalid service name");
            return false;
        }

        service_name_ = service_name;
        FUTON_LOGI("JavaHelperReceiver: initializing service '%s'", service_name_.c_str());

        // Create AIBinder class if not already created
        if (!g_java_helper_class) {
            g_java_helper_class = AIBinder_Class_define(
                    INTERFACE_DESCRIPTOR,
                    on_create,
                    on_destroy,
                    on_transact);

            if (!g_java_helper_class) {
                FUTON_LOGE("JavaHelperReceiver: failed to define Binder class");
                return false;
            }
        }

        // Create binder instance
        binder_ = AIBinder_new(g_java_helper_class, this);
        if (!binder_) {
            FUTON_LOGE("JavaHelperReceiver: failed to create Binder");
            return false;
        }

        // Register with ServiceManager
        if (!AServiceManager_addService) {
            FUTON_LOGE(
                    "JavaHelperReceiver: AServiceManager_addService not available (requires API 31+)");
            AIBinder_decStrong(binder_);
            binder_ = nullptr;
            return false;
        }

        binder_status_t status = AServiceManager_addService(
                binder_, service_name_.c_str());

        if (status != STATUS_OK) {
            FUTON_LOGE("JavaHelperReceiver: failed to register service (status=%d)", status);
            AIBinder_decStrong(binder_);
            binder_ = nullptr;
            return false;
        }

        // Set static instance for callback routing
        s_instance_ = this;

        initialized_ = true;
        FUTON_LOGI("JavaHelperReceiver: initialized successfully");
        return true;
    }

    void JavaHelperReceiver::shutdown() {
        if (!initialized_) {
            return;
        }

        FUTON_LOGI("JavaHelperReceiver: shutting down");

        // Clear static instance
        if (s_instance_ == this) {
            s_instance_ = nullptr;
        }

        // Release binder
        if (binder_) {
            AIBinder_decStrong(binder_);
            binder_ = nullptr;
        }

        service_name_.clear();
        initialized_ = false;
    }

    void JavaHelperReceiver::set_token_callback(DisplayTokenCallback callback) {
        std::lock_guard<std::mutex> lock(callback_mutex_);
        token_callback_ = std::move(callback);
    }

    void JavaHelperReceiver::set_error_callback(ErrorCallback callback) {
        std::lock_guard<std::mutex> lock(callback_mutex_);
        error_callback_ = std::move(callback);
    }

    binder_status_t JavaHelperReceiver::on_transact(
            AIBinder *binder,
            transaction_code_t code,
            const AParcel *in,
            AParcel *out) {

        if (!s_instance_) {
            FUTON_LOGE("JavaHelperReceiver: no instance for transaction");
            return STATUS_FAILED_TRANSACTION;
        }

        // Verify interface descriptor
        const char *interface_desc = nullptr;
        binder_status_t status = AParcel_readString(in, &interface_desc,
                                                    [](void *stringData, int32_t length,
                                                       char **buffer) -> bool {
                                                        *buffer = new char[length + 1];
                                                        return true;
                                                    });

        if (status != STATUS_OK) {
            FUTON_LOGW("JavaHelperReceiver: failed to read interface descriptor");
            // Continue anyway, some clients may not send it
        } else if (interface_desc) {
            if (strcmp(interface_desc, INTERFACE_DESCRIPTOR) != 0) {
                FUTON_LOGW("JavaHelperReceiver: interface mismatch: %s", interface_desc);
            }
            delete[] interface_desc;
        }

        switch (code) {
            case TRANSACTION_SEND_DISPLAY_TOKEN:
                FUTON_LOGD("JavaHelperReceiver: received SEND_DISPLAY_TOKEN");
                s_instance_->handle_display_token(in);
                return STATUS_OK;

            case TRANSACTION_SEND_ERROR:
                FUTON_LOGD("JavaHelperReceiver: received SEND_ERROR");
                s_instance_->handle_error(in);
                return STATUS_OK;

            default:
                FUTON_LOGW("JavaHelperReceiver: unknown transaction code %d", code);
                return STATUS_UNKNOWN_TRANSACTION;
        }
    }

    void JavaHelperReceiver::handle_display_token(const AParcel *in) {
        // Read IBinder (display token)
        AIBinder *token_binder = nullptr;
        binder_status_t status = AParcel_readStrongBinder(in, &token_binder);
        if (status != STATUS_OK) {
            FUTON_LOGE("JavaHelperReceiver: failed to read display token");
            return;
        }

        // Read dimensions
        int32_t width = 0, height = 0;
        AParcel_readInt32(in, &width);
        AParcel_readInt32(in, &height);

        FUTON_LOGI("JavaHelperReceiver: display token received, %dx%d", width, height);

        // Invoke callback
        std::lock_guard<std::mutex> lock(callback_mutex_);
        if (token_callback_) {
            // Pass the binder as void* (the actual IBinder pointer)
            token_callback_(static_cast<void *>(token_binder), width, height);
        } else {
            FUTON_LOGW("JavaHelperReceiver: no token callback registered");
            if (token_binder) {
                AIBinder_decStrong(token_binder);
            }
        }
    }

    void JavaHelperReceiver::handle_error(const AParcel *in) {
        // Read error message
        const char *error_msg = nullptr;
        binder_status_t status = AParcel_readString(in, &error_msg,
                                                    [](void *stringData, int32_t length,
                                                       char **buffer) -> bool {
                                                        *buffer = new char[length + 1];
                                                        return true;
                                                    });

        if (status != STATUS_OK || !error_msg) {
            FUTON_LOGE("JavaHelperReceiver: failed to read error message");
            return;
        }

        FUTON_LOGE("JavaHelperReceiver: error from Java helper: %s", error_msg);

        // Invoke callback
        std::lock_guard<std::mutex> lock(callback_mutex_);
        if (error_callback_) {
            error_callback_(error_msg);
        }

        delete[] error_msg;
    }

// JavaHelperStdoutReader implementation

    bool JavaHelperStdoutReader::is_protocol_line(const std::string &line) {
        return line.find(TOKEN_PREFIX) == 0 || line.find(ERROR_PREFIX) == 0;
    }

    Result <JavaHelperStdoutReader::StdoutResult>
    JavaHelperStdoutReader::parse_line(const std::string &line) {
        StdoutResult result;

        if (line.find(TOKEN_PREFIX) == 0) {
            // Parse: FUTON_TOKEN:<width>:<height>:<token_descriptor>
            std::string payload = line.substr(strlen(TOKEN_PREFIX));

            // Find first colon (after width)
            size_t pos1 = payload.find(':');
            if (pos1 == std::string::npos) {
                FUTON_LOGE("JavaHelperStdoutReader: malformed token line (no width delimiter)");
                return Result<StdoutResult>::error(FutonError::InvalidArgument);
            }

            // Find second colon (after height)
            size_t pos2 = payload.find(':', pos1 + 1);
            if (pos2 == std::string::npos) {
                FUTON_LOGE("JavaHelperStdoutReader: malformed token line (no height delimiter)");
                return Result<StdoutResult>::error(FutonError::InvalidArgument);
            }

            try {
                result.width = std::stoi(payload.substr(0, pos1));
                result.height = std::stoi(payload.substr(pos1 + 1, pos2 - pos1 - 1));
                result.token_descriptor = payload.substr(pos2 + 1);
                result.success = true;

                FUTON_LOGI("JavaHelperStdoutReader: parsed token %dx%d, descriptor=%s",
                           result.width, result.height, result.token_descriptor.c_str());
            } catch (const std::exception &e) {
                FUTON_LOGE("JavaHelperStdoutReader: failed to parse dimensions: %s", e.what());
                return Result<StdoutResult>::error(FutonError::InvalidArgument);
            }

        } else if (line.find(ERROR_PREFIX) == 0) {
            // Parse: FUTON_ERROR:<message>
            result.success = false;
            result.error_message = line.substr(strlen(ERROR_PREFIX));

            FUTON_LOGE("JavaHelperStdoutReader: error from Java: %s",
                       result.error_message.c_str());
        } else {
            FUTON_LOGW("JavaHelperStdoutReader: unrecognized line: %s", line.c_str());
            return Result<StdoutResult>::error(FutonError::InvalidArgument);
        }

        return Result<StdoutResult>::ok(result);
    }

} // namespace futon::vision
