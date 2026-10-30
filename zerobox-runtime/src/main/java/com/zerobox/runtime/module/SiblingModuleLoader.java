package com.zerobox.runtime.module;

import com.zerobox.sdk.api.ZeroModule;
import com.zerobox.sdk.api.ZeroModuleContext;
import dalvik.system.DexClassLoader;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Sibling Isolated Module ClassLoader Engine.
 *
 * <p>Why traditional Xposed / VirtualApp module loaders get detected:
 * They inject module DEX files directly into the guest app's {@code PathClassLoader} or place
 * {@code XposedBridge} in the parent ClassLoader chain of the guest APK. As a result, the guest
 * APK can trivially call {@code Class.forName("de.robv.android.xposed.XposedBridge")} to detect the framework.
 *
 * <p>ZeroBox's solution:
 * <ul>
 *   <li>The guest APK's {@code ClassLoader} hierarchy remains 100% pure ({@code BootClassLoader -> PathClassLoader(guest.apk)}).</li>
 *   <li>Each module runs inside a {@link SiblingBridgeClassLoader} that sits <em>next to</em> (sibling of)
 *       the guest ClassLoader.</li>
 *   <li>One-way visibility: The module ClassLoader can resolve classes from {@code guestClassLoader} and
 *       {@code hostRuntimeClassLoader}, while {@code guestClassLoader} has <b>zero</b> reference to the module ClassLoader!</li>
 * </ul>
 */
public final class SiblingModuleLoader {

    /**
     * One-way bridge ClassLoader used as the parent of a module's {@link DexClassLoader}.
     * Delegates to:
     * 1. BootClassLoader
     * 2. Host ZeroBox SDK / Xposed Bridge ClassLoader
     * 3. Guest APK ClassLoader
     * While never exposing module or Xposed classes back to the Guest APK ClassLoader!
     */
    public static final class SiblingBridgeClassLoader extends ClassLoader {
        private final ClassLoader hostSdkClassLoader;
        private final ClassLoader guestClassLoader;

        public SiblingBridgeClassLoader(ClassLoader hostSdkClassLoader, ClassLoader guestClassLoader) {
            super(Object.class.getClassLoader());
            this.hostSdkClassLoader = hostSdkClassLoader;
            this.guestClassLoader = guestClassLoader;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            // 1. Check Bootstrap classes first
            try {
                return super.loadClass(name, resolve);
            } catch (ClassNotFoundException ignored) {
            }

            // 2. Check ZeroBox SDK & Xposed compatibility bridge classes
            if (name.startsWith("com.zerobox.sdk.")
                    || name.startsWith("de.robv.android.xposed.")
                    || name.startsWith("android.app.AndroidAppHelper")) {
                if (hostSdkClassLoader != null) {
                    return hostSdkClassLoader.loadClass(name);
                }
            }

            // 3. One-way lookup into the guest application's ClassLoader
            if (guestClassLoader != null) {
                try {
                    return guestClassLoader.loadClass(name);
                } catch (ClassNotFoundException ignored) {
                }
            }

            // 4. Fallback to host ClassLoader if needed
            if (hostSdkClassLoader != null) {
                return hostSdkClassLoader.loadClass(name);
            }
            throw new ClassNotFoundException(name);
        }
    }

    private SiblingModuleLoader() {}

    /**
     * Loads a ZeroBox native-SDK module or Xposed/LSPosed module APK from disk into an isolated
     * sibling ClassLoader and invokes its entry point(s).
     *
     * @param moduleApkFile        Module APK file on disk
     * @param codeCacheDir         Isolated optimized DEX cache directory
     * @param context              ZeroModuleContext for the target guest app
     * @param enableXposedCompat   Whether Xposed/LSPosed compatibility bridge is enabled in PassthroughConfig
     * @return Number of module entry points successfully activated
     */
    public static int loadModuleApk(
            File moduleApkFile,
            File codeCacheDir,
            ZeroModuleContext context,
            boolean enableXposedCompat) {
        if (moduleApkFile == null || !moduleApkFile.exists()) {
            return 0;
        }
        int activatedCount = 0;
        try {
            List<String> zeroEntries = readInitEntries(moduleApkFile, "assets/zerobox_module.init");
            List<String> xposedEntries = enableXposedCompat
                    ? readInitEntries(moduleApkFile, "assets/xposed_init")
                    : new ArrayList<>();

            if (zeroEntries.isEmpty() && xposedEntries.isEmpty()) {
                return 0;
            }

            if (!codeCacheDir.exists()) {
                codeCacheDir.mkdirs();
            }

            SiblingBridgeClassLoader bridgeParent = new SiblingBridgeClassLoader(
                    SiblingModuleLoader.class.getClassLoader(),
                    context.getGuestClassLoader());

            String nativeLibDir = moduleApkFile.getParentFile() != null
                    ? new File(moduleApkFile.getParentFile(), "lib").getAbsolutePath()
                    : null;

            DexClassLoader moduleClassLoader = new DexClassLoader(
                    moduleApkFile.getAbsolutePath(),
                    codeCacheDir.getAbsolutePath(),
                    nativeLibDir,
                    bridgeParent);

            // 1. Activate ZeroBox native-SDK modules
            for (String entryClass : zeroEntries) {
                try {
                    Class<?> clazz = moduleClassLoader.loadClass(entryClass);
                    Object instance = clazz.getDeclaredConstructor().newInstance();
                    if (instance instanceof ZeroModule) {
                        ((ZeroModule) instance).onLoad(context);
                        activatedCount++;
                    }
                } catch (Throwable ignored) {
                }
            }

            // 2. Lazily activate Xposed/LSPosed modules via reflection on XposedModuleAdapter
            // so XposedBridge is NEVER touched unless enableXposedCompat == true and xposedEntries is non-empty!
            if (enableXposedCompat && !xposedEntries.isEmpty()) {
                activatedCount += invokeXposedBridgeAdapter(
                        moduleClassLoader,
                        moduleApkFile.getAbsolutePath(),
                        xposedEntries,
                        context);
            }
        } catch (Throwable ignored) {
        }
        return activatedCount;
    }

    private static int invokeXposedBridgeAdapter(
            ClassLoader moduleClassLoader,
            String modulePath,
            List<String> xposedEntryClasses,
            ZeroModuleContext context) {
        try {
            Class<?> adapterClass = Class.forName(
                    "de.robv.android.xposed.XposedModuleAdapter",
                    true,
                    SiblingModuleLoader.class.getClassLoader());
            Method dispatchMethod = adapterClass.getDeclaredMethod(
                    "activateModuleEntries",
                    ClassLoader.class,
                    String.class,
                    List.class,
                    ZeroModuleContext.class);
            dispatchMethod.setAccessible(true);
            Object count = dispatchMethod.invoke(
                    null,
                    moduleClassLoader,
                    modulePath,
                    xposedEntryClasses,
                    context);
            return (count instanceof Integer) ? (Integer) count : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public static List<String> readInitEntries(File apkFile, String assetEntryPath) {
        List<String> entries = new ArrayList<>();
        try (ZipFile zip = new ZipFile(apkFile)) {
            ZipEntry entry = zip.getEntry(assetEntryPath);
            if (entry == null) {
                return entries;
            }
            try (InputStream is = zip.getInputStream(entry);
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                        entries.add(trimmed);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return entries;
    }
}
