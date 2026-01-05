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

#ifndef FUTON_IPC_COMPAT_BINDER_MANAGER_H
#define FUTON_IPC_COMPAT_BINDER_MANAGER_H

#include <android/binder_ibinder.h>
#include <android/binder_status.h>
#include <dlfcn.h>

// These functions are available in libbinder_ndk.so but not declared in NDK headers
// We load them dynamically at runtime

extern "C" {

// Service Manager functions
typedef binder_exception_t (*AServiceManager_addService_t)(AIBinder *binder, const char *instance);
typedef AIBinder *(*AServiceManager_checkService_t)(const char *instance);
typedef AIBinder *(*AServiceManager_getService_t)(const char *instance);

// Binder Process functions
typedef void (*ABinderProcess_startThreadPool_t)();
typedef void (*ABinderProcess_joinThreadPool_t)();
typedef void (*ABinderProcess_setThreadPoolMaxThreadCount_t)(uint32_t numThreads);

}

namespace futon::ipc::compat {

    class BinderManagerCompat {
    public:
        static BinderManagerCompat &instance() {
            static BinderManagerCompat inst;
            return inst;
        }

        bool is_available() const { return available_; }

        binder_exception_t addService(AIBinder *binder, const char *instance) {
            if (addService_) return addService_(binder, instance);
            return EX_UNSUPPORTED_OPERATION;
        }

        AIBinder *checkService(const char *instance) {
            if (checkService_) return checkService_(instance);
            return nullptr;
        }

        AIBinder *getService(const char *instance) {
            if (getService_) return getService_(instance);
            return nullptr;
        }

        void startThreadPool() {
            if (startThreadPool_) startThreadPool_();
        }

        void joinThreadPool() {
            if (joinThreadPool_) joinThreadPool_();
        }

        void setThreadPoolMaxThreadCount(uint32_t numThreads) {
            if (setThreadPoolMaxThreadCount_) setThreadPoolMaxThreadCount_(numThreads);
        }

    private:
        BinderManagerCompat() {
            handle_ = dlopen("libbinder_ndk.so", RTLD_NOW);
            if (!handle_) {
                available_ = false;
                return;
            }

            addService_ = reinterpret_cast<AServiceManager_addService_t>(
                    dlsym(handle_, "AServiceManager_addService"));
            checkService_ = reinterpret_cast<AServiceManager_checkService_t>(
                    dlsym(handle_, "AServiceManager_checkService"));
            getService_ = reinterpret_cast<AServiceManager_getService_t>(
                    dlsym(handle_, "AServiceManager_getService"));
            startThreadPool_ = reinterpret_cast<ABinderProcess_startThreadPool_t>(
                    dlsym(handle_, "ABinderProcess_startThreadPool"));
            joinThreadPool_ = reinterpret_cast<ABinderProcess_joinThreadPool_t>(
                    dlsym(handle_, "ABinderProcess_joinThreadPool"));
            setThreadPoolMaxThreadCount_ = reinterpret_cast<ABinderProcess_setThreadPoolMaxThreadCount_t>(
                    dlsym(handle_, "ABinderProcess_setThreadPoolMaxThreadCount"));

            available_ = (addService_ && startThreadPool_ && joinThreadPool_);
        }

        ~BinderManagerCompat() {
            if (handle_) dlclose(handle_);
        }

        void *handle_ = nullptr;
        bool available_ = false;

        AServiceManager_addService_t addService_ = nullptr;
        AServiceManager_checkService_t checkService_ = nullptr;
        AServiceManager_getService_t getService_ = nullptr;
        ABinderProcess_startThreadPool_t startThreadPool_ = nullptr;
        ABinderProcess_joinThreadPool_t joinThreadPool_ = nullptr;
        ABinderProcess_setThreadPoolMaxThreadCount_t setThreadPoolMaxThreadCount_ = nullptr;
    };

// Convenience macros to match original API
    inline binder_exception_t AServiceManager_addService(AIBinder *binder, const char *instance) {
        return BinderManagerCompat::instance().addService(binder, instance);
    }

    inline AIBinder *AServiceManager_checkService(const char *instance) {
        return BinderManagerCompat::instance().checkService(instance);
    }

    inline AIBinder *AServiceManager_getService(const char *instance) {
        return BinderManagerCompat::instance().getService(instance);
    }

    inline void ABinderProcess_startThreadPool() {
        BinderManagerCompat::instance().startThreadPool();
    }

    inline void ABinderProcess_joinThreadPool() {
        BinderManagerCompat::instance().joinThreadPool();
    }

    inline void ABinderProcess_setThreadPoolMaxThreadCount(uint32_t numThreads) {
        BinderManagerCompat::instance().setThreadPoolMaxThreadCount(numThreads);
    }

}  // namespace futon::ipc::compat

// Make functions available in global namespace for compatibility
using futon::ipc::compat::AServiceManager_addService;
using futon::ipc::compat::AServiceManager_checkService;
using futon::ipc::compat::AServiceManager_getService;
using futon::ipc::compat::ABinderProcess_startThreadPool;
using futon::ipc::compat::ABinderProcess_joinThreadPool;
using futon::ipc::compat::ABinderProcess_setThreadPoolMaxThreadCount;

#endif  // FUTON_IPC_COMPAT_BINDER_MANAGER_H
