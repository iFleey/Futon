/*
 * Futon - Android Automation Daemon
 * Copyright (C) 2025 Fleey
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#ifndef FUTON_CORE_CRYPTO_DOUBLE_RATCHET_H
#define FUTON_CORE_CRYPTO_DOUBLE_RATCHET_H

#include <array>
#include <vector>
#include <string>
#include <optional>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <set>
#include <cstdint>

namespace futon::core::crypto {

// Key sizes
    constexpr size_t KEY_SIZE = 32;           // 256 bits
    constexpr size_t NONCE_SIZE = 12;         // 96 bits for AES-GCM
    constexpr size_t TAG_SIZE = 16;           // 128 bits for AES-GCM
    constexpr size_t DH_PUBLIC_KEY_SIZE = 32; // X25519
    constexpr size_t MAX_SKIP = 1000;         // Max skipped messages per chain

    using Key = std::array<uint8_t, KEY_SIZE>;
    using Nonce = std::array<uint8_t, NONCE_SIZE>;
    using DHPublicKey = std::array<uint8_t, DH_PUBLIC_KEY_SIZE>;
    using DHPrivateKey = std::array<uint8_t, KEY_SIZE>;

// Message header for Double Ratchet
    struct MessageHeader {
        DHPublicKey dh_public;     // Sender's current DH public key
        uint32_t prev_chain_len;   // Number of messages in previous sending chain
        uint32_t message_num;      // Message number in current sending chain

        std::vector<uint8_t> serialize() const;

        static std::optional<MessageHeader> deserialize(const std::vector<uint8_t> &data);
    };

// Encrypted message structure
    struct EncryptedMessage {
        MessageHeader header;
        std::vector<uint8_t> ciphertext;  // Includes AES-GCM tag

        std::vector<uint8_t> serialize() const;

        static std::optional<EncryptedMessage> deserialize(const std::vector<uint8_t> &data);
    };

// Skipped message key (for out-of-order messages)
    struct SkippedKey {
        DHPublicKey dh_public;
        uint32_t message_num;
        Key message_key;
    };

// DH key pair
    struct DHKeyPair {
        DHPublicKey public_key;
        DHPrivateKey private_key;

        static DHKeyPair generate();

        static std::vector<uint8_t> dh(const DHPrivateKey &priv, const DHPublicKey &pub);
    };

// Ratchet state
    struct RatchetState {
        // DH Ratchet
        DHKeyPair dh_self;
        std::optional<DHPublicKey> dh_remote;

        // Root key
        Key root_key;

        // Chain keys
        std::optional<Key> chain_key_send;
        std::optional<Key> chain_key_recv;

        // Message counters
        uint32_t send_count = 0;
        uint32_t recv_count = 0;
        uint32_t prev_send_count = 0;

        // Skipped message keys (for out-of-order delivery)
        std::vector<SkippedKey> skipped_keys;

        // Anti-replay: track received message numbers per DH public key
        std::unordered_map<std::string, std::set<uint32_t>> received_messages;

        // Session master key for data channel
        Key session_master_key;
        uint64_t session_key_generation = 0;

        void clear_sensitive();

        // Helper to convert DHPublicKey to string for map key
        static std::string dh_key_to_string(const DHPublicKey &key) {
            return std::string(reinterpret_cast<const char *>(key.data()), key.size());
        }
    };

// Key Derivation Function outputs
    struct KdfOutput {
        Key key1;
        Key key2;
    };

// Double Ratchet Protocol Implementation
// Based on Signal Protocol specification
    class DoubleRatchet {
    public:
        DoubleRatchet();

        ~DoubleRatchet();

        // Disable copy (sensitive key material)
        DoubleRatchet(const DoubleRatchet &) = delete;

        DoubleRatchet &operator=(const DoubleRatchet &) = delete;

        // Initialize as Alice (initiator)
        // shared_secret: from initial key agreement (e.g., X3DH)
        // bob_public: Bob's signed pre-key
        bool init_alice(const std::vector<uint8_t> &shared_secret,
                        const DHPublicKey &bob_public);

        // Initialize as Bob (responder)
        // shared_secret: from initial key agreement
        // bob_keypair: Bob's signed pre-key pair
        bool init_bob(const std::vector<uint8_t> &shared_secret,
                      const DHKeyPair &bob_keypair);

        // Encrypt a message (control channel)
        std::optional<EncryptedMessage> encrypt(const std::vector<uint8_t> &plaintext);

        // Decrypt a message (control channel)
        std::optional<std::vector<uint8_t>> decrypt(const EncryptedMessage &message);

        // Get current session master key for data channel
        // This key is derived from the chain key and rotates with DH ratchet
        Key get_session_master_key() const;

        // Get session key generation (increments on each DH ratchet step)
        uint64_t get_session_key_generation() const;

        // Force a DH ratchet step (for key rotation)
        bool force_ratchet_step();

        // Check if initialized
        bool is_initialized() const;

        // Get our current DH public key
        DHPublicKey get_public_key() const;

        // Get statistics
        struct Stats {
            uint64_t messages_sent;
            uint64_t messages_received;
            uint64_t ratchet_steps;
            uint64_t skipped_keys_count;
        };

        Stats get_stats() const;

    private:
        mutable std::mutex mutex_;
        std::unique_ptr<RatchetState> state_;
        bool initialized_ = false;

        // Statistics
        uint64_t messages_sent_ = 0;
        uint64_t messages_received_ = 0;
        uint64_t ratchet_steps_ = 0;

        // KDF functions
        static KdfOutput kdf_rk(const Key &rk, const std::vector<uint8_t> &dh_out);

        static KdfOutput kdf_ck(const Key &ck);

        // Derive session master key from current state
        void derive_session_master_key();

        // Perform DH ratchet step
        void dh_ratchet(const DHPublicKey &remote_public);

        // Skip message keys (for out-of-order messages)
        void skip_message_keys(uint32_t until);

        // Try to decrypt with skipped keys
        std::optional<std::vector<uint8_t>> try_skipped_keys(
                const MessageHeader &header,
                const std::vector<uint8_t> &ciphertext);

        // AEAD encrypt/decrypt
        static std::vector<uint8_t> aead_encrypt(
                const Key &key,
                const std::vector<uint8_t> &plaintext,
                const std::vector<uint8_t> &ad);

        static std::optional<std::vector<uint8_t>> aead_decrypt(
                const Key &key,
                const std::vector<uint8_t> &ciphertext,
                const std::vector<uint8_t> &ad);
    };

} // namespace futon::core::crypto

#endif // FUTON_CORE_CRYPTO_DOUBLE_RATCHET_H
