package com.zerobox.runtime.delegate;

import android.annotation.TargetApi;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ChangedPackages;
import android.content.pm.FeatureInfo;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.VersionedPackage;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.UserHandle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Clean-room {@link PackageManager} wrapper bound to {@link GuestContextWrapper}.
 *
 * <p>Why this is essential for running uninstalled APKs and passing signature checks:
 * <ul>
 *   <li>When an uninstalled guest APK (or {@code AppCompatActivity}) calls
 *       {@code getPackageManager().getPackageInfo(getPackageName(), GET_SIGNATURES)} or
 *       {@code getActivityInfo(getComponentName(), GET_META_DATA)}, the real OS PackageManager
 *       would throw {@link NameNotFoundException}.</li>
 *   <li>{@link GuestPackageManager} serves the guest APK's {@link PackageInfo}, {@link ApplicationInfo},
 *       {@link ActivityInfo}, {@link ServiceInfo}, {@link ProviderInfo}, and real V1/V2/V3
 *       {@code signatures} directly from the unmodified original {@code base.apk}, while passing
 *       all other queries straight through to the host OS {@link PackageManager}.</li>
 * </ul>
 */
@SuppressWarnings("deprecation")
public final class GuestPackageManager extends PackageManager {

    private final PackageManager base;
    private final String guestPackageName;
    private final PackageInfo guestPackageInfo;
    private final ApplicationInfo guestAppInfo;
    private final Resources guestResources;

    public GuestPackageManager(
            PackageManager base,
            String guestPackageName,
            PackageInfo guestPackageInfo,
            ApplicationInfo guestAppInfo,
            Resources guestResources) {
        this.base = base;
        this.guestPackageName = guestPackageName;
        this.guestPackageInfo = guestPackageInfo;
        this.guestAppInfo = guestAppInfo;
        this.guestResources = guestResources;
    }

    private boolean isGuestPackage(String packageName) {
        return guestPackageName != null && guestPackageName.equals(packageName);
    }

    @Override
    public PackageInfo getPackageInfo(String packageName, int flags) throws NameNotFoundException {
        if (isGuestPackage(packageName) && guestPackageInfo != null) {
            return guestPackageInfo;
        }
        return base.getPackageInfo(packageName, flags);
    }

    @TargetApi(Build.VERSION_CODES.O)
    @Override
    public PackageInfo getPackageInfo(VersionedPackage versionedPackage, int flags)
            throws NameNotFoundException {
        if (versionedPackage != null && isGuestPackage(versionedPackage.getPackageName())
                && guestPackageInfo != null) {
            return guestPackageInfo;
        }
        return base.getPackageInfo(versionedPackage, flags);
    }

    @Override
    public ApplicationInfo getApplicationInfo(String packageName, int flags)
            throws NameNotFoundException {
        if (isGuestPackage(packageName) && guestAppInfo != null) {
            return guestAppInfo;
        }
        return base.getApplicationInfo(packageName, flags);
    }

    @Override
    public ActivityInfo getActivityInfo(ComponentName component, int flags)
            throws NameNotFoundException {
        if (component != null && isGuestPackage(component.getPackageName())
                && guestPackageInfo != null && guestPackageInfo.activities != null) {
            for (ActivityInfo info : guestPackageInfo.activities) {
                if (component.getClassName().equals(info.name)) {
                    info.applicationInfo = guestAppInfo;
                    return info;
                }
            }
            // Synthesize fallback ActivityInfo for guest Activity so AppCompatDelegate never crashes
            ActivityInfo synth = new ActivityInfo();
            synth.packageName = guestPackageName;
            synth.name = component.getClassName();
            synth.applicationInfo = guestAppInfo;
            synth.theme = guestAppInfo.theme;
            return synth;
        }
        return base.getActivityInfo(component, flags);
    }

    @Override
    public ActivityInfo getReceiverInfo(ComponentName component, int flags)
            throws NameNotFoundException {
        if (component != null && isGuestPackage(component.getPackageName())
                && guestPackageInfo != null && guestPackageInfo.receivers != null) {
            for (ActivityInfo info : guestPackageInfo.receivers) {
                if (component.getClassName().equals(info.name)) {
                    info.applicationInfo = guestAppInfo;
                    return info;
                }
            }
        }
        return base.getReceiverInfo(component, flags);
    }

    @Override
    public ServiceInfo getServiceInfo(ComponentName component, int flags)
            throws NameNotFoundException {
        if (component != null && isGuestPackage(component.getPackageName())
                && guestPackageInfo != null && guestPackageInfo.services != null) {
            for (ServiceInfo info : guestPackageInfo.services) {
                if (component.getClassName().equals(info.name)) {
                    info.applicationInfo = guestAppInfo;
                    return info;
                }
            }
        }
        return base.getServiceInfo(component, flags);
    }

    @Override
    public ProviderInfo getProviderInfo(ComponentName component, int flags)
            throws NameNotFoundException {
        if (component != null && isGuestPackage(component.getPackageName())
                && guestPackageInfo != null && guestPackageInfo.providers != null) {
            for (ProviderInfo info : guestPackageInfo.providers) {
                if (component.getClassName().equals(info.name)) {
                    info.applicationInfo = guestAppInfo;
                    return info;
                }
            }
        }
        return base.getProviderInfo(component, flags);
    }

    @Override
    public String getInstallerPackageName(String packageName) {
        if (isGuestPackage(packageName)) {
            return "com.android.vending"; // Spoof official Google Play store installer
        }
        return base.getInstallerPackageName(packageName);
    }

    @Override
    public Resources getResourcesForApplication(ApplicationInfo app) throws NameNotFoundException {
        if (app != null && isGuestPackage(app.packageName) && guestResources != null) {
            return guestResources;
        }
        return base.getResourcesForApplication(app);
    }

    @Override
    public Resources getResourcesForApplication(String appPackageName) throws NameNotFoundException {
        if (isGuestPackage(appPackageName) && guestResources != null) {
            return guestResources;
        }
        return base.getResourcesForApplication(appPackageName);
    }

    @Override
    public List<ResolveInfo> queryIntentActivities(Intent intent, int flags) {
        List<ResolveInfo> hostResults = base.queryIntentActivities(intent, flags);
        if (intent != null && intent.getComponent() != null
                && isGuestPackage(intent.getComponent().getPackageName())) {
            try {
                ActivityInfo ai = getActivityInfo(intent.getComponent(), flags);
                ResolveInfo ri = new ResolveInfo();
                ri.activityInfo = ai;
                List<ResolveInfo> out = new ArrayList<>(hostResults);
                out.add(0, ri);
                return out;
            } catch (NameNotFoundException ignored) {
            }
        }
        return hostResults;
    }

    @Override
    public ResolveInfo resolveActivity(Intent intent, int flags) {
        if (intent != null && intent.getComponent() != null
                && isGuestPackage(intent.getComponent().getPackageName())) {
            try {
                ActivityInfo ai = getActivityInfo(intent.getComponent(), flags);
                ResolveInfo ri = new ResolveInfo();
                ri.activityInfo = ai;
                return ri;
            } catch (NameNotFoundException ignored) {
            }
        }
        return base.resolveActivity(intent, flags);
    }

    @Override
    public Intent getLaunchIntentForPackage(String packageName) {
        if (isGuestPackage(packageName) && guestPackageInfo != null
                && guestPackageInfo.activities != null && guestPackageInfo.activities.length > 0) {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setPackage(guestPackageName);
            intent.setClassName(guestPackageName, guestPackageInfo.activities[0].name);
            return intent;
        }
        return base.getLaunchIntentForPackage(packageName);
    }

    @Override
    public int checkPermission(String permName, String pkgName) {
        if (isGuestPackage(pkgName)) {
            return PackageManager.PERMISSION_GRANTED;
        }
        return base.checkPermission(permName, pkgName);
    }

    @Override
    public boolean isPermissionRevokedByPolicy(String permName, String pkgName) {
        if (isGuestPackage(pkgName)) {
            return false;
        }
        return base.isPermissionRevokedByPolicy(permName, pkgName);
    }

    @Override
    public String[] currentToCanonicalPackageNames(String[] names) {
        return base.currentToCanonicalPackageNames(names);
    }

    @Override
    public String[] canonicalToCurrentPackageNames(String[] names) {
        return base.canonicalToCurrentPackageNames(names);
    }

    @Override
    public Intent getLeanbackLaunchIntentForPackage(String packageName) {
        return base.getLeanbackLaunchIntentForPackage(packageName);
    }

    @Override
    public int[] getPackageGids(String packageName) throws NameNotFoundException {
        if (isGuestPackage(packageName)) return new int[0];
        return base.getPackageGids(packageName);
    }

    @Override
    public int[] getPackageGids(String packageName, int flags) throws NameNotFoundException {
        if (isGuestPackage(packageName)) return new int[0];
        return base.getPackageGids(packageName, flags);
    }

    @Override
    public int getPackageUid(String packageName, int flags) throws NameNotFoundException {
        if (isGuestPackage(packageName) && guestAppInfo != null) {
            return guestAppInfo.uid;
        }
        return base.getPackageUid(packageName, flags);
    }

    @Override
    public PermissionInfo getPermissionInfo(String name, int flags) throws NameNotFoundException {
        return base.getPermissionInfo(name, flags);
    }

    @Override
    public List<PermissionInfo> queryPermissionsByGroup(String group, int flags)
            throws NameNotFoundException {
        return base.queryPermissionsByGroup(group, flags);
    }

    @Override
    public PermissionGroupInfo getPermissionGroupInfo(String name, int flags)
            throws NameNotFoundException {
        return base.getPermissionGroupInfo(name, flags);
    }

    @Override
    public List<PermissionGroupInfo> getAllPermissionGroups(int flags) {
        return base.getAllPermissionGroups(flags);
    }

    @Override
    public String[] getSystemSharedLibraryNames() {
        return base.getSystemSharedLibraryNames();
    }

    @TargetApi(Build.VERSION_CODES.O)
    @Override
    public List<SharedLibraryInfo> getSharedLibraries(int flags) {
        return base.getSharedLibraries(flags);
    }

    @TargetApi(Build.VERSION_CODES.O)
    @Override
    public ChangedPackages getChangedPackages(int sequenceNumber) {
        return base.getChangedPackages(sequenceNumber);
    }

    @Override
    public FeatureInfo[] getSystemAvailableFeatures() {
        return base.getSystemAvailableFeatures();
    }

    @Override
    public boolean hasSystemFeature(String name) {
        return base.hasSystemFeature(name);
    }

    @Override
    public boolean hasSystemFeature(String name, int version) {
        return base.hasSystemFeature(name, version);
    }

    @Override
    public List<PackageInfo> getInstalledPackages(int flags) {
        List<PackageInfo> list = new ArrayList<>(base.getInstalledPackages(flags));
        if (guestPackageInfo != null) {
            list.add(guestPackageInfo);
        }
        return list;
    }

    @Override
    public List<PackageInfo> getPackagesHoldingPermissions(String[] permissions, int flags) {
        return base.getPackagesHoldingPermissions(permissions, flags);
    }

    @Override
    public int checkSignatures(String pkg1, String pkg2) {
        if (isGuestPackage(pkg1) && isGuestPackage(pkg2)) {
            return PackageManager.SIGNATURE_MATCH;
        }
        return base.checkSignatures(pkg1, pkg2);
    }

    @Override
    public int checkSignatures(int uid1, int uid2) {
        return base.checkSignatures(uid1, uid2);
    }

    @Override
    public String[] getPackagesForUid(int uid) {
        if (guestAppInfo != null && uid == guestAppInfo.uid) {
            return new String[] {guestPackageName};
        }
        return base.getPackagesForUid(uid);
    }

    @Override
    public String getNameForUid(int uid) {
        if (guestAppInfo != null && uid == guestAppInfo.uid) {
            return guestPackageName;
        }
        return base.getNameForUid(uid);
    }

    @Override
    public List<ApplicationInfo> getInstalledApplications(int flags) {
        List<ApplicationInfo> list = new ArrayList<>(base.getInstalledApplications(flags));
        if (guestAppInfo != null) {
            list.add(guestAppInfo);
        }
        return list;
    }

    @TargetApi(Build.VERSION_CODES.O)
    @Override
    public boolean isInstantApp() {
        return false;
    }

    @TargetApi(Build.VERSION_CODES.O)
    @Override
    public boolean isInstantApp(String packageName) {
        return false;
    }

    @TargetApi(Build.VERSION_CODES.O)
    @Override
    public int getInstantAppCookieMaxBytes() {
        return base.getInstantAppCookieMaxBytes();
    }

    @TargetApi(Build.VERSION_CODES.O)
    @Override
    public byte[] getInstantAppCookie() {
        return new byte[0];
    }

    @TargetApi(Build.VERSION_CODES.O)
    @Override
    public void clearInstantAppCookie() {}

    @TargetApi(Build.VERSION_CODES.O)
    @Override
    public void updateInstantAppCookie(byte[] cookie) {}

    @Override
    public boolean addPermission(PermissionInfo info) {
        return base.addPermission(info);
    }

    @Override
    public boolean addPermissionAsync(PermissionInfo info) {
        return base.addPermissionAsync(info);
    }

    @Override
    public void removePermission(String name) {
        base.removePermission(name);
    }

    @Override
    public List<ResolveInfo> queryIntentActivityOptions(
            ComponentName caller,
            Intent[] specifics,
            Intent intent,
            int flags) {
        return base.queryIntentActivityOptions(caller, specifics, intent, flags);
    }

    @Override
    public List<ResolveInfo> queryBroadcastReceivers(Intent intent, int flags) {
        return base.queryBroadcastReceivers(intent, flags);
    }

    @Override
    public ResolveInfo resolveService(Intent intent, int flags) {
        return base.resolveService(intent, flags);
    }

    @Override
    public List<ResolveInfo> queryIntentServices(Intent intent, int flags) {
        return base.queryIntentServices(intent, flags);
    }

    @Override
    public List<ResolveInfo> queryIntentContentProviders(Intent intent, int flags) {
        return base.queryIntentContentProviders(intent, flags);
    }

    @Override
    public ProviderInfo resolveContentProvider(String name, int flags) {
        if (guestPackageInfo != null && guestPackageInfo.providers != null) {
            for (ProviderInfo info : guestPackageInfo.providers) {
                if (name != null && name.equals(info.authority)) {
                    info.applicationInfo = guestAppInfo;
                    return info;
                }
            }
        }
        return base.resolveContentProvider(name, flags);
    }

    @Override
    public List<ProviderInfo> queryContentProviders(String processName, int uid, int flags) {
        return base.queryContentProviders(processName, uid, flags);
    }

    @Override
    public InstrumentationInfo getInstrumentationInfo(ComponentName className, int flags)
            throws NameNotFoundException {
        return base.getInstrumentationInfo(className, flags);
    }

    @Override
    public List<InstrumentationInfo> queryInstrumentation(String targetPackage, int flags) {
        return Collections.emptyList();
    }

    @Override
    public Drawable getDrawable(String packageName, int resid, ApplicationInfo appInfo) {
        if (isGuestPackage(packageName) && guestResources != null) {
            try {
                return guestResources.getDrawable(resid, null);
            } catch (Throwable ignored) {
            }
        }
        return base.getDrawable(packageName, resid, appInfo);
    }

    @Override
    public Drawable getActivityIcon(ComponentName activityName) throws NameNotFoundException {
        if (activityName != null && isGuestPackage(activityName.getPackageName())) {
            return getApplicationIcon(guestAppInfo);
        }
        return base.getActivityIcon(activityName);
    }

    @Override
    public Drawable getActivityIcon(Intent intent) throws NameNotFoundException {
        return base.getActivityIcon(intent);
    }

    @Override
    public Drawable getActivityBanner(ComponentName activityName) throws NameNotFoundException {
        return base.getActivityBanner(activityName);
    }

    @Override
    public Drawable getActivityBanner(Intent intent) throws NameNotFoundException {
        return base.getActivityBanner(intent);
    }

    @Override
    public Drawable getDefaultActivityIcon() {
        return base.getDefaultActivityIcon();
    }

    @Override
    public Drawable getApplicationIcon(ApplicationInfo info) {
        return base.getApplicationIcon(info);
    }

    @Override
    public Drawable getApplicationIcon(String packageName) throws NameNotFoundException {
        if (isGuestPackage(packageName) && guestAppInfo != null) {
            return base.getApplicationIcon(guestAppInfo);
        }
        return base.getApplicationIcon(packageName);
    }

    @Override
    public Drawable getApplicationBanner(ApplicationInfo info) {
        return base.getApplicationBanner(info);
    }

    @Override
    public Drawable getApplicationBanner(String packageName) throws NameNotFoundException {
        return base.getApplicationBanner(packageName);
    }

    @Override
    public Drawable getActivityLogo(ComponentName activityName) throws NameNotFoundException {
        return base.getActivityLogo(activityName);
    }

    @Override
    public Drawable getActivityLogo(Intent intent) throws NameNotFoundException {
        return base.getActivityLogo(intent);
    }

    @Override
    public Drawable getApplicationLogo(ApplicationInfo info) {
        return base.getApplicationLogo(info);
    }

    @Override
    public Drawable getApplicationLogo(String packageName) throws NameNotFoundException {
        return base.getApplicationLogo(packageName);
    }

    @Override
    public Drawable getUserBadgedIcon(Drawable icon, UserHandle user) {
        return base.getUserBadgedIcon(icon, user);
    }

    @Override
    public Drawable getUserBadgedDrawableForDensity(
            Drawable drawable,
            UserHandle user,
            Rect badgeLocation,
            int badgeDensity) {
        return base.getUserBadgedDrawableForDensity(drawable, user, badgeLocation, badgeDensity);
    }

    @Override
    public CharSequence getUserBadgedLabel(CharSequence label, UserHandle user) {
        return base.getUserBadgedLabel(label, user);
    }

    @Override
    public CharSequence getText(String packageName, int resid, ApplicationInfo appInfo) {
        if (isGuestPackage(packageName) && guestResources != null) {
            try {
                return guestResources.getText(resid);
            } catch (Throwable ignored) {
            }
        }
        return base.getText(packageName, resid, appInfo);
    }

    @Override
    public XmlResourceParser getXml(String packageName, int resid, ApplicationInfo appInfo) {
        if (isGuestPackage(packageName) && guestResources != null) {
            try {
                return guestResources.getXml(resid);
            } catch (Throwable ignored) {
            }
        }
        return base.getXml(packageName, resid, appInfo);
    }

    @Override
    public CharSequence getApplicationLabel(ApplicationInfo info) {
        return base.getApplicationLabel(info);
    }

    @Override
    public Resources getResourcesForActivity(ComponentName activityName)
            throws NameNotFoundException {
        if (activityName != null && isGuestPackage(activityName.getPackageName())
                && guestResources != null) {
            return guestResources;
        }
        return base.getResourcesForActivity(activityName);
    }

    @Override
    public void verifyPendingInstall(int id, int verificationCode) {
        base.verifyPendingInstall(id, verificationCode);
    }

    @Override
    public void extendVerificationTimeout(
            int id,
            int verificationCodeAtTimeout,
            long millisecondsToDelay) {
        base.extendVerificationTimeout(id, verificationCodeAtTimeout, millisecondsToDelay);
    }

    @Override
    public void setInstallerPackageName(String targetPackage, String installerPackageName) {
        base.setInstallerPackageName(targetPackage, installerPackageName);
    }

    @Override
    public void addPackageToPreferred(String packageName) {
        base.addPackageToPreferred(packageName);
    }

    @Override
    public void removePackageFromPreferred(String packageName) {
        base.removePackageFromPreferred(packageName);
    }

    @Override
    public List<PackageInfo> getPreferredPackages(int flags) {
        return base.getPreferredPackages(flags);
    }

    @Override
    public void addPreferredActivity(
            IntentFilter filter,
            int match,
            ComponentName[] set,
            ComponentName activity) {
        base.addPreferredActivity(filter, match, set, activity);
    }

    @Override
    public void clearPackagePreferredActivities(String packageName) {
        base.clearPackagePreferredActivities(packageName);
    }

    @Override
    public int getPreferredActivities(
            List<IntentFilter> outFilters,
            List<ComponentName> outActivities,
            String packageName) {
        return base.getPreferredActivities(outFilters, outActivities, packageName);
    }

    @Override
    public void setComponentEnabledSetting(ComponentName componentName, int newState, int flags) {
        if (componentName != null && isGuestPackage(componentName.getPackageName())) {
            return;
        }
        base.setComponentEnabledSetting(componentName, newState, flags);
    }

    @Override
    public int getComponentEnabledSetting(ComponentName componentName) {
        if (componentName != null && isGuestPackage(componentName.getPackageName())) {
            return PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        }
        return base.getComponentEnabledSetting(componentName);
    }

    @Override
    public void setApplicationEnabledSetting(String packageName, int newState, int flags) {
        if (isGuestPackage(packageName)) return;
        base.setApplicationEnabledSetting(packageName, newState, flags);
    }

    @Override
    public int getApplicationEnabledSetting(String packageName) {
        if (isGuestPackage(packageName)) {
            return PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        }
        return base.getApplicationEnabledSetting(packageName);
    }

    @Override
    public boolean isSafeMode() {
        return base.isSafeMode();
    }

    @TargetApi(Build.VERSION_CODES.O)
    @Override
    public void setApplicationCategoryHint(String packageName, int categoryHint) {
        if (isGuestPackage(packageName)) return;
        base.setApplicationCategoryHint(packageName, categoryHint);
    }

    @Override
    public PackageInstaller getPackageInstaller() {
        return base.getPackageInstaller();
    }

    @TargetApi(Build.VERSION_CODES.O)
    @Override
    public boolean canRequestPackageInstalls() {
        return true;
    }
}
