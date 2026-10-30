package com.zerobox.sdk.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * High-speed wildcard byte pattern compiler and matcher for memory scanning.
 * Supports IDA/x64dbg style hex strings such as {@code "48 8B ? ? E8 ?? ?? ?? ??"}.
 */
public final class MemoryPatternScanner {

    public static final class CompiledPattern {
        private final byte[] bytes;
        private final boolean[] mask; // true = exact byte match required, false = wildcard '?'

        CompiledPattern(byte[] bytes, boolean[] mask) {
            this.bytes = bytes;
            this.mask = mask;
        }

        public int length() {
            return bytes.length;
        }

        public byte[] getBytes() {
            return bytes.clone();
        }

        public boolean[] getMask() {
            return mask.clone();
        }
    }

    private MemoryPatternScanner() {}

    /**
     * Compiles a hex pattern string with optional {@code ?} or {@code ??} wildcards.
     */
    public static CompiledPattern compile(String pattern) {
        if (pattern == null || pattern.trim().isEmpty()) {
            throw new IllegalArgumentException("Pattern must not be empty");
        }
        String[] tokens = pattern.trim().split("\\s+");
        byte[] bytes = new byte[tokens.length];
        boolean[] mask = new boolean[tokens.length];

        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            if ("?".equals(token) || "??".equals(token) || "**".equals(token)) {
                bytes[i] = 0;
                mask[i] = false;
            } else {
                if (token.startsWith("0x") || token.startsWith("0X")) {
                    token = token.substring(2);
                }
                if (token.length() != 2) {
                    throw new IllegalArgumentException("Invalid hex byte token: " + tokens[i]);
                }
                int val = Integer.parseInt(token, 16);
                bytes[i] = (byte) (val & 0xFF);
                mask[i] = true;
            }
        }
        return new CompiledPattern(bytes, mask);
    }

    /**
     * Scans a byte buffer for all occurrences of the compiled wildcard pattern.
     *
     * @param buffer      Buffer to scan
     * @param validLength Number of valid bytes in {@code buffer}
     * @param baseAddress Virtual base address corresponding to {@code buffer[0]}
     * @param pattern     Compiled pattern
     * @param maxResults  Maximum matches to return (0 or negative for unlimited)
     * @return List of matched virtual addresses
     */
    public static List<Long> scanBuffer(
            byte[] buffer,
            int validLength,
            long baseAddress,
            CompiledPattern pattern,
            int maxResults) {
        if (buffer == null || pattern == null) {
            return Collections.emptyList();
        }
        int patLen = pattern.bytes.length;
        int limit = Math.min(buffer.length, validLength) - patLen;
        if (patLen == 0 || limit < 0) {
            return Collections.emptyList();
        }

        List<Long> matches = new ArrayList<>();
        byte[] patBytes = pattern.bytes;
        boolean[] patMask = pattern.mask;

        // Locate the first non-wildcard anchor byte for fast skipping
        int anchorIndex = -1;
        byte anchorByte = 0;
        for (int i = 0; i < patLen; i++) {
            if (patMask[i]) {
                anchorIndex = i;
                anchorByte = patBytes[i];
                break;
            }
        }

        for (int offset = 0; offset <= limit; offset++) {
            if (anchorIndex >= 0 && buffer[offset + anchorIndex] != anchorByte) {
                continue;
            }
            boolean matched = true;
            for (int j = 0; j < patLen; j++) {
                if (patMask[j] && buffer[offset + j] != patBytes[j]) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                matches.add(baseAddress + offset);
                if (maxResults > 0 && matches.size() >= maxResults) {
                    break;
                }
            }
        }
        return matches;
    }
}
