package com.zerobox.runtime.delegate;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.IBinder;
import com.zerobox.runtime.config.PassthroughConfig;
import com.zerobox.runtime.daemon.ZeroPackageEngine;
import dalvik.system.PathClassLoader;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight {@link Instrumentation} delegate that supports API 26+ (Android 8.0 ~ 15+)
 * without replacing {@code IActivityManager} singletons with {@code java.lang.reflect.Proxy}.
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>Installs itself onto {@code ActivityThread.sCurrentActivityThread.mInstrumentation}.</li>
 *   <li>Instantiates the target guest {@link Activity} from the unmodified original APK's {@link PathClassLoader}.</li>
 *   <li>Injects {@link GuestContextWrapper}, guest {@code Resources}, {@code Theme}, and {@code Application}
 *       before {@code Activity.onCreate(Bundle)} executes.</li>
 *   <li>Intercepts child {@code execStartActivity} calls from inside the guest Activity so internal navigation
 *       between guest Activities works seamlessly without {@code ActivityNotFoundException}.</li>
 * </ol>
 */
public final class ZeroInstrumentation extends Instrumentation {

    private static volatile ClassLoader sGuestClassLoader;
    private static volatile Application sGuestApplication;
    private static volatile Context sHostApplicationContext;
    private static volatile GuestLoadedApkEngine.LoadedGuestApk sCurrentLoadedApk;
    private static final Map<Activity, PendingActivityPatch> sPendingPatches =
            new ConcurrentHashMap<>();

    private static final class PendingActivityPatch {
        final GuestLoadedApkEngine.LoadedGuestApk loaded;
        final String activityClassName;

        PendingActivityPatch(GuestLoadedApkEngine.LoadedGuestApk loaded, String activityClassName) {
            this.loaded = loaded;
            this.activityClassName = activityClassName;
        }
    }

    private final Instrumentation base;
    private final String hostPackageName;

    public ZeroInstrumentation(Instrumentation base, String hostPackageName) {
        this.base = base;
        this.hostPackageName = hostPackageName;
    }

    public static void setHostApplicationContext(Context hostContext) {
        if (hostContext != null) {
            sHostApplicationContext = hostContext.getApplicationContext() != null
                    ? hostContext.getApplicationContext()
                    : hostContext;
        }
    }

    public static Context getHostApplicationContext() {
        if (sHostApplicationContext != null) {
            return sHostApplicationContext;
        }
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Method currentApp = atClass.getDeclaredMethod("currentApplication");
            currentApp.setAccessible(true);
            Application app = (Application) currentApp.invoke(null);
            if (app != null) {
                sHostApplicationContext = app;
                return app;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Installs {@link ZeroInstrumentation} onto the current process's {@code ActivityThread}.
     */
    public static synchronized void ensureInstalledOnCurrentActivityThread(Context hostContext) {
        setHostApplicationContext(hostContext);
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Method currentAtMethod = atClass.getDeclaredMethod("currentActivityThread");
            currentAtMethod.setAccessible(true);
            Object activityThread = currentAtMethod.invoke(null);
            if (activityThread == null) return;

            Field instrField = atClass.getDeclaredField("mInstrumentation");
            instrField.setAccessible(true);
            Instrumentation current = (Instrumentation) instrField.get(activityThread);
            if (!(current instanceof ZeroInstrumentation)) {
                ZeroInstrumentation wrapped = new ZeroInstrumentation(
                        current,
                        hostContext.getPackageName());
                instrField.set(activityThread, wrapped);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void bindPendingLoadedApkForActivity(
            Activity activity,
            GuestLoadedApkEngine.LoadedGuestApk loaded,
            String activityClassName) {
        if (activity != null && loaded != null) {
            sCurrentLoadedApk = loaded;
            sPendingPatches.put(activity, new PendingActivityPatch(loaded, activityClassName));
        }
    }

    public static void setActiveGuestEnvironment(ClassLoader guestCl, Application guestApp) {
        sGuestClassLoader = guestCl;
        sGuestApplication = guestApp;
    }

    public static ClassLoader getActiveGuestClassLoader(ClassLoader fallback) {
        return sGuestClassLoader != null ? sGuestClassLoader : fallback;
    }

    public static Application getActiveGuestApplication() {
        return sGuestApplication;
    }

    /**
     * Creates a pure {@link PathClassLoader} pointing directly to the unmodified original APK file
     * and its extracted native libraries directory, with {@code BootClassLoader} as parent so host
     * container classes never leak into the guest ClassLoader hierarchy.
     */
    public static PathClassLoader createCleanGuestClassLoader(File originalApkFile, File nativeLibDir) {
        String apkPath = originalApkFile.getAbsolutePath();
        String libPath = (nativeLibDir != null && nativeLibDir.exists())
                ? nativeLibDir.getAbsolutePath()
                : null;
        ClassLoader bootLoader = Context.class.getClassLoader();
        return new PathClassLoader(apkPath, libPath, bootLoader);
    }

    /**
     * Wraps a guest Activity launch Intent into a pre-declared host slot Activity Intent
     * while preserving the original Intent inside extras.
     */
    public static Intent wrapSlotActivityIntent(
            Context hostContext,
            int slotIndex,
            String guestPackage,
            String targetActivityClass,
            int userId,
            String originalApkPath,
            String isolatedDataDir,
            Intent originalIntent) {
        Class<?> slotClass = SlotActivity.getSlotActivityClass(slotIndex);
        Intent wrapper = new Intent(hostContext, slotClass);
        wrapper.putExtra(ZeroComponentFactory.EXTRA_TARGET_PACKAGE, guestPackage);
        wrapper.putExtra(ZeroComponentFactory.EXTRA_TARGET_CLASS, targetActivityClass);
        wrapper.putExtra(ZeroComponentFactory.EXTRA_TARGET_USER_ID, userId);
        wrapper.putExtra(ZeroComponentFactory.EXTRA_TARGET_APK_PATH, originalApkPath);
        wrapper.putExtra(ZeroComponentFactory.EXTRA_TARGET_DATA_DIR, isolatedDataDir);
        if (originalIntent != null) {
            wrapper.putExtra(ZeroComponentFactory.EXTRA_ORIGINAL_INTENT, originalIntent);
            wrapper.addFlags(originalIntent.getFlags());
        }
        return wrapper;
    }

    /**
     * Inspects an outgoing Intent from a guest Context/Activity; if it targets an Activity
     * inside the guest APK, wraps it into a SlotActivity Intent automatically.
     */
    public static Intent wrapGuestIntentIfNeeded(
            Context hostContext,
            String guestPackage,
            int userId,
            String originalApkPath,
            String isolatedDataDir,
            PackageInfo guestPackageInfo,
            Intent intent) {
        if (intent == null || intent.hasExtra(ZeroComponentFactory.EXTRA_TARGET_CLASS)) {
            return intent;
        }
        ComponentName comp = intent.getComponent();
        String targetClass = null;
        if (comp != null) {
            if (guestPackage.equals(comp.getPackageName())
                    || hostContext.getPackageName().equals(comp.getPackageName())) {
                targetClass = comp.getClassName();
            }
        } else if (guestPackage.equals(intent.getPackage())
                && guestPackageInfo != null
                && guestPackageInfo.activities != null
                && guestPackageInfo.activities.length > 0) {
            targetClass = guestPackageInfo.activities[0].name;
        }

        if (targetClass != null && isGuestActivityDeclared(guestPackageInfo, targetClass)) {
            return wrapSlotActivityIntent(
                    hostContext,
                    userId % 4,
                    guestPackage,
                    targetClass,
                    userId,
                    originalApkPath,
                    isolatedDataDir,
                    intent);
        }
        return intent;
    }

    private static boolean isGuestActivityDeclared(PackageInfo pkgInfo, String className) {
        if (pkgInfo == null || pkgInfo.activities == null || className == null) {
            return true;
        }
        for (ActivityInfo info : pkgInfo.activities) {
            if (className.equals(info.name)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Activity newActivity(ClassLoader cl, String className, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        if (intent != null && intent.hasExtra(ZeroComponentFactory.EXTRA_TARGET_CLASS)) {
            String targetClass = intent.getStringExtra(ZeroComponentFactory.EXTRA_TARGET_CLASS);
            String guestPkg = intent.getStringExtra(ZeroComponentFactory.EXTRA_TARGET_PACKAGE);
            int userId = intent.getIntExtra(ZeroComponentFactory.EXTRA_TARGET_USER_ID, 0);
            String apkPath = intent.getStringExtra(ZeroComponentFactory.EXTRA_TARGET_APK_PATH);
            String dataDir = intent.getStringExtra(ZeroComponentFactory.EXTRA_TARGET_DATA_DIR);

            if (targetClass != null && !targetClass.isEmpty()
                    && guestPkg != null && apkPath != null && dataDir != null
                    && sHostApplicationContext != null) {
                ZeroPackageEngine pkgEngine = new ZeroPackageEngine(sHostApplicationContext);
                PassthroughConfig config = pkgEngine.loadPassthroughConfig(guestPkg, userId);
                GuestLoadedApkEngine.LoadedGuestApk loaded =
                        GuestLoadedApkEngine.ensureGuestApkLoaded(
                                sHostApplicationContext,
                                guestPkg,
                                userId,
                                new File(apkPath),
                                new File(dataDir),
                                config,
                                pkgEngine.getEnabledModuleApks(),
                                pkgEngine.getEnabledNativePlugins());

                Intent orig = intent.getParcelableExtra(ZeroComponentFactory.EXTRA_ORIGINAL_INTENT);
                if (orig != null) {
                    orig.setExtrasClassLoader(loaded.classLoader);
                }
                Activity act = (base != null)
                        ? base.newActivity(loaded.classLoader, targetClass, intent)
                        : (Activity) loaded.classLoader.loadClass(targetClass).newInstance();
                bindPendingLoadedApkForActivity(act, loaded, targetClass);
                return act;
            }
        }
        if (base != null) {
            return base.newActivity(cl, className, intent);
        }
        return super.newActivity(cl, className, intent);
    }

    @Override
    public void callActivityOnCreate(Activity activity, Bundle icicle) {
        PendingActivityPatch patch = sPendingPatches.remove(activity);
        if (patch != null) {
            GuestLoadedApkEngine.patchActivityEnvironment(
                    activity,
                    patch.loaded,
                    patch.activityClassName);
            Intent currentIntent = activity.getIntent();
            if (currentIntent != null
                    && currentIntent.hasExtra(ZeroComponentFactory.EXTRA_ORIGINAL_INTENT)) {
                Intent orig = currentIntent.getParcelableExtra(
                        ZeroComponentFactory.EXTRA_ORIGINAL_INTENT);
                if (orig != null) {
                    orig.setExtrasClassLoader(patch.loaded.classLoader);
                    activity.setIntent(orig);
                }
            }
        } else if (sCurrentLoadedApk != null
                && !(activity instanceof SlotActivity)
                && !activity.getClass().getName().startsWith("com.zerobox.")) {
            GuestLoadedApkEngine.patchActivityEnvironment(
                    activity,
                    sCurrentLoadedApk,
                    activity.getClass().getName());
        }

        if (base != null) {
            base.callActivityOnCreate(activity, icicle);
        } else {
            super.callActivityOnCreate(activity, icicle);
        }
    }

    /**
     * Hidden Android framework method invoked by {@code Activity.startActivityForResult}.
     * Intercepted here so guest Activities navigating to other guest Activities are transparently
     * wrapped into {@link SlotActivity}.
     */
    @SuppressWarnings("unused")
    public ActivityResult execStartActivity(
            Context who,
            IBinder contextThread,
            IBinder token,
            Activity target,
            Intent intent,
            int requestCode,
            Bundle options) {
        if (sCurrentLoadedApk != null && sHostApplicationContext != null) {
            intent = wrapGuestIntentIfNeeded(
                    sHostApplicationContext,
                    sCurrentLoadedApk.packageName,
                    sCurrentLoadedApk.userId,
                    sCurrentLoadedApk.originalApkFile.getAbsolutePath(),
                    sCurrentLoadedApk.isolatedDataDir.getAbsolutePath(),
                    sCurrentLoadedApk.packageInfo,
                    intent);
        }
        if (base != null) {
            try {
                Method m = Instrumentation.class.getDeclaredMethod(
                        "execStartActivity",
                        Context.class,
                        IBinder.class,
                        IBinder.class,
                        Activity.class,
                        Intent.class,
                        int.class,
                        Bundle.class);
                m.setAccessible(true);
                return (ActivityResult) m.invoke(
                        base, who, contextThread, token, target, intent, requestCode, options);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    public String getHostPackageName() {
        return hostPackageName;
    }
}
