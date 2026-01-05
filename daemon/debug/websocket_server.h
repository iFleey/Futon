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

#ifndef FUTON_DEBUG_WEBSOCKET_SERVER_H
#define FUTON_DEBUG_WEBSOCKET_SERVER_H

#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include <cstdint>

namespace futon::debug {

// Default WebSocket server port
    constexpr uint16_t kDefaultWebSocketPort = 33212;

// WebSocket server for debug stream
// Supports multiple concurrent clients with non-blocking broadcast
    class WebSocketServer {
    public:
        WebSocketServer();

        ~WebSocketServer();

        // Disable copy
        WebSocketServer(const WebSocketServer &) = delete;

        WebSocketServer &operator=(const WebSocketServer &) = delete;

        // Start the server on specified port
        // @param port TCP port to listen on (default 33212)
        // @return true if server started successfully
        bool start(uint16_t port = kDefaultWebSocketPort);

        // Stop the server and disconnect all clients
        void stop();

        // Check if server is running
        bool is_running() const { return running_.load(); }

        // Broadcast JSON string to all connected clients
        // Thread-safe, non-blocking (drops message if send buffer full)
        void broadcast_json(const std::string &json);

        // Broadcast raw data to all connected clients
        void broadcast(const std::string &data);

        // Get current connected client count
        int get_client_count() const { return client_count_.load(); }

        // Get server port
        uint16_t get_port() const { return port_; }

    private:
        class Impl;

        std::unique_ptr<Impl> impl_;

        std::atomic<bool> running_{false};
        std::atomic<int> client_count_{0};
        uint16_t port_ = kDefaultWebSocketPort;
    };

} // namespace futon::debug

#endif // FUTON_DEBUG_WEBSOCKET_SERVER_H
