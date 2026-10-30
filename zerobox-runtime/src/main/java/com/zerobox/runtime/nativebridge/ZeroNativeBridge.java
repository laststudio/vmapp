package com.zerobox.runtime.nativebridge;

import com.zerobox.runtime.hook.ZeroHookImpl;
import java.lang.reflect.Member;

/**
 * JNI bridge to {@code libzerobox_core.so}.
 * Gracefully degrades on host JVM unit tests when native library is not loaded.
 */
public final class ZeroNativeBridge {

    private static volatile boolean sLibraryLoaded = false;

    static {
        try {
            System.loadLibrary("zerobox_core");
            sLibraryLoaded = true;
        } catch (Throwable ignored) {
            sLibraryLoaded = false;
        }
    }

    private ZeroNativeBridge() {}

    public static boolean isNativeAvailable() {
        return sLibraryLoaded;
    }

    // Calibration probes used by art_hook.cpp via RegisterNatives to auto-detect ArtMethod layout
    public static native void nativeCalibrationProbeA();

    public static native void nativeCalibrationProbeB();

    public static native int nativeInstallArtHook(
            Member reflectedMethod,
            boolean isStatic,
            String shorty);

    public static native boolean nativeRestoreArtHook(int slotIndex);

    public static native byte[] nativeSwapOriginalArtMethod(int slotIndex);

    public static native void nativeReapplyArtHook(int slotIndex, byte[] savedHookedBytes);

    /**
     * Upcall invoked by C++ {@code NativeHookTrampolineStub<N>} when a hooked ART method is called.
     */
    public static long onArtHookDispatched(
            int slotIndex,
            Object receiverOrClass,
            long[] rawArgs,
            Object[] objArgs,
            Object[] outObjReturn)
            throws Throwable {
        return ZeroHookImpl.getInstance()
                .handleNativeArtTrampoline(slotIndex, receiverOrClass, rawArgs, objArgs, outObjReturn);
    }

    public static native boolean nativeInitContainer(
            String hostPackage,
            String guestPackage,
            int userId,
            String isolatedDataDir,
            String originalApkPath,
            boolean enableSeccompVfs,
            boolean enableProcCloak,
            boolean enableBinderFilter,
            boolean passthroughHostPms);

    public static native void nativeAddPassthroughPath(String pathPrefix);

    public static native void nativeAddRedirectRule(String virtualPrefix, String realPrefix);

    public static native void nativeAddHiddenPath(String pathPrefix);

    public static native String nativeResolvePath(String virtualPath);

    public static native String[] nativeGetRealMemoryRegions(String filterSubstr);

    public static native int nativeReadMemory(long address, byte[] buffer, int offset, int length);

    public static native int nativeWriteMemory(long address, byte[] buffer, int offset, int length);

    public static native long[] nativeScanPattern(String moduleFilter, String hexPattern, int maxResults);

    public static native boolean nativeLoadPlugin(
            String soPath,
            String guestPackage,
            int userId,
            String dataDir);

    public static native boolean nativeHookPlt(
            String libName,
            String symbolName,
            long replacementPtr,
            long[] originalPtrOut);

    public static native long nativeGetLibcTextHash();

    public static native boolean nativeIsSeccompActive();
}
