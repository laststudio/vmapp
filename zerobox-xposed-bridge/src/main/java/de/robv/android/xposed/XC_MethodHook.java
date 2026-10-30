package de.robv.android.xposed;

import java.lang.reflect.Member;

/**
 * Standard Xposed/LSPosed method hook callback base class.
 * Bridged onto ZeroBox's {@link com.zerobox.sdk.hook.ZeroHook} inside an isolated sibling ClassLoader.
 */
public abstract class XC_MethodHook {

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public static final class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;
        private Object result;
        private Throwable throwable;
        private boolean returnEarly;

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
            this.throwable = null;
            this.returnEarly = true;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public boolean hasThrowable() {
            return throwable != null;
        }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.result = null;
            this.returnEarly = true;
        }

        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) {
                throw throwable;
            }
            return result;
        }

        boolean isReturnEarly() {
            return returnEarly;
        }

        void setNormalResult(Object res, Throwable t) {
            this.result = res;
            this.throwable = t;
        }
    }

    public class Unhook {
        private final Member hookMethod;
        private final com.zerobox.sdk.hook.ZeroHook.Unhook delegate;

        public Unhook(Member hookMethod, com.zerobox.sdk.hook.ZeroHook.Unhook delegate) {
            this.hookMethod = hookMethod;
            this.delegate = delegate;
        }

        public Member getHookedMethod() {
            return hookMethod;
        }

        public XC_MethodHook getCallback() {
            return XC_MethodHook.this;
        }

        public void unhook() {
            if (delegate != null) {
                delegate.unhook();
            }
        }
    }
}
