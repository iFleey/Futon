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

/**
 * @file vision.h
 * @brief Main include header for the vision module.
 * 
 * This header provides convenient access to all vision module components.
 */

#ifndef FUTON_VISION_VISION_H
#define FUTON_VISION_VISION_H

// Buffer management
#include "vision/buffer/hardware_buffer_wrapper.h"

// Screen capture
#include "vision/capture/surface_control_capture.h"
#include "vision/capture/virtual_display.h"
#include "vision/capture/vision_pipeline.h"

// Display adapter
#include "vision/display/display_adapter.h"
#include "vision/display/display_transaction.h"

// EGL/GPU
#include "vision/egl/egl_environment.h"
#include "vision/egl/gpu_preprocessor.h"

// Fallback
#include "vision/fallback/java_fallback.h"
#include "vision/fallback/java_helper_receiver.h"

// Symbol loading
#include "vision/loader/elf_symbol_scanner.h"
#include "vision/loader/surface_control_loader.h"
#include "vision/loader/symbol_resolver.h"

// Pipeline
#include "vision/pipeline/buffer_queue_pipeline.h"
#include "vision/pipeline/gl_consumer_wrapper.h"

#endif // FUTON_VISION_VISION_H
