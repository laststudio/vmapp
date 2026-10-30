package de.robv.android.xposed;

import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Standard Xposed/LSPosed callback interface invoked when a guest APK is loaded inside ZeroBox.
 */
public interface IXposedHookLoadPackage extends IXposedMod {
    void handleLoadPackage(LoadPackageParam lpparam) throws Throwable;
}
