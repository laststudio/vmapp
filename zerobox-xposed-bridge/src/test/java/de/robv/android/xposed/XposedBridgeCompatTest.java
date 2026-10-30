package de.robv.android.xposed;

import static org.junit.Assert.assertEquals;

import com.zerobox.sdk.hook.ZeroHook;
import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class XposedBridgeCompatTest {

    public static class DummyGuestClass {
        private int secretValue = 42;

        public int computeScore(int bonus) {
            return secretValue + bonus;
        }
    }

    @Test
    public void testXposedHelpersAndBridgeCompatibility() throws Throwable {
        final List<ZeroHook.MethodCallback> registeredCallbacks = new ArrayList<>();

        ZeroHook mockEngine = new ZeroHook() {
            @Override
            public Unhook hookMethod(final Member target, final MethodCallback callback) {
                registeredCallbacks.add(callback);
                return new Unhook() {
                    @Override
                    public Member getHookedMethod() {
                        return target;
                    }

                    @Override
                    public void unhook() {
                        registeredCallbacks.remove(callback);
                    }
                };
            }

            @Override
            public Object invokeOriginal(Member target, Object thisObject, Object... args) throws Throwable {
                if (target instanceof Method) {
                    return ((Method) target).invoke(thisObject, args);
                }
                if (target instanceof Constructor<?>) {
                    return ((Constructor<?>) target).newInstance(args);
                }
                return null;
            }

            @Override
            public void registerSyscallCallback(int syscallNumber, SyscallCallback callback) {}

            @Override
            public boolean hookNativePlt(
                    String libraryName,
                    String symbolName,
                    long replacementFnPtr,
                    long[] originalFnPtrOut) {
                return false;
            }
        };

        XposedBridge.bindHookEngine(mockEngine);

        DummyGuestClass guest = new DummyGuestClass();
        assertEquals(42, XposedHelpers.getIntField(guest, "secretValue"));
        XposedHelpers.setIntField(guest, "secretValue", 100);
        assertEquals(100, XposedHelpers.getIntField(guest, "secretValue"));

        XC_MethodHook.Unhook unhook = XposedHelpers.findAndHookMethod(
                DummyGuestClass.class,
                "computeScore",
                int.class,
                XC_MethodReplacement.returnConstant(9999));

        assertEquals(1, registeredCallbacks.size());
        Method m = DummyGuestClass.class.getDeclaredMethod("computeScore", int.class);
        ZeroHook.HookFrame frame = new ZeroHook.HookFrame(m, guest, new Object[] {10});
        registeredCallbacks.get(0).beforeCall(frame);
        assertEquals(true, frame.isReturnEarly());
        assertEquals(9999, frame.getResult());

        unhook.unhook();
        assertEquals(0, registeredCallbacks.size());
    }
}
