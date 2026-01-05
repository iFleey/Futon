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

#ifndef FUTON_IPC_FUTON_DAEMON_IMPL_H
#define FUTON_IPC_FUTON_DAEMON_IMPL_H

#include <memory>
#include <mutex>
#include <vector>
#include <string>
#include <cstdint>
#include <atomic>
#include <functional>
#include <unordered_map>

#include "ipc/compat/binder_auto_utils.h"
#include <android/binder_ibinder.h>

// AIDL generated headers (local stubs)
#include "ipc/aidl_stub/me/fleey/futon/BnFutonDaemon.h"
#include "ipc/aidl_stub/me/fleey/futon/IStatusCallback.h"
#include "ipc/aidl_stub/me/fleey/futon/IBufferReleaseCallback.h"
#include "ipc/aidl_stub/me/fleey/futon/DaemonStatus.h"
#include "ipc/aidl_stub/me/fleey/futon/FutonConfig.h"
#include "ipc/aidl_stub/me/fleey/futon/DetectionResult.h"
#include "ipc/aidl_stub/me/fleey/futon/SessionStatus.h"
#include "ipc/aidl_stub/me/fleey/futon/SystemStatus.h"
#include "ipc/aidl_stub/me/fleey/futon/AuthenticateResult.h"
#include "ipc/aidl_stub/me/fleey/futon/ScreenshotResult.h"
#include "ipc/aidl_stub/me/fleey/futon/CryptoHandshake.h"
#include "ipc/aidl_stub/me/fleey/futon/AttestationCertificate.h"
#include "ipc/aidl_stub/me/fleey/futon/InputDeviceEntry.h"

// Auth module
#include "core/auth/auth.h"

// Crypto module
#include "core/crypto/stream_cipher.h"

// Forward declarations for internal components
namespace futon::vision { class VisionPipeline; }
namespace futon::inference::ppocrv5 { class OcrEngine; }
namespace futon::input { class InputInjector; }
namespace futon::hotpath { class HotPathRouter; }
namespace futon::debug { class DebugStream; }
namespace futon::core { class SystemStatusDetector; }

namespace futon::ipc {

    constexpr int32_t DAEMON_PROTOCOL_VERSION = (1 << 24) | (0 << 16) | (0 << 8) | 0x4C;
    constexpr const char *MODEL_DIRECTORY = "/data/adb/futon/models";

// Callback wrapper
    struct CallbackEntry {
        std::shared_ptr<aidl::me::fleey::futon::IStatusCallback> callback;
        bool valid = true;
    };

// Buffer release callback entry
    struct BufferCallbackEntry {
        std::shared_ptr<aidl::me::fleey::futon::IBufferReleaseCallback> callback;
        bool valid = true;
    };

// Tracked buffer for screenshot management
    struct TrackedBuffer {
        int32_t buffer_id;
        AHardwareBuffer *buffer;
        int64_t timestamp_ns;
        int32_t width;
        int32_t height;
        uid_t owner_uid;
    };

// IFutonDaemon implementation using AIDL NDK backend
    class IFutonDaemonImpl : public aidl::me::fleey::futon::BnFutonDaemon {
    public:
        IFutonDaemonImpl();

        ~IFutonDaemonImpl() override;

        // Disable copy
        IFutonDaemonImpl(const IFutonDaemonImpl &) = delete;

        IFutonDaemonImpl &operator=(const IFutonDaemonImpl &) = delete;

        // Initialize with auth manager
        bool initialize(std::shared_ptr<core::auth::AuthManager> auth_manager);

        // ========== Version & Capability ==========
        ndk::ScopedAStatus getVersion(int32_t *_aidl_return) override;

        ndk::ScopedAStatus getCapabilities(int32_t *_aidl_return) override;

        ndk::ScopedAStatus getSystemStatus(
                aidl::me::fleey::futon::SystemStatus *_aidl_return) override;

        // ========== Authentication ==========
        ndk::ScopedAStatus getChallenge(std::vector<uint8_t> *_aidl_return) override;

        ndk::ScopedAStatus authenticate(
                const std::vector<uint8_t> &signature,
                const std::string &instanceId,
                aidl::me::fleey::futon::AuthenticateResult *_aidl_return) override;

        ndk::ScopedAStatus verifyAttestation(
                const std::vector<std::vector<uint8_t>> &attestationChain) override;

        ndk::ScopedAStatus checkSession(
                const std::string &instanceId,
                aidl::me::fleey::futon::SessionStatus *_aidl_return) override;

        // ========== Encrypted Channel ==========
        ndk::ScopedAStatus initCryptoChannel(
                const std::vector<uint8_t> &clientDhPublic,
                aidl::me::fleey::futon::CryptoHandshake *_aidl_return) override;

        ndk::ScopedAStatus sendControlMessage(
                const std::vector<uint8_t> &encryptedMessage,
                std::vector<uint8_t> *_aidl_return) override;

        ndk::ScopedAStatus sendDataMessage(
                const std::vector<uint8_t> &encryptedData,
                std::vector<uint8_t> *_aidl_return) override;

        ndk::ScopedAStatus rotateChannelKeys(
                aidl::me::fleey::futon::CryptoHandshake *_aidl_return) override;

        // ========== Callback Registration ==========
        ndk::ScopedAStatus registerStatusCallback(
                const std::shared_ptr<aidl::me::fleey::futon::IStatusCallback> &callback) override;

        ndk::ScopedAStatus unregisterStatusCallback(
                const std::shared_ptr<aidl::me::fleey::futon::IStatusCallback> &callback) override;

        ndk::ScopedAStatus registerBufferReleaseCallback(
                const std::shared_ptr<aidl::me::fleey::futon::IBufferReleaseCallback> &callback) override;

        ndk::ScopedAStatus unregisterBufferReleaseCallback(
                const std::shared_ptr<aidl::me::fleey::futon::IBufferReleaseCallback> &callback) override;

        // ========== Configuration ==========
        ndk::ScopedAStatus configure(const aidl::me::fleey::futon::FutonConfig &config) override;

        ndk::ScopedAStatus configureHotPath(const std::string &jsonRules) override;

        // ========== Input Device Discovery ==========
        ndk::ScopedAStatus listInputDevices(
                std::vector<aidl::me::fleey::futon::InputDeviceEntry> *_aidl_return) override;

        // ========== Perception ==========
        ndk::ScopedAStatus getScreenshot(
                aidl::me::fleey::futon::ScreenshotResult *_aidl_return) override;

        ndk::ScopedAStatus releaseScreenshot(int32_t bufferId) override;

        ndk::ScopedAStatus requestPerception(
                std::vector<aidl::me::fleey::futon::DetectionResult> *_aidl_return) override;

        // ========== Input Injection ==========
        ndk::ScopedAStatus tap(int32_t x, int32_t y) override;

        ndk::ScopedAStatus longPress(int32_t x, int32_t y, int32_t durationMs) override;

        ndk::ScopedAStatus doubleTap(int32_t x, int32_t y) override;

        ndk::ScopedAStatus swipe(
                int32_t x1, int32_t y1,
                int32_t x2, int32_t y2,
                int32_t durationMs) override;

        ndk::ScopedAStatus scroll(
                int32_t x, int32_t y,
                int32_t direction, int32_t distance) override;

        ndk::ScopedAStatus pinch(
                int32_t centerX, int32_t centerY,
                int32_t startDistance, int32_t endDistance,
                int32_t durationMs) override;

        ndk::ScopedAStatus multiTouch(
                const std::vector<int32_t> &xs,
                const std::vector<int32_t> &ys,
                const std::vector<int32_t> &actions) override;

        ndk::ScopedAStatus inputText(const std::string &text) override;

        ndk::ScopedAStatus pressKey(int32_t keyCode) override;

        // ========== System Actions ==========
        ndk::ScopedAStatus pressBack() override;

        ndk::ScopedAStatus pressHome() override;

        ndk::ScopedAStatus pressRecents() override;

        ndk::ScopedAStatus openNotifications() override;

        ndk::ScopedAStatus openQuickSettings() override;

        ndk::ScopedAStatus launchApp(const std::string &packageName) override;

        ndk::ScopedAStatus launchActivity(
                const std::string &packageName,
                const std::string &activityName) override;

        // ========== Utility Actions ==========
        ndk::ScopedAStatus wait(int32_t durationMs) override;

        ndk::ScopedAStatus saveScreenshot(const std::string &filePath) override;

        ndk::ScopedAStatus requestIntervention(
                const std::string &reason,
                const std::string &actionHint) override;

        ndk::ScopedAStatus call(
                const std::string &command,
                const std::string &argsJson,
                std::string *_aidl_return) override;

        // ========== Automation Control ==========
        ndk::ScopedAStatus startHotPath() override;

        ndk::ScopedAStatus stopAutomation() override;

        ndk::ScopedAStatus executeTask(
                const std::string &taskJson,
                int64_t *_aidl_return) override;

        // ========== Debug APIs ==========
        ndk::ScopedAStatus debugInjectTap(int32_t x, int32_t y) override;

        ndk::ScopedAStatus debugInjectSwipe(
                int32_t x1, int32_t y1,
                int32_t x2, int32_t y2,
                int32_t durationMs) override;

        ndk::ScopedAStatus debugRunDetection(
                std::vector<aidl::me::fleey::futon::DetectionResult> *_aidl_return) override;

        // ========== Legacy Compatibility (API < 34) ==========
        ndk::ScopedAStatus getScreenshotBytes(
                std::vector<uint8_t> *_aidl_return) override;

        // ========== Model Management ==========
        ndk::ScopedAStatus reloadModels(bool *_aidl_return) override;

        ndk::ScopedAStatus getModelStatus(std::string *_aidl_return) override;

        // Internal notification methods (called by pipeline components)
        void notify_status_update();

        void notify_automation_complete(bool success, const std::string &message);

        void notify_error(int code, const std::string &message);

        void notify_detection_result(
                const std::vector<aidl::me::fleey::futon::DetectionResult> &results);

        // Component setters (for dependency injection)
        void set_vision_pipeline(std::shared_ptr<futon::vision::VisionPipeline> pipeline);

        void set_ppocrv5_engine(std::shared_ptr<futon::inference::ppocrv5::OcrEngine> engine);

        void set_input_injector(std::shared_ptr<futon::input::InputInjector> injector);

        void set_hotpath_router(std::shared_ptr<futon::hotpath::HotPathRouter> router);

        void set_debug_stream(std::shared_ptr<futon::debug::DebugStream> stream);

        // Status update callback for periodic updates
        using StatusUpdateCallback = std::function<void()>;

        void set_status_update_callback(StatusUpdateCallback callback);

        // Pipeline control callbacks
        using PipelineStartCallback = std::function<bool(
                const aidl::me::fleey::futon::FutonConfig &)>;
        using PipelineStopCallback = std::function<void()>;

        void set_pipeline_start_callback(PipelineStartCallback callback);

        void set_pipeline_stop_callback(PipelineStopCallback callback);

        // Update runtime statistics (called by pipeline thread)
        void update_stats(float fps, float latency_ms, int frame_count);

        void set_active_delegate(const std::string &delegate);

        void set_hot_path_progress(int progress);

        // Check if daemon is running
        bool is_running() const { return running_.load(); }

        // Get current configuration
        const aidl::me::fleey::futon::FutonConfig &get_config() const { return config_; }

    private:
        // Authentication
        std::shared_ptr<core::auth::AuthManager> auth_manager_;
        std::string current_instance_id_;
        std::string pending_attestation_key_id_;
        mutable std::mutex auth_mutex_;

        // Crypto channel
        std::unique_ptr<core::crypto::DualChannelCrypto> crypto_channel_;
        std::string crypto_session_id_;
        mutable std::mutex crypto_mutex_;

        // Check if caller is authenticated
        bool check_authenticated(const char *method_name);

        // Thread-safe callback management
        mutable std::mutex callbacks_mutex_;
        std::vector<CallbackEntry> callbacks_;
        std::vector<BufferCallbackEntry> buffer_callbacks_;

        // Buffer tracking
        mutable std::mutex buffers_mutex_;
        std::unordered_map<int32_t, TrackedBuffer> tracked_buffers_;
        std::atomic<int32_t> next_buffer_id_{1};

        // State
        std::atomic<bool> running_{false};
        std::atomic<int64_t> last_status_timestamp_ns_{0};
        std::atomic<int> frame_count_{0};
        std::atomic<float> current_fps_{0.0f};
        std::atomic<float> current_latency_ms_{0.0f};
        std::atomic<int> hot_path_progress_{0};
        std::string active_delegate_{"none"};
        mutable std::mutex delegate_mutex_;

        // Configuration
        aidl::me::fleey::futon::FutonConfig config_;
        mutable std::mutex config_mutex_;

        // Component references (weak to avoid circular dependencies)
        std::weak_ptr<futon::vision::VisionPipeline> vision_pipeline_;
        std::weak_ptr<futon::inference::ppocrv5::OcrEngine> ppocrv5_engine_;
        std::weak_ptr<futon::input::InputInjector> input_injector_;
        std::weak_ptr<futon::hotpath::HotPathRouter> hotpath_router_;
        std::weak_ptr<futon::debug::DebugStream> debug_stream_;

        // Status update callback
        StatusUpdateCallback status_update_callback_;
        std::mutex status_callback_mutex_;

        // Pipeline control callbacks
        PipelineStartCallback pipeline_start_callback_;
        PipelineStopCallback pipeline_stop_callback_;
        std::mutex pipeline_callback_mutex_;

        // System status detector
        std::unique_ptr<futon::core::SystemStatusDetector> system_status_detector_;

        // Helper methods
        void remove_invalid_callbacks();

        aidl::me::fleey::futon::DaemonStatus build_status() const;

        int64_t get_current_time_ns() const;

        // Internal start/stop (no auth check, called by authenticated methods)
        ndk::ScopedAStatus start_internal();

        ndk::ScopedAStatus stop_internal();

        // Validate callback is still alive
        bool is_callback_valid(const CallbackEntry &entry) const;

        // Buffer management helpers
        int32_t track_buffer(AHardwareBuffer *buffer, int32_t width, int32_t height, uid_t owner);

        void release_tracked_buffer(int32_t buffer_id);
    };

} // namespace futon::ipc

#endif // FUTON_IPC_FUTON_DAEMON_IMPL_H
