package de.robv.android.xposed;

/**
 * Convenience subclass of {@link XC_MethodHook} that replaces the entire method execution.
 */
public abstract class XC_MethodReplacement extends XC_MethodHook {

    public static final XC_MethodReplacement DO_NOTHING = new XC_MethodReplacement() {
        @Override
        protected Object replaceHookedMethod(MethodHookParam param) {
            return null;
        }
    };

    public static XC_MethodReplacement returnConstant(final Object result) {
        return new XC_MethodReplacement() {
            @Override
            protected Object replaceHookedMethod(MethodHookParam param) {
                return result;
            }
        };
    }

    @Override
    protected final void beforeHookedMethod(MethodHookParam param) throws Throwable {
        try {
            Object result = replaceHookedMethod(param);
            param.setResult(result);
        } catch (Throwable t) {
            param.setThrowable(t);
        }
    }

    @Override
    protected final void afterHookedMethod(MethodHookParam param) throws Throwable {}

    protected abstract Object replaceHookedMethod(MethodHookParam param) throws Throwable;
}
