package com.zerobox.sample;

import com.zerobox.sdk.api.ZeroModule;
import com.zerobox.sdk.api.ZeroModuleContext;
import com.zerobox.sdk.hook.ZeroHook;
import com.zerobox.sdk.memory.MemoryRegion;
import com.zerobox.sdk.memory.ZeroMemory;
import com.zerobox.sdk.vfs.ZeroVfs;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Reference module demonstrating both:
 * <ol>
 *   <li>ZeroBox's native self-developed SDK ({@link ZeroModule}, {@link ZeroMemory}, {@link ZeroVfs}, {@link ZeroHook})</li>
 *   <li>Standard LSPosed / Xposed compatibility ({@link IXposedHookLoadPackage})</li>
 * </ol>
 */
public final class SampleZeroModule implements ZeroModule, IXposedHookLoadPackage {

    @Override
    public void onLoad(ZeroModuleContext context) throws Throwable {
        ZeroVfs vfs = context.getVfs();
        ZeroMemory memory = context.getMemory();

        // 1. Write module audit log into the guest application's isolated private directory
        String auditMessage = "ZeroBox Module Loaded for pkg=" + context.getPackageName()
                + ", userId=" + context.getUserId() + "\n";
        vfs.writePrivateFile(
                "files/zerobox_module_audit.log",
                auditMessage.getBytes(StandardCharsets.UTF_8),
                true);

        // 2. Add a custom passthrough directory rule ("Hiding > Confrontation")
        vfs.addPassthroughPath("/system/etc/security/cacerts");

        // 3. Inspect loaded libc.so memory regions via ZeroMemory
        List<MemoryRegion> libcRegions = memory.getMemoryRegions("libc.so");
        if (!libcRegions.isEmpty()) {
            MemoryRegion first = libcRegions.get(0);
            byte[] elfHeader = memory.readByteArray(first.getStartAddress(), 4);
            String summary = "libc.so base=0x" + Long.toHexString(first.getStartAddress())
                    + " perms=" + first.getPermissions()
                    + " magicLen=" + elfHeader.length + "\n";
            vfs.writePrivateFile(
                    "files/zerobox_module_audit.log",
                    summary.getBytes(StandardCharsets.UTF_8),
                    true);
        }
    }

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) throws Throwable {
        XposedBridge.log("SampleZeroModule Xposed entry active for: " + lpparam.packageName);

        // Example: Hook android.app.Activity#onResume via XposedHelpers if enabled
        XposedHelpers.findAndHookMethod(
                "android.app.Activity",
                lpparam.classLoader,
                "onResume",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        XposedBridge.log("Guest Activity resumed: " + param.thisObject.getClass().getName());
                    }
                });
    }
}
