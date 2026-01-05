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

#ifndef FUTON_CORE_AUTH_CALLER_VERIFIER_H
#define FUTON_CORE_AUTH_CALLER_VERIFIER_H

#include <cstdint>
#include <string>
#include <vector>
#include <mutex>
#include <optional>
#include <unordered_set>

namespace futon::core::auth {

// Verification result
    struct CallerVerificationResult {
        bool verified;
        std::string package_name;
        std::string apk_path;
        std::string selinux_context;
        std::string failure_reason;

        static CallerVerificationResult success(
                const std::string &pkg, const std::string &apk, const std::string &ctx) {
            return {true, pkg, apk, ctx, ""};
        }

        static CallerVerificationResult failure(const std::string &reason) {
            return {false, "", "", "", reason};
        }
    };

// Caller verification configuration
    struct CallerVerifierConfig {
        // Authorized package names (empty = allow all)
        std::vector<std::string> authorized_packages;

        // Expected APK signature fingerprints (SHA-256, hex encoded)
        // If empty, signature verification is skipped
        std::vector<std::string> authorized_signatures;

        // Path to store pinned public key fingerprint
        std::string pubkey_pin_path = "/data/adb/futon/.pubkey_pin";

        // Enable various verification layers
        bool verify_package_name = true;
        bool verify_apk_signature = true;
        bool verify_selinux_context = true;
        bool verify_process_path = true;
        bool enable_pubkey_pinning = true;

        // Allowed SELinux contexts (empty = allow all app contexts)
        std::unordered_set<std::string> allowed_selinux_contexts;

        // Blocked SELinux contexts (always blocked)
        std::unordered_set<std::string> blocked_selinux_contexts = {
                "u:r:su:s0",
                "u:r:magisk:s0",
                "u:r:zygote:s0",
                "u:r:shell:s0"
        };
    };

    class CallerVerifier {
    public:
        explicit CallerVerifier(const CallerVerifierConfig &config = CallerVerifierConfig());

        ~CallerVerifier() = default;

        // Disable copy
        CallerVerifier(const CallerVerifier &) = delete;

        CallerVerifier &operator=(const CallerVerifier &) = delete;

        // Initialize verifier
        bool initialize();

        // Main verification entry point
        CallerVerificationResult verify_caller(uid_t uid, pid_t pid);

        // Individual verification steps (can be called separately)
        bool verify_package_name(pid_t pid, std::string &out_package);

        bool verify_apk_path(pid_t pid, const std::string &package, std::string &out_path);

        bool verify_apk_signature(const std::string &apk_path);

        bool verify_selinux_context(pid_t pid, std::string &out_context);

        bool verify_process_executable(pid_t pid);

        // Public key pinning
        bool pin_public_key(const std::vector<uint8_t> &pubkey_fingerprint);

        bool verify_pinned_pubkey(const std::vector<uint8_t> &pubkey_fingerprint);

        bool has_pinned_pubkey() const;

        std::optional<std::vector<uint8_t>> get_pinned_pubkey_fingerprint() const;

        bool clear_pinned_pubkey();

        // Configuration updates
        void add_authorized_package(const std::string &package);

        void add_authorized_signature(const std::string &signature_hex);

        void set_authorized_packages(const std::vector<std::string> &packages);

        // Query
        bool is_package_authorized(const std::string &package) const;

        bool is_signature_authorized(const std::string &signature_hex) const;

    private:
        CallerVerifierConfig config_;
        mutable std::mutex mutex_;

        // Cached pinned public key fingerprint
        std::optional<std::vector<uint8_t>> pinned_pubkey_fingerprint_;

        // Helper methods
        std::string read_proc_file(pid_t pid, const char *filename);

        std::string get_package_from_cmdline(const std::string &cmdline);

        std::string get_apk_path_for_package(const std::string &package);

        std::vector<uint8_t> compute_apk_signature(const std::string &apk_path);

        std::string get_selinux_context(pid_t pid);

        // Load/save pinned key
        bool load_pinned_pubkey();

        bool save_pinned_pubkey(const std::vector<uint8_t> &fingerprint);
    };

} // namespace futon::core::auth

#endif // FUTON_CORE_AUTH_CALLER_VERIFIER_H
