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

#include "auth_manager.h"
#include "key_whitelist.h"
#include "security.h"
#include "hardened_config.h"
#include "integrity_checker.h"
#include "device_fingerprint.h"

#include <fstream>
#include <sstream>
#include <algorithm>
#include <cctype>
#include <filesystem>
#include <sys/stat.h>
#include <cstring>
#include <sys/system_properties.h>

namespace futon::core::auth {

    const char *auth_error_to_string(AuthError err) {
        switch (err) {
            case AuthError::OK:
                return "OK";
            case AuthError::PUBKEY_NOT_FOUND:
                return "Public key not found";
            case AuthError::PUBKEY_INVALID:
                return "Public key invalid";
            case AuthError::PUBKEY_TAMPERED:
                return "Public key tampered";
            case AuthError::CHALLENGE_EXPIRED:
                return "Challenge expired";
            case AuthError::CHALLENGE_NOT_FOUND:
                return "Challenge not found";
            case AuthError::SIGNATURE_INVALID:
                return "Signature invalid";
            case AuthError::SESSION_CONFLICT:
                return "Session conflict";
            case AuthError::SESSION_EXPIRED:
                return "Session expired";
            case AuthError::SESSION_NOT_FOUND:
                return "Session not found";
            case AuthError::RATE_LIMITED:
                return "Rate limited";
            case AuthError::CALLER_VERIFICATION_FAILED:
                return "Caller verification failed";
            case AuthError::INTERNAL_ERROR:
                return "Internal error";
            default:
                return "Unknown error";
        }
    }

    AuthManager::AuthManager(const AuthConfig &config)
            : config_(config),
              rate_limiter_(config.rate_limit_config),
              security_audit_(config.audit_config),
              caller_verifier_(config.caller_verifier_config) {
    }

    bool AuthManager::initialize() {
        FUTON_LOGI("Initializing AuthManager with enhanced security");

        // Initialize session manager first (for encrypted challenge storage)
        if (!session_manager_.initialize()) {
            FUTON_LOGE("Failed to initialize session manager");
            return false;
        }

        // Initialize security audit
        if (config_.enable_audit_logging) {
            if (!security_audit_.initialize()) {
                FUTON_LOGW("Failed to initialize security audit logging");
            } else {
                security_audit_.log(SecurityEventType::DAEMON_STARTED,
                                    SecuritySeverity::INFO, 0, getpid(), "AuthManager initialized");
            }
        }

        // Initialize unified security system (includes HardenedConfig, IntegrityChecker, etc.)
        SecuritySystem::SecurityFeatures features;
        features.obfuscation = true;
        features.device_binding = true;
        features.integrity_checking = true;
        features.anti_debugging = true;
        features.watermarking = true;
        features.rate_limiting = config_.enable_rate_limiting;
        features.audit_logging = config_.enable_audit_logging;
        SecuritySystem::configure_features(features);

        if (!SecuritySystem::initialize()) {
            FUTON_LOGW("SecuritySystem initialization failed (continuing with reduced security)");
        } else {
            // Perform initial security check
            auto security_result = SecuritySystem::perform_full_check();
            if (!security_result.passed) {
                FUTON_LOGW("Initial security check failed (score: %d): %s",
                           security_result.overall_score, security_result.summary.c_str());
                security_audit_.log_security_violation(
                        SecurityEventType::SECURITY_CHECK_FAILED, 0, getpid(),
                        security_result.summary);
            } else {
                FUTON_LOGI("Security check passed (score: %d/100)", security_result.overall_score);
            }
        }

        // Initialize caller verifier
        if (config_.enable_caller_verification) {
            if (!caller_verifier_.initialize()) {
                FUTON_LOGW("Failed to initialize caller verifier");
            }
        }

        if (!config_.require_authentication) {
            FUTON_LOGW("Authentication disabled by configuration");
            return true;
        }

        if (!load_public_key()) {
            FUTON_LOGW("Failed to load public key from %s", config_.pubkey_path.c_str());
            return false;
        }

        // Pin public key on first load if enabled
        if (config_.enable_pubkey_pinning) {
            auto fingerprint = get_public_key_fingerprint();
            if (fingerprint.has_value()) {
                if (!caller_verifier_.pin_public_key(fingerprint.value())) {
                    FUTON_LOGW("Public key pinning failed - possible tampering!");
                    security_audit_.log_security_violation(
                            SecurityEventType::PUBKEY_TAMPERED, 0, getpid(),
                            "Public key fingerprint mismatch with pinned key");
                }
            }
        }

        security_audit_.log(SecurityEventType::PUBKEY_LOADED,
                            SecuritySeverity::INFO, 0, getpid(),
                            "algorithm=" + std::string(
                                    key_algorithm_ == SignatureAlgorithm::ED25519 ? "Ed25519"
                                                                                  : "ECDSA-P256"));

        FUTON_LOGI(
                "AuthManager initialized: algorithm=%s, rate_limit=%s, audit=%s, caller_verify=%s",
                key_algorithm_ == SignatureAlgorithm::ED25519 ? "Ed25519" : "ECDSA-P256",
                config_.enable_rate_limiting ? "on" : "off",
                config_.enable_audit_logging ? "on" : "off",
                config_.enable_caller_verification ? "on" : "off");

        return true;
    }

    bool AuthManager::reload_public_key() {
        std::lock_guard<std::mutex> lock(pubkey_mutex_);

        auto old_fingerprint = public_key_.empty() ? std::nullopt
                                                   : std::make_optional(
                        CryptoUtils::sha256(public_key_));

        if (!load_public_key()) {
            return false;
        }

        // Check if pinned pubkey file was deleted (app regenerated keypair)
        // If so, clear the in-memory pinned fingerprint to allow the new key
        if (config_.enable_pubkey_pinning) {
            // Check if pin file exists by trying to clear it
            // clear_pinned_pubkey() will check the file and clear memory if file is gone
            static const std::string pin_path = "/data/adb/futon/.pubkey_pin";
            if (!std::filesystem::exists(pin_path)) {
                if (caller_verifier_.has_pinned_pubkey()) {
                    FUTON_LOGI(
                            "Pinned pubkey file deleted, clearing in-memory pin to accept new key");
                    caller_verifier_.clear_pinned_pubkey();
                }
            } else if (caller_verifier_.has_pinned_pubkey()) {
                // Pinned file exists and we have a pin - verify the new key matches
                auto new_fingerprint = CryptoUtils::sha256(public_key_);
                if (!caller_verifier_.verify_pinned_pubkey(new_fingerprint)) {
                    FUTON_LOGE("Reloaded public key does not match pinned fingerprint!");
                    security_audit_.log_security_violation(
                            SecurityEventType::PUBKEY_TAMPERED, 0, getpid(),
                            "Reloaded public key fingerprint mismatch");
                    return false;
                }
            }
        }

        security_audit_.log(SecurityEventType::PUBKEY_RELOADED,
                            SecuritySeverity::INFO, 0, getpid(), "");
        return true;
    }

    bool AuthManager::has_public_key() const {
        std::lock_guard<std::mutex> lock(pubkey_mutex_);
        return !public_key_.empty();
    }

    std::optional<std::vector<uint8_t>> AuthManager::get_public_key_fingerprint() const {
        std::lock_guard<std::mutex> lock(pubkey_mutex_);

        if (public_key_.empty()) {
            return std::nullopt;
        }

        return CryptoUtils::sha256(public_key_);
    }

    bool AuthManager::load_public_key() {
        // Derive encryption key if not already done
        if (config_.enable_pubkey_encryption && pubkey_encryption_key_.empty()) {
            pubkey_encryption_key_ = derive_pubkey_encryption_key();
            if (pubkey_encryption_key_.empty()) {
                FUTON_LOGE("Failed to derive public key encryption key");
                return false;
            }
        }

        std::ifstream file(config_.pubkey_path, std::ios::binary);
        if (!file.is_open()) {
            FUTON_LOGE("Cannot open public key file: %s", config_.pubkey_path.c_str());
            return false;
        }

        std::string content((std::istreambuf_iterator<char>(file)),
                            std::istreambuf_iterator<char>());
        file.close();

        content.erase(0, content.find_first_not_of(" \t\n\r"));
        content.erase(content.find_last_not_of(" \t\n\r") + 1);

        if (content.empty()) {
            FUTON_LOGE("Public key file is empty");
            return false;
        }

        std::vector<uint8_t> key_data;

        // Check if file contains encrypted data (binary) or plain hex
        if (config_.enable_pubkey_encryption && content.size() > 16 &&
            content.substr(0, 8) == "FUTONENC") {
            // Encrypted format: "FUTONENC" + nonce(16) + encrypted_data
            auto encrypted_hex = content.substr(8);
            auto encrypted = CryptoUtils::from_hex(encrypted_hex);
            if (!encrypted.has_value() || encrypted->size() < 16) {
                FUTON_LOGE("Invalid encrypted public key format");
                return false;
            }

            key_data = decrypt_pubkey(encrypted.value());
            if (key_data.empty()) {
                FUTON_LOGE("Failed to decrypt public key");
                return false;
            }
            FUTON_LOGI("Loaded encrypted public key");
        } else {
            // Plain hex format (legacy or encryption disabled)
            auto decoded = CryptoUtils::from_hex(content);
            if (!decoded.has_value()) {
                FUTON_LOGE("Invalid hex encoding in public key file");
                return false;
            }
            key_data = std::move(decoded.value());

            // Migrate to encrypted storage if enabled
            if (config_.enable_pubkey_encryption) {
                FUTON_LOGI("Migrating public key to encrypted storage");
                if (!save_public_key(key_data)) {
                    FUTON_LOGW("Failed to migrate public key to encrypted storage");
                }
            }
        }

        public_key_ = std::move(key_data);
        key_algorithm_ = CryptoUtils::detect_algorithm(public_key_);

        FUTON_LOGI("Loaded public key: %zu bytes, algorithm=%s, encrypted=%s",
                   public_key_.size(),
                   key_algorithm_ == SignatureAlgorithm::ED25519 ? "Ed25519" : "ECDSA-P256",
                   config_.enable_pubkey_encryption ? "yes" : "no");

        return true;
    }

    std::vector<uint8_t> AuthManager::derive_pubkey_encryption_key() {
        // Load from stored key file (most reliable)
        if (!config_.pubkey_key_path.empty()) {
            std::ifstream key_file(config_.pubkey_key_path, std::ios::binary);
            if (key_file.is_open()) {
                std::vector<uint8_t> stored_key(32);
                key_file.read(reinterpret_cast<char*>(stored_key.data()), 32);
                if (key_file.gcount() == 32) {
                    FUTON_LOGI("Loaded pubkey encryption key from %s", config_.pubkey_key_path.c_str());
                    return stored_key;
                }
            }
        }

        // Generate random key and store it
        auto random_key = CryptoUtils::generate_random_bytes(32);
        if (!random_key.empty() && !config_.pubkey_key_path.empty()) {
            std::ofstream key_file(config_.pubkey_key_path, std::ios::binary);
            if (key_file.is_open()) {
                key_file.write(reinterpret_cast<const char*>(random_key.data()), random_key.size());
                key_file.close();
                chmod(config_.pubkey_key_path.c_str(), 0600);
                FUTON_LOGI("Generated and stored new pubkey encryption key");
                return random_key;
            }
        }

        // Fallback: Derive from device properties
        FUTON_LOGW("Falling back to device-derived encryption key");
        return derive_pubkey_encryption_key_from_device();
    }

    std::vector<uint8_t> AuthManager::derive_pubkey_encryption_key_from_device() {
        std::vector<uint8_t> entropy;

        // Use __system_property_get for reliable property access
        auto append_property = [&entropy](const char* name) {
            char value[PROP_VALUE_MAX] = {0};
            int len = __system_property_get(name, value);
            if (len > 0) {
                entropy.insert(entropy.end(), value, value + len);
            }
        };

        // Primary device identifiers via system properties
        append_property("ro.build.fingerprint");
        append_property("ro.product.model");
        append_property("ro.product.brand");
        append_property("ro.product.device");
        append_property("ro.serialno");
        append_property("ro.boot.serialno");
        append_property("ro.hardware");

        // Fallback: read from files if properties are insufficient
        if (entropy.size() < 16) {
            auto append_file = [&entropy](const char* path) {
                std::ifstream file(path);
                if (!file.is_open()) return;
                std::string line;
                if (std::getline(file, line) && !line.empty()) {
                    entropy.insert(entropy.end(), line.begin(), line.end());
                }
            };

            append_file("/sys/devices/soc0/serial_number");
            append_file("/proc/sys/kernel/random/boot_id");
        }

        // Fixed salt
        const char *salt = "futon_pubkey_encryption_v1";
        entropy.insert(entropy.end(), salt, salt + strlen(salt));

        FUTON_LOGD("Collected %zu bytes from device properties", entropy.size());

        // Lower threshold since we have salt (26 bytes) as minimum
        if (entropy.size() < 26) {
            FUTON_LOGW("Insufficient device entropy (got %zu bytes)", entropy.size());
            return {};
        }

        return CryptoUtils::sha256(entropy);
    }

    std::vector<uint8_t> AuthManager::encrypt_pubkey(const std::vector<uint8_t> &pubkey) {
        if (pubkey_encryption_key_.empty()) {
            return {};
        }

        // Generate random nonce
        auto nonce = CryptoUtils::generate_random_bytes(16);
        if (nonce.empty()) {
            return {};
        }

        // Derive key stream from encryption key + nonce
        std::vector<uint8_t> key_material = pubkey_encryption_key_;
        key_material.insert(key_material.end(), nonce.begin(), nonce.end());
        auto key_stream = CryptoUtils::sha256(key_material);

        // Extend key stream if needed
        while (key_stream.size() < pubkey.size()) {
            key_material = key_stream;
            key_material.push_back(static_cast<uint8_t>(key_stream.size()));
            auto extended = CryptoUtils::sha256(key_material);
            key_stream.insert(key_stream.end(), extended.begin(), extended.end());
        }

        // XOR encryption
        std::vector<uint8_t> encrypted(pubkey.size());
        for (size_t i = 0; i < pubkey.size(); ++i) {
            encrypted[i] = pubkey[i] ^ key_stream[i];
        }

        // Prepend nonce
        std::vector<uint8_t> result = nonce;
        result.insert(result.end(), encrypted.begin(), encrypted.end());
        return result;
    }

    std::vector<uint8_t> AuthManager::decrypt_pubkey(const std::vector<uint8_t> &encrypted) {
        if (pubkey_encryption_key_.empty() || encrypted.size() < 17) {
            return {};
        }

        // Extract nonce (first 16 bytes)
        std::vector<uint8_t> nonce(encrypted.begin(), encrypted.begin() + 16);
        std::vector<uint8_t> ciphertext(encrypted.begin() + 16, encrypted.end());

        // Derive key stream from encryption key + nonce
        std::vector<uint8_t> key_material = pubkey_encryption_key_;
        key_material.insert(key_material.end(), nonce.begin(), nonce.end());
        auto key_stream = CryptoUtils::sha256(key_material);

        // Extend key stream if needed
        while (key_stream.size() < ciphertext.size()) {
            key_material = key_stream;
            key_material.push_back(static_cast<uint8_t>(key_stream.size()));
            auto extended = CryptoUtils::sha256(key_material);
            key_stream.insert(key_stream.end(), extended.begin(), extended.end());
        }

        // XOR decryption
        std::vector<uint8_t> decrypted(ciphertext.size());
        for (size_t i = 0; i < ciphertext.size(); ++i) {
            decrypted[i] = ciphertext[i] ^ key_stream[i];
        }

        return decrypted;
    }

    bool AuthManager::save_public_key(const std::vector<uint8_t> &pubkey) {
        if (!config_.enable_pubkey_encryption) {
            // Save as plain hex
            std::ofstream file(config_.pubkey_path);
            if (!file.is_open()) {
                return false;
            }
            file << CryptoUtils::to_hex(pubkey);
            return true;
        }

        auto encrypted = encrypt_pubkey(pubkey);
        if (encrypted.empty()) {
            return false;
        }

        std::ofstream file(config_.pubkey_path);
        if (!file.is_open()) {
            return false;
        }

        // Write encrypted format: "FUTONENC" + hex(nonce + ciphertext)
        file << "FUTONENC" << CryptoUtils::to_hex(encrypted);
        file.close();

        // Set restrictive permissions
        chmod(config_.pubkey_path.c_str(), 0600);

        return true;
    }

    AuthResult AuthManager::check_caller_allowed(uid_t uid, pid_t pid) {
        // Check rate limiting first
        if (config_.enable_rate_limiting) {
            auto rate_result = rate_limiter_.check_allowed(uid);
            if (!rate_result.allowed) {
                security_audit_.log_rate_limited(uid, pid, rate_result.retry_after_ms);
                return AuthResult::fail(AuthError::RATE_LIMITED,
                                        "Rate limited. Retry after " +
                                        std::to_string(rate_result.retry_after_ms) + "ms");
            }
        }

        // Verify caller process
        if (config_.enable_caller_verification) {
            auto verify_result = caller_verifier_.verify_caller(uid, pid);
            if (!verify_result.verified) {
                security_audit_.log_security_violation(
                        SecurityEventType::CALLER_VERIFICATION_FAILED, uid, pid,
                        verify_result.failure_reason);
                return AuthResult::fail(AuthError::CALLER_VERIFICATION_FAILED,
                                        verify_result.failure_reason);
            }
        }

        return AuthResult::ok();
    }

    std::vector<uint8_t> AuthManager::get_challenge(uid_t client_uid) {
        if (!config_.require_authentication) {
            FUTON_LOGD("Authentication disabled, returning empty challenge");
            return {};
        }

        auto challenge = session_manager_.create_challenge(client_uid);

        if (config_.enable_audit_logging && !challenge.empty()) {
            security_audit_.log(SecurityEventType::AUTH_CHALLENGE_REQUESTED,
                                SecuritySeverity::DEBUG, client_uid, 0, "");
        }

        return challenge;
    }

    AuthResult AuthManager::authenticate(
            const std::vector<uint8_t> &signature,
            const std::string &instance_id,
            uid_t client_uid,
            pid_t client_pid
    ) {
        FUTON_LOGI("authenticate() called: instance=%s, uid=%d, pid=%d, sig_size=%zu",
                   instance_id.c_str(), client_uid, client_pid, signature.size());

        // If authentication is disabled, auto-approve
        if (!config_.require_authentication) {
            FUTON_LOGW("Authentication disabled, auto-approving");
            if (!session_manager_.create_session(instance_id, client_uid)) {
                return AuthResult::fail(AuthError::SESSION_CONFLICT);
            }
            return AuthResult::ok();
        }

        // Check rate limiting
        if (config_.enable_rate_limiting) {
            auto rate_result = rate_limiter_.check_allowed(client_uid);
            if (!rate_result.allowed) {
                security_audit_.log_rate_limited(client_uid, client_pid,
                                                 rate_result.retry_after_ms);
                return AuthResult::fail(AuthError::RATE_LIMITED,
                                        "Rate limited. Retry after " +
                                        std::to_string(rate_result.retry_after_ms) + "ms");
            }
        }

        // Verify device binding
        auto &fingerprint = SecuritySystem::fingerprint();
        if (fingerprint.is_bound()) {
            auto fp_result = fingerprint.verify_device();
            if (!fp_result.verified) {
                FUTON_LOGE("Device fingerprint mismatch: %s", fp_result.failure_reason.c_str());
                security_audit_.log_security_violation(
                        SecurityEventType::DEVICE_MISMATCH, client_uid, client_pid,
                        fp_result.failure_reason);
                return AuthResult::fail(AuthError::INTERNAL_ERROR, "Device verification failed");
            }
        }

        // Get pending challenge for this client
        auto challenge_opt = session_manager_.get_pending_challenge(client_uid);
        if (!challenge_opt.has_value()) {
            FUTON_LOGE("No pending challenge for uid %d", client_uid);
            security_audit_.log_auth_failure(client_uid, client_pid,
                                             SecurityEventType::AUTH_FAILURE_NO_CHALLENGE);
            if (config_.enable_rate_limiting) {
                rate_limiter_.record_failure(client_uid);
            }
            return AuthResult::fail(AuthError::CHALLENGE_NOT_FOUND);
        }

        std::vector<uint8_t> challenge = std::move(challenge_opt.value());

        // Try to verify signature using KeyWhitelist first (User-Provisioned PKI)
        auto &key_whitelist = KeyWhitelist::instance();
        std::string matched_key_id;

        if (key_whitelist.has_keys()) {
            auto key_id_opt = key_whitelist.verify_signature(challenge, signature);
            if (key_id_opt.has_value()) {
                matched_key_id = key_id_opt.value();
                FUTON_LOGI("Signature verified with whitelisted key: %s", matched_key_id.c_str());

                // Mark key as used
                key_whitelist.mark_key_used(matched_key_id);
            }
        }

        // If no whitelisted key matched, fall back to legacy public key verification
        if (matched_key_id.empty()) {
            std::vector<uint8_t> pubkey_copy;
            {
                std::lock_guard<std::mutex> lock(pubkey_mutex_);
                if (public_key_.empty()) {
                    FUTON_LOGE("No public key loaded and no whitelisted keys matched");
                    security_audit_.log_auth_failure(client_uid, client_pid,
                                                     SecurityEventType::AUTH_FAILURE_PUBKEY_MISSING);
                    if (config_.enable_rate_limiting) {
                        rate_limiter_.record_failure(client_uid);
                    }
                    return AuthResult::fail(AuthError::PUBKEY_NOT_FOUND);
                }
                pubkey_copy = public_key_;
            }

            // Verify public key hasn't been tampered with
            if (config_.enable_pubkey_pinning && caller_verifier_.has_pinned_pubkey()) {
                auto current_fingerprint = CryptoUtils::sha256(pubkey_copy);
                if (!caller_verifier_.verify_pinned_pubkey(current_fingerprint)) {
                    FUTON_LOGE("Public key fingerprint mismatch - possible tampering!");
                    security_audit_.log_security_violation(
                            SecurityEventType::PUBKEY_TAMPERED, client_uid, client_pid,
                            "Public key changed since initial pinning");
                    return AuthResult::fail(AuthError::PUBKEY_TAMPERED);
                }
            }

            // Verify signature with legacy public key
            if (!verify_signature(challenge, signature)) {
                FUTON_LOGE("Signature verification failed for uid %d", client_uid);
                session_manager_.consume_challenge(client_uid);
                security_audit_.log_auth_failure(client_uid, client_pid,
                                                 SecurityEventType::AUTH_FAILURE_INVALID_SIGNATURE,
                                                 "Signature verification failed");
                if (config_.enable_rate_limiting) {
                    rate_limiter_.record_failure(client_uid);
                }
                return AuthResult::fail(AuthError::SIGNATURE_INVALID);
            }

            FUTON_LOGI("Signature verified with legacy public key");
        }

        // Consume the challenge (one-time use)
        session_manager_.consume_challenge(client_uid);

        // Create session
        if (!session_manager_.create_session(instance_id, client_uid)) {
            FUTON_LOGE("Failed to create session for instance %s", instance_id.c_str());
            security_audit_.log_session_event(SecurityEventType::SESSION_CONFLICT,
                                              client_uid, instance_id);
            return AuthResult::fail(AuthError::SESSION_CONFLICT);
        }

        // Success - reset rate limiter and log
        if (config_.enable_rate_limiting) {
            rate_limiter_.record_success(client_uid);
        }

        security_audit_.log_auth_success(client_uid, client_pid, instance_id);
        security_audit_.log_session_event(SecurityEventType::SESSION_CREATED,
                                          client_uid, instance_id);

        FUTON_LOGI("Authentication successful for instance %s, uid %d, key_id=%s",
                   instance_id.c_str(), client_uid,
                   matched_key_id.empty() ? "(legacy)" : matched_key_id.c_str());
        return AuthResult::ok(matched_key_id);
    }

    bool AuthManager::verify_signature(
            const std::vector<uint8_t> &challenge,
            const std::vector<uint8_t> &signature
    ) {
        std::lock_guard<std::mutex> lock(pubkey_mutex_);

        if (public_key_.empty()) {
            return false;
        }

        return CryptoUtils::verify_signature(public_key_, challenge, signature);
    }

    SessionStatus AuthManager::check_session(const std::string &instance_id, uid_t client_uid) {
        return session_manager_.check_session(instance_id, client_uid);
    }

    bool AuthManager::validate_session(const std::string &instance_id, uid_t client_uid) {
        if (!config_.require_authentication) {
            return true;
        }
        return session_manager_.validate_session(instance_id, client_uid);
    }

    void AuthManager::update_session_activity(const std::string &instance_id) {
        session_manager_.update_activity(instance_id);
    }

    void AuthManager::invalidate_session(const std::string &instance_id) {
        session_manager_.invalidate_session(instance_id);
        security_audit_.log_session_event(SecurityEventType::SESSION_INVALIDATED, 0, instance_id);
    }

    void AuthManager::invalidate_all_sessions() {
        session_manager_.invalidate_all_sessions();
        security_audit_.log(SecurityEventType::SESSION_INVALIDATED,
                            SecuritySeverity::WARNING, 0, 0, "All sessions invalidated");
    }

    void AuthManager::cleanup_expired() {
        session_manager_.cleanup_expired();
        rate_limiter_.cleanup_expired();
    }

} // namespace futon::core::auth
