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

#ifndef FUTON_INFERENCE_PPOCRV5_H
#define FUTON_INFERENCE_PPOCRV5_H

/**
 * PPOCRv5 OCR Module
 *
 * Complete OCR pipeline with detection and recognition using LiteRT C++ API.
 * Supports GPU acceleration via OpenCL with automatic CPU fallback.
 *
 * Usage:
 *   auto engine = ppocrv5::OcrEngine::Create(det_path, rec_path, keys_path);
 *   auto results = engine->Process(image_data, width, height, stride);
 *   for (const auto& result : results) {
 *       // result.text, result.confidence, result.box
 *   }
 */

#include "ppocrv5_types.h"
#include "ocr_engine.h"
#include "text_detector.h"
#include "text_recognizer.h"

#endif  // FUTON_INFERENCE_PPOCRV5_H
