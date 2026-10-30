package com.zerobox.sdk.memory;

import java.util.Locale;

/**
 * Immutable descriptor of a virtual memory region parsed from {@code /proc/self/maps}
 * or {@code /proc/<pid>/maps}.
 */
public final class MemoryRegion {
    private final long startAddress;
    private final long endAddress;
    private final String permissions;
    private final long offset;
    private final String device;
    private final long inode;
    private final String path;

    public MemoryRegion(
            long startAddress,
            long endAddress,
            String permissions,
            long offset,
            String device,
            long inode,
            String path) {
        this.startAddress = startAddress;
        this.endAddress = endAddress;
        this.permissions = permissions != null ? permissions : "----";
        this.offset = offset;
        this.device = device != null ? device : "00:00";
        this.inode = inode;
        this.path = path != null ? path : "";
    }

    public long getStartAddress() {
        return startAddress;
    }

    public long getEndAddress() {
        return endAddress;
    }

    public long getSize() {
        return Math.max(0L, endAddress - startAddress);
    }

    public String getPermissions() {
        return permissions;
    }

    public long getOffset() {
        return offset;
    }

    public String getDevice() {
        return device;
    }

    public long getInode() {
        return inode;
    }

    public String getPath() {
        return path;
    }

    public boolean isReadable() {
        return permissions.length() > 0 && permissions.charAt(0) == 'r';
    }

    public boolean isWritable() {
        return permissions.length() > 1 && permissions.charAt(1) == 'w';
    }

    public boolean isExecutable() {
        return permissions.length() > 2 && permissions.charAt(2) == 'x';
    }

    public boolean isPrivate() {
        return permissions.length() > 3 && permissions.charAt(3) == 'p';
    }

    /**
     * Parses a single line from {@code /proc/<pid>/maps}.
     * Example: {@code 7b2d400000-7b2d445000 r-xp 00000000 fd:00 12345 /apex/com.android.runtime/lib64/bionic/libc.so}
     */
    public static MemoryRegion parseMapsLine(String line) {
        if (line == null) {
            return null;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String[] tokens = trimmed.split("\\s+", 6);
        if (tokens.length < 5) {
            return null;
        }
        String[] range = tokens[0].split("-");
        if (range.length != 2) {
            return null;
        }
        try {
            long start = Long.parseUnsignedLong(range[0], 16);
            long end = Long.parseUnsignedLong(range[1], 16);
            String perms = tokens[1];
            long offset = Long.parseUnsignedLong(tokens[2], 16);
            String dev = tokens[3];
            long inode = Long.parseLong(tokens[4], 10);
            String pathname = tokens.length >= 6 ? tokens[5].trim() : "";
            return new MemoryRegion(start, end, perms, offset, dev, inode, pathname);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @Override
    public String toString() {
        return String.format(
                Locale.US,
                "%012x-%012x %s %08x %s %d %s",
                startAddress,
                endAddress,
                permissions,
                offset,
                device,
                inode,
                path);
    }
}
