package com.zerobox.sdk.hook;

import java.lang.reflect.Member;

/**
 * Stealth Java Method & Native Syscall Hook API.
 * Designed for zero ClassLoader pollution and lazy initialization (only activates when a module
 * actually registers a hook).
 */
public interface ZeroHook {

    /**
     * Mutable invocation frame passed to {@link MethodCallback#beforeCall(HookFrame)}
     * and {@link MethodCallback#afterCall(HookFrame)}.
     */
    final class HookFrame {
        public final Member method;
        public Object thisObject;
        public Object[] args;
        private Object result;
        private Throwable throwable;
        private boolean returnEarly;

        public HookFrame(Member method, Object thisObject, Object[] args) {
            this.method = method;
            this.thisObject = thisObject;
            this.args = args != null ? args : new Object[0];
        }

        public Object getResult() {
            return result;
        }

        /**
         * Sets the return value. If called inside {@code beforeCall}, skips the original method execution.
         */
        public void setResult(Object result) {
            this.result = result;
            this.throwable = null;
            this.returnEarly = true;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.result = null;
            this.returnEarly = true;
        }

        public boolean isReturnEarly() {
            return returnEarly;
        }

        public void setNormalResult(Object result) {
            this.result = result;
            this.throwable = null;
        }

        public void setNormalThrowable(Throwable t) {
            this.throwable = t;
            this.result = null;
        }
    }

    abstract class MethodCallback {
        public void beforeCall(HookFrame frame) throws Throwable {}

        public void afterCall(HookFrame frame) throws Throwable {}
    }

    interface Unhook {
        Member getHookedMethod();

        void unhook();
    }

    interface SyscallCallback {
        /**
         * Invoked when the Seccomp-BPF trap catches the subscribed syscall number.
         *
         * @param syscallNumber Raw architecture syscall number
         * @param args          Up to 6 syscall register arguments
         * @return Modified first argument (or original args[0])
         */
        long onSyscall(int syscallNumber, long[] args);
    }

    /**
     * Hooks a Java {@link java.lang.reflect.Method} or {@link java.lang.reflect.Constructor}.
     */
    Unhook hookMethod(Member target, MethodCallback callback);

    /**
     * Invokes the original unhooked method bypassing active callbacks.
     */
    Object invokeOriginal(Member target, Object thisObject, Object... args) throws Throwable;

    /**
     * Subscribes to a Linux syscall trapped by the Seccomp-BPF kernel filter.
     */
    void registerSyscallCallback(int syscallNumber, SyscallCallback callback);

    /**
     * Hooks a native ELF symbol via PLT/GOT table redirection without modifying .text code pages.
     */
    boolean hookNativePlt(String libraryName, String symbolName, long replacementFnPtr, long[] originalFnPtrOut);
}
