package com.lokey0905.FitnessHooks;

import android.content.Context;
import android.os.Build;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Restores the APK provider's own route when its inspected checker shape still matches. */
final class StandaloneProviderHook {
    private static final String CHECKER_CLASS = "cvv";
    private static final Set<Method> INSTALLED = ConcurrentHashMap.newKeySet();

    private StandaloneProviderHook() {
    }

    static synchronized int install(ClassLoader classLoader) {
        if (Build.VERSION.SDK_INT < 34) return 0;
        try {
            Class<?> checker = XposedHelpers.findClassIfExists(CHECKER_CLASS, classLoader);
            if (checker == null) throw new ClassNotFoundException(CHECKER_CLASS);
            Method[] gates = validateChecker(checker);
            if (INSTALLED.contains(gates[0]) || INSTALLED.contains(gates[1])) return 0;

            ProviderState state = new ProviderState();
            for (Method gate : gates) {
                gate.setAccessible(true);
                XposedBridge.hookMethod(gate, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!state.isEnabled()) return;
                        // These are only the APK-disable/migration gates, not permission grants.
                        param.setResult(Boolean.FALSE);
                        if (state.activationLogged.compareAndSet(false, true)) {
                            FitnessHooksEntry.log("standalone HC provider route active: cvv.a / cvv.b");
                        }
                    }
                });
            }
            INSTALLED.add(gates[0]);
            INSTALLED.add(gates[1]);
            // A failed second installation must not leave only one migration gate overridden.
            state.ready = true;
            FitnessHooksEntry.log("standalone HC provider hooks installed: 2 (cvv.a / cvv.b)");
            return 2;
        } catch (Throwable failure) {
            FitnessHooksEntry.log("standalone HC provider hooks unavailable: " + failure);
            return 0;
        }
    }

    private static Method[] validateChecker(Class<?> checker) throws ReflectiveOperationException {
        if (!Modifier.isPublic(checker.getModifiers()) || !Modifier.isFinal(checker.getModifiers())
                || checker.getSuperclass() != Object.class || checker.getInterfaces().length != 0) {
            throw new NoSuchMethodException("unexpected MigrationFeatureChecker class shape");
        }
        Constructor<?> constructor = checker.getDeclaredConstructor(boolean.class, boolean.class);
        if (!Modifier.isPublic(constructor.getModifiers()) || checker.getDeclaredConstructors().length != 1) {
            throw new NoSuchMethodException("unexpected MigrationFeatureChecker constructor");
        }
        int fields = 0;
        for (Field field : checker.getDeclaredFields()) {
            if (!field.isSynthetic()) fields++;
        }
        if (fields != 3) throw new NoSuchFieldException("unexpected MigrationFeatureChecker fields");
        for (String name : new String[]{"b", "c"}) {
            Field field = checker.getDeclaredField(name);
            int modifiers = field.getModifiers();
            if (field.getType() != boolean.class || !Modifier.isPrivate(modifiers)
                    || !Modifier.isFinal(modifiers) || Modifier.isStatic(modifiers)) {
                throw new NoSuchFieldException("unexpected MigrationFeatureChecker field: " + name);
            }
        }
        Field logger = checker.getDeclaredField("a");
        int loggerModifiers = logger.getModifiers();
        if (!Modifier.isPrivate(loggerModifiers) || !Modifier.isStatic(loggerModifiers)
                || !Modifier.isFinal(loggerModifiers) || logger.getType().isPrimitive()
                || logger.getType().isArray()) {
            throw new NoSuchFieldException("unexpected MigrationFeatureChecker logger");
        }
        int methods = 0;
        for (Method method : checker.getDeclaredMethods()) {
            if (!method.isSynthetic()) methods++;
        }
        if (methods != 2) throw new NoSuchMethodException("unexpected MigrationFeatureChecker methods");
        Method[] gates = {checker.getDeclaredMethod("a"), checker.getDeclaredMethod("b")};
        for (Method method : gates) {
            int modifiers = method.getModifiers();
            if (method.getReturnType() != boolean.class || !Modifier.isPublic(modifiers)
                    || !Modifier.isFinal(modifiers) || Modifier.isStatic(modifiers)) {
                throw new NoSuchMethodException("unexpected MigrationFeatureChecker gate: " + method.getName());
            }
        }
        return gates;
    }

    private static final class ProviderState {
        private volatile boolean ready;
        private final AtomicBoolean activationLogged = new AtomicBoolean();
        private final AtomicBoolean failureLogged = new AtomicBoolean();

        boolean isEnabled() {
            if (!ready || Build.VERSION.SDK_INT < 34) return false;
            Context context = TargetConfigBridge.getApplicationContext();
            if (context == null) return false;
            try {
                if (!StandaloneHealthConnect.PROVIDER_PACKAGE.equals(context.getPackageName())) return false;
                return TargetConfigBridge.isStandaloneProviderEnabled();
            } catch (Throwable failure) {
                if (failureLogged.compareAndSet(false, true)) {
                    FitnessHooksEntry.log("standalone HC provider check failed; preserving original route: " + failure);
                }
                return false;
            }
        }
    }
}
