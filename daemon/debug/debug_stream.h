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

#ifndef FUTON_DEBUG_DEBUG_STREAM_H
#define FUTON_DEBUG_DEBUG_STREAM_H

#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include <cstdint>

namespace futon::debug {

// Forward declaration
    class WebSocketServer;

// Bounding box for debug visualization
    struct BoundingBox {
        float x1 = 0.0f;
        float y1 = 0.0f;
        float x2 = 0.0f;
        float y2 = 0.0f;
        float confidence = 0.0f;
        int class_id = 0;
    };

// Debug frame data structure
    struct DebugFrame {
        int64_t timestamp_ns = 0;
        float fps = 0.0f;
        float latency_ms = 0.0f;
        int frame_count = 0;
        std::string active_delegate;
        std::vector<BoundingBox> detections;
    };

// Default configuration
    constexpr uint16_t kDefaultDebugPort = 33212;
    constexpr int kDefaultTargetHz = 30;
    constexpr int kMinTargetHz = 1;
    constexpr int kMaxTargetHz = 60;

// Debug stream for real-time telemetry via WebSocket
// Broadcasts debug frames at configurable rate without blocking inference pipeline
    class DebugStream {
    public:
        DebugStream();

        ~DebugStream();

        // Disable copy
        DebugStream(const DebugStream &) = delete;

        DebugStream &operator=(const DebugStream &) = delete;

        // Initialize the debug stream
        // @param port WebSocket server port (default 33212)
        // @param target_hz Target broadcast frequency (default 30Hz, clamped to 1-60Hz)
        // @return true if initialization successful
        bool initialize(uint16_t port = kDefaultDebugPort, int target_hz = kDefaultTargetHz);

        // Shutdown the debug stream
        void shutdown();

        // Push a frame for broadcast
        // Thread-safe, non-blocking - always returns immediately
        // Overwrites previous frame if not yet broadcast
        void push_frame(const DebugFrame &frame);

        // Check if running
        bool is_running() const { return running_.load(); }

        // Get current client count
        int get_client_count() const;

        // Get configured port
        uint16_t get_port() const { return port_; }

        // Get configured target Hz
        int get_target_hz() const { return target_hz_; }

    private:
        std::unique_ptr<WebSocketServer> server_;
        std::atomic<bool> running_{false};
        uint16_t port_ = kDefaultDebugPort;
        int target_hz_ = kDefaultTargetHz;
        std::thread broadcast_thread_;

        // Double-buffered frame storage for lock-free push
        std::mutex frame_mutex_;
        DebugFrame latest_frame_;
        std::atomic<bool> frame_updated_{false};

        // Broadcast loop running in separate thread
        void broadcast_loop();

        // Serialize frame to JSON string
        static std::string serialize_frame(const DebugFrame &frame);

        // Escape string for JSON
        static std::string escape_json_string(const std::string &str);
    };

} // namespace futon::debug

#endif // FUTON_DEBUG_DEBUG_STREAM_H
