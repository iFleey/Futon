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

#include "ws_frame.h"
#include <cstring>
#include <algorithm>
#include <sstream>

// SHA-1 implementation for WebSocket handshake
namespace {

    class SHA1 {
    public:
        SHA1() { reset(); }

        void update(const uint8_t *data, size_t len) {
            for (size_t i = 0; i < len; ++i) {
                buffer_[buffer_len_++] = data[i];
                if (buffer_len_ == 64) {
                    process_block();
                    buffer_len_ = 0;
                }
            }
            total_len_ += len;
        }

        void update(const std::string &str) {
            update(reinterpret_cast<const uint8_t *>(str.data()), str.size());
        }

        std::vector<uint8_t> finalize() {
            uint64_t total_bits = total_len_ * 8;

            // Padding
            buffer_[buffer_len_++] = 0x80;
            while (buffer_len_ != 56) {
                if (buffer_len_ == 64) {
                    process_block();
                    buffer_len_ = 0;
                }
                buffer_[buffer_len_++] = 0;
            }

            // Length in bits (big-endian)
            for (int i = 7; i >= 0; --i) {
                buffer_[buffer_len_++] = (total_bits >> (i * 8)) & 0xFF;
            }
            process_block();

            // Output hash
            std::vector<uint8_t> hash(20);
            for (int i = 0; i < 5; ++i) {
                hash[i * 4 + 0] = (h_[i] >> 24) & 0xFF;
                hash[i * 4 + 1] = (h_[i] >> 16) & 0xFF;
                hash[i * 4 + 2] = (h_[i] >> 8) & 0xFF;
                hash[i * 4 + 3] = h_[i] & 0xFF;
            }
            return hash;
        }

    private:
        void reset() {
            h_[0] = 0x67452301;
            h_[1] = 0xEFCDAB89;
            h_[2] = 0x98BADCFE;
            h_[3] = 0x10325476;
            h_[4] = 0xC3D2E1F0;
            buffer_len_ = 0;
            total_len_ = 0;
        }

        void process_block() {
            uint32_t w[80];

            for (int i = 0; i < 16; ++i) {
                w[i] = (buffer_[i * 4] << 24) |
                       (buffer_[i * 4 + 1] << 16) |
                       (buffer_[i * 4 + 2] << 8) |
                       buffer_[i * 4 + 3];
            }

            for (int i = 16; i < 80; ++i) {
                uint32_t val = w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16];
                w[i] = (val << 1) | (val >> 31);
            }

            uint32_t a = h_[0], b = h_[1], c = h_[2], d = h_[3], e = h_[4];

            for (int i = 0; i < 80; ++i) {
                uint32_t f, k;
                if (i < 20) {
                    f = (b & c) | ((~b) & d);
                    k = 0x5A827999;
                } else if (i < 40) {
                    f = b ^ c ^ d;
                    k = 0x6ED9EBA1;
                } else if (i < 60) {
                    f = (b & c) | (b & d) | (c & d);
                    k = 0x8F1BBCDC;
                } else {
                    f = b ^ c ^ d;
                    k = 0xCA62C1D6;
                }

                uint32_t temp = ((a << 5) | (a >> 27)) + f + e + k + w[i];
                e = d;
                d = c;
                c = (b << 30) | (b >> 2);
                b = a;
                a = temp;
            }

            h_[0] += a;
            h_[1] += b;
            h_[2] += c;
            h_[3] += d;
            h_[4] += e;
        }

        uint32_t h_[5];
        uint8_t buffer_[64];
        size_t buffer_len_;
        uint64_t total_len_;
    };

} // anonymous namespace

namespace futon::debug {

    std::vector<uint8_t> WsFrameCodec::encode(const WsFrame &frame) {
        std::vector<uint8_t> result;

        // First byte: FIN + opcode
        uint8_t first_byte = (frame.fin ? 0x80 : 0x00) | static_cast<uint8_t>(frame.opcode);
        result.push_back(first_byte);

        // Second byte: mask flag + payload length
        size_t payload_len = frame.payload.size();
        if (payload_len <= 125) {
            result.push_back(static_cast<uint8_t>(payload_len));
        } else if (payload_len <= 65535) {
            result.push_back(126);
            result.push_back((payload_len >> 8) & 0xFF);
            result.push_back(payload_len & 0xFF);
        } else {
            result.push_back(127);
            for (int i = 7; i >= 0; --i) {
                result.push_back((payload_len >> (i * 8)) & 0xFF);
            }
        }

        // Payload (server -> client: no mask)
        result.insert(result.end(), frame.payload.begin(), frame.payload.end());

        return result;
    }

    std::vector<uint8_t> WsFrameCodec::encode_text(const std::string &text) {
        WsFrame frame;
        frame.fin = true;
        frame.opcode = WsOpcode::Text;
        frame.masked = false;
        frame.payload.assign(text.begin(), text.end());
        return encode(frame);
    }

    std::vector<uint8_t> WsFrameCodec::encode_close(uint16_t code) {
        WsFrame frame;
        frame.fin = true;
        frame.opcode = WsOpcode::Close;
        frame.masked = false;
        frame.payload.push_back((code >> 8) & 0xFF);
        frame.payload.push_back(code & 0xFF);
        return encode(frame);
    }

    std::vector<uint8_t> WsFrameCodec::encode_pong(const std::vector<uint8_t> &payload) {
        WsFrame frame;
        frame.fin = true;
        frame.opcode = WsOpcode::Pong;
        frame.masked = false;
        frame.payload = payload;
        return encode(frame);
    }

    int WsFrameCodec::decode(const uint8_t *data, size_t len, WsFrame &out_frame) {
        if (len < 2) return 0;  // Need more data

        size_t pos = 0;

        // First byte
        out_frame.fin = (data[pos] & 0x80) != 0;
        out_frame.opcode = static_cast<WsOpcode>(data[pos] & 0x0F);
        pos++;

        // Second byte
        out_frame.masked = (data[pos] & 0x80) != 0;
        uint64_t payload_len = data[pos] & 0x7F;
        pos++;

        // Extended payload length
        if (payload_len == 126) {
            if (len < pos + 2) return 0;
            payload_len = (static_cast<uint64_t>(data[pos]) << 8) | data[pos + 1];
            pos += 2;
        } else if (payload_len == 127) {
            if (len < pos + 8) return 0;
            payload_len = 0;
            for (int i = 0; i < 8; ++i) {
                payload_len = (payload_len << 8) | data[pos + i];
            }
            pos += 8;
        }

        // Mask key (if masked)
        if (out_frame.masked) {
            if (len < pos + 4) return 0;
            out_frame.mask_key = (static_cast<uint32_t>(data[pos]) << 24) |
                                 (static_cast<uint32_t>(data[pos + 1]) << 16) |
                                 (static_cast<uint32_t>(data[pos + 2]) << 8) |
                                 data[pos + 3];
            pos += 4;
        }

        // Payload
        if (len < pos + payload_len) return 0;

        out_frame.payload.resize(payload_len);
        std::memcpy(out_frame.payload.data(), data + pos, payload_len);

        // Unmask if needed
        if (out_frame.masked && payload_len > 0) {
            apply_mask(out_frame.payload.data(), payload_len, out_frame.mask_key);
        }

        return static_cast<int>(pos + payload_len);
    }

    void WsFrameCodec::apply_mask(uint8_t *data, size_t len, uint32_t mask_key) {
        uint8_t mask[4] = {
                static_cast<uint8_t>((mask_key >> 24) & 0xFF),
                static_cast<uint8_t>((mask_key >> 16) & 0xFF),
                static_cast<uint8_t>((mask_key >> 8) & 0xFF),
                static_cast<uint8_t>(mask_key & 0xFF)
        };

        for (size_t i = 0; i < len; ++i) {
            data[i] ^= mask[i % 4];
        }
    }

    bool WsHandshake::parse_request(const std::string &request, std::string &out_key) {
        // Look for Sec-WebSocket-Key header
        const std::string key_header = "Sec-WebSocket-Key:";
        size_t pos = request.find(key_header);
        if (pos == std::string::npos) {
            return false;
        }

        pos += key_header.length();

        // Skip whitespace
        while (pos < request.length() && (request[pos] == ' ' || request[pos] == '\t')) {
            pos++;
        }

        // Find end of line
        size_t end = request.find("\r\n", pos);
        if (end == std::string::npos) {
            end = request.find('\n', pos);
        }
        if (end == std::string::npos) {
            end = request.length();
        }

        out_key = request.substr(pos, end - pos);

        // Trim trailing whitespace
        while (!out_key.empty() && (out_key.back() == ' ' || out_key.back() == '\t')) {
            out_key.pop_back();
        }

        return !out_key.empty();
    }

    std::string WsHandshake::generate_response(const std::string &client_key) {
        std::string accept_key = compute_accept_key(client_key);

        std::ostringstream response;
        response << "HTTP/1.1 101 Switching Protocols\r\n";
        response << "Upgrade: websocket\r\n";
        response << "Connection: Upgrade\r\n";
        response << "Sec-WebSocket-Accept: " << accept_key << "\r\n";
        response << "\r\n";

        return response.str();
    }

    std::string WsHandshake::compute_accept_key(const std::string &client_key) {
        // Concatenate with magic GUID
        const std::string magic = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
        std::string combined = client_key + magic;

        // SHA-1 hash
        SHA1 sha1;
        sha1.update(combined);
        std::vector<uint8_t> hash = sha1.finalize();

        // Base64 encode
        return base64_encode(hash.data(), hash.size());
    }

    std::string WsHandshake::base64_encode(const uint8_t *data, size_t len) {
        static const char *chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

        std::string result;
        result.reserve((len + 2) / 3 * 4);

        for (size_t i = 0; i < len; i += 3) {
            uint32_t n = static_cast<uint32_t>(data[i]) << 16;
            if (i + 1 < len) n |= static_cast<uint32_t>(data[i + 1]) << 8;
            if (i + 2 < len) n |= data[i + 2];

            result.push_back(chars[(n >> 18) & 0x3F]);
            result.push_back(chars[(n >> 12) & 0x3F]);
            result.push_back((i + 1 < len) ? chars[(n >> 6) & 0x3F] : '=');
            result.push_back((i + 2 < len) ? chars[n & 0x3F] : '=');
        }

        return result;
    }

} // namespace futon::debug
