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

#ifndef FUTON_CORE_AUTH_ATTESTATION_VERIFIER_H
#define FUTON_CORE_AUTH_ATTESTATION_VERIFIER_H

#include <string>
#include <vector>
#include <cstdint>
#include <optional>

namespace futon::core::auth {

// Android Key Attestation verification result
    struct AttestationResult {
        bool valid;
        std::string error_message;

        // Extracted from attestation certificate
        std::string package_name;           // Application ID
        std::vector<uint8_t> app_signature; // APK signing certificate digest
        int32_t attestation_version;        // Attestation version
        int32_t security_level;             // 0=Software, 1=TrustedEnvironment, 2=StrongBox

        // Key properties
        bool hardware_backed;               // Key is in TEE/StrongBox
        bool user_presence_required;        // Requires biometric/PIN

        // Device state
        bool verified_boot;                 // Device has verified boot
        bool device_locked;                 // Bootloader is locked
    };

// Configuration for attestation verification
    struct AttestationConfig {
        // Required package name (empty = any)
        std::string required_package;

        // Required APK signature (empty = any)
        std::vector<uint8_t> required_signature;

        // Minimum security level (0=any, 1=TEE, 2=StrongBox)
        int32_t min_security_level = 1;

        // Require hardware-backed key
        bool require_hardware_backed = true;

        // Require verified boot
        bool require_verified_boot = false;

        // Require locked bootloader
        bool require_device_locked = false;
    };

// Android Key Attestation Verifier
// Verifies that a public key was generated in Android KeyStore
// and extracts app identity from the attestation certificate chain
//
// Reference: https://developer.android.com/training/articles/security-key-attestation
    class AttestationVerifier {
    public:
        static AttestationVerifier &instance();

        // Initialize verifier (load Google root certificates)
        bool initialize();

        // Set verification configuration
        void set_config(const AttestationConfig &config);

        // Verify attestation certificate chain
        // cert_chain: DER-encoded certificate chain (leaf first)
        // public_key: Expected public key (must match leaf cert)
        AttestationResult verify(
                const std::vector<uint8_t> &cert_chain,
                const std::vector<uint8_t> &public_key
        );

        // Verify with raw certificate chain (multiple DER certs concatenated)
        AttestationResult verify_chain(
                const std::vector<std::vector<uint8_t>> &certs,
                const std::vector<uint8_t> &public_key
        );

        // Check if a signature matches the configured authorized signature
        bool is_authorized_signature(const std::vector<uint8_t> &signature) const;

        // Check if a package matches the configured authorized package
        bool is_authorized_package(const std::string &package) const;

    private:
        AttestationVerifier() = default;

        ~AttestationVerifier() = default;

        AttestationVerifier(const AttestationVerifier &) = delete;

        AttestationVerifier &operator=(const AttestationVerifier &) = delete;

        // Parse X.509 certificate and extract attestation extension
        bool parse_attestation_extension(
                const std::vector<uint8_t> &cert,
                AttestationResult &result
        );

        // Verify certificate chain signature
        bool verify_cert_chain(const std::vector<std::vector<uint8_t>> &certs);

        // Extract public key from certificate
        std::vector<uint8_t> extract_public_key(const std::vector<uint8_t> &cert);

        // Parse ASN.1 structures
        bool parse_asn1_sequence(
                const uint8_t *data,
                size_t len,
                std::vector<std::pair<const uint8_t *, size_t>> &elements
        );

        // Parse attestation extension OID: 1.3.6.1.4.1.11129.2.1.17
        static constexpr uint8_t ATTESTATION_OID[] = {
                0x06, 0x0A, 0x2B, 0x06, 0x01, 0x04, 0x01, 0xD6, 0x79, 0x02, 0x01, 0x11
        };

        AttestationConfig config_;
        bool initialized_ = false;

        // Google hardware attestation root certificates (DER)
        std::vector<std::vector<uint8_t>> root_certs_;
    };

} // namespace futon::core::auth

#endif // FUTON_CORE_AUTH_ATTESTATION_VERIFIER_H
