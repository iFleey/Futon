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

#ifndef FUTON_CORE_AUTH_SECURITY_H
#define FUTON_CORE_AUTH_SECURITY_H

#include <memory>
#include <functional>
#include <string>

// Core authentication
#include "auth.h"
#include "auth_manager.h"
#include "session_manager.h"
#include "caller_verifier.h"
#include "crypto_utils.h"
#include "rate_limiter.h"
#include "security_audit.h"

// Security modules
#include "device_fingerprint.h"
#include "integrity_checker.h"
#include "hardened_config.h"

#include "core/error.h"

namespace futon::core::auth {

// Unified security initialization
    class SecuritySystem {
    public:
        // Initialize all security subsystems
        static bool initialize();

        // Shutdown all security subsystems
        static void shutdown();

        // Perform comprehensive security check
        struct SecurityCheckResult {
            bool passed;
            bool config_valid;
            bool device_bound;
            bool integrity_ok;
            bool environment_safe;
            bool watermark_valid;
            int overall_score;  // 0-100
            std::string summary;
        };

        static SecurityCheckResult perform_full_check();

        // Quick security check (for frequent use)
        static bool quick_check();

        // Get security status summary
        static std::string get_status_summary();

        // Enable/disable security features
        struct SecurityFeatures {
            bool obfuscation = true;
            bool device_binding = true;
            bool integrity_checking = true;
            bool anti_debugging = true;
            bool watermarking = true;
            bool rate_limiting = true;
            bool audit_logging = true;
        };

        static void configure_features(const SecurityFeatures &features);

        static SecurityFeatures get_features();

        // Security event callback
        using SecurityEventCallback = std::function<void(const std::string &event, int severity)>;

        static void set_event_callback(SecurityEventCallback callback);

        // Get singleton instances
        static HardenedConfig &config();

        static IntegrityChecker &integrity();

        static DeviceFingerprint &fingerprint();

    private:
        static bool initialized_;
        static SecurityFeatures features_;
        static SecurityEventCallback event_callback_;
        static std::unique_ptr<IntegrityChecker> integrity_checker_;
        static std::unique_ptr<DeviceFingerprint> device_fingerprint_;
    };

// Convenience macros for security checks in code (telemetry-only: log but never block)

// Security gate - telemetry only, logs but never blocks execution
#define FUTON_SECURITY_GATE() \
    do { \
        if (!futon::core::auth::SecuritySystem::quick_check()) { \
            FUTON_LOGW("Telemetry: Security check issue at %s:%d (non-blocking)", __FILE__, __LINE__); \
        } \
    } while(0)

// Security gate with return value - telemetry only, logs but never blocks
#define FUTON_SECURITY_GATE_RET(ret) \
    do { \
        if (!futon::core::auth::SecuritySystem::quick_check()) { \
            FUTON_LOGW("Telemetry: Security check issue at %s:%d (non-blocking)", __FILE__, __LINE__); \
        } \
    } while(0)

// Anti-debug check - telemetry only, logs but never traps
#define FUTON_ANTI_DEBUG() \
    do { \
        if (futon::core::auth::SecuritySystem::integrity().is_debugger_attached()) { \
            FUTON_LOGW("Telemetry: Debugger detected (non-blocking)"); \
        } \
    } while(0)

// Integrity verification - telemetry only
#define FUTON_VERIFY_INTEGRITY() \
    (futon::core::auth::SecuritySystem::integrity().check_integrity().passed || \
     (FUTON_LOGW("Telemetry: Integrity check failed (non-blocking)"), true))

} // namespace futon::core::auth

#endif // FUTON_CORE_AUTH_SECURITY_H
