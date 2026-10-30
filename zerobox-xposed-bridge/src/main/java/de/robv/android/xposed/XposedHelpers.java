package de.robv.android.xposed;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Standard {@code XposedHelpers} reflection and hooking utility class for LSPosed/Xposed module compatibility.
 */
public final class XposedHelpers {

    public static final class ClassNotFoundError extends Error {
        public ClassNotFoundError(Throwable cause) {
            super(cause);
        }

        public ClassNotFoundError(String detailMessage, Throwable cause) {
            super(detailMessage, cause);
        }
    }

    private XposedHelpers() {}

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        if (classLoader == null) {
            classLoader = XposedHelpers.class.getClassLoader();
        }
        try {
            return Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException e) {
            throw new ClassNotFoundError(e);
        }
    }

    public static Class<?> findClassIfExists(String className, ClassLoader classLoader) {
        try {
            return findClass(className, classLoader);
        } catch (ClassNotFoundError ignored) {
            return null;
        }
    }

    public static Method findMethodExact(Class<?> clazz, String methodName, Object... parameterTypes) {
        Class<?>[] paramClasses = resolveParameterClasses(clazz.getClassLoader(), parameterTypes);
        try {
            Method method = clazz.getDeclaredMethod(methodName, paramClasses);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            throw new NoSuchMethodError(clazz.getName() + "#" + methodName);
        }
    }

    public static Constructor<?> findConstructorExact(Class<?> clazz, Object... parameterTypes) {
        Class<?>[] paramClasses = resolveParameterClasses(clazz.getClassLoader(), parameterTypes);
        try {
            Constructor<?> constructor = clazz.getDeclaredConstructor(paramClasses);
            constructor.setAccessible(true);
            return constructor;
        } catch (NoSuchMethodException e) {
            throw new NoSuchMethodError(clazz.getName() + "#<init>");
        }
    }

    public static XC_MethodHook.Unhook findAndHookMethod(
            Class<?> clazz,
            String methodName,
            Object... parameterTypesAndCallback) {
        if (parameterTypesAndCallback.length == 0
                || !(parameterTypesAndCallback[parameterTypesAndCallback.length - 1] instanceof XC_MethodHook)) {
            throw new IllegalArgumentException("No XC_MethodHook callback defined");
        }
        XC_MethodHook callback =
                (XC_MethodHook) parameterTypesAndCallback[parameterTypesAndCallback.length - 1];
        Object[] paramTypes = Arrays.copyOf(
                parameterTypesAndCallback,
                parameterTypesAndCallback.length - 1);
        Method m = findMethodExact(clazz, methodName, paramTypes);
        return XposedBridge.hookMethod(m, callback);
    }

    public static XC_MethodHook.Unhook findAndHookMethod(
            String className,
            ClassLoader classLoader,
            String methodName,
            Object... parameterTypesAndCallback) {
        return findAndHookMethod(
                findClass(className, classLoader),
                methodName,
                parameterTypesAndCallback);
    }

    public static XC_MethodHook.Unhook findAndHookConstructor(
            Class<?> clazz,
            Object... parameterTypesAndCallback) {
        if (parameterTypesAndCallback.length == 0
                || !(parameterTypesAndCallback[parameterTypesAndCallback.length - 1] instanceof XC_MethodHook)) {
            throw new IllegalArgumentException("No XC_MethodHook callback defined");
        }
        XC_MethodHook callback =
                (XC_MethodHook) parameterTypesAndCallback[parameterTypesAndCallback.length - 1];
        Object[] paramTypes = Arrays.copyOf(
                parameterTypesAndCallback,
                parameterTypesAndCallback.length - 1);
        Constructor<?> c = findConstructorExact(clazz, paramTypes);
        return XposedBridge.hookMethod(c, callback);
    }

    public static Field findField(Class<?> clazz, String fieldName) {
        Class<?> curr = clazz;
        while (curr != null) {
            try {
                Field f = curr.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                curr = curr.getSuperclass();
            }
        }
        throw new NoSuchFieldError(clazz.getName() + "#" + fieldName);
    }

    public static Object getObjectField(Object obj, String fieldName) {
        try {
            return findField(obj.getClass(), fieldName).get(obj);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
        try {
            findField(obj.getClass(), fieldName).set(obj, value);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static int getIntField(Object obj, String fieldName) {
        try {
            return findField(obj.getClass(), fieldName).getInt(obj);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static void setIntField(Object obj, String fieldName, int value) {
        try {
            findField(obj.getClass(), fieldName).setInt(obj, value);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static Object getStaticObjectField(Class<?> clazz, String fieldName) {
        try {
            return findField(clazz, fieldName).get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static void setStaticObjectField(Class<?> clazz, String fieldName, Object value) {
        try {
            findField(clazz, fieldName).set(null, value);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        try {
            Method m = findMatchingMethod(obj.getClass(), methodName, args);
            return m.invoke(obj, args);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e.getTargetException());
        }
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        try {
            Method m = findMatchingMethod(clazz, methodName, args);
            return m.invoke(null, args);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e.getTargetException());
        }
    }

    private static Method findMatchingMethod(Class<?> clazz, String methodName, Object[] args) {
        int argCount = args != null ? args.length : 0;
        Class<?> curr = clazz;
        while (curr != null) {
            for (Method m : curr.getDeclaredMethods()) {
                if (m.getName().equals(methodName) && m.getParameterTypes().length == argCount) {
                    m.setAccessible(true);
                    return m;
                }
            }
            curr = curr.getSuperclass();
        }
        throw new NoSuchMethodError(clazz.getName() + "#" + methodName);
    }

    private static Class<?>[] resolveParameterClasses(ClassLoader classLoader, Object[] parameterTypes) {
        if (parameterTypes == null || parameterTypes.length == 0) {
            return new Class<?>[0];
        }
        Class<?>[] classes = new Class<?>[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            Object type = parameterTypes[i];
            if (type instanceof Class<?>) {
                classes[i] = (Class<?>) type;
            } else if (type instanceof String) {
                classes[i] = findClass((String) type, classLoader);
            } else {
                throw new IllegalArgumentException("Parameter type must be Class or String: " + type);
            }
        }
        return classes;
    }
}
