package com.zerobox.runtime.daemon;

import android.app.Application;
import android.content.Context;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import com.zerobox.runtime.config.PassthroughConfig;
import com.zerobox.runtime.delegate.ZeroInstrumentation;
import com.zerobox.runtime.hook.ZeroHookImpl;
import com.zerobox.runtime.memory.ZeroMemoryImpl;
import com.zerobox.runtime.nativebridge.ZeroNativeBridge;
import com.zerobox.runtime.vfs.ZeroVfsImpl;
import com.zerobox.sdk.api.ZeroModuleContext;
import com.zerobox.sdk.control.IZeroControlSession;
import com.zerobox.sdk.hook.ZeroHook;
import com.zerobox.sdk.memory.MemoryRegion;
import com.zerobox.sdk.memory.ZeroMemory;
import com.zerobox.sdk.vfs.ZeroVfs;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Active runtime session inside a ZeroBox container slot process.
 * Implements {@link ZeroModuleContext} for in-process modules and exposes an
 * {@link IZeroControlSession.Stub} Binder for Shizuku-style cross-process control.
 */
public final class ZeroRuntimeSession implements ZeroModuleContext {

    private static volatile ZeroRuntimeSession sCurrentSession;

    private final Context hostContext;
    private final String packageName;
    private final String processName;
    private final int userId;
    private final File originalApkFile;
    private final File virtualDataDir;
    private final ClassLoader guestClassLoader;
    private final ZeroMemoryImpl memoryEngine;
    private final ZeroVfsImpl vfsEngine;
    private final PassthroughConfig passthroughConfig;
    private final IZeroControlSession.Stub controlAgentStub;

    private ZeroRuntimeSession(
            Context hostContext,
            String packageName,
            String processName,
            int userId,
            File originalApkFile,
            File virtualDataDir,
            ClassLoader guestClassLoader,
            PassthroughConfig passthroughConfig) {
        this.hostContext = hostContext.getApplicationContext();
        this.packageName = packageName;
        this.processName = processName;
        this.userId = userId;
        this.originalApkFile = originalApkFile;
        this.virtualDataDir = virtualDataDir;
        this.guestClassLoader = guestClassLoader;
        this.passthroughConfig = passthroughConfig;
        this.memoryEngine = new ZeroMemoryImpl();
        this.vfsEngine = new ZeroVfsImpl(virtualDataDir, packageName, userId, originalApkFile);

        for (String passthroughPath : passthroughConfig.getCustomPassthroughPaths()) {
            this.vfsEngine.addPassthroughPath(passthroughPath);
        }
        if (passthroughConfig.isPassthroughExternalStorage()) {
            this.vfsEngine.addPassthroughPath("/sdcard");
            this.vfsEngine.addPassthroughPath("/storage/emulated/0");
        }

        this.controlAgentStub = createControlStub();
    }

    public static synchronized ZeroRuntimeSession initializeSlotProcess(
            Context hostContext,
            String guestPackage,
            int userId,
            File originalApkFile,
            File isolatedDataDir,
            PassthroughConfig config) {
        if (sCurrentSession != null && sCurrentSession.packageName.equals(guestPackage)
                && sCurrentSession.userId == userId) {
            return sCurrentSession;
        }

        File libDir = new File(originalApkFile.getParentFile(), "lib");
        ClassLoader guestCl = ZeroInstrumentation.createCleanGuestClassLoader(originalApkFile, libDir);
        ZeroInstrumentation.setActiveGuestEnvironment(
                guestCl,
                hostContext.getApplicationContext() instanceof Application
                        ? (Application) hostContext.getApplicationContext()
                        : null);

        if (ZeroNativeBridge.isNativeAvailable()) {
            ZeroNativeBridge.nativeInitContainer(
                    hostContext.getPackageName(),
                    guestPackage,
                    userId,
                    isolatedDataDir.getAbsolutePath(),
                    originalApkFile.getAbsolutePath(),
                    config.isEnableSeccompVfs(),
                    config.isEnableProcCloak(),
                    config.isEnableBinderFilter(),
                    config.isPassthroughHostInstalledPkg());
        }

        sCurrentSession = new ZeroRuntimeSession(
                hostContext,
                guestPackage,
                guestPackage,
                userId,
                originalApkFile,
                isolatedDataDir,
                guestCl,
                config);
        return sCurrentSession;
    }

    public static synchronized ZeroRuntimeSession getCurrentOrCreateDefault(Context context) {
        if (sCurrentSession != null) {
            return sCurrentSession;
        }
        String defaultPkg = context.getPackageName() + ".sandbox";
        File defaultDataDir = new File(context.getFilesDir(), "zerobox_env/users/0/packages/" + defaultPkg + "/data");
        File hostApk = new File(context.getApplicationInfo().sourceDir);
        return initializeSlotProcess(
                context,
                defaultPkg,
                0,
                hostApk,
                defaultDataDir,
                PassthroughConfig.createPureStealthPreset());
    }

    public IBinder getControlAgentBinder() {
        return controlAgentStub.asBinder();
    }

    public PassthroughConfig getPassthroughConfig() {
        return passthroughConfig;
    }

    @Override
    public String getPackageName() {
        return packageName;
    }

    @Override
    public String getProcessName() {
        return processName;
    }

    @Override
    public int getUserId() {
        return userId;
    }

    @Override
    public File getOriginalApkFile() {
        return originalApkFile;
    }

    @Override
    public File getVirtualDataDir() {
        return virtualDataDir;
    }

    @Override
    public ClassLoader getGuestClassLoader() {
        return guestClassLoader;
    }

    @Override
    public Context getGuestContext() {
        return hostContext;
    }

    @Override
    public Application getGuestApplication() {
        return ZeroInstrumentation.getActiveGuestApplication();
    }

    @Override
    public ZeroMemory getMemory() {
        return memoryEngine;
    }

    @Override
    public ZeroVfs getVfs() {
        return vfsEngine;
    }

    @Override
    public ZeroHook getHook() {
        return ZeroHookImpl.getInstance();
    }

    @Override
    public boolean loadNativePlugin(String soFilePath) {
        if (!passthroughConfig.isEnableNativePluginLoader() || !ZeroNativeBridge.isNativeAvailable()) {
            return false;
        }
        return ZeroNativeBridge.nativeLoadPlugin(
                soFilePath,
                packageName,
                userId,
                virtualDataDir.getAbsolutePath());
    }

    private IZeroControlSession.Stub createControlStub() {
        return new IZeroControlSession.Stub() {
            @Override
            public int getPid() {
                return Process.myPid();
            }

            @Override
            public String getPackageName() {
                return packageName;
            }

            @Override
            public int getUserId() {
                return userId;
            }

            @Override
            public String getProcessName() {
                return processName;
            }

            @Override
            public List<String> getMemoryRegions(String filterSubstring) {
                List<MemoryRegion> regions = memoryEngine.getMemoryRegions(filterSubstring);
                List<String> lines = new ArrayList<>(regions.size());
                for (MemoryRegion region : regions) {
                    lines.add(region.toString());
                }
                return lines;
            }

            @Override
            public byte[] readMemory(long address, int length) {
                if (length <= 0 || length > 1024 * 1024) {
                    return new byte[0];
                }
                return memoryEngine.readByteArray(address, length);
            }

            @Override
            public int writeMemory(long address, byte[] data) {
                if (data == null || data.length == 0) {
                    return 0;
                }
                return memoryEngine.writeBytes(address, data, 0, data.length);
            }

            @Override
            public long[] scanPattern(String moduleFilter, String hexPattern, int maxResults) {
                List<Long> hits = memoryEngine.scanPattern(moduleFilter, hexPattern, maxResults);
                long[] out = new long[hits.size()];
                for (int i = 0; i < hits.size(); i++) {
                    out[i] = hits.get(i);
                }
                return out;
            }

            @Override
            public List<String> listPrivateFiles(String relativeDir) {
                return vfsEngine.listPrivateFiles(relativeDir);
            }

            @Override
            public byte[] readPrivateFile(String relativePath) throws RemoteException {
                try {
                    return vfsEngine.readPrivateFile(relativePath);
                } catch (IOException e) {
                    throw new RemoteException(e.getMessage());
                }
            }

            @Override
            public boolean writePrivateFile(String relativePath, byte[] data, boolean append) {
                try {
                    vfsEngine.writePrivateFile(relativePath, data, append);
                    return true;
                } catch (IOException e) {
                    return false;
                }
            }

            @Override
            public boolean addPassthroughPath(String pathPrefix) {
                vfsEngine.addPassthroughPath(pathPrefix);
                return true;
            }

            @Override
            public boolean addRedirectRule(String virtualPrefix, String realPrefix) {
                vfsEngine.addRedirectRule(virtualPrefix, realPrefix);
                return true;
            }

            @Override
            public boolean loadNativePlugin(String soFilePath) {
                return ZeroRuntimeSession.this.loadNativePlugin(soFilePath);
            }

            @Override
            public String runStealthSelfCheckReport() {
                return StealthSelfChecker.generateFullReport(
                        hostContext,
                        packageName,
                        originalApkFile,
                        guestClassLoader);
            }
        };
    }
}
