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

#ifndef FUTON_CORE_AUTH_INTEGRITY_CHECKER_H
#define FUTON_CORE_AUTH_INTEGRITY_CHECKER_H

#include <cstdint>
#include <string>
#include <vector>
#include <array>
#include <functional>
#include <atomic>

namespace futon::core::auth {

// Integrity check result
    struct IntegrityCheckResult {
        bool passed;
        uint32_t checks_performed;
        uint32_t checks_failed;
        std::string failure_reason;

        static IntegrityCheckResult success(uint32_t checks) {
            return {true, checks, 0, ""};
        }

        static IntegrityCheckResult failure(const std::string &reason,
                                            uint32_t performed, uint32_t failed) {
            return {false, performed, failed, reason};
        }
    };

// Anti-debugging detection result
    struct AntiDebugResult {
        bool debugger_detected;
        bool frida_detected;
        bool xposed_detected;
        bool ptrace_detected;
        bool breakpoint_detected;
        std::string details;
    };

// Integrity checker configuration
    struct IntegrityConfig {
        // Check intervals
        int periodic_check_interval_ms = 30000;  // 30 seconds

        // Enable specific checks
        bool check_code_sections = true;
        bool check_got_plt = true;
        bool check_critical_functions = true;
        bool check_debugger = true;
        bool check_frida = true;
        bool check_xposed = true;
        bool check_memory_maps = true;
        bool check_ptrace = true;

        // Response to tampering (telemetry-only mode: always log, never block)
        bool crash_on_tampering = false;  // ALWAYS false - telemetry only
        bool log_tampering = true;
        bool notify_callback = true;
    };

// Callback for integrity violations
    using IntegrityViolationCallback = std::function<void(const IntegrityCheckResult &)>;

    class IntegrityChecker {
    public:
        explicit IntegrityChecker(const IntegrityConfig &config = IntegrityConfig());

        ~IntegrityChecker();

        // Disable copy
        IntegrityChecker(const IntegrityChecker &) = delete;

        IntegrityChecker &operator=(const IntegrityChecker &) = delete;

        // Initialize checker (compute baseline hashes)
        bool initialize();

        // Perform full integrity check
        IntegrityCheckResult check_integrity();

        // Individual checks
        bool check_code_section_integrity();

        bool check_got_plt_integrity();

        bool check_critical_function_integrity();

        // Anti-debugging checks
        AntiDebugResult check_anti_debug();

        bool is_debugger_attached();

        bool is_frida_present();

        bool is_xposed_present();

        bool is_ptrace_attached();

        bool has_software_breakpoints();

        // Memory map analysis
        bool check_memory_maps();

        bool check_for_injected_libraries();

        // Start/stop periodic checking
        void start_periodic_checks();

        void stop_periodic_checks();

        bool is_periodic_checking() const;

        // Set violation callback
        void set_violation_callback(IntegrityViolationCallback callback);

        // Manual hash registration for critical functions
        void register_critical_function(const void *func_addr, size_t size,
                                        const std::string &name);

        // Get current status
        bool is_initialized() const { return initialized_; }

        uint64_t get_last_check_time() const { return last_check_time_; }

        uint32_t get_violation_count() const { return violation_count_; }

    private:
        IntegrityConfig config_;
        bool initialized_ = false;
        std::atomic<bool> periodic_running_{false};
        std::atomic<uint64_t> last_check_time_{0};
        std::atomic<uint32_t> violation_count_{0};

        IntegrityViolationCallback violation_callback_;

        // Baseline hashes
        std::vector<uint8_t> code_section_hash_;
        std::vector<uint8_t> got_plt_hash_;

        // Critical function registry
        struct CriticalFunction {
            const void *address;
            size_t size;
            std::string name;
            std::vector<uint8_t> hash;
        };
        std::vector<CriticalFunction> critical_functions_;

        // Periodic check thread
        void *periodic_thread_ = nullptr;

        // Internal helpers
        std::vector<uint8_t> compute_memory_hash(const void *addr, size_t size);

        bool find_code_section(void *&start, size_t &size);

        bool find_got_plt(void *&start, size_t &size);

        void handle_violation(const IntegrityCheckResult &result);

        // Anti-debug helpers
        bool check_proc_status();

        bool check_proc_maps_for_frida();

        bool check_proc_maps_for_xposed();

        bool scan_memory_for_frida_gadget();

        bool check_frida_server_port();
    };

// Inline integrity check macros (telemetry-only: log but never trap)
#define FUTON_INTEGRITY_CHECK() \
    do { \
        static futon::core::auth::IntegrityChecker* __checker = nullptr; \
        if (__checker && !__checker->check_integrity().passed) { \
            FUTON_LOGW("Telemetry: FUTON_INTEGRITY_CHECK failed (non-blocking)"); \
        } \
    } while(0)

// Anti-debug check (telemetry-only: log but never trap)
#define FUTON_ANTI_DEBUG_CHECK() \
    do { \
        FUTON_LOGW("Telemetry: FUTON_ANTI_DEBUG_CHECK invoked (non-blocking)"); \
    } while(0)

// Code watermark verification
    class CodeWatermark {
    public:
        // Embed watermark in code section
        static void embed_watermark(const std::string &identifier);

        // Verify watermark is present and unmodified
        static bool verify_watermark();

        // Get watermark identifier
        static std::string get_watermark_id();

        // Generate unique build watermark
        static std::vector<uint8_t> generate_build_watermark();

    private:
        // Watermark storage (in .rodata section)
        static constexpr char WATERMARK_MAGIC[] = "FUTON_WM_v1";
        static constexpr size_t WATERMARK_SIZE = 64;
    };

} // namespace futon::core::auth

#endif // FUTON_CORE_AUTH_INTEGRITY_CHECKER_H
