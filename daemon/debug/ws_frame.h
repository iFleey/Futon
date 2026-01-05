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

#ifndef FUTON_DEBUG_WS_FRAME_H
#define FUTON_DEBUG_WS_FRAME_H

#include <cstdint>
#include <string>
#include <vector>

namespace futon::debug {

// WebSocket opcodes (RFC 6455)
    enum class WsOpcode : uint8_t {
        Continuation = 0x0,
        Text = 0x1,
        Binary = 0x2,
        Close = 0x8,
        Ping = 0x9,
        Pong = 0xA
    };

// WebSocket frame structure
    struct WsFrame {
        bool fin = true;
        WsOpcode opcode = WsOpcode::Text;
        bool masked = false;
        uint32_t mask_key = 0;
        std::vector<uint8_t> payload;
    };

// WebSocket frame encoder/decoder
    class WsFrameCodec {
    public:
        // Encode a frame for sending (server -> client, no mask)
        static std::vector<uint8_t> encode(const WsFrame &frame);

        // Encode a text message
        static std::vector<uint8_t> encode_text(const std::string &text);

        // Encode a close frame
        static std::vector<uint8_t> encode_close(uint16_t code = 1000);

        // Encode a pong frame
        static std::vector<uint8_t> encode_pong(const std::vector<uint8_t> &payload);

        // Decode a frame from buffer
        // Returns bytes consumed, 0 if incomplete, -1 on error
        static int decode(const uint8_t *data, size_t len, WsFrame &out_frame);

    private:
        static void apply_mask(uint8_t *data, size_t len, uint32_t mask_key);
    };

// WebSocket handshake utilities
    class WsHandshake {
    public:
        // Parse HTTP upgrade request, extract Sec-WebSocket-Key
        static bool parse_request(const std::string &request, std::string &out_key);

        // Generate HTTP upgrade response
        static std::string generate_response(const std::string &client_key);

    private:
        static std::string compute_accept_key(const std::string &client_key);

        static std::string base64_encode(const uint8_t *data, size_t len);
    };

} // namespace futon::debug

#endif // FUTON_DEBUG_WS_FRAME_H
