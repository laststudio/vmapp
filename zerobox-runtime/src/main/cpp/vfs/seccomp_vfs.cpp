#include "zerobox_core.h"

#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <ucontext.h>
#include <unistd.h>
#include <linux/Audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>

namespace zerobox {
namespace vfs {

namespace {

struct SyscallListenerEntry {
    int syscall_nr;
    ZeroBoxSyscallCallback callback;
    void* user_data;
};

pthread_rwlock_t g_rules_lock = PTHREAD_RWLOCK_INITIALIZER;
std::vector<NativeVfsRule> g_passthrough_rules;
std::vector<NativeVfsRule> g_hide_rules;
std::vector<NativeVfsRule> g_redirect_rules;
std::vector<SyscallListenerEntry> g_syscall_listeners;

bool g_seccomp_installed = false;
bool g_enable_proc_cloak = true;
bool g_enable_binder_filter = true;
__thread bool g_in_sigsys_handler = false;

std::string NormalizePath(const std::string& raw) {
    if (raw.empty()) return "/";
    std::string out;
    out.reserve(raw.size());
    bool prev_slash = false;
    for (char c : raw) {
        if (c == '/') {
            if (!prev_slash) {
                out.push_back(c);
                prev_slash = true;
            }
        } else {
            out.push_back(c);
            prev_slash = false;
        }
    }
    if (out.size() > 1 && out.back() == '/') {
        out.pop_back();
    }
    return out;
}

bool MatchesPrefixBoundary(const std::string& path, const std::string& prefix) {
    if (prefix == "/") return !path.empty() && path[0] == '/';
    if (path.size() < prefix.size()) return false;
    if (path.compare(0, prefix.size(), prefix) != 0) return false;
    return path.size() == prefix.size() || path[prefix.size()] == '/';
}

void ReadSyscallContext(ucontext_t* uc, int* out_nr, long args[6]) {
#if defined(__aarch64__)
    *out_nr = static_cast<int>(uc->uc_mcontext.regs[8]);
    for (int i = 0; i < 6; ++i) {
        args[i] = static_cast<long>(uc->uc_mcontext.regs[i]);
    }
#elif defined(__arm__)
    *out_nr = static_cast<int>(uc->uc_mcontext.arm_r7);
    args[0] = static_cast<long>(uc->uc_mcontext.arm_r0);
    args[1] = static_cast<long>(uc->uc_mcontext.arm_r1);
    args[2] = static_cast<long>(uc->uc_mcontext.arm_r2);
    args[3] = static_cast<long>(uc->uc_mcontext.arm_r3);
    args[4] = static_cast<long>(uc->uc_mcontext.arm_r4);
    args[5] = static_cast<long>(uc->uc_mcontext.arm_r5);
#else
    *out_nr = -1;
    memset(args, 0, sizeof(long) * 6);
#endif
}

void WriteSyscallReturn(ucontext_t* uc, long ret_val) {
#if defined(__aarch64__)
    uc->uc_mcontext.regs[0] = static_cast<uint64_t>(ret_val);
#elif defined(__arm__)
    uc->uc_mcontext.arm_r0 = static_cast<unsigned long>(ret_val);
#endif
}

long DispatchPathSyscall(int nr, long a0, long a1, long a2, long a3, long a4, long a5) {
    // Handle dual-path syscalls (renameat / renameat2 / linkat)
#if defined(__NR_renameat)
    if (nr == __NR_renameat
#if defined(__NR_renameat2)
        || nr == __NR_renameat2
#endif
    ) {
        const char* old_p = reinterpret_cast<const char*>(a1);
        const char* new_p = reinterpret_cast<const char*>(a3);
        int err1 = 0, err2 = 0;
        std::string res_old = old_p ? ResolveVirtualPath(old_p, &err1) : "";
        std::string res_new = new_p ? ResolveVirtualPath(new_p, &err2) : "";
        if (err1 != 0) return -err1;
        if (err2 != 0) return -err2;
        return zerobox_raw_syscall(
            nr,
            a0,
            old_p ? reinterpret_cast<long>(res_old.c_str()) : 0,
            a2,
            new_p ? reinterpret_cast<long>(res_new.c_str()) : 0,
            a4,
            a5
        );
    }
#endif

    // Identify which argument holds the file path pointer
    int path_arg_idx = -1;
    bool is_open_family = false;
    bool is_readlink_family = false;

#if defined(__aarch64__)
    if (nr == __NR_openat) {
        path_arg_idx = 1;
        is_open_family = true;
    } else if (nr == __NR_faccessat || nr == __NR_newfstatat ||
               nr == __NR_mkdirat || nr == __NR_unlinkat || nr == __NR_fchmodat) {
        path_arg_idx = 1;
    } else if (nr == __NR_readlinkat) {
        path_arg_idx = 1;
        is_readlink_family = true;
    } else if (nr == __NR_statfs) {
        path_arg_idx = 0;
    }
#if defined(__NR_statx)
    else if (nr == __NR_statx) {
        path_arg_idx = 1;
    }
#endif
#elif defined(__arm__)
    if (nr == __NR_openat) {
        path_arg_idx = 1;
        is_open_family = true;
    } else if (nr == __NR_open) {
        path_arg_idx = 0;
        is_open_family = true;
    } else if (nr == __NR_faccessat || nr == __NR_fstatat64 ||
               nr == __NR_mkdirat || nr == __NR_unlinkat || nr == __NR_fchmodat) {
        path_arg_idx = 1;
    } else if (nr == __NR_access || nr == __NR_stat64 || nr == __NR_lstat64 ||
               nr == __NR_statfs || nr == __NR_mkdir || nr == __NR_unlink ||
               nr == __NR_rmdir || nr == __NR_chmod) {
        path_arg_idx = 0;
    } else if (nr == __NR_readlinkat) {
        path_arg_idx = 1;
        is_readlink_family = true;
    } else if (nr == __NR_readlink) {
        path_arg_idx = 0;
        is_readlink_family = true;
    }
#if defined(__NR_statx)
    else if (nr == __NR_statx) {
        path_arg_idx = 1;
    }
#endif
#endif

    long args[6] = {a0, a1, a2, a3, a4, a5};
    if (path_arg_idx < 0 || args[path_arg_idx] == 0) {
        return zerobox_raw_syscall(nr, a0, a1, a2, a3, a4, a5);
    }

    const char* orig_path = reinterpret_cast<const char*>(args[path_arg_idx]);

    // 1. Check /proc stealth cloak before VFS redirection
    if (g_enable_proc_cloak) {
        if (is_readlink_family) {
            char* user_buf = reinterpret_cast<char*>(args[path_arg_idx + 1]);
            size_t user_bufsiz = static_cast<size_t>(args[path_arg_idx + 2]);
            ssize_t cloaked_len = cloak::SanitizeReadlink(orig_path, user_buf, user_bufsiz);
            if (cloaked_len >= 0) {
                return static_cast<long>(cloaked_len);
            }
        } else if (is_open_family && cloak::ShouldInterceptProcPath(orig_path)) {
            int flags = static_cast<int>(args[path_arg_idx + 1]);
            mode_t mode = static_cast<mode_t>(args[path_arg_idx + 2]);
            int cloaked_fd = cloak::OpenSanitizedProcFile(orig_path, flags, mode);
            if (cloaked_fd >= 0) {
                return static_cast<long>(cloaked_fd);
            }
        }
    }

    // 2. Evaluate Passthrough / Hide / Redirect VFS rules
    int blocked_errno = 0;
    std::string resolved = ResolveVirtualPath(orig_path, &blocked_errno);
    if (blocked_errno != 0) {
        return -blocked_errno;
    }

    args[path_arg_idx] = reinterpret_cast<long>(resolved.c_str());
    long ret = zerobox_raw_syscall(nr, args[0], args[1], args[2], args[3], args[4], args[5]);

    if (is_open_family && ret >= 0 && g_enable_binder_filter) {
        binder::NotifyFileOpened(resolved.c_str(), static_cast<int>(ret));
    }
    return ret;
}

void SigsysTrapHandler(int signo, siginfo_t* info, void* ucontext_raw) {
    if (signo != SIGSYS || info == nullptr || ucontext_raw == nullptr) {
        return;
    }
    ucontext_t* uc = reinterpret_cast<ucontext_t*>(ucontext_raw);
    int nr = -1;
    long args[6] = {0};
    ReadSyscallContext(uc, &nr, args);

    if (g_in_sigsys_handler) {
        long raw_ret = zerobox_raw_syscall(nr, args[0], args[1], args[2], args[3], args[4], args[5]);
        WriteSyscallReturn(uc, raw_ret);
        return;
    }
    g_in_sigsys_handler = true;

    // Notify registered plugin syscall listeners first
    ZeroBoxSyscallContext plugin_ctx{};
    plugin_ctx.syscall_nr = nr;
    for (int i = 0; i < 6; ++i) {
        plugin_ctx.args[i] = static_cast<uintptr_t>(args[i]);
    }
    plugin_ctx.return_value = 0;
    plugin_ctx.override_return = 0;

    pthread_rwlock_rdlock(&g_rules_lock);
    for (const auto& listener : g_syscall_listeners) {
        if (listener.syscall_nr == nr && listener.callback != nullptr) {
            listener.callback(&plugin_ctx, listener.user_data);
        }
    }
    pthread_rwlock_unlock(&g_rules_lock);

    if (plugin_ctx.override_return) {
        WriteSyscallReturn(uc, static_cast<long>(plugin_ctx.return_value));
        g_in_sigsys_handler = false;
        return;
    }
    for (int i = 0; i < 6; ++i) {
        args[i] = static_cast<long>(plugin_ctx.args[i]);
    }

    long result = 0;
    if (nr == __NR_ioctl && g_enable_binder_filter && binder::IsBinderFd(static_cast<int>(args[0]))) {
        result = binder::HandleBinderIoctl(
            static_cast<int>(args[0]),
            static_cast<unsigned long>(args[1]),
            static_cast<uintptr_t>(args[2])
        );
    } else {
        result = DispatchPathSyscall(nr, args[0], args[1], args[2], args[3], args[4], args[5]);
    }

    WriteSyscallReturn(uc, result);
    g_in_sigsys_handler = false;
}

} // namespace

std::string ResolveVirtualPath(const char* input_path, int* out_blocked_errno) {
    if (out_blocked_errno != nullptr) {
        *out_blocked_errno = 0;
    }
    if (input_path == nullptr || input_path[0] != '/') {
        return input_path != nullptr ? std::string(input_path) : std::string();
    }

    std::string norm = NormalizePath(input_path);

    pthread_rwlock_rdlock(&g_rules_lock);

    // Priority 1: Passthrough rules (Hiding > Confrontation)
    for (const auto& rule : g_passthrough_rules) {
        if (MatchesPrefixBoundary(norm, rule.virtual_prefix)) {
            pthread_rwlock_unlock(&g_rules_lock);
            return std::string(input_path);
        }
    }

    // Priority 2: Hidden path rules
    for (const auto& rule : g_hide_rules) {
        if (MatchesPrefixBoundary(norm, rule.virtual_prefix)) {
            pthread_rwlock_unlock(&g_rules_lock);
            if (out_blocked_errno != nullptr) {
                *out_blocked_errno = ENOENT;
            }
            return norm;
        }
    }

    // Priority 3: Longest matching Redirect prefix
    const NativeVfsRule* best_match = nullptr;
    for (const auto& rule : g_redirect_rules) {
        if (MatchesPrefixBoundary(norm, rule.virtual_prefix)) {
            if (best_match == nullptr || rule.virtual_prefix.size() > best_match->virtual_prefix.size()) {
                best_match = &rule;
            }
        }
    }

    if (best_match != nullptr) {
        std::string suffix = norm.substr(best_match->virtual_prefix.size());
        std::string target = best_match->real_prefix + suffix;
        pthread_rwlock_unlock(&g_rules_lock);
        return target;
    }

    pthread_rwlock_unlock(&g_rules_lock);
    return std::string(input_path);
}

void AddPassthroughPrefix(const std::string& prefix) {
    std::string norm = NormalizePath(prefix);
    pthread_rwlock_wrlock(&g_rules_lock);
    g_passthrough_rules.push_back({VfsRuleKind::PASSTHROUGH, norm, ""});
    pthread_rwlock_unlock(&g_rules_lock);
}

void AddRedirectRule(const std::string& virtual_prefix, const std::string& real_prefix) {
    std::string v_norm = NormalizePath(virtual_prefix);
    std::string r_norm = NormalizePath(real_prefix);
    pthread_rwlock_wrlock(&g_rules_lock);
    g_redirect_rules.push_back({VfsRuleKind::REDIRECT, v_norm, r_norm});
    pthread_rwlock_unlock(&g_rules_lock);
}

void AddHiddenPrefix(const std::string& prefix) {
    std::string norm = NormalizePath(prefix);
    pthread_rwlock_wrlock(&g_rules_lock);
    g_hide_rules.push_back({VfsRuleKind::HIDE, norm, ""});
    pthread_rwlock_unlock(&g_rules_lock);
}

int RegisterSyscallListener(int syscall_nr, ZeroBoxSyscallCallback cb, void* user_data) {
    if (cb == nullptr) return -EINVAL;
    pthread_rwlock_wrlock(&g_rules_lock);
    g_syscall_listeners.push_back({syscall_nr, cb, user_data});
    pthread_rwlock_unlock(&g_rules_lock);
    return 0;
}

bool IsSeccompActive() {
    return g_seccomp_installed;
}

bool InitializeSeccompVfs(
    const std::string& guest_package,
    int user_id,
    const std::string& isolated_data_dir,
    const std::string& original_apk_path,
    bool enable_proc_cloak,
    bool enable_binder_filter
) {
    g_enable_proc_cloak = enable_proc_cloak;
    g_enable_binder_filter = enable_binder_filter;

    // Default system read-only passthrough paths (Hiding > Confrontation)
    AddPassthroughPrefix("/system");
    AddPassthroughPrefix("/apex");
    AddPassthroughPrefix("/vendor");
    AddPassthroughPrefix("/odm");
    AddPassthroughPrefix("/product");
    AddPassthroughPrefix(isolated_data_dir);

    // Default isolated private directory redirections for guest package
    if (!guest_package.empty() && !isolated_data_dir.empty()) {
        AddRedirectRule("/data/data/" + guest_package, isolated_data_dir);
        AddRedirectRule("/data/user/0/" + guest_package, isolated_data_dir);
        AddRedirectRule("/data/user/" + std::to_string(user_id) + "/" + guest_package, isolated_data_dir);
        AddRedirectRule("/data/user_de/0/" + guest_package, isolated_data_dir + "_de");
    }

    // Redirect virtual base.apk lookup to the untouched original APK (preserving V1/V2/V3 signature)
    if (!guest_package.empty() && !original_apk_path.empty()) {
        AddRedirectRule("/data/app/" + guest_package + "/base.apk", original_apk_path);
        AddRedirectRule("/data/app/" + guest_package + "-1/base.apk", original_apk_path);
    }

    if (g_seccomp_installed) {
        return true;
    }

    // Install SIGSYS handler
    struct sigaction sa{};
    sa.sa_sigaction = SigsysTrapHandler;
    sa.sa_flags = SA_SIGINFO | SA_NODEFER;
    sigemptyset(&sa.sa_mask);
    if (sigaction(SIGSYS, &sa, nullptr) != 0) {
        ZB_LOGE("Failed to register SIGSYS handler: %s", strerror(errno));
        return false;
    }

    uintptr_t raw_start = reinterpret_cast<uintptr_t>(&zerobox_raw_syscall) & ~static_cast<uintptr_t>(3);
    uintptr_t raw_end = (reinterpret_cast<uintptr_t>(&zerobox_raw_syscall_end) + 8) & ~static_cast<uintptr_t>(3);
    uintptr_t tramp_start = raw_start < raw_end ? raw_start : raw_end;
    uintptr_t tramp_end = raw_start < raw_end ? raw_end : (raw_start + 64);
    uint32_t tramp_start_lo = static_cast<uint32_t>(tramp_start & 0xFFFFFFFFULL);
    uint32_t tramp_end_lo = static_cast<uint32_t>(tramp_end & 0xFFFFFFFFULL);
    constexpr uint32_t kBinderWriteReadCmd = 0xc0306201U;

#if defined(__aarch64__)
    struct sock_filter filter[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, instruction_pointer)),
        BPF_JUMP(BPF_JMP | BPF_JGE | BPF_K, tramp_start_lo, 0, 2),
        BPF_JUMP(BPF_JMP | BPF_JGT | BPF_K, tramp_end_lo, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),

        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_openat, 13, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_faccessat, 12, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_newfstatat, 11, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_readlinkat, 10, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_statfs, 9, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_mkdirat, 8, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_unlinkat, 7, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_renameat, 6, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_renameat2, 5, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_fchmodat, 4, 0),
        // Only trap ioctl if request == BINDER_WRITE_READ (0xc0306201); allow GPU/SurfaceFlinger ioctl at kernel speed
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_ioctl, 0, 2),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[1])),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kBinderWriteReadCmd, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP)
    };
#elif defined(__arm__)
    struct sock_filter filter[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, instruction_pointer)),
        BPF_JUMP(BPF_JMP | BPF_JGE | BPF_K, tramp_start_lo, 0, 2),
        BPF_JUMP(BPF_JMP | BPF_JGT | BPF_K, tramp_end_lo, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),

        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_openat, 16, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_open, 15, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_faccessat, 14, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_access, 13, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_fstatat64, 12, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_stat64, 11, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_lstat64, 10, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_readlinkat, 9, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_readlink, 8, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_mkdirat, 7, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_unlinkat, 6, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_renameat, 5, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_fchmodat, 4, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_ioctl, 0, 2),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[1])),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kBinderWriteReadCmd, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP)
    };
#endif

    struct sock_fprog prog{};
    prog.len = static_cast<unsigned short>(sizeof(filter) / sizeof(filter[0]));
    prog.filter = filter;

    prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0);
    if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog, 0, 0) != 0) {
        ZB_LOGW("SECCOMP_MODE_FILTER setup returned errno=%d (%s)", errno, strerror(errno));
        return false;
    }

    g_seccomp_installed = true;
    ZB_LOGI("Seccomp-BPF kernel VFS initialized (zero libc modification, pkg=%s, user=%d)",
            guest_package.c_str(), user_id);
    return true;
}

} // namespace vfs
} // namespace zerobox
