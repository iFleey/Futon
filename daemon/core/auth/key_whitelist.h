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

#ifndef FUTON_CORE_AUTH_KEY_WHITELIST_H
#define FUTON_CORE_AUTH_KEY_WHITELIST_H

#include <string>
#include <vector>
#include <unordered_map>
#include <mutex>
#include <optional>
#include <cstdint>

namespace futon::core::auth {

// Public key entry with metadata
    struct PublicKeyEntry {
        std::string key_id;                    // Unique identifier (SHA-256 of pubkey)
        std::vector<uint8_t> public_key;       // Raw public key bytes (DER or raw)
        std::string algorithm;                 // "EC_P256", "ED25519"
        uint64_t created_at;                   // Unix timestamp
        uint64_t last_used_at;                 // Unix timestamp

        // Trust status
        enum class TrustStatus {
            PENDING_ATTESTATION,  // Key added, waiting for attestation verification
            TRUSTED,              // Attestation verified, key is trusted
            REJECTED,             // Attestation failed, key is rejected
            LEGACY                // Legacy key without attestation (less secure)
        };
        TrustStatus trust_status = TrustStatus::PENDING_ATTESTATION;

        // Attestation data (populated after verification)
        bool attestation_verified;             // Key Attestation was verified
        std::string attestation_package;       // Package from attestation cert
        std::vector<uint8_t> attestation_sig;  // App signature from attestation
        int32_t attestation_security_level;    // 0=SW, 1=TEE, 2=StrongBox

        bool is_active;                        // Can be used for auth

        // Check if key can be used for authentication
        bool can_authenticate() const {
            return is_active && (trust_status == TrustStatus::TRUSTED ||
                                 trust_status == TrustStatus::LEGACY);
        }
    };

// Result of key operations
    struct KeyOperationResult {
        bool success;
        std::string error_message;
        std::string key_id;  // For add operations
    };

// Key Whitelist Manager
// Manages multiple public keys for User-Provisioned PKI
// Keys are stored in /data/adb/futon/keys/ directory
    class KeyWhitelist {
    public:
        static KeyWhitelist &instance();

        // Initialize whitelist (load keys from disk)
        bool initialize();

        // Shutdown and cleanup
        void shutdown();

        // Add a new public key (called when app provisions via Root)
        // Returns key_id on success
        KeyOperationResult add_key(
                const std::vector<uint8_t> &public_key,
                const std::string &algorithm,
                const std::vector<uint8_t> &attestation_cert_chain = {}
        );

        // Remove a key by ID
        KeyOperationResult remove_key(const std::string &key_id);

        // Get a key by ID
        std::optional<PublicKeyEntry> get_key(const std::string &key_id) const;

        // Find key by public key bytes
        std::optional<PublicKeyEntry> find_key(const std::vector<uint8_t> &public_key) const;

        // Get all active keys
        std::vector<PublicKeyEntry> get_active_keys() const;

        // Verify a signature against any whitelisted key
        // Returns key_id of matching key, or empty if none match
        std::optional<std::string> verify_signature(
                const std::vector<uint8_t> &data,
                const std::vector<uint8_t> &signature
        ) const;

        // Verify attestation for a pending key
        // Called when app first connects and sends attestation chain
        // Returns true if attestation is valid and key is now TRUSTED
        struct AttestationVerifyResult {
            bool success;
            std::string error_message;
            PublicKeyEntry::TrustStatus new_status;
        };

        AttestationVerifyResult verify_key_attestation(
                const std::string &key_id,
                const std::vector<uint8_t> &attestation_chain
        );

        // Get keys that need attestation verification
        std::vector<PublicKeyEntry> get_pending_keys() const;

        // Check if a key requires attestation before use
        bool requires_attestation(const std::string &key_id) const;

        // Update last_used timestamp
        void mark_key_used(const std::string &key_id);

        // Reload keys from disk (e.g., after SIGHUP)
        bool reload();

        // Check if any keys are registered
        bool has_keys() const;

        // Get key count
        size_t key_count() const;

        // Directory path for keys
        static constexpr const char *KEYS_DIR = "/data/adb/futon/keys";

    private:
        KeyWhitelist() = default;

        ~KeyWhitelist() = default;

        KeyWhitelist(const KeyWhitelist &) = delete;

        KeyWhitelist &operator=(const KeyWhitelist &) = delete;

        // Load all keys from disk
        bool load_keys();

        // Save a key to disk
        bool save_key(const PublicKeyEntry &entry);

        // Delete key file from disk
        bool delete_key_file(const std::string &key_id);

        // Parse key file
        std::optional<PublicKeyEntry> parse_key_file(const std::string &path);

        // Generate key ID from public key
        std::string generate_key_id(const std::vector<uint8_t> &public_key) const;

        // Verify signature with specific key
        bool verify_with_key(
                const PublicKeyEntry &key,
                const std::vector<uint8_t> &data,
                const std::vector<uint8_t> &signature
        ) const;

        mutable std::mutex mutex_;
        std::unordered_map<std::string, PublicKeyEntry> keys_;
        bool initialized_ = false;
    };

} // namespace futon::core::auth

#endif // FUTON_CORE_AUTH_KEY_WHITELIST_H
