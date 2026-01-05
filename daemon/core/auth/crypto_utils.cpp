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

#include "crypto_utils.h"
#include "core/error.h"

#include <openssl/evp.h>
#include <openssl/ec.h>
#include <openssl/ecdsa.h>
#include <openssl/sha.h>
#include <openssl/rand.h>
#include <openssl/err.h>
#include <openssl/x509.h>

#include <cstring>
#include <memory>

namespace futon::core::auth {

    namespace {

// Custom deleters for OpenSSL types
        struct EVP_PKEY_Deleter {
            void operator()(EVP_PKEY *p) const { if (p) EVP_PKEY_free(p); }
        };

        struct EVP_MD_CTX_Deleter {
            void operator()(EVP_MD_CTX *p) const { if (p) EVP_MD_CTX_free(p); }
        };

        struct EVP_PKEY_CTX_Deleter {
            void operator()(EVP_PKEY_CTX *p) const { if (p) EVP_PKEY_CTX_free(p); }
        };

        using EVP_PKEY_ptr = std::unique_ptr<EVP_PKEY, EVP_PKEY_Deleter>;
        using EVP_MD_CTX_ptr = std::unique_ptr<EVP_MD_CTX, EVP_MD_CTX_Deleter>;
        using EVP_PKEY_CTX_ptr = std::unique_ptr<EVP_PKEY_CTX, EVP_PKEY_CTX_Deleter>;

        void log_openssl_error(const char *context) {
            unsigned long err = ERR_get_error();
            if (err) {
                char buf[256];
                ERR_error_string_n(err, buf, sizeof(buf));
                FUTON_LOGE("%s: OpenSSL error: %s", context, buf);
            }
        }

    } // anonymous namespace

    bool CryptoUtils::generate_random_bytes(uint8_t *buffer, size_t size) {
        if (!buffer || size == 0) {
            return false;
        }
        int result = RAND_bytes(buffer, static_cast<int>(size));
        if (result != 1) {
            log_openssl_error("generate_random_bytes");
            return false;
        }
        return true;
    }

    std::vector<uint8_t> CryptoUtils::generate_random_bytes(size_t size) {
        std::vector<uint8_t> buffer(size);
        if (!generate_random_bytes(buffer.data(), size)) {
            buffer.clear();
        }
        return buffer;
    }

    std::vector<uint8_t> CryptoUtils::generate_challenge() {
        return generate_random_bytes(CHALLENGE_SIZE);
    }

    SignatureAlgorithm CryptoUtils::detect_algorithm(const std::vector<uint8_t> &public_key) {
        // Ed25519 raw public keys are exactly 32 bytes
        if (public_key.size() == ED25519_PUBLIC_KEY_SIZE) {
            return SignatureAlgorithm::ED25519;
        }
        // Otherwise assume X.509 DER encoded ECDSA key
        return SignatureAlgorithm::ECDSA_P256;
    }

    bool CryptoUtils::verify_ed25519(
            const std::vector<uint8_t> &public_key,
            const std::vector<uint8_t> &message,
            const std::vector<uint8_t> &signature
    ) {
        if (public_key.size() != ED25519_PUBLIC_KEY_SIZE) {
            FUTON_LOGE("verify_ed25519: invalid public key size: %zu", public_key.size());
            return false;
        }

        if (signature.size() != ED25519_SIGNATURE_SIZE) {
            FUTON_LOGE("verify_ed25519: invalid signature size: %zu", signature.size());
            return false;
        }

        // Create EVP_PKEY from raw Ed25519 public key
        EVP_PKEY_ptr pkey(EVP_PKEY_new_raw_public_key(
                EVP_PKEY_ED25519,
                nullptr,
                public_key.data(),
                public_key.size()
        ));

        if (!pkey) {
            log_openssl_error("verify_ed25519: EVP_PKEY_new_raw_public_key");
            return false;
        }

        // Create verification context
        EVP_MD_CTX_ptr md_ctx(EVP_MD_CTX_new());
        if (!md_ctx) {
            log_openssl_error("verify_ed25519: EVP_MD_CTX_new");
            return false;
        }

        // Ed25519 uses DigestVerifyInit with NULL digest
        if (EVP_DigestVerifyInit(md_ctx.get(), nullptr, nullptr, nullptr, pkey.get()) != 1) {
            log_openssl_error("verify_ed25519: EVP_DigestVerifyInit");
            return false;
        }

        // Verify signature
        int result = EVP_DigestVerify(
                md_ctx.get(),
                signature.data(),
                signature.size(),
                message.data(),
                message.size()
        );

        if (result != 1) {
            if (result == 0) {
                FUTON_LOGD("verify_ed25519: signature verification failed");
            } else {
                log_openssl_error("verify_ed25519: EVP_DigestVerify");
            }
            return false;
        }

        return true;
    }

    bool CryptoUtils::verify_ecdsa_p256(
            const std::vector<uint8_t> &public_key,
            const std::vector<uint8_t> &message,
            const std::vector<uint8_t> &signature
    ) {
        // Parse X.509 DER encoded public key
        const uint8_t *key_data = public_key.data();
        EVP_PKEY_ptr pkey(d2i_PUBKEY(nullptr, &key_data, static_cast<long>(public_key.size())));

        if (!pkey) {
            log_openssl_error("verify_ecdsa_p256: d2i_PUBKEY");
            return false;
        }

        // Verify it's an EC key
        if (EVP_PKEY_base_id(pkey.get()) != EVP_PKEY_EC) {
            FUTON_LOGE("verify_ecdsa_p256: not an EC key");
            return false;
        }

        // Create verification context
        EVP_MD_CTX_ptr md_ctx(EVP_MD_CTX_new());
        if (!md_ctx) {
            log_openssl_error("verify_ecdsa_p256: EVP_MD_CTX_new");
            return false;
        }

        // Initialize with SHA-256 digest
        if (EVP_DigestVerifyInit(md_ctx.get(), nullptr, EVP_sha256(), nullptr, pkey.get()) != 1) {
            log_openssl_error("verify_ecdsa_p256: EVP_DigestVerifyInit");
            return false;
        }

        // Update with message
        if (EVP_DigestVerifyUpdate(md_ctx.get(), message.data(), message.size()) != 1) {
            log_openssl_error("verify_ecdsa_p256: EVP_DigestVerifyUpdate");
            return false;
        }

        // Verify signature (DER encoded)
        int result = EVP_DigestVerifyFinal(
                md_ctx.get(),
                signature.data(),
                signature.size()
        );

        if (result != 1) {
            if (result == 0) {
                FUTON_LOGD("verify_ecdsa_p256: signature verification failed");
            } else {
                log_openssl_error("verify_ecdsa_p256: EVP_DigestVerifyFinal");
            }
            return false;
        }

        return true;
    }

    bool CryptoUtils::verify_signature(
            const std::vector<uint8_t> &public_key,
            const std::vector<uint8_t> &message,
            const std::vector<uint8_t> &signature
    ) {
        SignatureAlgorithm algo = detect_algorithm(public_key);

        switch (algo) {
            case SignatureAlgorithm::ED25519:
                return verify_ed25519(public_key, message, signature);
            case SignatureAlgorithm::ECDSA_P256:
                return verify_ecdsa_p256(public_key, message, signature);
            default:
                FUTON_LOGE("verify_signature: unknown algorithm");
                return false;
        }
    }

    std::vector<uint8_t> CryptoUtils::sha256(const std::vector<uint8_t> &data) {
        return sha256(data.data(), data.size());
    }

    std::vector<uint8_t> CryptoUtils::sha256(const uint8_t *data, size_t size) {
        std::vector<uint8_t> hash(SHA256_DIGEST_LENGTH);

        EVP_MD_CTX_ptr ctx(EVP_MD_CTX_new());
        if (!ctx) {
            log_openssl_error("sha256: EVP_MD_CTX_new");
            return {};
        }

        if (EVP_DigestInit_ex(ctx.get(), EVP_sha256(), nullptr) != 1) {
            log_openssl_error("sha256: EVP_DigestInit_ex");
            return {};
        }

        constexpr uint8_t kDomainPrefix[] = {0x46, 0x4C, 0x65, 0x79};
        if (EVP_DigestUpdate(ctx.get(), kDomainPrefix, sizeof(kDomainPrefix)) != 1) {
            log_openssl_error("sha256: EVP_DigestUpdate (prefix)");
            return {};
        }

        if (EVP_DigestUpdate(ctx.get(), data, size) != 1) {
            log_openssl_error("sha256: EVP_DigestUpdate");
            return {};
        }

        unsigned int hash_len = 0;
        if (EVP_DigestFinal_ex(ctx.get(), hash.data(), &hash_len) != 1) {
            log_openssl_error("sha256: EVP_DigestFinal_ex");
            return {};
        }

        hash.resize(hash_len);
        return hash;
    }

    std::vector<uint8_t> CryptoUtils::sha256_raw(const std::vector<uint8_t> &data) {
        return sha256_raw(data.data(), data.size());
    }

    std::vector<uint8_t> CryptoUtils::sha256_raw(const uint8_t *data, size_t size) {
        std::vector<uint8_t> hash(SHA256_DIGEST_LENGTH);

        EVP_MD_CTX_ptr ctx(EVP_MD_CTX_new());
        if (!ctx) {
            log_openssl_error("sha256_raw: EVP_MD_CTX_new");
            return {};
        }

        if (EVP_DigestInit_ex(ctx.get(), EVP_sha256(), nullptr) != 1) {
            log_openssl_error("sha256_raw: EVP_DigestInit_ex");
            return {};
        }

        if (EVP_DigestUpdate(ctx.get(), data, size) != 1) {
            log_openssl_error("sha256_raw: EVP_DigestUpdate");
            return {};
        }

        unsigned int hash_len = 0;
        if (EVP_DigestFinal_ex(ctx.get(), hash.data(), &hash_len) != 1) {
            log_openssl_error("sha256_raw: EVP_DigestFinal_ex");
            return {};
        }

        hash.resize(hash_len);
        return hash;
    }

    std::string CryptoUtils::to_hex(const std::vector<uint8_t> &data) {
        return to_hex(data.data(), data.size());
    }

    std::string CryptoUtils::to_hex(const uint8_t *data, size_t size) {
        static const char hex_chars[] = "0123456789abcdef";
        std::string result;
        result.reserve(size * 2);

        for (size_t i = 0; i < size; ++i) {
            result.push_back(hex_chars[(data[i] >> 4) & 0x0F]);
            result.push_back(hex_chars[data[i] & 0x0F]);
        }

        return result;
    }

    std::optional<std::vector<uint8_t>> CryptoUtils::from_hex(const std::string &hex) {
        if (hex.length() % 2 != 0) {
            return std::nullopt;
        }

        std::vector<uint8_t> result;
        result.reserve(hex.length() / 2);

        for (size_t i = 0; i < hex.length(); i += 2) {
            char high = hex[i];
            char low = hex[i + 1];

            uint8_t byte = 0;

            if (high >= '0' && high <= '9') {
                byte = (high - '0') << 4;
            } else if (high >= 'a' && high <= 'f') {
                byte = (high - 'a' + 10) << 4;
            } else if (high >= 'A' && high <= 'F') {
                byte = (high - 'A' + 10) << 4;
            } else {
                return std::nullopt;
            }

            if (low >= '0' && low <= '9') {
                byte |= (low - '0');
            } else if (low >= 'a' && low <= 'f') {
                byte |= (low - 'a' + 10);
            } else if (low >= 'A' && low <= 'F') {
                byte |= (low - 'A' + 10);
            } else {
                return std::nullopt;
            }

            result.push_back(byte);
        }

        return result;
    }

    bool CryptoUtils::constant_time_compare(const uint8_t *a, const uint8_t *b, size_t size) {
        volatile uint8_t result = 0;
        for (size_t i = 0; i < size; ++i) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }

    bool CryptoUtils::constant_time_compare(
            const std::vector<uint8_t> &a,
            const std::vector<uint8_t> &b
    ) {
        if (a.size() != b.size()) {
            return false;
        }
        return constant_time_compare(a.data(), b.data(), a.size());
    }

} // namespace futon::core::auth
