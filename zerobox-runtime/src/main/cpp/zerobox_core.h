#ifndef ZEROBOX_CORE_H
#define ZEROBOX_CORE_H

#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include <sys/types.h>
#include <jni.h>
#include <android/log.h>
#include "zerobox_plugin_api.h"

#define ZB_LOG_TAG "ZeroBoxNative"
#define ZB_LOGI(...) __android_log_print(ANDROID_LOG_INFO, ZB_LOG_TAG, __VA_ARGS__)
#define ZB_LOGW(...) __android_log_print(ANDROID_LOG_WARN, ZB_LOG_TAG, __VA_ARGS__)
#define ZB_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, ZB_LOG_TAG, __VA_ARGS__)

#include <string>
#include <vector>

namespace zerobox {

enum class VfsRuleKind {
    PASSTHROUGH = 0,
    HIDE = 1,
    REDIRECT = 2
};

struct NativeVfsRule {
    VfsRuleKind kind;
    std::string virtual_prefix;
    std::string real_prefix;
};

/**
 * Executes a raw Linux kernel syscall from a dedicated instruction address range
 * that is whitelisted in the Seccomp-BPF filter (preventing SIGSYS recursion).
 */
extern "C" long zerobox_raw_syscall(
    long number,
    long arg0,
    long arg1,
    long arg2,
    long arg3,
    long arg4,
    long arg5
);

extern "C" void zerobox_raw_syscall_end();

namespace vfs {
    bool InitializeSeccompVfs(
        const std::string& guest_package,
        int user_id,
        const std::string& isolated_data_dir,
        const std::string& original_apk_path,
        bool enable_proc_cloak,
        bool enable_binder_filter
    );

    void AddPassthroughPrefix(const std::string& prefix);
    void AddRedirectRule(const std::string& virtual_prefix, const std::string& real_prefix);
    void AddHiddenPrefix(const std::string& prefix);
    std::string ResolveVirtualPath(const char* input_path, int* out_blocked_errno);
    int RegisterSyscallListener(int syscall_nr, ZeroBoxSyscallCallback cb, void* user_data);
    bool IsSeccompActive();
}

namespace cloak {
    void InitializeProcCloak(
        const std::string& guest_package,
        const std::string& isolated_data_dir,
        const std::string& original_apk_path
    );
    void AddCloakToken(const std::string& token);
    bool ShouldInterceptProcPath(const char* path);
    int OpenSanitizedProcFile(const char* path, int flags, mode_t mode);
    ssize_t SanitizeReadlink(const char* path, char* buf, size_t bufsiz);
    std::string FilterMapsContent(const std::string& raw_maps);
}

namespace binder {
    void InitializeBinderFilter(
        const std::string& host_package,
        const std::string& guest_package,
        int host_uid,
        int virtual_uid,
        bool passthrough_pms
    );
    void NotifyFileOpened(const char* path, int fd);
    bool IsBinderFd(int fd);
    long HandleBinderIoctl(int fd, unsigned long request, uintptr_t arg);
}

namespace memory {
    int SafeReadMemory(uintptr_t address, void* buffer, size_t length);
    int SafeWriteMemory(uintptr_t address, const void* buffer, size_t length);
    uintptr_t ScanPatternInModule(const char* module_substr, const char* hex_pattern);
    std::vector<uintptr_t> ScanPatternAll(const char* module_substr, const char* hex_pattern, int max_results);
    std::vector<std::string> ReadRealProcMapsLines(const char* filter_substr);
    int EnumerateMemoryRegions(ZeroBoxMemoryRegionVisitor visitor, void* user_data);
    int HookPltSymbol(const char* library_name, const char* symbol_name, void* replacement, void** original);
    bool LoadNativePlugin(
        JavaVM* vm,
        const char* so_path,
        const char* guest_package,
        int user_id,
        const char* data_dir
    );
    const ZeroBoxNativeApi* GetExportedNativeApi();
    uint64_t ComputeExecutableTextHash(const char* lib_name_substr);
}

namespace arthook {
    bool EnsureArtLayoutCalibrated(JNIEnv* env, jclass bridgeClass);
    int InstallArtMethodHook(
        JNIEnv* env,
        jclass bridgeClass,
        jobject reflectedMethod,
        jboolean isStatic,
        const char* shorty
    );
    bool RestoreArtMethodHook(int slotIndex);
    bool TemporarilySwapOriginalBytes(int slotIndex, uint8_t* saved_hooked_bytes);
    void ReapplyHookedBytes(int slotIndex, const uint8_t* saved_hooked_bytes);
}

} // namespace zerobox

#endif // ZEROBOX_CORE_H
