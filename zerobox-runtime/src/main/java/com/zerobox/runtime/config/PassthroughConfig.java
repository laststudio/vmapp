package com.zerobox.runtime.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Per-package / per-userId configurable Passthrough & Stealth policy.
 * Embodies the core design principle: "Hiding > Confrontation" (隐藏永远大于对抗).
 *
 * <p>Instead of forcefully intercepting and faking 40+ Android system services like VirtualApp,
 * ZeroBox defaults to passing 95%+ of system services straight through to the OS and provides
 * fine-grained switches so users can selectively disable interception layers per target app.
 */
public final class PassthroughConfig {

    private boolean enableSeccompVfs = true;
    private boolean enableProcCloak = true;
    private boolean enableBinderFilter = true;
    private boolean passthroughExternalStorage = true;
    private boolean passthroughHostInstalledPkg = true;
    private boolean enableJavaHookEngine = false; // Lazy-load: off by default for zero ART footprint
    private boolean enableNativePluginLoader = true;
    private final List<String> customPassthroughPaths = new ArrayList<>();

    public PassthroughConfig() {
        // Default read-only system passthrough paths
        customPassthroughPaths.add("/system");
        customPassthroughPaths.add("/apex");
        customPassthroughPaths.add("/vendor");
        customPassthroughPaths.add("/product");
    }

    /**
     * Preset: Maximum Stealth & Minimal Intervention (recommended default).
     * Isolates private directory via Seccomp-BPF + /proc cloak, passes external storage and
     * system services straight through, and keeps ART Java Hook engine completely unloaded.
     */
    public static PassthroughConfig createPureStealthPreset() {
        PassthroughConfig cfg = new PassthroughConfig();
        cfg.enableSeccompVfs = true;
        cfg.enableProcCloak = true;
        cfg.enableBinderFilter = true;
        cfg.passthroughExternalStorage = true;
        cfg.passthroughHostInstalledPkg = true;
        cfg.enableJavaHookEngine = false;
        cfg.enableNativePluginLoader = true;
        return cfg;
    }

    /**
     * Preset: Full Hook & Instrumentation Mode (enables Xposed/Java Hook + Native plugins).
     */
    public static PassthroughConfig createFullModulePreset() {
        PassthroughConfig cfg = createPureStealthPreset();
        cfg.enableJavaHookEngine = true;
        return cfg;
    }

    /**
     * Preset: Direct OS Passthrough Mode (for debugging/comparison with zero VFS redirection).
     */
    public static PassthroughConfig createDirectPassthroughPreset() {
        PassthroughConfig cfg = new PassthroughConfig();
        cfg.enableSeccompVfs = false;
        cfg.enableProcCloak = true;
        cfg.enableBinderFilter = false;
        cfg.passthroughExternalStorage = true;
        cfg.passthroughHostInstalledPkg = true;
        cfg.enableJavaHookEngine = false;
        cfg.enableNativePluginLoader = true;
        return cfg;
    }

    public boolean isEnableSeccompVfs() {
        return enableSeccompVfs;
    }

    public void setEnableSeccompVfs(boolean enableSeccompVfs) {
        this.enableSeccompVfs = enableSeccompVfs;
    }

    public boolean isEnableProcCloak() {
        return enableProcCloak;
    }

    public void setEnableProcCloak(boolean enableProcCloak) {
        this.enableProcCloak = enableProcCloak;
    }

    public boolean isEnableBinderFilter() {
        return enableBinderFilter;
    }

    public void setEnableBinderFilter(boolean enableBinderFilter) {
        this.enableBinderFilter = enableBinderFilter;
    }

    public boolean isPassthroughExternalStorage() {
        return passthroughExternalStorage;
    }

    public void setPassthroughExternalStorage(boolean passthroughExternalStorage) {
        this.passthroughExternalStorage = passthroughExternalStorage;
    }

    public boolean isPassthroughHostInstalledPkg() {
        return passthroughHostInstalledPkg;
    }

    public void setPassthroughHostInstalledPkg(boolean passthroughHostInstalledPkg) {
        this.passthroughHostInstalledPkg = passthroughHostInstalledPkg;
    }

    public boolean isEnableJavaHookEngine() {
        return enableJavaHookEngine;
    }

    public void setEnableJavaHookEngine(boolean enableJavaHookEngine) {
        this.enableJavaHookEngine = enableJavaHookEngine;
    }

    public boolean isEnableNativePluginLoader() {
        return enableNativePluginLoader;
    }

    public void setEnableNativePluginLoader(boolean enableNativePluginLoader) {
        this.enableNativePluginLoader = enableNativePluginLoader;
    }

    public List<String> getCustomPassthroughPaths() {
        return Collections.unmodifiableList(customPassthroughPaths);
    }

    public void addCustomPassthroughPath(String pathPrefix) {
        if (pathPrefix != null && !pathPrefix.trim().isEmpty()) {
            String trimmed = pathPrefix.trim();
            if (!customPassthroughPaths.contains(trimmed)) {
                customPassthroughPaths.add(trimmed);
            }
        }
    }

    public void clearCustomPassthroughPaths() {
        customPassthroughPaths.clear();
    }

    /**
     * Zero-dependency key=value serialization.
     */
    public String serialize() {
        StringBuilder sb = new StringBuilder();
        sb.append("enableSeccompVfs=").append(enableSeccompVfs).append('\n');
        sb.append("enableProcCloak=").append(enableProcCloak).append('\n');
        sb.append("enableBinderFilter=").append(enableBinderFilter).append('\n');
        sb.append("passthroughExternalStorage=").append(passthroughExternalStorage).append('\n');
        sb.append("passthroughHostInstalledPkg=").append(passthroughHostInstalledPkg).append('\n');
        sb.append("enableJavaHookEngine=").append(enableJavaHookEngine).append('\n');
        sb.append("enableNativePluginLoader=").append(enableNativePluginLoader).append('\n');
        sb.append("customPassthroughPaths=").append(String.join(";", customPassthroughPaths)).append('\n');
        return sb.toString();
    }

    public static PassthroughConfig deserialize(String text) {
        PassthroughConfig cfg = new PassthroughConfig();
        if (text == null || text.trim().isEmpty()) {
            return cfg;
        }
        String[] lines = text.split("\r?\n");
        for (String line : lines) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).trim();
            String val = line.substring(eq + 1).trim();
            switch (key) {
                case "enableSeccompVfs":
                    cfg.enableSeccompVfs = Boolean.parseBoolean(val);
                    break;
                case "enableProcCloak":
                    cfg.enableProcCloak = Boolean.parseBoolean(val);
                    break;
                case "enableBinderFilter":
                    cfg.enableBinderFilter = Boolean.parseBoolean(val);
                    break;
                case "passthroughExternalStorage":
                    cfg.passthroughExternalStorage = Boolean.parseBoolean(val);
                    break;
                case "passthroughHostInstalledPkg":
                    cfg.passthroughHostInstalledPkg = Boolean.parseBoolean(val);
                    break;
                case "enableJavaHookEngine":
                    cfg.enableJavaHookEngine = Boolean.parseBoolean(val);
                    break;
                case "enableNativePluginLoader":
                    cfg.enableNativePluginLoader = Boolean.parseBoolean(val);
                    break;
                case "customPassthroughPaths":
                    cfg.customPassthroughPaths.clear();
                    if (!val.isEmpty()) {
                        for (String p : val.split(";")) {
                            cfg.addCustomPassthroughPath(p);
                        }
                    }
                    break;
                default:
                    break;
            }
        }
        return cfg;
    }
}
