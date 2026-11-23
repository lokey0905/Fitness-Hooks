package com.lokey0905.FitnessHooks;

import android.os.Build;

import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class FitnessHooksEntry implements IXposedHookLoadPackage {
    private static final String TAG = "FitnessHooks: ";
    private static final String MODULE_PACKAGE = "com.lokey0905.FitnessHooks";
    private static final String TARGET_PACKAGE = "com.nianticlabs.pikmin";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (MODULE_PACKAGE.equals(loadPackageParam.packageName)) {
            installActivationIndicator(loadPackageParam.classLoader);
            int selfSdkHooks = SdkGateHook.installSelf(loadPackageParam.classLoader);
            log("module loaded; selfSdkHooks=" + selfSdkHooks);
            return;
        }

        if (ConfigTransport.FIT_PACKAGE.equals(loadPackageParam.packageName)) {
            int bridgeHooks = Build.VERSION.SDK_INT >= 34 ? TargetConfigBridge.install() : 0;
            int syncStateHooks = GoogleFitSyncStateHook.install(loadPackageParam.classLoader);
            int clientHooks = GoogleFitHealthConnectHook.install(loadPackageParam.classLoader);
            if (bridgeHooks == 1 && syncStateHooks == 2 && clientHooks == 5) {
                GoogleFitRoute.markHooksReady();
            }
            log("Google Fit loaded; configBridgeHooks=" + bridgeHooks
                    + "; syncStateHooks=" + syncStateHooks + "; clientHooks=" + clientHooks);
            return;
        }

        if (ConfigTransport.HC_PACKAGE.equals(loadPackageParam.packageName)) {
            int configBridgeHooks = Build.VERSION.SDK_INT >= 34
                    ? TargetConfigBridge.install() : 0;
            int providerHooks = StandaloneProviderHook.install(loadPackageParam.classLoader);
            log("HC provider loaded; configBridgeHooks=" + configBridgeHooks
                    + "; providerHooks=" + providerHooks);
            return;
        }

        if (!TARGET_PACKAGE.equals(loadPackageParam.packageName)) {
            return;
        }

        int configBridgeHooks = TargetConfigBridge.install();
        int sdkHooks = SdkGateHook.install(loadPackageParam.classLoader);
        int standaloneHcHooks = StandaloneHealthConnectHook.install(loadPackageParam.classLoader);
        int stepHooks = RecordingStepHook.install(loadPackageParam.classLoader);
        log("target loaded; configBridgeHooks=" + configBridgeHooks
                + "; sdkHooks=" + sdkHooks
                + "; standaloneHcHooks=" + standaloneHcHooks
                + "; stepHooks=" + stepHooks);
    }

    private static void installActivationIndicator(ClassLoader classLoader) {
        try {
            Class<?> settingsActivity = Class.forName(
                    MODULE_PACKAGE + ".HookSettingsActivity",
                    false,
                    classLoader
            );
            Method activationMethod = settingsActivity.getDeclaredMethod("isModuleActive");
            activationMethod.setAccessible(true);
            XposedBridge.hookMethod(activationMethod, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(Boolean.TRUE);
                        }
                    });
            log("module activation indicator installed");
        } catch (Throwable throwable) {
            log("module activation indicator failed: " + throwable);
            XposedBridge.log(throwable);
        }
    }

    static void log(String message) {
        XposedBridge.log(TAG + message);
    }
}
