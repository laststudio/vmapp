package de.robv.android.xposed;

import android.util.Log;
import com.zerobox.sdk.hook.ZeroHook;
import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * LSPosed / Xposed compatible {@code XposedBridge} implementation backed by ZeroBox's {@link ZeroHook}.
 * Loaded strictly inside the sibling module ClassLoader so the guest application cannot detect
 * {@code de.robv.android.xposed.XposedBridge} via {@code Class.forName}.
 */
public final class XposedBridge {

    public static final int XPOSED_BRIDGE_VERSION = 93;
    private static final String TAG = "ZeroBox-XposedBridge";
    private static volatile ZeroHook sHookEngine;

    private XposedBridge() {}

    public static void bindHookEngine(ZeroHook hookEngine) {
        sHookEngine = hookEngine;
    }

    public static int getXposedVersion() {
        return XPOSED_BRIDGE_VERSION;
    }

    public static synchronized void log(String text) {
        Log.i(TAG, text != null ? text : "null");
    }

    public static synchronized void log(Throwable t) {
        Log.e(TAG, Log.getStackTraceString(t));
    }

    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, final XC_MethodHook callback) {
        if (hookMethod == null || callback == null) {
            throw new IllegalArgumentException("hookMethod and callback must not be null");
        }
        if (!(hookMethod instanceof Method) && !(hookMethod instanceof Constructor<?>)) {
            throw new IllegalArgumentException("Only methods and constructors can be hooked");
        }
        ZeroHook engine = sHookEngine;
        if (engine == null) {
            throw new IllegalStateException("ZeroHook engine is not bound to XposedBridge");
        }

        ZeroHook.Unhook delegate = engine.hookMethod(hookMethod, new ZeroHook.MethodCallback() {
            @Override
            public void beforeCall(ZeroHook.HookFrame frame) throws Throwable {
                XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
                param.method = frame.method;
                param.thisObject = frame.thisObject;
                param.args = frame.args;
                callback.beforeHookedMethod(param);
                frame.thisObject = param.thisObject;
                frame.args = param.args;
                if (param.isReturnEarly()) {
                    if (param.hasThrowable()) {
                        frame.setThrowable(param.getThrowable());
                    } else {
                        frame.setResult(param.getResult());
                    }
                }
            }

            @Override
            public void afterCall(ZeroHook.HookFrame frame) throws Throwable {
                XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
                param.method = frame.method;
                param.thisObject = frame.thisObject;
                param.args = frame.args;
                param.setNormalResult(frame.getResult(), frame.getThrowable());
                callback.afterHookedMethod(param);
                if (param.hasThrowable()) {
                    frame.setNormalThrowable(param.getThrowable());
                } else {
                    frame.setNormalResult(param.getResult());
                }
            }
        });

        return callback.new Unhook(hookMethod, delegate);
    }

    public static Set<XC_MethodHook.Unhook> hookAllMethods(
            Class<?> hookClass,
            String methodName,
            XC_MethodHook callback) {
        Set<XC_MethodHook.Unhook> unhooks = new HashSet<>();
        for (Method method : hookClass.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                unhooks.add(hookMethod(method, callback));
            }
        }
        return unhooks;
    }

    public static Set<XC_MethodHook.Unhook> hookAllConstructors(
            Class<?> hookClass,
            XC_MethodHook callback) {
        Set<XC_MethodHook.Unhook> unhooks = new HashSet<>();
        for (Constructor<?> constructor : hookClass.getDeclaredConstructors()) {
            unhooks.add(hookMethod(constructor, callback));
        }
        return unhooks;
    }

    public static Object invokeOriginalMethod(
            Member method,
            Object thisObject,
            Object[] args) throws Throwable {
        ZeroHook engine = sHookEngine;
        if (engine == null) {
            throw new IllegalStateException("ZeroHook engine is not bound");
        }
        return engine.invokeOriginal(method, thisObject, args);
    }
}
