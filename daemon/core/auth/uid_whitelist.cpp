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

#include "uid_whitelist.h"
#include "crypto_utils.h"
#include "core/error.h"

#include <fstream>
#include <sstream>
#include <filesystem>
#include <algorithm>
#include <cstring>
#include <unistd.h>
#include <sys/stat.h>

// Simple JSON serialization (avoiding external dependencies)
// In production, consider using nlohmann/json or rapidjson

namespace futon::core::auth {

    UidWhitelist::UidWhitelist(const UidWhitelistConfig &config)
            : config_(config) {
    }

    UidWhitelist::~UidWhitelist() {
        shutdown();
    }

    int64_t UidWhitelist::current_time_ms() {
        auto now = std::chrono::steady_clock::now();
        return std::chrono::duration_cast<std::chrono::milliseconds>(
                now.time_since_epoch()).count();
    }

    bool UidWhitelist::initialize() {
        std::lock_guard<std::mutex> lock(mutex_);

        FUTON_LOGI("Initializing UID whitelist from %s", config_.whitelist_path.c_str());

        // Ensure directory exists
        std::filesystem::path whitelist_path(config_.whitelist_path);
        std::filesystem::path dir = whitelist_path.parent_path();

        if (!dir.empty() && !std::filesystem::exists(dir)) {
            try {
                std::filesystem::create_directories(dir);
            } catch (const std::exception &e) {
                FUTON_LOGE("Failed to create directory %s: %s", dir.c_str(), e.what());
                return false;
            }
        }

        // Load existing whitelist
        if (std::filesystem::exists(config_.whitelist_path)) {
            if (!load_whitelist()) {
                FUTON_LOGW("Failed to load whitelist, starting fresh");
            }
        }

        FUTON_LOGI("UID whitelist initialized: %zu authorized apps", authorized_apps_.size());
        return true;
    }

    void UidWhitelist::shutdown() {
        std::lock_guard<std::mutex> lock(mutex_);

        if (!authorized_apps_.empty()) {
            save_whitelist();
        }

        authorized_apps_.clear();
        pending_requests_.clear();
    }

    AuthorizationStatus UidWhitelist::check_authorization(uid_t uid) {
        std::lock_guard<std::mutex> lock(mutex_);

        // Check authorized apps
        auto it = authorized_apps_.find(uid);
        if (it != authorized_apps_.end()) {
            return it->second.status;
        }

        // Check pending requests
        auto pending_it = pending_requests_.find(uid);
        if (pending_it != pending_requests_.end()) {
            // Check if expired
            int64_t now = current_time_ms();
            if (now - pending_it->second.requested_at_ms > config_.pending_timeout_ms) {
                pending_requests_.erase(pending_it);
                return AuthorizationStatus::UNKNOWN;
            }
            return AuthorizationStatus::PENDING;
        }

        return AuthorizationStatus::UNKNOWN;
    }

    bool UidWhitelist::is_authorized(uid_t uid) {
        std::lock_guard<std::mutex> lock(mutex_);

        auto it = authorized_apps_.find(uid);
        if (it == authorized_apps_.end()) {
            return false;
        }

        if (it->second.status != AuthorizationStatus::AUTHORIZED) {
            return false;
        }

        // Update access stats
        it->second.last_access_ms = current_time_ms();
        it->second.access_count++;

        return true;
    }

    bool UidWhitelist::request_authorization(uid_t uid, pid_t pid,
                                             const std::string &package_name,
                                             const std::string &reason) {
        std::lock_guard<std::mutex> lock(mutex_);

        // Already authorized?
        auto auth_it = authorized_apps_.find(uid);
        if (auth_it != authorized_apps_.end()) {
            if (auth_it->second.status == AuthorizationStatus::AUTHORIZED) {
                return false;  // Already authorized
            }
            if (auth_it->second.status == AuthorizationStatus::DENIED) {
                return false;  // Previously denied
            }
        }

        // Already pending?
        if (pending_requests_.find(uid) != pending_requests_.end()) {
            return false;  // Already pending
        }

        // Check max pending requests
        if (pending_requests_.size() >= config_.max_pending_requests) {
            FUTON_LOGW("Max pending authorization requests reached");
            return false;
        }

        // Create pending request
        PendingAuthorization pending;
        pending.uid = uid;
        pending.pid = pid;
        pending.package_name = package_name.empty() ? get_package_name(uid, pid) : package_name;
        pending.requested_at_ms = current_time_ms();
        pending.request_reason = reason;

        pending_requests_[uid] = pending;

        FUTON_LOGI("Authorization requested for UID %d (%s)", uid, pending.package_name.c_str());

        // Notify callback
        if (auth_request_callback_) {
            auth_request_callback_(pending);
        }

        return true;
    }

    void UidWhitelist::authorize(uid_t uid, const std::string &label) {
        std::lock_guard<std::mutex> lock(mutex_);

        int64_t now = current_time_ms();

        // Check if there's a pending request
        auto pending_it = pending_requests_.find(uid);
        std::string package_name;

        if (pending_it != pending_requests_.end()) {
            package_name = pending_it->second.package_name;
            pending_requests_.erase(pending_it);
        }

        // Create or update authorized app entry
        AuthorizedApp &app = authorized_apps_[uid];
        app.uid = uid;
        app.package_name = package_name;
        app.label = label.empty() ? package_name : label;

        if (app.first_seen_ms == 0) {
            app.first_seen_ms = now;
        }
        app.authorized_at_ms = now;
        app.last_access_ms = now;
        app.status = AuthorizationStatus::AUTHORIZED;

        FUTON_LOGI("UID %d authorized: %s", uid, app.label.c_str());

        // Save whitelist
        save_whitelist();

        // Notify callback
        if (auth_decision_callback_) {
            auth_decision_callback_(uid, true);
        }
    }

    void UidWhitelist::deny(uid_t uid) {
        std::lock_guard<std::mutex> lock(mutex_);

        // Remove from pending
        pending_requests_.erase(uid);

        // Record denial
        AuthorizedApp &app = authorized_apps_[uid];
        app.uid = uid;
        app.status = AuthorizationStatus::DENIED;
        app.authorized_at_ms = current_time_ms();

        FUTON_LOGI("UID %d denied", uid);

        // Save whitelist
        save_whitelist();

        // Notify callback
        if (auth_decision_callback_) {
            auth_decision_callback_(uid, false);
        }
    }

    void UidWhitelist::revoke(uid_t uid) {
        std::lock_guard<std::mutex> lock(mutex_);

        auto it = authorized_apps_.find(uid);
        if (it != authorized_apps_.end()) {
            it->second.status = AuthorizationStatus::REVOKED;
            FUTON_LOGI("UID %d authorization revoked", uid);
            save_whitelist();
        }
    }

    bool UidWhitelist::register_public_key(uid_t uid, const std::vector<uint8_t> &public_key) {
        std::lock_guard<std::mutex> lock(mutex_);

        auto it = authorized_apps_.find(uid);
        if (it == authorized_apps_.end()) {
            FUTON_LOGW("Cannot register public key for unauthorized UID %d", uid);
            return false;
        }

        if (it->second.status != AuthorizationStatus::AUTHORIZED) {
            FUTON_LOGW("Cannot register public key for non-authorized UID %d", uid);
            return false;
        }

        // TOFU: Only accept first key registration
        if (!it->second.public_key.empty()) {
            // Verify it matches existing key
            if (!CryptoUtils::constant_time_compare(it->second.public_key, public_key)) {
                FUTON_LOGW("Public key mismatch for UID %d - possible attack!", uid);
                return false;
            }
            return true;  // Same key, OK
        }

        // First time registration
        it->second.public_key = public_key;
        it->second.key_registered_at_ms = current_time_ms();

        FUTON_LOGI("Public key registered for UID %d", uid);
        save_whitelist();

        return true;
    }

    std::optional<std::vector<uint8_t>> UidWhitelist::get_public_key(uid_t uid) const {
        std::lock_guard<std::mutex> lock(mutex_);

        auto it = authorized_apps_.find(uid);
        if (it == authorized_apps_.end() || it->second.public_key.empty()) {
            return std::nullopt;
        }

        return it->second.public_key;
    }

    bool UidWhitelist::has_public_key(uid_t uid) const {
        std::lock_guard<std::mutex> lock(mutex_);

        auto it = authorized_apps_.find(uid);
        return it != authorized_apps_.end() && !it->second.public_key.empty();
    }

    std::vector<AuthorizedApp> UidWhitelist::get_authorized_apps() const {
        std::lock_guard<std::mutex> lock(mutex_);

        std::vector<AuthorizedApp> result;
        for (const auto &[uid, app]: authorized_apps_) {
            if (app.status == AuthorizationStatus::AUTHORIZED) {
                result.push_back(app);
            }
        }
        return result;
    }

    std::vector<PendingAuthorization> UidWhitelist::get_pending_requests() const {
        std::lock_guard<std::mutex> lock(mutex_);

        std::vector<PendingAuthorization> result;
        for (const auto &[uid, pending]: pending_requests_) {
            result.push_back(pending);
        }
        return result;
    }

    std::optional<AuthorizedApp> UidWhitelist::get_app_info(uid_t uid) const {
        std::lock_guard<std::mutex> lock(mutex_);

        auto it = authorized_apps_.find(uid);
        if (it == authorized_apps_.end()) {
            return std::nullopt;
        }
        return it->second;
    }

    void UidWhitelist::cleanup_expired() {
        std::lock_guard<std::mutex> lock(mutex_);

        int64_t now = current_time_ms();

        for (auto it = pending_requests_.begin(); it != pending_requests_.end();) {
            if (now - it->second.requested_at_ms > config_.pending_timeout_ms) {
                FUTON_LOGD("Removing expired pending request for UID %d", it->first);
                it = pending_requests_.erase(it);
            } else {
                ++it;
            }
        }
    }

    void UidWhitelist::set_authorization_request_callback(AuthorizationRequestCallback callback) {
        std::lock_guard<std::mutex> lock(mutex_);
        auth_request_callback_ = std::move(callback);
    }

    void UidWhitelist::set_authorization_decision_callback(AuthorizationDecisionCallback callback) {
        std::lock_guard<std::mutex> lock(mutex_);
        auth_decision_callback_ = std::move(callback);
    }

    UidWhitelist::Stats UidWhitelist::get_stats() const {
        std::lock_guard<std::mutex> lock(mutex_);

        Stats stats{};
        stats.pending_count = pending_requests_.size();

        for (const auto &[uid, app]: authorized_apps_) {
            switch (app.status) {
                case AuthorizationStatus::AUTHORIZED:
                    stats.authorized_count++;
                    stats.total_access_count += app.access_count;
                    break;
                case AuthorizationStatus::DENIED:
                case AuthorizationStatus::REVOKED:
                    stats.denied_count++;
                    break;
                default:
                    break;
            }
        }

        return stats;
    }

    std::string UidWhitelist::get_package_name(uid_t uid, pid_t pid) {
        // Try to get package name from /proc/[pid]/cmdline
        if (pid > 0) {
            std::string cmdline_path = "/proc/" + std::to_string(pid) + "/cmdline";
            std::ifstream cmdline(cmdline_path);
            if (cmdline.is_open()) {
                std::string name;
                std::getline(cmdline, name, '\0');

                // Remove process suffix (e.g., ":service")
                size_t colon = name.find(':');
                if (colon != std::string::npos) {
                    name = name.substr(0, colon);
                }

                if (!name.empty()) {
                    return name;
                }
            }
        }

        // Fallback: return UID as string
        return "uid:" + std::to_string(uid);
    }

    bool UidWhitelist::load_whitelist() {
        // Format: UID|STATUS|PACKAGE_NAME|LABEL|FIRST_SEEN|AUTHORIZED_AT|LAST_ACCESS|ACCESS_COUNT|PUBKEY_HEX

        std::ifstream file(config_.whitelist_path);
        if (!file.is_open()) {
            return false;
        }

        std::string line;
        while (std::getline(file, line)) {
            if (line.empty() || line[0] == '#') continue;

            std::istringstream iss(line);
            std::string token;
            std::vector<std::string> tokens;

            while (std::getline(iss, token, '|')) {
                tokens.push_back(token);
            }

            if (tokens.size() < 8) continue;

            try {
                AuthorizedApp app;
                app.uid = static_cast<uid_t>(std::stoul(tokens[0]));
                app.status = static_cast<AuthorizationStatus>(std::stoi(tokens[1]));
                app.package_name = tokens[2];
                app.label = tokens[3];
                app.first_seen_ms = std::stoll(tokens[4]);
                app.authorized_at_ms = std::stoll(tokens[5]);
                app.last_access_ms = std::stoll(tokens[6]);
                app.access_count = std::stoi(tokens[7]);

                if (tokens.size() > 8 && !tokens[8].empty()) {
                    auto key = CryptoUtils::from_hex(tokens[8]);
                    if (key.has_value()) {
                        app.public_key = std::move(key.value());
                    }
                }

                authorized_apps_[app.uid] = std::move(app);
            } catch (const std::exception &e) {
                FUTON_LOGW("Failed to parse whitelist line: %s", e.what());
            }
        }

        return true;
    }

    bool UidWhitelist::save_whitelist() {
        std::ofstream file(config_.whitelist_path);
        if (!file.is_open()) {
            FUTON_LOGE("Failed to open whitelist file for writing: %s",
                       config_.whitelist_path.c_str());
            return false;
        }

        // File header with format version marker
        file << "# Futon Authorized Apps (v" << std::hex << 0x464C << std::dec << ")\n";
        file << "# Format: UID|STATUS|PACKAGE|LABEL|FIRST_SEEN|AUTH_AT|LAST_ACCESS|COUNT|PUBKEY\n";

        for (const auto &[uid, app]: authorized_apps_) {
            file << app.uid << "|"
                 << static_cast<int>(app.status) << "|"
                 << app.package_name << "|"
                 << app.label << "|"
                 << app.first_seen_ms << "|"
                 << app.authorized_at_ms << "|"
                 << app.last_access_ms << "|"
                 << app.access_count << "|"
                 << (app.public_key.empty() ? "" : CryptoUtils::to_hex(app.public_key))
                 << "\n";
        }

        file.close();

        // Set restrictive permissions
        chmod(config_.whitelist_path.c_str(), 0600);

        return true;
    }

} // namespace futon::core::auth
