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

#include "futon_daemon_impl.h"
#include "core/error.h"
#include "core/system_status.h"
#include "core/auth/auth_manager.h"
#include "core/auth/key_whitelist.h"
#include "core/crypto/double_ratchet.h"
#include "core/crypto/stream_cipher.h"
#include "vision/capture/vision_pipeline.h"
#include "inference/ppocrv5/ppocrv5.h"
#include "hotpath/hotpath_router.h"
#include "debug/debug_stream.h"
#include "input/input_injector.h"
#include "input/input_device_discovery.h"
#include "input/shell_executor.h"

#include <thread>
#include <android/hardware_buffer.h>
#include <chrono>
#include <algorithm>
#include <random>
#include <unistd.h>
#include <sstream>

using namespace aidl::me::fleey::futon;
using namespace futon::core;

namespace futon::ipc {

    IFutonDaemonImpl::IFutonDaemonImpl() {
        FUTON_LOGI("IFutonDaemonImpl created");

        // Initialize system status detector
        system_status_detector_ = std::make_unique<core::SystemStatusDetector>();

        config_.captureWidth = 640;
        config_.captureHeight = 640;
        config_.targetFps = 60;
        config_.modelPath = "";
        config_.enableDebugStream = false;
        config_.debugStreamPort = 33212;
    }

    IFutonDaemonImpl::~IFutonDaemonImpl() {
        FUTON_LOGI("IFutonDaemonImpl destroying");
        if (running_.load()) { stop_internal(); }

        std::lock_guard<std::mutex> lock(callbacks_mutex_);
        callbacks_.clear();
        buffer_callbacks_.clear();
        FUTON_LOGI("IFutonDaemonImpl destroyed");
    }

    bool IFutonDaemonImpl::initialize(std::shared_ptr<core::auth::AuthManager> auth_manager) {
        auth_manager_ = std::move(auth_manager);
        FUTON_LOGI("IFutonDaemonImpl initialized with auth manager");
        return true;
    }

    bool IFutonDaemonImpl::check_authenticated(const char *method_name) {
        if (!auth_manager_ || !auth_manager_->is_authentication_required()) {
            return true;
        }

        uid_t caller_uid = getCallingUid();
        pid_t caller_pid = getCallingPid();

        // Check caller is allowed (rate limiting + caller verification)
        auto caller_result = auth_manager_->check_caller_allowed(caller_uid, caller_pid);
        if (!caller_result.is_ok()) {
            FUTON_LOGW("%s: Caller check failed for uid %d pid %d: %s",
                       method_name, caller_uid, caller_pid, caller_result.message.c_str());
            auth_manager_->security_audit().log_api_denied(caller_uid, caller_pid, method_name);
            return false;
        }

        // Get active session and validate by UID
        auto session_opt = auth_manager_->session_manager().get_active_session();
        if (!session_opt.has_value()) {
            FUTON_LOGW("%s: No active session", method_name);
            auth_manager_->security_audit().log_api_denied(caller_uid, caller_pid, method_name);
            return false;
        }

        const auto &session = session_opt.value();
        if (session.client_uid != caller_uid) {
            FUTON_LOGW("%s: Session UID mismatch (expected %d, got %d)",
                       method_name, session.client_uid, caller_uid);
            auth_manager_->security_audit().log_security_violation(
                    core::auth::SecurityEventType::UID_MISMATCH, caller_uid, caller_pid,
                    "Session UID mismatch in " + std::string(method_name));
            return false;
        }

        if (!auth_manager_->validate_session(session.instance_id, caller_uid)) {
            FUTON_LOGW("%s: Session validation failed for uid %d", method_name, caller_uid);
            auth_manager_->security_audit().log_api_denied(caller_uid, caller_pid, method_name);
            return false;
        }

        auth_manager_->update_session_activity(session.instance_id);
        return true;
    }

// ========== Version & Capability ==========

    ndk::ScopedAStatus IFutonDaemonImpl::getVersion(int32_t *_aidl_return) {
        *_aidl_return = DAEMON_PROTOCOL_VERSION;
        return ndk::ScopedAStatus::ok();
    }

    ndk::ScopedAStatus IFutonDaemonImpl::getCapabilities(int32_t *_aidl_return) {
        int32_t caps = DaemonCapability::SCREEN_CAPTURE |
                       DaemonCapability::INPUT_INJECTION |
                       DaemonCapability::OBJECT_DETECTION |
                       DaemonCapability::OCR |
                       DaemonCapability::HOT_PATH;

        auto debug = debug_stream_.lock();
        if (debug) {
            caps |= static_cast<int32_t>(DaemonCapability::DEBUG_STREAM);
        }

        *_aidl_return = caps;
        return ndk::ScopedAStatus::ok();
    }

    ndk::ScopedAStatus IFutonDaemonImpl::getSystemStatus(
            aidl::me::fleey::futon::SystemStatus *_aidl_return
    ) {
        FUTON_LOGD("getSystemStatus() called");

        if (!system_status_detector_) {
            FUTON_LOGE("System status detector not initialized");
            return ndk::ScopedAStatus::fromServiceSpecificError(-1);
        }

        // Detect current system status (always fresh, not cached)
        auto status = system_status_detector_->detect();

        // Map C++ SystemStatus to AIDL SystemStatus
        _aidl_return->rootAvailable = status.root_available;
        _aidl_return->rootType = status.root_type;
        _aidl_return->rootVersion = status.root_version;

        _aidl_return->selinuxMode = static_cast<int32_t>(status.selinux_mode);
        _aidl_return->inputAccessAllowed = status.input_access_allowed;

        _aidl_return->canAccessDevInput = status.can_access_dev_input;
        _aidl_return->touchDevicePath = status.touch_device_path;
        _aidl_return->maxTouchPoints = status.max_touch_points;
        _aidl_return->inputError = status.input_error;

        _aidl_return->daemonPid = status.daemon_pid;
        _aidl_return->uptimeMs = status.uptime_ms;
        _aidl_return->daemonVersion = status.daemon_version;

        return ndk::ScopedAStatus::ok();
    }

// ========== Authentication ==========

    ndk::ScopedAStatus IFutonDaemonImpl::getChallenge(std::vector<uint8_t> *_aidl_return) {
        FUTON_LOGI("getChallenge() called");

        if (!auth_manager_) {
            FUTON_LOGE("Auth manager not initialized");
            return ndk::ScopedAStatus::fromServiceSpecificErrorWithMessage(
                    -1, "Auth manager not initialized");
        }

        // Always try to reload public key - it may have been regenerated by the app
        // This handles the case where the app regenerates its keypair after a signing failure
        if (auth_manager_->reload_public_key()) {
            FUTON_LOGI("Public key loaded/reloaded successfully");
        }

        // Check if public key is loaded
        if (!auth_manager_->has_public_key()) {
            FUTON_LOGE("Public key not loaded - check /data/adb/futon/.auth_pubkey");
            return ndk::ScopedAStatus::fromServiceSpecificErrorWithMessage(
                    -3, "Public key not loaded. Generate keypair in app settings first.");
        }

        uid_t caller_uid = getCallingUid();
        pid_t caller_pid = getCallingPid();

        // Check rate limiting before generating challenge
        auto caller_check = auth_manager_->check_caller_allowed(caller_uid, caller_pid);
        if (!caller_check.is_ok()) {
            FUTON_LOGW("getChallenge: Caller check failed for uid %d pid %d: %s",
                       caller_uid, caller_pid, caller_check.message.c_str());
            return ndk::ScopedAStatus::fromServiceSpecificErrorWithMessage(
                    static_cast<int32_t>(caller_check.error), caller_check.message.c_str());
        }

        auto challenge = auth_manager_->get_challenge(caller_uid);

        if (challenge.empty()) {
            FUTON_LOGE("Failed to generate challenge");
            return ndk::ScopedAStatus::fromServiceSpecificErrorWithMessage(
                    -2, "Failed to generate challenge");
        }

        *_aidl_return = std::move(challenge);
        return ndk::ScopedAStatus::ok();
    }

    ndk::ScopedAStatus IFutonDaemonImpl::authenticate(
            const std::vector<uint8_t> &signature,
            const std::string &instanceId,
            aidl::me::fleey::futon::AuthenticateResult *_aidl_return
    ) {
        FUTON_LOGI("authenticate() called: instance=%s, sig_size=%zu",
                   instanceId.c_str(), signature.size());

        if (!auth_manager_) {
            FUTON_LOGE("Auth manager not initialized");
            _aidl_return->success = false;
            _aidl_return->requiresAttestation = false;
            _aidl_return->keyId = std::nullopt;
            return ndk::ScopedAStatus::fromServiceSpecificErrorWithMessage(
                    -1, "Auth manager not initialized");
        }

        uid_t caller_uid = getCallingUid();
        pid_t caller_pid = getCallingPid();

        // Check caller is allowed before authentication (rate limiting + caller verification)
        auto caller_check = auth_manager_->check_caller_allowed(caller_uid, caller_pid);
        if (!caller_check.is_ok()) {
            FUTON_LOGE("Caller check failed: %s", caller_check.message.c_str());
            _aidl_return->success = false;
            _aidl_return->requiresAttestation = false;
            _aidl_return->keyId = std::nullopt;
            return ndk::ScopedAStatus::fromServiceSpecificErrorWithMessage(
                    static_cast<int32_t>(caller_check.error), caller_check.message.c_str());
        }

        auto result = auth_manager_->authenticate(signature, instanceId, caller_uid, caller_pid);

        if (!result.is_ok()) {
            FUTON_LOGE("Authentication failed: %s (error=%d)",
                       result.message.c_str(), static_cast<int>(result.error));
            _aidl_return->success = false;
            _aidl_return->requiresAttestation = false;
            _aidl_return->keyId = std::nullopt;

            // Include detailed error message
            std::string error_msg = result.message.empty()
                                    ? core::auth::auth_error_to_string(result.error)
                                    : result.message;
            return ndk::ScopedAStatus::fromServiceSpecificErrorWithMessage(
                    static_cast<int32_t>(result.error), error_msg.c_str());
        }

        // Check if the key requires attestation verification
        bool requires_attestation = false;
        std::string key_id;

        auto &key_whitelist = core::auth::KeyWhitelist::instance();
        if (!result.key_id.empty()) {
            key_id = result.key_id;
            requires_attestation = key_whitelist.requires_attestation(key_id);

            if (requires_attestation) {
                FUTON_LOGI("Key %s requires attestation verification", key_id.c_str());
                std::lock_guard<std::mutex> lock(auth_mutex_);
                pending_attestation_key_id_ = key_id;
            }
        }

        {
            std::lock_guard<std::mutex> lock(auth_mutex_);
            current_instance_id_ = instanceId;
        }

        _aidl_return->success = true;
        _aidl_return->requiresAttestation = requires_attestation;
        _aidl_return->keyId = key_id.empty() ? std::nullopt : std::make_optional(key_id);

        FUTON_LOGI("Authentication successful for instance %s (attestation_required=%d)",
                   instanceId.c_str(), requires_attestation);
        return ndk::ScopedAStatus::ok();
    }

    ndk::ScopedAStatus IFutonDaemonImpl::verifyAttestation(
            const std::vector<std::vector<uint8_t>> &attestationChain
    ) {
        FUTON_LOGI("verifyAttestation() called: chain_size=%zu", attestationChain.size());

        if (attestationChain.empty()) {
            FUTON_LOGE("Empty attestation chain");
            return ndk::ScopedAStatus::fromServiceSpecificError(-1);
        }

        std::string key_id;
        {
            std::lock_guard<std::mutex> lock(auth_mutex_);
            key_id = pending_attestation_key_id_;
        }

        if (key_id.empty()) {
            FUTON_LOGE("No pending attestation verification");
            return ndk::ScopedAStatus::fromServiceSpecificError(-2);
        }

        // Concatenate chain into single buffer for verification
        std::vector<uint8_t> chain_buffer;
        for (const auto &cert: attestationChain) {
            chain_buffer.insert(chain_buffer.end(), cert.begin(), cert.end());
        }

        auto &key_whitelist = core::auth::KeyWhitelist::instance();
        auto result = key_whitelist.verify_key_attestation(key_id, chain_buffer);

        if (!result.success) {
            FUTON_LOGE("Attestation verification failed for key %s: %s",
                       key_id.c_str(), result.error_message.c_str());

            // Log security event
            if (auth_manager_) {
                uid_t caller_uid = getCallingUid();
                pid_t caller_pid = getCallingPid();
                auth_manager_->security_audit().log_security_violation(
                        core::auth::SecurityEventType::ATTESTATION_FAILED,
                        caller_uid, caller_pid,
                        "Attestation Mismatch: " + result.error_message
                );
            }

            return ndk::ScopedAStatus::fromServiceSpecificError(-3);
        }

        // Clear pending attestation
        {
            std::lock_guard<std::mutex> lock(auth_mutex_);
            pending_attestation_key_id_.clear();
        }

        FUTON_LOGI("Attestation verification successful for key %s", key_id.c_str());
        return ndk::ScopedAStatus::ok();
    }

    ndk::ScopedAStatus IFutonDaemonImpl::checkSession(
            const std::string &instanceId,
            SessionStatus *_aidl_return
    ) {
        if (!auth_manager_) {
            _aidl_return->hasActiveSession = false;
            _aidl_return->isOwnSession = false;
            _aidl_return->remainingTimeoutMs = 0;
            return ndk::ScopedAStatus::ok();
        }

        uid_t caller_uid = getCallingUid();
        auto status = auth_manager_->check_session(instanceId, caller_uid);

        _aidl_return->hasActiveSession = status.has_active_session;
        _aidl_return->isOwnSession = status.is_own_session;
        _aidl_return->remainingTimeoutMs = status.remaining_timeout_ms;

        return ndk::ScopedAStatus::ok();
    }

// ========== Encrypted Channel ==========

    ndk::ScopedAStatus IFutonDaemonImpl::initCryptoChannel(
            const std::vector<uint8_t> &clientDhPublic,
            aidl::me::fleey::futon::CryptoHandshake *_aidl_return
    ) {
        if (!check_authenticated("initCryptoChannel")) {
            _aidl_return->errorCode = -100;
            _aidl_return->errorMessage = "Not authenticated";
            return ndk::ScopedAStatus::ok();
        }

        FUTON_LOGI("initCryptoChannel: client_dh_size=%zu", clientDhPublic.size());

        if (clientDhPublic.size() != core::crypto::DH_PUBLIC_KEY_SIZE) {
            _aidl_return->errorCode = -1;
            _aidl_return->errorMessage = "Invalid DH public key size";
            return ndk::ScopedAStatus::ok();
        }

        std::lock_guard<std::mutex> lock(crypto_mutex_);

        // Generate our DH key pair
        auto our_keypair = core::crypto::DHKeyPair::generate();

        // Derive shared secret from session context
        std::vector<uint8_t> shared_secret;
        {
            std::lock_guard<std::mutex> auth_lock(auth_mutex_);
            if (auth_manager_) {
                auto session = auth_manager_->session_manager().get_active_session();
                if (session.has_value()) {
                    // Derive shared secret from session instance_id + client_uid + timestamp
                    // This creates a deterministic but unique secret per session
                    shared_secret.resize(32);

                    const auto &instance_id = session->instance_id;
                    uint64_t uid_bytes = static_cast<uint64_t>(session->client_uid);
                    uint64_t time_bytes = static_cast<uint64_t>(session->created_at_ms);

                    // Simple derivation: hash(instance_id || uid || timestamp)
                    // Using XOR-based mixing for performance (real impl would use HKDF)
                    size_t id_len = std::min(instance_id.size(), static_cast<size_t>(16));
                    for (size_t i = 0; i < id_len; ++i) {
                        shared_secret[i] = static_cast<uint8_t>(instance_id[i]);
                    }
                    for (size_t i = 0; i < 8; ++i) {
                        shared_secret[16 + i] = static_cast<uint8_t>((uid_bytes >> (i * 8)) & 0xFF);
                    }
                    for (size_t i = 0; i < 8; ++i) {
                        shared_secret[24 + i] = static_cast<uint8_t>((time_bytes >> (i * 8)) &
                                                                     0xFF);
                    }
                }
            }
        }

        if (shared_secret.empty()) {
            _aidl_return->errorCode = -2;
            _aidl_return->errorMessage = "No active session";
            return ndk::ScopedAStatus::ok();
        }

        // Initialize crypto channel as responder (Bob)
        crypto_channel_ = std::make_unique<core::crypto::DualChannelCrypto>();

        core::crypto::DHPublicKey client_pub;
        std::copy(clientDhPublic.begin(), clientDhPublic.end(), client_pub.begin());

        if (!crypto_channel_->init_responder(shared_secret, our_keypair)) {
            _aidl_return->errorCode = -3;
            _aidl_return->errorMessage = "Failed to initialize crypto channel";
            crypto_channel_.reset();
            return ndk::ScopedAStatus::ok();
        }

        // Generate session ID
        std::array<uint8_t, 16> session_bytes;
        std::random_device rd;
        std::generate(session_bytes.begin(), session_bytes.end(), std::ref(rd));

        char session_hex[33];
        for (size_t i = 0; i < 16; ++i) {
            snprintf(session_hex + i * 2, 3, "%02x", session_bytes[i]);
        }
        crypto_session_id_ = std::string(session_hex, 32);

        // Fill response
        _aidl_return->dhPublicKey.assign(our_keypair.public_key.begin(),
                                         our_keypair.public_key.end());
        _aidl_return->sessionId = crypto_session_id_;
        _aidl_return->keyGeneration = 1;
        _aidl_return->capabilities = 0x03;  // Double Ratchet + Stream Cipher
        _aidl_return->errorCode = 0;
        _aidl_return->errorMessage = std::nullopt;

        // Clear sensitive data
        std::fill(shared_secret.begin(), shared_secret.end(), 0);

        FUTON_LOGI("Crypto channel initialized, session: %s", crypto_session_id_.c_str());
        return ndk::ScopedAStatus::ok();
    }

    ndk::ScopedAStatus IFutonDaemonImpl::sendControlMessage(
            const std::vector<uint8_t> &encryptedMessage,
            std::vector<uint8_t> *_aidl_return
    ) {
        if (!check_authenticated("sendControlMessage")) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-100);
        }

        std::lock_guard<std::mutex> lock(crypto_mutex_);

        if (!crypto_channel_ || !crypto_channel_->is_initialized()) {
            FUTON_LOGW("sendControlMessage: crypto channel not initialized");
            return ndk::ScopedAStatus::fromServiceSpecificError(-1);
        }

        // Decrypt incoming control message
        auto decrypted = crypto_channel_->decrypt_control(encryptedMessage);

        if (!decrypted.has_value()) {
            FUTON_LOGE("sendControlMessage: decryption failed");
            return ndk::ScopedAStatus::fromServiceSpecificError(-2);
        }

        std::vector<uint8_t> response = {0x06};  // ACK

        // Encrypt response
        auto encrypted_response = crypto_channel_->encrypt_control(response);
        if (!encrypted_response.has_value()) {
            FUTON_LOGE("sendControlMessage: response encryption failed");
            return ndk::ScopedAStatus::fromServiceSpecificError(-3);
        }

        *_aidl_return = std::move(encrypted_response.value());
        return ndk::ScopedAStatus::ok();
    }

    ndk::ScopedAStatus IFutonDaemonImpl::sendDataMessage(
            const std::vector<uint8_t> &encryptedData,
            std::vector<uint8_t> *_aidl_return
    ) {
        if (!check_authenticated("sendDataMessage")) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-100);
        }

        std::lock_guard<std::mutex> lock(crypto_mutex_);

        if (!crypto_channel_ || !crypto_channel_->is_initialized()) {
            FUTON_LOGW("sendDataMessage: crypto channel not initialized");
            return ndk::ScopedAStatus::fromServiceSpecificError(-1);
        }

        // Decrypt incoming data
        auto decrypted = crypto_channel_->decrypt_data(
                encryptedData.data(), encryptedData.size());

        if (!decrypted.has_value()) {
            FUTON_LOGE("sendDataMessage: decryption failed");
            return ndk::ScopedAStatus::fromServiceSpecificError(-2);
        }

        std::vector<uint8_t> response;

        // Encrypt response
        auto encrypted_response = crypto_channel_->encrypt_data(
                response.data(), response.size());

        *_aidl_return = std::move(encrypted_response);
        return ndk::ScopedAStatus::ok();
    }

    ndk::ScopedAStatus IFutonDaemonImpl::rotateChannelKeys(
            aidl::me::fleey::futon::CryptoHandshake *_aidl_return
    ) {
        if (!check_authenticated("rotateChannelKeys")) {
            _aidl_return->errorCode = -100;
            _aidl_return->errorMessage = "Not authenticated";
            return ndk::ScopedAStatus::ok();
        }

        std::lock_guard<std::mutex> lock(crypto_mutex_);

        if (!crypto_channel_ || !crypto_channel_->is_initialized()) {
            _aidl_return->errorCode = -1;
            _aidl_return->errorMessage = "Crypto channel not initialized";
            return ndk::ScopedAStatus::ok();
        }

        if (!crypto_channel_->rotate_keys()) {
            _aidl_return->errorCode = -2;
            _aidl_return->errorMessage = "Key rotation failed";
            return ndk::ScopedAStatus::ok();
        }

        auto pub_key = crypto_channel_->get_public_key();
        auto stats = crypto_channel_->get_stats();

        _aidl_return->dhPublicKey.assign(pub_key.begin(), pub_key.end());
        _aidl_return->sessionId = crypto_session_id_;
        _aidl_return->keyGeneration = static_cast<int64_t>(stats.control_stats.ratchet_steps);
        _aidl_return->capabilities = 0x03;
        _aidl_return->errorCode = 0;
        _aidl_return->errorMessage = std::nullopt;

        FUTON_LOGI("Keys rotated, generation: %lld",
                   static_cast<long long>(_aidl_return->keyGeneration));
        return ndk::ScopedAStatus::ok();
    }

// ========== Callback Registration ==========

    ndk::ScopedAStatus IFutonDaemonImpl::registerStatusCallback(
            const std::shared_ptr<IStatusCallback> &callback
    ) {
        if (!check_authenticated("registerStatusCallback")) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-100);
        }

        if (!callback) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-1);
        }

        FUTON_LOGI("registerStatusCallback() called");
        std::lock_guard<std::mutex> lock(callbacks_mutex_);

        for (const auto &entry: callbacks_) {
            if (entry.callback == callback) {
                FUTON_LOGW("Callback already registered");
                return ndk::ScopedAStatus::ok();
            }
        }

        CallbackEntry entry;
        entry.callback = callback;
        entry.valid = true;
        callbacks_.push_back(std::move(entry));

        FUTON_LOGI("Callback registered, total=%zu", callbacks_.size());
        return ndk::ScopedAStatus::ok();
    }

    ndk::ScopedAStatus IFutonDaemonImpl::unregisterStatusCallback(
            const std::shared_ptr<IStatusCallback> &callback
    ) {
        if (!check_authenticated("unregisterStatusCallback")) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-100);
        }

        if (!callback) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-1);
        }

        FUTON_LOGI("unregisterStatusCallback() called");
        std::lock_guard<std::mutex> lock(callbacks_mutex_);

        auto it = std::find_if(callbacks_.begin(), callbacks_.end(),
                               [&callback](const CallbackEntry &entry) {
                                   return entry.callback == callback;
                               });

        if (it == callbacks_.end()) {
            FUTON_LOGW("Callback not found");
            return ndk::ScopedAStatus::ok();
        }

        callbacks_.erase(it);
        FUTON_LOGI("Callback unregistered, remaining=%zu", callbacks_.size());
        return ndk::ScopedAStatus::ok();
    }

    ndk::ScopedAStatus IFutonDaemonImpl::registerBufferReleaseCallback(
            const std::shared_ptr<IBufferReleaseCallback> &callback
    ) {
        if (!check_authenticated("registerBufferReleaseCallback")) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-100);
        }

        if (!callback) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-1);
        }

        std::lock_guard<std::mutex> lock(callbacks_mutex_);

        BufferCallbackEntry entry;
        entry.callback = callback;
        entry.valid = true;
        buffer_callbacks_.push_back(std::move(entry));

        return ndk::ScopedAStatus::ok();
    }

    ndk::ScopedAStatus IFutonDaemonImpl::unregisterBufferReleaseCallback(
            const std::shared_ptr<IBufferReleaseCallback> &callback
    ) {
        if (!check_authenticated("unregisterBufferReleaseCallback")) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-100);
        }

        if (!callback) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-1);
        }

        std::lock_guard<std::mutex> lock(callbacks_mutex_);

        auto it = std::find_if(buffer_callbacks_.begin(), buffer_callbacks_.end(),
                               [&callback](const BufferCallbackEntry &entry) {
                                   return entry.callback == callback;
                               });

        if (it != buffer_callbacks_.end()) {
            buffer_callbacks_.erase(it);
        }

        return ndk::ScopedAStatus::ok();
    }

// ========== Configuration ==========

    ndk::ScopedAStatus IFutonDaemonImpl::configure(const FutonConfig &config) {
        if (!check_authenticated("configure")) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-100);
        }

        FUTON_LOGI("configure() called: %dx%d @ %d fps, debugStream=%s port=%d, touchDevice=%s",
                   config.captureWidth, config.captureHeight, config.targetFps,
                   config.enableDebugStream ? "true" : "false", config.debugStreamPort,
                   config.touchDevicePath.empty() ? "auto" : config.touchDevicePath.c_str());

        FutonConfig old_config;
        {
            std::lock_guard<std::mutex> lock(config_mutex_);
            old_config = config_;
            config_ = config;
        }

        // Handle touch device path change - reinitialize InputInjector if needed
        if (old_config.touchDevicePath != config.touchDevicePath) {
            auto injector = input_injector_.lock();
            if (injector) {
                FUTON_LOGI("Touch device path changed, reinitializing InputInjector...");
                injector->shutdown();
                auto result = injector->initialize(config.touchDevicePath);
                if (!result.is_ok()) {
                    FUTON_LOGW("InputInjector reinitialization failed");
                }
            }
        }

        // Handle debug stream configuration regardless of running state
        auto debug = debug_stream_.lock();
        if (debug) {
            bool port_changed = old_config.debugStreamPort != config.debugStreamPort;
            bool enable_changed = old_config.enableDebugStream != config.enableDebugStream;

            if (config.enableDebugStream) {
                if (!old_config.enableDebugStream || port_changed) {
                    FUTON_LOGI("Starting debug stream on port %d", config.debugStreamPort);
                    debug->shutdown();
                    if (!debug->initialize(config.debugStreamPort, 30)) {
                        FUTON_LOGW("Failed to start debug stream on port %d",
                                   config.debugStreamPort);
                    } else {
                        FUTON_LOGI("Debug stream started successfully on port %d",
                                   config.debugStreamPort);
                    }
                }
            } else if (enable_changed) {
                FUTON_LOGI("Stopping debug stream");
                debug->shutdown();
            }
        } else {
            FUTON_LOGW("Debug stream not available");
        }

        return ndk::ScopedAStatus::ok();
    }

    ndk::ScopedAStatus IFutonDaemonImpl::configureHotPath(const std::string &jsonRules) {
        if (!check_authenticated("configureHotPath")) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-100);
        }

        FUTON_LOGI("configureHotPath() called, rules length=%zu", jsonRules.length());

        auto router = hotpath_router_.lock();
        if (!router) {
            FUTON_LOGW("HotPath router not available");
            return ndk::ScopedAStatus::fromServiceSpecificError(-1);
        }

        if (!router->load_rules(jsonRules)) {
            FUTON_LOGE("Failed to parse HotPath rules");
            return ndk::ScopedAStatus::fromServiceSpecificError(-2);
        }

        hot_path_progress_.store(0);
        return ndk::ScopedAStatus::ok();
    }

// ========== Perception ==========

    ndk::ScopedAStatus IFutonDaemonImpl::getScreenshot(ScreenshotResult *_aidl_return) {
        if (!check_authenticated("getScreenshot")) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-100);
        }

        auto pipeline = vision_pipeline_.lock();
        if (!pipeline) {
            FUTON_LOGE("getScreenshot: Vision pipeline not available (null)");
            return ndk::ScopedAStatus::fromServiceSpecificErrorWithMessage(
                    -1, "Vision pipeline not available");
        }

        // Auto-initialize pipeline if not initialized
        if (!pipeline->is_initialized()) {
            FUTON_LOGI("getScreenshot: Vision pipeline not initialized, auto-initializing...");
            std::lock_guard<std::mutex> lock(pipeline_callback_mutex_);
            if (pipeline_start_callback_) {
                FutonConfig default_config;
                {
                    std::lock_guard<std::mutex> cfg_lock(config_mutex_);
                    default_config = config_;
                }
                if (!pipeline_start_callback_(default_config)) {
                    FUTON_LOGE("getScreenshot: Failed to auto-initialize vision pipeline");
                    return ndk::ScopedAStatus::fromServiceSpecificErrorWithMessage(
                            -2, "Failed to initialize vision pipeline");
                }
                FUTON_LOGI("getScreenshot: Vision pipeline auto-initialized successfully");
            } else {
                FUTON_LOGE("getScreenshot: No pipeline start callback registered");
                return ndk::ScopedAStatus::fromServiceSpecificErrorWithMessage(
                        -3, "Vision pipeline not configured");
            }
        }

        auto frame_result = pipeline->acquire_frame();
        if (!frame_result.is_ok()) {
            FUTON_LOGE("getScreenshot: Failed to acquire frame (error=%d)",
                       static_cast<int>(frame_result.error()));
            return ndk::ScopedAStatus::fromServiceSpecificErrorWithMessage(
                    -4, "Failed to acquire frame");
        }

        auto &frame = frame_result.value();
        uid_t caller_uid = getCallingUid();

        int32_t buffer_id = track_buffer(
                frame.buffer,
                frame.width,
                frame.height,
                caller_uid
        );

        _aidl_return->bufferId = buffer_id;
        _aidl_return->buffer = frame.buffer;
        _aidl_return->timestampNs = frame.timestamp_ns;
        _aidl_return->width = frame.width;
        _aidl_return->height = frame.height;

        return ndk::ScopedAStatus::ok();
    }

    ndk::ScopedAStatus IFutonDaemonImpl::releaseScreenshot(int32_t bufferId) {
        if (!check_authenticated("releaseScreenshot")) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-100);
        }

        release_tracked_buffer(bufferId);
        return ndk::ScopedAStatus::ok();
    }

    ndk::ScopedAStatus IFutonDaemonImpl::requestPerception(
            std::vector<DetectionResult> *_aidl_return
    ) {
        if (!check_authenticated("requestPerception")) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-100);
        }

        _aidl_return->clear();

        auto pipeline = vision_pipeline_.lock();
        if (!pipeline) {
            FUTON_LOGE("requestPerception: Vision pipeline not available (null)");
            return ndk::ScopedAStatus::fromServiceSpecificErrorWithMessage(
                    -1, "Vision pipeline not available");
        }

        // Auto-initialize pipeline if not initialized
        if (!pipeline->is_initialized()) {
            FUTON_LOGI("requestPerception: Vision pipeline not initialized, auto-initializing...");
            std::lock_guard<std::mutex> lock(pipeline_callback_mutex_);
            if (pipeline_start_callback_) {
                FutonConfig default_config;
                {
                    std::lock_guard<std::mutex> cfg_lock(config_mutex_);
                    default_config = config_;
                }
                if (!pipeline_start_callback_(default_config)) {
                    FUTON_LOGE("requestPerception: Failed to auto-initialize vision pipeline");
                    return ndk::ScopedAStatus::fromServiceSpecificErrorWithMessage(
                            -2, "Failed to initialize vision pipeline");
                }
                FUTON_LOGI("requestPerception: Vision pipeline auto-initialized successfully");
            } else {
                FUTON_LOGE("requestPerception: No pipeline start callback registered");
                return ndk::ScopedAStatus::fromServiceSpecificErrorWithMessage(
                        -3, "Vision pipeline not configured");
            }
        }

        auto ocr_engine = ppocrv5_engine_.lock();
        if (!ocr_engine) {
            FUTON_LOGW("requestPerception: PPOCRv5 engine not available");
            return ndk::ScopedAStatus::fromServiceSpecificErrorWithMessage(
                    -4, "OCR engine not available");
        }

        // Acquire frame from vision pipeline
        auto frame_result = pipeline->acquire_frame();
        if (!frame_result.is_ok()) {
            FUTON_LOGE("requestPerception: Failed to acquire frame (error=%d)",
                       static_cast<int>(frame_result.error()));
            return ndk::ScopedAStatus::fromServiceSpecificErrorWithMessage(
                    -5, "Failed to acquire frame");
        }

        auto &frame = frame_result.value();

        // Wait for fence if present
        if (frame.fence_fd >= 0) {
            if (!futon::vision::VisionPipeline::wait_for_fence(frame.fence_fd, 100)) {
                FUTON_LOGW("requestPerception: Fence wait timeout");
            }
        }

        // Lock AHardwareBuffer to get pixel data
        AHardwareBuffer_Desc desc;
        AHardwareBuffer_describe(frame.buffer, &desc);

        void *pixels = nullptr;
        int lock_result = AHardwareBuffer_lock(
                frame.buffer,
                AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,
                -1,  // fence
                nullptr,  // rect (full buffer)
                &pixels
        );

        if (lock_result != 0 || pixels == nullptr) {
            FUTON_LOGE("requestPerception: Failed to lock hardware buffer");
            pipeline->release_frame();
            return ndk::ScopedAStatus::fromServiceSpecificErrorWithMessage(
                    -6, "Failed to lock hardware buffer");
        }

        // Calculate stride (bytes per row)
        int stride = desc.stride * 4;  // RGBA = 4 bytes per pixel
        int width = static_cast<int>(desc.width);
        int height = static_cast<int>(desc.height);

        FUTON_LOGD("requestPerception: Processing %dx%d image (stride=%d)", width, height, stride);

        // Run OCR
        auto ocr_results = ocr_engine->Process(
                static_cast<const uint8_t *>(pixels),
                width, height, stride
        );

        // Unlock buffer
        AHardwareBuffer_unlock(frame.buffer, nullptr);
        pipeline->release_frame();

        // Convert OCR results to DetectionResult
        // Return pixel coordinates directly (not normalized)
        // classId: 0 = text element (OCR result)
        for (const auto &ocr: ocr_results) {
            DetectionResult det;

            // Convert rotated rect to axis-aligned bounding box (pixel coordinates)
            // RotatedRect has center_x, center_y, width, height, angle
            float half_w = ocr.box.width / 2.0f;
            float half_h = ocr.box.height / 2.0f;

            // Return pixel coordinates directly
            det.x1 = std::max(0.0f, ocr.box.center_x - half_w);
            det.y1 = std::max(0.0f, ocr.box.center_y - half_h);
            det.x2 = std::min(static_cast<float>(width), ocr.box.center_x + half_w);
            det.y2 = std::min(static_cast<float>(height), ocr.box.center_y + half_h);

            det.confidence = ocr.box.confidence;
            det.classId = 0;  // 0 = text element
            det.className = "text";
            det.text = ocr.text;
            det.textConfidence = ocr.confidence;

            _aidl_return->push_back(std::move(det));
        }

        auto benchmark = ocr_engine->GetBenchmark();
        FUTON_LOGI("requestPerception: %zu OCR results, det=%.1fms, rec=%.1fms, total=%.1fms",
                   _aidl_return->size(),
                   benchmark.detection_time_ms,
                   benchmark.recognition_time_ms,
                   benchmark.total_time_ms);

        return ndk::ScopedAStatus::ok();
    }

// Placeholder for removed inference code
#if 0
    auto frame_result = pipeline->acquire_frame();
    if (!frame_result.is_ok()) {
        return ndk::ScopedAStatus::fromServiceSpecificError(-3);
    }

    auto &frame = frame_result.value();
    futon::inference::InferenceResult inf_result;
    auto inf_status = engine->run_inference(frame.buffer, frame.fence_fd, &inf_result);
    pipeline->release_frame();

    if (!inf_status.is_ok()) {
        return ndk::ScopedAStatus::fromServiceSpecificError(-4);
    }

    for (const auto &det: inf_result.detections) {
        DetectionResult aidl_det;
        aidl_det.x1 = det.x1;
        aidl_det.y1 = det.y1;
        aidl_det.x2 = det.x2;
        aidl_det.y2 = det.y2;
        aidl_det.confidence = det.confidence;
        aidl_det.classId = det.class_id;
        aidl_det.className = "";
        _aidl_return->push_back(std::move(aidl_det));
    }

    return ndk::ScopedAStatus::ok();
#endif

// ========== Input Injection ==========

    ndk::ScopedAStatus IFutonDaemonImpl::tap(int32_t x, int32_t y) {
        if (!check_authenticated("tap")) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-100);
        }

        auto injector = input_injector_.lock();
        if (!injector) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-1);
        }

        auto result = injector->tap(x, y);
        if (!result) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-2);
        }

        return ndk::ScopedAStatus::ok();
    }

    ndk::ScopedAStatus IFutonDaemonImpl::longPress(int32_t x, int32_t y, int32_t durationMs) {
        if (!check_authenticated("longPress")) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-100);
        }

        auto injector = input_injector_.lock();
        if (!injector) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-1);
        }

        // Long press is a tap with extended duration (default 500ms if not specified)
        int32_t duration = durationMs > 0 ? durationMs : 500;
        auto result = injector->tap(x, y, duration);
        if (!result) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-2);
        }

        return ndk::ScopedAStatus::ok();
    }

    ndk::ScopedAStatus IFutonDaemonImpl::doubleTap(int32_t x, int32_t y) {
        if (!check_authenticated("doubleTap")) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-100);
        }

        auto injector = input_injector_.lock();
        if (!injector) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-1);
        }

        // Double tap: two quick taps with ~100ms interval
        auto result1 = injector->tap(x, y, 50);
        if (!result1) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-2);
        }

        std::this_thread::sleep_for(std::chrono::milliseconds(100));

        auto result2 = injector->tap(x, y, 50);
        if (!result2) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-3);
        }

        return ndk::ScopedAStatus::ok();
    }

    ndk::ScopedAStatus IFutonDaemonImpl::swipe(
            int32_t x1, int32_t y1,
            int32_t x2, int32_t y2,
            int32_t durationMs
    ) {
        if (!check_authenticated("swipe")) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-100);
        }

        auto injector = input_injector_.lock();
        if (!injector) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-1);
        }

        auto result = injector->swipe(x1, y1, x2, y2, durationMs);
        if (!result) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-2);
        }

        return ndk::ScopedAStatus::ok();
    }

// Scroll direction constants
    static constexpr int32_t SCROLL_UP = 0;
    static constexpr int32_t SCROLL_DOWN = 1;
    static constexpr int32_t SCROLL_LEFT = 2;
    static constexpr int32_t SCROLL_RIGHT = 3;

    ndk::ScopedAStatus IFutonDaemonImpl::scroll(
            int32_t x, int32_t y,
            int32_t direction, int32_t distance
    ) {
        if (!check_authenticated("scroll")) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-100);
        }

        auto injector = input_injector_.lock();
        if (!injector) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-1);
        }

        int32_t x2 = x, y2 = y;
        switch (direction) {
            case SCROLL_UP:
                y2 = y - distance;
                break;
            case SCROLL_DOWN:
                y2 = y + distance;
                break;
            case SCROLL_LEFT:
                x2 = x - distance;
                break;
            case SCROLL_RIGHT:
                x2 = x + distance;
                break;
            default:
                return ndk::ScopedAStatus::fromServiceSpecificError(-2);
        }

        auto result = injector->swipe(x, y, x2, y2, 300);
        if (!result) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-3);
        }

        return ndk::ScopedAStatus::ok();
    }

    ndk::ScopedAStatus IFutonDaemonImpl::pinch(
            int32_t centerX, int32_t centerY,
            int32_t startDistance, int32_t endDistance,
            int32_t durationMs
    ) {
        if (!check_authenticated("pinch")) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-100);
        }

        auto injector = input_injector_.lock();
        if (!injector) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-1);
        }

        // Pinch uses two fingers moving symmetrically around center
        // Calculate start and end positions for both fingers
        int32_t halfStartDist = startDistance / 2;
        int32_t halfEndDist = endDistance / 2;

        // Finger 1: left side, Finger 2: right side
        int32_t f1_start_x = centerX - halfStartDist;
        int32_t f1_end_x = centerX - halfEndDist;
        int32_t f2_start_x = centerX + halfStartDist;
        int32_t f2_end_x = centerX + halfEndDist;

        int32_t steps = std::max(10, durationMs / 16);
        int32_t step_delay_ms = durationMs / steps;

        // Start both fingers
        std::vector<int32_t> xs = {f1_start_x, f2_start_x};
        std::vector<int32_t> ys = {centerY, centerY};
        std::vector<int32_t> actions = {input::InputInjector::ACTION_DOWN,
                                        input::InputInjector::ACTION_DOWN};

        auto result = injector->multi_touch(xs, ys, actions);
        if (!result.is_ok()) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-2);
        }

        // Move fingers
        for (int32_t i = 1; i <= steps; ++i) {
            float t = static_cast<float>(i) / steps;
            int32_t f1_x = f1_start_x + static_cast<int32_t>((f1_end_x - f1_start_x) * t);
            int32_t f2_x = f2_start_x + static_cast<int32_t>((f2_end_x - f2_start_x) * t);

            xs = {f1_x, f2_x};
            ys = {centerY, centerY};
            actions = {input::InputInjector::ACTION_MOVE, input::InputInjector::ACTION_MOVE};

            result = injector->multi_touch(xs, ys, actions);
            if (!result.is_ok()) {
                return ndk::ScopedAStatus::fromServiceSpecificError(-3);
            }

            std::this_thread::sleep_for(std::chrono::milliseconds(step_delay_ms));
        }

        // Release both fingers
        xs = {f1_end_x, f2_end_x};
        ys = {centerY, centerY};
        actions = {input::InputInjector::ACTION_UP, input::InputInjector::ACTION_UP};

        result = injector->multi_touch(xs, ys, actions);
        if (!result.is_ok()) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-4);
        }

        return ndk::ScopedAStatus::ok();
    }

    ndk::ScopedAStatus IFutonDaemonImpl::multiTouch(
            const std::vector<int32_t> &xs,
            const std::vector<int32_t> &ys,
            const std::vector<int32_t> &actions
    ) {
        if (!check_authenticated("multiTouch")) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-100);
        }

        if (xs.size() != ys.size() || xs.size() != actions.size()) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-1);
        }

        auto injector = input_injector_.lock();
        if (!injector) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-2);
        }

        auto result = injector->multi_touch(xs, ys, actions);
        if (!result.is_ok()) {
            FUTON_LOGW("multiTouch failed: %s", result.message.c_str());
            return ndk::ScopedAStatus::fromServiceSpecificError(-3);
        }

        return ndk::ScopedAStatus::ok();
    }

    ndk::ScopedAStatus IFutonDaemonImpl::inputText(const std::string &text) {
        FUTON_LOGI("inputText called: text_len=%zu", text.size());

        if (!check_authenticated("inputText")) {
            FUTON_LOGW("inputText: authentication failed");
            return ndk::ScopedAStatus::fromServiceSpecificError(-100);
        }

        auto injector = input_injector_.lock();
        if (!injector) {
            FUTON_LOGE("inputText: injector not available");
            return ndk::ScopedAStatus::fromServiceSpecificError(-1);
        }

        FUTON_LOGD("inputText: calling injector->input_text()");
        auto result = injector->input_text(text);
        if (!result) {
            FUTON_LOGE("inputText: injection failed: %s", result.message.c_str());
            return ndk::ScopedAStatus::fromServiceSpecificError(-2);
        }

        FUTON_LOGI("inputText: success");
        return ndk::ScopedAStatus::ok();
    }

    ndk::ScopedAStatus IFutonDaemonImpl::pressKey(int32_t keyCode) {
        if (!check_authenticated("pressKey")) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-100);
        }

        auto injector = input_injector_.lock();
        if (!injector) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-1);
        }

        auto result = injector->press_key(keyCode);
        if (!result) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-2);
        }

        return ndk::ScopedAStatus::ok();
    }

// ========== System Actions ==========

// Android key codes
    static constexpr int32_t KEYCODE_BACK = 4;
    static constexpr int32_t KEYCODE_HOME = 3;
    static constexpr int32_t KEYCODE_APP_SWITCH = 187;

    ndk::ScopedAStatus IFutonDaemonImpl::pressBack() {
        if (!check_authenticated("pressBack")) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-100);
        }

        auto injector = input_injector_.lock();
        if (!injector) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-1);
        }

        auto result = injector->press_key(KEYCODE_BACK);
        if (!result) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-2);
        }

        return ndk::ScopedAStatus::ok();
    }

    ndk::ScopedAStatus IFutonDaemonImpl::pressHome() {
        if (!check_authenticated("pressHome")) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-100);
        }

        auto injector = input_injector_.lock();
        if (!injector) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-1);
        }

        auto result = injector->press_key(KEYCODE_HOME);
        if (!result) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-2);
        }

        return ndk::ScopedAStatus::ok();
    }

    ndk::ScopedAStatus IFutonDaemonImpl::pressRecents() {
        if (!check_authenticated("pressRecents")) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-100);
        }

        auto injector = input_injector_.lock();
        if (!injector) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-1);
        }

        auto result = injector->press_key(KEYCODE_APP_SWITCH);
        if (!result) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-2);
        }

        return ndk::ScopedAStatus::ok();
    }

    ndk::ScopedAStatus IFutonDaemonImpl::openNotifications() {
        if (!check_authenticated("openNotifications")) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-100);
        }

        // Run in background to avoid blocking Binder thread
        int ret = system("cmd statusbar expand-notifications &");
        if (ret != 0) {
            FUTON_LOGW("openNotifications failed with code %d", ret);
            return ndk::ScopedAStatus::fromServiceSpecificError(-1);
        }

        return ndk::ScopedAStatus::ok();
    }

    ndk::ScopedAStatus IFutonDaemonImpl::openQuickSettings() {
        if (!check_authenticated("openQuickSettings")) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-100);
        }

        // Run in background to avoid blocking Binder thread
        int ret = system("cmd statusbar expand-settings &");
        if (ret != 0) {
            FUTON_LOGW("openQuickSettings failed with code %d", ret);
            return ndk::ScopedAStatus::fromServiceSpecificError(-1);
        }

        return ndk::ScopedAStatus::ok();
    }

    // ========== Input Validation Helpers ==========

    /**
     * Validates a package name against Android naming rules.
     * Only allows: a-z, A-Z, 0-9, dots (.), underscores (_)
     * Must contain at least one dot, max 256 chars.
     */
    static bool is_valid_package_name(const std::string &name) {
        if (name.empty() || name.length() > 256) {
            return false;
        }

        bool has_dot = false;
        for (size_t i = 0; i < name.length(); ++i) {
            char c = name[i];
            if (c == '.') {
                has_dot = true;
                // Dot cannot be first, last, or consecutive
                if (i == 0 || i == name.length() - 1 || name[i - 1] == '.') {
                    return false;
                }
            } else if (!((c >= 'a' && c <= 'z') ||
                         (c >= 'A' && c <= 'Z') ||
                         (c >= '0' && c <= '9') ||
                         c == '_')) {
                return false;
            }
        }
        return has_dot;
    }

    /**
     * Validates a component name (package/activity format).
     * Only allows: a-z, A-Z, 0-9, dots (.), underscores (_), slashes (/), dollar signs ($)
     * Must contain exactly one slash, max 512 chars.
     * Activity part can start with dot (shorthand for package prefix).
     */
    static bool is_valid_component_name(const std::string &name) {
        if (name.empty() || name.length() > 512) {
            return false;
        }

        int slash_count = 0;
        size_t slash_pos = 0;
        for (size_t i = 0; i < name.length(); ++i) {
            char c = name[i];
            if (c == '/') {
                slash_count++;
                slash_pos = i;
                if (slash_count > 1) return false;
            } else if (c == '.') {
                // Dot cannot be first char of package, or consecutive
                // But CAN follow slash (shorthand activity name like /.MainActivity)
                if (i == 0 || (name[i - 1] == '.' && name[i - 1] != '/')) {
                    return false;
                }
            } else if (!((c >= 'a' && c <= 'z') ||
                         (c >= 'A' && c <= 'Z') ||
                         (c >= '0' && c <= '9') ||
                         c == '_' || c == '$')) {
                return false;
            }
        }

        // Must have exactly one slash, not at start or end
        return slash_count == 1 && slash_pos > 0 && slash_pos < name.length() - 1;
    }

    ndk::ScopedAStatus IFutonDaemonImpl::launchApp(const std::string &packageName) {
        if (!check_authenticated("launchApp")) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-100);
        }

        // Input validation: prevent command injection
        if (!is_valid_package_name(packageName)) {
            FUTON_LOGE("launchApp: invalid package name: '%s'", packageName.c_str());
            return ndk::ScopedAStatus::fromServiceSpecificErrorWithMessage(
                    -1, "Invalid package name");
        }

        FUTON_LOGI("launchApp(%s): starting", packageName.c_str());
        auto &shell = input::ShellExecutor::instance();

        // Helper lambda to check if am start output indicates success
        // am start returns 0 on success, but may return non-zero even when activity starts
        // We check output for "Starting:" or "Warning: Activity not started" patterns
        auto is_am_start_success = [](const std::string &output, int exit_code) -> bool {
            // Exit code 0 is always success
            if (exit_code == 0) return true;

            // Check output for success indicators
            if (output.find("Starting:") != std::string::npos) return true;
            if (output.find("Activity started") != std::string::npos) return true;

            // "Activity not started, its current task has been brought to the front"
            // This means the app is already running and was brought to front - still success
            if (output.find("brought to the front") != std::string::npos) return true;

            // "Warning: Activity not started because the current activity is being kept"
            // App is already in foreground - success
            if (output.find("current activity is being kept") != std::string::npos) return true;

            return false;
        };

        // Method 1: Resolve launcher activity first, then start it
        std::string cmd = "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER " + packageName;
        std::string output = shell.exec(cmd.c_str(), 3000);
        FUTON_LOGI("launchApp(%s): resolve-activity output: '%s'", packageName.c_str(), output.c_str());

        if (!output.empty()) {
            // Parse output to find component (format: package/activity)
            std::string component;
            std::istringstream iss(output);
            std::string line;
            while (std::getline(iss, line)) {
                // Trim whitespace
                size_t start = line.find_first_not_of(" \n\r\t");
                size_t end = line.find_last_not_of(" \n\r\t");
                if (start != std::string::npos && end != std::string::npos) {
                    line = line.substr(start, end - start + 1);
                }

                // Check if line contains '/' (component format)
                if (line.find('/') != std::string::npos && is_valid_component_name(line)) {
                    component = line;
                    break;
                }
            }

            if (!component.empty()) {
                FUTON_LOGI("launchApp(%s): resolved component: %s", packageName.c_str(), component.c_str());
                cmd = "am start -n " + component + " 2>&1";
                int ret = -1;
                output = shell.exec(cmd.c_str(), 5000);
                // Parse exit code from output or use exec_status
                ret = shell.exec_status(("am start -n " + component).c_str(), 5000);
                if (is_am_start_success(output, ret)) {
                    FUTON_LOGI("launchApp(%s): am start -n succeeded (output: %s)", packageName.c_str(),
                               output.c_str());
                    return ndk::ScopedAStatus::ok();
                }
                FUTON_LOGW("launchApp(%s): am start -n failed with %d, output: %s", packageName.c_str(), ret,
                           output.c_str());
            } else {
                FUTON_LOGW("launchApp(%s): could not parse component from resolve-activity", packageName.c_str());
            }
        }

        // Method 2: monkey command (high privilege fallback)
        cmd = "monkey -p " + packageName + " -c android.intent.category.LAUNCHER 1 2>&1";
        FUTON_LOGI("launchApp(%s): trying monkey", packageName.c_str());
        output = shell.exec(cmd.c_str(), 5000);
        int ret = shell.exec_status(("monkey -p " + packageName + " -c android.intent.category.LAUNCHER 1").c_str(),
                                    5000);
        // monkey returns 0 on success, but also check for "Events injected: 1"
        if (ret == 0 || output.find("Events injected: 1") != std::string::npos) {
            FUTON_LOGI("launchApp(%s): monkey succeeded", packageName.c_str());
            return ndk::ScopedAStatus::ok();
        }
        FUTON_LOGW("launchApp(%s): monkey failed with %d, output: %s", packageName.c_str(), ret, output.c_str());

        // Method 3: Try am start with package directly (some apps support this)
        cmd = "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER " + packageName + " 2>&1";
        FUTON_LOGI("launchApp(%s): trying am start with LAUNCHER intent", packageName.c_str());
        output = shell.exec(cmd.c_str(), 5000);
        ret = shell.exec_status(
                ("am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER " + packageName).c_str(),
                5000);
        if (is_am_start_success(output, ret)) {
            FUTON_LOGI("launchApp(%s): am start succeeded (output: %s)", packageName.c_str(), output.c_str());
            return ndk::ScopedAStatus::ok();
        }
        FUTON_LOGW("launchApp(%s): am start failed with %d, output: %s", packageName.c_str(), ret, output.c_str());

        FUTON_LOGE("launchApp(%s): all methods failed", packageName.c_str());
        return ndk::ScopedAStatus::fromServiceSpecificError(-2);
    }

    ndk::ScopedAStatus IFutonDaemonImpl::launchActivity(
            const std::string &packageName,
            const std::string &activityName
    ) {
        if (!check_authenticated("launchActivity")) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-100);
        }

        // Input validation: prevent command injection
        if (!is_valid_package_name(packageName)) {
            FUTON_LOGE("launchActivity: invalid package name: '%s'", packageName.c_str());
            return ndk::ScopedAStatus::fromServiceSpecificErrorWithMessage(
                    -1, "Invalid package name");
        }

        // Validate activity name (similar rules but allows $ for inner classes)
        if (activityName.empty() || activityName.length() > 256) {
            FUTON_LOGE("launchActivity: invalid activity name length");
            return ndk::ScopedAStatus::fromServiceSpecificErrorWithMessage(
                    -1, "Invalid activity name");
        }
        for (char c: activityName) {
            if (!((c >= 'a' && c <= 'z') ||
                  (c >= 'A' && c <= 'Z') ||
                  (c >= '0' && c <= '9') ||
                  c == '.' || c == '_' || c == '$')) {
                FUTON_LOGE("launchActivity: invalid char in activity name: '%c'", c);
                return ndk::ScopedAStatus::fromServiceSpecificErrorWithMessage(
                        -1, "Invalid activity name");
            }
        }

        std::string component = packageName + "/" + activityName;
        FUTON_LOGI("launchActivity: %s", component.c_str());

        auto &shell = input::ShellExecutor::instance();
        std::string cmd = "am start -n \"" + component + "\"";
        int ret = shell.exec_status(cmd.c_str(), 5000);
        if (ret != 0) {
            FUTON_LOGW("launchActivity(%s) failed with code %d", component.c_str(), ret);
            return ndk::ScopedAStatus::fromServiceSpecificError(-2);
        }

        return ndk::ScopedAStatus::ok();
    }

// ========== Utility Actions ==========

    ndk::ScopedAStatus IFutonDaemonImpl::wait(int32_t durationMs) {
        if (!check_authenticated("wait")) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-100);
        }

        if (durationMs > 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(durationMs));
        }

        return ndk::ScopedAStatus::ok();
    }

    ndk::ScopedAStatus IFutonDaemonImpl::saveScreenshot(const std::string &filePath) {
        if (!check_authenticated("saveScreenshot")) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-100);
        }

        if (filePath.empty()) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-1);
        }

        // Note: screencap needs to complete before we return, so we don't use &
        // But we add output redirection to avoid potential issues
        std::string cmd = "screencap -p " + filePath + " > /dev/null 2>&1";
        int ret = system(cmd.c_str());
        if (ret != 0) {
            FUTON_LOGW("saveScreenshot(%s) failed with code %d", filePath.c_str(), ret);
            return ndk::ScopedAStatus::fromServiceSpecificError(-2);
        }

        return ndk::ScopedAStatus::ok();
    }

    ndk::ScopedAStatus IFutonDaemonImpl::requestIntervention(
            const std::string &reason,
            const std::string &actionHint
    ) {
        if (!check_authenticated("requestIntervention")) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-100);
        }

        FUTON_LOGI("Intervention requested: %s (hint: %s)",
                   reason.c_str(), actionHint.c_str());

        // Notify all registered callbacks about the intervention request
        std::lock_guard<std::mutex> lock(callbacks_mutex_);
        for (auto &entry: callbacks_) {
            if (entry.valid && entry.callback) {
                try {
                    // Use onError callback with special code for intervention
                    // Code 1000 = intervention request
                    entry.callback->onError(1000, reason);
                } catch (...) {
                    entry.valid = false;
                }
            }
        }

        return ndk::ScopedAStatus::ok();
    }

    ndk::ScopedAStatus IFutonDaemonImpl::call(
            const std::string &command,
            const std::string &argsJson,
            std::string *_aidl_return
    ) {
        if (!check_authenticated("call")) {
            *_aidl_return = R"({"success":false,"error":"Not authenticated"})";
            return ndk::ScopedAStatus::ok();
        }

        FUTON_LOGI("call(%s) with args: %s", command.c_str(), argsJson.c_str());

        // Parse command namespace
        std::string ns, cmd;
        size_t dot_pos = command.find('.');
        if (dot_pos != std::string::npos) {
            ns = command.substr(0, dot_pos);
            cmd = command.substr(dot_pos + 1);
        } else {
            ns = "";
            cmd = command;
        }

        // Execute command based on namespace
        if (ns == "shell" || cmd == "shell") {
            // shell command execution
            // Args: {"cmd": "ls -la"}
            // Extract cmd from JSON (simple parsing)
            size_t cmd_start = argsJson.find("\"cmd\"");
            if (cmd_start == std::string::npos) {
                *_aidl_return = R"({"success":false,"error":"Missing 'cmd' argument"})";
                return ndk::ScopedAStatus::ok();
            }

            size_t value_start = argsJson.find(':', cmd_start);
            size_t quote_start = argsJson.find('"', value_start);
            size_t quote_end = argsJson.find('"', quote_start + 1);

            if (quote_start == std::string::npos || quote_end == std::string::npos) {
                *_aidl_return = R"({"success":false,"error":"Invalid 'cmd' format"})";
                return ndk::ScopedAStatus::ok();
            }

            std::string shell_cmd = argsJson.substr(quote_start + 1, quote_end - quote_start - 1);

            // Execute and capture output
            FILE *pipe = popen(shell_cmd.c_str(), "r");
            if (!pipe) {
                *_aidl_return = R"({"success":false,"error":"Failed to execute command"})";
                return ndk::ScopedAStatus::ok();
            }

            std::string output;
            char buffer[256];
            while (fgets(buffer, sizeof(buffer), pipe) != nullptr) {
                output += buffer;
            }
            int exit_code = pclose(pipe);

            // Escape output for JSON
            std::string escaped_output;
            for (char c: output) {
                switch (c) {
                    case '"':
                        escaped_output += "\\\"";
                        break;
                    case '\\':
                        escaped_output += "\\\\";
                        break;
                    case '\n':
                        escaped_output += "\\n";
                        break;
                    case '\r':
                        escaped_output += "\\r";
                        break;
                    case '\t':
                        escaped_output += "\\t";
                        break;
                    default:
                        escaped_output += c;
                }
            }

            *_aidl_return = "{\"success\":true,\"exitCode\":" + std::to_string(exit_code) +
                            ",\"output\":\"" + escaped_output + "\"}";
        } else if (ns == "log") {
            // log command
            // Args: {"level": "info", "message": "..."}
            FUTON_LOGI("DSL Log: %s", argsJson.c_str());
            *_aidl_return = R"({"success":true})";
        } else if (ns == "var") {
            // Variable storage (in-memory for now)
            // var.set: {"key": "name", "value": "..."}
            // var.get: {"key": "name"}
            static std::unordered_map<std::string, std::string> variables;
            static std::mutex var_mutex;

            if (cmd == "set") {
                // Simple JSON parsing for key and value
                size_t key_start = argsJson.find("\"key\"");
                size_t value_start = argsJson.find("\"value\"");

                if (key_start == std::string::npos) {
                    *_aidl_return = R"({"success":false,"error":"Missing 'key'"})";
                    return ndk::ScopedAStatus::ok();
                }

                // Extract key
                size_t k_quote1 = argsJson.find('"', argsJson.find(':', key_start));
                size_t k_quote2 = argsJson.find('"', k_quote1 + 1);
                std::string key = argsJson.substr(k_quote1 + 1, k_quote2 - k_quote1 - 1);

                // Extract value
                size_t v_quote1 = argsJson.find('"', argsJson.find(':', value_start));
                size_t v_quote2 = argsJson.find('"', v_quote1 + 1);
                std::string value = argsJson.substr(v_quote1 + 1, v_quote2 - v_quote1 - 1);

                std::lock_guard<std::mutex> lock(var_mutex);
                variables[key] = value;
                *_aidl_return = R"({"success":true})";
            } else if (cmd == "get") {
                size_t key_start = argsJson.find("\"key\"");
                if (key_start == std::string::npos) {
                    *_aidl_return = R"({"success":false,"error":"Missing 'key'"})";
                    return ndk::ScopedAStatus::ok();
                }

                size_t k_quote1 = argsJson.find('"', argsJson.find(':', key_start));
                size_t k_quote2 = argsJson.find('"', k_quote1 + 1);
                std::string key = argsJson.substr(k_quote1 + 1, k_quote2 - k_quote1 - 1);

                std::lock_guard<std::mutex> lock(var_mutex);
                auto it = variables.find(key);
                if (it != variables.end()) {
                    *_aidl_return = "{\"success\":true,\"value\":\"" + it->second + "\"}";
                } else {
                    *_aidl_return = R"({"success":true,"value":null})";
                }
            } else {
                *_aidl_return = R"({"success":false,"error":"Unknown var command"})";
            }
        } else if (cmd == "vibrate") {
            // Vibrate using shell
            // Args: {"duration": 200}
            int duration = 200;
            size_t dur_pos = argsJson.find("\"duration\"");
            if (dur_pos != std::string::npos) {
                size_t colon = argsJson.find(':', dur_pos);
                duration = std::stoi(argsJson.substr(colon + 1));
            }

            std::string vibrate_cmd =
                    "cmd vibrator_manager vibrate " + std::to_string(duration) + " -f &";
            int ret = system(vibrate_cmd.c_str());
            *_aidl_return = ret == 0 ? R"({"success":true})"
                                     : R"({"success":false,"error":"Vibrate failed"})";
        } else if (cmd == "toast") {
            // Toast via am broadcast (requires accessibility or overlay)
            // For now, just log it - actual toast needs app-side handling
            FUTON_LOGI("Toast requested: %s", argsJson.c_str());
            *_aidl_return = R"({"success":true,"note":"Toast forwarded to app"})";

            // Notify callbacks
            std::lock_guard<std::mutex> lock(callbacks_mutex_);
            for (auto &entry: callbacks_) {
                if (entry.valid && entry.callback) {
                    try {
                        // Code 1001 = toast request
                        entry.callback->onError(1001, argsJson);
                    } catch (...) {
                        entry.valid = false;
                    }
                }
            }
        } else if (cmd == "notify") {
            // Notification - forward to app
            FUTON_LOGI("Notification requested: %s", argsJson.c_str());
            *_aidl_return = R"({"success":true,"note":"Notification forwarded to app"})";

            std::lock_guard<std::mutex> lock(callbacks_mutex_);
            for (auto &entry: callbacks_) {
                if (entry.valid && entry.callback) {
                    try {
                        // Code 1002 = notification request
                        entry.callback->onError(1002, argsJson);
                    } catch (...) {
                        entry.valid = false;
                    }
                }
            }
        } else if (cmd == "broadcast") {
            // Send broadcast
            // Args: {"action": "com.example.ACTION", "extras": {...}}
            size_t action_start = argsJson.find("\"action\"");
            if (action_start == std::string::npos) {
                *_aidl_return = R"({"success":false,"error":"Missing 'action'"})";
                return ndk::ScopedAStatus::ok();
            }

            size_t a_quote1 = argsJson.find('"', argsJson.find(':', action_start));
            size_t a_quote2 = argsJson.find('"', a_quote1 + 1);
            std::string action = argsJson.substr(a_quote1 + 1, a_quote2 - a_quote1 - 1);

            std::string broadcast_cmd = "am broadcast -a " + action + " > /dev/null 2>&1 &";
            int ret = system(broadcast_cmd.c_str());
            *_aidl_return = ret == 0 ? R"({"success":true})"
                                     : R"({"success":false,"error":"Broadcast failed"})";
        } else if (cmd == "intent") {
            // Start intent
            // Args: {"action": "...", "data": "...", "package": "...", "component": "..."}
            std::string intent_cmd = "am start";

            // Parse action
            size_t action_pos = argsJson.find("\"action\"");
            if (action_pos != std::string::npos) {
                size_t q1 = argsJson.find('"', argsJson.find(':', action_pos));
                size_t q2 = argsJson.find('"', q1 + 1);
                intent_cmd += " -a " + argsJson.substr(q1 + 1, q2 - q1 - 1);
            }

            // Parse data
            size_t data_pos = argsJson.find("\"data\"");
            if (data_pos != std::string::npos) {
                size_t q1 = argsJson.find('"', argsJson.find(':', data_pos));
                size_t q2 = argsJson.find('"', q1 + 1);
                intent_cmd += " -d " + argsJson.substr(q1 + 1, q2 - q1 - 1);
            }

            // Parse component
            size_t comp_pos = argsJson.find("\"component\"");
            if (comp_pos != std::string::npos) {
                size_t q1 = argsJson.find('"', argsJson.find(':', comp_pos));
                size_t q2 = argsJson.find('"', q1 + 1);
                intent_cmd += " -n " + argsJson.substr(q1 + 1, q2 - q1 - 1);
            }

            // Run in background to avoid blocking Binder thread
            intent_cmd += " > /dev/null 2>&1 &";
            int ret = system(intent_cmd.c_str());
            *_aidl_return = ret == 0 ? R"({"success":true})"
                                     : R"({"success":false,"error":"Intent failed"})";
        } else if (ns == "clipboard") {
            if (cmd == "set") {
                // Set clipboard via service call
                size_t text_pos = argsJson.find("\"text\"");
                if (text_pos == std::string::npos) {
                    *_aidl_return = R"({"success":false,"error":"Missing 'text'"})";
                    return ndk::ScopedAStatus::ok();
                }

                size_t q1 = argsJson.find('"', argsJson.find(':', text_pos));
                size_t q2 = argsJson.find('"', q1 + 1);
                std::string text = argsJson.substr(q1 + 1, q2 - q1 - 1);

                // Use am broadcast to set clipboard (requires helper)
                // Run in background to avoid blocking Binder thread
                std::string clip_cmd =
                        "am broadcast -a clipper.set -e text '" + text + "' > /dev/null 2>&1 &";
                int ret = system(clip_cmd.c_str());
                *_aidl_return = ret == 0 ? R"({"success":true})"
                                         : R"({"success":false,"error":"Clipboard set failed"})";
            } else if (cmd == "get") {
                // Get clipboard - forward to app
                *_aidl_return = R"({"success":true,"note":"Clipboard get forwarded to app"})";

                std::lock_guard<std::mutex> lock(callbacks_mutex_);
                for (auto &entry: callbacks_) {
                    if (entry.valid && entry.callback) {
                        try {
                            // Code 1003 = clipboard get request
                            entry.callback->onError(1003, "");
                        } catch (...) {
                            entry.valid = false;
                        }
                    }
                }
            } else {
                *_aidl_return = R"({"success":false,"error":"Unknown clipboard command"})";
            }
        } else {
            // Unknown command - could be extended via plugins/DSL
            *_aidl_return = "{\"success\":false,\"error\":\"Unknown command: " + command + "\"}";
        }

        return ndk::ScopedAStatus::ok();
    }

// ========== Automation Control ==========

    ndk::ScopedAStatus IFutonDaemonImpl::startHotPath() {
        if (!check_authenticated("startHotPath")) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-100);
        }

        return start_internal();
    }

    ndk::ScopedAStatus IFutonDaemonImpl::stopAutomation() {
        if (!check_authenticated("stopAutomation")) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-100);
        }

        return stop_internal();
    }

    ndk::ScopedAStatus IFutonDaemonImpl::executeTask(
            const std::string &taskJson,
            int64_t *_aidl_return
    ) {
        if (!check_authenticated("executeTask")) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-100);
        }

        FUTON_LOGI("executeTask() called, json length=%zu", taskJson.length());

        static std::atomic<int64_t> task_counter{1};
        *_aidl_return = task_counter.fetch_add(1);

        return ndk::ScopedAStatus::ok();
    }

// ========== Debug APIs ==========

    ndk::ScopedAStatus IFutonDaemonImpl::debugInjectTap(int32_t x, int32_t y) {
#ifdef NDEBUG
        FUTON_LOGD("debugInjectTap: disabled in release build");
        return ndk::ScopedAStatus::ok();
#else
        FUTON_LOGI("debugInjectTap(%d, %d)", x, y);
        auto injector = input_injector_.lock();
        if (!injector) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-1);
        }
        auto result = injector->tap(x, y);
        return result ? ndk::ScopedAStatus::ok()
                      : ndk::ScopedAStatus::fromServiceSpecificError(-2);
#endif
    }

    ndk::ScopedAStatus IFutonDaemonImpl::debugInjectSwipe(
            int32_t x1, int32_t y1,
            int32_t x2, int32_t y2,
            int32_t durationMs
    ) {
#ifdef NDEBUG
        FUTON_LOGD("debugInjectSwipe: disabled in release build");
        return ndk::ScopedAStatus::ok();
#else
        FUTON_LOGI("debugInjectSwipe(%d,%d -> %d,%d, %dms)", x1, y1, x2, y2, durationMs);
        auto injector = input_injector_.lock();
        if (!injector) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-1);
        }
        auto result = injector->swipe(x1, y1, x2, y2, durationMs);
        return result ? ndk::ScopedAStatus::ok()
                      : ndk::ScopedAStatus::fromServiceSpecificError(-2);
#endif
    }

    ndk::ScopedAStatus IFutonDaemonImpl::debugRunDetection(
            std::vector<DetectionResult> *_aidl_return
    ) {
#ifdef NDEBUG
        FUTON_LOGD("debugRunDetection: disabled in release build");
        _aidl_return->clear();
        return ndk::ScopedAStatus::ok();
#else
        return requestPerception(_aidl_return);
#endif
    }

// ========== Legacy Compatibility (API < 34) ==========

    ndk::ScopedAStatus IFutonDaemonImpl::getScreenshotBytes(
            std::vector<uint8_t> *_aidl_return
    ) {
        if (!check_authenticated("getScreenshotBytes")) {
            return ndk::ScopedAStatus::fromServiceSpecificError(-100);
        }

        _aidl_return->clear();

        auto pipeline = vision_pipeline_.lock();
        if (!pipeline) {
            FUTON_LOGE("getScreenshotBytes: pipeline not available");
            return ndk::ScopedAStatus::fromServiceSpecificError(-1);
        }

        // Auto-initialize pipeline if not initialized
        if (!pipeline->is_initialized()) {
            FUTON_LOGI("getScreenshotBytes: Vision pipeline not initialized, auto-initializing...");
            std::lock_guard<std::mutex> lock(pipeline_callback_mutex_);
            if (pipeline_start_callback_) {
                FutonConfig default_config;
                {
                    std::lock_guard<std::mutex> cfg_lock(config_mutex_);
                    default_config = config_;
                }
                if (!pipeline_start_callback_(default_config)) {
                    FUTON_LOGE("getScreenshotBytes: Failed to auto-initialize vision pipeline");
                    return ndk::ScopedAStatus::fromServiceSpecificError(-2);
                }
            } else {
                FUTON_LOGE("getScreenshotBytes: No pipeline start callback registered");
                return ndk::ScopedAStatus::fromServiceSpecificError(-3);
            }
        }

        auto frame_result = pipeline->acquire_frame();
        if (!frame_result.is_ok()) {
            FUTON_LOGE("getScreenshotBytes: Failed to acquire frame (error=%d)",
                       static_cast<int>(frame_result.error()));
            return ndk::ScopedAStatus::fromServiceSpecificError(-4);
        }

        auto frame = frame_result.value();
        if (!frame.buffer) {
            FUTON_LOGE("getScreenshotBytes: capture failed, buffer is null");
            return ndk::ScopedAStatus::fromServiceSpecificError(-5);
        }

        // Lock AHardwareBuffer to get pixel data
        AHardwareBuffer_Desc desc;
        AHardwareBuffer_describe(frame.buffer, &desc);

        void *pixels = nullptr;
        int lock_result = AHardwareBuffer_lock(
                frame.buffer,
                AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,
                -1,  // fence
                nullptr,  // rect (full buffer)
                &pixels);

        if (lock_result != 0 || !pixels) {
            FUTON_LOGE("getScreenshotBytes: failed to lock buffer: %d", lock_result);
            pipeline->release_frame();
            return ndk::ScopedAStatus::fromServiceSpecificError(-6);
        }

        // Calculate data size
        // Format: [4 bytes: width][4 bytes: height][8 bytes: timestamp][RGBA data]
        const size_t header_size = 4 + 4 + 8;  // width + height + timestamp
        const size_t pixel_data_size = desc.width * desc.height * 4;  // RGBA
        const size_t total_size = header_size + pixel_data_size;

        _aidl_return->resize(total_size);
        uint8_t *out = _aidl_return->data();

        // Write header
        uint32_t width = desc.width;
        uint32_t height = desc.height;
        int64_t timestamp = frame.timestamp_ns;

        memcpy(out, &width, 4);
        memcpy(out + 4, &height, 4);
        memcpy(out + 8, &timestamp, 8);

        // Copy pixel data (handle stride if different from width)
        const uint8_t *src = static_cast<const uint8_t *>(pixels);
        uint8_t *dst = out + header_size;

        if (desc.stride == desc.width) {
            // No padding, direct copy
            memcpy(dst, src, pixel_data_size);
        } else {
            // Handle stride padding
            const size_t row_bytes = desc.width * 4;
            const size_t stride_bytes = desc.stride * 4;
            for (uint32_t y = 0; y < desc.height; ++y) {
                memcpy(dst + y * row_bytes, src + y * stride_bytes, row_bytes);
            }
        }

        // Unlock buffer
        AHardwareBuffer_unlock(frame.buffer, nullptr);
        pipeline->release_frame();

        FUTON_LOGD("getScreenshotBytes: captured %ux%u, %zu bytes",
                   width, height, total_size);

        return ndk::ScopedAStatus::ok();
    }

// ========== Internal Start/Stop Methods ==========

    ndk::ScopedAStatus IFutonDaemonImpl::start_internal() {
        FUTON_LOGI("start_internal() called");

        if (running_.load()) {
            FUTON_LOGW("Daemon already running");
            return ndk::ScopedAStatus::ok();
        }

        FutonConfig current_config;
        {
            std::lock_guard<std::mutex> lock(config_mutex_);
            current_config = config_;
        }

        {
            std::lock_guard<std::mutex> lock(pipeline_callback_mutex_);
            if (pipeline_start_callback_) {
                if (!pipeline_start_callback_(current_config)) {
                    FUTON_LOGE("Pipeline start callback failed");
                    return ndk::ScopedAStatus::fromServiceSpecificError(-1);
                }
            }
        }

        running_.store(true);
        frame_count_.store(0);
        hot_path_progress_.store(0);
        last_status_timestamp_ns_.store(get_current_time_ns());

        notify_status_update();
        return ndk::ScopedAStatus::ok();
    }

    ndk::ScopedAStatus IFutonDaemonImpl::stop_internal() {
        FUTON_LOGI("stop_internal() called");

        if (!running_.load()) {
            FUTON_LOGW("Daemon not running");
            return ndk::ScopedAStatus::ok();
        }

        {
            std::lock_guard<std::mutex> lock(pipeline_callback_mutex_);
            if (pipeline_stop_callback_) {
                pipeline_stop_callback_();
            }
        }

        running_.store(false);
        notify_status_update();
        return ndk::ScopedAStatus::ok();
    }

// ========== Internal Methods ==========

    void IFutonDaemonImpl::remove_invalid_callbacks() {
        callbacks_.erase(
                std::remove_if(callbacks_.begin(), callbacks_.end(),
                               [](const CallbackEntry &entry) { return !entry.valid; }),
                callbacks_.end()
        );
    }

    bool IFutonDaemonImpl::is_callback_valid(const CallbackEntry &entry) const {
        if (!entry.valid || !entry.callback) return false;
        try {
            AIBinder *binder = entry.callback->asBinder().get();
            if (!binder) return false;
            return AIBinder_isAlive(binder);
        } catch (...) {
            return false;
        }
    }

    int64_t IFutonDaemonImpl::get_current_time_ns() const {
        auto now = std::chrono::steady_clock::now();
        return std::chrono::duration_cast<std::chrono::nanoseconds>(
                now.time_since_epoch()).count();
    }

    DaemonStatus IFutonDaemonImpl::build_status() const {
        DaemonStatus status;
        status.timestampNs = get_current_time_ns();
        status.fps = current_fps_.load();
        status.totalLatencyMs = current_latency_ms_.load();
        status.captureLatencyMs = 0.0f;  // TODO: track separately
        status.inferenceLatencyMs = 0.0f;  // TODO: track separately
        status.frameCount = frame_count_.load();
        status.isRunning = running_.load();
        status.hotPathProgress = hot_path_progress_.load();

        // Buffer pool stats
        {
            std::lock_guard<std::mutex> lock(buffers_mutex_);
            status.buffersInUse = static_cast<int32_t>(tracked_buffers_.size());
            status.buffersAvailable = config_.bufferPoolSize - status.buffersInUse;
        }

        {
            std::lock_guard<std::mutex> lock(delegate_mutex_);
            status.activeDelegate = active_delegate_;
        }
        return status;
    }

    int32_t IFutonDaemonImpl::track_buffer(
            AHardwareBuffer *buffer,
            int32_t width,
            int32_t height,
            uid_t owner
    ) {
        std::lock_guard<std::mutex> lock(buffers_mutex_);

        int32_t id = next_buffer_id_.fetch_add(1);

        TrackedBuffer tracked;
        tracked.buffer_id = id;
        tracked.buffer = buffer;
        tracked.timestamp_ns = get_current_time_ns();
        tracked.width = width;
        tracked.height = height;
        tracked.owner_uid = owner;

        if (buffer) {
            AHardwareBuffer_acquire(buffer);
        }

        tracked_buffers_[id] = tracked;
        return id;
    }

    void IFutonDaemonImpl::release_tracked_buffer(int32_t buffer_id) {
        std::lock_guard<std::mutex> lock(buffers_mutex_);

        auto it = tracked_buffers_.find(buffer_id);
        if (it == tracked_buffers_.end()) {
            FUTON_LOGW("Buffer %d not found", buffer_id);
            return;
        }

        if (it->second.buffer) {
            AHardwareBuffer_release(it->second.buffer);
        }

        tracked_buffers_.erase(it);
        FUTON_LOGD("Released buffer %d", buffer_id);
    }

// ========== Notification Methods ==========

    void IFutonDaemonImpl::notify_status_update() {
        DaemonStatus status = build_status();

        int64_t last_ts = last_status_timestamp_ns_.load();
        if (status.timestampNs <= last_ts) {
            status.timestampNs = last_ts + 1;
        }
        last_status_timestamp_ns_.store(status.timestampNs);

        std::lock_guard<std::mutex> lock(callbacks_mutex_);
        std::vector<size_t> failed_indices;

        for (size_t i = 0; i < callbacks_.size(); ++i) {
            auto &entry = callbacks_[i];
            if (!is_callback_valid(entry)) {
                failed_indices.push_back(i);
                continue;
            }
            auto result = entry.callback->onStatusUpdate(status);
            if (!result.isOk()) {
                failed_indices.push_back(i);
            }
        }

        for (auto idx: failed_indices) {
            if (idx < callbacks_.size()) {
                callbacks_[idx].valid = false;
            }
        }

        if (!failed_indices.empty()) {
            remove_invalid_callbacks();
        }
    }

    void IFutonDaemonImpl::notify_automation_complete(bool success, const std::string &message) {
        FUTON_LOGI("notify_automation_complete: success=%d", success);

        std::lock_guard<std::mutex> lock(callbacks_mutex_);
        std::vector<size_t> failed_indices;

        for (size_t i = 0; i < callbacks_.size(); ++i) {
            auto &entry = callbacks_[i];
            if (!is_callback_valid(entry)) {
                failed_indices.push_back(i);
                continue;
            }
            auto result = entry.callback->onAutomationComplete(success, message);
            if (!result.isOk()) {
                failed_indices.push_back(i);
            }
        }

        for (auto idx: failed_indices) {
            if (idx < callbacks_.size()) {
                callbacks_[idx].valid = false;
            }
        }

        if (!failed_indices.empty()) {
            remove_invalid_callbacks();
        }
    }

    void IFutonDaemonImpl::notify_error(int code, const std::string &message) {
        FUTON_LOGE("notify_error: code=%d, message=%s", code, message.c_str());

        std::lock_guard<std::mutex> lock(callbacks_mutex_);
        std::vector<size_t> failed_indices;

        for (size_t i = 0; i < callbacks_.size(); ++i) {
            auto &entry = callbacks_[i];
            if (!is_callback_valid(entry)) {
                failed_indices.push_back(i);
                continue;
            }
            auto result = entry.callback->onError(code, message);
            if (!result.isOk()) {
                failed_indices.push_back(i);
            }
        }

        for (auto idx: failed_indices) {
            if (idx < callbacks_.size()) {
                callbacks_[idx].valid = false;
            }
        }

        if (!failed_indices.empty()) {
            remove_invalid_callbacks();
        }
    }

    void IFutonDaemonImpl::notify_detection_result(
            const std::vector<DetectionResult> &results
    ) {
        std::lock_guard<std::mutex> lock(callbacks_mutex_);
        std::vector<size_t> failed_indices;

        for (size_t i = 0; i < callbacks_.size(); ++i) {
            auto &entry = callbacks_[i];
            if (!is_callback_valid(entry)) {
                failed_indices.push_back(i);
                continue;
            }
            auto result = entry.callback->onDetectionResult(results);
            if (!result.isOk()) {
                failed_indices.push_back(i);
            }
        }

        for (auto idx: failed_indices) {
            if (idx < callbacks_.size()) {
                callbacks_[idx].valid = false;
            }
        }

        if (!failed_indices.empty()) {
            remove_invalid_callbacks();
        }
    }

// ========== Component Setters ==========

    void IFutonDaemonImpl::set_vision_pipeline(
            std::shared_ptr<futon::vision::VisionPipeline> pipeline) {
        vision_pipeline_ = pipeline;
    }

    void IFutonDaemonImpl::set_ppocrv5_engine(
            std::shared_ptr<futon::inference::ppocrv5::OcrEngine> engine) {
        ppocrv5_engine_ = engine;
    }

    void IFutonDaemonImpl::set_input_injector(
            std::shared_ptr<futon::input::InputInjector> injector) {
        input_injector_ = injector;
    }

    void IFutonDaemonImpl::set_hotpath_router(
            std::shared_ptr<futon::hotpath::HotPathRouter> router) {
        hotpath_router_ = router;
    }

    void IFutonDaemonImpl::set_debug_stream(
            std::shared_ptr<futon::debug::DebugStream> stream) {
        debug_stream_ = stream;
    }

    void IFutonDaemonImpl::set_status_update_callback(StatusUpdateCallback callback) {
        std::lock_guard<std::mutex> lock(status_callback_mutex_);
        status_update_callback_ = std::move(callback);
    }

    void IFutonDaemonImpl::set_pipeline_start_callback(PipelineStartCallback callback) {
        std::lock_guard<std::mutex> lock(pipeline_callback_mutex_);
        pipeline_start_callback_ = std::move(callback);
    }

    void IFutonDaemonImpl::set_pipeline_stop_callback(PipelineStopCallback callback) {
        std::lock_guard<std::mutex> lock(pipeline_callback_mutex_);
        pipeline_stop_callback_ = std::move(callback);
    }

    void IFutonDaemonImpl::update_stats(float fps, float latency_ms, int frame_count) {
        current_fps_.store(fps);
        current_latency_ms_.store(latency_ms);
        frame_count_.store(frame_count);
    }

    void IFutonDaemonImpl::set_active_delegate(const std::string &delegate) {
        std::lock_guard<std::mutex> lock(delegate_mutex_);
        active_delegate_ = delegate;
    }

    void IFutonDaemonImpl::set_hot_path_progress(int progress) {
        hot_path_progress_.store(progress);
    }

// ========== Input Device Discovery ==========

    ndk::ScopedAStatus IFutonDaemonImpl::listInputDevices(
            std::vector<aidl::me::fleey::futon::InputDeviceEntry> *_aidl_return
    ) {
        FUTON_LOGI("listInputDevices() called");

        _aidl_return->clear();

        futon::input::InputDeviceDiscovery discovery;
        auto devices = discovery.list_all_devices();

        for (const auto &dev: devices) {
            aidl::me::fleey::futon::InputDeviceEntry entry;
            entry.path = dev.path;
            entry.name = dev.name;
            entry.isTouchscreen = dev.is_touchscreen;
            entry.supportsMultiTouch = dev.supports_multi_touch;
            entry.mtProtocol = static_cast<int32_t>(dev.mt_protocol);
            entry.maxX = dev.max_x;
            entry.maxY = dev.max_y;
            entry.maxTouchPoints = dev.max_touch_points;
            entry.touchscreenProbability = dev.touchscreen_probability;
            entry.probabilityReason = dev.probability_reason;
            _aidl_return->push_back(std::move(entry));
        }

        FUTON_LOGI("listInputDevices: found %zu devices", _aidl_return->size());
        return ndk::ScopedAStatus::ok();
    }

// ========== Model Management ==========

    ndk::ScopedAStatus IFutonDaemonImpl::reloadModels(bool *_aidl_return) {
        if (!check_authenticated("reloadModels")) {
            *_aidl_return = false;
            return ndk::ScopedAStatus::fromServiceSpecificError(-100);
        }

        FUTON_LOGI("reloadModels() called");

        bool success = true;
        std::string det_model_path = std::string(MODEL_DIRECTORY) + "/ocr_det_fp16.tflite";
        std::string rec_model_path = std::string(MODEL_DIRECTORY) + "/ocr_rec_fp16.tflite";
        std::string keys_path = std::string(MODEL_DIRECTORY) + "/keys_v5.txt";

        // Reload PPOCRv5 engine if available
        auto ppocrv5 = ppocrv5_engine_.lock();
        if (ppocrv5) {
            FUTON_LOGI("Reloading PPOCRv5 engine...");

            // Check if all required files exist
            bool det_exists = access(det_model_path.c_str(), R_OK) == 0;
            bool rec_exists = access(rec_model_path.c_str(), R_OK) == 0;
            bool keys_exists = access(keys_path.c_str(), R_OK) == 0;

            if (det_exists && rec_exists && keys_exists) {
                FUTON_LOGI("PPOCRv5 models found, engine will be reloaded on next use");
            } else {
                FUTON_LOGW("PPOCRv5 models not found:");
                FUTON_LOGW("  Det: %s (%s)", det_model_path.c_str(), det_exists ? "OK" : "MISSING");
                FUTON_LOGW("  Rec: %s (%s)", rec_model_path.c_str(), rec_exists ? "OK" : "MISSING");
                FUTON_LOGW("  Keys: %s (%s)", keys_path.c_str(), keys_exists ? "OK" : "MISSING");
                success = false;
            }
        }

        *_aidl_return = success;
        FUTON_LOGI("reloadModels() completed: success=%d", success);
        return ndk::ScopedAStatus::ok();
    }

    ndk::ScopedAStatus IFutonDaemonImpl::getModelStatus(std::string *_aidl_return) {
        FUTON_LOGD("getModelStatus() called");

        // Build JSON status response
        std::string json = "{";

        // PPOCRv5 model paths
        std::string det_model_path = std::string(MODEL_DIRECTORY) + "/ocr_det_fp16.tflite";
        std::string rec_model_path = std::string(MODEL_DIRECTORY) + "/ocr_rec_fp16.tflite";
        std::string keys_path = std::string(MODEL_DIRECTORY) + "/keys_v5.txt";

        bool det_exists = access(det_model_path.c_str(), R_OK) == 0;
        bool rec_exists = access(rec_model_path.c_str(), R_OK) == 0;
        bool keys_exists = access(keys_path.c_str(), R_OK) == 0;

        auto ppocrv5 = ppocrv5_engine_.lock();
        bool ppocrv5_initialized = ppocrv5 != nullptr;

        json += "\"ppocrv5_detection\":{";
        json += "\"file_exists\":" + std::string(det_exists ? "true" : "false") + ",";
        json += "\"initialized\":" + std::string(ppocrv5_initialized ? "true" : "false");
        json += "},";

        json += "\"ppocrv5_recognition\":{";
        json += "\"file_exists\":" + std::string(rec_exists ? "true" : "false") + ",";
        json += "\"initialized\":" + std::string(ppocrv5_initialized ? "true" : "false");
        json += "},";

        json += "\"ppocrv5_dictionary\":{";
        json += "\"file_exists\":" + std::string(keys_exists ? "true" : "false") + ",";
        json += "\"initialized\":" + std::string(ppocrv5_initialized ? "true" : "false");
        json += "},";

        // Add accelerator info
        if (ppocrv5) {
            std::string accel = ppocrv5->GetActiveAccelerator() ==
                                futon::inference::ppocrv5::AcceleratorType::kGpu ? "gpu" : "cpu";
            json += "\"accelerator\":\"" + accel + "\",";
        }

        json += "\"model_directory\":\"" + std::string(MODEL_DIRECTORY) + "\"";
        json += "}";

        *_aidl_return = json;
        return ndk::ScopedAStatus::ok();
    }

} // namespace futon::ipc
