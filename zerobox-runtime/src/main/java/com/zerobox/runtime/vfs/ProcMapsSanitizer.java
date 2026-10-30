package com.zerobox.runtime.vfs;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Sanitizes {@code /proc/self/maps} and {@code /proc/self/fd} content so that guest applications
 * inspecting their own memory mappings cannot detect ZeroBox, Xposed, or injected native plugins.
 */
public final class ProcMapsSanitizer {

    private final List<String> hiddenTokens = new CopyOnWriteArrayList<>(Arrays.asList(
            "libzerobox_core.so",
            "libzerobox",
            "zerobox-runtime",
            "zerobox-xposed",
            "XposedBridge",
            "lsposed",
            "edxposed",
            "frida-agent",
            "substrate"
    ));

    private volatile String hostOriginalApkPath = "";
    private volatile String guestPackageName = "";

    public void configure(String guestPackageName, String hostOriginalApkPath) {
        this.guestPackageName = guestPackageName != null ? guestPackageName : "";
        this.hostOriginalApkPath = hostOriginalApkPath != null ? hostOriginalApkPath : "";
    }

    public void addCloakToken(String token) {
        if (token != null && !token.trim().isEmpty()) {
            hiddenTokens.add(token.trim());
        }
    }

    public String sanitizeMapsContent(String rawMaps) {
        if (rawMaps == null || rawMaps.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(rawMaps.length());
        String[] lines = rawMaps.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty() && i == lines.length - 1) {
                break;
            }
            if (shouldHideLine(line)) {
                continue;
            }
            if (!hostOriginalApkPath.isEmpty() && !guestPackageName.isEmpty()
                    && line.contains(hostOriginalApkPath)) {
                line = line.replace(
                        hostOriginalApkPath,
                        "/data/app/" + guestPackageName + "-1/base.apk");
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }

    public String sanitizeStatusContent(String rawStatus) {
        if (rawStatus == null || rawStatus.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(rawStatus.length());
        String[] lines = rawStatus.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty() && i == lines.length - 1) {
                break;
            }
            if (line.startsWith("Seccomp:")) {
                out.append("Seccomp:\t0\n");
            } else if (line.startsWith("Seccomp_filters:")) {
                out.append("Seccomp_filters:\t0\n");
            } else if (line.startsWith("TracerPid:")) {
                out.append("TracerPid:\t0\n");
            } else {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    private boolean shouldHideLine(String line) {
        for (String token : hiddenTokens) {
            if (!token.isEmpty() && line.contains(token)) {
                return true;
            }
        }
        return false;
    }
}
