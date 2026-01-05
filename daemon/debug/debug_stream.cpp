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

#include "debug_stream.h"
#include "websocket_server.h"
#include "core/error.h"

#include <chrono>
#include <sstream>
#include <iomanip>
#include <cmath>

namespace futon::debug {

    DebugStream::DebugStream() = default;

    DebugStream::~DebugStream() {
        shutdown();
    }

    bool DebugStream::initialize(uint16_t port, int target_hz) {
        if (running_.load()) {
            FUTON_LOGD("DebugStream already initialized");
            return true;
        }

        port_ = port;
        target_hz_ = std::min(std::max(target_hz, kMinTargetHz), kMaxTargetHz);

        server_ = std::make_unique<WebSocketServer>();
        if (!server_->start(port_)) {
            FUTON_LOGE("Failed to start WebSocket server on port %d", port_);
            server_.reset();
            return false;
        }

        running_.store(true);
        broadcast_thread_ = std::thread(&DebugStream::broadcast_loop, this);

        FUTON_LOGI("DebugStream initialized: port=%d, target_hz=%d", port_, target_hz_);
        return true;
    }

    void DebugStream::shutdown() {
        if (!running_.load()) {
            return;
        }

        running_.store(false);

        if (broadcast_thread_.joinable()) {
            broadcast_thread_.join();
        }

        if (server_) {
            server_->stop();
            server_.reset();
        }

        FUTON_LOGI("DebugStream shutdown");
    }

    void DebugStream::push_frame(const DebugFrame &frame) {
        // Non-blocking update - overwrites previous frame
        std::lock_guard<std::mutex> lock(frame_mutex_);
        latest_frame_ = frame;
        frame_updated_.store(true);
    }

    int DebugStream::get_client_count() const {
        return server_ ? server_->get_client_count() : 0;
    }

    void DebugStream::broadcast_loop() {
        const auto interval = std::chrono::microseconds(1000000 / target_hz_);
        auto next_broadcast = std::chrono::steady_clock::now();

        while (running_.load()) {
            auto now = std::chrono::steady_clock::now();

            // Wait until next broadcast time
            if (now < next_broadcast) {
                std::this_thread::sleep_until(next_broadcast);
                now = std::chrono::steady_clock::now();
            }

            // Schedule next broadcast
            next_broadcast = now + interval;

            // Skip if no clients connected (avoid serialization overhead)
            if (!server_ || server_->get_client_count() == 0) {
                continue;
            }

            // Skip if no new frame
            if (!frame_updated_.load()) {
                continue;
            }

            // Get frame copy
            DebugFrame frame;
            {
                std::lock_guard<std::mutex> lock(frame_mutex_);
                frame = latest_frame_;
                frame_updated_.store(false);
            }

            // Serialize and broadcast
            std::string json = serialize_frame(frame);
            server_->broadcast_json(json);
        }
    }

    std::string DebugStream::serialize_frame(const DebugFrame &frame) {
        std::ostringstream ss;
        ss << std::setprecision(6);

        ss << "{";
        ss << "\"timestamp_ns\":" << frame.timestamp_ns << ",";
        ss << "\"fps\":" << frame.fps << ",";
        ss << "\"latency_ms\":" << frame.latency_ms << ",";
        ss << "\"frame_count\":" << frame.frame_count << ",";
        ss << "\"active_delegate\":\"" << escape_json_string(frame.active_delegate) << "\",";
        ss << "\"detections\":[";

        for (size_t i = 0; i < frame.detections.size(); ++i) {
            const auto &det = frame.detections[i];
            if (i > 0) ss << ",";
            ss << "{";
            ss << "\"x1\":" << det.x1 << ",";
            ss << "\"y1\":" << det.y1 << ",";
            ss << "\"x2\":" << det.x2 << ",";
            ss << "\"y2\":" << det.y2 << ",";
            ss << "\"confidence\":" << det.confidence << ",";
            ss << "\"class_id\":" << det.class_id;
            ss << "}";
        }

        ss << "]}";
        return ss.str();
    }

    std::string DebugStream::escape_json_string(const std::string &str) {
        std::ostringstream ss;
        for (char c: str) {
            switch (c) {
                case '"':
                    ss << "\\\"";
                    break;
                case '\\':
                    ss << "\\\\";
                    break;
                case '\b':
                    ss << "\\b";
                    break;
                case '\f':
                    ss << "\\f";
                    break;
                case '\n':
                    ss << "\\n";
                    break;
                case '\r':
                    ss << "\\r";
                    break;
                case '\t':
                    ss << "\\t";
                    break;
                default:
                    if (static_cast<unsigned char>(c) < 0x20) {
                        ss << "\\u" << std::hex << std::setw(4) << std::setfill('0')
                           << static_cast<int>(static_cast<unsigned char>(c));
                    } else {
                        ss << c;
                    }
                    break;
            }
        }
        return ss.str();
    }

}  // namespace futon::debug
