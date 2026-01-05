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

#include "device_fingerprint.h"
#include "crypto_utils.h"
#include "core/error.h"

#include <fstream>
#include <sstream>
#include <cstring>
#include <chrono>
#include <thread>
#include <sys/stat.h>
#include <sys/sysinfo.h>
#include <sys/utsname.h>
#include <unistd.h>
#include <fcntl.h>

namespace futon::core::auth {

#if defined(__aarch64__)

    static inline uint64_t read_cycle_counter() {
        uint64_t val;
        asm volatile("mrs %0, cntvct_el0" : "=r"(val));
        return val;
    }

#elif defined(__x86_64__)
    static inline uint64_t read_cycle_counter() {
        uint32_t lo, hi;
        asm volatile("rdtsc" : "=a"(lo), "=d"(hi));
        return (static_cast<uint64_t>(hi) << 32) | lo;
    }
#else
    static inline uint64_t read_cycle_counter() {
        return std::chrono::high_resolution_clock::now().time_since_epoch().count();
    }
#endif

    std::vector<uint8_t> TimingFingerprint::measure() {
        std::vector<uint64_t> measurements;
        measurements.reserve(64);

        // Measure instruction timing variance
        for (int i = 0; i < 16; i++) {
            measurements.push_back(measure_instruction_timing());
        }

        // Measure memory access timing
        for (int i = 0; i < 16; i++) {
            measurements.push_back(measure_memory_timing());
        }

        // Measure cache timing
        for (int i = 0; i < 16; i++) {
            measurements.push_back(measure_cache_timing());
        }

        // Measure branch prediction timing
        for (int i = 0; i < 16; i++) {
            measurements.push_back(measure_branch_timing());
        }

        // Convert to bytes and hash
        std::vector<uint8_t> raw_data;
        raw_data.reserve(measurements.size() * 8);

        for (uint64_t m: measurements) {
            for (int i = 0; i < 8; i++) {
                raw_data.push_back(static_cast<uint8_t>((m >> (i * 8)) & 0xFF));
            }
        }

        return CryptoUtils::sha256(raw_data);
    }

    uint64_t TimingFingerprint::measure_instruction_timing() {
        volatile uint64_t dummy = 0;

        uint64_t start = read_cycle_counter();

        // Simple arithmetic operations
        for (int i = 0; i < 100; i++) {
            dummy += i;
            dummy ^= (dummy << 3);
            dummy *= 31;
        }

        uint64_t end = read_cycle_counter();

        return end - start;
    }

    uint64_t TimingFingerprint::measure_memory_timing() {
        // Allocate and access memory to measure timing
        volatile uint8_t buffer[4096];

        uint64_t start = read_cycle_counter();

        // Sequential memory access
        for (int i = 0; i < 4096; i++) {
            buffer[i] = static_cast<uint8_t>(i);
        }

        // Random-ish access pattern
        for (int i = 0; i < 100; i++) {
            int idx = (i * 37) % 4096;
            buffer[idx] ^= buffer[(idx + 128) % 4096];
        }

        uint64_t end = read_cycle_counter();

        return end - start;
    }

    uint64_t TimingFingerprint::measure_cache_timing() {
        // Large buffer to exceed L1 cache
        static volatile uint8_t large_buffer[256 * 1024];

        uint64_t start = read_cycle_counter();

        // Access pattern that causes cache misses
        for (int i = 0; i < 1000; i++) {
            int idx = (i * 4099) % (256 * 1024);  // Prime stride
            large_buffer[idx] = static_cast<uint8_t>(i);
        }

        uint64_t end = read_cycle_counter();

        return end - start;
    }

    uint64_t TimingFingerprint::measure_branch_timing() {
        volatile int result = 0;
        volatile int condition = 0;

        uint64_t start = read_cycle_counter();

        // Unpredictable branches
        for (int i = 0; i < 100; i++) {
            condition = (i * 7) % 13;
            if (condition > 6) {
                result += i;
            } else {
                result -= i;
            }

            if ((condition * i) % 11 > 5) {
                result ^= i;
            }
        }

        uint64_t end = read_cycle_counter();

        return end - start;
    }

    std::vector<uint8_t> HardwareEntropy::collect(size_t bytes) {
        std::vector<uint8_t> pool;
        pool.reserve(bytes * 4);  // Collect more than needed, then hash

        add_cpu_entropy(pool);
        add_timing_entropy(pool);
        add_memory_entropy(pool);
        add_system_entropy(pool);

        // Hash to get requested size
        std::vector<uint8_t> result;
        result.reserve(bytes);

        while (result.size() < bytes) {
            auto hash = CryptoUtils::sha256(pool);
            for (size_t i = 0; i < hash.size() && result.size() < bytes; i++) {
                result.push_back(hash[i]);
            }
            // Mix pool for next iteration
            pool.insert(pool.end(), hash.begin(), hash.end());
        }

        return result;
    }

    void HardwareEntropy::add_cpu_entropy(std::vector<uint8_t> &pool) {
        // CPU info from /proc/cpuinfo
        int fd = open("/proc/cpuinfo", O_RDONLY);
        if (fd >= 0) {
            char buf[4096];
            ssize_t n = read(fd, buf, sizeof(buf) - 1);
            close(fd);

            if (n > 0) {
                buf[n] = '\0';

                // Extract specific fields
                const char *fields[] = {
                        "Hardware", "Serial", "Revision", "CPU implementer",
                        "CPU architecture", "CPU variant", "CPU part"
                };

                for (const char *field: fields) {
                    const char *pos = strstr(buf, field);
                    if (pos) {
                        const char *end = strchr(pos, '\n');
                        if (end) {
                            pool.insert(pool.end(), pos, end);
                        }
                    }
                }
            }
        }
    }

    void HardwareEntropy::add_timing_entropy(std::vector<uint8_t> &pool) {
        // Collect timing measurements
        for (int i = 0; i < 32; i++) {
            uint64_t t = read_cycle_counter();
            for (int j = 0; j < 8; j++) {
                pool.push_back(static_cast<uint8_t>((t >> (j * 8)) & 0xFF));
            }

            // Small delay to get different values
            volatile int dummy = 0;
            for (int k = 0; k < 100; k++) dummy += k;
        }
    }

    void HardwareEntropy::add_memory_entropy(std::vector<uint8_t> &pool) {
        // Memory info
        struct sysinfo si;
        if (sysinfo(&si) == 0) {
            pool.insert(pool.end(),
                        reinterpret_cast<uint8_t *>(&si),
                        reinterpret_cast<uint8_t *>(&si) + sizeof(si));
        }

        // Stack address (ASLR provides entropy)
        volatile int stack_var = 0;
        uintptr_t stack_addr = reinterpret_cast<uintptr_t>(&stack_var);
        for (int i = 0; i < 8; i++) {
            pool.push_back(static_cast<uint8_t>((stack_addr >> (i * 8)) & 0xFF));
        }
    }

    void HardwareEntropy::add_system_entropy(std::vector<uint8_t> &pool) {
        // Boot ID
        int fd = open("/proc/sys/kernel/random/boot_id", O_RDONLY);
        if (fd >= 0) {
            char buf[64];
            ssize_t n = read(fd, buf, sizeof(buf) - 1);
            close(fd);
            if (n > 0) {
                pool.insert(pool.end(), buf, buf + n);
            }
        }

        // Uptime
        fd = open("/proc/uptime", O_RDONLY);
        if (fd >= 0) {
            char buf[64];
            ssize_t n = read(fd, buf, sizeof(buf) - 1);
            close(fd);
            if (n > 0) {
                pool.insert(pool.end(), buf, buf + n);
            }
        }

        // Current time with nanosecond precision
        struct timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        pool.insert(pool.end(),
                    reinterpret_cast<uint8_t *>(&ts),
                    reinterpret_cast<uint8_t *>(&ts) + sizeof(ts));
    }

    DeviceFingerprint::DeviceFingerprint(const DeviceBindingConfig &config)
            : config_(config) {
    }

    bool DeviceFingerprint::initialize() {
        FUTON_LOGI("Initializing DeviceFingerprint...");

        // Try to load existing binding
        if (load_binding()) {
            FUTON_LOGI("Loaded existing device binding");
            return true;
        }

        FUTON_LOGI("No existing binding found");
        return true;
    }

    std::vector<uint8_t> DeviceFingerprint::collect_fingerprint() {
        auto components = collect_components();
        return combine_components(components);
    }

    DeviceFingerprintComponents DeviceFingerprint::collect_components() {
        DeviceFingerprintComponents comp;

        if (config_.use_cpu_info) {
            comp.cpu_info = collect_cpu_info();
        }

        if (config_.use_memory_info) {
            comp.memory_info = collect_memory_info();
        }

        if (config_.use_kernel_info) {
            comp.kernel_info = collect_kernel_info();
        }

        if (config_.use_hardware_serial) {
            comp.hardware_serial = collect_hardware_serial();
        }

        if (config_.use_boot_id) {
            comp.boot_id = collect_boot_id();
        }

        if (config_.use_build_fingerprint) {
            comp.build_fingerprint = collect_build_fingerprint();
        }

        if (config_.use_selinux_info) {
            comp.selinux_info = collect_selinux_info();
        }

        if (config_.use_partition_info) {
            comp.partition_info = collect_partition_info();
        }

        if (config_.use_timing_fingerprint) {
            comp.timing_fingerprint = collect_timing_fingerprint();
        }

        return comp;
    }

    bool DeviceFingerprint::bind_to_device() {
        std::lock_guard<std::mutex> lock(mutex_);

        auto fingerprint = collect_fingerprint();
        auto components = collect_components();

        if (fingerprint.empty()) {
            FUTON_LOGE("Failed to collect device fingerprint");
            return false;
        }

        if (!save_binding(fingerprint, components)) {
            FUTON_LOGE("Failed to save device binding");
            return false;
        }

        bound_fingerprint_ = fingerprint;
        bound_components_ = components;

        FUTON_LOGI("Device bound successfully");
        return true;
    }

    FingerprintVerifyResult DeviceFingerprint::verify_device() {
        std::lock_guard<std::mutex> lock(mutex_);

        if (!bound_fingerprint_.has_value()) {
            return FingerprintVerifyResult::failure("No device binding exists");
        }

        auto current_components = collect_components();

        int matched = 0, total = 0;
        int score = compare_components(bound_components_.value(), current_components, matched,
                                       total);

        if (score >= config_.match_threshold_percent) {
            return FingerprintVerifyResult::success(score, matched, total);
        }

        std::ostringstream reason;
        reason << "Fingerprint mismatch: score=" << score << "% (threshold="
               << config_.match_threshold_percent << "%), matched=" << matched << "/" << total;

        return FingerprintVerifyResult::failure(reason.str());
    }

    bool DeviceFingerprint::is_bound() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return bound_fingerprint_.has_value();
    }

    std::optional<std::vector<uint8_t>> DeviceFingerprint::get_bound_fingerprint() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return bound_fingerprint_;
    }

    bool DeviceFingerprint::clear_binding() {
        std::lock_guard<std::mutex> lock(mutex_);

        if (unlink(config_.binding_file_path.c_str()) != 0 && errno != ENOENT) {
            FUTON_LOGW("Failed to remove binding file: %s", strerror(errno));
        }

        bound_fingerprint_.reset();
        bound_components_.reset();

        return true;
    }

    std::vector<uint8_t> DeviceFingerprint::get_device_entropy() const {
        return HardwareEntropy::collect(32);
    }

    std::vector<uint8_t> DeviceFingerprint::collect_cpu_info() {
        std::string data;

        // Read /proc/cpuinfo
        int fd = open("/proc/cpuinfo", O_RDONLY);
        if (fd >= 0) {
            char buf[8192];
            ssize_t n = read(fd, buf, sizeof(buf) - 1);
            close(fd);

            if (n > 0) {
                buf[n] = '\0';

                // Extract stable fields
                const char *fields[] = {
                        "Hardware", "CPU implementer", "CPU architecture",
                        "CPU variant", "CPU part", "Features"
                };

                for (const char *field: fields) {
                    const char *pos = strstr(buf, field);
                    if (pos) {
                        const char *colon = strchr(pos, ':');
                        const char *end = strchr(pos, '\n');
                        if (colon && end && colon < end) {
                            data += std::string(colon + 1, end);
                        }
                    }
                }
            }
        }

        return hash_string(data);
    }

    std::vector<uint8_t> DeviceFingerprint::collect_memory_info() {
        std::string data;

        // Memory size (stable across reboots)
        struct sysinfo si;
        if (sysinfo(&si) == 0) {
            data += std::to_string(si.totalram);
            data += std::to_string(si.mem_unit);
        }

        // Page size
        data += std::to_string(sysconf(_SC_PAGESIZE));

        return hash_string(data);
    }

    std::vector<uint8_t> DeviceFingerprint::collect_kernel_info() {
        std::string data;

        struct utsname uts;
        if (uname(&uts) == 0) {
            data += uts.sysname;
            data += uts.release;
            data += uts.version;
            data += uts.machine;
        }

        return hash_string(data);
    }

    std::vector<uint8_t> DeviceFingerprint::collect_hardware_serial() {
        std::string data;

        // Try multiple sources for hardware serial
        const char *serial_paths[] = {
                "/sys/devices/soc0/serial_number",
                "/sys/class/android_usb/android0/iSerial",
                "/sys/devices/virtual/android_usb/android0/iSerial"
        };

        for (const char *path: serial_paths) {
            std::string content = read_file(path);
            if (!content.empty()) {
                data += content;
                break;
            }
        }

        // Also try /proc/cpuinfo Serial field
        int fd = open("/proc/cpuinfo", O_RDONLY);
        if (fd >= 0) {
            char buf[4096];
            ssize_t n = read(fd, buf, sizeof(buf) - 1);
            close(fd);

            if (n > 0) {
                buf[n] = '\0';
                const char *serial = strstr(buf, "Serial");
                if (serial) {
                    const char *colon = strchr(serial, ':');
                    const char *end = strchr(serial, '\n');
                    if (colon && end && colon < end) {
                        data += std::string(colon + 1, end);
                    }
                }
            }
        }

        return hash_string(data);
    }

    std::vector<uint8_t> DeviceFingerprint::collect_boot_id() {
        std::string content = read_file("/proc/sys/kernel/random/boot_id");
        return hash_string(content);
    }

    std::vector<uint8_t> DeviceFingerprint::collect_build_fingerprint() {
        std::string data;

        // Read build.prop
        const char *prop_files[] = {
                "/system/build.prop",
                "/vendor/build.prop",
                "/product/build.prop"
        };

        for (const char *path: prop_files) {
            int fd = open(path, O_RDONLY);
            if (fd < 0) continue;

            char buf[16384];
            ssize_t n = read(fd, buf, sizeof(buf) - 1);
            close(fd);

            if (n <= 0) continue;
            buf[n] = '\0';

            // Extract stable properties
            const char *props[] = {
                    "ro.build.fingerprint",
                    "ro.build.id",
                    "ro.build.display.id",
                    "ro.product.model",
                    "ro.product.brand",
                    "ro.product.device",
                    "ro.product.board",
                    "ro.hardware"
            };

            for (const char *prop: props) {
                const char *pos = strstr(buf, prop);
                if (pos) {
                    const char *eq = strchr(pos, '=');
                    const char *end = strchr(pos, '\n');
                    if (eq && end && eq < end) {
                        data += std::string(eq + 1, end);
                    }
                }
            }
        }

        return hash_string(data);
    }

    std::vector<uint8_t> DeviceFingerprint::collect_selinux_info() {
        std::string data;

        // SELinux enforce status
        data += read_file("/sys/fs/selinux/enforce");

        // SELinux policy version
        data += read_file("/sys/fs/selinux/policyvers");

        return hash_string(data);
    }

    std::vector<uint8_t> DeviceFingerprint::collect_partition_info() {
        std::string data;

        // Read partition layout
        int fd = open("/proc/partitions", O_RDONLY);
        if (fd >= 0) {
            char buf[4096];
            ssize_t n = read(fd, buf, sizeof(buf) - 1);
            close(fd);

            if (n > 0) {
                buf[n] = '\0';
                data = buf;
            }
        }

        return hash_string(data);
    }

    std::vector<uint8_t> DeviceFingerprint::collect_timing_fingerprint() {
        return TimingFingerprint::measure();
    }

    std::vector<uint8_t> DeviceFingerprint::combine_components(
            const DeviceFingerprintComponents &components
    ) {
        std::vector<uint8_t> combined;

        auto append = [&combined](const std::vector<uint8_t> &data) {
            combined.insert(combined.end(), data.begin(), data.end());
        };

        append(components.cpu_info);
        append(components.memory_info);
        append(components.kernel_info);
        append(components.hardware_serial);
        append(components.boot_id);
        append(components.build_fingerprint);
        append(components.selinux_info);
        append(components.partition_info);
        append(components.timing_fingerprint);

        return CryptoUtils::sha256(combined);
    }

    int DeviceFingerprint::compare_components(
            const DeviceFingerprintComponents &a,
            const DeviceFingerprintComponents &b,
            int &matched,
            int &total
    ) {
        matched = 0;
        total = 0;

        auto compare = [&](const std::vector<uint8_t> &x, const std::vector<uint8_t> &y,
                           int weight) {
            if (x.empty() && y.empty()) return;
            total += weight;
            if (CryptoUtils::constant_time_compare(x, y)) {
                matched += weight;
            }
        };

        // Weight components by stability and importance
        compare(a.cpu_info, b.cpu_info, 15);
        compare(a.memory_info, b.memory_info, 10);
        compare(a.kernel_info, b.kernel_info, 10);
        compare(a.hardware_serial, b.hardware_serial, 20);
        compare(a.build_fingerprint, b.build_fingerprint, 20);
        compare(a.selinux_info, b.selinux_info, 5);
        compare(a.partition_info, b.partition_info, 10);
        // Timing fingerprint has lower weight due to variability
        compare(a.timing_fingerprint, b.timing_fingerprint, 10);

        if (total == 0) return 100;
        return (matched * 100) / total;
    }

    bool DeviceFingerprint::load_binding() {
        int fd = open(config_.binding_file_path.c_str(), O_RDONLY);
        if (fd < 0) return false;

        // Read fingerprint (32 bytes SHA-256)
        std::vector<uint8_t> fingerprint(32);
        ssize_t n = read(fd, fingerprint.data(), 32);

        if (n != 32) {
            close(fd);
            return false;
        }

        // Read component hashes
        DeviceFingerprintComponents components;

        auto read_component = [fd](std::vector<uint8_t> &comp) -> bool {
            uint32_t size;
            if (read(fd, &size, 4) != 4) return false;
            if (size > 1024) return false;  // Sanity check

            comp.resize(size);
            if (size > 0 && read(fd, comp.data(), size) != static_cast<ssize_t>(size)) {
                return false;
            }
            return true;
        };

        bool ok = read_component(components.cpu_info) &&
                  read_component(components.memory_info) &&
                  read_component(components.kernel_info) &&
                  read_component(components.hardware_serial) &&
                  read_component(components.boot_id) &&
                  read_component(components.build_fingerprint) &&
                  read_component(components.selinux_info) &&
                  read_component(components.partition_info) &&
                  read_component(components.timing_fingerprint);

        close(fd);

        if (!ok) return false;

        bound_fingerprint_ = fingerprint;
        bound_components_ = components;

        return true;
    }

    bool DeviceFingerprint::save_binding(
            const std::vector<uint8_t> &fingerprint,
            const DeviceFingerprintComponents &components
    ) {
        // Ensure directory exists
        std::string dir = config_.binding_file_path.substr(
                0, config_.binding_file_path.rfind('/'));
        mkdir(dir.c_str(), 0700);

        int fd = open(config_.binding_file_path.c_str(),
                      O_WRONLY | O_CREAT | O_TRUNC, 0600);
        if (fd < 0) {
            FUTON_LOGE("Failed to create binding file: %s", strerror(errno));
            return false;
        }

        // Write fingerprint
        if (write(fd, fingerprint.data(), fingerprint.size()) !=
            static_cast<ssize_t>(fingerprint.size())) {
            close(fd);
            return false;
        }

        // Write components
        auto write_component = [fd](const std::vector<uint8_t> &comp) -> bool {
            uint32_t size = comp.size();
            if (write(fd, &size, 4) != 4) return false;
            if (size > 0 && write(fd, comp.data(), size) != static_cast<ssize_t>(size)) {
                return false;
            }
            return true;
        };

        bool ok = write_component(components.cpu_info) &&
                  write_component(components.memory_info) &&
                  write_component(components.kernel_info) &&
                  write_component(components.hardware_serial) &&
                  write_component(components.boot_id) &&
                  write_component(components.build_fingerprint) &&
                  write_component(components.selinux_info) &&
                  write_component(components.partition_info) &&
                  write_component(components.timing_fingerprint);

        close(fd);
        return ok;
    }

    std::string DeviceFingerprint::read_file(const std::string &path) {
        int fd = open(path.c_str(), O_RDONLY);
        if (fd < 0) return "";

        char buf[4096];
        ssize_t n = read(fd, buf, sizeof(buf) - 1);
        close(fd);

        if (n <= 0) return "";
        buf[n] = '\0';

        // Trim whitespace
        char *start = buf;
        while (*start && (*start == ' ' || *start == '\t' || *start == '\n')) start++;

        char *end = buf + n - 1;
        while (end > start && (*end == ' ' || *end == '\t' || *end == '\n' || *end == '\0')) {
            *end = '\0';
            end--;
        }

        return std::string(start);
    }

    std::vector<uint8_t> DeviceFingerprint::hash_string(const std::string &str) {
        if (str.empty()) return {};
        return CryptoUtils::sha256(std::vector<uint8_t>(str.begin(), str.end()));
    }

} // namespace futon::core::auth
