package de.robv.android.xposed;

import android.content.pm.ApplicationInfo;
import com.zerobox.sdk.api.ZeroModuleContext;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
import java.util.List;

/**
 * Lazy-loaded adapter invoked by {@code SiblingModuleLoader} only when Xposed compatibility
 * is enabled in {@code PassthroughConfig} for the target application.
 */
public final class XposedModuleAdapter {

    private XposedModuleAdapter() {}

    public static int activateModuleEntries(
            ClassLoader moduleClassLoader,
            String modulePath,
            List<String> entryClassNames,
            ZeroModuleContext context) {
        if (moduleClassLoader == null || entryClassNames == null || context == null) {
            return 0;
        }
        XposedBridge.bindHookEngine(context.getHook());

        LoadPackageParam lpparam = new LoadPackageParam();
        lpparam.packageName = context.getPackageName();
        lpparam.processName = context.getProcessName();
        lpparam.classLoader = context.getGuestClassLoader();
        lpparam.isFirstApplication = true;
        if (context.getGuestContext() != null) {
            lpparam.appInfo = new ApplicationInfo(context.getGuestContext().getApplicationInfo());
            lpparam.appInfo.packageName = context.getPackageName();
            if (context.getVirtualDataDir() != null) {
                lpparam.appInfo.dataDir = context.getVirtualDataDir().getAbsolutePath();
            }
            if (context.getOriginalApkFile() != null) {
                lpparam.appInfo.sourceDir = context.getOriginalApkFile().getAbsolutePath();
                lpparam.appInfo.publicSourceDir = context.getOriginalApkFile().getAbsolutePath();
            }
        }

        int loaded = 0;
        for (String className : entryClassNames) {
            try {
                Class<?> modClass = moduleClassLoader.loadClass(className);
                Object instance = modClass.getDeclaredConstructor().newInstance();
                if (instance instanceof IXposedHookLoadPackage) {
                    ((IXposedHookLoadPackage) instance).handleLoadPackage(lpparam);
                    loaded++;
                }
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
        }
        return loaded;
    }
}
