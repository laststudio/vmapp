package com.zerobox.sdk.api;

/**
 * Entry point interface for ZeroBox native-SDK modules.
 * Modules declare their implementation class name in {@code assets/zerobox_module.init}.
 * Loaded inside a sibling isolated ClassLoader so the guest APK cannot detect module classes.
 */
public interface ZeroModule {
    /**
     * Called when the target guest application is initialized inside a ZeroBox container slot,
     * prior to {@code Application.onCreate()}.
     *
     * @param context Rich execution context providing access to Memory, VFS, Hook, and Passthrough APIs.
     * @throws Throwable Any exception thrown by the module is caught and logged by the sandbox.
     */
    void onLoad(ZeroModuleContext context) throws Throwable;
}
