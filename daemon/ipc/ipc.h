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

#ifndef FUTON_IPC_H
#define FUTON_IPC_H

/**
 * @file ipc.h
 * @brief Futon IPC Module - Binder IPC service implementation
 *
 * This module provides the Binder IPC interface for the Futon daemon.
 * It implements the IFutonDaemon AIDL interface and manages client callbacks.
 *
 * Usage:
 * @code
 * auto impl = std::make_shared<futon::ipc::IFutonDaemonImpl>();
 *
 * // Set component references
 * impl->set_vision_pipeline(vision_pipeline);
 * impl->set_inference_engine(inference_engine);
 * impl->set_input_injector(input_injector);
 * impl->set_hotpath_router(hotpath_router);
 * impl->set_debug_stream(debug_stream);
 *
 * // Register service
 * if (!futon::ipc::BinderService::register_service(impl)) {
 *     // Handle error
 * }
 *
 * // Start thread pool and join (blocking)
 * futon::ipc::BinderService::start_thread_pool();
 * futon::ipc::BinderService::join_thread_pool();
 * @endcode
 */

#include "binder_service.h"
#include "futon_daemon_impl.h"

#endif // FUTON_IPC_H
