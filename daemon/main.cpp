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

#include "futon.h"

#include "core/core.h"
#include "core/branding.h"
#include "core/auth/auth.h"
#include "core/auth/hardened_config.h"
#include "core/auth/key_whitelist.h"
#include "core/auth/attestation_verifier.h"
#include "core/sandbox/sandbox.h"
#include "ipc/ipc.h"
#include "vision/vision.h"
#include "inference/inference.h"
#include "inference/ppocrv5/ppocrv5.h"
#include "input/input.h"
#include "input/shell_executor.h"
#include "debug/debug.h"
#include "hotpath/hotpath.h"

#include "ipc/aidl_stub/me/fleey/futon/FutonConfig.h"

#include <memory>
#include <cstring>
#include <thread>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <unistd.h>

using namespace futon::core;
using namespace futon::core::auth;
using namespace futon::core::sandbox;
using namespace futon::ipc;
using namespace futon::vision;
using namespace futon::inference;
using namespace futon::inference::ppocrv5;
using namespace futon::input;
using namespace futon::debug;
using namespace futon::hotpath;

using Branding = futon::core::Branding;

// Global components for signal handler access
static std::atomic<bool> g_shutdown_requested{false};
static std::atomic<bool> g_skip_sig_check{false};
static std::shared_ptr<Watchdog> g_watchdog;
static std::shared_ptr<VisionPipeline> g_vision_pipeline;
static std::shared_ptr<ppocrv5::OcrEngine> g_ppocrv5_engine;
static std::shared_ptr<InputInjector> g_input_injector;
static std::shared_ptr<DebugStream> g_debug_stream;
static std::shared_ptr<HotPathRouter> g_hotpath_router;
static std::shared_ptr<IFutonDaemonImpl> g_daemon_impl;
static std::shared_ptr<AuthManager> g_auth_manager;

// Auth cleanup thread
static std::thread g_auth_cleanup_thread;
static std::atomic<bool> g_auth_cleanup_running{false};

// Pipeline thread
static std::thread g_pipeline_thread;
static std::atomic<bool> g_pipeline_running{false};

// Default paths for models and dictionaries
static constexpr const char *MODEL_DIRECTORY = "/data/adb/futon/models";
static constexpr const char *kDefaultOcrModelPath = "/data/adb/futon/models/ocr_rec_fp16.tflite";
static constexpr const char *kDefaultOcrDictPath = "/data/adb/futon/models/keys_v5.txt";
static constexpr const char *kDefaultOcrDetModelPath = "/data/adb/futon/models/ocr_det_fp16.tflite";

static void print_usage(const char *prog) {
    printf("Usage: %s [options]\n", prog);
    printf("Options:\n");
    printf("  --help              Show this help\n");
    printf("  --skip-sig-check    Skip APK signature verification (debug)\n");
    printf("  (no args)           Run as daemon\n");
}


// Pipeline processing loop
static void pipeline_loop() {
    FUTON_LOGI("Pipeline thread started");

    // Status update interval (5Hz for Binder callbacks)
    constexpr int STATUS_UPDATE_INTERVAL_MS = 200;
    auto last_status_update = std::chrono::steady_clock::now();

    while (g_pipeline_running.load() && !g_shutdown_requested.load()) {
        // Feed watchdog
        if (g_watchdog) {
            g_watchdog->feed();
        }

        // Check if daemon is running
        if (!g_daemon_impl || !g_daemon_impl->is_running()) {
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
            continue;
        }

        // Acquire frame from vision pipeline
        if (!g_vision_pipeline || !g_vision_pipeline->is_initialized()) {
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
            continue;
        }

        auto frame_result = g_vision_pipeline->acquire_frame();
        if (!frame_result.is_ok()) {
            FUTON_LOGW("Frame acquisition failed: %d",
                       static_cast<int>(frame_result.error()));
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
            continue;
        }

        auto &frame = frame_result.value();

        // Release frame
        g_vision_pipeline->release_frame();

        // Check if automation is complete
        if (g_hotpath_router && g_hotpath_router->is_complete() && g_daemon_impl) {
            g_daemon_impl->notify_automation_complete(
                    true, "Automation completed");
        }

        // Push debug frame if debug stream is enabled
        if (g_debug_stream && g_debug_stream->is_running()) {
            DebugFrame debug_frame;
            debug_frame.timestamp_ns = frame.timestamp_ns;
            debug_frame.fps = g_vision_pipeline->get_current_fps();
            debug_frame.latency_ms = frame.total_time_ms;
            debug_frame.frame_count = static_cast<int>(frame.frame_number);

            // PPOCRv5 uses GPU acceleration
            if (g_ppocrv5_engine) {
                debug_frame.active_delegate =
                        g_ppocrv5_engine->GetActiveAccelerator() == ppocrv5::AcceleratorType::kGpu
                        ? "gpu" : "cpu";
            } else {
                debug_frame.active_delegate = "none";
            }

            g_debug_stream->push_frame(debug_frame);
        }

        // Periodic status update to Binder callbacks
        auto now = std::chrono::steady_clock::now();
        auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
                now - last_status_update).count();

        if (elapsed >= STATUS_UPDATE_INTERVAL_MS && g_daemon_impl) {
            // Update stats in daemon impl
            if (g_vision_pipeline) {
                float fps = g_vision_pipeline->get_current_fps();
                float latency = g_vision_pipeline->get_average_latency_ms();
                g_daemon_impl->update_stats(fps, latency,
                                            static_cast<int>(g_vision_pipeline->get_frame_count()));
            }

            // Update delegate info (PPOCRv5 uses GPU)
            if (g_ppocrv5_engine) {
                std::string delegate_str =
                        g_ppocrv5_engine->GetActiveAccelerator() == ppocrv5::AcceleratorType::kGpu
                        ? "gpu" : "cpu";
                g_daemon_impl->set_active_delegate(delegate_str);
            }

            g_daemon_impl->notify_status_update();
            last_status_update = now;
        }
    }

    FUTON_LOGI("Pipeline thread exiting");
}


// Initialize all components
static bool initialize_components(const ProcessConfig &config) {
    FUTON_LOGI("Initializing components...");

    // Create Watchdog with 200ms timeout
    g_watchdog = std::make_shared<Watchdog>(config.watchdog_timeout_ms);

    // Set watchdog recovery callback
    g_watchdog->set_recovery_callback([]() {
        FUTON_LOGW("Watchdog triggered recovery");
        // PPOCRv5 engine handles its own recovery internally
    });

    // Create VisionPipeline
    g_vision_pipeline = std::make_shared<VisionPipeline>();

    // Create InputInjector
    g_input_injector = std::make_shared<InputInjector>();
    auto input_result = g_input_injector->initialize();
    if (!input_result.is_ok()) {
        FUTON_LOGW("InputInjector initialization failed, will use shell fallback");
    }

    // Create DebugStream (disabled by default)
    g_debug_stream = std::make_shared<DebugStream>();

    // Create HotPathRouter
    g_hotpath_router = std::make_shared<HotPathRouter>();

    auto &hc = HardenedConfig::instance();
    hc.initialize();
    if (!hc.is_environment_safe()) {
        FUTON_LOGW("Environment check failed");
    }

    // Initialize User-Provisioned PKI (Phase 2)
    // Keys are deployed by app via Root, verified with Key Attestation
    auto &attestation_verifier = AttestationVerifier::instance();
    if (!attestation_verifier.initialize()) {
        FUTON_LOGW("AttestationVerifier initialization failed");
    }

    auto &key_whitelist = KeyWhitelist::instance();
    if (!key_whitelist.initialize()) {
        FUTON_LOGW("KeyWhitelist initialization failed");
    } else {
        FUTON_LOGI("KeyWhitelist initialized with %zu keys", key_whitelist.key_count());
    }

    AuthConfig auth_config;
    auth_config.pubkey_path = "/data/adb/futon/.auth_pubkey";
    auth_config.require_authentication = true;
    auth_config.session_timeout_ms = SESSION_TIMEOUT_MS;
    auth_config.challenge_timeout_ms = CHALLENGE_TIMEOUT_MS;

    auth_config.enable_rate_limiting = true;
    auth_config.rate_limit_config.max_failures = 5;
    auth_config.rate_limit_config.initial_backoff_ms = 1000;
    auth_config.rate_limit_config.max_backoff_ms = 5 * 60 * 1000;
    auth_config.rate_limit_config.reset_window_ms = 15 * 60 * 1000;

    auth_config.enable_audit_logging = true;
    auth_config.audit_config.log_path = "/data/adb/futon/security.log";
    auth_config.audit_config.max_file_size = 1024 * 1024;
    auth_config.audit_config.max_rotated_files = 3;
    auth_config.audit_config.max_memory_entries = 100;

    auth_config.enable_caller_verification = true;
    auth_config.caller_verifier_config.verify_package_name = true;
    auth_config.caller_verifier_config.verify_selinux_context = true;
    auth_config.caller_verifier_config.verify_process_path = true;
    auth_config.caller_verifier_config.verify_apk_signature = !g_skip_sig_check.load();
    auth_config.caller_verifier_config.pubkey_pin_path = "/data/adb/futon/.pubkey_pin";
    auth_config.caller_verifier_config.authorized_packages = {hc.get_authorized_package()};
    auth_config.caller_verifier_config.authorized_signatures = {
            CryptoUtils::to_hex(hc.get_authorized_signature())};
    auth_config.enable_pubkey_pinning = true;

    g_auth_manager = std::make_shared<AuthManager>(auth_config);
    if (!g_auth_manager->initialize()) {
        FUTON_LOGW("AuthManager initialization failed");
    }

    g_daemon_impl = std::make_shared<IFutonDaemonImpl>();
    if (!g_daemon_impl->initialize(g_auth_manager)) {
        FUTON_LOGE("Failed to initialize daemon impl");
        return false;
    }

    // Initialize PPOCRv5 engine early (if models exist) for perception APIs
    // This enables OCR-based perception without requiring startHotPath()
    {
        const char *det_model_path = kDefaultOcrDetModelPath;
        const char *rec_model_path = kDefaultOcrModelPath;
        const char *keys_path = kDefaultOcrDictPath;

        bool det_exists = access(det_model_path, R_OK) == 0;
        bool rec_exists = access(rec_model_path, R_OK) == 0;
        bool keys_exists = access(keys_path, R_OK) == 0;

        if (det_exists && rec_exists && keys_exists) {
            FUTON_LOGI("Initializing PPOCRv5 engine at startup...");
            auto engine = ppocrv5::OcrEngine::Create(
                    det_model_path, rec_model_path, keys_path,
                    ppocrv5::AcceleratorType::kGpu);

            if (engine) {
                g_ppocrv5_engine = std::move(engine);
                FUTON_LOGI("PPOCRv5 engine initialized successfully");
                FUTON_LOGI("  Det model: %s", det_model_path);
                FUTON_LOGI("  Rec model: %s", rec_model_path);
                FUTON_LOGI("  Keys: %s", keys_path);
                FUTON_LOGI("  Accelerator: %s",
                           g_ppocrv5_engine->GetActiveAccelerator() == ppocrv5::AcceleratorType::kGpu ? "GPU" : "CPU");

                // Set the engine reference in daemon impl for perception APIs
                g_daemon_impl->set_ppocrv5_engine(g_ppocrv5_engine);
            } else {
                FUTON_LOGW("Failed to initialize PPOCRv5 engine at startup");
            }
        } else {
            FUTON_LOGI("PPOCRv5 models not found at startup, OCR disabled. Expected paths:");
            FUTON_LOGI("  Det model: %s (%s)", det_model_path, det_exists ? "OK" : "MISSING");
            FUTON_LOGI("  Rec model: %s (%s)", rec_model_path, rec_exists ? "OK" : "MISSING");
            FUTON_LOGI("  Keys: %s (%s)", keys_path, keys_exists ? "OK" : "MISSING");
        }
    }

    g_daemon_impl->set_vision_pipeline(g_vision_pipeline);
    g_daemon_impl->set_input_injector(g_input_injector);
    g_daemon_impl->set_debug_stream(g_debug_stream);
    g_daemon_impl->set_hotpath_router(g_hotpath_router);

    g_hotpath_router->set_completion_callback(
            [](bool success, const std::string &message) {
                if (g_daemon_impl) {
                    g_daemon_impl->notify_automation_complete(success, message);
                }
            });

    // Start auth cleanup thread
    g_auth_cleanup_running.store(true);
    g_auth_cleanup_thread = std::thread([]() {
        FUTON_LOGI("Auth cleanup thread started");
        while (g_auth_cleanup_running.load() && !g_shutdown_requested.load()) {
            std::this_thread::sleep_for(std::chrono::seconds(30));
            if (g_auth_manager) {
                g_auth_manager->cleanup_expired();
            }
        }
        FUTON_LOGI("Auth cleanup thread exiting");
    });

    // Install Seccomp-BPF filter (MUST be last, after all initialization)
    // This is kernel-level enforcement - cannot be bypassed even with Root
#if FUTON_SECCOMP_ENABLED
    FUTON_LOGI("Installing Seccomp-BPF syscall filter...");
    SeccompConfig seccomp_config;
    seccomp_config.audit_log_path = "/data/adb/futon/seccomp_audit.log";

    auto seccomp_result = SeccompFilter::install(seccomp_config);
    if (!seccomp_result.success) {
        FUTON_LOGE("Seccomp installation failed: %s", seccomp_result.error_message.c_str());
        FUTON_LOGE("Aborting for security - daemon cannot run without syscall filtering");
        return false;
    }
    FUTON_LOGI("Seccomp installed: %d allowed, %d logged, %d blocked syscalls",
               seccomp_result.allowed_count, seccomp_result.logged_count, seccomp_result.blocked_count);
#else
    FUTON_LOGW("Seccomp-BPF is DISABLED at compile time");
#endif

    FUTON_LOGI("Components created successfully");
    return true;
}

// Start pipeline components based on configuration
static bool start_pipeline(const aidl::me::fleey::futon::FutonConfig &config) {
    FUTON_LOGI("Starting pipeline with config: %dx%d @ %d fps",
               config.captureWidth, config.captureHeight, config.targetFps);

    // Initialize vision pipeline
    VisionConfig vision_config;
    vision_config.resolution = CaptureResolution::Half;  // Default to half resolution
    vision_config.target_fps = config.targetFps;
    vision_config.enable_gpu_preprocess = true;
    vision_config.custom_width = config.captureWidth;
    vision_config.custom_height = config.captureHeight;

    if (!g_vision_pipeline->initialize(vision_config)) {
        FUTON_LOGE("Failed to initialize vision pipeline");
        return false;
    }

    FUTON_LOGI("Vision pipeline initialized: %ux%u -> %ux%u",
               g_vision_pipeline->get_capture_width(),
               g_vision_pipeline->get_capture_height(),
               g_vision_pipeline->get_width(),
               g_vision_pipeline->get_height());

    // Initialize PPOCRv5 engine (det + rec) if not already initialized and models exist
    if (!g_ppocrv5_engine) {
        const char *det_model_path = kDefaultOcrDetModelPath;
        const char *rec_model_path = kDefaultOcrModelPath;
        const char *keys_path = kDefaultOcrDictPath;

        bool det_exists = access(det_model_path, R_OK) == 0;
        bool rec_exists = access(rec_model_path, R_OK) == 0;
        bool keys_exists = access(keys_path, R_OK) == 0;

        if (det_exists && rec_exists && keys_exists) {
            FUTON_LOGI("Initializing PPOCRv5 engine (det + rec)...");
            auto engine = ppocrv5::OcrEngine::Create(
                    det_model_path, rec_model_path, keys_path,
                    ppocrv5::AcceleratorType::kGpu);

            if (engine) {
                g_ppocrv5_engine = std::move(engine);
                FUTON_LOGI("PPOCRv5 engine initialized successfully");
                FUTON_LOGI("  Det model: %s", det_model_path);
                FUTON_LOGI("  Rec model: %s", rec_model_path);
                FUTON_LOGI("  Keys: %s", keys_path);
                FUTON_LOGI("  Accelerator: %s",
                           g_ppocrv5_engine->GetActiveAccelerator() == ppocrv5::AcceleratorType::kGpu ? "GPU" : "CPU");

                // Update daemon impl reference
                if (g_daemon_impl) {
                    g_daemon_impl->set_ppocrv5_engine(g_ppocrv5_engine);
                }
            } else {
                FUTON_LOGW("Failed to initialize PPOCRv5 engine");
            }
        } else {
            FUTON_LOGI("PPOCRv5 models not found, PPOCRv5 disabled. Expected paths:");
            FUTON_LOGI("  Det model: %s (%s)", det_model_path, det_exists ? "OK" : "MISSING");
            FUTON_LOGI("  Rec model: %s (%s)", rec_model_path, rec_exists ? "OK" : "MISSING");
            FUTON_LOGI("  Keys: %s (%s)", keys_path, keys_exists ? "OK" : "MISSING");
        }
    } else {
        FUTON_LOGI("PPOCRv5 engine already initialized, skipping");
    }

    // Start debug stream if enabled
    if (config.enableDebugStream) {
        if (!g_debug_stream->initialize(config.debugStreamPort, 30)) {
            FUTON_LOGW("Failed to initialize debug stream on port %d",
                       config.debugStreamPort);
        } else {
            FUTON_LOGI("Debug stream started on port %d", config.debugStreamPort);
        }
    }

    // Start watchdog
    g_watchdog->start();
    FUTON_LOGI("Watchdog started");

    // Start pipeline thread
    g_pipeline_running.store(true);
    g_pipeline_thread = std::thread(pipeline_loop);
    FUTON_LOGI("Pipeline thread started");

    return true;
}

// Stop pipeline components
static void stop_pipeline() {
    FUTON_LOGI("Stopping pipeline...");

    // Stop pipeline thread
    g_pipeline_running.store(false);
    if (g_pipeline_thread.joinable()) {
        g_pipeline_thread.join();
    }

    // Stop watchdog
    if (g_watchdog) {
        g_watchdog->stop();
    }

    // Shutdown debug stream
    if (g_debug_stream) {
        g_debug_stream->shutdown();
    }

    // Reset PPOCRv5 engine
    g_ppocrv5_engine.reset();

    // Shutdown vision pipeline
    if (g_vision_pipeline) {
        g_vision_pipeline->shutdown();
    }

    FUTON_LOGI("Pipeline stopped");
}

// Cleanup all components
static void cleanup_components() {
    FUTON_LOGI("Cleaning up components...");

    stop_pipeline();

    // Shutdown input injector
    if (g_input_injector) {
        g_input_injector->shutdown();
    }

    // Unregister Binder service
    BinderService::unregister_service();

    // Release component references
    g_daemon_impl.reset();
    g_hotpath_router.reset();
    g_debug_stream.reset();
    g_input_injector.reset();
    g_ppocrv5_engine.reset();
    g_vision_pipeline.reset();
    g_watchdog.reset();

    // Stop auth cleanup thread
    g_auth_cleanup_running.store(false);
    if (g_auth_cleanup_thread.joinable()) {
        g_auth_cleanup_thread.join();
    }

    // Release auth manager
    g_auth_manager.reset();

    FUTON_LOGI("Components cleaned up");
}

static void disable_process_freezer() {
    pid_t pid = getpid();
    char path[256];

    snprintf(path, sizeof(path), "/sys/fs/cgroup/uid_0/pid_%d/cgroup.freeze", pid);
    FILE *f = fopen(path, "w");
    if (f) {
        fprintf(f, "0");
        fclose(f);
        FUTON_LOGI("Disabled freezer via %s", path);
    }

    f = fopen("/dev/cgroup_info/cgroup.procs", "w");
    if (!f) {
        f = fopen("/sys/fs/cgroup/cgroup.procs", "w");
    }
    if (f) {
        fprintf(f, "%d", pid);
        fclose(f);
        FUTON_LOGI("Moved process to root cgroup");
    }

    snprintf(path, sizeof(path), "/proc/%d/oom_score_adj", pid);
    f = fopen(path, "w");
    if (f) {
        fprintf(f, "-1000");
        fclose(f);
        FUTON_LOGI("Set oom_score_adj to -1000");
    }
}

static int run_daemon() {
    FUTON_LOGI("========================================");
    FUTON_LOGI("%s", Branding::get_startup_banner().c_str());
    FUTON_LOGI("%s", Branding::get_attribution().c_str());
    FUTON_LOGI("========================================");

    // Initialize process (mlockall, scheduling priority, PID file)
    ProcessConfig config;
    config.sched_priority = 15;
    config.lock_memory = true;
    config.pid_file = "/data/local/tmp/futon_daemon.pid";
    config.watchdog_timeout_ms = 500;  // 500ms for pipeline operations

    if (!ProcessInit::initialize(config)) {
        FUTON_LOGE("Failed to initialize process");
        return 1;
    }

    // Disable cgroup freezer for this process
    disable_process_freezer();

    // ShellExecutor must start before Binder (clean thread state)
    if (!futon::input::ShellExecutor::instance().start()) {
        FUTON_LOGW("Failed to start ShellExecutor");
    }

    if (!ProcessInit::init_binder()) {
        FUTON_LOGE("Failed to initialize Binder");
        futon::input::ShellExecutor::instance().stop();
        ProcessInit::cleanup();
        return 1;
    }

    if (!initialize_components(config)) {
        FUTON_LOGE("Failed to initialize components");
        ProcessInit::cleanup();
        return 1;
    }

    // Set up pipeline control callbacks
    g_daemon_impl->set_pipeline_start_callback(
            [](const aidl::me::fleey::futon::FutonConfig &cfg) -> bool {
                return start_pipeline(cfg);
            });

    g_daemon_impl->set_pipeline_stop_callback([]() {
        stop_pipeline();
    });

    // Register Binder service
    if (!BinderService::register_service(g_daemon_impl)) {
        FUTON_LOGE("Failed to register Binder service");
        cleanup_components();
        ProcessInit::cleanup();
        return 1;
    }

    FUTON_LOGI("Binder service registered: %s", kFutonServiceName);

    // Create and configure main loop
    MainLoop main_loop;
    main_loop.set_watchdog(g_watchdog);
    main_loop.set_shutdown_callback([]() {
        FUTON_LOGI("Shutdown callback invoked");
        g_shutdown_requested.store(true);
        cleanup_components();
        ProcessInit::cleanup();
    });

    // Override daemon start/stop to control pipeline
    // The IFutonDaemonImpl::start() will be called via Binder
    // We need to hook into it to actually start the pipeline

    FUTON_LOGI("Futon Daemon initialized, entering main loop");
    FUTON_LOGI("Waiting for client connections on service: %s", kFutonServiceName);

    // Run main loop (blocks until shutdown)
    main_loop.run();

    FUTON_LOGI("Futon Daemon exiting");
    return 0;
}

int main(int argc, char *argv[]) {
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--help") == 0 || strcmp(argv[i], "-h") == 0) {
            print_usage(argv[0]);
            return 0;
        }
        if (strcmp(argv[i], "--skip-sig-check") == 0) {
            g_skip_sig_check.store(true);
            FUTON_LOGW("APK signature verification disabled (debug mode)");
            continue;
        }
    }

    return run_daemon();
}
