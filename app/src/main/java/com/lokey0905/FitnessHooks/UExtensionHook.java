package com.lokey0905.FitnessHooks;

import android.content.Context;
import android.os.Build;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Experimental extension floor only while one of our HC support probes is running. */
final class UExtensionHook {
    private static final int U_SDK = 34;
    private static final int MIN_U_EXTENSION = 13;
    private static final AtomicBoolean API_HOOK_INSTALLED = new AtomicBoolean(false);
    private static final Set<Method> SCOPED_METHODS = ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<ArrayDeque<Scope>> SCOPES = new ThreadLocal<>();

    private UExtensionHook() {
    }

    static int install(ClassLoader classLoader) {
        return install(classLoader,
                "com.nianticproject.ichigo.fitness.FitnessClientHealthConnect", false);
    }

    static int installSelf(ClassLoader classLoader) {
        return install(classLoader, "com.lokey0905.FitnessHooks.PikminHealthConnectProbe", true);
    }

    private static int install(ClassLoader classLoader, String className, boolean self) {
        if (Build.VERSION.SDK_INT < U_SDK) {
            return 0;
        }
        try {
            Class<?> gateClass = XposedHelpers.findClassIfExists(className, classLoader);
            if (gateClass == null) {
                FitnessHooksEntry.log("U Extension scope class not found: " + className);
                return 0;
            }
            Method gate = gateClass.getDeclaredMethod("isSupported");
            gate.setAccessible(true);
            Method contextGetter = self ? gateClass.getDeclaredMethod("getContext") : null;
            if (contextGetter != null) {
                contextGetter.setAccessible(true);
            }
            Method api = Class.forName("android.os.ext.SdkExtensions", false, classLoader)
                    .getDeclaredMethod("getExtensionVersion", int.class);
            int installed = installApi(api);
            if (!SCOPED_METHODS.add(gate)) {
                return installed;
            }
            try {
                XposedBridge.hookMethod(gate, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        ArrayDeque<Scope> scopes = SCOPES.get();
                        if (scopes == null) {
                            scopes = new ArrayDeque<>();
                            SCOPES.set(scopes);
                        }
                        // Start disabled so failures or nested configuration reads fail closed.
                        Scope scope = new Scope(param);
                        scopes.push(scope);
                        try {
                            HookConfig.Snapshot config = self
                                    ? ConfigTransport.readModuleSettings(
                                            (Context) contextGetter.invoke(param.thisObject))
                                    : HookConfig.readFromTarget(classLoader);
                            scope.enabled = config.uExtensionHookEnabled;
                        } catch (Throwable failure) {
                            FitnessHooksEntry.log("U Extension configuration unavailable: " + failure);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        ArrayDeque<Scope> scopes = SCOPES.get();
                        if (scopes == null) {
                            return;
                        }
                        if (!scopes.isEmpty() && scopes.peek().call == param) {
                            scopes.pop();
                        } else {
                            // Unexpected callback order must never leave an active override behind.
                            scopes.clear();
                        }
                        if (scopes.isEmpty()) {
                            SCOPES.remove();
                        }
                    }
                });
            } catch (Throwable failure) {
                SCOPED_METHODS.remove(gate);
                throw failure;
            }
            FitnessHooksEntry.log("U Extension scope installed: " + className + ".isSupported");
            return installed + 1;
        } catch (Throwable failure) {
            FitnessHooksEntry.log("U Extension hook failed: " + failure);
            XposedBridge.log(failure);
            return 0;
        }
    }

    private static int installApi(Method api) throws Throwable {
        if (!API_HOOK_INSTALLED.compareAndSet(false, true)) {
            return 0;
        }
        try {
            XposedBridge.hookMethod(api, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    ArrayDeque<Scope> scopes = SCOPES.get();
                    if (scopes == null || scopes.isEmpty() || !scopes.peek().enabled
                            || param.args == null || param.args.length != 1
                            || !(param.args[0] instanceof Integer)
                            || (Integer) param.args[0] != U_SDK) {
                        return;
                    }
                    Object result = param.getResult();
                    // A failed API call has no Integer result; preserve its original exception.
                    if (result instanceof Integer && (Integer) result >= 0
                            && (Integer) result < MIN_U_EXTENSION) {
                        param.setResult(MIN_U_EXTENSION);
                    }
                }
            });
            FitnessHooksEntry.log("U Extension API hook installed (HC support scopes only)");
            return 1;
        } catch (Throwable failure) {
            API_HOOK_INSTALLED.set(false);
            throw failure;
        }
    }

    private static final class Scope {
        final Object call;
        boolean enabled;

        Scope(Object call) {
            this.call = call;
        }
    }
}
