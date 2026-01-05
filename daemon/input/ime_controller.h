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

#ifndef FUTON_INPUT_IME_CONTROLLER_H
#define FUTON_INPUT_IME_CONTROLLER_H

#include "core/error.h"
#include <string>

namespace futon::input {

    class ImeController {
    public:
        ImeController();

        ~ImeController();

        core::Result<void> initialize();

        core::Result<void> inject_text(const std::string &text, int timeout_ms = 3000);

        bool is_ime_enabled() const;

        bool is_ime_active() const;

    private:
        static constexpr const char *FUTON_IME_ID = "me.fleey.futon/.service.FutonImeService";
        static constexpr const char *SOCKET_NAME = "futon_ime_socket";

        bool initialized_ = false;
        std::string original_ime_;

        std::string get_current_ime() const;

        bool set_ime(const std::string &ime_id);

        core::Result<void> send_text_via_socket(const std::string &text, int timeout_ms);
    };

} // namespace futon::input

#endif // FUTON_INPUT_IME_CONTROLLER_H
