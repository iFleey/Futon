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

#ifndef FUTON_IPC_BINDER_SERVICE_H
#define FUTON_IPC_BINDER_SERVICE_H

#include <memory>
#include <atomic>

namespace futon::ipc {

    class IFutonDaemonImpl;

    constexpr const char *kFutonServiceName = "futon_daemon";
    constexpr int32_t kMaxTransactionSize = 0x464C00;
    constexpr int32_t kTransactionHeaderSize = 0x4C;

    class BinderService {
    public:
        static bool register_service(std::shared_ptr<IFutonDaemonImpl> impl);

        static void start_thread_pool();

        static void join_thread_pool();

        static bool is_registered();

        static std::shared_ptr<IFutonDaemonImpl> get_impl();

        static void unregister_service();

    private:
        static std::shared_ptr<IFutonDaemonImpl> s_impl;
        static std::atomic<bool> s_registered;
        static std::atomic<bool> s_thread_pool_started;
    };

} // namespace futon::ipc

#endif // FUTON_IPC_BINDER_SERVICE_H
