package com.zerobox.runtime.hook;

import com.zerobox.runtime.nativebridge.ZeroNativeBridge;
import com.zerobox.sdk.hook.ZeroHook;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lazy-initialized stealth hook engine implementing {@link ZeroHook}.
 *
 * <p>Key design property ("Hiding > Confrontation"):
 * If {@code PassthroughConfig.isEnableJavaHookEngine()} is false for a target app,
 * this engine remains completely dormant and never touches ART method structs.
 * When active on Android ART, it uses {@link ZeroNativeBridge#nativeInstallArtHook}
 * to redirect the target {@code ArtMethod} entry point through a self-calibrated
 * native trampoline.
 */
public final class ZeroHookImpl implements ZeroHook {

    private static final ZeroHookImpl INSTANCE = new ZeroHookImpl();

    public static ZeroHookImpl getInstance() {
        return INSTANCE;
    }

    private final Map<Member, CopyOnWriteArrayList<MethodCallback>> hookedMethods =
            new ConcurrentHashMap<>();
    private final Map<Member, Integer> memberToSlotMap = new ConcurrentHashMap<>();
    private final Map<Integer, Member> slotToMemberMap = new ConcurrentHashMap<>();
    private final Map<Integer, CopyOnWriteArrayList<SyscallCallback>> syscallCallbacks =
            new ConcurrentHashMap<>();
    private volatile boolean initialized = false;

    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public Unhook hookMethod(final Member target, final MethodCallback callback) {
        if (target == null || callback == null) {
            throw new IllegalArgumentException("Target member and callback must not be null");
        }
        this.initialized = true;
        if (target instanceof AccessibleObject) {
            ((AccessibleObject) target).setAccessible(true);
        }
        final CopyOnWriteArrayList<MethodCallback> list =
                hookedMethods.computeIfAbsent(target, k -> new CopyOnWriteArrayList<>());
        list.add(callback);

        // Install native ART ArtMethod hook if running on Android device/emulator
        if (ZeroNativeBridge.isNativeAvailable() && !memberToSlotMap.containsKey(target)) {
            boolean isStatic = Modifier.isStatic(target.getModifiers());
            String shorty = computeShorty(target);
            int slot = ZeroNativeBridge.nativeInstallArtHook(target, isStatic, shorty);
            if (slot >= 0) {
                memberToSlotMap.put(target, slot);
                slotToMemberMap.put(slot, target);
            }
        }

        return new Unhook() {
            @Override
            public Member getHookedMethod() {
                return target;
            }

            @Override
            public void unhook() {
                list.remove(callback);
                if (list.isEmpty()) {
                    Integer slot = memberToSlotMap.remove(target);
                    if (slot != null) {
                        slotToMemberMap.remove(slot);
                        if (ZeroNativeBridge.isNativeAvailable()) {
                            ZeroNativeBridge.nativeRestoreArtHook(slot);
                        }
                    }
                }
            }
        };
    }

    /**
     * Computes the DEX shorty descriptor for a {@link Method} or {@link Constructor}
     * (e.g. {@code "LLI"} for {@code String foo(String, int)}).
     */
    public static String computeShorty(Member target) {
        StringBuilder sb = new StringBuilder(9);
        Class<?>[] paramTypes;
        if (target instanceof Method) {
            Method m = (Method) target;
            sb.append(typeToShortyChar(m.getReturnType()));
            paramTypes = m.getParameterTypes();
        } else if (target instanceof Constructor<?>) {
            Constructor<?> c = (Constructor<?>) target;
            sb.append('V');
            paramTypes = c.getParameterTypes();
        } else {
            return "V";
        }
        for (Class<?> pt : paramTypes) {
            sb.append(typeToShortyChar(pt));
        }
        return sb.toString();
    }

    private static char typeToShortyChar(Class<?> type) {
        if (type == void.class) return 'V';
        if (type == boolean.class) return 'Z';
        if (type == byte.class) return 'B';
        if (type == char.class) return 'C';
        if (type == short.class) return 'S';
        if (type == int.class) return 'I';
        if (type == long.class) return 'J';
        if (type == float.class) return 'F';
        if (type == double.class) return 'D';
        return 'L';
    }

    /**
     * Invoked from C++ {@code NativeHookTrampolineStub<N>} when a hooked ART method executes.
     * Supports both primitive and Object parameters (up to 8 arguments) and Object/primitive return values.
     */
    public long handleNativeArtTrampoline(
            int slotIndex,
            Object receiverOrClass,
            long[] rawArgs,
            Object[] objArgs,
            Object[] outObjReturn)
            throws Throwable {
        Member target = slotToMemberMap.get(slotIndex);
        if (target == null) {
            return 0L;
        }
        boolean isStatic = Modifier.isStatic(target.getModifiers());
        Object thisObj = isStatic ? null : receiverOrClass;
        Object[] boxedArgs = unboxMixedArgs(target, rawArgs, objArgs);
        Object res = dispatchHookedCall(target, thisObj, boxedArgs);
        if (target instanceof Method) {
            Class<?> retType = ((Method) target).getReturnType();
            if (!retType.isPrimitive() && retType != void.class) {
                if (outObjReturn != null && outObjReturn.length > 0) {
                    outObjReturn[0] = res;
                }
                return 0L;
            }
        }
        return boxPrimitiveReturn(target, res);
    }

    /**
     * Backward-compatible overload for unit tests testing primitive-only dispatch.
     */
    public long handleNativeArtTrampoline(int slotIndex, Object receiverOrClass, long[] rawArgs)
            throws Throwable {
        return handleNativeArtTrampoline(slotIndex, receiverOrClass, rawArgs, null, null);
    }

    private static Object[] unboxMixedArgs(Member target, long[] rawArgs, Object[] objArgs) {
        Class<?>[] paramTypes;
        if (target instanceof Method) {
            paramTypes = ((Method) target).getParameterTypes();
        } else if (target instanceof Constructor<?>) {
            paramTypes = ((Constructor<?>) target).getParameterTypes();
        } else {
            return new Object[0];
        }
        Object[] out = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            Class<?> pt = paramTypes[i];
            if (!pt.isPrimitive()) {
                out[i] = (objArgs != null && i < objArgs.length) ? objArgs[i] : null;
                continue;
            }
            long raw = (rawArgs != null && i < rawArgs.length) ? rawArgs[i] : 0L;
            if (pt == int.class) {
                out[i] = (int) raw;
            } else if (pt == long.class) {
                out[i] = raw;
            } else if (pt == boolean.class) {
                out[i] = (raw != 0L);
            } else if (pt == byte.class) {
                out[i] = (byte) raw;
            } else if (pt == short.class) {
                out[i] = (short) raw;
            } else if (pt == char.class) {
                out[i] = (char) raw;
            } else if (pt == float.class) {
                out[i] = Float.intBitsToFloat((int) raw);
            } else if (pt == double.class) {
                out[i] = Double.longBitsToDouble(raw);
            }
        }
        return out;
    }

    private static long boxPrimitiveReturn(Member target, Object res) {
        if (res == null) {
            return 0L;
        }
        if (res instanceof Boolean) {
            return ((Boolean) res) ? 1L : 0L;
        }
        if (res instanceof Character) {
            return (char) res;
        }
        if (res instanceof Float) {
            return Float.floatToRawIntBits((Float) res);
        }
        if (res instanceof Double) {
            return Double.doubleToRawLongBits((Double) res);
        }
        if (res instanceof Number) {
            return ((Number) res).longValue();
        }
        return 0L;
    }

    /**
     * Dispatches a hooked method call through all registered before/after callbacks.
     * Used by both the native ART trampoline and the Xposed compatibility bridge.
     */
    public Object dispatchHookedCall(Member target, Object thisObject, Object[] args) throws Throwable {
        List<MethodCallback> callbacks = hookedMethods.get(target);
        if (callbacks == null || callbacks.isEmpty()) {
            return invokeOriginal(target, thisObject, args);
        }

        HookFrame frame = new HookFrame(target, thisObject, args);
        MethodCallback[] snapshot = callbacks.toArray(new MethodCallback[0]);
        int index = 0;

        // 1. Run beforeCall in registration order
        for (; index < snapshot.length; index++) {
            try {
                snapshot[index].beforeCall(frame);
            } catch (Throwable t) {
                frame.setNormalThrowable(t);
            }
            if (frame.isReturnEarly()) {
                index++;
                break;
            }
        }

        // 2. Invoke original method if no beforeCall short-circuited
        if (!frame.isReturnEarly()) {
            try {
                Object res = invokeOriginal(target, frame.thisObject, frame.args);
                frame.setNormalResult(res);
            } catch (Throwable t) {
                frame.setNormalThrowable(t);
            }
        }

        // 3. Run afterCall in reverse order
        for (int i = index - 1; i >= 0; i--) {
            Object prevResult = frame.getResult();
            Throwable prevThrowable = frame.getThrowable();
            try {
                snapshot[i].afterCall(frame);
            } catch (Throwable t) {
                if (prevThrowable == null) {
                    frame.setNormalThrowable(t);
                } else {
                    frame.setNormalResult(prevResult);
                    frame.setNormalThrowable(prevThrowable);
                }
            }
        }

        if (frame.getThrowable() != null) {
            throw frame.getThrowable();
        }
        return frame.getResult();
    }

    @Override
    public synchronized Object invokeOriginal(Member target, Object thisObject, Object... args)
            throws Throwable {
        Integer slot = memberToSlotMap.get(target);
        byte[] savedHookedBytes = null;
        if (slot != null && ZeroNativeBridge.isNativeAvailable()) {
            savedHookedBytes = ZeroNativeBridge.nativeSwapOriginalArtMethod(slot);
        }
        try {
            if (target instanceof Method) {
                Method m = (Method) target;
                m.setAccessible(true);
                return m.invoke(thisObject, args);
            } else if (target instanceof Constructor<?>) {
                Constructor<?> c = (Constructor<?>) target;
                c.setAccessible(true);
                return c.newInstance(args);
            }
            throw new IllegalArgumentException("Unsupported Member type: " + target);
        } catch (InvocationTargetException e) {
            throw e.getTargetException() != null ? e.getTargetException() : e;
        } finally {
            if (slot != null && savedHookedBytes != null && ZeroNativeBridge.isNativeAvailable()) {
                ZeroNativeBridge.nativeReapplyArtHook(slot, savedHookedBytes);
            }
        }
    }

    @Override
    public void registerSyscallCallback(int syscallNumber, SyscallCallback callback) {
        if (callback == null) return;
        syscallCallbacks
                .computeIfAbsent(syscallNumber, k -> new CopyOnWriteArrayList<>())
                .add(callback);
    }

    @Override
    public boolean hookNativePlt(
            String libraryName,
            String symbolName,
            long replacementFnPtr,
            long[] originalFnPtrOut) {
        if (!ZeroNativeBridge.isNativeAvailable()) {
            return false;
        }
        return ZeroNativeBridge.nativeHookPlt(
                libraryName != null ? libraryName : "",
                symbolName,
                replacementFnPtr,
                originalFnPtrOut);
    }

    public boolean isMethodHooked(Member target) {
        List<MethodCallback> list = hookedMethods.get(target);
        return list != null && !list.isEmpty();
    }
}
