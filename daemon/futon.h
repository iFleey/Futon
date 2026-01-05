/*
 * Futon - Android Automation Daemon
 * Copyright (C) 2025 Fleey
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#ifndef FUTON_H
#define FUTON_H

#include "core/core.h"
#include "vision/vision.h"
#include "inference/inference.h"
#include "input/input.h"
#include "ipc/ipc.h"
#include "hotpath/hotpath.h"
#include "debug/debug.h"

#define FUTON_API_VERSION ((1 << 16) | (0 << 8) | 0x4C)
#define FUTON_MAGIC 0x464C

#endif // FUTON_H
