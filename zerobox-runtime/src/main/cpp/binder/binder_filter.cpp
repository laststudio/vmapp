#include "zerobox_core.h"

#include <pthread.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/syscall.h>
#include <linux/android/binder.h>

namespace zerobox {
namespace binder {

namespace {

pthread_mutex_t g_binder_lock = PTHREAD_MUTEX_INITIALIZER;
int g_binder_fds[8] = {-1, -1, -1, -1, -1, -1, -1, -1};
std::string g_host_package;
std::string g_guest_package;
std::vector<uint16_t> g_guest_pkg_utf16;
std::vector<uint16_t> g_host_pkg_utf16;
bool g_passthrough_pms = false;

std::vector<uint16_t> AsciiToUtf16(const std::string& str) {
    std::vector<uint16_t> out(str.size());
    for (size_t i = 0; i < str.size(); ++i) {
        out[i] = static_cast<uint16_t>(static_cast<unsigned char>(str[i]));
    }
    return out;
}

/**
 * Checks whether a Parcel buffer starts with a specific UTF-16 interface token
 * (such as "com.android.internal.app.IAppOpsService" or "android.content.pm.IPackageManager").
 */
bool ParcelContainsAsciiTokenUtf16(const uint8_t* data, size_t size, const char* ascii_token) {
    size_t tok_len = strlen(ascii_token);
    size_t byte_len = tok_len * 2;
    if (size < byte_len + 8) {
        return false;
    }
    size_t scan_limit = size < 192 ? size - byte_len : 192;
    for (size_t offset = 0; offset <= scan_limit; offset += 2) {
        bool matched = true;
        for (size_t i = 0; i < tok_len; ++i) {
            uint16_t ch = static_cast<uint16_t>(data[offset + i * 2]) |
                          (static_cast<uint16_t>(data[offset + i * 2 + 1]) << 8);
            if (ch != static_cast<unsigned char>(ascii_token[i])) {
                matched = false;
                break;
            }
        }
        if (matched) {
            return true;
        }
    }
    return false;
}

} // namespace

void InitializeBinderFilter(
    const std::string& host_package,
    const std::string& guest_package,
    int /*host_uid*/,
    int /*virtual_uid*/,
    bool passthrough_pms
) {
    pthread_mutex_lock(&g_binder_lock);
    g_host_package = host_package;
    g_guest_package = guest_package;
    g_guest_pkg_utf16 = AsciiToUtf16(guest_package);
    g_host_pkg_utf16 = AsciiToUtf16(host_package);
    g_passthrough_pms = passthrough_pms;
    pthread_mutex_unlock(&g_binder_lock);
}

void NotifyFileOpened(const char* path, int fd) {
    if (path == nullptr || fd < 0) return;
    if (strcmp(path, "/dev/binder") == 0 ||
        strcmp(path, "/dev/hwbinder") == 0 ||
        strcmp(path, "/dev/vndbinder") == 0) {
        pthread_mutex_lock(&g_binder_lock);
        for (int& slot : g_binder_fds) {
            if (slot == fd) {
                pthread_mutex_unlock(&g_binder_lock);
                return;
            }
        }
        for (int& slot : g_binder_fds) {
            if (slot < 0) {
                slot = fd;
                break;
            }
        }
        pthread_mutex_unlock(&g_binder_lock);
    }
}

bool IsBinderFd(int fd) {
    if (fd < 0) return false;
    pthread_mutex_lock(&g_binder_lock);
    for (int slot : g_binder_fds) {
        if (slot == fd) {
            pthread_mutex_unlock(&g_binder_lock);
            return true;
        }
    }
    pthread_mutex_unlock(&g_binder_lock);
    return false;
}

long HandleBinderIoctl(int fd, unsigned long request, uintptr_t arg) {
    // 95%+ of Binder transactions pass straight through to the kernel driver
    // without touching Java IActivityManager singletons or creating Proxy classes.
    if (request == BINDER_WRITE_READ && arg != 0) {
        auto* bwr = reinterpret_cast<struct binder_write_read*>(arg);
        if (bwr->write_size > 0 && bwr->write_buffer != 0) {
            const uint8_t* ptr = reinterpret_cast<const uint8_t*>(bwr->write_buffer);
            const uint8_t* end = ptr + bwr->write_size;
            while (ptr + sizeof(uint32_t) <= end) {
                uint32_t cmd = *reinterpret_cast<const uint32_t*>(ptr);
                ptr += sizeof(uint32_t);
                if (cmd == BC_TRANSACTION || cmd == BC_REPLY) {
                    if (ptr + sizeof(struct binder_transaction_data) <= end) {
                        const auto* tr = reinterpret_cast<const struct binder_transaction_data*>(ptr);
                        if (tr->data_size > 16 && tr->data.ptr.buffer != 0) {
                            const auto* parcel = reinterpret_cast<const uint8_t*>(tr->data.ptr.buffer);
                            // Inspect only AppOps / ContentService UID-CallingPackage strict check
                            if (ParcelContainsAsciiTokenUtf16(
                                    parcel,
                                    static_cast<size_t>(tr->data_size),
                                    "com.android.internal.app.IAppOpsService")) {
                                // AppOps strict UID/package verification observed at driver boundary
                            }
                        }
                        ptr += sizeof(struct binder_transaction_data);
                    } else {
                        break;
                    }
                } else {
                    break;
                }
            }
        }
    }

    return zerobox_raw_syscall(
        __NR_ioctl,
        fd,
        static_cast<long>(request),
        static_cast<long>(arg),
        0, 0, 0
    );
}

} // namespace binder
} // namespace zerobox
