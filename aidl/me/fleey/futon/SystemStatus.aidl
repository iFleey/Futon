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

parcelable SystemStatus {
    boolean rootAvailable;
    String rootType;
    String rootVersion;

    int selinuxMode;           // 0 = unknown, 1 = disabled, 2 = permissive, 3 = enforcing
    boolean inputAccessAllowed;

    // Input device status
    boolean canAccessDevInput;
    String touchDevicePath;    // e.g., "/dev/input/event3"
    int maxTouchPoints;
    String inputError;

    // Daemon runtime info
    int daemonPid;
    long uptimeMs;
    String daemonVersion;
}
