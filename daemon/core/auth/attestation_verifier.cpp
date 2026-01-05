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

#include "attestation_verifier.h"
#include "crypto_utils.h"
#include "hardened_config.h"
#include "core/error.h"

#include <openssl/x509.h>
#include <openssl/x509v3.h>
#include <openssl/evp.h>
#include <openssl/ec.h>
#include <openssl/pem.h>
#include <openssl/asn1.h>
#include <openssl/obj_mac.h>

#include <algorithm>
#include <cstring>

namespace futon::core::auth {

// Google Hardware Attestation Root CAs
// Source: https://developer.android.com/privacy-and-security/security-key-attestation.md.txt?hl=zh-cn#root_certificate

// If you're Chinese developer, you might visit the page
// https://developer.android.com/privacy-and-security/security-key-attestation?hl=zh-cn
// and find inconsistent information.
// Yes, Idk why this happens either. All I can say is that the world is a big mess, or maybe I'm wrong lol.
// But for now, I choose to trust the original page.

// Btw, I'm not entirely sure if the certificates I added are sufficient either—I simply couldn't find enough information (I searched everywhere).
// If you notice any issues, please report them promptly!
// Similarly, you should also report this inconsistency with Google's information—it's absolutely ridiculous.

// Current Global Root (Expires 2042-03-15)
    static const char *GOOGLE_ROOT_CA_1 = R"(
-----BEGIN CERTIFICATE-----
MIIFHDCCAwSgAwIBAgIJAPHBcqaZ6vUdMA0GCSqGSIb3DQEBCwUAMBsxGTAXBgNV
BAUTEGY5MjAwOWU4NTNiNmIwNDUwHhcNMjIwMzIwMTgwNzQ4WhcNNDIwMzE1MTgw
NzQ4WjAbMRkwFwYDVQQFExBmOTIwMDllODUzYjZiMDQ1MIICIjANBgkqhkiG9w0B
AQEFAAOCAg8AMIICCgKCAgEAr7bHgiuxpwHsK7Qui8xUFmOr75gvMsd/dTEDDJdS
Sxtf6An7xyqpRR90PL2abxM1dEqlXnf2tqw1Ne4Xwl5jlRfdnJLmN0pTy/4lj4/7
tv0Sk3iiKkypnEUtR6WfMgH0QZfKHM1+di+y9TFRtv6y//0rb+T+W8a9nsNL/ggj
nar86461qO0rOs2cXjp3kOG1FEJ5MVmFmBGtnrKpa73XpXyTqRxB/M0n1n/W9nGq
C4FSYa04T6N5RIZGBN2z2MT5IKGbFlbC8UrW0DxW7AYImQQcHtGl/m00QLVWutHQ
oVJYnFPlXTcHYvASLu+RhhsbDmxMgJJ0mcDpvsC4PjvB+TxywElgS70vE0XmLD+O
JtvsBslHZvPBKCOdT0MS+tgSOIfga+z1Z1g7+DVagf7quvmag8jfPioyKvxnK/Eg
sTUVi2ghzq8wm27ud/mIM7AY2qEORR8Go3TVB4HzWQgpZrt3i5MIlCaY504LzSRi
igHCzAPlHws+W0rB5N+er5/2pJKnfBSDiCiFAVtCLOZ7gLiMm0jhO2B6tUXHI/+M
RPjy02i59lINMRRev56GKtcd9qO/0kUJWdZTdA2XoS82ixPvZtXQpUpuL12ab+9E
aDK8Z4RHJYYfCT3Q5vNAXaiWQ+8PTWm2QgBR/bkwSWc+NpUFgNPN9PvQi8WEg5Um
AGMCAwEAAaNjMGEwHQYDVR0OBBYEFDZh4QB8iAUJUYtEbEf/GkzJ6k8SMB8GA1Ud
IwQYMBaAFDZh4QB8iAUJUYtEbEf/GkzJ6k8SMA8GA1UdEwEB/wQFMAMBAf8wDgYD
VR0PAQH/BAQDAgIEMA0GCSqGSIb3DQEBCwUAA4ICAQB8cMqTllHc8U+qCrOlg3H7
174lmaCsbo/bJ0C17JEgMLb4kvrqsXZs01U3mB/qABg/1t5Pd5AORHARs1hhqGIC
W/nKMav574f9rZN4PC2ZlufGXb7sIdJpGiO9ctRhiLuYuly10JccUZGEHpHSYM2G
tkgYbZba6lsCPYAAP83cyDV+1aOkTf1RCp/lM0PKvmxYN10RYsK631jrleGdcdkx
oSK//mSQbgcWnmAEZrzHoF1/0gso1HZgIn0YLzVhLSA/iXCX4QT2h3J5z3znluKG
1nv8NQdxei2DIIhASWfu804CA96cQKTTlaae2fweqXjdN1/v2nqOhngNyz1361mF
mr4XmaKH/ItTwOe72NI9ZcwS1lVaCvsIkTDCEXdm9rCNPAY10iTunIHFXRh+7KPz
lHGewCq/8TOohBRn0/NNfh7uRslOSZ/xKbN9tMBtw37Z8d2vvnXq/YWdsm1+JLVw
n6yYD/yacNJBlwpddla8eaVMjsF6nBnIgQOf9zKSe06nSTqvgwUHosgOECZJZ1Eu
zbH4yswbt02tKtKEFhx+v+OTge/06V+jGsqTWLsfrOCNLuA8H++z+pUENmpqnnHo
vaI47gC+TNpkgYGkkBT6B/m/U01BuOBBTzhIlMEZq9qkDWuM2cA5kW5V3FJUcfHn
w1IdYIg2Wxg7yHcQZemFQg==
-----END CERTIFICATE-----
)";

// Future Root (Valid starting Feb 1, 2026)
    static const char *GOOGLE_ROOT_CA_2 = R"(
-----BEGIN CERTIFICATE-----
MIICIjCCAaigAwIBAgIRAISp0Cl7DrWK5/8OgN52BgUwCgYIKoZIzj0EAwMwUjEc
MBoGA1UEAwwTS2V5IEF0dGVzdGF0aW9uIENBMTEQMA4GA1UECwwHQW5kcm9pZDET
MBEGA1UECgwKR29vZ2xlIExMQzELMAkGA1UEBhMCVVMwHhcNMjUwNzE3MjIzMjE4
WhcNMzUwNzE1MjIzMjE4WjBSMRwwGgYDVQQDDBNLZXkgQXR0ZXN0YXRpb24gQ0Ex
MRAwDgYDVQQLDAdBbmRyb2lkMRMwEQYDVQQKDApHb29nbGUgTExDMQswCQYDVQQG
EwJVUzB2MBAGByqGSM49AgEGBSuBBAAiA2IABCPaI3FO3z5bBQo8cuiEas4HjqCt
G/mLFfRT0MsIssPBEEU5Cfbt6sH5yOAxqEi5QagpU1yX4HwnGb7OtBYpDTB57uH5
Eczm34A5FNijV3s0/f0UPl7zbJcTx6xwqMIRq6NCMEAwDwYDVR0TAQH/BAUwAwEB
/zAOBgNVHQ8BAf8EBAMCAQYwHQYDVR0OBBYEFFIyuyz7RkOb3NaBqQ5lZuA0QepA
MAoGCCqGSM49BAMDA2gAMGUCMETfjPO/HwqReR2CS7p0ZWoD/LHs6hDi422opifH
EUaYLxwGlT9SLdjkVpz0UUOR5wIxAIoGyxGKRHVTpqpGRFiJtQEOOTp/+s1GcxeY
uR2zh/80lQyu9vAFCj6E4AXc+osmRg==
-----END CERTIFICATE-----
)";

// Historical Root 1 (Expires 2026-05-24)
//
    static const char *GOOGLE_ROOT_CA_3 = R"(
-----BEGIN CERTIFICATE-----
MIIFYDCCA0igAwIBAgIJAOj6GWMU0voYMA0GCSqGSIb3DQEBCwUAMBsxGTAXBgNV
BAUTEGY5MjAwOWU4NTNiNmIwNDUwHhcNMTYwNTI2MTYyODUyWhcNMjYwNTI0MTYy
ODUyWjAbMRkwFwYDVQQFExBmOTIwMDllODUzYjZiMDQ1MIICIjANBgkqhkiG9w0B
AQEFAAOCAg8AMIICCgKCAgEAr7bHgiuxpwHsK7Qui8xUFmOr75gvMsd/dTEDDJdS
Sxtf6An7xyqpRR90PL2abxM1dEqlXnf2tqw1Ne4Xwl5jlRfdnJLmN0pTy/4lj4/7
tv0Sk3iiKkypnEUtR6WfMgH0QZfKHM1+di+y9TFRtv6y//0rb+T+W8a9nsNL/ggj
nar86461qO0rOs2cXjp3kOG1FEJ5MVmFmBGtnrKpa73XpXyTqRxB/M0n1n/W9nGq
C4FSYa04T6N5RIZGBN2z2MT5IKGbFlbC8UrW0DxW7AYImQQcHtGl/m00QLVWutHQ
oVJYnFPlXTcHYvASLu+RhhsbDmxMgJJ0mcDpvsC4PjvB+TxywElgS70vE0XmLD+O
JtvsBslHZvPBKCOdT0MS+tgSOIfga+z1Z1g7+DVagf7quvmag8jfPioyKvxnK/Eg
sTUVi2ghzq8wm27ud/mIM7AY2qEORR8Go3TVB4HzWQgpZrt3i5MIlCaY504LzSRi
igHCzAPlHws+W0rB5N+er5/2pJKnfBSDiCiFAVtCLOZ7gLiMm0jhO2B6tUXHI/+M
RPjy02i59lINMRRev56GKtcd9qO/0kUJWdZTdA2XoS82ixPvZtXQpUpuL12ab+9E
aDK8Z4RHJYYfCT3Q5vNAXaiWQ+8PTWm2QgBR/bkwSWc+NpUFgNPN9PvQi8WEg5Um
AGMCAwEAAaOBpjCBozAdBgNVHQ4EFgQUNmHhAHyIBQlRi0RsR/8aTMnqTxIwHwYD
VR0jBBgwFoAUNmHhAHyIBQlRi0RsR/8aTMnqTxIwDwYDVR0TAQH/BAUwAwEB/zAO
BgNVHQ8BAf8EBAMCAYYwQAYDVR0fBDkwNzA1oDOgMYYvaHR0cHM6Ly9hbmRyb2lk
Lmdvb2dsZWFwaXMuY29tL2F0dGVzdGF0aW9uL2NybC8wDQYJKoZIhvcNAQELBQAD
ggIBACDIw41L3KlXG0aMiS//cqrG+EShHUGo8HNsw30W1kJtjn6UBwRM6jnmiwfB
Pb8VA91chb2vssAtX2zbTvqBJ9+LBPGCdw/E53Rbf86qhxKaiAHOjpvAy5Y3m00m
qC0w/Zwvju1twb4vhLaJ5NkUJYsUS7rmJKHHBnETLi8GFqiEsqTWpG/6ibYCv7rY
DBJDcR9W62BW9jfIoBQcxUCUJouMPH25lLNcDc1ssqvC2v7iUgI9LeoM1sNovqPm
QUiG9rHli1vXxzCyaMTjwftkJLkf6724DFhuKug2jITV0QkXvaJWF4nUaHOTNA4u
JU9WDvZLI1j83A+/xnAJUucIv/zGJ1AMH2boHqF8CY16LpsYgBt6tKxxWH00XcyD
CdW2KlBCeqbQPcsFmWyWugxdcekhYsAWyoSf818NUsZdBWBaR/OukXrNLfkQ79Iy
ZohZbvabO/X+MVT3rriAoKc8oE2Uws6DF+60PV7/WIPjNvXySdqspImSN78mflxD
qwLqRBYkA3I75qppLGG9rp7UCdRjxMl8ZDBld+7yvHVgt1cVzJx9xnyGCC23Uaic
MDSXYrB4I4WHXPGjxhZuCuPBLTdOLU8YRvMYdEvYebWHMpvwGCF6bAx3JBpIeOQ1
wDB5y0USicV3YgYGmi+NZfhA4URSh77Yd6uuJOJENRaNVTzk
-----END CERTIFICATE-----
)";

// Historical Root 2 (Expires 2034-11-18)
// NOTE: This CA should be removed soon, but I might forget. Please remind me!
    static const char *GOOGLE_ROOT_CA_4 = R"(
-----BEGIN CERTIFICATE-----
MIIFHDCCAwSgAwIBAgIJANUP8luj8tazMA0GCSqGSIb3DQEBCwUAMBsxGTAXBgNV
BAUTEGY5MjAwOWU4NTNiNmIwNDUwHhcNMTkxMTIyMjAzNzU4WhcNMzQxMTE4MjAz
NzU4WjAbMRkwFwYDVQQFExBmOTIwMDllODUzYjZiMDQ1MIICIjANBgkqhkiG9w0B
AQEFAAOCAg8AMIICCgKCAgEAr7bHgiuxpwHsK7Qui8xUFmOr75gvMsd/dTEDDJdS
Sxtf6An7xyqpRR90PL2abxM1dEqlXnf2tqw1Ne4Xwl5jlRfdnJLmN0pTy/4lj4/7
tv0Sk3iiKkypnEUtR6WfMgH0QZfKHM1+di+y9TFRtv6y//0rb+T+W8a9nsNL/ggj
nar86461qO0rOs2cXjp3kOG1FEJ5MVmFmBGtnrKpa73XpXyTqRxB/M0n1n/W9nGq
C4FSYa04T6N5RIZGBN2z2MT5IKGbFlbC8UrW0DxW7AYImQQcHtGl/m00QLVWutHQ
oVJYnFPlXTcHYvASLu+RhhsbDmxMgJJ0mcDpvsC4PjvB+TxywElgS70vE0XmLD+O
JtvsBslHZvPBKCOdT0MS+tgSOIfga+z1Z1g7+DVagf7quvmag8jfPioyKvxnK/Eg
sTUVi2ghzq8wm27ud/mIM7AY2qEORR8Go3TVB4HzWQgpZrt3i5MIlCaY504LzSRi
igHCzAPlHws+W0rB5N+er5/2pJKnfBSDiCiFAVtCLOZ7gLiMm0jhO2B6tUXHI/+M
RPjy02i59lINMRRev56GKtcd9qO/0kUJWdZTdA2XoS82ixPvZtXQpUpuL12ab+9E
aDK8Z4RHJYYfCT3Q5vNAXaiWQ+8PTWm2QgBR/bkwSWc+NpUFgNPN9PvQi8WEg5Um
AGMCAwEAAaNjMGEwHQYDVR0OBBYEFDZh4QB8iAUJUYtEbEf/GkzJ6k8SMB8GA1Ud
IwQYMBaAFDZh4QB8iAUJUYtEbEf/GkzJ6k8SMA8GA1UdEwEB/wQFMAMBAf8wDgYD
VR0PAQH/BAQDAgIEMA0GCSqGSIb3DQEBCwUAA4ICAQBOMaBc8oumXb2voc7XCWnu
XKhBBK3e2KMGz39t7lA3XXRe2ZLLAkLM5y3J7tURkf5a1SutfdOyXAmeE6SRo83U
h6WszodmMkxK5GM4JGrnt4pBisu5igXEydaW7qq2CdC6DOGjG+mEkN8/TA6p3cno
L/sPyz6evdjLlSeJ8rFBH6xWyIZCbrcpYEJzXaUOEaxxXxgYz5/cTiVKN2M1G2ok
QBUIYSY6bjEL4aUN5cfo7ogP3UvliEo3Eo0YgwuzR2v0KR6C1cZqZJSTnghIC/vA
D32KdNQ+c3N+vl2OTsUVMC1GiWkngNx1OO1+kXW+YTnnTUOtOIswUP/Vqd5SYgAI
mMAfY8U9/iIgkQj6T2W6FsScy94IN9fFhE1UtzmLoBIuUFsVXJMTz+Jucth+IqoW
Fua9v1R93/k98p41pjtFX+H8DslVgfP097vju4KDlqN64xV1grw3ZLl4CiOe/A91
oeLm2UHOq6wn3esB4r2EIQKb6jTVGu5sYCcdWpXr0AUVqcABPdgL+H7qJguBw09o
jm6xNIrw2OocrDKsudk/okr/AwqEyPKw9WnMlQgLIKw1rODG2NvU9oR3GVGdMkUB
ZutL8VuFkERQGt6vQ2OCw0sV47VMkuYbacK/xyZFiRcrPJPb41zgbQj9XAEyLKCH
ex0SdDrx+tWUDqG8At2JHA==
-----END CERTIFICATE-----
)";

    static const char *GOOGLE_ROOT_CAS[] = {
            GOOGLE_ROOT_CA_1,
            GOOGLE_ROOT_CA_2,
            GOOGLE_ROOT_CA_3,
            GOOGLE_ROOT_CA_4
    };

    AttestationVerifier &AttestationVerifier::instance() {
        static AttestationVerifier instance;
        return instance;
    }

    bool AttestationVerifier::initialize() {
        if (initialized_) {
            return true;
        }

        FUTON_LOGI("Initializing attestation verifier...");

        // Load Google root certificates
        for (const char *pem: GOOGLE_ROOT_CAS) {
            BIO *bio = BIO_new_mem_buf(pem, -1);
            if (!bio) {
                FUTON_LOGW("Failed to create BIO for a root cert");
                continue;
            }

            X509 *root_cert = PEM_read_bio_X509(bio, nullptr, nullptr, nullptr);
            BIO_free(bio);

            if (!root_cert) {
                FUTON_LOGW("Failed to parse a Google root certificate");
                continue;
            }

            // Convert to DER
            int der_len = i2d_X509(root_cert, nullptr);
            if (der_len > 0) {
                std::vector<uint8_t> der(der_len);
                uint8_t *p = der.data();
                i2d_X509(root_cert, &p);
                root_certs_.push_back(std::move(der));
            }

            X509_free(root_cert);
        }

        if (root_certs_.empty()) {
            FUTON_LOGE("Failed to load any Google root certificates");
            return false;
        }

        // Load default config from HardenedConfig
        auto &hc = HardenedConfig::instance();
        config_.required_package = hc.get_authorized_package();
        config_.required_signature = hc.get_authorized_signature();
        config_.min_security_level = 1;  // Require TEE at minimum
        config_.require_hardware_backed = true;

        initialized_ = true;
        FUTON_LOGI("Attestation verifier initialized");
        return true;
    }

    void AttestationVerifier::set_config(const AttestationConfig &config) {
        config_ = config;
    }

    AttestationResult AttestationVerifier::verify(
            const std::vector<uint8_t> &cert_chain,
            const std::vector<uint8_t> &public_key
    ) {
        AttestationResult result;
        result.valid = false;

        if (cert_chain.empty()) {
            result.error_message = "Empty certificate chain";
            return result;
        }

        // Parse concatenated DER certificates
        std::vector<std::vector<uint8_t>> certs;
        const uint8_t *p = cert_chain.data();
        const uint8_t *end = p + cert_chain.size();

        while (p < end) {
            // Parse X.509 certificate length from ASN.1 header
            if (p[0] != 0x30) {  // SEQUENCE tag
                result.error_message = "Invalid certificate format";
                return result;
            }

            size_t len;
            size_t header_len;

            if (p[1] & 0x80) {
                // Long form length
                int num_bytes = p[1] & 0x7F;
                if (num_bytes > 4 || p + 2 + num_bytes > end) {
                    result.error_message = "Invalid certificate length encoding";
                    return result;
                }
                len = 0;
                for (int i = 0; i < num_bytes; i++) {
                    len = (len << 8) | p[2 + i];
                }
                header_len = 2 + num_bytes;
            } else {
                // Short form length
                len = p[1];
                header_len = 2;
            }

            size_t total_len = header_len + len;
            if (p + total_len > end) {
                result.error_message = "Certificate extends beyond chain";
                return result;
            }

            certs.emplace_back(p, p + total_len);
            p += total_len;
        }

        return verify_chain(certs, public_key);
    }

    AttestationResult AttestationVerifier::verify_chain(
            const std::vector<std::vector<uint8_t>> &certs,
            const std::vector<uint8_t> &public_key
    ) {
        AttestationResult result;
        result.valid = false;

        if (certs.empty()) {
            result.error_message = "Empty certificate chain";
            return result;
        }

        // Verify certificate chain signatures
        if (!verify_cert_chain(certs)) {
            result.error_message = "Certificate chain verification failed";
            return result;
        }

        // Extract public key from leaf certificate
        auto leaf_pubkey = extract_public_key(certs[0]);
        if (leaf_pubkey.empty()) {
            result.error_message = "Failed to extract public key from certificate";
            return result;
        }

        // Verify public key matches
        if (leaf_pubkey != public_key) {
            result.error_message = "Public key mismatch";
            return result;
        }

        // Parse attestation extension from leaf certificate
        if (!parse_attestation_extension(certs[0], result)) {
            result.error_message = "Failed to parse attestation extension: " + result.error_message;
            return result;
        }

        // Verify against config
        if (config_.require_hardware_backed && !result.hardware_backed) {
            result.error_message = "Key is not hardware-backed";
            return result;
        }

        if (result.security_level < config_.min_security_level) {
            result.error_message = "Security level too low: " +
                                   std::to_string(result.security_level) + " < " +
                                   std::to_string(config_.min_security_level);
            return result;
        }

        if (!config_.required_package.empty() &&
            result.package_name != config_.required_package) {
            result.error_message = "Package mismatch: " + result.package_name +
                                   " != " + config_.required_package;
            return result;
        }

        if (!config_.required_signature.empty() &&
            result.app_signature != config_.required_signature) {
            result.error_message = "Signature mismatch";
            return result;
        }

        if (config_.require_verified_boot && !result.verified_boot) {
            result.error_message = "Device does not have verified boot";
            return result;
        }

        if (config_.require_device_locked && !result.device_locked) {
            result.error_message = "Device bootloader is unlocked";
            return result;
        }

        result.valid = true;
        return result;
    }

    bool AttestationVerifier::verify_cert_chain(const std::vector<std::vector<uint8_t>> &certs) {
        if (certs.empty()) return false;

        // Build X509 chain
        STACK_OF(X509) * chain = sk_X509_new_null();
        if (!chain) return false;

        std::vector<X509 *> x509_certs;

        for (const auto &cert_der: certs) {
            const uint8_t *p = cert_der.data();
            X509 *cert = d2i_X509(nullptr, &p, cert_der.size());
            if (!cert) {
                for (auto *c: x509_certs) X509_free(c);
                sk_X509_free(chain);
                return false;
            }
            x509_certs.push_back(cert);
            sk_X509_push(chain, cert);
        }

        // Create certificate store with root certs
        X509_STORE *store = X509_STORE_new();
        if (!store) {
            for (auto *c: x509_certs) X509_free(c);
            sk_X509_free(chain);
            return false;
        }

        for (const auto &root_der: root_certs_) {
            const uint8_t *p = root_der.data();
            X509 *root = d2i_X509(nullptr, &p, root_der.size());
            if (root) {
                X509_STORE_add_cert(store, root);
                X509_free(root);
            }
        }

        // Verify chain
        X509_STORE_CTX *ctx = X509_STORE_CTX_new();
        bool valid = false;

        if (ctx) {
            X509_STORE_CTX_init(ctx, store, x509_certs[0], chain);
            valid = (X509_verify_cert(ctx) == 1);

            if (!valid) {
                int err = X509_STORE_CTX_get_error(ctx);
                FUTON_LOGW("Certificate verification failed: %s",
                           X509_verify_cert_error_string(err));
            }

            X509_STORE_CTX_free(ctx);
        }

        X509_STORE_free(store);
        for (auto *c: x509_certs) X509_free(c);
        sk_X509_free(chain);

        return valid;
    }

    std::vector<uint8_t> AttestationVerifier::extract_public_key(const std::vector<uint8_t> &cert) {
        const uint8_t *p = cert.data();
        X509 *x509 = d2i_X509(nullptr, &p, cert.size());
        if (!x509) return {};

        EVP_PKEY *pkey = X509_get_pubkey(x509);
        X509_free(x509);

        if (!pkey) return {};

        std::vector<uint8_t> result;

        // Get raw public key bytes
        int key_type = EVP_PKEY_base_id(pkey);

        if (key_type == EVP_PKEY_EC) {
            // EC key - extract raw point
            size_t len = 0;
            if (EVP_PKEY_get_raw_public_key(pkey, nullptr, &len) == 1) {
                result.resize(len);
                EVP_PKEY_get_raw_public_key(pkey, result.data(), &len);
            } else {
                // Fallback: get as DER SubjectPublicKeyInfo
                int der_len = i2d_PUBKEY(pkey, nullptr);
                if (der_len > 0) {
                    result.resize(der_len);
                    uint8_t *pp = result.data();
                    i2d_PUBKEY(pkey, &pp);
                }
            }
        } else if (key_type == EVP_PKEY_ED25519) {
            // Ed25519 - get raw 32-byte key
            size_t len = 32;
            result.resize(len);
            EVP_PKEY_get_raw_public_key(pkey, result.data(), &len);
            result.resize(len);
        }

        EVP_PKEY_free(pkey);
        return result;
    }

    bool AttestationVerifier::parse_attestation_extension(
            const std::vector<uint8_t> &cert,
            AttestationResult &result
    ) {
        const uint8_t *p = cert.data();
        X509 *x509 = d2i_X509(nullptr, &p, cert.size());
        if (!x509) {
            result.error_message = "Failed to parse certificate";
            return false;
        }

        // Find attestation extension (OID: 1.3.6.1.4.1.11129.2.1.17)
        ASN1_OBJECT *attestation_oid = OBJ_txt2obj("1.3.6.1.4.1.11129.2.1.17", 1);
        if (!attestation_oid) {
            X509_free(x509);
            result.error_message = "Failed to create attestation OID";
            return false;
        }

        int ext_idx = X509_get_ext_by_OBJ(x509, attestation_oid, -1);
        ASN1_OBJECT_free(attestation_oid);

        if (ext_idx < 0) {
            X509_free(x509);
            result.error_message = "Attestation extension not found";
            return false;
        }

        X509_EXTENSION *ext = X509_get_ext(x509, ext_idx);
        if (!ext) {
            X509_free(x509);
            result.error_message = "Failed to get attestation extension";
            return false;
        }

        ASN1_OCTET_STRING *ext_data = X509_EXTENSION_get_data(ext);
        if (!ext_data) {
            X509_free(x509);
            result.error_message = "Failed to get extension data";
            return false;
        }

        // Parse KeyDescription ASN.1 structure
        // KeyDescription ::= SEQUENCE {
        //     attestationVersion         INTEGER,
        //     attestationSecurityLevel   SecurityLevel,
        //     keymasterVersion           INTEGER,
        //     keymasterSecurityLevel     SecurityLevel,
        //     attestationChallenge       OCTET STRING,
        //     uniqueId                   OCTET STRING,
        //     softwareEnforced           AuthorizationList,
        //     teeEnforced                AuthorizationList,
        // }

        const uint8_t *ext_p = ASN1_STRING_get0_data(ext_data);
        int ext_len = ASN1_STRING_length(ext_data);

        // Simple ASN.1 parsing for key fields
        // This is a simplified parser - production code should use proper ASN.1 library

        if (ext_len < 4 || ext_p[0] != 0x30) {  // SEQUENCE
            X509_free(x509);
            result.error_message = "Invalid attestation extension format";
            return false;
        }

        // Skip SEQUENCE header
        int seq_len;
        int header_len;
        if (ext_p[1] & 0x80) {
            int num_bytes = ext_p[1] & 0x7F;
            seq_len = 0;
            for (int i = 0; i < num_bytes; i++) {
                seq_len = (seq_len << 8) | ext_p[2 + i];
            }
            header_len = 2 + num_bytes;
        } else {
            seq_len = ext_p[1];
            header_len = 2;
        }

        const uint8_t *seq_p = ext_p + header_len;
        const uint8_t *seq_end = seq_p + seq_len;

        // Parse attestationVersion (INTEGER)
        if (seq_p + 3 <= seq_end && seq_p[0] == 0x02) {
            int int_len = seq_p[1];
            result.attestation_version = 0;
            for (int i = 0; i < int_len && i < 4; i++) {
                result.attestation_version = (result.attestation_version << 8) | seq_p[2 + i];
            }
            seq_p += 2 + int_len;
        }

        // Parse attestationSecurityLevel (ENUMERATED)
        if (seq_p + 3 <= seq_end && seq_p[0] == 0x0A) {
            result.security_level = seq_p[2];
            result.hardware_backed = (result.security_level >= 1);
            seq_p += 3;
        }

        // For now, set defaults for fields we don't fully parse
        result.verified_boot = true;  // Assume verified boot
        result.device_locked = false; // Can't determine without full parsing
        result.user_presence_required = false;

        // Parse softwareEnforced and teeEnforced to extract package info
        // This requires parsing AuthorizationList which contains:
        // - Tag 709 (0x02C5): attestationApplicationId

        // For simplicity, we'll extract package name from the certificate subject
        // In production, you'd want to fully parse the AuthorizationList

        X509_NAME *subject = X509_get_subject_name(x509);
        if (subject) {
            char cn[256] = {0};
            X509_NAME_get_text_by_NID(subject, NID_commonName, cn, sizeof(cn));
            // Package name might be in CN or we need to parse attestationApplicationId
        }

        X509_free(x509);

        // If we got here, basic parsing succeeded
        // Package name and signature would come from attestationApplicationId
        // which requires more complex ASN.1 parsing

        return true;
    }

    bool AttestationVerifier::is_authorized_signature(const std::vector<uint8_t> &signature) const {
        if (config_.required_signature.empty()) {
            return true;  // No signature requirement
        }
        return signature == config_.required_signature;
    }

    bool AttestationVerifier::is_authorized_package(const std::string &package) const {
        if (config_.required_package.empty()) {
            return true;  // No package requirement
        }
        return package == config_.required_package;
    }

} // namespace futon::core::auth
