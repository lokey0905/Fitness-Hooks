package com.lokey0905.pikminfixhca13;

import android.os.Build;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class PikminHealthConnectHook implements IXposedHookLoadPackage {
    private static final String TAG = "PikminFixHCA13: ";
    private static final String TARGET_PACKAGE = "com.nianticlabs.pikmin";
    private static final AtomicBoolean BYPASS_LOGGED = new AtomicBoolean(false);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (!TARGET_PACKAGE.equals(loadPackageParam.packageName)) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            log("Android " + Build.VERSION.SDK_INT + " already satisfies the native gate; no hook installed.");
            return;
        }

        int installedHooks = 0;
        installedHooks += hookBooleanGate(
                loadPackageParam.classLoader,
                "com.nianticproject.ichigo.fitness.FitnessClientHealthConnect",
                "isSupported"
        );
        installedHooks += hookBooleanGate(
                loadPackageParam.classLoader,
                "com.nianticproject.ichigo.fitness.FitnessManager",
                "osSupportsHealthConnect"
        );

        log("target loaded; sdk=" + Build.VERSION.SDK_INT + "; hooks=" + installedHooks);
    }

    private static int hookBooleanGate(ClassLoader classLoader, String className, String methodName) {
        try {
            Class<?> targetClass = XposedHelpers.findClassIfExists(className, classLoader);
            if (targetClass == null) {
                log("class not found: " + className);
                return 0;
            }

            Method method = targetClass.getDeclaredMethod(methodName);
            method.setAccessible(true);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        param.setResult(Boolean.TRUE);
                        if (BYPASS_LOGGED.compareAndSet(false, true)) {
                            log("bypassed Android 14 gate through " + className + "." + methodName + "()");
                        }
                    }
                }
            });
            log("hook installed: " + className + "." + methodName + "()");
            return 1;
        } catch (Throwable throwable) {
            log("hook failed: " + className + "." + methodName + "(): " + throwable);
            XposedBridge.log(throwable);
            return 0;
        }
    }

    private static void log(String message) {
        XposedBridge.log(TAG + message);
    }
}
