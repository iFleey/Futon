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

#include "session_manager.h"
#include "crypto_utils.h"
#include "core/error.h"

#include <algorithm>
#include <fstream>

namespace futon::core::auth {

    SessionManager::SessionManager() = default;

    SessionManager::~SessionManager() {
        secure_clear();
    }

    bool SessionManager::initialize() {
        std::lock_guard<std::mutex> lock(mutex_);

        if (initialized_) {
            return true;
        }

        session_key_ = derive_session_key();
        if (session_key_.empty()) {
            FUTON_LOGE("Failed to derive session key");
            return false;
        }

        initialized_ = true;
        FUTON_LOGI("SessionManager initialized with encrypted challenge storage");
        return true;
    }

    std::vector<uint8_t> SessionManager::derive_session_key() {
        std::vector<uint8_t> entropy;

        constexpr uint32_t kDerivationSalt = 0x464C6579;
        for (int i = 0; i < 4; ++i) {
            entropy.push_back(static_cast<uint8_t>((kDerivationSalt >> (i * 8)) & 0xFF));
        }

        // Gather entropy from multiple sources
        auto random = CryptoUtils::generate_random_bytes(32);
        entropy.insert(entropy.end(), random.begin(), random.end());

        // Add boot_id for session uniqueness
        std::ifstream boot_id("/proc/sys/kernel/random/boot_id");
        if (boot_id.is_open()) {
            std::string s;
            std::getline(boot_id, s);
            entropy.insert(entropy.end(), s.begin(), s.end());
        }

        // Add timestamp
        auto now = std::chrono::steady_clock::now().time_since_epoch().count();
        for (int i = 0; i < 8; ++i) {
            entropy.push_back(static_cast<uint8_t>((now >> (i * 8)) & 0xFF));
        }

        // Add process info
        pid_t pid = getpid();
        entropy.push_back(static_cast<uint8_t>(pid & 0xFF));
        entropy.push_back(static_cast<uint8_t>((pid >> 8) & 0xFF));

        return CryptoUtils::sha256(entropy);
    }

    std::vector<uint8_t> SessionManager::encrypt_challenge(
            const std::vector<uint8_t> &challenge,
            std::vector<uint8_t> &out_nonce) const {

        // Generate random nonce
        out_nonce = CryptoUtils::generate_random_bytes(16);
        if (out_nonce.empty()) {
            return {};
        }

        // XOR-based encryption with key stream derived from session_key + nonce
        std::vector<uint8_t> key_material = session_key_;
        key_material.insert(key_material.end(), out_nonce.begin(), out_nonce.end());
        auto key_stream = CryptoUtils::sha256(key_material);

        std::vector<uint8_t> encrypted(challenge.size());
        for (size_t i = 0; i < challenge.size(); ++i) {
            encrypted[i] = challenge[i] ^ key_stream[i % key_stream.size()];
        }

        return encrypted;
    }

    std::vector<uint8_t> SessionManager::decrypt_challenge(
            const std::vector<uint8_t> &encrypted,
            const std::vector<uint8_t> &nonce) const {

        // Derive same key stream
        std::vector<uint8_t> key_material = session_key_;
        key_material.insert(key_material.end(), nonce.begin(), nonce.end());
        auto key_stream = CryptoUtils::sha256(key_material);

        std::vector<uint8_t> decrypted(encrypted.size());
        for (size_t i = 0; i < encrypted.size(); ++i) {
            decrypted[i] = encrypted[i] ^ key_stream[i % key_stream.size()];
        }

        return decrypted;
    }

    void SessionManager::secure_clear() {
        std::lock_guard<std::mutex> lock(mutex_);

        // Securely clear session key
        if (!session_key_.empty()) {
            std::fill(session_key_.begin(), session_key_.end(), 0);
            session_key_.clear();
        }

        // Clear all pending challenges
        for (auto &[uid, pending]: pending_challenges_) {
            std::fill(pending.encrypted_challenge.begin(), pending.encrypted_challenge.end(), 0);
            std::fill(pending.challenge_nonce.begin(), pending.challenge_nonce.end(), 0);
        }
        pending_challenges_.clear();

        active_session_.reset();
        initialized_ = false;
    }

    int64_t SessionManager::current_time_ms() {
        auto now = std::chrono::steady_clock::now();
        return std::chrono::duration_cast<std::chrono::milliseconds>(
                now.time_since_epoch()
        ).count();
    }

    std::vector<uint8_t> SessionManager::create_challenge(uid_t client_uid) {
        std::lock_guard<std::mutex> lock(mutex_);

        if (!initialized_) {
            FUTON_LOGE("SessionManager not initialized");
            return {};
        }

        // Generate new challenge
        auto challenge = CryptoUtils::generate_challenge();
        if (challenge.empty()) {
            FUTON_LOGE("Failed to generate challenge");
            return {};
        }

        // Encrypt challenge before storing
        std::vector<uint8_t> nonce;
        auto encrypted = encrypt_challenge(challenge, nonce);
        if (encrypted.empty()) {
            FUTON_LOGE("Failed to encrypt challenge");
            return {};
        }

        // Store encrypted challenge
        PendingChallenge pending;
        pending.encrypted_challenge = std::move(encrypted);
        pending.challenge_nonce = std::move(nonce);
        pending.created_at_ms = current_time_ms();
        pending.client_uid = client_uid;

        pending_challenges_[client_uid] = std::move(pending);

        FUTON_LOGD("Created encrypted challenge for uid %d, size=%zu", client_uid,
                   challenge.size());
        return challenge;
    }

    bool SessionManager::validate_challenge(
            const std::vector<uint8_t> &challenge,
            uid_t client_uid
    ) {
        std::lock_guard<std::mutex> lock(mutex_);

        if (!initialized_) {
            FUTON_LOGE("SessionManager not initialized");
            return false;
        }

        auto it = pending_challenges_.find(client_uid);
        if (it == pending_challenges_.end()) {
            FUTON_LOGW("No pending challenge for uid %d", client_uid);
            return false;
        }

        auto &pending = it->second;

        // Check expiration
        if (pending.is_expired(current_time_ms())) {
            FUTON_LOGW("Challenge expired for uid %d", client_uid);
            // Securely clear before erasing
            std::fill(pending.encrypted_challenge.begin(), pending.encrypted_challenge.end(), 0);
            std::fill(pending.challenge_nonce.begin(), pending.challenge_nonce.end(), 0);
            pending_challenges_.erase(it);
            return false;
        }

        // Decrypt stored challenge
        auto decrypted = decrypt_challenge(pending.encrypted_challenge, pending.challenge_nonce);
        if (decrypted.empty()) {
            FUTON_LOGE("Failed to decrypt challenge for uid %d", client_uid);
            return false;
        }

        // Constant-time comparison
        bool match = CryptoUtils::constant_time_compare(decrypted, challenge);

        // Securely clear decrypted challenge
        std::fill(decrypted.begin(), decrypted.end(), 0);

        if (!match) {
            FUTON_LOGW("Challenge mismatch for uid %d", client_uid);
            return false;
        }

        return true;
    }

    std::optional<std::vector<uint8_t>>
    SessionManager::get_pending_challenge(uid_t client_uid) const {
        std::lock_guard<std::mutex> lock(mutex_);

        if (!initialized_) {
            FUTON_LOGE("SessionManager not initialized");
            return std::nullopt;
        }

        auto it = pending_challenges_.find(client_uid);
        if (it == pending_challenges_.end()) {
            return std::nullopt;
        }

        const auto &pending = it->second;

        // Check expiration
        if (pending.is_expired(current_time_ms())) {
            return std::nullopt;
        }

        // Decrypt and return challenge
        return decrypt_challenge(pending.encrypted_challenge, pending.challenge_nonce);
    }

    void SessionManager::clear_challenge(uid_t client_uid) {
        std::lock_guard<std::mutex> lock(mutex_);

        auto it = pending_challenges_.find(client_uid);
        if (it != pending_challenges_.end()) {
            // Securely clear before erasing
            std::fill(it->second.encrypted_challenge.begin(), it->second.encrypted_challenge.end(),
                      0);
            std::fill(it->second.challenge_nonce.begin(), it->second.challenge_nonce.end(), 0);
            pending_challenges_.erase(it);
        }
    }

    void SessionManager::consume_challenge(uid_t client_uid) {
        clear_challenge(client_uid);
    }

    bool SessionManager::create_session(const std::string &instance_id, uid_t client_uid) {
        std::lock_guard<std::mutex> lock(mutex_);

        int64_t now = current_time_ms();

        // Check if there's an existing active session
        if (active_session_.has_value()) {
            const auto &existing = active_session_.value();

            // If same instance_id, just update
            if (existing.instance_id == instance_id) {
                FUTON_LOGI("Refreshing existing session for instance %s", instance_id.c_str());
                active_session_->last_activity_ms = now;
                return true;
            }

            // If existing session is expired, allow new session
            if (existing.is_expired(now)) {
                FUTON_LOGI("Replacing expired session");
            } else {
                // Active session exists from different instance
                FUTON_LOGW("Session conflict: active session from instance %s, "
                           "new request from %s",
                           existing.instance_id.c_str(), instance_id.c_str());
                return false;
            }
        }

        // Create new session
        Session session;
        session.instance_id = instance_id;
        session.created_at_ms = now;
        session.last_activity_ms = now;
        session.client_uid = client_uid;
        session.authenticated = true;

        active_session_ = std::move(session);

        FUTON_LOGI("Created session for instance %s, uid %d", instance_id.c_str(), client_uid);
        return true;
    }

    bool SessionManager::validate_session(const std::string &instance_id, uid_t client_uid) {
        std::lock_guard<std::mutex> lock(mutex_);

        if (!active_session_.has_value()) {
            return false;
        }

        const auto &session = active_session_.value();

        // Check instance_id match
        if (session.instance_id != instance_id) {
            return false;
        }

        // Check UID match
        if (session.client_uid != client_uid) {
            FUTON_LOGW("UID mismatch: session uid=%d, caller uid=%d",
                       session.client_uid, client_uid);
            return false;
        }

        // Check expiration
        if (session.is_expired(current_time_ms())) {
            FUTON_LOGI("Session expired for instance %s", instance_id.c_str());
            active_session_.reset();
            return false;
        }

        return session.authenticated;
    }

    void SessionManager::update_activity(const std::string &instance_id) {
        std::lock_guard<std::mutex> lock(mutex_);

        if (active_session_.has_value() && active_session_->instance_id == instance_id) {
            active_session_->last_activity_ms = current_time_ms();
        }
    }

    void SessionManager::invalidate_session(const std::string &instance_id) {
        std::lock_guard<std::mutex> lock(mutex_);

        if (active_session_.has_value() && active_session_->instance_id == instance_id) {
            FUTON_LOGI("Invalidating session for instance %s", instance_id.c_str());
            active_session_.reset();
        }
    }

    void SessionManager::invalidate_all_sessions() {
        std::lock_guard<std::mutex> lock(mutex_);
        active_session_.reset();
        pending_challenges_.clear();
        FUTON_LOGI("All sessions invalidated");
    }

    SessionStatus SessionManager::check_session(const std::string &instance_id, uid_t client_uid) {
        std::lock_guard<std::mutex> lock(mutex_);

        SessionStatus status;
        status.has_active_session = false;
        status.is_own_session = false;
        status.remaining_timeout_ms = 0;

        if (!active_session_.has_value()) {
            return status;
        }

        const auto &session = active_session_.value();
        int64_t now = current_time_ms();

        // Check if session is expired
        if (session.is_expired(now)) {
            active_session_.reset();
            return status;
        }

        status.has_active_session = true;
        status.is_own_session = (session.instance_id == instance_id &&
                                 session.client_uid == client_uid);
        status.remaining_timeout_ms = session.remaining_timeout_ms(now);

        return status;
    }

    std::optional<Session> SessionManager::get_active_session() const {
        std::lock_guard<std::mutex> lock(mutex_);

        if (!active_session_.has_value()) {
            return std::nullopt;
        }

        // Check expiration
        if (active_session_->is_expired(current_time_ms())) {
            return std::nullopt;
        }

        return active_session_;
    }

    bool SessionManager::has_active_session() const {
        std::lock_guard<std::mutex> lock(mutex_);

        if (!active_session_.has_value()) {
            return false;
        }

        return !active_session_->is_expired(current_time_ms());
    }

    void SessionManager::cleanup_expired() {
        std::lock_guard<std::mutex> lock(mutex_);

        int64_t now = current_time_ms();

        // Cleanup expired session
        if (active_session_.has_value() && active_session_->is_expired(now)) {
            FUTON_LOGD("Cleaning up expired session");
            active_session_.reset();
        }

        // Cleanup expired challenges (with secure clearing)
        for (auto it = pending_challenges_.begin(); it != pending_challenges_.end();) {
            if (it->second.is_expired(now)) {
                FUTON_LOGD("Cleaning up expired challenge for uid %d", it->first);
                // Securely clear before erasing
                std::fill(it->second.encrypted_challenge.begin(),
                          it->second.encrypted_challenge.end(), 0);
                std::fill(it->second.challenge_nonce.begin(), it->second.challenge_nonce.end(), 0);
                it = pending_challenges_.erase(it);
            } else {
                ++it;
            }
        }
    }

} // namespace futon::core::auth
