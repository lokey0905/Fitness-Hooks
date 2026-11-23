package com.lokey0905.FitnessHooks;

import android.os.Build;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Separates only Fit's HC sync metadata; never deletes or copies health records. */
final class GoogleFitSyncStateHook {
    private static final String SUFFIX = "_fitnesshooks_hc_apk";
    private static final Set<String> STORES = Set.of("AhpConsent", "AhpDownsyncDebugInfo",
            "AhpDownsyncState", "AhpUpsyncDebugInfo", "AhpUpsyncState");
    private static final Set<String> DATABASES = Set.of("ahp_downsync_interval_queue.db", "ahp_mapping.db");
    private static final Set<Method> INSTALLED = ConcurrentHashMap.newKeySet();

    private GoogleFitSyncStateHook() {}

    static synchronized int install(ClassLoader loader) {
        if (Build.VERSION.SDK_INT < 34) return 0;
        try {
            Builder store = inspect(loader, "ppv", "ppw");
            Builder database = inspect(loader, "pqa", "pqb");
            if (INSTALLED.contains(store.build) || INSTALLED.contains(database.build)) return 0;
            Installation installation = new Installation();
            hook(store, STORES, false, installation);
            hook(database, DATABASES, true, installation);
            INSTALLED.add(store.build);
            INSTALLED.add(database.build);
            installation.ready = true;
            FitnessHooksEntry.log("Google Fit HC sync-state hooks installed: 2");
            return 2;
        } catch (Throwable failure) {
            FitnessHooksEntry.log("Google Fit HC sync-state hooks unavailable: " + failure);
            return 0;
        }
    }

    private static Builder inspect(ClassLoader loader, String builderName, String configName)
            throws ReflectiveOperationException {
        Class<?> type = XposedHelpers.findClassIfExists(builderName, loader);
        Class<?> result = XposedHelpers.findClassIfExists(configName, loader);
        if (type == null || result == null) throw new ClassNotFoundException(builderName + " / " + configName);
        Method build = type.getDeclaredMethod("a");
        Field name = type.getDeclaredField("a");
        if (!Modifier.isPublic(type.getModifiers()) || !Modifier.isFinal(type.getModifiers())
                || build.getReturnType() != result || !Modifier.isPublic(build.getModifiers())
                || !Modifier.isFinal(build.getModifiers()) || Modifier.isStatic(build.getModifiers())
                || name.getType() != String.class || !Modifier.isPublic(name.getModifiers())
                || Modifier.isStatic(name.getModifiers()) || Modifier.isFinal(name.getModifiers())) {
            throw new NoSuchMethodException("Unexpected Fit HC store-builder structure: " + builderName);
        }
        build.setAccessible(true);
        name.setAccessible(true);
        return new Builder(build, name);
    }

    private static void hook(Builder builder, Set<String> names, boolean database, Installation installation) {
        XposedBridge.hookMethod(builder.build, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!installation.ready) return;
                try {
                    String name = (String) builder.name.get(param.thisObject);
                    // Unrelated Fit stores must neither change nor capture the HC route early.
                    if (name == null || !names.contains(name) || !GoogleFitRoute.selected()) return;
                    String isolated = database
                            ? name.substring(0, name.length() - 3) + SUFFIX + ".db"
                            : name + SUFFIX;
                    builder.name.set(param.thisObject, isolated);
                    if (installation.logged.add(name)) {
                        FitnessHooksEntry.log("Google Fit standalone HC sync state: " + isolated);
                    }
                } catch (Throwable failure) {
                    // Do not open the system-provider state while the route is pending,
                    // or after selecting the APK route. Xposed can swallow callback throws.
                    param.setResult(null);
                    try {
                        param.getClass().getMethod("setThrowable", Throwable.class).invoke(param, failure);
                    } catch (ReflectiveOperationException unavailable) {
                        FitnessHooksEntry.log("Google Fit sync-state exception delivery unavailable: " + unavailable);
                    }
                    FitnessHooksEntry.log("Google Fit HC sync-state isolation failed: " + failure);
                }
            }
        });
    }

    private static final class Builder {
        final Method build;
        final Field name;

        Builder(Method build, Field name) {
            this.build = build;
            this.name = name;
        }
    }

    private static final class Installation {
        volatile boolean ready;
        final Set<String> logged = ConcurrentHashMap.newKeySet();
    }
}
