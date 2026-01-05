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

#ifndef FUTON_CORE_AUTH_AUTH_MANAGER_H
#define FUTON_CORE_AUTH_AUTH_MANAGER_H

#include "session_manager.h"
#include "crypto_utils.h"
#include "rate_limiter.h"
#include "security_audit.h"
#include "caller_verifier.h"
#include "core/error.h"

#include <string>
#include <vector>
#include <memory>
#include <mutex>
#include <optional>

namespace futon::core::auth {

// Authentication error codes
    enum class AuthError {
        OK = 0,
        PUBKEY_NOT_FOUND,
        PUBKEY_INVALID,
        PUBKEY_TAMPERED,
        CHALLENGE_EXPIRED,
        CHALLENGE_NOT_FOUND,
        SIGNATURE_INVALID,
        SESSION_CONFLICT,
        SESSION_EXPIRED,
        SESSION_NOT_FOUND,
        RATE_LIMITED,
        CALLER_VERIFICATION_FAILED,
        INTERNAL_ERROR
    };

    const char *auth_error_to_string(AuthError err);

// Authentication result
    struct AuthResult {
        AuthError error;
        std::string message;
        std::string key_id;  // ID of the key used for authentication (for attestation tracking)

        bool is_ok() const { return error == AuthError::OK; }

        static AuthResult ok(const std::string &key_id = "") {
            AuthResult r;
            r.error = AuthError::OK;
            r.message = "";
            r.key_id = key_id;
            return r;
        }

        static AuthResult fail(AuthError err, const std::string &msg = "") {
            AuthResult r;
            r.error = err;
            r.message = msg.empty() ? auth_error_to_string(err) : msg;
            r.key_id = "";
            return r;
        }
    };

// Configuration for AuthManager
    struct AuthConfig {
        std::string pubkey_path = "/data/adb/futon/.auth_pubkey";
        std::string pubkey_key_path = "/data/adb/futon/.auth_pubkey_key";  // Key for pubkey encryption
        bool require_authentication = true;
        int64_t session_timeout_ms = SESSION_TIMEOUT_MS;
        int64_t challenge_timeout_ms = CHALLENGE_TIMEOUT_MS;

        // Rate limiting configuration
        RateLimitConfig rate_limit_config;

        // Audit logging configuration
        AuditConfig audit_config;

        // Caller verification configuration
        CallerVerifierConfig caller_verifier_config;

        // Enable security features
        bool enable_rate_limiting = true;
        bool enable_audit_logging = true;
        bool enable_caller_verification = true;
        bool enable_pubkey_pinning = true;
        bool enable_pubkey_encryption = true;  // Encrypt public key at rest
    };

// Main authentication manager
    class AuthManager {
    public:
        explicit AuthManager(const AuthConfig &config = AuthConfig());

        ~AuthManager() = default;

        // Disable copy
        AuthManager(const AuthManager &) = delete;

        AuthManager &operator=(const AuthManager &) = delete;

        // Initialize - load public key
        bool initialize();

        // Check if authentication is required
        bool is_authentication_required() const { return config_.require_authentication; }

        // Public key management
        bool reload_public_key();

        bool has_public_key() const;

        std::optional<std::vector<uint8_t>> get_public_key_fingerprint() const;

        // Authentication flow
        std::vector<uint8_t> get_challenge(uid_t client_uid);

        AuthResult authenticate(
                const std::vector<uint8_t> &signature,
                const std::string &instance_id,
                uid_t client_uid,
                pid_t client_pid = 0
        );

        // Session management
        SessionStatus check_session(const std::string &instance_id, uid_t client_uid);

        bool validate_session(const std::string &instance_id, uid_t client_uid);

        void update_session_activity(const std::string &instance_id);

        void invalidate_session(const std::string &instance_id);

        void invalidate_all_sessions();

        void cleanup_expired();

        // Get session manager for direct access if needed
        SessionManager &session_manager() { return session_manager_; }

        const SessionManager &session_manager() const { return session_manager_; }

        // Get rate limiter for direct access
        RateLimiter &rate_limiter() { return rate_limiter_; }

        const RateLimiter &rate_limiter() const { return rate_limiter_; }

        // Get caller verifier for direct access
        CallerVerifier &caller_verifier() { return caller_verifier_; }

        const CallerVerifier &caller_verifier() const { return caller_verifier_; }

        // Security audit access
        SecurityAudit &security_audit() { return security_audit_; }

        // Check if caller is allowed (rate limit + caller verification)
        AuthResult check_caller_allowed(uid_t uid, pid_t pid);

    private:
        AuthConfig config_;
        SessionManager session_manager_;
        RateLimiter rate_limiter_;
        SecurityAudit security_audit_;
        CallerVerifier caller_verifier_;

        mutable std::mutex pubkey_mutex_;
        std::vector<uint8_t> public_key_;
        SignatureAlgorithm key_algorithm_ = SignatureAlgorithm::ECDSA_P256;

        // Encryption key for public key storage
        std::vector<uint8_t> pubkey_encryption_key_;

        // Load public key from file (decrypting if necessary)
        bool load_public_key();

        // Save public key to file (encrypting if enabled)
        bool save_public_key(const std::vector<uint8_t> &pubkey);

        // Derive encryption key for public key storage
        std::vector<uint8_t> derive_pubkey_encryption_key();

        // Fallback: Derive encryption key from device properties
        std::vector<uint8_t> derive_pubkey_encryption_key_from_device();

        // Encrypt/decrypt public key
        std::vector<uint8_t> encrypt_pubkey(const std::vector<uint8_t> &pubkey);

        std::vector<uint8_t> decrypt_pubkey(const std::vector<uint8_t> &encrypted);

        // Verify signature against stored public key
        bool verify_signature(
                const std::vector<uint8_t> &challenge,
                const std::vector<uint8_t> &signature
        );
    };

} // namespace futon::core::auth

#endif // FUTON_CORE_AUTH_AUTH_MANAGER_H
