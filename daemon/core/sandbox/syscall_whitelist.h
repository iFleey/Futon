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

#ifndef FUTON_CORE_SANDBOX_SYSCALL_WHITELIST_H
#define FUTON_CORE_SANDBOX_SYSCALL_WHITELIST_H

#include <sys/syscall.h>
#include <array>

namespace futon::core::sandbox {

// =============================================================================
// Syscall Whitelist for Futon Daemon
// =============================================================================

// -----------------------------------------------------------------------------
// ALLOWED SYSCALLS - Minimum set required for daemon operation
// -----------------------------------------------------------------------------

// File I/O (required for config, models, logging)
    constexpr int SYSCALLS_FILE_IO[] = {
            __NR_read,
            __NR_write,
            __NR_close,
            __NR_lseek,
            __NR_pread64,
            __NR_pwrite64,
            __NR_readv,
            __NR_writev,
            __NR_preadv,
            __NR_pwritev,
#ifdef __NR_open
            __NR_open,          // Legacy, some libc still use it
#endif
            __NR_openat,        // Modern replacement for open
#ifdef __NR_stat
            __NR_stat,          // Legacy
#endif
#ifdef __NR_fstat
            __NR_fstat,         // Legacy
#endif
#ifdef __NR_lstat
            __NR_lstat,         // Legacy
#endif
            __NR_newfstatat,    // Modern replacement (fstatat)
            __NR_statx,         // Even newer stat
#ifdef __NR_access
            __NR_access,        // Legacy
#endif
            __NR_faccessat,     // Modern replacement
            __NR_faccessat2,    // Newer variant
#ifdef __NR_dup
            __NR_dup,
#endif
            __NR_dup3,
            __NR_fcntl,
            __NR_flock,
            __NR_fsync,
            __NR_fdatasync,
            __NR_ftruncate,
#ifdef __NR_getdents
            __NR_getdents,      // Legacy
#endif
            __NR_getdents64,    // Modern
#ifdef __NR_getcwd
            __NR_getcwd,
#endif
#ifdef __NR_readlink
            __NR_readlink,      // Legacy
#endif
            __NR_readlinkat,    // Modern
#ifdef __NR_unlink
            __NR_unlink,        // Legacy
#endif
            __NR_unlinkat,      // Modern
#ifdef __NR_rename
            __NR_rename,        // Legacy
#endif
            __NR_renameat,      // Modern
            __NR_renameat2,     // Newer
#ifdef __NR_mkdir
            __NR_mkdir,         // Legacy
#endif
            __NR_mkdirat,       // Modern
#ifdef __NR_rmdir
            __NR_rmdir,         // Legacy (covered by unlinkat)
#endif
            __NR_fchmod,
            __NR_fchmodat,
            __NR_fchown,
            __NR_fchownat,
            __NR_umask,
    };
    constexpr size_t SYSCALLS_FILE_IO_COUNT = sizeof(SYSCALLS_FILE_IO) / sizeof(int);

// Memory management (required for allocations, mmap for GPU/inference)
    constexpr int SYSCALLS_MEMORY[] = {
            __NR_brk,
            __NR_mmap,
            __NR_munmap,
            __NR_mprotect,      // Note: we block PROT_EXEC via argument filtering
            __NR_mremap,
            __NR_madvise,
            __NR_mlock,
            __NR_mlock2,
            __NR_munlock,
            __NR_mlockall,
            __NR_munlockall,
            __NR_mincore,
            __NR_msync,
    };
    constexpr size_t SYSCALLS_MEMORY_COUNT = sizeof(SYSCALLS_MEMORY) / sizeof(int);

// Process/Thread (required for threading, but NOT fork/exec)
    constexpr int SYSCALLS_PROCESS[] = {
            __NR_exit,
            __NR_exit_group,
            __NR_getpid,
            __NR_gettid,
            __NR_getuid,
            __NR_geteuid,
            __NR_getgid,
            __NR_getegid,
            __NR_getppid,
            __NR_getpgid,
            __NR_getsid,
            __NR_getgroups,
            __NR_setpgid,
            __NR_setsid,
            __NR_getrlimit,
            __NR_setrlimit,
            __NR_prlimit64,
            __NR_getrusage,
            __NR_sched_yield,
            __NR_sched_getaffinity,
            __NR_sched_setaffinity,
            __NR_sched_getscheduler,
            __NR_sched_setscheduler,
            __NR_sched_getparam,
            __NR_sched_setparam,
            __NR_sched_get_priority_max,
            __NR_sched_get_priority_min,
            __NR_prctl,         // Needed for PR_SET_NAME, etc.
#ifdef __NR_arch_prctl
            __NR_arch_prctl,    // x86_64 specific
#endif
            __NR_set_tid_address,
            __NR_set_robust_list,
            __NR_get_robust_list,
#ifdef __NR_rseq
            __NR_rseq,          // Restartable sequences (glibc 2.35+)
#endif
    };

// Calculate count at runtime to handle conditional syscalls
    inline size_t get_syscalls_process_count() {
        static const int arr[] = {
                __NR_sched_yield,
                __NR_sched_getaffinity,
                __NR_sched_setaffinity,
                __NR_sched_getscheduler,
                __NR_sched_setscheduler,
                __NR_sched_getparam,
                __NR_sched_setparam,
                __NR_sched_get_priority_max,
                __NR_sched_get_priority_min,
                __NR_prctl,
#ifdef __NR_arch_prctl
                __NR_arch_prctl,
#endif
                __NR_set_tid_address,
                __NR_set_robust_list,
                __NR_get_robust_list,
#ifdef __NR_rseq
                __NR_rseq,
#endif
        };
        return sizeof(arr) / sizeof(int);
    }

    constexpr size_t SYSCALLS_PROCESS_COUNT =
            sizeof(SYSCALLS_PROCESS) / sizeof(SYSCALLS_PROCESS[0]);

// Signals (required for signal handling)
    constexpr int SYSCALLS_SIGNAL[] = {
            __NR_rt_sigaction,
            __NR_rt_sigprocmask,
            __NR_rt_sigreturn,
            __NR_rt_sigsuspend,
            __NR_rt_sigpending,
            __NR_rt_sigtimedwait,
            __NR_rt_sigqueueinfo,
            __NR_rt_tgsigqueueinfo,
            __NR_kill,          // Needed for self-signaling
            __NR_tgkill,
            __NR_tkill,
            __NR_sigaltstack,
    };
    constexpr size_t SYSCALLS_SIGNAL_COUNT = sizeof(SYSCALLS_SIGNAL) / sizeof(int);

// Synchronization (required for threading)
    constexpr int SYSCALLS_SYNC[] = {
            __NR_futex,
#ifdef __NR_futex_waitv
            __NR_futex_waitv,   // Linux 5.16+
#endif
    };
    constexpr size_t SYSCALLS_SYNC_COUNT = sizeof(SYSCALLS_SYNC) / sizeof(int);

// Time (required for timing, sleep)
    constexpr int SYSCALLS_TIME[] = {
            __NR_clock_gettime,
            __NR_clock_getres,
            __NR_clock_nanosleep,
            __NR_nanosleep,
            __NR_gettimeofday,
#ifdef __NR_time
            __NR_time,          // Legacy
#endif
    };
    constexpr size_t SYSCALLS_TIME_COUNT = sizeof(SYSCALLS_TIME) / sizeof(int);

// Event/Poll (required for event loop)
    constexpr int SYSCALLS_EVENT[] = {
            __NR_epoll_create1,
            __NR_epoll_ctl,
            __NR_epoll_pwait,
#ifdef __NR_epoll_pwait2
            __NR_epoll_pwait2,  // Linux 5.11+
#endif
#ifdef __NR_poll
            __NR_poll,          // Legacy
#endif
            __NR_ppoll,
#ifdef __NR_select
            __NR_select,        // Legacy
#endif
            __NR_pselect6,
            __NR_eventfd2,
            __NR_timerfd_create,
            __NR_timerfd_settime,
            __NR_timerfd_gettime,
            __NR_signalfd4,
            __NR_pipe2,
    };
    constexpr size_t SYSCALLS_EVENT_COUNT = sizeof(SYSCALLS_EVENT) / sizeof(int);

// Random (required for crypto)
    constexpr int SYSCALLS_RANDOM[] = {
            __NR_getrandom,
    };
    constexpr size_t SYSCALLS_RANDOM_COUNT = sizeof(SYSCALLS_RANDOM) / sizeof(int);

// Binder IPC (required for Android IPC)
// Note: ioctl is allowed but we should filter BINDER_* commands only
    constexpr int SYSCALLS_BINDER[] = {
            __NR_ioctl,         // Binder uses ioctl
    };
    constexpr size_t SYSCALLS_BINDER_COUNT = sizeof(SYSCALLS_BINDER) / sizeof(int);

// Socket (limited - only for existing connections, NOT new connections)
// We allow operations on existing sockets but block socket() creation
    constexpr int SYSCALLS_SOCKET_OPS[] = {
            __NR_sendto,
            __NR_recvfrom,
            __NR_sendmsg,
            __NR_recvmsg,
            __NR_shutdown,
            __NR_getsockname,
            __NR_getpeername,
            __NR_getsockopt,
            __NR_setsockopt,
            // Note: socket(), bind(), listen(), accept() are NOT included
            // Daemon should create all sockets before seccomp is installed
    };
    constexpr size_t SYSCALLS_SOCKET_OPS_COUNT = sizeof(SYSCALLS_SOCKET_OPS) / sizeof(int);

// GPU/Graphics (required for EGL/GLES inference)
    constexpr int SYSCALLS_GPU[] = {
            // GPU drivers use these
            __NR_mmap,          // Already in MEMORY
            __NR_ioctl,         // Already in BINDER (GPU also uses ioctl)
            // DMA-BUF
#ifdef __NR_memfd_create
            __NR_memfd_create,
#endif
    };
    constexpr size_t SYSCALLS_GPU_COUNT = sizeof(SYSCALLS_GPU) / sizeof(int);

// Misc (required for various operations)
    constexpr int SYSCALLS_MISC[] = {
            __NR_uname,
            __NR_sysinfo,
            __NR_capget,
            __NR_capset,
#ifdef __NR_personality
            __NR_personality,   // Usually returns EINVAL, harmless
#endif
    };
    constexpr size_t SYSCALLS_MISC_COUNT = sizeof(SYSCALLS_MISC) / sizeof(int);

// -----------------------------------------------------------------------------
// BLOCKED SYSCALLS - Dangerous syscalls that are ALWAYS blocked
// -----------------------------------------------------------------------------

    constexpr int SYSCALLS_BLOCKED[] = {
            // Process creation - CRITICAL: prevents shell spawning
            __NR_execve,
            __NR_execveat,
#ifdef __NR_fork
            __NR_fork,
#endif
#ifdef __NR_vfork
            __NR_vfork,
#endif
            __NR_clone,
            __NR_clone3,

            // Debugging - prevents ptrace attacks
            __NR_ptrace,
            __NR_process_vm_readv,
            __NR_process_vm_writev,

            // Kernel modules - prevents rootkit loading
            __NR_init_module,
            __NR_finit_module,
            __NR_delete_module,

            // Mount - prevents filesystem manipulation
            __NR_mount,
            __NR_umount2,
            __NR_pivot_root,
            __NR_chroot,

            // Namespace - prevents container escape
            __NR_unshare,
            __NR_setns,

            // Reboot - prevents system disruption
            __NR_reboot,

            // Keyring - prevents credential theft
            __NR_add_key,
            __NR_request_key,
            __NR_keyctl,

            // BPF - prevents BPF-based attacks
            __NR_bpf,

            // Perf - prevents side-channel attacks
            __NR_perf_event_open,

            // Userfaultfd - prevents exploitation
            __NR_userfaultfd,

            // io_uring - complex attack surface
            __NR_io_uring_setup,
            __NR_io_uring_enter,
            __NR_io_uring_register,

            // Landlock - we don't need it and it's complex
            __NR_landlock_create_ruleset,
            __NR_landlock_add_rule,
            __NR_landlock_restrict_self,

            // Socket creation - prevent new network connections
            // (existing sockets from before seccomp are allowed)
            __NR_socket,
            __NR_socketpair,
            __NR_bind,
            __NR_listen,
            __NR_accept,
            __NR_accept4,
            __NR_connect,
    };
    constexpr size_t SYSCALLS_BLOCKED_COUNT = sizeof(SYSCALLS_BLOCKED) / sizeof(int);

// -----------------------------------------------------------------------------
// Helper to check if syscall is in a list
// -----------------------------------------------------------------------------

    template<size_t N>
    constexpr bool is_in_list(int syscall_nr, const int (&list)[N]) {
        for (size_t i = 0; i < N; ++i) {
            if (list[i] == syscall_nr) return true;
        }
        return false;
    }

} // namespace futon::core::sandbox

#endif // FUTON_CORE_SANDBOX_SYSCALL_WHITELIST_H
