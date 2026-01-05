/*
 * Futon - Android Automation Daemon
 * Copyright (C) 2025 Fleey
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#include "stream_cipher.h"
#include "core/error.h"

#include <openssl/evp.h>
#include <openssl/rand.h>
#include <openssl/kdf.h>
#include <cstring>

namespace futon::core::crypto {

    namespace {

        constexpr const char *STREAM_KEY_INFO = "FutonStreamKey";

        void secure_zero(void *ptr, size_t size) {
            volatile uint8_t *p = static_cast<volatile uint8_t *>(ptr);
            while (size--) *p++ = 0;
        }

    } // anonymous namespace

// ChunkHeader implementation
    std::array<uint8_t, ChunkHeader::SIZE> ChunkHeader::serialize() const {
        std::array<uint8_t, SIZE> data{};

        // Little-endian encoding
        data[0] = key_generation & 0xFF;
        data[1] = (key_generation >> 8) & 0xFF;
        data[2] = (key_generation >> 16) & 0xFF;
        data[3] = (key_generation >> 24) & 0xFF;
        data[4] = (key_generation >> 32) & 0xFF;
        data[5] = (key_generation >> 40) & 0xFF;
        data[6] = (key_generation >> 48) & 0xFF;
        data[7] = (key_generation >> 56) & 0xFF;

        data[8] = chunk_index & 0xFF;
        data[9] = (chunk_index >> 8) & 0xFF;
        data[10] = (chunk_index >> 16) & 0xFF;
        data[11] = (chunk_index >> 24) & 0xFF;

        data[12] = chunk_size & 0xFF;
        data[13] = (chunk_size >> 8) & 0xFF;
        data[14] = (chunk_size >> 16) & 0xFF;
        data[15] = (chunk_size >> 24) & 0xFF;

        data[16] = flags & 0xFF;
        data[17] = (flags >> 8) & 0xFF;
        data[18] = (flags >> 16) & 0xFF;
        data[19] = (flags >> 24) & 0xFF;

        return data;
    }

    std::optional<ChunkHeader> ChunkHeader::deserialize(const uint8_t *data, size_t len) {
        if (len < SIZE) return std::nullopt;

        ChunkHeader header;
        header.key_generation =
                static_cast<uint64_t>(data[0]) |
                (static_cast<uint64_t>(data[1]) << 8) |
                (static_cast<uint64_t>(data[2]) << 16) |
                (static_cast<uint64_t>(data[3]) << 24) |
                (static_cast<uint64_t>(data[4]) << 32) |
                (static_cast<uint64_t>(data[5]) << 40) |
                (static_cast<uint64_t>(data[6]) << 48) |
                (static_cast<uint64_t>(data[7]) << 56);

        header.chunk_index = data[8] | (data[9] << 8) | (data[10] << 16) | (data[11] << 24);
        header.chunk_size = data[12] | (data[13] << 8) | (data[14] << 16) | (data[15] << 24);
        header.flags = data[16] | (data[17] << 8) | (data[18] << 16) | (data[19] << 24);

        return header;
    }

// StreamCipher implementation
    StreamCipher::StreamCipher(const StreamCipherConfig &config) : config_(config) {}

    StreamCipher::~StreamCipher() {
        if (current_key_) {
            secure_zero(current_key_->key.data(), current_key_->key.size());
        }
        if (previous_key_) {
            secure_zero(previous_key_->key.data(), previous_key_->key.size());
        }
    }

    Key StreamCipher::derive_stream_key(const Key &master_key, uint64_t generation) {
        Key stream_key{};

        // Use generation as salt
        std::array<uint8_t, 8> salt;
        for (int i = 0; i < 8; i++) {
            salt[i] = (generation >> (i * 8)) & 0xFF;
        }

        EVP_PKEY_CTX *ctx = EVP_PKEY_CTX_new_id(EVP_PKEY_HKDF, nullptr);
        if (!ctx) return stream_key;

        size_t out_len = KEY_SIZE;
        if (EVP_PKEY_derive_init(ctx) <= 0 ||
            EVP_PKEY_CTX_set_hkdf_md(ctx, EVP_sha256()) <= 0 ||
            EVP_PKEY_CTX_set1_hkdf_salt(ctx, salt.data(), salt.size()) <= 0 ||
            EVP_PKEY_CTX_set1_hkdf_key(ctx, master_key.data(), master_key.size()) <= 0 ||
            EVP_PKEY_CTX_add1_hkdf_info(ctx,
                                        reinterpret_cast<const uint8_t *>(STREAM_KEY_INFO),
                                        strlen(STREAM_KEY_INFO)) <= 0 ||
            EVP_PKEY_derive(ctx, stream_key.data(), &out_len) <= 0) {
            EVP_PKEY_CTX_free(ctx);
            return Key{};
        }

        EVP_PKEY_CTX_free(ctx);
        return stream_key;
    }

    bool StreamCipher::init(const Key &session_master_key, uint64_t generation) {
        std::lock_guard<std::mutex> lock(mutex_);

        current_key_ = std::make_unique<StreamKey>();
        current_key_->key = derive_stream_key(session_master_key, generation);
        current_key_->generation = generation;
        current_key_->created_at = std::chrono::steady_clock::now();
        current_key_->bytes_encrypted = 0;

        send_chunk_index_ = 0;

        FUTON_LOGI("StreamCipher initialized, generation: %lu", generation);
        return true;
    }

    bool StreamCipher::update_key(const Key &new_session_master_key, uint64_t generation) {
        std::lock_guard<std::mutex> lock(mutex_);

        // Move current to previous (for in-flight decryption)
        if (current_key_) {
            previous_key_ = std::move(current_key_);
        }

        current_key_ = std::make_unique<StreamKey>();
        current_key_->key = derive_stream_key(new_session_master_key, generation);
        current_key_->generation = generation;
        current_key_->created_at = std::chrono::steady_clock::now();
        current_key_->bytes_encrypted = 0;

        send_chunk_index_ = 0;
        rotations_++;

        if (rotation_callback_) {
            rotation_callback_(generation);
        }

        FUTON_LOGI("StreamCipher key updated, generation: %lu", generation);
        return true;
    }

    std::vector<uint8_t>
    StreamCipher::encrypt_chunk(const uint8_t *data, size_t size, uint32_t index) {
        // Output: [Header][Nonce][Ciphertext][Tag]
        std::vector<uint8_t> output;
        output.reserve(ChunkHeader::SIZE + NONCE_SIZE + size + TAG_SIZE);

        // Build header
        ChunkHeader header;
        header.key_generation = current_key_->generation;
        header.chunk_index = index;
        header.chunk_size = static_cast<uint32_t>(size);
        header.flags = 0;

        auto header_bytes = header.serialize();
        output.insert(output.end(), header_bytes.begin(), header_bytes.end());

        // Generate nonce
        std::array<uint8_t, NONCE_SIZE> nonce;
        if (RAND_bytes(nonce.data(), NONCE_SIZE) != 1) {
            return {};
        }
        output.insert(output.end(), nonce.begin(), nonce.end());

        // Encrypt with AES-256-GCM
        EVP_CIPHER_CTX *ctx = EVP_CIPHER_CTX_new();
        if (!ctx) return {};

        // Reserve space for ciphertext + tag
        size_t ct_start = output.size();
        output.resize(ct_start + size + TAG_SIZE);

        int len = 0;
        int ct_len = 0;

        if (EVP_EncryptInit_ex(ctx, EVP_aes_256_gcm(), nullptr, nullptr, nullptr) != 1 ||
            EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_IVLEN, NONCE_SIZE, nullptr) != 1 ||
            EVP_EncryptInit_ex(ctx, nullptr, nullptr, current_key_->key.data(), nonce.data()) !=
            1) {
            EVP_CIPHER_CTX_free(ctx);
            return {};
        }

        // Use header as AAD
        if (EVP_EncryptUpdate(ctx, nullptr, &len, header_bytes.data(), header_bytes.size()) != 1) {
            EVP_CIPHER_CTX_free(ctx);
            return {};
        }

        // Encrypt data
        if (EVP_EncryptUpdate(ctx, output.data() + ct_start, &len, data, size) != 1) {
            EVP_CIPHER_CTX_free(ctx);
            return {};
        }
        ct_len = len;

        if (EVP_EncryptFinal_ex(ctx, output.data() + ct_start + len, &len) != 1) {
            EVP_CIPHER_CTX_free(ctx);
            return {};
        }
        ct_len += len;

        // Get tag
        if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_GET_TAG, TAG_SIZE,
                                output.data() + ct_start + ct_len) != 1) {
            EVP_CIPHER_CTX_free(ctx);
            return {};
        }

        EVP_CIPHER_CTX_free(ctx);
        output.resize(ct_start + ct_len + TAG_SIZE);

        current_key_->bytes_encrypted += size;
        total_encrypted_ += size;

        return output;
    }

    std::vector<uint8_t> StreamCipher::encrypt(const uint8_t *data, size_t size) {
        std::lock_guard<std::mutex> lock(mutex_);

        if (!current_key_) {
            FUTON_LOGE("StreamCipher not initialized");
            return {};
        }

        std::vector<uint8_t> output;

        // Split into chunks
        size_t offset = 0;
        while (offset < size) {
            size_t chunk_size = std::min(config_.chunk_size, size - offset);
            auto chunk = encrypt_chunk(data + offset, chunk_size, send_chunk_index_++);

            if (chunk.empty()) {
                FUTON_LOGE("Failed to encrypt chunk");
                return {};
            }

            output.insert(output.end(), chunk.begin(), chunk.end());
            offset += chunk_size;
        }

        return output;
    }

    std::vector<uint8_t> StreamCipher::encrypt(const std::vector<uint8_t> &data) {
        return encrypt(data.data(), data.size());
    }

    std::optional<std::vector<uint8_t>> StreamCipher::decrypt_chunk(
            const ChunkHeader &header,
            const uint8_t *encrypted_data,
            size_t encrypted_size
    ) {
        // Find the right key
        StreamKey *key = nullptr;
        if (current_key_ && current_key_->generation == header.key_generation) {
            key = current_key_.get();
        } else if (previous_key_ && previous_key_->generation == header.key_generation) {
            key = previous_key_.get();
        }

        if (!key) {
            FUTON_LOGE("No key for generation %lu", header.key_generation);
            return std::nullopt;
        }

        // encrypted_data format: [Nonce][Ciphertext][Tag]
        if (encrypted_size < NONCE_SIZE + TAG_SIZE) {
            return std::nullopt;
        }

        const uint8_t *nonce = encrypted_data;
        const uint8_t *ciphertext = encrypted_data + NONCE_SIZE;
        size_t ct_len = encrypted_size - NONCE_SIZE - TAG_SIZE;
        const uint8_t *tag = encrypted_data + NONCE_SIZE + ct_len;

        std::vector<uint8_t> plaintext(ct_len);

        EVP_CIPHER_CTX *ctx = EVP_CIPHER_CTX_new();
        if (!ctx) return std::nullopt;

        int len = 0;
        int pt_len = 0;

        if (EVP_DecryptInit_ex(ctx, EVP_aes_256_gcm(), nullptr, nullptr, nullptr) != 1 ||
            EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_IVLEN, NONCE_SIZE, nullptr) != 1 ||
            EVP_DecryptInit_ex(ctx, nullptr, nullptr, key->key.data(), nonce) != 1) {
            EVP_CIPHER_CTX_free(ctx);
            return std::nullopt;
        }

        // Verify AAD (header)
        auto header_bytes = header.serialize();
        if (EVP_DecryptUpdate(ctx, nullptr, &len, header_bytes.data(), header_bytes.size()) != 1) {
            EVP_CIPHER_CTX_free(ctx);
            return std::nullopt;
        }

        // Decrypt
        if (EVP_DecryptUpdate(ctx, plaintext.data(), &len, ciphertext, ct_len) != 1) {
            EVP_CIPHER_CTX_free(ctx);
            return std::nullopt;
        }
        pt_len = len;

        // Set expected tag
        if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_TAG, TAG_SIZE,
                                const_cast<uint8_t *>(tag)) != 1) {
            EVP_CIPHER_CTX_free(ctx);
            return std::nullopt;
        }

        // Verify tag
        if (EVP_DecryptFinal_ex(ctx, plaintext.data() + len, &len) != 1) {
            EVP_CIPHER_CTX_free(ctx);
            FUTON_LOGE("Stream cipher authentication failed");
            return std::nullopt;
        }
        pt_len += len;

        EVP_CIPHER_CTX_free(ctx);
        plaintext.resize(pt_len);

        total_decrypted_ += pt_len;
        return plaintext;
    }

    std::optional<std::vector<uint8_t>> StreamCipher::decrypt(const uint8_t *data, size_t size) {
        std::lock_guard<std::mutex> lock(mutex_);

        if (!current_key_) {
            FUTON_LOGE("StreamCipher not initialized");
            return std::nullopt;
        }

        std::vector<uint8_t> output;
        size_t offset = 0;

        while (offset < size) {
            // Parse header
            if (offset + ChunkHeader::SIZE > size) {
                FUTON_LOGE("Incomplete chunk header");
                return std::nullopt;
            }

            auto header_opt = ChunkHeader::deserialize(data + offset, size - offset);
            if (!header_opt) {
                FUTON_LOGE("Invalid chunk header");
                return std::nullopt;
            }

            offset += ChunkHeader::SIZE;

            // Calculate encrypted chunk size
            size_t encrypted_chunk_size = NONCE_SIZE + header_opt->chunk_size + TAG_SIZE;
            if (offset + encrypted_chunk_size > size) {
                FUTON_LOGE("Incomplete encrypted chunk");
                return std::nullopt;
            }

            // Decrypt chunk
            auto chunk = decrypt_chunk(*header_opt, data + offset, encrypted_chunk_size);
            if (!chunk) {
                return std::nullopt;
            }

            output.insert(output.end(), chunk->begin(), chunk->end());
            offset += encrypted_chunk_size;
        }

        return output;
    }

    std::optional<std::vector<uint8_t>> StreamCipher::decrypt(const std::vector<uint8_t> &data) {
        return decrypt(data.data(), data.size());
    }

    bool StreamCipher::needs_rotation() const {
        std::lock_guard<std::mutex> lock(mutex_);

        if (!current_key_) return false;

        // Check bytes threshold
        if (current_key_->bytes_encrypted >= config_.rotation_bytes) {
            return true;
        }

        // Check time threshold
        auto elapsed = std::chrono::steady_clock::now() - current_key_->created_at;
        if (elapsed >= std::chrono::seconds(config_.rotation_seconds)) {
            return true;
        }

        return false;
    }

    uint64_t StreamCipher::get_key_generation() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return current_key_ ? current_key_->generation : 0;
    }

    size_t StreamCipher::get_bytes_encrypted() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return current_key_ ? current_key_->bytes_encrypted.load() : 0;
    }

    void StreamCipher::set_rotation_callback(KeyRotationCallback callback) {
        std::lock_guard<std::mutex> lock(mutex_);
        rotation_callback_ = std::move(callback);
    }

    StreamCipher::Stats StreamCipher::get_stats() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return Stats{
                total_encrypted_.load(),
                total_decrypted_.load(),
                rotations_.load(),
                current_key_ ? current_key_->generation : 0
        };
    }

// DualChannelCrypto implementation
    DualChannelCrypto::DualChannelCrypto()
            : control_channel_(std::make_unique<DoubleRatchet>()),
              data_channel_(std::make_unique<StreamCipher>()) {}

    DualChannelCrypto::~DualChannelCrypto() = default;

    bool DualChannelCrypto::init_initiator(const std::vector<uint8_t> &shared_secret,
                                           const DHPublicKey &responder_public) {
        std::lock_guard<std::mutex> lock(mutex_);

        if (!control_channel_->init_alice(shared_secret, responder_public)) {
            FUTON_LOGE("Failed to initialize control channel as initiator");
            return false;
        }

        sync_data_channel_key();

        FUTON_LOGI("DualChannelCrypto initialized as initiator");
        return true;
    }

    bool DualChannelCrypto::init_responder(const std::vector<uint8_t> &shared_secret,
                                           const DHKeyPair &our_keypair) {
        std::lock_guard<std::mutex> lock(mutex_);

        if (!control_channel_->init_bob(shared_secret, our_keypair)) {
            FUTON_LOGE("Failed to initialize control channel as responder");
            return false;
        }

        // Data channel will be initialized after first message exchange
        FUTON_LOGI("DualChannelCrypto initialized as responder");
        return true;
    }

    void DualChannelCrypto::sync_data_channel_key() {
        auto session_key = control_channel_->get_session_master_key();
        auto generation = control_channel_->get_session_key_generation();

        if (generation == 0) {
            // Not yet initialized
            return;
        }

        if (data_channel_->get_key_generation() < generation) {
            if (data_channel_->get_key_generation() == 0) {
                data_channel_->init(session_key, generation);
            } else {
                data_channel_->update_key(session_key, generation);
            }
        }
    }

    std::optional<std::vector<uint8_t>> DualChannelCrypto::encrypt_control(
            const std::vector<uint8_t> &data
    ) {
        std::lock_guard<std::mutex> lock(mutex_);

        auto encrypted = control_channel_->encrypt(data);
        if (!encrypted) {
            return std::nullopt;
        }

        // Sync data channel after control message
        sync_data_channel_key();

        return encrypted->serialize();
    }

    std::optional<std::vector<uint8_t>> DualChannelCrypto::decrypt_control(
            const std::vector<uint8_t> &data
    ) {
        std::lock_guard<std::mutex> lock(mutex_);

        auto msg = EncryptedMessage::deserialize(data);
        if (!msg) {
            FUTON_LOGE("Failed to deserialize control message");
            return std::nullopt;
        }

        auto decrypted = control_channel_->decrypt(*msg);
        if (!decrypted) {
            return std::nullopt;
        }

        // Sync data channel after receiving control message
        sync_data_channel_key();

        return decrypted;
    }

    std::vector<uint8_t> DualChannelCrypto::encrypt_data(const uint8_t *data, size_t size) {
        std::lock_guard<std::mutex> lock(mutex_);

        // Check if rotation needed before encrypting
        if (data_channel_->needs_rotation()) {
            FUTON_LOGI("Data channel key rotation triggered");
            control_channel_->force_ratchet_step();
            sync_data_channel_key();
        }

        return data_channel_->encrypt(data, size);
    }

    std::optional<std::vector<uint8_t>> DualChannelCrypto::decrypt_data(
            const uint8_t *data, size_t size
    ) {
        std::lock_guard<std::mutex> lock(mutex_);
        return data_channel_->decrypt(data, size);
    }

    bool DualChannelCrypto::rotate_keys() {
        std::lock_guard<std::mutex> lock(mutex_);

        if (!control_channel_->force_ratchet_step()) {
            return false;
        }

        sync_data_channel_key();
        return true;
    }

    bool DualChannelCrypto::data_channel_needs_rotation() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return data_channel_->needs_rotation();
    }

    DHPublicKey DualChannelCrypto::get_public_key() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return control_channel_->get_public_key();
    }

    bool DualChannelCrypto::is_initialized() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return control_channel_->is_initialized();
    }

    DualChannelCrypto::Stats DualChannelCrypto::get_stats() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return Stats{
                control_channel_->get_stats(),
                data_channel_->get_stats()
        };
    }

} // namespace futon::core::crypto
