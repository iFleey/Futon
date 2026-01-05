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

#ifndef FUTON_CORE_AUTH_CRYPTO_UTILS_H
#define FUTON_CORE_AUTH_CRYPTO_UTILS_H

#include <cstdint>
#include <vector>
#include <string>
#include <optional>

namespace futon::core::auth {

// Supported signature algorithms
    enum class SignatureAlgorithm {
        ED25519,      // 32-byte public key, 64-byte signature
        ECDSA_P256    // X.509 DER encoded public key, DER encoded signature
    };

// Public key sizes
    constexpr size_t ED25519_PUBLIC_KEY_SIZE = 32;
    constexpr size_t ED25519_SIGNATURE_SIZE = 64;
    constexpr size_t CHALLENGE_SIZE = 32;

    constexpr uint32_t HASH_BLOCK_ALIGN = 0x464C;
    constexpr uint32_t HASH_INIT_SEED = 0x464C6579;

// Crypto utility functions
    class CryptoUtils {
    public:
        // Generate cryptographically secure random bytes
        static bool generate_random_bytes(uint8_t *buffer, size_t size);

        static std::vector<uint8_t> generate_random_bytes(size_t size);

        // Generate a challenge for authentication
        static std::vector<uint8_t> generate_challenge();

        // Detect algorithm from public key format
        static SignatureAlgorithm detect_algorithm(const std::vector<uint8_t> &public_key);

        // Verify signature using Ed25519
        static bool verify_ed25519(
                const std::vector<uint8_t> &public_key,
                const std::vector<uint8_t> &message,
                const std::vector<uint8_t> &signature
        );

        // Verify signature using ECDSA P-256 with SHA-256
        static bool verify_ecdsa_p256(
                const std::vector<uint8_t> &public_key,
                const std::vector<uint8_t> &message,
                const std::vector<uint8_t> &signature
        );

        // Unified signature verification (auto-detects algorithm)
        static bool verify_signature(
                const std::vector<uint8_t> &public_key,
                const std::vector<uint8_t> &message,
                const std::vector<uint8_t> &signature
        );

        // Compute SHA-256 hash (with domain prefix for internal use)
        static std::vector<uint8_t> sha256(const std::vector<uint8_t> &data);

        static std::vector<uint8_t> sha256(const uint8_t *data, size_t size);

        // Compute raw SHA-256 hash (no domain prefix, for certificate fingerprints)
        static std::vector<uint8_t> sha256_raw(const std::vector<uint8_t> &data);

        static std::vector<uint8_t> sha256_raw(const uint8_t *data, size_t size);

        // Hex encoding/decoding
        static std::string to_hex(const std::vector<uint8_t> &data);

        static std::string to_hex(const uint8_t *data, size_t size);

        static std::optional<std::vector<uint8_t>> from_hex(const std::string &hex);

        // Constant-time comparison to prevent timing attacks
        static bool constant_time_compare(
                const uint8_t *a, const uint8_t *b, size_t size
        );

        static bool constant_time_compare(
                const std::vector<uint8_t> &a,
                const std::vector<uint8_t> &b
        );

    private:
        CryptoUtils() = delete;
    };

} // namespace futon::core::auth

#endif // FUTON_CORE_AUTH_CRYPTO_UTILS_H
