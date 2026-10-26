package com.lokey0905.FitnessHooks;

import android.content.Context;
import android.os.Build;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class SdkGateHook {
    private static final AtomicBoolean BYPASS_LOGGED = new AtomicBoolean(false);

    private SdkGateHook() {
    }

    static int install(ClassLoader classLoader) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            FitnessHooksEntry.log(
                    "SDK gate hook not needed on Android " + Build.VERSION.SDK_INT
            );
            return 0;
        }

        int installed = 0;
        installed += hookBooleanGate(
                classLoader,
                "com.nianticproject.ichigo.fitness.FitnessClientHealthConnect",
                "isSupported",
                false
        );
        installed += hookBooleanGate(
                classLoader,
                "com.nianticproject.ichigo.fitness.FitnessManager",
                "osSupportsHealthConnect",
                false
        );
        return installed;
    }

    static int installSelf(ClassLoader classLoader) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return 0;
        }
        String probe = "com.lokey0905.FitnessHooks.PikminHealthConnectProbe";
        return hookBooleanGate(classLoader, probe, "isSupported", true)
                + hookBooleanGate(classLoader, probe, "osSupportsHealthConnect", true);
    }

    private static int hookBooleanGate(
            ClassLoader classLoader,
            String className,
            String methodName,
            boolean self
    ) {
        try {
            Class<?> targetClass = XposedHelpers.findClassIfExists(className, classLoader);
            if (targetClass == null) {
                FitnessHooksEntry.log("class not found: " + className);
                return 0;
            }

            Method method = targetClass.getDeclaredMethod(methodName);
            method.setAccessible(true);
            Method contextGetter = self ? targetClass.getDeclaredMethod("getContext") : null;
            if (contextGetter != null) {
                contextGetter.setAccessible(true);
            }
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    HookConfig.Snapshot config = self
                            ? ConfigTransport.readModuleSettings((Context) contextGetter.invoke(param.thisObject))
                            : HookConfig.readFromTarget(classLoader);
                    if (!config.sdkHookEnabled) {
                        return;
                    }
                    param.setResult(Boolean.TRUE);
                    if (BYPASS_LOGGED.compareAndSet(false, true)) {
                        FitnessHooksEntry.log(
                                "SDK gate enabled through " + className + "." + methodName + "()"
                        );
                    }
                }
            });
            FitnessHooksEntry.log("SDK hook installed: " + className + "." + methodName);
            return 1;
        } catch (Throwable throwable) {
            FitnessHooksEntry.log(
                    "SDK hook failed: " + className + "." + methodName + ": " + throwable
            );
            XposedBridge.log(throwable);
            return 0;
        }
    }
}
