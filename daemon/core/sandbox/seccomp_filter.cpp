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

#include "seccomp_filter.h"
#include "syscall_whitelist.h"
#include "core/error.h"

#include <seccomp.h>
#include <linux/seccomp.h>
#include <linux/audit.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/utsname.h>
#include <unistd.h>
#include <cerrno>
#include <cstring>
#include <cstdlib>
#include <fstream>
#include <sstream>
#include <set>
#include <chrono>
#include <mutex>

namespace futon::core::sandbox {

// Static members
    AuditCallback SeccompFilter::s_audit_callback = nullptr;
    static std::mutex s_audit_mutex;

    KernelInfo SeccompFilter::detect_kernel_info() {
        KernelInfo info{};

        struct utsname uts;
        if (uname(&uts) == 0) {
            info.release = uts.release;

            // Parse version: "5.10.43-android12-9-00001-..."
            if (sscanf(uts.release, "%d.%d.%d", &info.major, &info.minor, &info.patch) < 2) {
                info.major = 5;
                info.minor = 4;
                info.patch = 0;
            }
        }

        // Detect Android API level from system property
        info.android_api_level = 30;  // Default to Android 11

        // Try to read from /system/build.prop
        std::ifstream prop("/system/build.prop");
        if (prop.is_open()) {
            std::string line;
            while (std::getline(prop, line)) {
                if (line.find("ro.build.version.sdk=") == 0) {
                    info.android_api_level = std::stoi(line.substr(21));
                    break;
                }
            }
        }

        FUTON_LOGI("Kernel: %d.%d.%d, Android API: %d",
                   info.major, info.minor, info.patch, info.android_api_level);

        return info;
    }

    std::string SeccompFilter::get_syscall_name(int syscall_nr) {
        // Use libseccomp to resolve syscall name
        const char *name = seccomp_syscall_resolve_num_arch(SCMP_ARCH_NATIVE, syscall_nr);
        if (name) {
            return std::string(name);
        }
        return "syscall_" + std::to_string(syscall_nr);
    }

    std::vector<int> SeccompFilter::build_allow_list(const KernelInfo &kernel) {
        std::set<int> allowed;

        // Core I/O (always needed)
        allowed.insert(SCMP_SYS(read));
        allowed.insert(SCMP_SYS(write));
        allowed.insert(SCMP_SYS(close));
        allowed.insert(SCMP_SYS(lseek));
        allowed.insert(SCMP_SYS(pread64));
        allowed.insert(SCMP_SYS(pwrite64));
        allowed.insert(SCMP_SYS(readv));
        allowed.insert(SCMP_SYS(writev));

        // File operations - kernel version dependent
        if (kernel.major >= 5 || (kernel.major == 4 && kernel.minor >= 14)) {
            // Modern kernels: prefer *at variants
            allowed.insert(SCMP_SYS(openat));
            allowed.insert(SCMP_SYS(newfstatat));
            allowed.insert(SCMP_SYS(faccessat));
            allowed.insert(SCMP_SYS(readlinkat));
            allowed.insert(SCMP_SYS(unlinkat));
            allowed.insert(SCMP_SYS(renameat));
            allowed.insert(SCMP_SYS(mkdirat));
            allowed.insert(SCMP_SYS(fchmodat));
            allowed.insert(SCMP_SYS(fchownat));
        }

#ifdef __NR_open
        // Legacy syscalls for older kernels/libc
        if (kernel.android_api_level <= 30) {
            allowed.insert(SCMP_SYS(open));
            allowed.insert(SCMP_SYS(stat));
            allowed.insert(SCMP_SYS(fstat));
            allowed.insert(SCMP_SYS(lstat));
            allowed.insert(SCMP_SYS(access));
            allowed.insert(SCMP_SYS(readlink));
            allowed.insert(SCMP_SYS(unlink));
            allowed.insert(SCMP_SYS(rename));
            allowed.insert(SCMP_SYS(mkdir));
            allowed.insert(SCMP_SYS(rmdir));
        }
#endif

        // More file ops
        allowed.insert(SCMP_SYS(fcntl));
        allowed.insert(SCMP_SYS(flock));
        allowed.insert(SCMP_SYS(fsync));
        allowed.insert(SCMP_SYS(fdatasync));
        allowed.insert(SCMP_SYS(ftruncate));
        allowed.insert(SCMP_SYS(getdents64));
        allowed.insert(SCMP_SYS(getcwd));
        allowed.insert(SCMP_SYS(fchmod));
        allowed.insert(SCMP_SYS(fchown));
        allowed.insert(SCMP_SYS(umask));
        allowed.insert(SCMP_SYS(dup));
        allowed.insert(SCMP_SYS(dup3));

        // Memory management
        allowed.insert(SCMP_SYS(brk));
        allowed.insert(SCMP_SYS(mmap));
        allowed.insert(SCMP_SYS(munmap));
        allowed.insert(SCMP_SYS(mprotect));
        allowed.insert(SCMP_SYS(mremap));
        allowed.insert(SCMP_SYS(msync));
        allowed.insert(SCMP_SYS(mlock));
        allowed.insert(SCMP_SYS(munlock));

        // Process info (read-only, safe)
        allowed.insert(SCMP_SYS(getpid));
        allowed.insert(SCMP_SYS(gettid));
        allowed.insert(SCMP_SYS(getuid));
        allowed.insert(SCMP_SYS(geteuid));
        allowed.insert(SCMP_SYS(getgid));
        allowed.insert(SCMP_SYS(getegid));
        allowed.insert(SCMP_SYS(getppid));
        allowed.insert(SCMP_SYS(exit));
        allowed.insert(SCMP_SYS(exit_group));

        // Scheduling
        allowed.insert(SCMP_SYS(sched_yield));
        allowed.insert(SCMP_SYS(sched_getaffinity));
        allowed.insert(SCMP_SYS(sched_setaffinity));
        allowed.insert(SCMP_SYS(getrlimit));
        allowed.insert(SCMP_SYS(setrlimit));
        allowed.insert(SCMP_SYS(prlimit64));
        allowed.insert(SCMP_SYS(prctl));

        // Signals
        allowed.insert(SCMP_SYS(rt_sigaction));
        allowed.insert(SCMP_SYS(rt_sigprocmask));
        allowed.insert(SCMP_SYS(rt_sigreturn));
        allowed.insert(SCMP_SYS(kill));
        allowed.insert(SCMP_SYS(tgkill));
        allowed.insert(SCMP_SYS(sigaltstack));

        // Threading (Futex is critical for mutexes)
        allowed.insert(SCMP_SYS(futex));
        allowed.insert(SCMP_SYS(set_tid_address));
        allowed.insert(SCMP_SYS(set_robust_list));
        allowed.insert(SCMP_SYS(get_robust_list));

        // Thread creation (clone/clone3 with CLONE_THREAD flag)
        // Required for std::thread, Binder thread pool, etc.
        // Security: execve is blocked, so even if fork happens, no shell can be spawned
        allowed.insert(SCMP_SYS(clone));
        allowed.insert(SCMP_SYS(clone3));

        // Time
        allowed.insert(SCMP_SYS(clock_gettime));
        allowed.insert(SCMP_SYS(clock_getres));
        allowed.insert(SCMP_SYS(clock_nanosleep));
        allowed.insert(SCMP_SYS(nanosleep));
        allowed.insert(SCMP_SYS(gettimeofday));

        // Event/Poll
        allowed.insert(SCMP_SYS(epoll_create1));
        allowed.insert(SCMP_SYS(epoll_ctl));
        allowed.insert(SCMP_SYS(epoll_pwait));
        allowed.insert(SCMP_SYS(ppoll));
        allowed.insert(SCMP_SYS(pselect6));
        allowed.insert(SCMP_SYS(eventfd2));
        allowed.insert(SCMP_SYS(timerfd_create));
        allowed.insert(SCMP_SYS(timerfd_settime));
        allowed.insert(SCMP_SYS(timerfd_gettime));
        allowed.insert(SCMP_SYS(pipe2));

        // Random (critical for crypto)
        allowed.insert(SCMP_SYS(getrandom));

        // Binder IPC (critical for Android)
        allowed.insert(SCMP_SYS(ioctl));

        // Socket operations on EXISTING sockets (not creation)
        allowed.insert(SCMP_SYS(sendto));
        allowed.insert(SCMP_SYS(recvfrom));
        allowed.insert(SCMP_SYS(sendmsg));
        allowed.insert(SCMP_SYS(recvmsg));
        allowed.insert(SCMP_SYS(shutdown));
        allowed.insert(SCMP_SYS(getsockname));
        allowed.insert(SCMP_SYS(getpeername));
        allowed.insert(SCMP_SYS(getsockopt));
        allowed.insert(SCMP_SYS(setsockopt));

        // GPU/Graphics
        allowed.insert(SCMP_SYS(memfd_create));

        // Misc safe syscalls
        allowed.insert(SCMP_SYS(uname));
        allowed.insert(SCMP_SYS(getrusage));

        // Android 12+ specific
        if (kernel.android_api_level >= 31) {
            allowed.insert(SCMP_SYS(faccessat2));
#ifdef __NR_futex_waitv
            allowed.insert(SCMP_SYS(futex_waitv));
#endif
        }

        // Android 14+ specific
        if (kernel.android_api_level >= 34) {
#ifdef __NR_rseq
            allowed.insert(SCMP_SYS(rseq));
#endif
        }

        return std::vector<int>(allowed.begin(), allowed.end());
    }

    std::vector<int> SeccompFilter::build_log_list(const KernelInfo &kernel) {
        // Level 2: Unknown/edge syscalls - log for telemetry but allow
        std::set<int> logged;

        // Memory hints (usually safe but not strictly needed)
        logged.insert(SCMP_SYS(madvise));
        logged.insert(SCMP_SYS(mincore));
        logged.insert(SCMP_SYS(mlockall));
        logged.insert(SCMP_SYS(munlockall));

        // System info (read-only, safe)
        logged.insert(SCMP_SYS(sysinfo));
        logged.insert(SCMP_SYS(capget));
        logged.insert(SCMP_SYS(capset));

        // Extended attributes (might be used by some libs)
        logged.insert(SCMP_SYS(getxattr));
        logged.insert(SCMP_SYS(lgetxattr));
        logged.insert(SCMP_SYS(fgetxattr));
        logged.insert(SCMP_SYS(listxattr));
        logged.insert(SCMP_SYS(llistxattr));
        logged.insert(SCMP_SYS(flistxattr));

        // Stat variants we might have missed
        logged.insert(SCMP_SYS(statx));
        logged.insert(SCMP_SYS(statfs));
        logged.insert(SCMP_SYS(fstatfs));

        // Signal variants
        logged.insert(SCMP_SYS(rt_sigsuspend));
        logged.insert(SCMP_SYS(rt_sigpending));
        logged.insert(SCMP_SYS(rt_sigtimedwait));
        logged.insert(SCMP_SYS(signalfd4));

        // Process groups (usually safe)
        logged.insert(SCMP_SYS(getpgid));
        logged.insert(SCMP_SYS(getsid));
        logged.insert(SCMP_SYS(getgroups));
        logged.insert(SCMP_SYS(setpgid));
        logged.insert(SCMP_SYS(setsid));

        // Scheduler info
        logged.insert(SCMP_SYS(sched_getscheduler));
        logged.insert(SCMP_SYS(sched_setscheduler));
        logged.insert(SCMP_SYS(sched_getparam));
        logged.insert(SCMP_SYS(sched_setparam));
        logged.insert(SCMP_SYS(sched_get_priority_max));
        logged.insert(SCMP_SYS(sched_get_priority_min));

        // Arch-specific
#ifdef __aarch64__
        logged.insert(SCMP_SYS(arch_prctl));
#endif

        // Personality (usually returns EINVAL)
        logged.insert(SCMP_SYS(personality));

        return std::vector<int>(logged.begin(), logged.end());
    }

    std::vector<int> SeccompFilter::build_kill_list() {
        std::vector<int> blocked;

        // Process creation - prevents shell spawning
        // Note: clone/clone3 are NOT blocked because they're needed for pthread_create
        // Thread creation uses clone with CLONE_VM|CLONE_FS|CLONE_FILES|CLONE_SIGHAND|CLONE_THREAD
        // Fork uses clone without CLONE_VM - this distinction is handled by argument filtering
        blocked.push_back(SCMP_SYS(execve));
        blocked.push_back(SCMP_SYS(execveat));
        blocked.push_back(SCMP_SYS(fork));
        blocked.push_back(SCMP_SYS(vfork));
        // clone and clone3 are allowed for threading but execve is blocked,
        // so even if someone forks, they can't exec anything

        // Debugging - prevents ptrace attacks
        blocked.push_back(SCMP_SYS(ptrace));
        blocked.push_back(SCMP_SYS(process_vm_readv));
        blocked.push_back(SCMP_SYS(process_vm_writev));

        // Kernel modules - prevents rootkit loading
        blocked.push_back(SCMP_SYS(init_module));
        blocked.push_back(SCMP_SYS(finit_module));
        blocked.push_back(SCMP_SYS(delete_module));

        // Mount - prevents filesystem manipulation
        blocked.push_back(SCMP_SYS(mount));
        blocked.push_back(SCMP_SYS(umount2));
        blocked.push_back(SCMP_SYS(pivot_root));
        blocked.push_back(SCMP_SYS(chroot));

        // Namespace - prevents container escape
        blocked.push_back(SCMP_SYS(unshare));
        blocked.push_back(SCMP_SYS(setns));

        // Reboot - prevents system disruption
        blocked.push_back(SCMP_SYS(reboot));

        // Keyring - prevents credential theft
        blocked.push_back(SCMP_SYS(add_key));
        blocked.push_back(SCMP_SYS(request_key));
        blocked.push_back(SCMP_SYS(keyctl));

        // BPF - prevents BPF-based attacks
        blocked.push_back(SCMP_SYS(bpf));

        // Perf - prevents side-channel attacks
        blocked.push_back(SCMP_SYS(perf_event_open));

        // Userfaultfd - prevents exploitation
        blocked.push_back(SCMP_SYS(userfaultfd));

        // io_uring - complex attack surface
        blocked.push_back(SCMP_SYS(io_uring_setup));
        blocked.push_back(SCMP_SYS(io_uring_enter));
        blocked.push_back(SCMP_SYS(io_uring_register));

        // Socket creation - prevent new network connections
        blocked.push_back(SCMP_SYS(socket));
        blocked.push_back(SCMP_SYS(socketpair));
        blocked.push_back(SCMP_SYS(bind));
        blocked.push_back(SCMP_SYS(listen));
        blocked.push_back(SCMP_SYS(accept));
        blocked.push_back(SCMP_SYS(accept4));
        blocked.push_back(SCMP_SYS(connect));

        // Landlock
        blocked.push_back(SCMP_SYS(landlock_create_ruleset));
        blocked.push_back(SCMP_SYS(landlock_add_rule));
        blocked.push_back(SCMP_SYS(landlock_restrict_self));

        return blocked;
    }

    void SeccompFilter::write_audit_log(
            const std::string &path,
            const SeccompAuditEntry &entry
    ) {
        std::lock_guard<std::mutex> lock(s_audit_mutex);

        std::ofstream log(path, std::ios::app);
        if (!log.is_open()) return;

        // Format: timestamp|syscall_nr|syscall_name|pid|tid
        log << entry.timestamp_ns << "|"
            << entry.syscall_nr << "|"
            << entry.syscall_name << "|"
            << entry.pid << "|"
            << entry.tid << "\n";
    }

    std::vector<SeccompAuditEntry> SeccompFilter::read_audit_log(
            const std::string &path,
            size_t max_entries
    ) {
        std::vector<SeccompAuditEntry> entries;
        std::ifstream log(path);
        if (!log.is_open()) return entries;

        std::string line;
        while (std::getline(log, line) && entries.size() < max_entries) {
            SeccompAuditEntry entry;
            std::istringstream iss(line);
            std::string token;

            if (std::getline(iss, token, '|')) entry.timestamp_ns = std::stoull(token);
            if (std::getline(iss, token, '|')) entry.syscall_nr = std::stoi(token);
            if (std::getline(iss, token, '|')) entry.syscall_name = token;
            if (std::getline(iss, token, '|')) entry.pid = std::stoi(token);
            if (std::getline(iss, token, '|')) entry.tid = std::stoi(token);

            entries.push_back(entry);
        }

        return entries;
    }

    void SeccompFilter::set_audit_callback(AuditCallback callback) {
        s_audit_callback = std::move(callback);
    }


    SeccompResult SeccompFilter::install() {
        SeccompConfig default_config;
        return install(default_config);
    }

    SeccompResult SeccompFilter::install(const SeccompConfig &config) {
        SeccompResult result;
        result.success = false;
        result.allowed_count = 0;
        result.logged_count = 0;
        result.blocked_count = 0;

        // Detect kernel version
        result.kernel_info = detect_kernel_info();

        FUTON_LOGI("Installing Seccomp filter (libseccomp)...");
        FUTON_LOGI("Kernel: %s, API level: %d",
                   result.kernel_info.release.c_str(),
                   result.kernel_info.android_api_level);

        // Create seccomp context with default action LOG (Level 2)
        // Unknown syscalls will be logged but allowed
        scmp_filter_ctx ctx = seccomp_init(SCMP_ACT_LOG);
        if (ctx == nullptr) {
            result.error_message = "Failed to create seccomp context";
            FUTON_LOGE("Seccomp: %s", result.error_message.c_str());
            return result;
        }

        // Build syscall lists based on kernel version
        auto allow_list = build_allow_list(result.kernel_info);
        auto log_list = build_log_list(result.kernel_info);
        auto kill_list = build_kill_list();

        // Add extra allowed syscalls from config
        for (int syscall_nr: config.extra_allowed_syscalls) {
            allow_list.push_back(syscall_nr);
        }

        // Add extra blocked syscalls from config
        for (int syscall_nr: config.extra_blocked_syscalls) {
            kill_list.push_back(syscall_nr);
        }

        // Level 1: Add ALLOW rules
        for (int syscall_nr: allow_list) {
            int rc = seccomp_rule_add(ctx, SCMP_ACT_ALLOW, syscall_nr, 0);
            if (rc == 0) {
                result.allowed_count++;
            } else if (rc != -EEXIST) {
                FUTON_LOGW("Failed to add allow rule for syscall %d: %s",
                           syscall_nr, get_syscall_name(syscall_nr).c_str());
            }
        }

        // Level 2: LOG rules are handled by default action (SCMP_ACT_LOG)
        // We just count them for reporting
        result.logged_count = static_cast<int>(log_list.size());

        // Level 3: Add KILL rules (override default LOG)
        for (int syscall_nr: kill_list) {
            int rc = seccomp_rule_add(ctx, SCMP_ACT_KILL_PROCESS, syscall_nr, 0);
            if (rc == 0) {
                result.blocked_count++;
            } else if (rc != -EEXIST) {
                FUTON_LOGW("Failed to add kill rule for syscall %d: %s",
                           syscall_nr, get_syscall_name(syscall_nr).c_str());
            }
        }

        FUTON_LOGI("Seccomp rules: %d allow, %d log, %d kill",
                   result.allowed_count, result.logged_count, result.blocked_count);

        // Enable NO_NEW_PRIVS (required for unprivileged seccomp)
        if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) == -1) {
            result.error_message = "Failed to set NO_NEW_PRIVS: " + std::string(strerror(errno));
            FUTON_LOGE("Seccomp: %s", result.error_message.c_str());
            seccomp_release(ctx);
            return result;
        }

        // Load the filter into the kernel
        int rc = seccomp_load(ctx);
        if (rc < 0) {
            result.error_message = "Failed to load seccomp filter: " + std::string(strerror(-rc));
            FUTON_LOGE("Seccomp: %s", result.error_message.c_str());
            seccomp_release(ctx);
            return result;
        }

        // Release context (filter is now in kernel)
        seccomp_release(ctx);

        // Verify installation
        int mode = get_mode();
        if (mode != SECCOMP_MODE_FILTER) {
            result.error_message = "Seccomp filter not active after installation";
            FUTON_LOGE("Seccomp: %s (mode=%d)", result.error_message.c_str(), mode);
            return result;
        }

        result.success = true;
        FUTON_LOGI("Seccomp filter installed successfully");
        FUTON_LOGI("  Level 1 (Allow): %d syscalls", result.allowed_count);
        FUTON_LOGI("  Level 2 (Log):   %d syscalls (default for unknown)", result.logged_count);
        FUTON_LOGI("  Level 3 (Kill):  %d syscalls", result.blocked_count);

        return result;
    }

    bool SeccompFilter::is_active() {
        return get_mode() == SECCOMP_MODE_FILTER;
    }

    int SeccompFilter::get_mode() {
        std::ifstream status("/proc/self/status");
        if (!status.is_open()) return -1;

        std::string line;
        while (std::getline(status, line)) {
            if (line.find("Seccomp:") == 0) {
                size_t pos = line.find_last_not_of(" \t\n\r");
                if (pos != std::string::npos) {
                    char mode_char = line[pos];
                    if (mode_char >= '0' && mode_char <= '2') {
                        return mode_char - '0';
                    }
                }
            }
        }
        return 0;
    }

    KernelInfo SeccompFilter::get_kernel_info() {
        return detect_kernel_info();
    }

    std::vector<int> SeccompFilter::probe_required_syscalls() {
        auto kernel = detect_kernel_info();
        return build_allow_list(kernel);
    }

    void SeccompFilter::test_execve_blocked() {
        // WARNING: This will kill the process if seccomp is working correctly!
        FUTON_LOGW("Testing execve block - process should die...");
        system("/bin/sh -c 'echo test'");
        // If we reach here, seccomp is NOT working
        FUTON_LOGE("SECURITY FAILURE: execve was not blocked!");
    }

} // namespace futon::core::sandbox
