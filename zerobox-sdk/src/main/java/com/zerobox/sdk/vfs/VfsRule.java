package com.zerobox.sdk.vfs;

/**
 * Immutable Virtual File System rule evaluated by the Seccomp-BPF path resolver.
 * Priority order: {@link RuleType#PASSTHROUGH} > {@link RuleType#HIDE} > {@link RuleType#REDIRECT}.
 */
public final class VfsRule {

    public enum RuleType {
        /**
         * Explicitly pass matching paths directly to the real Linux kernel without redirection.
         * Implements the "Hiding > Confrontation" philosophy.
         */
        PASSTHROUGH,

        /**
         * Redirect matching virtual prefix to a target host path prefix.
         */
        REDIRECT,

        /**
         * Pretend matching path does not exist (returns -ENOENT at syscall level).
         */
        HIDE
    }

    private final RuleType type;
    private final String virtualPrefix;
    private final String targetPrefix;

    public VfsRule(RuleType type, String virtualPrefix, String targetPrefix) {
        if (type == null) {
            throw new IllegalArgumentException("RuleType cannot be null");
        }
        this.type = type;
        this.virtualPrefix = normalizePath(virtualPrefix);
        this.targetPrefix = targetPrefix != null ? normalizePath(targetPrefix) : "";
    }

    public static VfsRule passthrough(String pathPrefix) {
        return new VfsRule(RuleType.PASSTHROUGH, pathPrefix, "");
    }

    public static VfsRule redirect(String virtualPrefix, String targetPrefix) {
        return new VfsRule(RuleType.REDIRECT, virtualPrefix, targetPrefix);
    }

    public static VfsRule hide(String pathPrefix) {
        return new VfsRule(RuleType.HIDE, pathPrefix, "");
    }

    public RuleType getType() {
        return type;
    }

    public String getVirtualPrefix() {
        return virtualPrefix;
    }

    public String getTargetPrefix() {
        return targetPrefix;
    }

    public static String normalizePath(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "/";
        }
        String cleaned = raw.replaceAll("/{2,}", "/");
        if (cleaned.length() > 1 && cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned;
    }
}
