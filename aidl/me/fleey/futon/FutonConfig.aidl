/*
 * Futon - Android Automation Daemon Interface
 * Copyright (C) 2025 Fleey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.fleey.futon;

parcelable FutonConfig {
    int captureWidth;
    int captureHeight;
    int targetFps;
    String modelPath;
    String ocrDetModelPath;
    String ocrRecModelPath;
    String ocrKeysPath;
    float minConfidence;
    boolean enableDebugStream;
    int debugStreamPort;
    int statusUpdateIntervalMs;
    int bufferPoolSize;
    int hotPathNoMatchThreshold;
    
    /** User-selected touch input device path. Empty string means auto-detect. */
    String touchDevicePath;
}
