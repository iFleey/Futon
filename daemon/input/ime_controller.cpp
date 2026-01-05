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

#include "ime_controller.h"
#include "shell_executor.h"

#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <cstring>

using namespace futon::core;

namespace futon::input {

    ImeController::ImeController() = default;

    ImeController::~ImeController() {
        if (!original_ime_.empty()) {
            set_ime(original_ime_);
        }
    }

    std::string ImeController::get_current_ime() const {
        return ShellExecutor::instance().exec("settings get secure default_input_method");
    }

    bool ImeController::set_ime(const std::string &ime_id) {
        char cmd[256];
        snprintf(cmd, sizeof(cmd), "ime set %s", ime_id.c_str());
        return ShellExecutor::instance().exec_status(cmd) == 0;
    }

    bool ImeController::is_ime_enabled() const {
        std::string list = ShellExecutor::instance().exec("ime list -s");
        return list.find(FUTON_IME_ID) != std::string::npos;
    }

    bool ImeController::is_ime_active() const {
        return get_current_ime() == FUTON_IME_ID;
    }

    Result<void> ImeController::initialize() {
        if (initialized_) return Result<void>::ok();

        if (!is_ime_enabled()) {
            char cmd[256];
            snprintf(cmd, sizeof(cmd), "ime enable %s", FUTON_IME_ID);
            ShellExecutor::instance().exec_status(cmd, 5000);

            if (!is_ime_enabled()) {
                return Result<void>::err(FutonError::NotInitialized,
                                         "FutonImeService not enabled. Enable in Settings");
            }
        }

        initialized_ = true;
        return Result<void>::ok();
    }

    Result<void> ImeController::send_text_via_socket(const std::string &text, int timeout_ms) {
        int sock = socket(AF_UNIX, SOCK_STREAM, 0);
        if (sock < 0) {
            return Result<void>::err(FutonError::InternalError, "Socket failed");
        }

        struct timeval tv;
        tv.tv_sec = timeout_ms / 1000;
        tv.tv_usec = (timeout_ms % 1000) * 1000;
        setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
        setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

        struct sockaddr_un addr{};
        addr.sun_family = AF_UNIX;
        addr.sun_path[0] = '\0';
        strncpy(addr.sun_path + 1, SOCKET_NAME, sizeof(addr.sun_path) - 2);
        socklen_t addr_len = offsetof(struct sockaddr_un, sun_path) + 1 + strlen(SOCKET_NAME);

        bool connected = false;
        int delay_us = 20000;
        for (int i = 0; i < 15; i++) {
            if (connect(sock, (struct sockaddr *) &addr, addr_len) == 0) {
                connected = true;
                break;
            }
            usleep(delay_us);
            delay_us = (delay_us * 3) / 2;
        }

        if (!connected) {
            close(sock);
            return Result<void>::err(FutonError::InternalError, "Connect failed");
        }

        ssize_t sent = send(sock, text.c_str(), text.size(), 0);
        if (sent < 0) {
            close(sock);
            return Result<void>::err(FutonError::InternalError, "Send failed");
        }

        char ack = 1;
        recv(sock, &ack, 1, 0);
        close(sock);

        if (ack != 0) {
            return Result<void>::err(FutonError::InternalError, "IME NAK");
        }

        return Result<void>::ok();
    }

    Result<void> ImeController::inject_text(const std::string &text, int timeout_ms) {
        if (!initialized_) {
            auto r = initialize();
            if (!r.is_ok()) return r;
        }

        if (text.empty()) return Result<void>::ok();

        original_ime_ = get_current_ime();

        bool need_restore = false;
        if (!is_ime_active()) {
            if (!set_ime(FUTON_IME_ID)) {
                return Result<void>::err(FutonError::InternalError, "IME switch failed");
            }
            need_restore = true;

            usleep(300000);
        }

        auto result = send_text_via_socket(text, timeout_ms);

        if (need_restore && !original_ime_.empty()) {
            usleep(50000);
            set_ime(original_ime_);
            original_ime_.clear();
        }

        return result;
    }

} // namespace futon::input
