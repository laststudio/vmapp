#ifndef ZEROBOX_PLUGIN_API_H
#define ZEROBOX_PLUGIN_API_H

#include <stdint.h>
#include <stddef.h>
#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

#define ZEROBOX_NATIVE_API_VERSION 0x00010000U

/**
 * Syscall interception context passed to native plugin syscall listeners.
 * Abstracts away AArch64 (arm64-v8a) and ARM32 (armeabi-v7a) register layout differences.
 */
typedef struct ZeroBoxSyscallContext {
    int syscall_nr;
    uintptr_t args[6];
    intptr_t return_value;
    int override_return; /* Set to 1 in pre-callback to skip kernel execution and return return_value */
} ZeroBoxSyscallContext;

/**
 * Callback invoked by the Seccomp-BPF SIGSYS trap handler when a subscribed syscall fires.
 */
typedef void (*ZeroBoxSyscallCallback)(ZeroBoxSyscallContext* ctx, void* user_data);

/**
 * Memory region descriptor returned by enumerate_memory_regions.
 */
typedef struct ZeroBoxMemoryRegionInfo {
    uintptr_t start_address;
    uintptr_t end_address;
    uint32_t permissions; /* Bitmask: 1=R, 2=W, 4=X, 8=Private */
    uint64_t file_offset;
    const char* pathname;
} ZeroBoxMemoryRegionInfo;

typedef int (*ZeroBoxMemoryRegionVisitor)(const ZeroBoxMemoryRegionInfo* region, void* user_data);

/**
 * Function table exposed by the ZeroBox Native Runtime to loaded .so plugins.
 * Designed for stability, zero libc modification, and dual-ABI compatibility.
 */
typedef struct ZeroBoxNativeApi {
    uint32_t version;

    /**
     * Safe memory read using process_vm_readv (never triggers SIGSEGV on invalid address).
     * Returns number of bytes read, or negative errno on failure.
     */
    int (*read_memory)(uintptr_t address, void* buffer, size_t length);

    /**
     * Safe memory write using page-aligned mprotect + process_vm_writev.
     * Returns number of bytes written, or negative errno on failure.
     */
    int (*write_memory)(uintptr_t address, const void* buffer, size_t length);

    /**
     * Scan executable/readable sections of a loaded library (or NULL for all regions)
     * using a wildcard hex pattern such as "48 8B ? ? E8 ?? ?? ?? ??".
     * Returns first matched address, or 0 if not found.
     */
    uintptr_t (*scan_pattern)(const char* module_name_substr, const char* hex_pattern);

    /**
     * Enumerate real (unsanitized) memory regions of the current container process.
     */
    int (*enumerate_memory_regions)(ZeroBoxMemoryRegionVisitor visitor, void* user_data);

    /**
     * Add a dynamic Seccomp VFS path redirection rule (virtual_prefix -> real_prefix).
     */
    int (*add_vfs_redirect)(const char* virtual_prefix, const char* real_prefix);

    /**
     * Add a dynamic Seccomp VFS passthrough prefix (Hiding > Confrontation).
     * Any file operation matching this prefix bypasses redirection directly to the host kernel.
     */
    int (*add_vfs_passthrough)(const char* path_prefix);

    /**
     * Hide a specific substring from /proc/self/maps and /proc/self/smaps when read by the guest APK.
     */
    int (*add_proc_maps_cloak_token)(const char* substring_to_hide);

    /**
     * Register a kernel boundary syscall listener via ZeroBox's Seccomp-BPF dispatcher.
     */
    int (*register_syscall_listener)(int syscall_nr, ZeroBoxSyscallCallback callback, void* user_data);

    /**
     * Hook a PLT/GOT entry in a target ELF library without modifying .text segments.
     */
    int (*plt_hook_symbol)(const char* library_name, const char* symbol_name, void* replacement, void** original);
} ZeroBoxNativeApi;

/**
 * Standard entry point exported by a ZeroBox Native (.so) plugin.
 * Invoked automatically when the plugin is loaded into the isolated linker namespace.
 *
 * @param vm           JavaVM pointer of the container process
 * @param api          Function table provided by ZeroBox runtime
 * @param package_name Virtual package name of the guest app
 * @param user_id      Multi-instance user ID (0, 1, 2, ...)
 * @param data_dir     Real redirected private data directory on host filesystem
 * @return 0 on success, non-zero on error
 */
JNIEXPORT int zerobox_module_init(
    JavaVM* vm,
    const ZeroBoxNativeApi* api,
    const char* package_name,
    int user_id,
    const char* data_dir
);

#ifdef __cplusplus
}
#endif

#endif /* ZEROBOX_PLUGIN_API_H */
