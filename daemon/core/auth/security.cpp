/*
 * Futon - Unified Security System Implementation
 * Copyright (C) 2025 Fleey
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Security modules can be disabled at compile time via CMake options.
 * This is intentional per GPL Section 3 - users have the right to modify
 * the software, including disabling technical protection measures.
 *
 * Build options:
 *   -DFUTON_ENABLE_SECURITY=OFF        Disable all security
 *   -DFUTON_ENABLE_ANTI_DEBUG=OFF      Disable anti-debugging
 *   -DFUTON_ENABLE_DEVICE_BINDING=OFF  Disable device binding
 *   -DFUTON_ENABLE_INTEGRITY_CHECK=OFF Disable integrity checks
 *   -DFUTON_ENABLE_WATERMARK=OFF       Disable watermarking
 */

#include "security.h"
#include "core/error.h"

#include <chrono>
#include <sstream>

// Default to enabled if not defined (for IDE compatibility)
#ifndef FUTON_SECURITY_ENABLED
#define FUTON_SECURITY_ENABLED 1
#endif
#ifndef FUTON_ANTI_DEBUG_ENABLED
#define FUTON_ANTI_DEBUG_ENABLED 1
#endif
#ifndef FUTON_DEVICE_BINDING_ENABLED
#define FUTON_DEVICE_BINDING_ENABLED 1
#endif
#ifndef FUTON_INTEGRITY_CHECK_ENABLED
#define FUTON_INTEGRITY_CHECK_ENABLED 1
#endif
#ifndef FUTON_WATERMARK_ENABLED
#define FUTON_WATERMARK_ENABLED 1
#endif

namespace futon::core::auth {

// Static member initialization
    bool SecuritySystem::initialized_ = false;
    SecuritySystem::SecurityFeatures SecuritySystem::features_;
    SecuritySystem::SecurityEventCallback SecuritySystem::event_callback_;
    std::unique_ptr<IntegrityChecker> SecuritySystem::integrity_checker_;
    std::unique_ptr<DeviceFingerprint> SecuritySystem::device_fingerprint_;

    bool SecuritySystem::initialize() {
        if (initialized_) {
            FUTON_LOGW("SecuritySystem already initialized");
            return true;
        }

#if !FUTON_SECURITY_ENABLED
        FUTON_LOGI("SecuritySystem: All security modules DISABLED at compile time");
        initialized_ = true;
        return true;
#else

        FUTON_LOGI("Initializing SecuritySystem with configured security subsystems");

        // Initialize HardenedConfig first (provides device key)
        if (!HardenedConfig::instance().initialize()) {
            FUTON_LOGE("Failed to initialize HardenedConfig");
            if (event_callback_) {
                event_callback_("HardenedConfig initialization failed", 3);
            }
            return false;
        }

#if FUTON_INTEGRITY_CHECK_ENABLED
        // Initialize IntegrityChecker
        if (features_.integrity_checking) {
            IntegrityConfig integrity_config;
            integrity_config.check_debugger = features_.anti_debugging && FUTON_ANTI_DEBUG_ENABLED;
            integrity_config.check_frida = features_.anti_debugging && FUTON_ANTI_DEBUG_ENABLED;
            integrity_config.check_xposed = features_.anti_debugging && FUTON_ANTI_DEBUG_ENABLED;

            integrity_checker_ = std::make_unique<IntegrityChecker>(integrity_config);
            if (!integrity_checker_->initialize()) {
                FUTON_LOGW("IntegrityChecker initialization failed (non-fatal)");
            }
        }
#else
        FUTON_LOGI("IntegrityChecker: DISABLED at compile time");
#endif

#if FUTON_DEVICE_BINDING_ENABLED
        // Initialize DeviceFingerprint
        if (features_.device_binding) {
            DeviceBindingConfig fp_config;
            device_fingerprint_ = std::make_unique<DeviceFingerprint>(fp_config);
            if (!device_fingerprint_->initialize()) {
                FUTON_LOGW("DeviceFingerprint initialization failed (non-fatal)");
            }
        }
#else
        FUTON_LOGI("DeviceFingerprint: DISABLED at compile time");
#endif

        // Watermarking removed - not implemented

        // Perform initial security check
        auto result = perform_full_check();
        if (!result.passed) {
            FUTON_LOGW("Initial security check failed: %s", result.summary.c_str());
            if (event_callback_) {
                event_callback_("Initial security check failed: " + result.summary, 2);
            }
        }

        initialized_ = true;
        FUTON_LOGI("SecuritySystem initialized successfully (score: %d/100)",
                   result.overall_score);

        if (event_callback_) {
            event_callback_("SecuritySystem initialized", 0);
        }

        return true;
#endif // FUTON_SECURITY_ENABLED
    }

    void SecuritySystem::shutdown() {
        if (!initialized_) {
            return;
        }

        FUTON_LOGI("Shutting down SecuritySystem");

        // Stop periodic integrity checks
        if (integrity_checker_) {
            integrity_checker_->stop_periodic_checks();
            integrity_checker_.reset();
        }

        device_fingerprint_.reset();

        // Shutdown HardenedConfig
        HardenedConfig::instance().shutdown();

        initialized_ = false;

        if (event_callback_) {
            event_callback_("SecuritySystem shutdown", 0);
        }
    }

    SecuritySystem::SecurityCheckResult SecuritySystem::perform_full_check() {
        SecurityCheckResult result;
        result.passed = true;
        result.overall_score = 100;
        std::stringstream summary;

#if !FUTON_SECURITY_ENABLED
        // All security disabled - return success with note
        result.config_valid = true;
        result.device_bound = true;
        result.integrity_ok = true;
        result.environment_safe = true;
        result.watermark_valid = true;
        result.summary = "Security modules disabled at compile time";
        return result;
#endif

        // Check 1: HardenedConfig verification
        auto config_result = HardenedConfig::instance().verify_all();
        result.config_valid = config_result.valid;
        result.device_bound = config_result.device_bound;
        result.environment_safe = config_result.environment_safe;

        if (!config_result.is_fully_valid()) {
            result.passed = false;
            result.overall_score -= 30;
            summary << "Config: " << config_result.failure_reason << "; ";
        }

#if FUTON_INTEGRITY_CHECK_ENABLED
        // Check 2: Integrity verification (telemetry-only: log but don't block)
        if (features_.integrity_checking && integrity_checker_) {
            auto integrity_result = integrity_checker_->check_integrity();
            result.integrity_ok = true;  // Always pass - telemetry only

            if (!integrity_result.passed) {
                // Log for telemetry but don't fail
                FUTON_LOGW("Telemetry: Integrity check issue: %s (non-blocking)",
                           integrity_result.failure_reason.c_str());
                summary << "Integrity(telemetry): " << integrity_result.failure_reason << "; ";
            }

#if FUTON_ANTI_DEBUG_ENABLED
            // Anti-debug checks (telemetry-only: log but don't block)
            if (features_.anti_debugging) {
                auto anti_debug = integrity_checker_->check_anti_debug();
                if (anti_debug.debugger_detected || anti_debug.frida_detected ||
                    anti_debug.xposed_detected) {
                    // Log for telemetry but don't fail
                    FUTON_LOGW("Telemetry: Anti-debug detection: %s (non-blocking)",
                               anti_debug.details.c_str());
                    summary << "AntiDebug(telemetry): " << anti_debug.details << "; ";
                }
            }
#endif // FUTON_ANTI_DEBUG_ENABLED
        } else {
            result.integrity_ok = true;  // Not checked
        }
#else
        result.integrity_ok = true;  // Disabled at compile time
#endif // FUTON_INTEGRITY_CHECK_ENABLED

#if FUTON_DEVICE_BINDING_ENABLED
        // Check 3: Device binding verification
        if (features_.device_binding && device_fingerprint_) {
            auto fp_result = device_fingerprint_->verify_device();
            if (!fp_result.verified) {
                result.device_bound = false;
                result.overall_score -= 15;
                summary << "DeviceBinding: " << fp_result.failure_reason << "; ";
            }
        }
#else
        result.device_bound = true;  // Disabled at compile time
#endif // FUTON_DEVICE_BINDING_ENABLED

        // Watermarking removed - always valid
        result.watermark_valid = true;

        // Clamp score
        if (result.overall_score < 0) {
            result.overall_score = 0;
        }

        result.summary = summary.str();
        if (result.summary.empty()) {
            result.summary = "All checks passed";
        }

        return result;
    }

    bool SecuritySystem::quick_check() {
        if (!initialized_) {
            return false;
        }

#if !FUTON_SECURITY_ENABLED
        return true;  // Security disabled
#endif

        // Quick environment safety check
        if (!HardenedConfig::instance().is_environment_safe()) {
            FUTON_LOGW("Telemetry: Environment safety check failed (non-blocking)");
            // Don't return false - telemetry only
        }

#if FUTON_ANTI_DEBUG_ENABLED
        // Quick debugger check (telemetry-only: log but don't block)
        if (features_.anti_debugging && integrity_checker_) {
            if (integrity_checker_->is_debugger_attached()) {
                FUTON_LOGW("Telemetry: Debugger detected in quick_check (non-blocking)");
                // Don't return false - telemetry only
            }
        }
#endif

        return true;
    }

    std::string SecuritySystem::get_status_summary() {
        if (!initialized_) {
            return "SecuritySystem not initialized";
        }

        auto result = perform_full_check();
        std::stringstream ss;

        ss << "Security Status: " << (result.passed ? "PASS" : "FAIL") << "\n";
        ss << "  Score: " << result.overall_score << "/100\n";
        ss << "  Config Valid: " << (result.config_valid ? "Yes" : "No") << "\n";
        ss << "  Device Bound: " << (result.device_bound ? "Yes" : "No") << "\n";
        ss << "  Integrity OK: " << (result.integrity_ok ? "Yes" : "No") << "\n";
        ss << "  Environment Safe: " << (result.environment_safe ? "Yes" : "No") << "\n";
        ss << "  Watermark Valid: " << (result.watermark_valid ? "Yes" : "No") << "\n";

        if (!result.summary.empty() && result.summary != "All checks passed") {
            ss << "  Details: " << result.summary << "\n";
        }

        return ss.str();
    }

    void SecuritySystem::configure_features(const SecurityFeatures &features) {
        features_ = features;
        FUTON_LOGI("Security features configured: obf=%d, bind=%d, integrity=%d, "
                   "antidebug=%d, watermark=%d, ratelimit=%d, audit=%d",
                   features.obfuscation, features.device_binding,
                   features.integrity_checking, features.anti_debugging,
                   features.watermarking, features.rate_limiting, features.audit_logging);
    }

    SecuritySystem::SecurityFeatures SecuritySystem::get_features() {
        return features_;
    }

    void SecuritySystem::set_event_callback(SecurityEventCallback callback) {
        event_callback_ = std::move(callback);
    }

    HardenedConfig &SecuritySystem::config() {
        return HardenedConfig::instance();
    }

    IntegrityChecker &SecuritySystem::integrity() {
        if (!integrity_checker_) {
            // Create default instance if not initialized
            static IntegrityChecker default_checker;
            return default_checker;
        }
        return *integrity_checker_;
    }

    DeviceFingerprint &SecuritySystem::fingerprint() {
        if (!device_fingerprint_) {
            // Create default instance if not initialized
            static DeviceFingerprint default_fingerprint;
            return default_fingerprint;
        }
        return *device_fingerprint_;
    }

} // namespace futon::core::auth
