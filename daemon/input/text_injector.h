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

#ifndef FUTON_INPUT_TEXT_INJECTOR_H
#define FUTON_INPUT_TEXT_INJECTOR_H

#include "core/error.h"
#include <string>
#include <memory>
#include <sys/types.h>

namespace futon::input {

    class ImeController;

    class TextInjector {
    public:
        TextInjector();

        ~TextInjector();

        TextInjector(const TextInjector &) = delete;

        TextInjector &operator=(const TextInjector &) = delete;

        core::Result<void> initialize();

        void shutdown();

        core::Result<void> inject_text(const std::string &text, int timeout_ms = 3000);

        bool is_available() const;

        static pid_t get_foreground_pid();

    private:
        bool initialized_ = false;
        std::unique_ptr<ImeController> ime_controller_;
    };

} // namespace futon::input

#endif // FUTON_INPUT_TEXT_INJECTOR_H
