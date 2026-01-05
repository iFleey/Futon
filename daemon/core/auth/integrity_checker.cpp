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

#include "integrity_checker.h"
#include "crypto_utils.h"
#include "core/error.h"

#include <fstream>
#include <sstream>
#include <cstring>
#include <chrono>
#include <thread>
#include <thread>
#include <atomic>
#include <dlfcn.h>
#include <elf.h>
#include <link.h>
#include <sys/mman.h>
#include <sys/ptrace.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <netinet/in.h>
#include <unistd.h>
#include <fcntl.h>
#include <dirent.h>
#include <signal.h>
#include <setjmp.h>

namespace futon::core::auth {

#if defined(__aarch64__)

    static inline uint64_t rdtsc_native() {
        uint64_t val;
        asm volatile("mrs %0, cntvct_el0" : "=r"(val));
        return val;
    }

    static inline void memory_barrier() {
        asm volatile("dmb sy":: : "memory");
    }

    static inline bool check_breakpoint_instruction() {
        auto func_addr = reinterpret_cast<uintptr_t>(&rdtsc_native);
        auto code_ptr = reinterpret_cast<volatile uint32_t *>(func_addr);
        uint32_t instr = *code_ptr;
        return (instr & 0xFFE0001F) == 0xD4200000;
    }

#elif defined(__x86_64__) || defined(__i386__)
    static inline uint64_t rdtsc_native() {
        uint32_t lo, hi;
        asm volatile("rdtsc" : "=a"(lo), "=d"(hi));
        return (static_cast<uint64_t>(hi) << 32) | lo;
    }

    static inline void memory_barrier() {
        asm volatile("mfence" ::: "memory");
    }

    static inline bool check_breakpoint_instruction() {
        auto func_addr = reinterpret_cast<const uint8_t*>(&rdtsc_native);
        return *func_addr == 0xCC;
    }

#else
    static inline uint64_t rdtsc_native() {
        return std::chrono::high_resolution_clock::now().time_since_epoch().count();
    }
    static inline void memory_barrier() {
        std::atomic_thread_fence(std::memory_order_seq_cst);
    }
    static inline bool check_breakpoint_instruction() { return false; }
#endif

    static ssize_t syscall_read(int fd, void *buf, size_t count) {
        return syscall(SYS_read, fd, buf, count);
    }

    static int syscall_open(const char *path, int flags) {
        return syscall(SYS_openat, AT_FDCWD, path, flags, 0);
    }

    static int syscall_close(int fd) {
        return syscall(SYS_close, fd);
    }

    static long syscall_ptrace(int request, pid_t pid, void *addr, void *data) {
        return syscall(SYS_ptrace, request, pid, addr, data);
    }

    static pid_t syscall_getppid() {
        return syscall(SYS_getppid);
    }

    struct ElfSectionInfo {
        uintptr_t addr;
        size_t size;
        std::string name;
        uint32_t type;
        uint64_t flags;
    };

    static std::vector<ElfSectionInfo> get_elf_sections() {
        std::vector<ElfSectionInfo> sections;

        dl_iterate_phdr([](struct dl_phdr_info *info, size_t, void *data) -> int {
            auto *sections = static_cast<std::vector<ElfSectionInfo> *>(data);

            if (info->dlpi_name[0] != '\0' &&
                strstr(info->dlpi_name, "futon") == nullptr) {
                return 0;
            }

            for (int i = 0; i < info->dlpi_phnum; i++) {
                const ElfW(Phdr) &phdr = info->dlpi_phdr[i];

                if (phdr.p_type == PT_LOAD) {
                    ElfSectionInfo sec;
                    sec.addr = info->dlpi_addr + phdr.p_vaddr;
                    sec.size = phdr.p_memsz;
                    sec.type = phdr.p_type;
                    sec.flags = phdr.p_flags;

                    if ((phdr.p_flags & PF_X) && !(phdr.p_flags & PF_W)) {
                        sec.name = ".text";
                    } else if (!(phdr.p_flags & PF_W) && !(phdr.p_flags & PF_X)) {
                        sec.name = ".rodata";
                    } else if ((phdr.p_flags & PF_W) && !(phdr.p_flags & PF_X)) {
                        sec.name = ".data";
                    }

                    sections->push_back(sec);
                }
            }
            return 0;
        }, &sections);

        return sections;
    }

    static std::optional<ElfSectionInfo> find_text_section() {
        auto sections = get_elf_sections();
        for (const auto &sec: sections) {
            if (sec.name == ".text" && sec.size > 0) {
                return sec;
            }
        }
        return std::nullopt;
    }

    static constexpr uint64_t TIMING_THRESHOLD = 50000;

    static bool detect_timing_anomaly() {
        memory_barrier();
        uint64_t start = rdtsc_native();
        memory_barrier();

        volatile int dummy = 0;
        for (int i = 0; i < 100; i++) {
            dummy += i;
            dummy ^= (dummy << 1);
        }

        memory_barrier();
        uint64_t end = rdtsc_native();
        memory_barrier();

        return (end - start) > TIMING_THRESHOLD;
    }

    static bool detect_single_stepping() {
        memory_barrier();
        uint64_t t1 = rdtsc_native();
        memory_barrier();
        uint64_t t2 = rdtsc_native();
        memory_barrier();
        uint64_t t3 = rdtsc_native();
        memory_barrier();

        uint64_t delta1 = t2 - t1;
        uint64_t delta2 = t3 - t2;

        if (delta1 > 5000 || delta2 > 5000) {
            return true;
        }

        uint64_t ratio = (delta1 > delta2) ? (delta1 / (delta2 + 1)) : (delta2 / (delta1 + 1));
        return ratio > 10;
    }

    static bool check_tracer_pid() {
        int fd = syscall_open("/proc/self/status", O_RDONLY);
        if (fd < 0) return false;

        char buf[4096];
        ssize_t n = syscall_read(fd, buf, sizeof(buf) - 1);
        syscall_close(fd);

        if (n <= 0) return false;
        buf[n] = '\0';

        const char *tracer = strstr(buf, "TracerPid:");
        if (!tracer) return false;

        tracer += 10;
        while (*tracer == ' ' || *tracer == '\t') tracer++;

        return atoi(tracer) != 0;
    }

    static bool is_parent_debugger() {
        pid_t ppid = syscall_getppid();

        char path[64];
        snprintf(path, sizeof(path), "/proc/%d/comm", ppid);

        int fd = syscall_open(path, O_RDONLY);
        if (fd < 0) return false;

        char comm[256] = {0};
        syscall_read(fd, comm, sizeof(comm) - 1);
        syscall_close(fd);

        char *nl = strchr(comm, '\n');
        if (nl) *nl = '\0';

        static const char *debuggers[] = {
                "gdb", "lldb", "strace", "ltrace", "ida", "ida64",
                "radare2", "r2", "frida", "frida-server", "gdbserver"
        };

        for (const char *name: debuggers) {
            if (strcasestr(comm, name) != nullptr) {
                return true;
            }
        }

        return false;
    }

    static std::vector<uint8_t> compute_text_hash() {
        auto text_section = find_text_section();
        if (!text_section.has_value()) {
            return {};
        }

        const auto &sec = text_section.value();
        const uint8_t *mem = reinterpret_cast<const uint8_t *>(sec.addr);

        return CryptoUtils::sha256(std::vector<uint8_t>(mem, mem + sec.size));
    }

    static bool detect_inline_hooks() {
        void *handle = dlopen(nullptr, RTLD_NOW);
        if (!handle) return false;

#if defined(__aarch64__)
        auto is_hook = [](const uint8_t *ptr) -> bool {
            uint32_t instr = *reinterpret_cast<const uint32_t *>(ptr);
            if ((instr & 0xFC000000) == 0x14000000) return true;
            if ((instr & 0xFF000000) == 0x58000000) {
                uint32_t next = *reinterpret_cast<const uint32_t *>(ptr + 4);
                if ((next & 0xFFFFFC1F) == 0xD61F0000) return true;
            }
            return false;
        };
#elif defined(__x86_64__)
        auto is_hook = [](const uint8_t* ptr) -> bool {
            if (ptr[0] == 0xE9) return true;
            if (ptr[0] == 0xFF && ptr[1] == 0x25) return true;
            if (ptr[0] == 0x48 && ptr[1] == 0xB8) return true;
            if (ptr[0] == 0xCC) return true;
            return false;
        };
#else
        auto is_hook = [](const uint8_t*) -> bool { return false; };
#endif

        const char *funcs[] = {"open", "read", "write", "mmap", "ptrace"};

        for (const char *name: funcs) {
            void *func = dlsym(RTLD_DEFAULT, name);
            if (func && is_hook(static_cast<const uint8_t *>(func))) {
                dlclose(handle);
                return true;
            }
        }

        dlclose(handle);
        return false;
    }

    static bool detect_frida_ports() {
        const int ports[] = {27042, 27043, 27044, 27045};

        for (int port: ports) {
            int sock = socket(AF_INET, SOCK_STREAM, 0);
            if (sock < 0) continue;

            int flags = fcntl(sock, F_GETFL, 0);
            fcntl(sock, F_SETFL, flags | O_NONBLOCK);

            struct sockaddr_in addr{};
            addr.sin_family = AF_INET;
            addr.sin_port = htons(port);
            addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

            int result = connect(sock, reinterpret_cast<struct sockaddr *>(&addr), sizeof(addr));

            if (result == 0) {
                close(sock);
                return true;
            }

            if (errno == EINPROGRESS) {
                fd_set fdset;
                FD_ZERO(&fdset);
                FD_SET(sock, &fdset);

                struct timeval tv = {0, 50000};
                if (select(sock + 1, nullptr, &fdset, nullptr, &tv) > 0) {
                    int error = 0;
                    socklen_t len = sizeof(error);
                    getsockopt(sock, SOL_SOCKET, SO_ERROR, &error, &len);
                    if (error == 0) {
                        close(sock);
                        return true;
                    }
                }
            }

            close(sock);
        }

        return false;
    }

    static bool detect_frida_threads() {
        DIR *dir = opendir("/proc/self/task");
        if (!dir) return false;

        struct dirent *entry;
        while ((entry = readdir(dir)) != nullptr) {
            if (entry->d_type != DT_DIR || entry->d_name[0] == '.') continue;

            char path[128];
            snprintf(path, sizeof(path), "/proc/self/task/%s/comm", entry->d_name);

            int fd = syscall_open(path, O_RDONLY);
            if (fd < 0) continue;

            char comm[64] = {0};
            syscall_read(fd, comm, sizeof(comm) - 1);
            syscall_close(fd);

            if (strstr(comm, "gmain") || strstr(comm, "gum-js-loop") ||
                strstr(comm, "pool-frida")) {
                closedir(dir);
                return true;
            }
        }

        closedir(dir);
        return false;
    }

    static bool detect_frida_maps() {
        int fd = syscall_open("/proc/self/maps", O_RDONLY);
        if (fd < 0) return false;

        char buf[65536];
        ssize_t total = 0;
        ssize_t n;

        while ((n = syscall_read(fd, buf + total, sizeof(buf) - total - 1)) > 0) {
            total += n;
            if (total >= static_cast<ssize_t>(sizeof(buf) - 1)) break;
        }
        syscall_close(fd);

        if (total <= 0) return false;
        buf[total] = '\0';

        return strcasestr(buf, "frida") != nullptr ||
               strcasestr(buf, "gadget") != nullptr ||
               strcasestr(buf, "linjector") != nullptr;
    }

    static bool detect_xposed() {
        static const char *paths[] = {
                "/system/framework/XposedBridge.jar",
                "/system/lib/libxposed_art.so",
                "/system/lib64/libxposed_art.so",
                "/data/adb/lspd",
                "/data/adb/edxp",
                "/data/adb/modules/zygisk_lsposed",
                "/data/adb/modules/riru_lsposed"
        };

        for (const char *path: paths) {
            if (access(path, F_OK) == 0) {
                return true;
            }
        }

        int fd = syscall_open("/proc/self/maps", O_RDONLY);
        if (fd >= 0) {
            char buf[32768];
            ssize_t n = syscall_read(fd, buf, sizeof(buf) - 1);
            syscall_close(fd);

            if (n > 0) {
                buf[n] = '\0';
                if (strstr(buf, "XposedBridge") || strstr(buf, "lspd") ||
                    strstr(buf, "edxp") || strstr(buf, "libxposed")) {
                    return true;
                }
            }
        }

        return false;
    }

    IntegrityChecker::IntegrityChecker(const IntegrityConfig &config)
            : config_(config) {
    }

    IntegrityChecker::~IntegrityChecker() {
        stop_periodic_checks();
    }

    bool IntegrityChecker::initialize() {
        if (initialized_) return true;

        if (config_.check_code_sections) {
            code_section_hash_ = compute_text_hash();
        }

        initialized_ = true;
        last_check_time_ = static_cast<uint64_t>(
                std::chrono::steady_clock::now().time_since_epoch().count());

        return true;
    }

    IntegrityCheckResult IntegrityChecker::check_integrity() {
        uint32_t checks = 0;
        uint32_t failed = 0;
        std::string reason;

        if (config_.check_code_sections && !code_section_hash_.empty()) {
            checks++;
            auto current = compute_text_hash();
            if (!CryptoUtils::constant_time_compare(code_section_hash_, current)) {
                failed++;
                reason += ".text modified; ";
            }
        }

        if (config_.check_got_plt) {
            checks++;
            if (detect_inline_hooks()) {
                failed++;
                reason += "hooks detected; ";
            }
        }

        last_check_time_ = static_cast<uint64_t>(
                std::chrono::steady_clock::now().time_since_epoch().count());

        if (failed > 0) {
            violation_count_ += failed;
            auto result = IntegrityCheckResult::failure(reason, checks, failed);
            handle_violation(result);
            return result;
        }

        return IntegrityCheckResult::success(checks);
    }

    bool IntegrityChecker::check_code_section_integrity() {
        if (code_section_hash_.empty()) return true;
        auto current = compute_text_hash();
        return CryptoUtils::constant_time_compare(code_section_hash_, current);
    }

    bool IntegrityChecker::check_got_plt_integrity() {
        return !detect_inline_hooks();
    }

    bool IntegrityChecker::check_critical_function_integrity() {
        for (const auto &func: critical_functions_) {
            const uint8_t *mem = static_cast<const uint8_t *>(func.address);
            auto current = CryptoUtils::sha256(std::vector<uint8_t>(mem, mem + func.size));
            if (!CryptoUtils::constant_time_compare(func.hash, current)) {
                return false;
            }
        }
        return true;
    }

    AntiDebugResult IntegrityChecker::check_anti_debug() {
        AntiDebugResult result{};

        if (config_.check_ptrace) {
            result.ptrace_detected = check_tracer_pid();
        }

        if (config_.check_debugger) {
            result.debugger_detected = detect_timing_anomaly() ||
                                       detect_single_stepping() ||
                                       is_parent_debugger();
        }

        if (config_.check_frida) {
            result.frida_detected = detect_frida_ports() ||
                                    detect_frida_threads() ||
                                    detect_frida_maps();
        }

        if (config_.check_xposed) {
            result.xposed_detected = detect_xposed();
        }

        result.breakpoint_detected = check_breakpoint_instruction();

        std::ostringstream details;
        if (result.debugger_detected) details << "debugger ";
        if (result.frida_detected) details << "frida ";
        if (result.xposed_detected) details << "xposed ";
        if (result.ptrace_detected) details << "ptrace ";
        if (result.breakpoint_detected) details << "breakpoint ";
        result.details = details.str();

        return result;
    }

    bool IntegrityChecker::is_debugger_attached() {
        if (check_tracer_pid()) return true;
        if (detect_timing_anomaly()) return true;
        if (is_parent_debugger()) return true;

        if (syscall_ptrace(PTRACE_TRACEME, 0, nullptr, nullptr) == -1) {
            if (errno == EPERM) return true;
        }

        return false;
    }

    bool IntegrityChecker::is_frida_present() {
        return detect_frida_ports() || detect_frida_threads() || detect_frida_maps();
    }

    bool IntegrityChecker::is_xposed_present() {
        return detect_xposed();
    }

    bool IntegrityChecker::is_ptrace_attached() {
        return check_tracer_pid();
    }

    bool IntegrityChecker::has_software_breakpoints() {
        return check_breakpoint_instruction() || detect_inline_hooks();
    }

    bool IntegrityChecker::check_memory_maps() {
        return !detect_inline_hooks();
    }

    bool IntegrityChecker::check_for_injected_libraries() {
        return is_frida_present() || is_xposed_present();
    }

    void IntegrityChecker::start_periodic_checks() {
        if (periodic_running_.load()) return;

        periodic_running_.store(true);

        periodic_thread_ = new std::thread([this]() {
            while (periodic_running_.load()) {
                std::this_thread::sleep_for(
                        std::chrono::milliseconds(config_.periodic_check_interval_ms));

                if (!periodic_running_.load()) break;

                // Telemetry-only: log results but don't take blocking action
                auto result = check_integrity();
                if (!result.passed) {
                    FUTON_LOGW("Telemetry: Periodic integrity check: %s (non-blocking)",
                               result.failure_reason.c_str());
                    if (config_.notify_callback && violation_callback_) {
                        violation_callback_(result);
                    }
                }

                auto anti_debug = check_anti_debug();
                if (anti_debug.debugger_detected || anti_debug.frida_detected ||
                    anti_debug.xposed_detected) {
                    FUTON_LOGW("Telemetry: Periodic anti-debug check: %s (non-blocking)",
                               anti_debug.details.c_str());
                    // Log only, no handle_violation call to avoid any blocking behavior
                }
            }
        });
    }

    void IntegrityChecker::stop_periodic_checks() {
        periodic_running_.store(false);

        if (periodic_thread_) {
            auto *t = static_cast<std::thread *>(periodic_thread_);
            if (t->joinable()) {
                t->join();
            }
            delete t;
            periodic_thread_ = nullptr;
        }
    }

    bool IntegrityChecker::is_periodic_checking() const {
        return periodic_running_.load();
    }

    void IntegrityChecker::set_violation_callback(IntegrityViolationCallback callback) {
        violation_callback_ = std::move(callback);
    }

    void IntegrityChecker::register_critical_function(const void *func_addr, size_t size,
                                                      const std::string &name) {
        CriticalFunction func;
        func.address = func_addr;
        func.size = size;
        func.name = name;

        const uint8_t *mem = static_cast<const uint8_t *>(func_addr);
        func.hash = CryptoUtils::sha256(std::vector<uint8_t>(mem, mem + size));

        critical_functions_.push_back(func);
    }

    void IntegrityChecker::handle_violation(const IntegrityCheckResult &result) {
        // Telemetry-only mode: always log, never crash
        FUTON_LOGW("Telemetry: Integrity violation detected: %s (checks: %u/%u) - non-blocking",
                   result.failure_reason.c_str(),
                   result.checks_failed, result.checks_performed);

        if (config_.notify_callback && violation_callback_) {
            violation_callback_(result);
        }

        // crash_on_tampering is intentionally ignored - telemetry only
    }

    void CodeWatermark::embed_watermark(const std::string &) {
    }

    bool CodeWatermark::verify_watermark() {
        return true;
    }

    std::string CodeWatermark::get_watermark_id() {
        return WATERMARK_MAGIC;
    }

    std::vector<uint8_t> CodeWatermark::generate_build_watermark() {
        std::vector<uint8_t> data;
        data.insert(data.end(), WATERMARK_MAGIC, WATERMARK_MAGIC + sizeof(WATERMARK_MAGIC));

        auto now = std::chrono::system_clock::now().time_since_epoch().count();
        for (int i = 0; i < 8; i++) {
            data.push_back(static_cast<uint8_t>((now >> (i * 8)) & 0xFF));
        }

        return CryptoUtils::sha256(data);
    }

} // namespace futon::core::auth
