#include "zerobox_core.h"

#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

namespace zerobox {
namespace cloak {

namespace {

pthread_rwlock_t g_cloak_lock = PTHREAD_RWLOCK_INITIALIZER;
std::string g_guest_package;
std::string g_isolated_data_dir;
std::string g_original_apk_path;
std::vector<std::string> g_cloak_tokens = {
    "libzerobox_core.so",
    "libzerobox",
    "zerobox-runtime",
    "zerobox-xposed",
    "XposedBridge",
    "lsposed",
    "edxposed",
    "frida-agent",
    "substrate"
};

int CreateAnonymousMemFd(const char* name, const char* data, size_t len) {
    int mem_fd = -1;
#if defined(__NR_memfd_create)
    mem_fd = static_cast<int>(zerobox_raw_syscall(
        __NR_memfd_create,
        reinterpret_cast<long>(name),
        1 /* MFD_CLOEXEC */,
        0, 0, 0, 0
    ));
#endif
    if (mem_fd < 0 && !g_isolated_data_dir.empty()) {
        std::string tmp_path = g_isolated_data_dir + "/.zb_proc_tmp";
        mem_fd = static_cast<int>(zerobox_raw_syscall(
            __NR_openat,
            AT_FDCWD,
            reinterpret_cast<long>(tmp_path.c_str()),
            O_CREAT | O_RDWR | O_TRUNC | O_CLOEXEC,
            0600, 0, 0
        ));
        if (mem_fd >= 0) {
#if defined(__NR_unlinkat)
            zerobox_raw_syscall(__NR_unlinkat, AT_FDCWD, reinterpret_cast<long>(tmp_path.c_str()), 0, 0, 0, 0);
#endif
        }
    }
    if (mem_fd < 0) {
        return -1;
    }

    size_t written = 0;
    while (written < len) {
        long n = zerobox_raw_syscall(
            __NR_write,
            mem_fd,
            reinterpret_cast<long>(data + written),
            static_cast<long>(len - written),
            0, 0, 0
        );
        if (n <= 0) break;
        written += static_cast<size_t>(n);
    }
    zerobox_raw_syscall(__NR_lseek, mem_fd, 0, SEEK_SET, 0, 0, 0);
    return mem_fd;
}

std::string ReadWholeFileRaw(const char* path) {
    int fd = static_cast<int>(zerobox_raw_syscall(
        __NR_openat,
        AT_FDCWD,
        reinterpret_cast<long>(path),
        O_RDONLY | O_CLOEXEC,
        0, 0, 0
    ));
    if (fd < 0) {
        return {};
    }
    std::string content;
    char buf[4096];
    while (true) {
        long n = zerobox_raw_syscall(
            __NR_read,
            fd,
            reinterpret_cast<long>(buf),
            sizeof(buf),
            0, 0, 0
        );
        if (n <= 0) break;
        content.append(buf, static_cast<size_t>(n));
    }
    zerobox_raw_syscall(__NR_close, fd, 0, 0, 0, 0, 0);
    return content;
}

bool EndsWith(const std::string& str, const char* suffix) {
    size_t s_len = strlen(suffix);
    return str.size() >= s_len && str.compare(str.size() - s_len, s_len, suffix) == 0;
}

} // namespace

void InitializeProcCloak(
    const std::string& guest_package,
    const std::string& isolated_data_dir,
    const std::string& original_apk_path
) {
    pthread_rwlock_wrlock(&g_cloak_lock);
    g_guest_package = guest_package;
    g_isolated_data_dir = isolated_data_dir;
    g_original_apk_path = original_apk_path;
    pthread_rwlock_unlock(&g_cloak_lock);
}

void AddCloakToken(const std::string& token) {
    if (token.empty()) return;
    pthread_rwlock_wrlock(&g_cloak_lock);
    g_cloak_tokens.push_back(token);
    pthread_rwlock_unlock(&g_cloak_lock);
}

std::string FilterMapsContent(const std::string& raw_maps) {
    pthread_rwlock_rdlock(&g_cloak_lock);
    std::vector<std::string> tokens = g_cloak_tokens;
    std::string orig_apk = g_original_apk_path;
    std::string guest_pkg = g_guest_package;
    pthread_rwlock_unlock(&g_cloak_lock);

    std::string sanitized;
    sanitized.reserve(raw_maps.size());

    size_t pos = 0;
    while (pos < raw_maps.size()) {
        size_t next_nl = raw_maps.find('\n', pos);
        size_t line_end = (next_nl == std::string::npos) ? raw_maps.size() : next_nl + 1;
        std::string line = raw_maps.substr(pos, line_end - pos);
        pos = line_end;

        bool hide_line = false;
        for (const auto& tok : tokens) {
            if (!tok.empty() && line.find(tok) != std::string::npos) {
                hide_line = true;
                break;
            }
        }
        if (hide_line) {
            continue;
        }

        // Rewrite host container APK path to standard /data/app/<guest_pkg>/base.apk
        if (!orig_apk.empty() && !guest_pkg.empty()) {
            size_t apk_pos = line.find(orig_apk);
            if (apk_pos != std::string::npos) {
                std::string virtual_apk = "/data/app/" + guest_pkg + "-1/base.apk";
                line.replace(apk_pos, orig_apk.size(), virtual_apk);
            }
        }
        sanitized.append(line);
    }
    return sanitized;
}

std::string FilterStatusContent(const std::string& raw_status) {
    std::string sanitized;
    sanitized.reserve(raw_status.size());

    size_t pos = 0;
    while (pos < raw_status.size()) {
        size_t next_nl = raw_status.find('\n', pos);
        size_t line_end = (next_nl == std::string::npos) ? raw_status.size() : next_nl + 1;
        std::string line = raw_status.substr(pos, line_end - pos);
        pos = line_end;

        if (line.compare(0, 8, "Seccomp:") == 0) {
            sanitized.append("Seccomp:\t0\n");
        } else if (line.compare(0, 16, "Seccomp_filters:") == 0) {
            sanitized.append("Seccomp_filters:\t0\n");
        } else if (line.compare(0, 10, "TracerPid:") == 0) {
            sanitized.append("TracerPid:\t0\n");
        } else {
            sanitized.append(line);
        }
    }
    return sanitized;
}

bool ShouldInterceptProcPath(const char* path) {
    if (path == nullptr || strncmp(path, "/proc/", 6) != 0) {
        return false;
    }
    std::string p(path);
    return EndsWith(p, "/maps") ||
           EndsWith(p, "/smaps") ||
           EndsWith(p, "/cmdline") ||
           EndsWith(p, "/status");
}

int OpenSanitizedProcFile(const char* path, int /*flags*/, mode_t /*mode*/) {
    std::string p(path);
    if (EndsWith(p, "/cmdline")) {
        pthread_rwlock_rdlock(&g_cloak_lock);
        std::string cmdline = g_guest_package;
        pthread_rwlock_unlock(&g_cloak_lock);
        if (!cmdline.empty()) {
            cmdline.push_back('\0');
            return CreateAnonymousMemFd("cmdline", cmdline.data(), cmdline.size());
        }
        return -1;
    }

    if (EndsWith(p, "/maps") || EndsWith(p, "/smaps")) {
        std::string raw = ReadWholeFileRaw(path);
        if (raw.empty()) {
            return -1;
        }
        std::string clean = FilterMapsContent(raw);
        return CreateAnonymousMemFd("maps", clean.data(), clean.size());
    }

    if (EndsWith(p, "/status")) {
        std::string raw = ReadWholeFileRaw(path);
        if (raw.empty()) {
            return -1;
        }
        std::string clean = FilterStatusContent(raw);
        return CreateAnonymousMemFd("status", clean.data(), clean.size());
    }
    return -1;
}

ssize_t SanitizeReadlink(const char* path, char* buf, size_t bufsiz) {
    if (path == nullptr || buf == nullptr || bufsiz == 0) {
        return -1;
    }
    if (strncmp(path, "/proc/", 6) != 0) {
        return -1;
    }

    char real_target[1024] = {0};
    long n = zerobox_raw_syscall(
        __NR_readlinkat,
        AT_FDCWD,
        reinterpret_cast<long>(path),
        reinterpret_cast<long>(real_target),
        sizeof(real_target) - 1,
        0, 0
    );
    if (n <= 0) {
        return -1;
    }
    real_target[n] = '\0';
    std::string target_str(real_target);

    pthread_rwlock_rdlock(&g_cloak_lock);
    std::string data_dir = g_isolated_data_dir;
    std::string guest_pkg = g_guest_package;
    std::string orig_apk = g_original_apk_path;
    pthread_rwlock_unlock(&g_cloak_lock);

    if (!data_dir.empty() && !guest_pkg.empty() && target_str.find(data_dir) == 0) {
        std::string virtual_data = "/data/user/0/" + guest_pkg + target_str.substr(data_dir.size());
        size_t copy_len = virtual_data.size() < bufsiz ? virtual_data.size() : bufsiz;
        memcpy(buf, virtual_data.data(), copy_len);
        return static_cast<ssize_t>(copy_len);
    }

    if (!orig_apk.empty() && !guest_pkg.empty() && target_str == orig_apk) {
        std::string virtual_apk = "/data/app/" + guest_pkg + "-1/base.apk";
        size_t copy_len = virtual_apk.size() < bufsiz ? virtual_apk.size() : bufsiz;
        memcpy(buf, virtual_apk.data(), copy_len);
        return static_cast<ssize_t>(copy_len);
    }

    size_t copy_len = static_cast<size_t>(n) < bufsiz ? static_cast<size_t>(n) : bufsiz;
    memcpy(buf, real_target, copy_len);
    return static_cast<ssize_t>(copy_len);
}

} // namespace cloak
} // namespace zerobox
