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

#include "websocket_server.h"
#include "ws_frame.h"
#include "core/error.h"

#include <sys/socket.h>
#include <sys/epoll.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <fcntl.h>
#include <cerrno>
#include <cstring>
#include <algorithm>
#include <unordered_map>

namespace futon::debug {

// Client connection state
    enum class ClientState {
        Handshaking,    // Waiting for HTTP upgrade request
        Connected,      // WebSocket connection established
        Closing         // Close frame sent, waiting for response
    };

// Per-client data
    struct ClientData {
        int fd = -1;
        ClientState state = ClientState::Handshaking;
        std::vector<uint8_t> recv_buffer;
        std::vector<uint8_t> send_buffer;
        bool send_pending = false;
    };

// Maximum clients and buffer sizes
    constexpr int kMaxClients = 16;
    constexpr size_t kMaxRecvBuffer = 8192;
    constexpr size_t kMaxSendBuffer = 65536;
    constexpr int kEpollMaxEvents = 32;

    class WebSocketServer::Impl {
    public:
        Impl(WebSocketServer *owner) : owner_(owner) {}

        ~Impl() {
            stop();
        }

        bool start(uint16_t port) {
            if (running_) {
                return true;
            }

            // Create server socket
            server_fd_ = socket(AF_INET, SOCK_STREAM, 0);
            if (server_fd_ < 0) {
                FUTON_LOGE_ERRNO("Failed to create server socket");
                return false;
            }

            // Set socket options
            int opt = 1;
            setsockopt(server_fd_, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));
            setsockopt(server_fd_, SOL_SOCKET, SO_REUSEPORT, &opt, sizeof(opt));

            // Set non-blocking
            if (!set_nonblocking(server_fd_)) {
                close(server_fd_);
                server_fd_ = -1;
                return false;
            }

            // Bind to port
            struct sockaddr_in addr{};
            addr.sin_family = AF_INET;
            addr.sin_addr.s_addr = INADDR_ANY;
            addr.sin_port = htons(port);

            if (bind(server_fd_, reinterpret_cast<struct sockaddr *>(&addr), sizeof(addr)) < 0) {
                FUTON_LOGE_ERRNO("Failed to bind to port");
                close(server_fd_);
                server_fd_ = -1;
                return false;
            }

            // Listen
            if (listen(server_fd_, kMaxClients) < 0) {
                FUTON_LOGE_ERRNO("Failed to listen");
                close(server_fd_);
                server_fd_ = -1;
                return false;
            }

            // Create epoll instance
            epoll_fd_ = epoll_create1(EPOLL_CLOEXEC);
            if (epoll_fd_ < 0) {
                FUTON_LOGE_ERRNO("Failed to create epoll");
                close(server_fd_);
                server_fd_ = -1;
                return false;
            }

            // Add server socket to epoll
            struct epoll_event ev{};
            ev.events = EPOLLIN;
            ev.data.fd = server_fd_;
            if (epoll_ctl(epoll_fd_, EPOLL_CTL_ADD, server_fd_, &ev) < 0) {
                FUTON_LOGE_ERRNO("Failed to add server to epoll");
                close(epoll_fd_);
                close(server_fd_);
                epoll_fd_ = -1;
                server_fd_ = -1;
                return false;
            }

            running_ = true;
            server_thread_ = std::thread(&Impl::server_loop, this);

            FUTON_LOGI("WebSocket server started on port %d", port);
            return true;
        }

        void stop() {
            if (!running_) {
                return;
            }

            running_ = false;

            // Wake up epoll by closing server socket
            if (server_fd_ >= 0) {
                shutdown(server_fd_, SHUT_RDWR);
            }

            if (server_thread_.joinable()) {
                server_thread_.join();
            }

            // Close all clients
            {
                std::lock_guard<std::mutex> lock(clients_mutex_);
                for (auto &[fd, client]: clients_) {
                    close(fd);
                }
                clients_.clear();
            }

            if (epoll_fd_ >= 0) {
                close(epoll_fd_);
                epoll_fd_ = -1;
            }

            if (server_fd_ >= 0) {
                close(server_fd_);
                server_fd_ = -1;
            }

            owner_->client_count_.store(0);
            FUTON_LOGI("WebSocket server stopped");
        }

        void broadcast(const std::string &data) {
            if (!running_) {
                return;
            }

            std::vector<uint8_t> frame = WsFrameCodec::encode_text(data);

            std::lock_guard<std::mutex> lock(clients_mutex_);
            for (auto &[fd, client]: clients_) {
                if (client.state == ClientState::Connected) {
                    queue_send(client, frame);
                }
            }
        }

        int get_client_count() const {
            std::lock_guard<std::mutex> lock(clients_mutex_);
            int count = 0;
            for (const auto &[fd, client]: clients_) {
                if (client.state == ClientState::Connected) {
                    count++;
                }
            }
            return count;
        }

    private:
        WebSocketServer *owner_;
        int server_fd_ = -1;
        int epoll_fd_ = -1;
        std::atomic<bool> running_{false};
        std::thread server_thread_;
        mutable std::mutex clients_mutex_;
        std::unordered_map<int, ClientData> clients_;

        bool set_nonblocking(int fd) {
            int flags = fcntl(fd, F_GETFL, 0);
            if (flags < 0) {
                FUTON_LOGE_ERRNO("fcntl F_GETFL failed");
                return false;
            }
            if (fcntl(fd, F_SETFL, flags | O_NONBLOCK) < 0) {
                FUTON_LOGE_ERRNO("fcntl F_SETFL failed");
                return false;
            }
            return true;
        }

        void server_loop() {
            struct epoll_event events[kEpollMaxEvents];

            while (running_) {
                int nfds = epoll_wait(epoll_fd_, events, kEpollMaxEvents, 100);

                if (nfds < 0) {
                    if (errno == EINTR) continue;
                    FUTON_LOGE_ERRNO("epoll_wait failed");
                    break;
                }

                for (int i = 0; i < nfds; ++i) {
                    int fd = events[i].data.fd;
                    uint32_t ev = events[i].events;

                    if (fd == server_fd_) {
                        // New connection
                        if (ev & EPOLLIN) {
                            accept_client();
                        }
                    } else {
                        // Client event
                        if (ev & EPOLLIN) {
                            handle_client_read(fd);
                        }
                        if (ev & EPOLLOUT) {
                            handle_client_write(fd);
                        }
                        if (ev & (EPOLLERR | EPOLLHUP)) {
                            remove_client(fd);
                        }
                    }
                }
            }
        }

        void accept_client() {
            struct sockaddr_in client_addr{};
            socklen_t addr_len = sizeof(client_addr);

            int client_fd = accept(server_fd_,
                                   reinterpret_cast<struct sockaddr *>(&client_addr),
                                   &addr_len);

            if (client_fd < 0) {
                if (errno != EAGAIN && errno != EWOULDBLOCK) {
                    FUTON_LOGE_ERRNO("accept failed");
                }
                return;
            }

            // Check client limit
            {
                std::lock_guard<std::mutex> lock(clients_mutex_);
                if (clients_.size() >= kMaxClients) {
                    FUTON_LOGW("Max clients reached, rejecting connection");
                    close(client_fd);
                    return;
                }
            }

            // Set non-blocking and TCP_NODELAY
            if (!set_nonblocking(client_fd)) {
                close(client_fd);
                return;
            }

            int opt = 1;
            setsockopt(client_fd, IPPROTO_TCP, TCP_NODELAY, &opt, sizeof(opt));

            // Add to epoll
            struct epoll_event ev{};
            ev.events = EPOLLIN | EPOLLET;
            ev.data.fd = client_fd;
            if (epoll_ctl(epoll_fd_, EPOLL_CTL_ADD, client_fd, &ev) < 0) {
                FUTON_LOGE_ERRNO("Failed to add client to epoll");
                close(client_fd);
                return;
            }

            // Add to clients map
            {
                std::lock_guard<std::mutex> lock(clients_mutex_);
                ClientData client;
                client.fd = client_fd;
                client.state = ClientState::Handshaking;
                clients_[client_fd] = std::move(client);
            }

            char addr_str[INET_ADDRSTRLEN];
            inet_ntop(AF_INET, &client_addr.sin_addr, addr_str, sizeof(addr_str));
            FUTON_LOGD("Client connected from %s:%d (fd=%d)",
                       addr_str, ntohs(client_addr.sin_port), client_fd);
        }

        void handle_client_read(int fd) {
            std::lock_guard<std::mutex> lock(clients_mutex_);

            auto it = clients_.find(fd);
            if (it == clients_.end()) {
                return;
            }

            ClientData &client = it->second;

            // Read data
            uint8_t buf[4096];
            while (true) {
                ssize_t n = recv(fd, buf, sizeof(buf), 0);

                if (n < 0) {
                    if (errno == EAGAIN || errno == EWOULDBLOCK) {
                        break;  // No more data
                    }
                    FUTON_LOGE_ERRNO("recv failed");
                    remove_client_locked(fd);
                    return;
                }

                if (n == 0) {
                    // Connection closed
                    remove_client_locked(fd);
                    return;
                }

                // Append to receive buffer
                if (client.recv_buffer.size() + n > kMaxRecvBuffer) {
                    FUTON_LOGW("Client recv buffer overflow, disconnecting");
                    remove_client_locked(fd);
                    return;
                }

                client.recv_buffer.insert(client.recv_buffer.end(), buf, buf + n);
            }

            // Process received data
            process_client_data(client);
        }

        void process_client_data(ClientData &client) {
            if (client.state == ClientState::Handshaking) {
                // Look for complete HTTP request
                std::string request(client.recv_buffer.begin(), client.recv_buffer.end());
                size_t end_pos = request.find("\r\n\r\n");

                if (end_pos != std::string::npos) {
                    // Parse handshake
                    std::string ws_key;
                    if (WsHandshake::parse_request(request, ws_key)) {
                        // Send response
                        std::string response = WsHandshake::generate_response(ws_key);
                        std::vector<uint8_t> response_data(response.begin(), response.end());
                        queue_send(client, response_data);

                        client.state = ClientState::Connected;
                        client.recv_buffer.erase(client.recv_buffer.begin(),
                                                 client.recv_buffer.begin() + end_pos + 4);

                        owner_->client_count_.fetch_add(1);
                        FUTON_LOGI("WebSocket handshake complete (fd=%d), clients=%d",
                                   client.fd, owner_->client_count_.load());
                    } else {
                        FUTON_LOGW("Invalid WebSocket handshake");
                        remove_client_locked(client.fd);
                        return;
                    }
                }
            }

            if (client.state == ClientState::Connected) {
                // Process WebSocket frames
                while (!client.recv_buffer.empty()) {
                    WsFrame frame;
                    int consumed = WsFrameCodec::decode(
                            client.recv_buffer.data(),
                            client.recv_buffer.size(),
                            frame);

                    if (consumed < 0) {
                        FUTON_LOGW("Invalid WebSocket frame");
                        remove_client_locked(client.fd);
                        return;
                    }

                    if (consumed == 0) {
                        break;  // Need more data
                    }

                    // Remove consumed data
                    client.recv_buffer.erase(client.recv_buffer.begin(),
                                             client.recv_buffer.begin() + consumed);

                    // Handle frame
                    handle_frame(client, frame);
                }
            }
        }

        void handle_frame(ClientData &client, const WsFrame &frame) {
            switch (frame.opcode) {
                case WsOpcode::Text:
                case WsOpcode::Binary:
                    // Ignore incoming data (debug stream is one-way)
                    break;

                case WsOpcode::Ping:
                    // Respond with pong
                    queue_send(client, WsFrameCodec::encode_pong(frame.payload));
                    break;

                case WsOpcode::Pong:
                    // Ignore
                    break;

                case WsOpcode::Close:
                    // Send close response and disconnect
                    queue_send(client, WsFrameCodec::encode_close());
                    client.state = ClientState::Closing;
                    break;

                default:
                    break;
            }
        }

        void queue_send(ClientData &client, const std::vector<uint8_t> &data) {
            if (client.send_buffer.size() + data.size() > kMaxSendBuffer) {
                // Drop message if buffer full (non-blocking)
                return;
            }

            client.send_buffer.insert(client.send_buffer.end(), data.begin(), data.end());

            if (!client.send_pending) {
                // Try to send immediately
                flush_send(client);
            }

            // Update epoll if still have data to send
            if (!client.send_buffer.empty()) {
                client.send_pending = true;
                struct epoll_event ev{};
                ev.events = EPOLLIN | EPOLLOUT | EPOLLET;
                ev.data.fd = client.fd;
                epoll_ctl(epoll_fd_, EPOLL_CTL_MOD, client.fd, &ev);
            }
        }

        void flush_send(ClientData &client) {
            while (!client.send_buffer.empty()) {
                ssize_t n = send(client.fd, client.send_buffer.data(),
                                 client.send_buffer.size(), MSG_NOSIGNAL);

                if (n < 0) {
                    if (errno == EAGAIN || errno == EWOULDBLOCK) {
                        break;  // Would block
                    }
                    FUTON_LOGE_ERRNO("send failed");
                    return;
                }

                client.send_buffer.erase(client.send_buffer.begin(),
                                         client.send_buffer.begin() + n);
            }

            if (client.send_buffer.empty()) {
                client.send_pending = false;

                // Remove EPOLLOUT
                struct epoll_event ev{};
                ev.events = EPOLLIN | EPOLLET;
                ev.data.fd = client.fd;
                epoll_ctl(epoll_fd_, EPOLL_CTL_MOD, client.fd, &ev);

                // Close if in closing state
                if (client.state == ClientState::Closing) {
                    remove_client_locked(client.fd);
                }
            }
        }

        void handle_client_write(int fd) {
            std::lock_guard<std::mutex> lock(clients_mutex_);

            auto it = clients_.find(fd);
            if (it == clients_.end()) {
                return;
            }

            flush_send(it->second);
        }

        void remove_client(int fd) {
            std::lock_guard<std::mutex> lock(clients_mutex_);
            remove_client_locked(fd);
        }

        void remove_client_locked(int fd) {
            auto it = clients_.find(fd);
            if (it == clients_.end()) {
                return;
            }

            bool was_connected = (it->second.state == ClientState::Connected);

            epoll_ctl(epoll_fd_, EPOLL_CTL_DEL, fd, nullptr);
            close(fd);
            clients_.erase(it);

            if (was_connected) {
                owner_->client_count_.fetch_sub(1);
            }

            FUTON_LOGD("Client disconnected (fd=%d), clients=%d",
                       fd, owner_->client_count_.load());
        }
    };

    WebSocketServer::WebSocketServer()
            : impl_(std::make_unique<Impl>(this)) {
    }

    WebSocketServer::~WebSocketServer() {
        stop();
    }

    bool WebSocketServer::start(uint16_t port) {
        if (running_.load()) {
            FUTON_LOGD("WebSocket server already running");
            return true;
        }

        port_ = port;

        if (!impl_->start(port)) {
            return false;
        }

        running_.store(true);
        return true;
    }

    void WebSocketServer::stop() {
        if (!running_.load()) {
            return;
        }

        running_.store(false);
        impl_->stop();
        client_count_.store(0);
    }

    void WebSocketServer::broadcast_json(const std::string &json) {
        broadcast(json);
    }

    void WebSocketServer::broadcast(const std::string &data) {
        if (!running_.load()) {
            return;
        }

        impl_->broadcast(data);
    }

}  // namespace futon::debug
