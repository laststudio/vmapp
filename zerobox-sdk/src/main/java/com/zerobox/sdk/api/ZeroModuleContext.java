package com.zerobox.sdk.api;

import android.app.Application;
import android.content.Context;
import com.zerobox.sdk.hook.ZeroHook;
import com.zerobox.sdk.memory.ZeroMemory;
import com.zerobox.sdk.vfs.ZeroVfs;
import java.io.File;

/**
 * Execution context passed to {@link ZeroModule#onLoad(ZeroModuleContext)}.
 * Provides direct, zero-reflection access to the guest application's runtime environment
 * as well as ZeroBox's Memory, Virtual File System, and Stealth Hook engines.
 */
public interface ZeroModuleContext {
    /**
     * Virtual package name of the guest application currently running in this container process.
     */
    String getPackageName();

    /**
     * Logical process name (e.g., {@code com.example.target} or {@code com.example.target:push}).
     */
    String getProcessName();

    /**
     * Multi-instance user/clone ID (0 for primary instance, 1, 2, ... for clones).
     */
    int getUserId();

    /**
     * Unmodified original APK file on disk (preserves original V1/V2/V3 signing block).
     */
    File getOriginalApkFile();

    /**
     * Isolated private data directory on the host file system mapped to {@code /data/user/0/<pkg>}.
     */
    File getVirtualDataDir();

    /**
     * The guest application's {@link ClassLoader}.
     * Note: The module itself runs in a sibling isolated ClassLoader so the guest app
     * cannot discover module classes via {@code Class.forName}, while the module can load
     * any guest class via this ClassLoader.
     */
    ClassLoader getGuestClassLoader();

    /**
     * Guest Application Context (available during and after attachBaseContext).
     */
    Context getGuestContext();

    /**
     * Guest Application instance (if already instantiated).
     */
    Application getGuestApplication();

    /**
     * Direct in-process memory read/write and wildcard pattern scanning engine.
     */
    ZeroMemory getMemory();

    /**
     * Virtual file system and private directory access/passthrough engine.
     */
    ZeroVfs getVfs();

    /**
     * Stealth Java method and kernel syscall hook engine.
     */
    ZeroHook getHook();

    /**
     * Dynamically load a pure native (.so) plugin into an isolated linker namespace
     * and invoke its {@code zerobox_module_init} entry point.
     */
    boolean loadNativePlugin(String soFilePath);
}
