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

#ifndef FUTON_CORE_BRANDING_H
#define FUTON_CORE_BRANDING_H

#include <string>
#include <cstdint>

#ifndef FUTON_VERSION
#define FUTON_VERSION "dev"
#endif

#ifndef FUTON_GIT_HASH
#define FUTON_GIT_HASH "unknown"
#endif

namespace futon::core {

    class Branding {
    public:
        static constexpr const char *APP_NAME = "Futon";
        static constexpr const char *SERVICE_NAME = "futon_daemon";
        static constexpr const char *AUTHOR = "Fleey";
        static constexpr const char *REPOSITORY = "https://github.com/iFleey/Futon";
        static constexpr uint32_t BUILD_ID = 0x464C6579;

        static std::string get_startup_banner() {
            std::string banner = std::string(APP_NAME) + " Daemon";
            std::string ver = FUTON_VERSION;
            std::string hash = FUTON_GIT_HASH;
            if (ver != "dev") {
                banner += " v" + ver;
            }
            if (hash != "unknown" && hash.length() >= 7) {
                banner += " (" + hash.substr(0, 7) + ")";
            }
            return banner;
        }

        static std::string get_attribution() {
            return std::string("The original implementation by ") + AUTHOR + ". Repository: " +
                   REPOSITORY;
        }

    private:
        Branding() = delete;
    };

} // namespace futon::core

#endif // FUTON_CORE_BRANDING_H
