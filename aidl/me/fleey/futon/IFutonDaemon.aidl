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

import android.hardware.HardwareBuffer;
import me.fleey.futon.FutonConfig;
import me.fleey.futon.DaemonStatus;
import me.fleey.futon.DetectionResult;
import me.fleey.futon.ScreenshotResult;
import me.fleey.futon.SessionStatus;
import me.fleey.futon.SystemStatus;
import me.fleey.futon.AuthenticateResult;
import me.fleey.futon.IStatusCallback;
import me.fleey.futon.IBufferReleaseCallback;
import me.fleey.futon.CryptoHandshake;
import me.fleey.futon.AttestationCertificate;
import me.fleey.futon.InputDeviceEntry;

interface IFutonDaemon {
    // ========== Version & Capability ==========
    int getVersion();
    int getCapabilities();
    SystemStatus getSystemStatus();

    // ========== Authentication ==========
    byte[] getChallenge();
    AuthenticateResult authenticate(in byte[] signature, String instanceId);
    void verifyAttestation(in AttestationCertificate[] attestationChain);
    SessionStatus checkSession(String instanceId);

    // ========== Encrypted Channel (Double Ratchet + Stream Cipher) ==========
    // Initiate crypto handshake, returns daemon's DH public key
    CryptoHandshake initCryptoChannel(in byte[] clientDhPublic);

    // Send encrypted control message (Double Ratchet)
    byte[] sendControlMessage(in byte[] encryptedMessage);

    // Send encrypted data (Stream Cipher) - for large payloads
    byte[] sendDataMessage(in byte[] encryptedData);

    // Request key rotation
    CryptoHandshake rotateChannelKeys();

    // ========== Callback Registration ==========
    void registerStatusCallback(IStatusCallback callback);
    void unregisterStatusCallback(IStatusCallback callback);
    void registerBufferReleaseCallback(IBufferReleaseCallback callback);
    void unregisterBufferReleaseCallback(IBufferReleaseCallback callback);

    // ========== Configuration ==========
    void configure(in FutonConfig config);
    void configureHotPath(in String jsonRules);

    // ========== Input Device Discovery ==========
    /** List all input devices with touchscreen probability scores */
    InputDeviceEntry[] listInputDevices();

    // ========== Perception ==========
    ScreenshotResult getScreenshot();
    void releaseScreenshot(int bufferId);
    DetectionResult[] requestPerception();

    // ========== Input Injection ==========
    void tap(int x, int y);
    void longPress(int x, int y, int durationMs);
    void doubleTap(int x, int y);
    void swipe(int x1, int y1, int x2, int y2, int durationMs);
    void scroll(int x, int y, int direction, int distance);
    void pinch(int centerX, int centerY, int startDistance, int endDistance, int durationMs);
    void multiTouch(in int[] xs, in int[] ys, in int[] actions);
    void inputText(String text);
    void pressKey(int keyCode);

    // ========== System Actions ==========
    void pressBack();
    void pressHome();
    void pressRecents();
    void openNotifications();
    void openQuickSettings();
    void launchApp(String packageName);
    void launchActivity(String packageName, String activityName);

    // ========== Utility Actions ==========
    void wait(int durationMs);
    void saveScreenshot(String filePath);

    /**
     * Request user intervention when automation cannot proceed.
     * This notifies the user that manual action is required.
     * @param reason Description of why intervention is needed
     * @param actionHint Suggested action for the user (optional)
     */
    void requestIntervention(String reason, String actionHint);

    /**
     * Execute a built-in command with arguments.
     * Supports extensible command system for automation DSL.
     *
     * @param command Command name (e.g., "http.get", "webhook")
     * @param argsJson JSON-encoded arguments for the command
     * @return JSON-encoded result
     */
    String call(String command, String argsJson);

    // ========== Automation Control ==========
    void startHotPath();
    void stopAutomation();
    long executeTask(String taskJson);

    // ========== Model Management ==========
    /**
     * Reload models from the model directory.
     * Called after app deploys new model files.
     * @return true if models loaded successfully
     */
    boolean reloadModels();

    /**
     * Get current model status.
     * @return JSON string with model loading status
     */
    String getModelStatus();

    // ========== Debug APIs (no-op in release) ==========
    void debugInjectTap(int x, int y);
    void debugInjectSwipe(int x1, int y1, int x2, int y2, int durationMs);
    DetectionResult[] debugRunDetection();

    // ========== Legacy Compatibility (API < 34) ==========
    /**
     * Get screenshot as raw RGBA bytes (fallback for API < 34).
     * Less efficient than HardwareBuffer but works on all Android versions.
     * Use getScreenshot() when HardwareBuffer transfer is available.
     * @return Raw RGBA pixel data with metadata prefix:
     *         [4 bytes: width][4 bytes: height][8 bytes: timestamp][width*height*4 bytes: RGBA]
     */
    byte[] getScreenshotBytes();
}
