package com.zerobox.runtime.daemon;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Build;
import com.zerobox.runtime.config.PassthroughConfig;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Zero-Modification Guest APK, Multi-User Storage &amp; Module Repository Engine.
 *
 * <p>Key anti-detection property:
 * The original APK file is <b>never</b> repacked, re-signed, or modified. Its V1/V2/V3 APK Signature
 * Scheme block remains 100% identical to the official release APK.
 */
public final class ZeroPackageEngine {

    public static final class VirtualAppRecord {
        public final String packageName;
        public final String appLabel;
        public final String versionName;
        public final String mainActivityClass;
        public final List<String> allActivityClasses;
        public final File originalApkFile;
        public final File nativeLibDir;
        public final List<Integer> userIds;
        public final Drawable appIcon;

        public VirtualAppRecord(
                String packageName,
                String appLabel,
                String versionName,
                String mainActivityClass,
                List<String> allActivityClasses,
                File originalApkFile,
                File nativeLibDir,
                List<Integer> userIds,
                Drawable appIcon) {
            this.packageName = packageName;
            this.appLabel = appLabel;
            this.versionName = versionName;
            this.mainActivityClass = mainActivityClass;
            this.allActivityClasses = allActivityClasses;
            this.originalApkFile = originalApkFile;
            this.nativeLibDir = nativeLibDir;
            this.userIds = userIds;
            this.appIcon = appIcon;
        }
    }

    public static final class HostInstalledAppCandidate {
        public final String packageName;
        public final String appLabel;
        public final String sourceApkPath;
        public final boolean isSystemApp;

        public HostInstalledAppCandidate(
                String packageName,
                String appLabel,
                String sourceApkPath,
                boolean isSystemApp) {
            this.packageName = packageName;
            this.appLabel = appLabel;
            this.sourceApkPath = sourceApkPath;
            this.isSystemApp = isSystemApp;
        }
    }

    private final Context hostContext;
    private final File rootContainerDir;

    public ZeroPackageEngine(Context hostContext) {
        this.hostContext = hostContext.getApplicationContext() != null
                ? hostContext.getApplicationContext()
                : hostContext;
        this.rootContainerDir = new File(this.hostContext.getFilesDir(), "zerobox_env");
        if (!this.rootContainerDir.exists()) {
            this.rootContainerDir.mkdirs();
        }
    }

    /**
     * Lists user-installed (and optionally system) apps on the host device so the user can
     * one-click import any installed app into ZeroBox without needing an external file manager.
     */
    public List<HostInstalledAppCandidate> listHostInstalledApps(boolean includeSystemApps) {
        PackageManager pm = hostContext.getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        List<HostInstalledAppCandidate> candidates = new ArrayList<>();
        String selfPkg = hostContext.getPackageName();

        for (ApplicationInfo info : apps) {
            if (info.packageName == null || info.packageName.equals(selfPkg)) {
                continue;
            }
            if (info.sourceDir == null || !new File(info.sourceDir).exists()) {
                continue;
            }
            boolean isSystem = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    && (info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
            if (!includeSystemApps && isSystem) {
                continue;
            }
            CharSequence labelCs = pm.getApplicationLabel(info);
            String label = labelCs != null ? labelCs.toString() : info.packageName;
            candidates.add(new HostInstalledAppCandidate(
                    info.packageName,
                    label,
                    info.sourceDir,
                    isSystem));
        }
        Collections.sort(candidates, (a, b) -> a.appLabel.compareToIgnoreCase(b.appLabel));
        return candidates;
    }

    /**
     * Imports an APK file into the container repository without modifying a single byte of the APK,
     * extracts matching ABI native libraries (arm64-v8a / armeabi-v7a), and initializes user 0.
     */
    public VirtualAppRecord importUnmodifiedApk(File sourceApkFile) throws IOException {
        if (sourceApkFile == null || !sourceApkFile.exists()) {
            throw new IOException("Source APK file does not exist");
        }
        PackageManager pm = hostContext.getPackageManager();
        int flags = PackageManager.GET_ACTIVITIES
                | PackageManager.GET_SERVICES
                | PackageManager.GET_PROVIDERS
                | PackageManager.GET_META_DATA;
        PackageInfo pkgInfo = pm.getPackageArchiveInfo(sourceApkFile.getAbsolutePath(), flags);
        if (pkgInfo == null || pkgInfo.packageName == null) {
            throw new IOException("Invalid APK archive: " + sourceApkFile.getAbsolutePath());
        }

        String pkgName = pkgInfo.packageName;
        File appRepoDir = getAppRepoDir(pkgName);
        if (!appRepoDir.exists()) {
            appRepoDir.mkdirs();
        }

        File storedApkFile = new File(appRepoDir, "base.apk");
        if (!sourceApkFile.getAbsolutePath().equals(storedApkFile.getAbsolutePath())) {
            if (storedApkFile.exists()) {
                storedApkFile.setWritable(true);
            }
            copyFileExact(sourceApkFile, storedApkFile);
        }
        storedApkFile.setReadOnly();

        File nativeLibDir = new File(appRepoDir, "lib");
        extractNativeLibrariesDualAbi(storedApkFile, nativeLibDir);

        // Initialize primary instance (userId = 0)
        createInstanceUser(pkgName, 0);

        return inspectRegisteredPackage(pkgName);
    }

    /**
     * Imports an APK stream (e.g. from Android Storage Access Framework document picker)
     * without modifying its contents.
     */
    public VirtualAppRecord importUnmodifiedApkStream(InputStream apkStream) throws IOException {
        File tmpDir = new File(rootContainerDir, "tmp");
        if (!tmpDir.exists()) tmpDir.mkdirs();
        File tempApk = new File(tmpDir, "import_" + System.currentTimeMillis() + ".apk");
        try (FileOutputStream fos = new FileOutputStream(tempApk)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = apkStream.read(buf)) != -1) {
                fos.write(buf, 0, n);
            }
        }
        try {
            return importUnmodifiedApk(tempApk);
        } finally {
            tempApk.delete();
        }
    }

    /**
     * Imports a ZeroBox SDK or Xposed/LSPosed module APK into the active modules repository.
     */
    public File importModuleApk(File sourceModuleApk, String moduleName) throws IOException {
        File modulesDir = new File(rootContainerDir, "modules");
        if (!modulesDir.exists()) modulesDir.mkdirs();
        String safeName = (moduleName != null && !moduleName.isEmpty())
                ? moduleName.replaceAll("[^a-zA-Z0-9._-]", "_")
                : "module_" + System.currentTimeMillis();
        if (!safeName.endsWith(".apk")) {
            safeName += ".apk";
        }
        File dst = new File(modulesDir, safeName);
        copyFileExact(sourceModuleApk, dst);
        return dst;
    }

    /**
     * Imports a pure Native (.so) plugin into the active native plugins repository.
     */
    public File importNativePluginSo(File sourceSoFile, String soName) throws IOException {
        File pluginsDir = new File(rootContainerDir, "native_plugins");
        if (!pluginsDir.exists()) pluginsDir.mkdirs();
        String safeName = (soName != null && !soName.isEmpty())
                ? soName.replaceAll("[^a-zA-Z0-9._-]", "_")
                : sourceSoFile.getName();
        if (!safeName.endsWith(".so")) {
            safeName += ".so";
        }
        File dst = new File(pluginsDir, safeName);
        copyFileExact(sourceSoFile, dst);
        return dst;
    }

    public List<File> getEnabledModuleApks() {
        List<File> list = new ArrayList<>();
        // Always include the host APK if it bundles SampleZeroModule assets
        File selfApk = new File(hostContext.getApplicationInfo().sourceDir);
        if (selfApk.exists()) {
            list.add(selfApk);
        }
        File modulesDir = new File(rootContainerDir, "modules");
        File[] children = modulesDir.listFiles();
        if (children != null) {
            for (File f : children) {
                if (f.isFile() && f.getName().endsWith(".apk")) {
                    list.add(f);
                }
            }
        }
        return list;
    }

    public List<File> getEnabledNativePlugins() {
        List<File> list = new ArrayList<>();
        File builtInSampleSo = new File(
                hostContext.getApplicationInfo().nativeLibraryDir,
                "libzerobox_sample_plugin.so");
        if (builtInSampleSo.exists()) {
            list.add(builtInSampleSo);
        }
        File pluginsDir = new File(rootContainerDir, "native_plugins");
        File[] children = pluginsDir.listFiles();
        if (children != null) {
            for (File f : children) {
                if (f.isFile() && f.getName().endsWith(".so")) {
                    list.add(f);
                }
            }
        }
        return list;
    }

    /**
     * Creates a new multi-instance user slot (e.g., userId = 1, 2, ...) for an imported package.
     */
    public File createInstanceUser(String packageName, int userId) {
        File dataDir = getIsolatedDataDir(packageName, userId);
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }
        new File(dataDir, "files").mkdirs();
        new File(dataDir, "cache").mkdirs();
        new File(dataDir, "shared_prefs").mkdirs();
        new File(dataDir, "databases").mkdirs();

        File configFile = getPassthroughConfigFile(packageName, userId);
        if (!configFile.exists()) {
            savePassthroughConfig(
                    packageName,
                    userId,
                    PassthroughConfig.createPureStealthPreset());
        }
        return dataDir;
    }

    public boolean removeRegisteredApp(String packageName) {
        File appDir = getAppRepoDir(packageName);
        deleteRecursively(appDir);
        File usersRoot = new File(rootContainerDir, "users");
        File[] userDirs = usersRoot.listFiles();
        if (userDirs != null) {
            for (File uDir : userDirs) {
                deleteRecursively(new File(uDir, "packages/" + packageName));
            }
        }
        return !appDir.exists();
    }

    public File getAppRepoDir(String packageName) {
        return new File(new File(rootContainerDir, "apps"), packageName);
    }

    public File getOriginalApkFile(String packageName) {
        return new File(getAppRepoDir(packageName), "base.apk");
    }

    public File getNativeLibDir(String packageName) {
        return new File(getAppRepoDir(packageName), "lib");
    }

    public File getIsolatedDataDir(String packageName, int userId) {
        return new File(
                new File(rootContainerDir, "users/" + userId + "/packages/" + packageName),
                "data");
    }

    public File getPassthroughConfigFile(String packageName, int userId) {
        return new File(
                new File(rootContainerDir, "users/" + userId + "/packages/" + packageName),
                "passthrough.conf");
    }

    public PassthroughConfig loadPassthroughConfig(String packageName, int userId) {
        File file = getPassthroughConfigFile(packageName, userId);
        if (!file.exists()) {
            return PassthroughConfig.createPureStealthPreset();
        }
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[1024];
            int n;
            while ((n = fis.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return PassthroughConfig.deserialize(
                    new String(bos.toByteArray(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return PassthroughConfig.createPureStealthPreset();
        }
    }

    public void savePassthroughConfig(String packageName, int userId, PassthroughConfig config) {
        File file = getPassthroughConfigFile(packageName, userId);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (FileOutputStream fos = new FileOutputStream(file, false)) {
            fos.write(config.serialize().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    public List<VirtualAppRecord> listRegisteredApps() {
        File appsDir = new File(rootContainerDir, "apps");
        if (!appsDir.exists() || !appsDir.isDirectory()) {
            return Collections.emptyList();
        }
        File[] children = appsDir.listFiles();
        if (children == null) {
            return Collections.emptyList();
        }
        List<VirtualAppRecord> records = new ArrayList<>();
        for (File dir : children) {
            if (dir.isDirectory()) {
                VirtualAppRecord rec = inspectRegisteredPackage(dir.getName());
                if (rec != null) {
                    records.add(rec);
                }
            }
        }
        Collections.sort(records, (a, b) -> a.appLabel.compareToIgnoreCase(b.appLabel));
        return records;
    }

    public VirtualAppRecord inspectRegisteredPackage(String packageName) {
        File baseApk = getOriginalApkFile(packageName);
        if (!baseApk.exists()) {
            return null;
        }
        PackageManager pm = hostContext.getPackageManager();
        PackageInfo info = pm.getPackageArchiveInfo(
                baseApk.getAbsolutePath(),
                PackageManager.GET_ACTIVITIES | PackageManager.GET_META_DATA);
        String label = packageName;
        String version = "1.0";
        String mainActivity = "";
        List<String> allActivities = new ArrayList<>();
        Drawable icon = null;

        // First check if host PMS has a resolved MAIN/LAUNCHER intent for this package
        try {
            Intent launchIntent = pm.getLaunchIntentForPackage(packageName);
            if (launchIntent != null && launchIntent.getComponent() != null) {
                mainActivity = launchIntent.getComponent().getClassName();
            } else {
                Intent query = new Intent(Intent.ACTION_MAIN);
                query.addCategory(Intent.CATEGORY_LAUNCHER);
                query.setPackage(packageName);
                List<ResolveInfo> resolves = pm.queryIntentActivities(query, 0);
                if (resolves != null && !resolves.isEmpty() && resolves.get(0).activityInfo != null) {
                    mainActivity = resolves.get(0).activityInfo.name;
                }
            }
        } catch (Throwable ignored) {
        }

        if (info != null) {
            if (info.versionName != null) {
                version = info.versionName;
            }
            ApplicationInfo appInfo = info.applicationInfo;
            if (appInfo != null) {
                appInfo.sourceDir = baseApk.getAbsolutePath();
                appInfo.publicSourceDir = baseApk.getAbsolutePath();
                CharSequence cs = pm.getApplicationLabel(appInfo);
                if (cs != null) {
                    label = cs.toString();
                }
                try {
                    icon = pm.getApplicationIcon(appInfo);
                } catch (Throwable ignored) {
                }
            }
            if (info.activities != null && info.activities.length > 0) {
                for (ActivityInfo act : info.activities) {
                    if (act.name != null) {
                        allActivities.add(act.name);
                        if (mainActivity.isEmpty()) {
                            mainActivity = act.name;
                        }
                    }
                }
            }
        }

        List<Integer> users = listUsersForPackage(packageName);
        if (users.isEmpty()) {
            users.add(0);
        }

        return new VirtualAppRecord(
                packageName,
                label,
                version,
                mainActivity,
                allActivities,
                baseApk,
                getNativeLibDir(packageName),
                users,
                icon);
    }

    private List<Integer> listUsersForPackage(String packageName) {
        File usersRoot = new File(rootContainerDir, "users");
        if (!usersRoot.exists()) {
            return new ArrayList<>();
        }
        File[] userDirs = usersRoot.listFiles();
        if (userDirs == null) {
            return new ArrayList<>();
        }
        List<Integer> ids = new ArrayList<>();
        for (File uDir : userDirs) {
            try {
                int uid = Integer.parseInt(uDir.getName());
                File pkgDir = new File(uDir, "packages/" + packageName);
                if (pkgDir.exists()) {
                    ids.add(uid);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        Collections.sort(ids);
        return ids;
    }

    private static void extractNativeLibrariesDualAbi(File apkFile, File targetLibDir) throws IOException {
        if (!targetLibDir.exists()) {
            targetLibDir.mkdirs();
        }
        String preferredAbi = Build.SUPPORTED_64_BIT_ABIS.length > 0 ? "arm64-v8a" : "armeabi-v7a";
        String fallbackAbi = "armeabi-v7a";

        try (ZipFile zip = new ZipFile(apkFile)) {
            boolean foundPreferred = extractAbiEntries(zip, "lib/" + preferredAbi + "/", targetLibDir);
            if (!foundPreferred && !preferredAbi.equals(fallbackAbi)) {
                extractAbiEntries(zip, "lib/" + fallbackAbi + "/", targetLibDir);
            }
        }
    }

    private static boolean extractAbiEntries(ZipFile zip, String prefix, File targetLibDir)
            throws IOException {
        boolean foundAny = false;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (!entry.isDirectory() && name.startsWith(prefix) && name.endsWith(".so")) {
                String fileName = name.substring(prefix.length());
                if (fileName.contains("/") || fileName.contains("..")) continue;
                File outFile = new File(targetLibDir, fileName);
                try (InputStream is = zip.getInputStream(entry);
                     FileOutputStream fos = new FileOutputStream(outFile)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        fos.write(buf, 0, n);
                    }
                }
                foundAny = true;
            }
        }
        return foundAny;
    }

    private static void copyFileExact(File src, File dst) throws IOException {
        try (FileInputStream fis = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dst)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = fis.read(buf)) != -1) {
                fos.write(buf, 0, n);
            }
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteRecursively(c);
                }
            }
        }
        f.setWritable(true);
        f.delete();
    }
}
