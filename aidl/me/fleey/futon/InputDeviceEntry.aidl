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

/**
 * Information about an input device discovered on the system.
 * Used for user selection of touch input device.
 */
parcelable InputDeviceEntry {
    /** Device path, e.g., "/dev/input/event3" */
    String path;
    
    /** Device name from kernel, e.g., "fts_ts" */
    String name;
    
    /** Whether this device has touchscreen capabilities (ABS_MT_* or ABS_X/Y + BTN_TOUCH) */
    boolean isTouchscreen;
    
    /** Whether multi-touch is supported */
    boolean supportsMultiTouch;
    
    /** Multi-touch protocol: 0=single, 1=protocol_a, 2=protocol_b */
    int mtProtocol;
    
    /** Maximum X coordinate */
    int maxX;
    
    /** Maximum Y coordinate */
    int maxY;
    
    /** Maximum simultaneous touch points */
    int maxTouchPoints;
    
    /** Probability score (0-100) that this is the primary touchscreen */
    int touchscreenProbability;
    
    /** Reason for the probability score */
    String probabilityReason;
}
