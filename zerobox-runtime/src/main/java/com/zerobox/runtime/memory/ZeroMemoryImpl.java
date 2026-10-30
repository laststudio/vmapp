package com.zerobox.runtime.memory;

import com.zerobox.runtime.nativebridge.ZeroNativeBridge;
import com.zerobox.sdk.memory.MemoryPatternScanner;
import com.zerobox.sdk.memory.MemoryRegion;
import com.zerobox.sdk.memory.ZeroMemory;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Runtime implementation of {@link ZeroMemory}.
 * Uses {@link ZeroNativeBridge} {@code process_vm_readv}/{@code process_vm_writev} for crash-safe
 * direct memory access and pattern scanning.
 */
public final class ZeroMemoryImpl implements ZeroMemory {

    @Override
    public List<MemoryRegion> getMemoryRegions(String filterSubstring) {
        String filter = filterSubstring != null ? filterSubstring : "";
        List<MemoryRegion> regions = new ArrayList<>();

        if (ZeroNativeBridge.isNativeAvailable()) {
            String[] lines = ZeroNativeBridge.nativeGetRealMemoryRegions(filter);
            if (lines != null) {
                for (String line : lines) {
                    MemoryRegion region = MemoryRegion.parseMapsLine(line);
                    if (region != null) {
                        regions.add(region);
                    }
                }
            }
            return regions;
        }

        // Fallback for non-JNI environments
        File mapsFile = new File("/proc/self/maps");
        if (!mapsFile.exists()) {
            return Collections.emptyList();
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(mapsFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (filter.isEmpty() || line.contains(filter)) {
                    MemoryRegion region = MemoryRegion.parseMapsLine(line);
                    if (region != null) {
                        regions.add(region);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return regions;
    }

    @Override
    public int readBytes(long address, byte[] buffer, int offset, int length) {
        if (buffer == null || offset < 0 || length <= 0 || offset + length > buffer.length) {
            return -22; // -EINVAL
        }
        if (!ZeroNativeBridge.isNativeAvailable()) {
            return -38; // -ENOSYS
        }
        return ZeroNativeBridge.nativeReadMemory(address, buffer, offset, length);
    }

    @Override
    public int writeBytes(long address, byte[] buffer, int offset, int length) {
        if (buffer == null || offset < 0 || length <= 0 || offset + length > buffer.length) {
            return -22; // -EINVAL
        }
        if (!ZeroNativeBridge.isNativeAvailable()) {
            return -38; // -ENOSYS
        }
        return ZeroNativeBridge.nativeWriteMemory(address, buffer, offset, length);
    }

    @Override
    public List<Long> scanPattern(String moduleFilter, String hexPattern, int maxResults) {
        // Validate pattern syntax early via MemoryPatternScanner
        MemoryPatternScanner.compile(hexPattern);
        if (!ZeroNativeBridge.isNativeAvailable()) {
            return Collections.emptyList();
        }
        long[] hits = ZeroNativeBridge.nativeScanPattern(
                moduleFilter != null ? moduleFilter : "",
                hexPattern,
                maxResults);
        if (hits == null || hits.length == 0) {
            return Collections.emptyList();
        }
        List<Long> out = new ArrayList<>(hits.length);
        for (long addr : hits) {
            out.add(addr);
        }
        return out;
    }
}
