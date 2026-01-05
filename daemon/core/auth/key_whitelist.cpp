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

#include "key_whitelist.h"
#include "crypto_utils.h"
#include "attestation_verifier.h"
#include "core/error.h"

#include <fstream>
#include <sstream>
#include <dirent.h>
#include <sys/stat.h>
#include <unistd.h>
#include <chrono>
#include <algorithm>

namespace futon::core::auth {

    KeyWhitelist &KeyWhitelist::instance() {
        static KeyWhitelist instance;
        return instance;
    }

    bool KeyWhitelist::initialize() {
        std::lock_guard<std::mutex> lock(mutex_);

        if (initialized_) {
            return true;
        }

        FUTON_LOGI("Initializing key whitelist...");

        // Ensure keys directory exists
        if (mkdir(KEYS_DIR, 0700) != 0 && errno != EEXIST) {
            FUTON_LOGE("Failed to create keys directory: %s", strerror(errno));
            return false;
        }

        // Set restrictive permissions
        chmod(KEYS_DIR, 0700);

        // Load existing keys
        if (!load_keys()) {
            FUTON_LOGW("Failed to load some keys, continuing with available keys");
        }

        initialized_ = true;
        FUTON_LOGI("Key whitelist initialized with %zu keys", keys_.size());
        return true;
    }

    void KeyWhitelist::shutdown() {
        std::lock_guard<std::mutex> lock(mutex_);
        keys_.clear();
        initialized_ = false;
    }

    bool KeyWhitelist::load_keys() {
        DIR *dir = opendir(KEYS_DIR);
        if (!dir) {
            FUTON_LOGW("Cannot open keys directory: %s", strerror(errno));
            return false;
        }

        struct dirent *entry;
        int loaded = 0;
        int failed = 0;

        while ((entry = readdir(dir)) != nullptr) {
            // Skip . and ..
            if (entry->d_name[0] == '.') continue;

            // Only process .key files
            std::string filename(entry->d_name);
            if (filename.size() < 4 || filename.substr(filename.size() - 4) != ".key") {
                continue;
            }

            std::string path = std::string(KEYS_DIR) + "/" + filename;
            auto key_entry = parse_key_file(path);

            if (key_entry.has_value()) {
                keys_[key_entry->key_id] = std::move(*key_entry);
                loaded++;
            } else {
                FUTON_LOGW("Failed to parse key file: %s", path.c_str());
                failed++;
            }
        }

        closedir(dir);

        FUTON_LOGI("Loaded %d keys, %d failed", loaded, failed);
        return failed == 0;
    }

    std::optional<PublicKeyEntry> KeyWhitelist::parse_key_file(const std::string &path) {
        std::ifstream file(path);
        if (!file.is_open()) {
            return std::nullopt;
        }

        PublicKeyEntry entry;
        std::string line;

        // Simple key-value format:
        // key_id=<hex>
        // algorithm=EC_P256
        // public_key=<hex>
        // created_at=<timestamp>
        // attestation_verified=true/false
        // attestation_package=<package>
        // attestation_sig=<hex>

        while (std::getline(file, line)) {
            // Skip empty lines and comments
            if (line.empty() || line[0] == '#') continue;

            size_t eq_pos = line.find('=');
            if (eq_pos == std::string::npos) continue;

            std::string key = line.substr(0, eq_pos);
            std::string value = line.substr(eq_pos + 1);

            // Trim whitespace
            value.erase(0, value.find_first_not_of(" \t\r\n"));
            value.erase(value.find_last_not_of(" \t\r\n") + 1);

            if (key == "key_id") {
                entry.key_id = value;
            } else if (key == "algorithm") {
                entry.algorithm = value;
            } else if (key == "public_key") {
                auto bytes = CryptoUtils::from_hex(value);
                if (bytes.has_value()) {
                    entry.public_key = std::move(*bytes);
                }
            } else if (key == "created_at") {
                entry.created_at = std::stoull(value);
            } else if (key == "last_used_at") {
                entry.last_used_at = std::stoull(value);
            } else if (key == "attestation_verified") {
                entry.attestation_verified = (value == "true" || value == "1");
            } else if (key == "attestation_package") {
                entry.attestation_package = value;
            } else if (key == "attestation_sig") {
                auto bytes = CryptoUtils::from_hex(value);
                if (bytes.has_value()) {
                    entry.attestation_sig = std::move(*bytes);
                }
            } else if (key == "is_active") {
                entry.is_active = (value == "true" || value == "1");
            } else if (key == "trust_status") {
                if (value == "TRUSTED") {
                    entry.trust_status = PublicKeyEntry::TrustStatus::TRUSTED;
                } else if (value == "REJECTED") {
                    entry.trust_status = PublicKeyEntry::TrustStatus::REJECTED;
                } else if (value == "LEGACY") {
                    entry.trust_status = PublicKeyEntry::TrustStatus::LEGACY;
                } else {
                    entry.trust_status = PublicKeyEntry::TrustStatus::PENDING_ATTESTATION;
                }
            } else if (key == "attestation_security_level") {
                entry.attestation_security_level = std::stoi(value);
            }
        }

        // Validate required fields
        if (entry.key_id.empty() || entry.public_key.empty() || entry.algorithm.empty()) {
            return std::nullopt;
        }

        // Default to active if not specified
        if (entry.created_at == 0) {
            entry.created_at = static_cast<uint64_t>(
                    std::chrono::system_clock::now().time_since_epoch().count() / 1000000);
        }

        return entry;
    }

    bool KeyWhitelist::save_key(const PublicKeyEntry &entry) {
        std::string path = std::string(KEYS_DIR) + "/" + entry.key_id + ".key";
        std::ofstream file(path);

        if (!file.is_open()) {
            FUTON_LOGE("Failed to create key file: %s", path.c_str());
            return false;
        }

        file << "# Futon Public Key Entry\n";
        file << "# Auto-generated - do not edit manually\n\n";
        file << "key_id=" << entry.key_id << "\n";
        file << "algorithm=" << entry.algorithm << "\n";
        file << "public_key=" << CryptoUtils::to_hex(entry.public_key) << "\n";
        file << "created_at=" << entry.created_at << "\n";
        file << "last_used_at=" << entry.last_used_at << "\n";
        file << "attestation_verified=" << (entry.attestation_verified ? "true" : "false") << "\n";
        file << "attestation_package=" << entry.attestation_package << "\n";
        file << "attestation_sig=" << CryptoUtils::to_hex(entry.attestation_sig) << "\n";
        file << "attestation_security_level=" << entry.attestation_security_level << "\n";

        // Trust status
        std::string trust_str;
        switch (entry.trust_status) {
            case PublicKeyEntry::TrustStatus::TRUSTED:
                trust_str = "TRUSTED";
                break;
            case PublicKeyEntry::TrustStatus::REJECTED:
                trust_str = "REJECTED";
                break;
            case PublicKeyEntry::TrustStatus::LEGACY:
                trust_str = "LEGACY";
                break;
            default:
                trust_str = "PENDING_ATTESTATION";
                break;
        }
        file << "trust_status=" << trust_str << "\n";
        file << "is_active=" << (entry.is_active ? "true" : "false") << "\n";

        file.close();

        // Set restrictive permissions
        chmod(path.c_str(), 0600);

        return true;
    }

    bool KeyWhitelist::delete_key_file(const std::string &key_id) {
        std::string path = std::string(KEYS_DIR) + "/" + key_id + ".key";
        return unlink(path.c_str()) == 0;
    }

    std::string KeyWhitelist::generate_key_id(const std::vector<uint8_t> &public_key) const {
        auto hash = CryptoUtils::sha256(public_key);
        // Use first 16 bytes (32 hex chars) as key ID
        return CryptoUtils::to_hex(std::vector<uint8_t>(hash.begin(), hash.begin() + 16));
    }

    KeyOperationResult KeyWhitelist::add_key(
            const std::vector<uint8_t> &public_key,
            const std::string &algorithm,
            const std::vector<uint8_t> &attestation_cert_chain
    ) {
        std::lock_guard<std::mutex> lock(mutex_);

        KeyOperationResult result;
        result.success = false;

        if (public_key.empty()) {
            result.error_message = "Empty public key";
            return result;
        }

        if (algorithm != "EC_P256" && algorithm != "ED25519") {
            result.error_message = "Unsupported algorithm: " + algorithm;
            return result;
        }

        // Generate key ID
        std::string key_id = generate_key_id(public_key);

        // Check if key already exists
        if (keys_.find(key_id) != keys_.end()) {
            result.success = true;
            result.key_id = key_id;
            result.error_message = "Key already registered";
            return result;
        }

        // Create entry
        PublicKeyEntry entry;
        entry.key_id = key_id;
        entry.public_key = public_key;
        entry.algorithm = algorithm;
        entry.created_at = static_cast<uint64_t>(
                std::chrono::system_clock::now().time_since_epoch().count() / 1000000);
        entry.last_used_at = 0;
        entry.is_active = true;

        // Verify attestation if provided
        if (!attestation_cert_chain.empty()) {
            auto &verifier = AttestationVerifier::instance();
            auto attest_result = verifier.verify(attestation_cert_chain, public_key);

            if (attest_result.valid) {
                entry.attestation_verified = true;
                entry.attestation_package = attest_result.package_name;
                entry.attestation_sig = attest_result.app_signature;
                entry.attestation_security_level = attest_result.security_level;
                entry.trust_status = PublicKeyEntry::TrustStatus::TRUSTED;
                FUTON_LOGI("Key attestation verified: package=%s, security_level=%d",
                           attest_result.package_name.c_str(),
                           attest_result.security_level);
            } else {
                FUTON_LOGW("Key attestation failed: %s", attest_result.error_message.c_str());
                // Mark as pending - will need attestation on first connect
                entry.attestation_verified = false;
                entry.trust_status = PublicKeyEntry::TrustStatus::PENDING_ATTESTATION;
            }
        } else {
            // No attestation provided - mark as pending
            entry.attestation_verified = false;
            entry.trust_status = PublicKeyEntry::TrustStatus::PENDING_ATTESTATION;
            FUTON_LOGW("Key added without attestation - requires verification on first connect");
        }

        // Save to disk
        if (!save_key(entry)) {
            result.error_message = "Failed to save key to disk";
            return result;
        }

        // Add to memory
        keys_[key_id] = std::move(entry);

        result.success = true;
        result.key_id = key_id;
        FUTON_LOGI("Key added: %s (attestation=%s)",
                   key_id.c_str(),
                   entry.attestation_verified ? "verified" : "none");

        return result;
    }

    KeyOperationResult KeyWhitelist::remove_key(const std::string &key_id) {
        std::lock_guard<std::mutex> lock(mutex_);

        KeyOperationResult result;
        result.key_id = key_id;

        auto it = keys_.find(key_id);
        if (it == keys_.end()) {
            result.success = false;
            result.error_message = "Key not found";
            return result;
        }

        // Delete from disk
        delete_key_file(key_id);

        // Remove from memory
        keys_.erase(it);

        result.success = true;
        FUTON_LOGI("Key removed: %s", key_id.c_str());
        return result;
    }

    std::optional<PublicKeyEntry> KeyWhitelist::get_key(const std::string &key_id) const {
        std::lock_guard<std::mutex> lock(mutex_);

        auto it = keys_.find(key_id);
        if (it != keys_.end()) {
            return it->second;
        }
        return std::nullopt;
    }

    std::optional<PublicKeyEntry>
    KeyWhitelist::find_key(const std::vector<uint8_t> &public_key) const {
        std::lock_guard<std::mutex> lock(mutex_);

        std::string key_id = generate_key_id(public_key);
        auto it = keys_.find(key_id);
        if (it != keys_.end()) {
            return it->second;
        }
        return std::nullopt;
    }

    std::vector<PublicKeyEntry> KeyWhitelist::get_active_keys() const {
        std::lock_guard<std::mutex> lock(mutex_);

        std::vector<PublicKeyEntry> active;
        for (const auto &[id, entry]: keys_) {
            if (entry.is_active) {
                active.push_back(entry);
            }
        }
        return active;
    }

    bool KeyWhitelist::verify_with_key(
            const PublicKeyEntry &key,
            const std::vector<uint8_t> &data,
            const std::vector<uint8_t> &signature
    ) const {
        if (key.algorithm == "EC_P256") {
            return CryptoUtils::verify_ecdsa_p256(key.public_key, data, signature);
        } else if (key.algorithm == "ED25519") {
            return CryptoUtils::verify_ed25519(key.public_key, data, signature);
        }
        return false;
    }

    std::optional<std::string> KeyWhitelist::verify_signature(
            const std::vector<uint8_t> &data,
            const std::vector<uint8_t> &signature
    ) const {
        std::lock_guard<std::mutex> lock(mutex_);

        for (const auto &[id, entry]: keys_) {
            // Only use keys that can authenticate
            if (!entry.can_authenticate()) continue;

            if (verify_with_key(entry, data, signature)) {
                return id;
            }
        }

        return std::nullopt;
    }

    KeyWhitelist::AttestationVerifyResult KeyWhitelist::verify_key_attestation(
            const std::string &key_id,
            const std::vector<uint8_t> &attestation_chain
    ) {
        std::lock_guard<std::mutex> lock(mutex_);

        AttestationVerifyResult result;
        result.success = false;
        result.new_status = PublicKeyEntry::TrustStatus::PENDING_ATTESTATION;

        auto it = keys_.find(key_id);
        if (it == keys_.end()) {
            result.error_message = "Key not found: " + key_id;
            return result;
        }

        auto &entry = it->second;

        // Already trusted?
        if (entry.trust_status == PublicKeyEntry::TrustStatus::TRUSTED) {
            result.success = true;
            result.new_status = PublicKeyEntry::TrustStatus::TRUSTED;
            return result;
        }

        // Verify attestation
        auto &verifier = AttestationVerifier::instance();
        auto attest_result = verifier.verify(attestation_chain, entry.public_key);

        if (attest_result.valid) {
            // Update entry
            entry.attestation_verified = true;
            entry.attestation_package = attest_result.package_name;
            entry.attestation_sig = attest_result.app_signature;
            entry.attestation_security_level = attest_result.security_level;
            entry.trust_status = PublicKeyEntry::TrustStatus::TRUSTED;

            // Save to disk
            save_key(entry);

            result.success = true;
            result.new_status = PublicKeyEntry::TrustStatus::TRUSTED;

            FUTON_LOGI("Key %s attestation verified: package=%s",
                       key_id.c_str(), attest_result.package_name.c_str());
        } else {
            // Attestation failed - reject the key
            entry.trust_status = PublicKeyEntry::TrustStatus::REJECTED;
            entry.is_active = false;

            // Save to disk
            save_key(entry);

            result.error_message = "Attestation Mismatch: " + attest_result.error_message;
            result.new_status = PublicKeyEntry::TrustStatus::REJECTED;

            FUTON_LOGE("SECURITY ALERT: Key %s attestation REJECTED: %s",
                       key_id.c_str(), attest_result.error_message.c_str());
            FUTON_LOGE("Possible malicious Root app attempting to impersonate!");
        }

        return result;
    }

    std::vector<PublicKeyEntry> KeyWhitelist::get_pending_keys() const {
        std::lock_guard<std::mutex> lock(mutex_);

        std::vector<PublicKeyEntry> pending;
        for (const auto &[id, entry]: keys_) {
            if (entry.trust_status == PublicKeyEntry::TrustStatus::PENDING_ATTESTATION) {
                pending.push_back(entry);
            }
        }
        return pending;
    }

    bool KeyWhitelist::requires_attestation(const std::string &key_id) const {
        std::lock_guard<std::mutex> lock(mutex_);

        auto it = keys_.find(key_id);
        if (it == keys_.end()) return false;

        return it->second.trust_status == PublicKeyEntry::TrustStatus::PENDING_ATTESTATION;
    }

    void KeyWhitelist::mark_key_used(const std::string &key_id) {
        std::lock_guard<std::mutex> lock(mutex_);

        auto it = keys_.find(key_id);
        if (it != keys_.end()) {
            it->second.last_used_at = static_cast<uint64_t>(
                    std::chrono::system_clock::now().time_since_epoch().count() / 1000000);
        }
    }

    bool KeyWhitelist::reload() {
        std::lock_guard<std::mutex> lock(mutex_);

        FUTON_LOGI("Reloading key whitelist...");
        keys_.clear();
        return load_keys();
    }

    bool KeyWhitelist::has_keys() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return !keys_.empty();
    }

    size_t KeyWhitelist::key_count() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return keys_.size();
    }

} // namespace futon::core::auth
