/*
 * Futon - Android Automation Daemon
 * Copyright (C) 2025 Fleey
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#include "double_ratchet.h"
#include "core/error.h"

#include <openssl/evp.h>
#include <openssl/rand.h>
#include <openssl/kdf.h>
#include <openssl/err.h>
#include <cstring>
#include <algorithm>

namespace futon::core::crypto {

    namespace {

        constexpr const char *HKDF_INFO_RK = "FutonRatchetRK";
        constexpr const char *HKDF_INFO_CK = "FutonRatchetCK";
        constexpr const char *HKDF_INFO_SMK = "FutonSessionMK";

        void secure_zero(void *ptr, size_t size) {
            volatile uint8_t *p = static_cast<volatile uint8_t *>(ptr);
            while (size--) *p++ = 0;
        }

        std::vector<uint8_t> hkdf_sha256(
                const uint8_t *salt, size_t salt_len,
                const uint8_t *ikm, size_t ikm_len,
                const uint8_t *info, size_t info_len,
                size_t out_len
        ) {
            std::vector<uint8_t> out(out_len);

            EVP_PKEY_CTX *ctx = EVP_PKEY_CTX_new_id(EVP_PKEY_HKDF, nullptr);
            if (!ctx) return {};

            if (EVP_PKEY_derive_init(ctx) <= 0 ||
                EVP_PKEY_CTX_set_hkdf_md(ctx, EVP_sha256()) <= 0 ||
                EVP_PKEY_CTX_set1_hkdf_salt(ctx, salt, salt_len) <= 0 ||
                EVP_PKEY_CTX_set1_hkdf_key(ctx, ikm, ikm_len) <= 0 ||
                EVP_PKEY_CTX_add1_hkdf_info(ctx, info, info_len) <= 0 ||
                EVP_PKEY_derive(ctx, out.data(), &out_len) <= 0) {
                EVP_PKEY_CTX_free(ctx);
                return {};
            }

            EVP_PKEY_CTX_free(ctx);
            out.resize(out_len);
            return out;
        }

    } // anonymous namespace

// MessageHeader serialization
    std::vector<uint8_t> MessageHeader::serialize() const {
        std::vector<uint8_t> data;
        data.reserve(DH_PUBLIC_KEY_SIZE + 8);

        data.insert(data.end(), dh_public.begin(), dh_public.end());

        // Little-endian encoding
        data.push_back(prev_chain_len & 0xFF);
        data.push_back((prev_chain_len >> 8) & 0xFF);
        data.push_back((prev_chain_len >> 16) & 0xFF);
        data.push_back((prev_chain_len >> 24) & 0xFF);

        data.push_back(message_num & 0xFF);
        data.push_back((message_num >> 8) & 0xFF);
        data.push_back((message_num >> 16) & 0xFF);
        data.push_back((message_num >> 24) & 0xFF);

        return data;
    }

    std::optional<MessageHeader> MessageHeader::deserialize(const std::vector<uint8_t> &data) {
        if (data.size() < DH_PUBLIC_KEY_SIZE + 8) {
            return std::nullopt;
        }

        MessageHeader header;
        std::copy_n(data.begin(), DH_PUBLIC_KEY_SIZE, header.dh_public.begin());

        size_t offset = DH_PUBLIC_KEY_SIZE;
        header.prev_chain_len = data[offset] | (data[offset + 1] << 8) |
                                (data[offset + 2] << 16) | (data[offset + 3] << 24);
        offset += 4;
        header.message_num = data[offset] | (data[offset + 1] << 8) |
                             (data[offset + 2] << 16) | (data[offset + 3] << 24);

        return header;
    }

// EncryptedMessage serialization
    std::vector<uint8_t> EncryptedMessage::serialize() const {
        auto header_data = header.serialize();
        std::vector<uint8_t> data;

        // Format: [header_len:4][header][ciphertext]
        uint32_t header_len = static_cast<uint32_t>(header_data.size());
        data.push_back(header_len & 0xFF);
        data.push_back((header_len >> 8) & 0xFF);
        data.push_back((header_len >> 16) & 0xFF);
        data.push_back((header_len >> 24) & 0xFF);

        data.insert(data.end(), header_data.begin(), header_data.end());
        data.insert(data.end(), ciphertext.begin(), ciphertext.end());

        return data;
    }

    std::optional<EncryptedMessage>
    EncryptedMessage::deserialize(const std::vector<uint8_t> &data) {
        if (data.size() < 4) return std::nullopt;

        uint32_t header_len = data[0] | (data[1] << 8) | (data[2] << 16) | (data[3] << 24);
        if (data.size() < 4 + header_len) return std::nullopt;

        std::vector<uint8_t> header_data(data.begin() + 4, data.begin() + 4 + header_len);
        auto header_opt = MessageHeader::deserialize(header_data);
        if (!header_opt) return std::nullopt;

        EncryptedMessage msg;
        msg.header = *header_opt;
        msg.ciphertext.assign(data.begin() + 4 + header_len, data.end());

        return msg;
    }

// DHKeyPair implementation
    DHKeyPair DHKeyPair::generate() {
        DHKeyPair kp;

        EVP_PKEY *pkey = nullptr;
        EVP_PKEY_CTX *ctx = EVP_PKEY_CTX_new_id(EVP_PKEY_X25519, nullptr);

        if (!ctx || EVP_PKEY_keygen_init(ctx) <= 0 || EVP_PKEY_keygen(ctx, &pkey) <= 0) {
            if (ctx) EVP_PKEY_CTX_free(ctx);
            FUTON_LOGE("Failed to generate X25519 key pair");
            return kp;
        }

        size_t len = KEY_SIZE;
        EVP_PKEY_get_raw_public_key(pkey, kp.public_key.data(), &len);
        EVP_PKEY_get_raw_private_key(pkey, kp.private_key.data(), &len);

        EVP_PKEY_free(pkey);
        EVP_PKEY_CTX_free(ctx);

        return kp;
    }

    std::vector<uint8_t> DHKeyPair::dh(const DHPrivateKey &priv, const DHPublicKey &pub) {
        std::vector<uint8_t> shared(KEY_SIZE);

        EVP_PKEY *priv_key = EVP_PKEY_new_raw_private_key(EVP_PKEY_X25519, nullptr,
                                                          priv.data(), priv.size());
        EVP_PKEY *pub_key = EVP_PKEY_new_raw_public_key(EVP_PKEY_X25519, nullptr,
                                                        pub.data(), pub.size());

        if (!priv_key || !pub_key) {
            if (priv_key) EVP_PKEY_free(priv_key);
            if (pub_key) EVP_PKEY_free(pub_key);
            return {};
        }

        EVP_PKEY_CTX *ctx = EVP_PKEY_CTX_new(priv_key, nullptr);
        size_t len = KEY_SIZE;

        if (!ctx || EVP_PKEY_derive_init(ctx) <= 0 ||
            EVP_PKEY_derive_set_peer(ctx, pub_key) <= 0 ||
            EVP_PKEY_derive(ctx, shared.data(), &len) <= 0) {
            shared.clear();
        }

        if (ctx) EVP_PKEY_CTX_free(ctx);
        EVP_PKEY_free(priv_key);
        EVP_PKEY_free(pub_key);

        return shared;
    }

// RatchetState cleanup
    void RatchetState::clear_sensitive() {
        secure_zero(dh_self.private_key.data(), dh_self.private_key.size());
        secure_zero(root_key.data(), root_key.size());
        if (chain_key_send) secure_zero(chain_key_send->data(), chain_key_send->size());
        if (chain_key_recv) secure_zero(chain_key_recv->data(), chain_key_recv->size());
        secure_zero(session_master_key.data(), session_master_key.size());

        for (auto &sk: skipped_keys) {
            secure_zero(sk.message_key.data(), sk.message_key.size());
        }
        skipped_keys.clear();
        received_messages.clear();
    }

// DoubleRatchet implementation
    DoubleRatchet::DoubleRatchet() : state_(std::make_unique<RatchetState>()) {}

    DoubleRatchet::~DoubleRatchet() {
        if (state_) {
            state_->clear_sensitive();
        }
    }

    KdfOutput DoubleRatchet::kdf_rk(const Key &rk, const std::vector<uint8_t> &dh_out) {
        KdfOutput out;

        auto derived = hkdf_sha256(
                rk.data(), rk.size(),
                dh_out.data(), dh_out.size(),
                reinterpret_cast<const uint8_t *>(HKDF_INFO_RK), strlen(HKDF_INFO_RK),
                KEY_SIZE * 2
        );

        if (derived.size() == KEY_SIZE * 2) {
            std::copy_n(derived.begin(), KEY_SIZE, out.key1.begin());
            std::copy_n(derived.begin() + KEY_SIZE, KEY_SIZE, out.key2.begin());
        }

        secure_zero(derived.data(), derived.size());
        return out;
    }

    KdfOutput DoubleRatchet::kdf_ck(const Key &ck) {
        KdfOutput out;

        // Use HMAC-based KDF for chain key derivation
        auto derived = hkdf_sha256(
                ck.data(), ck.size(),
                reinterpret_cast<const uint8_t *>(HKDF_INFO_CK), strlen(HKDF_INFO_CK),
                nullptr, 0,
                KEY_SIZE * 2
        );

        if (derived.size() == KEY_SIZE * 2) {
            std::copy_n(derived.begin(), KEY_SIZE, out.key1.begin());           // New chain key
            std::copy_n(derived.begin() + KEY_SIZE, KEY_SIZE, out.key2.begin()); // Message key
        }

        secure_zero(derived.data(), derived.size());
        return out;
    }

    void DoubleRatchet::derive_session_master_key() {
        if (!state_->chain_key_send) return;

        auto derived = hkdf_sha256(
                state_->root_key.data(), state_->root_key.size(),
                state_->chain_key_send->data(), state_->chain_key_send->size(),
                reinterpret_cast<const uint8_t *>(HKDF_INFO_SMK), strlen(HKDF_INFO_SMK),
                KEY_SIZE
        );

        if (derived.size() == KEY_SIZE) {
            std::copy_n(derived.begin(), KEY_SIZE, state_->session_master_key.begin());
            state_->session_key_generation++;
        }

        secure_zero(derived.data(), derived.size());
    }

    bool DoubleRatchet::init_alice(const std::vector<uint8_t> &shared_secret,
                                   const DHPublicKey &bob_public) {
        std::lock_guard<std::mutex> lock(mutex_);

        if (shared_secret.size() < KEY_SIZE) {
            FUTON_LOGE("Shared secret too short");
            return false;
        }

        state_->clear_sensitive();

        // Generate our DH key pair
        state_->dh_self = DHKeyPair::generate();
        state_->dh_remote = bob_public;

        // Initialize root key from shared secret
        std::copy_n(shared_secret.begin(), KEY_SIZE, state_->root_key.begin());

        // Perform initial DH ratchet
        auto dh_out = DHKeyPair::dh(state_->dh_self.private_key, bob_public);
        if (dh_out.empty()) {
            FUTON_LOGE("Initial DH failed");
            return false;
        }

        auto kdf_out = kdf_rk(state_->root_key, dh_out);
        state_->root_key = kdf_out.key1;
        state_->chain_key_send = kdf_out.key2;

        secure_zero(dh_out.data(), dh_out.size());

        // Derive initial session master key
        derive_session_master_key();

        state_->send_count = 0;
        state_->recv_count = 0;
        state_->prev_send_count = 0;

        initialized_ = true;
        ratchet_steps_++;

        FUTON_LOGI("DoubleRatchet initialized as Alice");
        return true;
    }

    bool DoubleRatchet::init_bob(const std::vector<uint8_t> &shared_secret,
                                 const DHKeyPair &bob_keypair) {
        std::lock_guard<std::mutex> lock(mutex_);

        if (shared_secret.size() < KEY_SIZE) {
            FUTON_LOGE("Shared secret too short");
            return false;
        }

        state_->clear_sensitive();

        // Use provided key pair
        state_->dh_self = bob_keypair;
        state_->dh_remote = std::nullopt;

        // Initialize root key from shared secret
        std::copy_n(shared_secret.begin(), KEY_SIZE, state_->root_key.begin());

        state_->send_count = 0;
        state_->recv_count = 0;
        state_->prev_send_count = 0;

        initialized_ = true;

        FUTON_LOGI("DoubleRatchet initialized as Bob");
        return true;
    }

    void DoubleRatchet::dh_ratchet(const DHPublicKey &remote_public) {
        // Store previous send count
        state_->prev_send_count = state_->send_count;
        state_->send_count = 0;
        state_->recv_count = 0;

        state_->dh_remote = remote_public;

        // DH with our current private key and their new public key
        auto dh_out = DHKeyPair::dh(state_->dh_self.private_key, remote_public);
        auto kdf_out = kdf_rk(state_->root_key, dh_out);
        state_->root_key = kdf_out.key1;
        state_->chain_key_recv = kdf_out.key2;

        secure_zero(dh_out.data(), dh_out.size());

        // Generate new DH key pair
        state_->dh_self = DHKeyPair::generate();

        // DH with our new private key and their public key
        dh_out = DHKeyPair::dh(state_->dh_self.private_key, remote_public);
        kdf_out = kdf_rk(state_->root_key, dh_out);
        state_->root_key = kdf_out.key1;
        state_->chain_key_send = kdf_out.key2;

        secure_zero(dh_out.data(), dh_out.size());

        // Derive new session master key
        derive_session_master_key();

        ratchet_steps_++;
    }

    std::vector<uint8_t> DoubleRatchet::aead_encrypt(
            const Key &key,
            const std::vector<uint8_t> &plaintext,
            const std::vector<uint8_t> &ad
    ) {
        std::vector<uint8_t> ciphertext(plaintext.size() + TAG_SIZE + NONCE_SIZE);

        // Generate random nonce
        if (RAND_bytes(ciphertext.data(), NONCE_SIZE) != 1) {
            return {};
        }

        EVP_CIPHER_CTX *ctx = EVP_CIPHER_CTX_new();
        if (!ctx) return {};

        int len = 0;
        int ciphertext_len = 0;

        if (EVP_EncryptInit_ex(ctx, EVP_aes_256_gcm(), nullptr, nullptr, nullptr) != 1 ||
            EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_IVLEN, NONCE_SIZE, nullptr) != 1 ||
            EVP_EncryptInit_ex(ctx, nullptr, nullptr, key.data(), ciphertext.data()) != 1) {
            EVP_CIPHER_CTX_free(ctx);
            return {};
        }

        // Add AAD
        if (!ad.empty()) {
            if (EVP_EncryptUpdate(ctx, nullptr, &len, ad.data(), ad.size()) != 1) {
                EVP_CIPHER_CTX_free(ctx);
                return {};
            }
        }

        // Encrypt
        if (EVP_EncryptUpdate(ctx, ciphertext.data() + NONCE_SIZE, &len,
                              plaintext.data(), plaintext.size()) != 1) {
            EVP_CIPHER_CTX_free(ctx);
            return {};
        }
        ciphertext_len = len;

        if (EVP_EncryptFinal_ex(ctx, ciphertext.data() + NONCE_SIZE + len, &len) != 1) {
            EVP_CIPHER_CTX_free(ctx);
            return {};
        }
        ciphertext_len += len;

        // Get tag
        if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_GET_TAG, TAG_SIZE,
                                ciphertext.data() + NONCE_SIZE + ciphertext_len) != 1) {
            EVP_CIPHER_CTX_free(ctx);
            return {};
        }

        EVP_CIPHER_CTX_free(ctx);
        ciphertext.resize(NONCE_SIZE + ciphertext_len + TAG_SIZE);
        return ciphertext;
    }

    std::optional<std::vector<uint8_t>> DoubleRatchet::aead_decrypt(
            const Key &key,
            const std::vector<uint8_t> &ciphertext,
            const std::vector<uint8_t> &ad
    ) {
        if (ciphertext.size() < NONCE_SIZE + TAG_SIZE) {
            return std::nullopt;
        }

        size_t ct_len = ciphertext.size() - NONCE_SIZE - TAG_SIZE;
        std::vector<uint8_t> plaintext(ct_len);

        EVP_CIPHER_CTX *ctx = EVP_CIPHER_CTX_new();
        if (!ctx) return std::nullopt;

        int len = 0;
        int plaintext_len = 0;

        if (EVP_DecryptInit_ex(ctx, EVP_aes_256_gcm(), nullptr, nullptr, nullptr) != 1 ||
            EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_IVLEN, NONCE_SIZE, nullptr) != 1 ||
            EVP_DecryptInit_ex(ctx, nullptr, nullptr, key.data(), ciphertext.data()) != 1) {
            EVP_CIPHER_CTX_free(ctx);
            return std::nullopt;
        }

        // Add AAD
        if (!ad.empty()) {
            if (EVP_DecryptUpdate(ctx, nullptr, &len, ad.data(), ad.size()) != 1) {
                EVP_CIPHER_CTX_free(ctx);
                return std::nullopt;
            }
        }

        // Decrypt
        if (EVP_DecryptUpdate(ctx, plaintext.data(), &len,
                              ciphertext.data() + NONCE_SIZE, ct_len) != 1) {
            EVP_CIPHER_CTX_free(ctx);
            return std::nullopt;
        }
        plaintext_len = len;

        // Set expected tag
        if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_TAG, TAG_SIZE,
                                const_cast<uint8_t *>(ciphertext.data() + NONCE_SIZE + ct_len)) !=
            1) {
            EVP_CIPHER_CTX_free(ctx);
            return std::nullopt;
        }

        // Verify tag
        if (EVP_DecryptFinal_ex(ctx, plaintext.data() + len, &len) != 1) {
            EVP_CIPHER_CTX_free(ctx);
            return std::nullopt;  // Authentication failed
        }
        plaintext_len += len;

        EVP_CIPHER_CTX_free(ctx);
        plaintext.resize(plaintext_len);
        return plaintext;
    }

    void DoubleRatchet::skip_message_keys(uint32_t until) {
        if (!state_->chain_key_recv) return;

        if (state_->recv_count + MAX_SKIP < until) {
            FUTON_LOGW("Too many skipped messages: %u", until - state_->recv_count);
            return;
        }

        while (state_->recv_count < until) {
            auto kdf_out = kdf_ck(*state_->chain_key_recv);

            SkippedKey sk;
            sk.dh_public = *state_->dh_remote;
            sk.message_num = state_->recv_count;
            sk.message_key = kdf_out.key2;

            state_->skipped_keys.push_back(sk);
            state_->chain_key_recv = kdf_out.key1;
            state_->recv_count++;

            // Limit skipped keys storage
            if (state_->skipped_keys.size() > MAX_SKIP) {
                secure_zero(state_->skipped_keys.front().message_key.data(), KEY_SIZE);
                state_->skipped_keys.erase(state_->skipped_keys.begin());
            }
        }
    }

    std::optional<std::vector<uint8_t>> DoubleRatchet::try_skipped_keys(
            const MessageHeader &header,
            const std::vector<uint8_t> &ciphertext
    ) {
        for (auto it = state_->skipped_keys.begin(); it != state_->skipped_keys.end(); ++it) {
            if (it->dh_public == header.dh_public && it->message_num == header.message_num) {
                auto ad = header.serialize();
                auto plaintext = aead_decrypt(it->message_key, ciphertext, ad);

                secure_zero(it->message_key.data(), KEY_SIZE);
                state_->skipped_keys.erase(it);

                return plaintext;
            }
        }
        return std::nullopt;
    }

    std::optional<EncryptedMessage> DoubleRatchet::encrypt(const std::vector<uint8_t> &plaintext) {
        std::lock_guard<std::mutex> lock(mutex_);

        if (!initialized_ || !state_->chain_key_send) {
            FUTON_LOGE("DoubleRatchet not initialized for sending");
            return std::nullopt;
        }

        // Derive message key from chain key
        auto kdf_out = kdf_ck(*state_->chain_key_send);
        state_->chain_key_send = kdf_out.key1;
        Key message_key = kdf_out.key2;

        // Build header
        EncryptedMessage msg;
        msg.header.dh_public = state_->dh_self.public_key;
        msg.header.prev_chain_len = state_->prev_send_count;
        msg.header.message_num = state_->send_count;

        // Encrypt with message key
        auto ad = msg.header.serialize();
        msg.ciphertext = aead_encrypt(message_key, plaintext, ad);

        // Clear message key immediately
        secure_zero(message_key.data(), message_key.size());

        if (msg.ciphertext.empty()) {
            FUTON_LOGE("AEAD encryption failed");
            return std::nullopt;
        }

        state_->send_count++;
        messages_sent_++;

        return msg;
    }

    std::optional<std::vector<uint8_t>> DoubleRatchet::decrypt(const EncryptedMessage &message) {
        std::lock_guard<std::mutex> lock(mutex_);

        if (!initialized_) {
            FUTON_LOGE("DoubleRatchet not initialized");
            return std::nullopt;
        }

        // Anti-replay check: reject if we've seen this exact (dhPublic, messageNum) before
        auto dh_key_str = RatchetState::dh_key_to_string(message.header.dh_public);
        auto &seen_messages = state_->received_messages[dh_key_str];
        if (seen_messages.count(message.header.message_num) > 0) {
            FUTON_LOGW("Replay attack detected: message %u already received",
                       message.header.message_num);
            return std::nullopt;
        }

        // Try skipped message keys first
        auto skipped_result = try_skipped_keys(message.header, message.ciphertext);
        if (skipped_result) {
            seen_messages.insert(message.header.message_num);
            messages_received_++;
            return skipped_result;
        }

        // Check if we need to perform DH ratchet
        if (!state_->dh_remote || message.header.dh_public != *state_->dh_remote) {
            // Skip any remaining messages in current receiving chain
            if (state_->chain_key_recv && state_->dh_remote) {
                skip_message_keys(message.header.prev_chain_len);
            }

            // Perform DH ratchet
            dh_ratchet(message.header.dh_public);

            // Clear old received messages for previous DH keys (they're now invalid)
            for (auto it = state_->received_messages.begin();
                 it != state_->received_messages.end();) {
                if (it->first != dh_key_str) {
                    it = state_->received_messages.erase(it);
                } else {
                    ++it;
                }
            }
        }

        // Skip messages if needed
        skip_message_keys(message.header.message_num);

        if (!state_->chain_key_recv) {
            FUTON_LOGE("No receiving chain key");
            return std::nullopt;
        }

        // Derive message key
        auto kdf_out = kdf_ck(*state_->chain_key_recv);
        state_->chain_key_recv = kdf_out.key1;
        Key message_key = kdf_out.key2;

        // Decrypt
        auto ad = message.header.serialize();
        auto plaintext = aead_decrypt(message_key, message.ciphertext, ad);

        // Clear message key immediately (forward secrecy)
        secure_zero(message_key.data(), message_key.size());

        if (!plaintext) {
            FUTON_LOGE("AEAD decryption failed - message tampered or wrong key");
            return std::nullopt;
        }

        // Mark message as received (anti-replay)
        seen_messages.insert(message.header.message_num);
        state_->recv_count++;
        messages_received_++;

        return plaintext;
    }

    Key DoubleRatchet::get_session_master_key() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return state_->session_master_key;
    }

    uint64_t DoubleRatchet::get_session_key_generation() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return state_->session_key_generation;
    }

    bool DoubleRatchet::force_ratchet_step() {
        std::lock_guard<std::mutex> lock(mutex_);

        if (!initialized_ || !state_->dh_remote) {
            return false;
        }

        // Generate new DH key pair and derive new keys
        auto old_public = state_->dh_self.public_key;
        state_->dh_self = DHKeyPair::generate();

        auto dh_out = DHKeyPair::dh(state_->dh_self.private_key, *state_->dh_remote);
        if (dh_out.empty()) {
            return false;
        }

        auto kdf_out = kdf_rk(state_->root_key, dh_out);
        state_->root_key = kdf_out.key1;
        state_->chain_key_send = kdf_out.key2;

        secure_zero(dh_out.data(), dh_out.size());

        state_->prev_send_count = state_->send_count;
        state_->send_count = 0;

        derive_session_master_key();
        ratchet_steps_++;

        FUTON_LOGI("Forced ratchet step, generation: %lu", state_->session_key_generation);
        return true;
    }

    bool DoubleRatchet::is_initialized() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return initialized_;
    }

    DHPublicKey DoubleRatchet::get_public_key() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return state_->dh_self.public_key;
    }

    DoubleRatchet::Stats DoubleRatchet::get_stats() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return Stats{
                messages_sent_,
                messages_received_,
                ratchet_steps_,
                state_->skipped_keys.size()
        };
    }

} // namespace futon::core::crypto
