#include "zerobox_core.h"

#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <link.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <unistd.h>

namespace zerobox {
namespace memory {

namespace {

struct ParsedRegion {
    uintptr_t start;
    uintptr_t end;
    uint32_t perms;
    uint64_t offset;
    std::string path;
};

bool ParseMapsLineInternal(const std::string& line, ParsedRegion* out) {
    unsigned long long start = 0;
    unsigned long long end = 0;
    unsigned long long offset = 0;
    char perms_str[8] = {0};
    int path_pos = 0;

    int matched = sscanf(
        line.c_str(),
        "%llx-%llx %4s %llx %*x:%*x %*d %n",
        &start,
        &end,
        perms_str,
        &offset,
        &path_pos
    );
    if (matched < 4) {
        return false;
    }
    out->start = static_cast<uintptr_t>(start);
    out->end = static_cast<uintptr_t>(end);
    out->offset = static_cast<uint64_t>(offset);
    out->perms = 0;
    if (perms_str[0] == 'r') out->perms |= 1U;
    if (perms_str[1] == 'w') out->perms |= 2U;
    if (perms_str[2] == 'x') out->perms |= 4U;
    if (perms_str[3] == 'p') out->perms |= 8U;

    if (path_pos > 0 && static_cast<size_t>(path_pos) < line.size()) {
        std::string p = line.substr(static_cast<size_t>(path_pos));
        while (!p.empty() && (p.back() == '\n' || p.back() == '\r' || p.back() == ' ')) {
            p.pop_back();
        }
        out->path = p;
    } else {
        out->path.clear();
    }
    return true;
}

int HexCharToNibble(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

bool CompileHexPattern(
    const char* pattern_str,
    std::vector<uint8_t>* out_bytes,
    std::vector<uint8_t>* out_mask
) {
    if (pattern_str == nullptr) return false;
    out_bytes->clear();
    out_mask->clear();

    const char* p = pattern_str;
    while (*p != '\0') {
        while (*p == ' ' || *p == '\t') ++p;
        if (*p == '\0') break;

        if (*p == '?') {
            ++p;
            if (*p == '?') ++p;
            out_bytes->push_back(0);
            out_mask->push_back(0);
        } else {
            if (p[0] == '0' && (p[1] == 'x' || p[1] == 'X')) {
                p += 2;
            }
            int hi = HexCharToNibble(p[0]);
            int lo = HexCharToNibble(p[1]);
            if (hi < 0 || lo < 0) {
                return false;
            }
            p += 2;
            out_bytes->push_back(static_cast<uint8_t>((hi << 4) | lo));
            out_mask->push_back(1);
        }
    }
    return !out_bytes->empty();
}

struct PltHookSearchCtx {
    const char* lib_substr;
    const char* sym_name;
    void* replacement;
    void** original;
    int hooked_count;
};

#if defined(__LP64__)
typedef Elf64_Ehdr Elf_Ehdr;
typedef Elf64_Phdr Elf_Phdr;
typedef Elf64_Dyn  Elf_Dyn;
typedef Elf64_Sym  Elf_Sym;
typedef Elf64_Rela Elf_Reloc;
#define ELF_R_SYM(info) ELF64_R_SYM(info)
#else
typedef Elf32_Ehdr Elf_Ehdr;
typedef Elf32_Phdr Elf_Phdr;
typedef Elf32_Dyn  Elf_Dyn;
typedef Elf32_Sym  Elf_Sym;
typedef Elf32_Rel  Elf_Reloc;
#define ELF_R_SYM(info) ELF32_R_SYM(info)
#endif

int DlIteratePltCallback(struct dl_phdr_info* info, size_t /*size*/, void* data) {
    auto* ctx = reinterpret_cast<PltHookSearchCtx*>(data);
    if (info->dlpi_name == nullptr || info->dlpi_name[0] == '\0') {
        return 0;
    }
    if (ctx->lib_substr != nullptr && ctx->lib_substr[0] != '\0') {
        if (strstr(info->dlpi_name, ctx->lib_substr) == nullptr) {
            return 0;
        }
    }

    const Elf_Dyn* dyn = nullptr;
    for (int i = 0; i < info->dlpi_phnum; ++i) {
        if (info->dlpi_phdr[i].p_type == PT_DYNAMIC) {
            dyn = reinterpret_cast<const Elf_Dyn*>(info->dlpi_addr + info->dlpi_phdr[i].p_vaddr);
            break;
        }
    }
    if (dyn == nullptr) return 0;

    const Elf_Sym* symtab = nullptr;
    const char* strtab = nullptr;
    const Elf_Reloc* jmprel = nullptr;
    size_t pltrelsz = 0;

    for (; dyn->d_tag != DT_NULL; ++dyn) {
        switch (dyn->d_tag) {
            case DT_SYMTAB:
                symtab = reinterpret_cast<const Elf_Sym*>(dyn->d_un.d_ptr);
                break;
            case DT_STRTAB:
                strtab = reinterpret_cast<const char*>(dyn->d_un.d_ptr);
                break;
            case DT_JMPREL:
                jmprel = reinterpret_cast<const Elf_Reloc*>(dyn->d_un.d_ptr);
                break;
            case DT_PLTRELSZ:
                pltrelsz = static_cast<size_t>(dyn->d_un.d_val);
                break;
            default:
                break;
        }
    }

    if (symtab == nullptr || strtab == nullptr || jmprel == nullptr || pltrelsz == 0) {
        return 0;
    }

    size_t count = pltrelsz / sizeof(Elf_Reloc);
    long page_size = sysconf(_SC_PAGESIZE);

    for (size_t i = 0; i < count; ++i) {
        size_t sym_idx = ELF_R_SYM(jmprel[i].r_info);
        const char* name = strtab + symtab[sym_idx].st_name;
        if (strcmp(name, ctx->sym_name) == 0) {
            void** got_entry = reinterpret_cast<void**>(info->dlpi_addr + jmprel[i].r_offset);
            uintptr_t page_start = reinterpret_cast<uintptr_t>(got_entry) & ~(page_size - 1);
            if (mprotect(reinterpret_cast<void*>(page_start), static_cast<size_t>(page_size),
                         PROT_READ | PROT_WRITE) == 0) {
                if (ctx->original != nullptr && *ctx->original == nullptr) {
                    *ctx->original = *got_entry;
                }
                *got_entry = ctx->replacement;
                __builtin___clear_cache(
                    reinterpret_cast<char*>(got_entry),
                    reinterpret_cast<char*>(got_entry + 1)
                );
                ctx->hooked_count++;
            }
        }
    }
    return 0;
}

int ApiAddVfsRedirect(const char* virtual_prefix, const char* real_prefix) {
    if (virtual_prefix == nullptr || real_prefix == nullptr) return -EINVAL;
    vfs::AddRedirectRule(virtual_prefix, real_prefix);
    return 0;
}

int ApiAddVfsPassthrough(const char* path_prefix) {
    if (path_prefix == nullptr) return -EINVAL;
    vfs::AddPassthroughPrefix(path_prefix);
    return 0;
}

int ApiAddProcCloakToken(const char* token) {
    if (token == nullptr) return -EINVAL;
    cloak::AddCloakToken(token);
    return 0;
}

const ZeroBoxNativeApi kExportedApi = {
    ZEROBOX_NATIVE_API_VERSION,
    &SafeReadMemory,
    &SafeWriteMemory,
    &ScanPatternInModule,
    &EnumerateMemoryRegions,
    &ApiAddVfsRedirect,
    &ApiAddVfsPassthrough,
    &ApiAddProcCloakToken,
    &vfs::RegisterSyscallListener,
    &HookPltSymbol
};

} // namespace

int SafeReadMemory(uintptr_t address, void* buffer, size_t length) {
    if (address == 0 || buffer == nullptr || length == 0) {
        return -EINVAL;
    }
    struct iovec local_iov{};
    local_iov.iov_base = buffer;
    local_iov.iov_len = length;

    struct iovec remote_iov{};
    remote_iov.iov_base = reinterpret_cast<void*>(address);
    remote_iov.iov_len = length;

    long n = zerobox_raw_syscall(
        __NR_process_vm_readv,
        getpid(),
        reinterpret_cast<long>(&local_iov),
        1,
        reinterpret_cast<long>(&remote_iov),
        1,
        0
    );
    if (n < 0) {
        return -errno;
    }
    return static_cast<int>(n);
}

int SafeWriteMemory(uintptr_t address, const void* buffer, size_t length) {
    if (address == 0 || buffer == nullptr || length == 0) {
        return -EINVAL;
    }
    struct iovec local_iov{};
    local_iov.iov_base = const_cast<void*>(buffer);
    local_iov.iov_len = length;

    struct iovec remote_iov{};
    remote_iov.iov_base = reinterpret_cast<void*>(address);
    remote_iov.iov_len = length;

    long n = zerobox_raw_syscall(
        __NR_process_vm_writev,
        getpid(),
        reinterpret_cast<long>(&local_iov),
        1,
        reinterpret_cast<long>(&remote_iov),
        1,
        0
    );
    if (n == static_cast<long>(length)) {
        return static_cast<int>(n);
    }

    // Fallback: page-aligned mprotect if target page is read-only
    long page_size = sysconf(_SC_PAGESIZE);
    uintptr_t page_start = address & ~(static_cast<uintptr_t>(page_size) - 1);
    uintptr_t page_end = (address + length + static_cast<uintptr_t>(page_size) - 1) &
                         ~(static_cast<uintptr_t>(page_size) - 1);
    size_t prot_len = page_end - page_start;

    if (mprotect(reinterpret_cast<void*>(page_start), prot_len,
                 PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        return -errno;
    }
    memcpy(reinterpret_cast<void*>(address), buffer, length);
    __builtin___clear_cache(
        reinterpret_cast<char*>(address),
        reinterpret_cast<char*>(address + length)
    );
    return static_cast<int>(length);
}

std::vector<std::string> ReadRealProcMapsLines(const char* filter_substr) {
    std::vector<std::string> lines;
    int fd = static_cast<int>(zerobox_raw_syscall(
        __NR_openat,
        AT_FDCWD,
        reinterpret_cast<long>("/proc/self/maps"),
        O_RDONLY | O_CLOEXEC,
        0, 0, 0
    ));
    if (fd < 0) {
        return lines;
    }

    std::string raw;
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
        raw.append(buf, static_cast<size_t>(n));
    }
    zerobox_raw_syscall(__NR_close, fd, 0, 0, 0, 0, 0);

    size_t pos = 0;
    while (pos < raw.size()) {
        size_t nl = raw.find('\n', pos);
        size_t end = (nl == std::string::npos) ? raw.size() : nl;
        std::string line = raw.substr(pos, end - pos);
        pos = (nl == std::string::npos) ? raw.size() : nl + 1;
        if (line.empty()) continue;

        if (filter_substr == nullptr || filter_substr[0] == '\0' ||
            line.find(filter_substr) != std::string::npos) {
            lines.push_back(line);
        }
    }
    return lines;
}

int EnumerateMemoryRegions(ZeroBoxMemoryRegionVisitor visitor, void* user_data) {
    if (visitor == nullptr) return -EINVAL;
    std::vector<std::string> lines = ReadRealProcMapsLines(nullptr);
    int visited = 0;
    for (const auto& line : lines) {
        ParsedRegion reg{};
        if (!ParseMapsLineInternal(line, &reg)) continue;
        ZeroBoxMemoryRegionInfo info{};
        info.start_address = reg.start;
        info.end_address = reg.end;
        info.permissions = reg.perms;
        info.file_offset = reg.offset;
        info.pathname = reg.path.c_str();
        visited++;
        if (visitor(&info, user_data) != 0) {
            break;
        }
    }
    return visited;
}

std::vector<uintptr_t> ScanPatternAll(const char* module_substr, const char* hex_pattern, int max_results) {
    std::vector<uintptr_t> results;
    std::vector<uint8_t> pat_bytes;
    std::vector<uint8_t> pat_mask;
    if (!CompileHexPattern(hex_pattern, &pat_bytes, &pat_mask)) {
        return results;
    }

    size_t pat_len = pat_bytes.size();
    std::vector<std::string> lines = ReadRealProcMapsLines(module_substr);
    std::vector<uint8_t> chunk(65536);

    for (const auto& line : lines) {
        ParsedRegion reg{};
        if (!ParseMapsLineInternal(line, &reg)) continue;
        if ((reg.perms & 1U) == 0) continue; // Skip non-readable regions

        uintptr_t cursor = reg.start;
        while (cursor + pat_len <= reg.end) {
            size_t to_read = reg.end - cursor;
            if (to_read > chunk.size()) {
                to_read = chunk.size();
            }
            int n = SafeReadMemory(cursor, chunk.data(), to_read);
            if (n < static_cast<int>(pat_len)) {
                cursor += chunk.size();
                continue;
            }

            size_t limit = static_cast<size_t>(n) - pat_len;
            for (size_t i = 0; i <= limit; ++i) {
                bool matched = true;
                for (size_t j = 0; j < pat_len; ++j) {
                    if (pat_mask[j] && chunk[i + j] != pat_bytes[j]) {
                        matched = false;
                        break;
                    }
                }
                if (matched) {
                    results.push_back(cursor + i);
                    if (max_results > 0 && static_cast<int>(results.size()) >= max_results) {
                        return results;
                    }
                }
            }

            if (to_read == chunk.size() && chunk.size() > pat_len) {
                cursor += chunk.size() - pat_len + 1;
            } else {
                break;
            }
        }
    }
    return results;
}

uintptr_t ScanPatternInModule(const char* module_substr, const char* hex_pattern) {
    std::vector<uintptr_t> matches = ScanPatternAll(module_substr, hex_pattern, 1);
    return matches.empty() ? 0 : matches[0];
}

int HookPltSymbol(const char* library_name, const char* symbol_name, void* replacement, void** original) {
    if (symbol_name == nullptr || replacement == nullptr) {
        return -EINVAL;
    }
    PltHookSearchCtx ctx{};
    ctx.lib_substr = library_name;
    ctx.sym_name = symbol_name;
    ctx.replacement = replacement;
    ctx.original = original;
    ctx.hooked_count = 0;
    dl_iterate_phdr(DlIteratePltCallback, &ctx);
    return ctx.hooked_count > 0 ? 0 : -ENOENT;
}

bool LoadNativePlugin(
    JavaVM* vm,
    const char* so_path,
    const char* guest_package,
    int user_id,
    const char* data_dir
) {
    if (so_path == nullptr || so_path[0] == '\0') {
        return false;
    }
    // Automatically cloak the plugin path in /proc/self/maps before dlopen
    cloak::AddCloakToken(so_path);
    const char* slash = strrchr(so_path, '/');
    if (slash != nullptr && slash[1] != '\0') {
        cloak::AddCloakToken(slash + 1);
    }

    void* handle = dlopen(so_path, RTLD_NOW | RTLD_LOCAL);
    if (handle == nullptr) {
        ZB_LOGE("dlopen failed for plugin %s: %s", so_path, dlerror());
        return false;
    }

    using InitFn = int (*)(JavaVM*, const ZeroBoxNativeApi*, const char*, int, const char*);
    auto init_fn = reinterpret_cast<InitFn>(dlsym(handle, "zerobox_module_init"));
    if (init_fn == nullptr) {
        ZB_LOGW("Plugin %s loaded, no zerobox_module_init exported (assuming constructor init)", so_path);
        return true;
    }

    int rc = init_fn(
        vm,
        &kExportedApi,
        guest_package != nullptr ? guest_package : "",
        user_id,
        data_dir != nullptr ? data_dir : ""
    );
    ZB_LOGI("Plugin %s zerobox_module_init returned %d", so_path, rc);
    return rc == 0;
}

const ZeroBoxNativeApi* GetExportedNativeApi() {
    return &kExportedApi;
}

uint64_t ComputeExecutableTextHash(const char* lib_name_substr) {
    std::vector<std::string> lines = ReadRealProcMapsLines(lib_name_substr);
    uint64_t hash = 14695981039346656037ULL; // FNV-1a 64-bit offset basis
    for (const auto& line : lines) {
        ParsedRegion reg{};
        if (!ParseMapsLineInternal(line, &reg)) continue;
        if ((reg.perms & 4U) == 0) continue; // Only executable (.text) segments

        size_t len = reg.end - reg.start;
        if (len > 4096) len = 4096; // Hash first 4KB of executable entry section
        uint8_t buf[4096];
        int n = SafeReadMemory(reg.start, buf, len);
        for (int i = 0; i < n; ++i) {
            hash ^= static_cast<uint64_t>(buf[i]);
            hash *= 1099511628211ULL;
        }
        break;
    }
    return hash;
}

} // namespace memory
} // namespace zerobox
