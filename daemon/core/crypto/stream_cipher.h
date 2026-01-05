/*
 * Futon - Android Automation Daemon
 * Copyright (C) 2025 Fleey
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#ifndef FUTON_CORE_CRYPTO_STREAM_CIPHER_H
#define FUTON_CORE_CRYPTO_STREAM_CIPHER_H

#include "double_ratchet.h"
#include <atomic>
#include <chrono>
#include <functional>

namespace futon::core::crypto {

// Stream cipher configuration
    struct StreamCipherConfig {
        size_t rotation_bytes = 10 * 1024 * 1024;  // Rotate key every 10MB
        uint32_t rotation_seconds = 300;            // Rotate key every 5 minutes
        size_t chunk_size = 64 * 1024;              // 64KB chunks for large data
    };

// Stream key with metadata
    struct StreamKey {
        Key key;
        uint64_t generation;
        std::chrono::steady_clock::time_point created_at;
        std::atomic<size_t> bytes_encrypted{0};
    };

// Encrypted chunk header
    struct ChunkHeader {
        uint64_t key_generation;    // Which key generation was used
        uint32_t chunk_index;       // Index within the stream
        uint32_t chunk_size;        // Size of plaintext
        uint32_t flags;             // Reserved for future use

        static constexpr size_t SIZE = 20;

        std::array<uint8_t, SIZE> serialize() const;

        static std::optional<ChunkHeader> deserialize(const uint8_t *data, size_t len);
    };


// Callback for key rotation events
    using KeyRotationCallback = std::function<void(uint64_t new_generation)>;

// High-performance stream cipher for data channel
// Uses AES-256-GCM with key derived from Double Ratchet session master key
    class StreamCipher {
    public:
        explicit StreamCipher(const StreamCipherConfig &config = StreamCipherConfig());

        ~StreamCipher();

        // Disable copy
        StreamCipher(const StreamCipher &) = delete;

        StreamCipher &operator=(const StreamCipher &) = delete;

        // Initialize with session master key from Double Ratchet
        bool init(const Key &session_master_key, uint64_t generation);

        // Update key (called when Double Ratchet rotates)
        bool update_key(const Key &new_session_master_key, uint64_t generation);

        // Encrypt data (for sending)
        // Returns: [ChunkHeader][Nonce][Ciphertext][Tag] for each chunk
        std::vector<uint8_t> encrypt(const uint8_t *data, size_t size);

        std::vector<uint8_t> encrypt(const std::vector<uint8_t> &data);

        // Decrypt data (for receiving)
        std::optional<std::vector<uint8_t>> decrypt(const uint8_t *data, size_t size);

        std::optional<std::vector<uint8_t>> decrypt(const std::vector<uint8_t> &data);

        // Check if key rotation is needed
        bool needs_rotation() const;

        // Get current key generation
        uint64_t get_key_generation() const;

        // Get bytes encrypted with current key
        size_t get_bytes_encrypted() const;

        // Set callback for key rotation events
        void set_rotation_callback(KeyRotationCallback callback);

        // Statistics
        struct Stats {
            uint64_t total_bytes_encrypted;
            uint64_t total_bytes_decrypted;
            uint64_t key_rotations;
            uint64_t current_generation;
        };

        Stats get_stats() const;

    private:
        StreamCipherConfig config_;
        mutable std::mutex mutex_;

        std::unique_ptr<StreamKey> current_key_;
        std::unique_ptr<StreamKey> previous_key_;  // Keep for in-flight decryption

        std::atomic<uint64_t> total_encrypted_{0};
        std::atomic<uint64_t> total_decrypted_{0};
        std::atomic<uint64_t> rotations_{0};

        uint32_t send_chunk_index_ = 0;

        KeyRotationCallback rotation_callback_;

        // Derive stream key from session master key
        static Key derive_stream_key(const Key &master_key, uint64_t generation);

        // Encrypt single chunk
        std::vector<uint8_t> encrypt_chunk(const uint8_t *data, size_t size, uint32_t index);

        // Decrypt single chunk
        std::optional<std::vector<uint8_t>> decrypt_chunk(
                const ChunkHeader &header,
                const uint8_t *encrypted_data,
                size_t encrypted_size);
    };

// Dual-channel crypto manager
// Combines Double Ratchet (control) and Stream Cipher (data)
    class DualChannelCrypto {
    public:
        DualChannelCrypto();

        ~DualChannelCrypto();

        // Initialize as initiator (Alice)
        bool init_initiator(const std::vector<uint8_t> &shared_secret,
                            const DHPublicKey &responder_public);

        // Initialize as responder (Bob)
        bool init_responder(const std::vector<uint8_t> &shared_secret,
                            const DHKeyPair &our_keypair);

        // Control channel operations (Double Ratchet)
        std::optional<std::vector<uint8_t>> encrypt_control(const std::vector<uint8_t> &data);

        std::optional<std::vector<uint8_t>> decrypt_control(const std::vector<uint8_t> &data);

        // Data channel operations (Stream Cipher)
        std::vector<uint8_t> encrypt_data(const uint8_t *data, size_t size);

        std::optional<std::vector<uint8_t>> decrypt_data(const uint8_t *data, size_t size);

        // Force key rotation (triggers DH ratchet step)
        bool rotate_keys();

        // Check if data channel needs key rotation
        bool data_channel_needs_rotation() const;

        // Get our DH public key (for key exchange)
        DHPublicKey get_public_key() const;

        // Check initialization status
        bool is_initialized() const;

        // Get statistics
        struct Stats {
            DoubleRatchet::Stats control_stats;
            StreamCipher::Stats data_stats;
        };

        Stats get_stats() const;

    private:
        std::unique_ptr<DoubleRatchet> control_channel_;
        std::unique_ptr<StreamCipher> data_channel_;
        mutable std::mutex mutex_;

        // Sync data channel key with control channel
        void sync_data_channel_key();
    };

} // namespace futon::core::crypto

#endif // FUTON_CORE_CRYPTO_STREAM_CIPHER_H
