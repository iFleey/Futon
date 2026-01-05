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

#ifndef FUTON_CORE_PROCESS_INIT_H
#define FUTON_CORE_PROCESS_INIT_H

namespace futon::core {

    struct ProcessConfig {
        int sched_priority = 15;
        bool lock_memory = true;
        const char *pid_file = "/data/local/tmp/futon_daemon.pid";
        int watchdog_timeout_ms = 200;
    };

    class ProcessInit {
    public:
        static bool initialize(const ProcessConfig &config);

        static void cleanup();

        // Initialize binder thread pool (required for SurfaceFlinger communication)
        static bool init_binder();

    private:
        static bool lock_memory();

        static bool set_realtime_priority(int priority);

        static bool write_pid_file(const char *path);

        static bool remove_pid_file(const char *path);

        static const char *s_pid_file_path;
    };

} // namespace futon::core

#endif // FUTON_CORE_PROCESS_INIT_H
