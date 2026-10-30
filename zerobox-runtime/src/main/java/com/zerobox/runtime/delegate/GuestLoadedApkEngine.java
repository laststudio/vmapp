package com.zerobox.runtime.delegate;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Build;
import android.os.Process;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.Window;
import android.webkit.WebView;
import com.zerobox.runtime.config.PassthroughConfig;
import com.zerobox.runtime.daemon.ZeroRuntimeSession;
import com.zerobox.runtime.module.SiblingModuleLoader;
import dalvik.system.PathClassLoader;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Loads an unmodified guest APK into the current container slot process, initializing:
 * <ol>
 *   <li>Seccomp-BPF kernel VFS &amp; {@code /proc/self/maps} cloak</li>
 *   <li>Isolated {@link PathClassLoader} (with native library path)</li>
 *   <li>Genuine guest {@link Resources} and {@link AssetManager}</li>
 *   <li>{@link GuestContextWrapper} with UID-safe {@code getOpPackageName()}</li>
 *   <li>Registered ZeroBox SDK modules, LSPosed/Xposed modules, and Native {@code .so} plugins</li>
 *   <li>Guest {@link ContentProvider}s (e.g. {@code androidx.startup.InitializationProvider})</li>
 *   <li>Guest {@link Application#attachBaseContext(Context)} and {@link Application#onCreate()}</li>
 * </ol>
 */
public final class GuestLoadedApkEngine {

    public static final class LoadedGuestApk {
        public final String packageName;
        public final int userId;
        public final File originalApkFile;
        public final File isolatedDataDir;
        public final File nativeLibDir;
        public final PackageInfo packageInfo;
        public final ApplicationInfo applicationInfo;
        public final ClassLoader classLoader;
        public final Resources resources;
        public final GuestContextWrapper baseContext;
        public final Application application;
        public final ZeroRuntimeSession runtimeSession;

        LoadedGuestApk(
                String packageName,
                int userId,
                File originalApkFile,
                File isolatedDataDir,
                File nativeLibDir,
                PackageInfo packageInfo,
                ApplicationInfo applicationInfo,
                ClassLoader classLoader,
                Resources resources,
                GuestContextWrapper baseContext,
                Application application,
                ZeroRuntimeSession runtimeSession) {
            this.packageName = packageName;
            this.userId = userId;
            this.originalApkFile = originalApkFile;
            this.isolatedDataDir = isolatedDataDir;
            this.nativeLibDir = nativeLibDir;
            this.packageInfo = packageInfo;
            this.applicationInfo = applicationInfo;
            this.classLoader = classLoader;
            this.resources = resources;
            this.baseContext = baseContext;
            this.application = application;
            this.runtimeSession = runtimeSession;
        }
    }

    private static final Map<String, LoadedGuestApk> sLoadedCache = new ConcurrentHashMap<>();
    private static final Map<String, ContentProvider> sGuestProvidersByAuthority =
            new ConcurrentHashMap<>();
    private static final AtomicBoolean sWebViewSuffixInitialized = new AtomicBoolean(false);

    private GuestLoadedApkEngine() {}

    public static synchronized LoadedGuestApk ensureGuestApkLoaded(
            Context hostContext,
            String guestPackage,
            int userId,
            File originalApkFile,
            File isolatedDataDir,
            PassthroughConfig config,
            List<File> enabledModuleApks,
            List<File> enabledNativePlugins) {
        String key = guestPackage + "@" + userId;
        LoadedGuestApk cached = sLoadedCache.get(key);
        if (cached != null) {
            return cached;
        }

        Context appHostCtx = hostContext.getApplicationContext() != null
                ? hostContext.getApplicationContext()
                : hostContext;

        // 0. Configure isolated WebView data directory suffix on Android 9.0+ (API 28+)
        configureWebViewDataDirectorySuffix(guestPackage, userId);

        // 1. Ensure ZeroInstrumentation is hooked into ActivityThread.mInstrumentation
        ZeroInstrumentation.ensureInstalledOnCurrentActivityThread(appHostCtx);

        // 2. Initialize Native Seccomp-BPF VFS, /proc cloak, and ZeroRuntimeSession
        ZeroRuntimeSession session = ZeroRuntimeSession.initializeSlotProcess(
                appHostCtx,
                guestPackage,
                userId,
                originalApkFile,
                isolatedDataDir,
                config);

        File nativeLibDir = new File(originalApkFile.getParentFile(), "lib");

        // 3. Parse full PackageInfo (Activities, Services, Providers, Signatures, MetaData) from unmodified APK
        PackageManager pm = appHostCtx.getPackageManager();
        int flags = PackageManager.GET_ACTIVITIES
                | PackageManager.GET_SERVICES
                | PackageManager.GET_PROVIDERS
                | PackageManager.GET_RECEIVERS
                | PackageManager.GET_META_DATA
                | PackageManager.GET_SIGNATURES;
        PackageInfo pkgInfo = pm.getPackageArchiveInfo(originalApkFile.getAbsolutePath(), flags);
        if (pkgInfo == null) {
            pkgInfo = new PackageInfo();
            pkgInfo.packageName = guestPackage;
            pkgInfo.applicationInfo = new ApplicationInfo(appHostCtx.getApplicationInfo());
        }

        ApplicationInfo appInfo = pkgInfo.applicationInfo != null
                ? pkgInfo.applicationInfo
                : new ApplicationInfo(appHostCtx.getApplicationInfo());
        appInfo.packageName = guestPackage;
        appInfo.sourceDir = originalApkFile.getAbsolutePath();
        appInfo.publicSourceDir = originalApkFile.getAbsolutePath();
        appInfo.dataDir = isolatedDataDir.getAbsolutePath();
        appInfo.deviceProtectedDataDir = isolatedDataDir.getAbsolutePath() + "_de";
        appInfo.nativeLibraryDir = nativeLibDir.getAbsolutePath();
        appInfo.uid = Process.myUid();

        // 4. Create isolated guest PathClassLoader and guest Resources
        ClassLoader guestCl = session.getGuestClassLoader();
        Resources guestRes = createGuestResources(appHostCtx, appInfo, originalApkFile);

        // 5. Create GuestContextWrapper
        GuestContextWrapper guestContext = new GuestContextWrapper(
                appHostCtx,
                guestPackage,
                userId,
                originalApkFile,
                isolatedDataDir,
                nativeLibDir,
                guestCl,
                guestRes,
                appInfo,
                pkgInfo);

        // 6. Instantiate Guest Application and attach GuestContextWrapper
        Application guestApp = instantiateGuestApplication(guestCl, appInfo, guestContext);
        guestContext.setGuestApplication(guestApp);
        ZeroInstrumentation.setActiveGuestEnvironment(guestCl, guestApp);

        // 7. Load enabled ZeroBox / Xposed modules and Native .so plugins BEFORE Providers & Application.onCreate()
        if (enabledNativePlugins != null && config.isEnableNativePluginLoader()) {
            for (File soFile : enabledNativePlugins) {
                if (soFile != null && soFile.exists()) {
                    session.loadNativePlugin(soFile.getAbsolutePath());
                }
            }
        }
        if (enabledModuleApks != null) {
            File codeCacheDir = new File(isolatedDataDir, "code_cache/modules");
            for (File modApk : enabledModuleApks) {
                if (modApk != null && modApk.exists()) {
                    SiblingModuleLoader.loadModuleApk(
                            modApk,
                            codeCacheDir,
                            session,
                            config.isEnableJavaHookEngine());
                }
            }
        }

        // 8. Install Guest ContentProviders BEFORE Application.onCreate() (standard Android startup order)
        if (!guestPackage.equals(appHostCtx.getPackageName())) {
            installGuestContentProviders(guestContext, guestCl, pkgInfo, appInfo);
        }

        // 9. Invoke guestApp.onCreate()
        if (guestApp != null && !guestPackage.equals(appHostCtx.getPackageName())) {
            try {
                guestApp.onCreate();
            } catch (Throwable ignored) {
            }
        }

        LoadedGuestApk loaded = new LoadedGuestApk(
                guestPackage,
                userId,
                originalApkFile,
                isolatedDataDir,
                nativeLibDir,
                pkgInfo,
                appInfo,
                guestCl,
                guestRes,
                guestContext,
                guestApp,
                session);
        sLoadedCache.put(key, loaded);
        return loaded;
    }

    private static void configureWebViewDataDirectorySuffix(String guestPackage, int userId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                && sWebViewSuffixInitialized.compareAndSet(false, true)) {
            try {
                String safePkg = (guestPackage != null ? guestPackage : "guest")
                        .replaceAll("[^a-zA-Z0-9_]", "_");
                WebView.setDataDirectorySuffix("zb_u" + userId + "_" + safePkg);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void installGuestContentProviders(
            Context guestContext,
            ClassLoader guestCl,
            PackageInfo pkgInfo,
            ApplicationInfo appInfo) {
        if (pkgInfo == null || pkgInfo.providers == null || guestCl == null) {
            return;
        }
        for (ProviderInfo pInfo : pkgInfo.providers) {
            if (pInfo == null || pInfo.name == null || pInfo.name.isEmpty()) {
                continue;
            }
            try {
                pInfo.applicationInfo = appInfo;
                Class<?> providerClass = guestCl.loadClass(pInfo.name);
                ContentProvider provider =
                        (ContentProvider) providerClass.getDeclaredConstructor().newInstance();
                provider.attachInfo(guestContext, pInfo);
                if (pInfo.authority != null && !pInfo.authority.isEmpty()) {
                    for (String auth : pInfo.authority.split(";")) {
                        if (!auth.trim().isEmpty()) {
                            sGuestProvidersByAuthority.put(auth.trim(), provider);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public static ContentProvider getGuestProviderByAuthority(String authority) {
        return authority != null ? sGuestProvidersByAuthority.get(authority) : null;
    }

    public static LoadedGuestApk getActiveLoadedApk(String guestPackage, int userId) {
        return sLoadedCache.get(guestPackage + "@" + userId);
    }

    /**
     * Injects the guest APK's {@link GuestContextWrapper}, {@link Resources}, {@link Application},
     * {@link LayoutInflater} (including {@code PhoneWindow.mLayoutInflater}), and {@link ActivityInfo}
     * theme into a newly instantiated guest {@link Activity} prior to {@code Activity.onCreate(Bundle)}.
     */
    public static void patchActivityEnvironment(
            Activity activity,
            LoadedGuestApk loaded,
            String activityClassName) {
        if (activity == null || loaded == null) {
            return;
        }
        try {
            GuestContextWrapper activityBaseCtx = new GuestContextWrapper(
                    activity.getBaseContext(),
                    loaded.packageName,
                    loaded.userId,
                    loaded.originalApkFile,
                    loaded.isolatedDataDir,
                    loaded.nativeLibDir,
                    loaded.classLoader,
                    loaded.resources,
                    loaded.applicationInfo,
                    loaded.packageInfo);
            activityBaseCtx.setGuestApplication(loaded.application);

            // 1. Replace ContextWrapper.mBase
            setFieldValue(ContextWrapper.class, activity, "mBase", activityBaseCtx);

            // 2. Replace ContextThemeWrapper.mResources and reset mTheme
            setFieldValue(ContextThemeWrapper.class, activity, "mResources", loaded.resources);
            setFieldValue(ContextThemeWrapper.class, activity, "mTheme", null);

            // 3. Replace Activity.mApplication & Activity.mComponent
            if (loaded.application != null) {
                setFieldValue(Activity.class, activity, "mApplication", loaded.application);
            }
            if (activityClassName != null && !activityClassName.isEmpty()) {
                setFieldValue(
                        Activity.class,
                        activity,
                        "mComponent",
                        new ComponentName(loaded.packageName, activityClassName));
            }

            // 4. Resolve ActivityInfo from guest PackageInfo and apply guest Activity theme & title
            ActivityInfo matchedInfo = findActivityInfo(loaded.packageInfo, activityClassName);
            if (matchedInfo != null) {
                matchedInfo.applicationInfo = loaded.applicationInfo;
                setFieldValue(Activity.class, activity, "mActivityInfo", matchedInfo);
                int themeRes = matchedInfo.getThemeResource();
                if (themeRes != 0) {
                    activity.setTheme(themeRes);
                } else if (loaded.applicationInfo.theme != 0) {
                    activity.setTheme(loaded.applicationInfo.theme);
                }
                if (matchedInfo.labelRes != 0 && loaded.resources != null) {
                    try {
                        activity.setTitle(loaded.resources.getString(matchedInfo.labelRes));
                    } catch (Throwable ignored) {
                    }
                } else if (matchedInfo.nonLocalizedLabel != null) {
                    activity.setTitle(matchedInfo.nonLocalizedLabel);
                }
            } else if (loaded.applicationInfo.theme != 0) {
                activity.setTheme(loaded.applicationInfo.theme);
            }

            // 5. Patch Activity.mInflater and PhoneWindow.mLayoutInflater so setContentView(R.layout.xxx)
            //    inflates layouts from the guest APK's Resources and ClassLoader!
            LayoutInflater baseInflater = (LayoutInflater) activityBaseCtx.getSystemService(
                    Context.LAYOUT_INFLATER_SERVICE);
            if (baseInflater != null) {
                LayoutInflater guestActivityInflater = baseInflater.cloneInContext(activity);
                setFieldValue(Activity.class, activity, "mInflater", guestActivityInflater);
                Window window = activity.getWindow();
                if (window != null) {
                    setFieldValue(window.getClass(), window, "mLayoutInflater", guestActivityInflater);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static ActivityInfo findActivityInfo(PackageInfo pkgInfo, String className) {
        if (pkgInfo == null || pkgInfo.activities == null || className == null) {
            return null;
        }
        for (ActivityInfo info : pkgInfo.activities) {
            if (className.equals(info.name)) {
                return info;
            }
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private static Resources createGuestResources(
            Context hostContext,
            ApplicationInfo appInfo,
            File originalApkFile) {
        try {
            Resources res = hostContext.getPackageManager().getResourcesForApplication(appInfo);
            if (res != null) {
                return res;
            }
        } catch (Throwable ignored) {
        }

        try {
            AssetManager assetManager = AssetManager.class.getDeclaredConstructor().newInstance();
            Method addAssetPath = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
            addAssetPath.setAccessible(true);
            addAssetPath.invoke(assetManager, originalApkFile.getAbsolutePath());
            Resources hostRes = hostContext.getResources();
            return new Resources(
                    assetManager,
                    hostRes.getDisplayMetrics(),
                    hostRes.getConfiguration());
        } catch (Throwable ignored) {
            return hostContext.getResources();
        }
    }

    private static Application instantiateGuestApplication(
            ClassLoader guestCl,
            ApplicationInfo appInfo,
            GuestContextWrapper guestContext) {
        String appClassName = (appInfo != null && appInfo.className != null && !appInfo.className.isEmpty())
                ? appInfo.className
                : "android.app.Application";
        try {
            Class<?> appClass = guestCl.loadClass(appClassName);
            Application app = (Application) appClass.getDeclaredConstructor().newInstance();
            Method attachMethod = Application.class.getDeclaredMethod("attach", Context.class);
            attachMethod.setAccessible(true);
            attachMethod.invoke(app, guestContext);
            return app;
        } catch (Throwable ignored) {
            try {
                Application fallback = new Application();
                Method attachMethod = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
                attachMethod.setAccessible(true);
                attachMethod.invoke(fallback, guestContext);
                return fallback;
            } catch (Throwable t) {
                return null;
            }
        }
    }

    private static void setFieldValue(Class<?> clazz, Object target, String fieldName, Object value) {
        Class<?> curr = clazz;
        while (curr != null) {
            try {
                Field f = curr.getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                curr = curr.getSuperclass();
            } catch (Throwable ignored) {
                return;
            }
        }
    }
}
