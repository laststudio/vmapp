package com.zerobox.runtime.vfs;

import com.zerobox.sdk.vfs.VfsRule;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Deterministic prefix-boundary VFS rule evaluator.
 * Evaluates rules in strict order:
 * <ol>
 *   <li>{@link VfsRule.RuleType#PASSTHROUGH} (Hiding &gt; Confrontation)</li>
 *   <li>{@link VfsRule.RuleType#HIDE} (returns {@code null} to signal ENOENT)</li>
 *   <li>{@link VfsRule.RuleType#REDIRECT} (longest matching prefix wins)</li>
 * </ol>
 */
public final class VfsRuleMatcher {

    private final List<VfsRule> passthroughRules = new CopyOnWriteArrayList<>();
    private final List<VfsRule> hideRules = new CopyOnWriteArrayList<>();
    private final List<VfsRule> redirectRules = new CopyOnWriteArrayList<>();

    public void addRule(VfsRule rule) {
        if (rule == null) return;
        switch (rule.getType()) {
            case PASSTHROUGH:
                passthroughRules.add(rule);
                break;
            case HIDE:
                hideRules.add(rule);
                break;
            case REDIRECT:
                redirectRules.add(rule);
                break;
        }
    }

    public List<VfsRule> getAllRules() {
        List<VfsRule> all = new ArrayList<>();
        all.addAll(passthroughRules);
        all.addAll(hideRules);
        all.addAll(redirectRules);
        return all;
    }

    /**
     * Resolves a virtual path to its physical target path, or {@code null} if hidden.
     */
    public String resolve(String virtualPath) {
        if (virtualPath == null || virtualPath.isEmpty()) {
            return virtualPath;
        }
        String norm = VfsRule.normalizePath(virtualPath);

        // 1. Passthrough rules take top priority
        for (VfsRule rule : passthroughRules) {
            if (matchesBoundary(norm, rule.getVirtualPrefix())) {
                return virtualPath;
            }
        }

        // 2. Hidden path rules
        for (VfsRule rule : hideRules) {
            if (matchesBoundary(norm, rule.getVirtualPrefix())) {
                return null;
            }
        }

        // 3. Longest-prefix Redirect rule
        VfsRule bestMatch = null;
        for (VfsRule rule : redirectRules) {
            if (matchesBoundary(norm, rule.getVirtualPrefix())) {
                if (bestMatch == null
                        || rule.getVirtualPrefix().length() > bestMatch.getVirtualPrefix().length()) {
                    bestMatch = rule;
                }
            }
        }

        if (bestMatch != null) {
            String suffix = norm.substring(bestMatch.getVirtualPrefix().length());
            return bestMatch.getTargetPrefix() + suffix;
        }

        return virtualPath;
    }

    public static boolean matchesBoundary(String normalizedPath, String normalizedPrefix) {
        if ("/".equals(normalizedPrefix)) {
            return normalizedPath.startsWith("/");
        }
        if (normalizedPath.length() < normalizedPrefix.length()) {
            return false;
        }
        if (!normalizedPath.startsWith(normalizedPrefix)) {
            return false;
        }
        return normalizedPath.length() == normalizedPrefix.length()
                || normalizedPath.charAt(normalizedPrefix.length()) == '/';
    }
}
