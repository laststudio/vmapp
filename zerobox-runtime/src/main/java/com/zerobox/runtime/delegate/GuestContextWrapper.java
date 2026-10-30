package com.zerobox.runtime.delegate;

import android.annotation.TargetApi;
import android.app.Application;
import android.content.AttributionSource;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Full-featured isolated {@link Context} environment for a guest APK running inside a ZeroBox slot.
 *
 * <p>Key architectural design:
 * <ul>
 *   <li>{@link #getPackageName()} returns the guest APK's package name so app-level checks see the real package.</li>
 *   <li>{@code getOpPackageName()} and {@link #getAttributionSource()} return the host container's UID-aligned
 *       identity so Android 8.0 ~ 15+ {@code AppOpsManager} / {@code system_server} Binder calls never throw
 *       {@code SecurityException: Package <guest> does not belong to <host_uid>}.</li>
 *   <li>{@link #getPackageManager()} returns {@link GuestPackageManager}, serving the guest APK's components,
 *       metadata, and unmodified V1/V2/V3 signatures without {@code NameNotFoundException}.</li>
 *   <li>{@link #getSystemService(String)} binds {@link LayoutInflater} to this guest context so XML layouts
 *       inflate with the guest APK's {@link Resources} and {@link ClassLoader}.</li>
 * </ul>
 */
public final class GuestContextWrapper extends ContextWrapper {

    private final Context hostContext;
    private final String guestPackageName;
    private final int userId;
    private final File originalApkFile;
    private final File isolatedDataDir;
    private final File nativeLibDir;
    private final ClassLoader guestClassLoader;
    private final Resources guestResources;
    private final ApplicationInfo guestAppInfo;
    private final PackageInfo guestPackageInfo;
    private final GuestPackageManager guestPackageManager;
    private Resources.Theme guestTheme;
    private Application guestApplication;
    private LayoutInflater guestLayoutInflater;

    private static final Map<String, SharedPreferences> sIsolatedPrefsCache =
            new ConcurrentHashMap<>();

    public GuestContextWrapper(
            Context hostContext,
            String guestPackageName,
            int userId,
            File originalApkFile,
            File isolatedDataDir,
            File nativeLibDir,
            ClassLoader guestClassLoader,
            Resources guestResources,
            ApplicationInfo guestAppInfo,
            PackageInfo guestPackageInfo) {
        super(hostContext);
        this.hostContext = hostContext;
        this.guestPackageName = guestPackageName;
        this.userId = userId;
        this.originalApkFile = originalApkFile;
        this.isolatedDataDir = isolatedDataDir;
        this.nativeLibDir = nativeLibDir;
        this.guestClassLoader = guestClassLoader;
        this.guestResources = guestResources;
        this.guestAppInfo = guestAppInfo;
        this.guestPackageInfo = guestPackageInfo;
        this.guestPackageManager = new GuestPackageManager(
                hostContext.getPackageManager(),
                guestPackageName,
                guestPackageInfo,
                guestAppInfo,
                guestResources);

        ensurePrivateDirectoriesExist();
    }

    public void setGuestApplication(Application guestApplication) {
        this.guestApplication = guestApplication;
    }

    private void ensurePrivateDirectoriesExist() {
        if (!isolatedDataDir.exists()) {
            isolatedDataDir.mkdirs();
        }
        new File(isolatedDataDir, "files").mkdirs();
        new File(isolatedDataDir, "cache").mkdirs();
        new File(isolatedDataDir, "code_cache").mkdirs();
        new File(isolatedDataDir, "no_backup").mkdirs();
        new File(isolatedDataDir, "shared_prefs").mkdirs();
        new File(isolatedDataDir, "databases").mkdirs();
    }

    @Override
    public String getPackageName() {
        return guestPackageName;
    }

    /**
     * Used by Android Framework system service managers (AppOpsManager, ClipboardManager,
     * NotificationManager, Toast, AudioManager, ContentResolver) when constructing Binder Parcels.
     * Returning the host's package name matches {@code Binder.getCallingUid()} in {@code system_server}
     * without requiring Java Proxy hooks on 40+ services!
     */
    public String getOpPackageName() {
        return hostContext.getPackageName();
    }

    @TargetApi(Build.VERSION_CODES.S)
    @Override
    public AttributionSource getAttributionSource() {
        return hostContext.getAttributionSource();
    }

    @Override
    public PackageManager getPackageManager() {
        return guestPackageManager;
    }

    @Override
    public Object getSystemService(String name) {
        if (Context.LAYOUT_INFLATER_SERVICE.equals(name)) {
            if (guestLayoutInflater == null) {
                LayoutInflater baseInflater = (LayoutInflater) super.getSystemService(name);
                if (baseInflater != null) {
                    guestLayoutInflater = baseInflater.cloneInContext(this);
                }
            }
            if (guestLayoutInflater != null) {
                return guestLayoutInflater;
            }
        }
        return super.getSystemService(name);
    }

    @Override
    public Context getApplicationContext() {
        return guestApplication != null ? guestApplication : this;
    }

    @Override
    public ApplicationInfo getApplicationInfo() {
        return guestAppInfo;
    }

    @Override
    public String getPackageCodePath() {
        return originalApkFile.getAbsolutePath();
    }

    @Override
    public String getPackageResourcePath() {
        return originalApkFile.getAbsolutePath();
    }

    @Override
    public ClassLoader getClassLoader() {
        return guestClassLoader;
    }

    @Override
    public Resources getResources() {
        return guestResources != null ? guestResources : super.getResources();
    }

    @Override
    public AssetManager getAssets() {
        return guestResources != null ? guestResources.getAssets() : super.getAssets();
    }

    @Override
    public Resources.Theme getTheme() {
        if (guestTheme == null && guestResources != null) {
            guestTheme = guestResources.newTheme();
            int themeRes = (guestAppInfo != null && guestAppInfo.theme != 0)
                    ? guestAppInfo.theme
                    : android.R.style.Theme_DeviceDefault_Light_NoActionBar;
            guestTheme.applyStyle(themeRes, true);
        }
        return guestTheme != null ? guestTheme : super.getTheme();
    }

    @Override
    public void setTheme(int resid) {
        if (guestResources != null) {
            if (guestTheme == null) {
                guestTheme = guestResources.newTheme();
            }
            guestTheme.applyStyle(resid, true);
        } else {
            super.setTheme(resid);
        }
    }

    @Override
    public File getDataDir() {
        return isolatedDataDir;
    }

    @Override
    public File getFilesDir() {
        File dir = new File(isolatedDataDir, "files");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    @Override
    public File getCacheDir() {
        File dir = new File(isolatedDataDir, "cache");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    @Override
    public File getCodeCacheDir() {
        File dir = new File(isolatedDataDir, "code_cache");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    @Override
    public File getNoBackupFilesDir() {
        File dir = new File(isolatedDataDir, "no_backup");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    @Override
    public File getExternalFilesDir(String type) {
        File base = new File(isolatedDataDir, "external_files");
        File target = (type == null || type.isEmpty()) ? base : new File(base, type);
        if (!target.exists()) target.mkdirs();
        return target;
    }

    @Override
    public File getExternalCacheDir() {
        File dir = new File(isolatedDataDir, "external_cache");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    @Override
    public File getDir(String name, int mode) {
        File dir = new File(isolatedDataDir, "app_" + name);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    @Override
    public File getFileStreamPath(String name) {
        return new File(getFilesDir(), name);
    }

    @Override
    public FileInputStream openFileInput(String name) throws FileNotFoundException {
        return new FileInputStream(getFileStreamPath(name));
    }

    @Override
    public FileOutputStream openFileOutput(String name, int mode) throws FileNotFoundException {
        boolean append = (mode & Context.MODE_APPEND) != 0;
        return new FileOutputStream(getFileStreamPath(name), append);
    }

    @Override
    public boolean deleteFile(String name) {
        return getFileStreamPath(name).delete();
    }

    @Override
    public String[] fileList() {
        String[] list = getFilesDir().list();
        return list != null ? list : new String[0];
    }

    @Override
    public File getDatabasePath(String name) {
        if (name != null && name.startsWith("/")) {
            return new File(name);
        }
        File dbDir = new File(isolatedDataDir, "databases");
        if (!dbDir.exists()) dbDir.mkdirs();
        return new File(dbDir, name != null ? name : "default.db");
    }

    @Override
    public SQLiteDatabase openOrCreateDatabase(
            String name,
            int mode,
            SQLiteDatabase.CursorFactory factory) {
        return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory);
    }

    @Override
    public SQLiteDatabase openOrCreateDatabase(
            String name,
            int mode,
            SQLiteDatabase.CursorFactory factory,
            DatabaseErrorHandler errorHandler) {
        return SQLiteDatabase.openOrCreateDatabase(
                getDatabasePath(name).getAbsolutePath(),
                factory,
                errorHandler);
    }

    @Override
    public boolean deleteDatabase(String name) {
        return SQLiteDatabase.deleteDatabase(getDatabasePath(name));
    }

    @Override
    public String[] databaseList() {
        File dbDir = new File(isolatedDataDir, "databases");
        String[] list = dbDir.list();
        return list != null ? list : new String[0];
    }

    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
        String isolatedName = "zb_u" + userId + "_" + guestPackageName + "_" + name;
        return sIsolatedPrefsCache.computeIfAbsent(
                isolatedName,
                k -> hostContext.getSharedPreferences(k, mode));
    }

    @Override
    public void startActivity(Intent intent) {
        super.startActivity(ZeroInstrumentation.wrapGuestIntentIfNeeded(
                hostContext,
                guestPackageName,
                userId,
                originalApkFile.getAbsolutePath(),
                isolatedDataDir.getAbsolutePath(),
                guestPackageInfo,
                intent));
    }

    @Override
    public void startActivity(Intent intent, Bundle options) {
        super.startActivity(
                ZeroInstrumentation.wrapGuestIntentIfNeeded(
                        hostContext,
                        guestPackageName,
                        userId,
                        originalApkFile.getAbsolutePath(),
                        isolatedDataDir.getAbsolutePath(),
                        guestPackageInfo,
                        intent),
                options);
    }

    @Override
    public ComponentName startService(Intent service) {
        return super.startService(wrapGuestServiceIntentIfNeeded(service));
    }

    @Override
    public boolean stopService(Intent service) {
        return super.stopService(wrapGuestServiceIntentIfNeeded(service));
    }

    @Override
    public boolean bindService(Intent service, android.content.ServiceConnection conn, int flags) {
        return super.bindService(wrapGuestServiceIntentIfNeeded(service), conn, flags);
    }

    private Intent wrapGuestServiceIntentIfNeeded(Intent service) {
        if (service == null || service.hasExtra(ZeroComponentFactory.EXTRA_TARGET_CLASS)) {
            return service;
        }
        ComponentName comp = service.getComponent();
        if (comp != null && (guestPackageName.equals(comp.getPackageName())
                || hostContext.getPackageName().equals(comp.getPackageName()))) {
            Class<?> slotSvc = SlotService.getSlotServiceClass(userId);
            Intent wrapper = new Intent(hostContext, slotSvc);
            wrapper.putExtra(ZeroComponentFactory.EXTRA_TARGET_PACKAGE, guestPackageName);
            wrapper.putExtra(ZeroComponentFactory.EXTRA_TARGET_CLASS, comp.getClassName());
            wrapper.putExtra(ZeroComponentFactory.EXTRA_TARGET_USER_ID, userId);
            wrapper.putExtra(ZeroComponentFactory.EXTRA_TARGET_APK_PATH, originalApkFile.getAbsolutePath());
            wrapper.putExtra(ZeroComponentFactory.EXTRA_TARGET_DATA_DIR, isolatedDataDir.getAbsolutePath());
            wrapper.putExtra(ZeroComponentFactory.EXTRA_ORIGINAL_INTENT, service);
            return wrapper;
        }
        return service;
    }

    public PackageInfo getGuestPackageInfo() {
        return guestPackageInfo;
    }

    public File getNativeLibDir() {
        return nativeLibDir;
    }

    public PackageManager getHostPackageManager() {
        return hostContext.getPackageManager();
    }
}
