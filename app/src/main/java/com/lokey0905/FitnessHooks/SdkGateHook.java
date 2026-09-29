package com.lokey0905.FitnessHooks;

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
                "isSupported"
        );
        installed += hookBooleanGate(
                classLoader,
                "com.nianticproject.ichigo.fitness.FitnessManager",
                "osSupportsHealthConnect"
        );
        return installed;
    }

    private static int hookBooleanGate(
            ClassLoader classLoader,
            String className,
            String methodName
    ) {
        try {
            Class<?> targetClass = XposedHelpers.findClassIfExists(className, classLoader);
            if (targetClass == null) {
                FitnessHooksEntry.log("class not found: " + className);
                return 0;
            }

            Method method = targetClass.getDeclaredMethod(methodName);
            method.setAccessible(true);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    HookConfig.Snapshot config = HookConfig.readFromTarget(classLoader);
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
