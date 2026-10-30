#include "zerobox_plugin_api.h"
#include <android/log.h>
#include <string.h>

#define PLUGIN_TAG "ZeroBoxSampleNativePlugin"

namespace {

int OnRegionVisited(const ZeroBoxMemoryRegionInfo* region, void* user_data) {
    int* count = reinterpret_cast<int*>(user_data);
    if (region != nullptr && region->pathname != nullptr && strstr(region->pathname, "libc.so") != nullptr) {
        (*count)++;
    }
    return 0; // Continue enumeration
}

} // namespace

extern "C" JNIEXPORT int zerobox_module_init(
    JavaVM* /*vm*/,
    const ZeroBoxNativeApi* api,
    const char* package_name,
    int user_id,
    const char* data_dir
) {
    if (api == nullptr || api->version < ZEROBOX_NATIVE_API_VERSION) {
        return -1;
    }

    // 1. Register a passthrough prefix from native plugin
    api->add_vfs_passthrough("/system/fonts");

    // 2. Enumerate real memory regions and verify ELF header reading via SafeReadMemory
    int libc_segments = 0;
    api->enumerate_memory_regions(&OnRegionVisited, &libc_segments);

    // 3. Scan for ELF magic "7F 45 4C 46" in libc.so
    uintptr_t elf_base = api->scan_pattern("libc.so", "7F 45 4C 46");
    uint8_t magic[4] = {0};
    if (elf_base != 0) {
        api->read_memory(elf_base, magic, sizeof(magic));
    }

    __android_log_print(
        ANDROID_LOG_INFO,
        PLUGIN_TAG,
        "Native plugin initialized: pkg=%s user=%d data=%s libc_segs=%d elf_base=0x%zx",
        package_name ? package_name : "",
        user_id,
        data_dir ? data_dir : "",
        libc_segments,
        static_cast<size_t>(elf_base)
    );
    return 0;
}
