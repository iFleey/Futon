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

#ifndef FUTON_VISION_FALLBACK_JAVA_HELPER_RECEIVER_H
#define FUTON_VISION_FALLBACK_JAVA_HELPER_RECEIVER_H

#include "core/error.h"
#include <android/binder_ibinder.h>
#include <cstdint>
#include <string>
#include <functional>
#include <mutex>
#include <atomic>

namespace futon::vision {

/**
 * Callback types for Java helper events.
 */
    using DisplayTokenCallback = std::function<void(void *token, int32_t width, int32_t height)>;
    using ErrorCallback = std::function<void(const char *error)>;

/**
 * JavaHelperReceiver - Binder service for receiving display tokens from Java helper.
 *
 * This class implements a Binder service that the Java helper process can
 * connect to and send the display token back to the native daemon.
 *
 * Transaction codes:
 * - 1: SEND_DISPLAY_TOKEN (token: IBinder, width: int, height: int)
 * - 2: SEND_ERROR (message: String)
 */
    class JavaHelperReceiver {
    public:
        // Transaction codes matching Java side
        static constexpr int32_t TRANSACTION_SEND_DISPLAY_TOKEN = 1;
        static constexpr int32_t TRANSACTION_SEND_ERROR = 2;

        // Interface descriptor
        static constexpr const char *INTERFACE_DESCRIPTOR = "me.fleey.futon.IFutonJavaHelper";

        JavaHelperReceiver();

        ~JavaHelperReceiver();

        // Disable copy
        JavaHelperReceiver(const JavaHelperReceiver &) = delete;

        JavaHelperReceiver &operator=(const JavaHelperReceiver &) = delete;

        /**
         * Initialize and register the Binder service.
         *
         * @param service_name Name to register with ServiceManager
         * @return true on success
         */
        bool initialize(const char *service_name);

        /**
         * Shutdown and unregister the Binder service.
         */
        void shutdown();

        /**
         * Check if receiver is initialized.
         */
        bool is_initialized() const { return initialized_; }

        /**
         * Set callback for display token received.
         */
        void set_token_callback(DisplayTokenCallback callback);

        /**
         * Set callback for error received.
         */
        void set_error_callback(ErrorCallback callback);

        /**
         * Get the service name.
         */
        const std::string &get_service_name() const { return service_name_; }

    private:
        bool initialized_ = false;
        std::string service_name_;
        AIBinder *binder_ = nullptr;

        // Callbacks
        std::mutex callback_mutex_;
        DisplayTokenCallback token_callback_;
        ErrorCallback error_callback_;

        // Binder transaction handler
        static binder_status_t on_transact(
                AIBinder *binder,
                transaction_code_t code,
                const AParcel *in,
                AParcel *out);

        // Instance pointer for static callback
        static JavaHelperReceiver *s_instance_;

        void handle_display_token(const AParcel *in);

        void handle_error(const AParcel *in);
    };

/**
 * JavaHelperStdoutReader - Reads display token from Java helper's stdout.
 *
 * When Binder IPC fails, the Java helper writes the token info to stdout.
 * This class parses that output as a fallback mechanism.
 *
 * Protocol:
 *   Success: FUTON_TOKEN:<width>:<height>:<token_descriptor>
 *   Error:   FUTON_ERROR:<message>
 *
 * Advantages over file-based fallback:
 *   - No file permission issues (SELinux, chmod)
 *   - No read/write race conditions
 *   - No cleanup of residual files needed
 *   - Atomic, memory-level communication via pipe
 */
    class JavaHelperStdoutReader {
    public:
        static constexpr const char *TOKEN_PREFIX = "FUTON_TOKEN:";
        static constexpr const char *ERROR_PREFIX = "FUTON_ERROR:";

        struct StdoutResult {
            bool success = false;
            int32_t width = 0;
            int32_t height = 0;
            std::string token_descriptor;
            std::string error_message;
        };

        /**
         * Parse a line from Java helper's stdout.
         *
         * @param line The line to parse
         * @return Result containing token info or error
         */
        static core::Result <StdoutResult> parse_line(const std::string &line);

        /**
         * Check if a line contains our protocol marker.
         */
        static bool is_protocol_line(const std::string &line);
    };

} // namespace futon::vision

#endif // FUTON_VISION_FALLBACK_JAVA_HELPER_RECEIVER_H
