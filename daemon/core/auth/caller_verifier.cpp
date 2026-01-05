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

#include "caller_verifier.h"
#include "crypto_utils.h"
#include "integrity_checker.h"
#include "hardened_config.h"
#include "core/error.h"

#include <fstream>
#include <sstream>
#include <filesystem>
#include <algorithm>
#include <cstring>
#include <dirent.h>
#include <sys/stat.h>
#include <unistd.h>

namespace futon::core::auth {

    CallerVerifier::CallerVerifier(const CallerVerifierConfig &config)
            : config_(config) {
    }

    bool CallerVerifier::initialize() {
        std::lock_guard<std::mutex> lock(mutex_);

        // Load pinned public key if exists
        if (config_.enable_pubkey_pinning) {
            load_pinned_pubkey();
        }

        FUTON_LOGI("CallerVerifier initialized: packages=%zu, signatures=%zu, pubkey_pinned=%s",
                   config_.authorized_packages.size(),
                   config_.authorized_signatures.size(),
                   pinned_pubkey_fingerprint_.has_value() ? "yes" : "no");

        return true;
    }

    CallerVerificationResult CallerVerifier::verify_caller(uid_t uid, pid_t pid) {
        std::string package_name;
        std::string apk_path;
        std::string selinux_context;

        // Integrity checks are telemetry-only: log detections but don't block
        static IntegrityChecker integrity_checker;
        static bool integrity_initialized = false;
        if (!integrity_initialized) {
            integrity_checker.initialize();
            integrity_initialized = true;
        }

        if (integrity_checker.is_frida_present()) {
            FUTON_LOGW("Telemetry: Frida detected (non-blocking)");
        }

        if (integrity_checker.is_xposed_present()) {
            FUTON_LOGW("Telemetry: Xposed detected (non-blocking)");
        }

        if (integrity_checker.is_debugger_attached()) {
            FUTON_LOGW("Telemetry: Debugger attached (non-blocking)");
        }

        if (config_.verify_selinux_context) {
            if (!verify_selinux_context(pid, selinux_context)) {
                return CallerVerificationResult::failure(
                        "SELinux context verification failed: " + selinux_context);
            }
        }

        if (config_.verify_process_path) {
            if (!verify_process_executable(pid)) {
                return CallerVerificationResult::failure("Process executable verification failed");
            }
        }

        if (config_.verify_package_name) {
            if (!verify_package_name(pid, package_name)) {
                return CallerVerificationResult::failure("Package name verification failed");
            }

            std::string authorized_package = HardenedConfig::instance().get_authorized_package();
            if (!authorized_package.empty() && package_name != authorized_package) {
                return CallerVerificationResult::failure("Package not authorized: " + package_name);
            }
        }

        if (config_.verify_package_name) {
            if (!verify_apk_path(pid, package_name, apk_path)) {
                return CallerVerificationResult::failure(
                        "APK path verification failed for package: " + package_name);
            }
        }

        if (config_.verify_apk_signature && !apk_path.empty()) {
            if (!verify_apk_signature(apk_path)) {
                return CallerVerificationResult::failure(
                        "APK signature verification failed: " + apk_path);
            }
        }

        return CallerVerificationResult::success(package_name, apk_path, selinux_context);
    }

    bool CallerVerifier::verify_package_name(pid_t pid, std::string &out_package) {
        std::string cmdline = read_proc_file(pid, "cmdline");
        if (cmdline.empty()) {
            FUTON_LOGW("Failed to read cmdline for pid %d", pid);
            return false;
        }

        out_package = get_package_from_cmdline(cmdline);
        if (out_package.empty()) {
            FUTON_LOGW("Failed to extract package name from cmdline: %s", cmdline.c_str());
            return false;
        }

        // Check against authorized packages
        if (!config_.authorized_packages.empty()) {
            if (!is_package_authorized(out_package)) {
                FUTON_LOGW("Package not authorized: %s", out_package.c_str());
                return false;
            }
        }

        FUTON_LOGD("Package verified: %s", out_package.c_str());
        return true;
    }

    bool CallerVerifier::verify_apk_path(pid_t pid, const std::string &package,
                                         std::string &out_path) {
        out_path = get_apk_path_for_package(package);
        if (out_path.empty()) {
            FUTON_LOGW("Failed to find APK path for package: %s", package.c_str());
            return false;
        }

        // Verify APK is in expected location (/data/app/)
        if (out_path.find("/data/app/") != 0 &&
            out_path.find("/system/app/") != 0 &&
            out_path.find("/system/priv-app/") != 0) {
            FUTON_LOGW("APK path not in expected location: %s", out_path.c_str());
            return false;
        }

        // Verify APK file exists
        if (!std::filesystem::exists(out_path)) {
            FUTON_LOGW("APK file does not exist: %s", out_path.c_str());
            return false;
        }

        FUTON_LOGD("APK path verified: %s", out_path.c_str());
        return true;
    }

    bool CallerVerifier::verify_apk_signature(const std::string &apk_path) {
        auto signature = compute_apk_signature(apk_path);
        if (signature.empty()) {
            FUTON_LOGW("Failed to compute APK signature: %s", apk_path.c_str());
            return false;
        }

        // Get authorized signature from HardenedConfig
        auto authorized_sig = HardenedConfig::instance().get_authorized_signature();
        if (!authorized_sig.empty()) {
            if (CryptoUtils::constant_time_compare(signature, authorized_sig)) {
                FUTON_LOGD("APK signature verified via HardenedConfig");
                return true;
            }
        }

        // Fallback to config list
        if (config_.authorized_signatures.empty() && authorized_sig.empty()) {
            return true;
        }

        std::string signature_hex = CryptoUtils::to_hex(signature);
        if (is_signature_authorized(signature_hex)) {
            FUTON_LOGD("APK signature verified via config list");
            return true;
        }

        FUTON_LOGW("APK signature not authorized: %s", signature_hex.c_str());
        return false;
    }

    bool CallerVerifier::verify_selinux_context(pid_t pid, std::string &out_context) {
        out_context = get_selinux_context(pid);
        if (out_context.empty()) {
            FUTON_LOGW("Failed to get SELinux context for pid %d", pid);
            return false;
        }

        // Check blocked contexts first
        for (const auto &blocked: config_.blocked_selinux_contexts) {
            if (out_context.find(blocked) != std::string::npos) {
                FUTON_LOGW("SELinux context blocked: %s", out_context.c_str());
                return false;
            }
        }

        // If allowed contexts are specified, check against them
        if (!config_.allowed_selinux_contexts.empty()) {
            bool found = false;
            for (const auto &allowed: config_.allowed_selinux_contexts) {
                if (out_context.find(allowed) != std::string::npos) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                FUTON_LOGW("SELinux context not in allowed list: %s", out_context.c_str());
                return false;
            }
        }

        FUTON_LOGD("SELinux context verified: %s", out_context.c_str());
        return true;
    }

    bool CallerVerifier::verify_process_executable(pid_t pid) {
        // Read /proc/[pid]/exe symlink
        char exe_path[PATH_MAX];
        std::string proc_exe = "/proc/" + std::to_string(pid) + "/exe";

        ssize_t len = readlink(proc_exe.c_str(), exe_path, sizeof(exe_path) - 1);
        if (len == -1) {
            FUTON_LOGW("Failed to read exe link for pid %d: %s", pid, strerror(errno));
            return false;
        }
        exe_path[len] = '\0';

        std::string exe(exe_path);

        // Android apps should be running from app_process or app_process64
        if (exe.find("app_process") == std::string::npos) {
            FUTON_LOGW("Process not running from app_process: %s", exe.c_str());
            return false;
        }

        FUTON_LOGD("Process executable verified: %s", exe.c_str());
        return true;
    }

    bool CallerVerifier::pin_public_key(const std::vector<uint8_t> &pubkey_fingerprint) {
        std::lock_guard<std::mutex> lock(mutex_);

        if (pinned_pubkey_fingerprint_.has_value()) {
            // Already pinned - verify it matches
            if (!CryptoUtils::constant_time_compare(
                    pinned_pubkey_fingerprint_.value(), pubkey_fingerprint)) {
                FUTON_LOGE("Public key fingerprint mismatch with pinned key!");
                return false;
            }
            FUTON_LOGD("Public key matches pinned fingerprint");
            return true;
        }

        // First time - pin the key
        if (!save_pinned_pubkey(pubkey_fingerprint)) {
            FUTON_LOGE("Failed to save pinned public key");
            return false;
        }

        pinned_pubkey_fingerprint_ = pubkey_fingerprint;
        FUTON_LOGI("Public key pinned: %s",
                   CryptoUtils::to_hex(pubkey_fingerprint).substr(0, 16).c_str());
        return true;
    }

    bool CallerVerifier::verify_pinned_pubkey(const std::vector<uint8_t> &pubkey_fingerprint) {
        std::lock_guard<std::mutex> lock(mutex_);

        if (!pinned_pubkey_fingerprint_.has_value()) {
            // No pinned key - this is first authentication, pin it
            return true;  // Will be pinned during authentication
        }

        return CryptoUtils::constant_time_compare(
                pinned_pubkey_fingerprint_.value(), pubkey_fingerprint);
    }

    bool CallerVerifier::has_pinned_pubkey() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return pinned_pubkey_fingerprint_.has_value();
    }

    std::optional<std::vector<uint8_t>> CallerVerifier::get_pinned_pubkey_fingerprint() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return pinned_pubkey_fingerprint_;
    }

    bool CallerVerifier::clear_pinned_pubkey() {
        std::lock_guard<std::mutex> lock(mutex_);

        try {
            if (std::filesystem::exists(config_.pubkey_pin_path)) {
                std::filesystem::remove(config_.pubkey_pin_path);
            }
        } catch (const std::exception &e) {
            FUTON_LOGE("Failed to remove pinned pubkey file: %s", e.what());
            return false;
        }

        pinned_pubkey_fingerprint_.reset();
        FUTON_LOGI("Pinned public key cleared");
        return true;
    }

    void CallerVerifier::add_authorized_package(const std::string &package) {
        std::lock_guard<std::mutex> lock(mutex_);
        config_.authorized_packages.push_back(package);
    }

    void CallerVerifier::add_authorized_signature(const std::string &signature_hex) {
        std::lock_guard<std::mutex> lock(mutex_);
        config_.authorized_signatures.push_back(signature_hex);
    }

    void CallerVerifier::set_authorized_packages(const std::vector<std::string> &packages) {
        std::lock_guard<std::mutex> lock(mutex_);
        config_.authorized_packages = packages;
    }

    bool CallerVerifier::is_package_authorized(const std::string &package) const {
        if (config_.authorized_packages.empty()) {
            return true;  // No restrictions
        }

        return std::find(config_.authorized_packages.begin(),
                         config_.authorized_packages.end(),
                         package) != config_.authorized_packages.end();
    }

    bool CallerVerifier::is_signature_authorized(const std::string &signature_hex) const {
        if (config_.authorized_signatures.empty()) {
            return true;  // No restrictions
        }

        // Case-insensitive comparison
        std::string lower_sig = signature_hex;
        std::transform(lower_sig.begin(), lower_sig.end(), lower_sig.begin(), ::tolower);

        for (const auto &auth_sig: config_.authorized_signatures) {
            std::string lower_auth = auth_sig;
            std::transform(lower_auth.begin(), lower_auth.end(), lower_auth.begin(), ::tolower);
            if (lower_sig == lower_auth) {
                return true;
            }
        }

        return false;
    }

    std::string CallerVerifier::read_proc_file(pid_t pid, const char *filename) {
        std::string path = "/proc/" + std::to_string(pid) + "/" + filename;
        std::ifstream file(path);
        if (!file.is_open()) {
            return "";
        }

        std::string content;
        std::getline(file, content, '\0');
        return content;
    }

    std::string CallerVerifier::get_package_from_cmdline(const std::string &cmdline) {
        // cmdline format: "package.name" or "package.name:process_name"
        size_t colon_pos = cmdline.find(':');
        if (colon_pos != std::string::npos) {
            return cmdline.substr(0, colon_pos);
        }

        // Remove any null characters
        std::string result = cmdline;
        result.erase(std::remove(result.begin(), result.end(), '\0'), result.end());

        return result;
    }

    std::string CallerVerifier::get_apk_path_for_package(const std::string &package) {
        // Search common APK locations directly (faster than pm command)
        std::vector<std::string> search_paths = {
                "/data/app/",
                "/system/app/",
                "/system/priv-app/"
        };

        for (const auto &base_path: search_paths) {
            try {
                if (!std::filesystem::exists(base_path)) continue;

                // On Android 12+, format is: /data/app/~~RANDOM==/package.name-RANDOM==/base.apk
                for (const auto &entry: std::filesystem::directory_iterator(base_path)) {
                    if (!entry.is_directory()) continue;

                    std::string dir_name = entry.path().filename().string();

                    // Check if directory name contains package name (old format)
                    if (dir_name.find(package) == 0) {
                        std::string apk_path = entry.path().string() + "/base.apk";
                        if (std::filesystem::exists(apk_path)) {
                            FUTON_LOGD("Found APK (old format): %s", apk_path.c_str());
                            return apk_path;
                        }
                    }

                    // Check subdirectories (new format with ~~RANDOM==)
                    if (dir_name.find("~~") == 0 || dir_name.find("==") != std::string::npos) {
                        try {
                            for (const auto &sub_entry: std::filesystem::directory_iterator(
                                    entry.path())) {
                                if (!sub_entry.is_directory()) continue;
                                std::string sub_dir_name = sub_entry.path().filename().string();
                                // Format: package.name-RANDOM== or package.name-RANDOM
                                if (sub_dir_name.find(package) == 0) {
                                    std::string apk_path = sub_entry.path().string() + "/base.apk";
                                    if (std::filesystem::exists(apk_path)) {
                                        FUTON_LOGD("Found APK (new format): %s", apk_path.c_str());
                                        return apk_path;
                                    }
                                }
                            }
                        } catch (const std::exception &e) {
                            FUTON_LOGW("Error searching subdir %s: %s", entry.path().c_str(),
                                       e.what());
                        }
                    }
                }
            } catch (const std::exception &e) {
                FUTON_LOGW("Error searching %s: %s", base_path.c_str(), e.what());
            }
        }

        FUTON_LOGW("APK not found for package: %s", package.c_str());
        return "";
    }

    namespace {
        inline uint32_t read_u32_le(const uint8_t *data) {
            return data[0] | (data[1] << 8) | (data[2] << 16) | (data[3] << 24);
        }

        inline uint64_t read_u64_le(const uint8_t *data) {
            uint64_t result = 0;
            for (int i = 0; i < 8; i++) {
                result |= static_cast<uint64_t>(data[i]) << (i * 8);
            }
            return result;
        }

        // Extract first X.509 certificate DER from APK Signature Scheme v2/v3 block
        std::optional<std::vector<uint8_t>> extract_certificate_from_v2v3_block(
                const uint8_t *data, size_t length) {
            if (length < 4) return std::nullopt;

            // signers: length-prefixed sequence
            uint32_t signers_len = read_u32_le(data);
            if (signers_len > length - 4 || signers_len < 4) return std::nullopt;

            // first signer: length-prefixed
            const uint8_t *signer_ptr = data + 4;
            uint32_t signer_len = read_u32_le(signer_ptr);
            if (signer_len > signers_len - 4) return std::nullopt;

            const uint8_t *signer_data = signer_ptr + 4;
            size_t pos = 0;

            // signed_data: length-prefixed
            if (pos + 4 > signer_len) return std::nullopt;
            uint32_t signed_data_len = read_u32_le(signer_data + pos);
            pos += 4;
            if (pos + signed_data_len > signer_len) return std::nullopt;

            const uint8_t *signed_data = signer_data + pos;
            size_t sd_pos = 0;

            // digests: length-prefixed sequence (skip)
            if (sd_pos + 4 > signed_data_len) return std::nullopt;
            uint32_t digests_len = read_u32_le(signed_data + sd_pos);
            sd_pos += 4 + digests_len;

            // certificates: length-prefixed sequence
            if (sd_pos + 4 > signed_data_len) return std::nullopt;
            uint32_t certs_len = read_u32_le(signed_data + sd_pos);
            sd_pos += 4;
            if (sd_pos + certs_len > signed_data_len) return std::nullopt;

            const uint8_t *certs_data = signed_data + sd_pos;

            // first certificate: length-prefixed DER
            if (certs_len < 4) return std::nullopt;
            uint32_t cert_len = read_u32_le(certs_data);
            if (4 + cert_len > certs_len) return std::nullopt;

            return std::vector<uint8_t>(certs_data + 4, certs_data + 4 + cert_len);
        }
    }  // namespace

    std::vector<uint8_t> CallerVerifier::compute_apk_signature(const std::string &apk_path) {
        std::ifstream file(apk_path, std::ios::binary | std::ios::ate);
        if (!file.is_open()) {
            FUTON_LOGW("Cannot open APK: %s", apk_path.c_str());
            return {};
        }

        size_t file_size = file.tellg();
        if (file_size < 22) {
            FUTON_LOGW("APK too small");
            return {};
        }

        file.seekg(0, std::ios::beg);
        std::vector<uint8_t> apk(file_size);
        file.read(reinterpret_cast<char *>(apk.data()), file_size);
        file.close();

        // Locate EOCD (End of Central Directory)
        size_t eocd_offset = 0;
        for (size_t i = file_size - 22; i > 0 && i > file_size - 65557; --i) {
            if (apk[i] == 0x50 && apk[i + 1] == 0x4b &&
                apk[i + 2] == 0x05 && apk[i + 3] == 0x06) {
                eocd_offset = i;
                break;
            }
        }
        if (eocd_offset == 0) {
            FUTON_LOGW("EOCD not found");
            return {};
        }

        uint32_t cd_offset = read_u32_le(&apk[eocd_offset + 16]);
        if (cd_offset < 32 || cd_offset > file_size) {
            FUTON_LOGW("Invalid CD offset");
            return {};
        }

        // Verify APK Signing Block magic
        const char *magic = "APK Sig Block 42";
        if (memcmp(&apk[cd_offset - 16], magic, 16) != 0) {
            FUTON_LOGW("APK Signing Block not found");
            return {};
        }

        // Parse block structure per AOSP ApkSigningBlockUtils
        uint64_t size_of_block = read_u64_le(&apk[cd_offset - 24]);
        if (size_of_block < 8 || size_of_block > cd_offset - 8) {
            FUTON_LOGW("Invalid block size");
            return {};
        }

        size_t block_offset = cd_offset - (size_of_block + 8);
        uint64_t leading_size = read_u64_le(&apk[block_offset]);
        if (leading_size != size_of_block) {
            FUTON_LOGW("Block size mismatch");
            return {};
        }

        // Extract certificate from ID-value pairs
        size_t pairs_start = block_offset + 8;
        size_t pairs_end = pairs_start + size_of_block - 8;
        size_t offset = pairs_start;

        while (offset + 12 <= pairs_end) {
            uint64_t pair_len = read_u64_le(&apk[offset]);
            offset += 8;

            if (pair_len < 4 || offset + pair_len > pairs_end) break;

            uint32_t id = read_u32_le(&apk[offset]);
            offset += 4;
            size_t value_len = pair_len - 4;

            // APK Signature Scheme v2/v3/v3.1
            if (id == 0x7109871a || id == 0xf05368c0 || id == 0x1b93ad61) {
                auto cert = extract_certificate_from_v2v3_block(&apk[offset], value_len);
                if (cert.has_value()) {
                    auto fp = CryptoUtils::sha256_raw(cert.value());
                    FUTON_LOGD("Certificate SHA-256: %s", CryptoUtils::to_hex(fp).c_str());
                    return fp;
                }
            }

            offset += value_len;
        }

        FUTON_LOGW("No certificate found");
        return {};
    }

    std::string CallerVerifier::get_selinux_context(pid_t pid) {
        std::string path = "/proc/" + std::to_string(pid) + "/attr/current";
        std::ifstream file(path);
        if (!file.is_open()) {
            return "";
        }

        std::string context;
        std::getline(file, context);

        // Remove trailing null character if present
        if (!context.empty() && context.back() == '\0') {
            context.pop_back();
        }

        return context;
    }

    bool CallerVerifier::load_pinned_pubkey() {
        try {
            if (!std::filesystem::exists(config_.pubkey_pin_path)) {
                return false;
            }

            std::ifstream file(config_.pubkey_pin_path);
            if (!file.is_open()) {
                return false;
            }

            std::string hex_fingerprint;
            std::getline(file, hex_fingerprint);

            // Trim whitespace
            hex_fingerprint.erase(0, hex_fingerprint.find_first_not_of(" \t\n\r"));
            hex_fingerprint.erase(hex_fingerprint.find_last_not_of(" \t\n\r") + 1);

            auto decoded = CryptoUtils::from_hex(hex_fingerprint);
            if (!decoded.has_value()) {
                FUTON_LOGW("Invalid hex in pinned pubkey file");
                return false;
            }

            pinned_pubkey_fingerprint_ = std::move(decoded.value());
            FUTON_LOGI("Loaded pinned pubkey fingerprint: %s",
                       hex_fingerprint.substr(0, 16).c_str());
            return true;
        } catch (const std::exception &e) {
            FUTON_LOGW("Failed to load pinned pubkey: %s", e.what());
            return false;
        }
    }

    bool CallerVerifier::save_pinned_pubkey(const std::vector<uint8_t> &fingerprint) {
        try {
            // Ensure directory exists
            std::filesystem::path pin_path(config_.pubkey_pin_path);
            std::filesystem::path pin_dir = pin_path.parent_path();

            if (!pin_dir.empty() && !std::filesystem::exists(pin_dir)) {
                std::filesystem::create_directories(pin_dir);
            }

            std::ofstream file(config_.pubkey_pin_path);
            if (!file.is_open()) {
                FUTON_LOGE("Failed to open pinned pubkey file for writing");
                return false;
            }

            file << CryptoUtils::to_hex(fingerprint) << std::endl;
            file.close();

            // Set restrictive permissions
            chmod(config_.pubkey_pin_path.c_str(), 0600);

            return true;
        } catch (const std::exception &e) {
            FUTON_LOGE("Failed to save pinned pubkey: %s", e.what());
            return false;
        }
    }

} // namespace futon::core::auth
