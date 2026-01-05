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

#include "binder_service.h"
#include "futon_daemon_impl.h"
#include "core/error.h"
#include "ipc/compat/binder_auto_utils.h"
#include "ipc/compat/binder_manager.h"

using namespace futon::core;

namespace futon::ipc {

// Static member initialization
    std::shared_ptr<IFutonDaemonImpl> BinderService::s_impl;
    std::atomic<bool> BinderService::s_registered{false};
    std::atomic<bool> BinderService::s_thread_pool_started{false};

    bool BinderService::register_service(std::shared_ptr<IFutonDaemonImpl> impl) {
        if (!impl) {
            FUTON_LOGE("register_service: null implementation");
            return false;
        }

        if (s_registered.load()) {
            FUTON_LOGW("Service already registered");
            return true;
        }

        FUTON_LOGI("Registering service: %s", kFutonServiceName);

        // Get the binder object from the implementation
        ndk::SpAIBinder binder = impl->asBinder();
        if (!binder.get()) {
            FUTON_LOGE("Failed to get binder from implementation");
            return false;
        }

        // Register with ServiceManager
        binder_exception_t exception = AServiceManager_addService(
                binder.get(), kFutonServiceName);

        if (exception != EX_NONE) {
            FUTON_LOGE("AServiceManager_addService failed: %d", exception);
            return false;
        }

        s_impl = impl;
        s_registered.store(true);

        FUTON_LOGI("Service registered successfully: %s", kFutonServiceName);
        return true;
    }

    void BinderService::start_thread_pool() {
        if (s_thread_pool_started.load()) {
            FUTON_LOGW("Thread pool already started");
            return;
        }

        FUTON_LOGI("Starting Binder thread pool");
        ABinderProcess_startThreadPool();
        s_thread_pool_started.store(true);
        FUTON_LOGI("Binder thread pool started");
    }

    void BinderService::join_thread_pool() {
        if (!s_thread_pool_started.load()) {
            FUTON_LOGW("Thread pool not started, starting now");
            start_thread_pool();
        }

        FUTON_LOGI("Joining Binder thread pool (blocking)");
        ABinderProcess_joinThreadPool();
        FUTON_LOGI("Binder thread pool exited");
    }

    bool BinderService::is_registered() {
        return s_registered.load();
    }

    std::shared_ptr<IFutonDaemonImpl> BinderService::get_impl() {
        return s_impl;
    }

    void BinderService::unregister_service() {
        if (!s_registered.load()) {
            FUTON_LOGW("Service not registered");
            return;
        }

        FUTON_LOGI("Unregistering service: %s", kFutonServiceName);
        s_impl.reset();
        s_registered.store(false);
        FUTON_LOGI("Service unregistered");
    }

} // namespace futon::ipc
