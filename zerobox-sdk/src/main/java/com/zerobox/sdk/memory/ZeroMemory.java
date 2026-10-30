package com.zerobox.sdk.memory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Safe in-process and cross-process memory inspection, modification, and signature scanning API.
 * Backed by {@code process_vm_readv} / {@code process_vm_writev} and controlled page permission
 * toggling in native code so invalid addresses return an error code rather than crashing the process.
 */
public interface ZeroMemory {

    /**
     * Lists real virtual memory regions (bypassing the /proc/self/maps cloak used against guest detection).
     *
     * @param filterSubstring Optional substring filter (e.g. {@code "libil2cpp.so"} or {@code "libc.so"}), or empty/null for all.
     */
    List<MemoryRegion> getMemoryRegions(String filterSubstring);

    /**
     * Safely reads raw bytes from the specified virtual address.
     *
     * @return Number of bytes actually read, or negative errno on failure.
     */
    int readBytes(long address, byte[] buffer, int offset, int length);

    /**
     * Safely writes raw bytes to the specified virtual address.
     * Automatically handles temporary page protection adjustments if needed.
     *
     * @return Number of bytes actually written, or negative errno on failure.
     */
    int writeBytes(long address, byte[] buffer, int offset, int length);

    /**
     * Scans matching memory regions for a wildcard hex pattern (e.g. {@code "48 8B ? ? E8 ?? ?? ?? ??"}).
     *
     * @param moduleFilter Optional library/region name substring (null or empty for all readable regions)
     * @param hexPattern   Space-separated hex byte pattern with {@code ?} or {@code ??} wildcards
     * @param maxResults   Maximum number of addresses to return (0 for unlimited)
     */
    List<Long> scanPattern(String moduleFilter, String hexPattern, int maxResults);

    default byte[] readByteArray(long address, int length) {
        byte[] buf = new byte[length];
        int n = readBytes(address, buf, 0, length);
        if (n <= 0) {
            return new byte[0];
        }
        if (n == length) {
            return buf;
        }
        byte[] trimmed = new byte[n];
        System.arraycopy(buf, 0, trimmed, 0, n);
        return trimmed;
    }

    default int readInt(long address) {
        byte[] buf = new byte[4];
        if (readBytes(address, buf, 0, 4) != 4) {
            throw new IllegalStateException("Failed to read int32 at 0x" + Long.toHexString(address));
        }
        return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    default long readLong(long address) {
        byte[] buf = new byte[8];
        if (readBytes(address, buf, 0, 8) != 8) {
            throw new IllegalStateException("Failed to read int64 at 0x" + Long.toHexString(address));
        }
        return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    default float readFloat(long address) {
        byte[] buf = new byte[4];
        if (readBytes(address, buf, 0, 4) != 4) {
            throw new IllegalStateException("Failed to read float at 0x" + Long.toHexString(address));
        }
        return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).getFloat();
    }

    default double readDouble(long address) {
        byte[] buf = new byte[8];
        if (readBytes(address, buf, 0, 8) != 8) {
            throw new IllegalStateException("Failed to read double at 0x" + Long.toHexString(address));
        }
        return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).getDouble();
    }

    default String readCString(long address, int maxLength) {
        byte[] buf = readByteArray(address, Math.max(1, maxLength));
        int len = 0;
        while (len < buf.length && buf[len] != 0) {
            len++;
        }
        return new String(buf, 0, len, StandardCharsets.UTF_8);
    }

    default boolean writeInt(long address, int value) {
        byte[] buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
        return writeBytes(address, buf, 0, 4) == 4;
    }

    default boolean writeLong(long address, long value) {
        byte[] buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array();
        return writeBytes(address, buf, 0, 8) == 8;
    }

    default boolean writeFloat(long address, float value) {
        byte[] buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array();
        return writeBytes(address, buf, 0, 4) == 4;
    }
}
