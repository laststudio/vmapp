package com.zerobox.runtime.delegate;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AppComponentFactory;
import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import com.zerobox.runtime.config.PassthroughConfig;
import com.zerobox.runtime.daemon.ZeroPackageEngine;
import java.io.File;

/**
 * Clean-room component instantiation delegate built on Android's official {@link AppComponentFactory}
 * (API 28+).
 *
 * <p>Unlike VirtualApp's reflection-heavy {@code ActivityThread.H.mCallback} and {@code IActivityManager}
 * Java Dynamic Proxy architecture, {@link ZeroComponentFactory} intercepts component creation at the
 * standard Android framework extension point, unwrapping slot intents into guest component classes
 * loaded from the unmodified original APK's {@link ClassLoader}.
 */
@TargetApi(Build.VERSION_CODES.P)
public class ZeroComponentFactory extends AppComponentFactory {

    public static final String EXTRA_TARGET_CLASS = "zerobox.extra.TARGET_CLASS";
    public static final String EXTRA_TARGET_PACKAGE = "zerobox.extra.TARGET_PACKAGE";
    public static final String EXTRA_TARGET_USER_ID = "zerobox.extra.TARGET_USER_ID";
    public static final String EXTRA_TARGET_APK_PATH = "zerobox.extra.TARGET_APK_PATH";
    public static final String EXTRA_TARGET_DATA_DIR = "zerobox.extra.TARGET_DATA_DIR";
    public static final String EXTRA_ORIGINAL_INTENT = "zerobox.extra.ORIGINAL_INTENT";

    @Override
    public Activity instantiateActivity(ClassLoader cl, String className, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        if (intent != null && intent.hasExtra(EXTRA_TARGET_CLASS)) {
            String targetClass = intent.getStringExtra(EXTRA_TARGET_CLASS);
            String guestPkg = intent.getStringExtra(EXTRA_TARGET_PACKAGE);
            int userId = intent.getIntExtra(EXTRA_TARGET_USER_ID, 0);
            String apkPath = intent.getStringExtra(EXTRA_TARGET_APK_PATH);
            String dataDir = intent.getStringExtra(EXTRA_TARGET_DATA_DIR);

            if (targetClass != null && !targetClass.isEmpty()
                    && guestPkg != null && apkPath != null && dataDir != null) {
                Context hostCtx = ZeroInstrumentation.getHostApplicationContext();
                if (hostCtx != null) {
                    ZeroPackageEngine pkgEngine = new ZeroPackageEngine(hostCtx);
                    PassthroughConfig config = pkgEngine.loadPassthroughConfig(guestPkg, userId);
                    GuestLoadedApkEngine.LoadedGuestApk loaded =
                            GuestLoadedApkEngine.ensureGuestApkLoaded(
                                    hostCtx,
                                    guestPkg,
                                    userId,
                                    new File(apkPath),
                                    new File(dataDir),
                                    config,
                                    pkgEngine.getEnabledModuleApks(),
                                    pkgEngine.getEnabledNativePlugins());
                    Class<?> resolved = loaded.classLoader.loadClass(targetClass);
                    Activity act = (Activity) resolved.newInstance();
                    ZeroInstrumentation.bindPendingLoadedApkForActivity(act, loaded, targetClass);
                    return act;
                }
            }
        }
        return super.instantiateActivity(cl, className, intent);
    }

    @Override
    public Service instantiateService(ClassLoader cl, String className, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        if (intent != null && intent.hasExtra(EXTRA_TARGET_CLASS)) {
            String targetClass = intent.getStringExtra(EXTRA_TARGET_CLASS);
            ClassLoader guestCl = ZeroInstrumentation.getActiveGuestClassLoader(cl);
            if (targetClass != null && !targetClass.isEmpty()) {
                Class<?> resolved = guestCl.loadClass(targetClass);
                return (Service) resolved.newInstance();
            }
        }
        return super.instantiateService(cl, className, intent);
    }

    @Override
    public BroadcastReceiver instantiateReceiver(ClassLoader cl, String className, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        ClassLoader guestCl = ZeroInstrumentation.getActiveGuestClassLoader(cl);
        return super.instantiateReceiver(guestCl, className, intent);
    }

    @Override
    public ContentProvider instantiateProvider(ClassLoader cl, String className)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return super.instantiateProvider(cl, className);
    }

    @Override
    public Application instantiateApplication(ClassLoader cl, String className)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return super.instantiateApplication(cl, className);
    }
}
