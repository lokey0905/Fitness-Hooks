package com.lokey0905.FitnessHooks;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

final class ConfigTransport {
    static final String MODULE_PACKAGE = "com.lokey0905.FitnessHooks";
    static final String TARGET_PACKAGE = "com.nianticlabs.pikmin";
    static final String CONFIG_PERMISSION = MODULE_PACKAGE + ".permission.CONFIGURE";
    static final String ACTION_APPLY_CONFIG = MODULE_PACKAGE + ".action.APPLY_CONFIG";
    static final String ACTION_REQUEST_CONFIG = MODULE_PACKAGE + ".action.REQUEST_CONFIG";
    static final String TARGET_PREFS_NAME = MODULE_PACKAGE + ".target_config";

    private ConfigTransport() {
    }

    static HookConfig.Snapshot readModuleSettings(Context context) {
        return readPreferences(context.getSharedPreferences(HookConfig.PREFS_NAME, 0));
    }

    static HookConfig.Snapshot readTargetSettings(Context context) {
        return readPreferences(context.getSharedPreferences(TARGET_PREFS_NAME, 0));
    }

    static boolean storeTargetSettings(Context context, Intent intent) {
        boolean sdkEnabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                && intent.getBooleanExtra(
                        HookConfig.KEY_SDK_HOOK_ENABLED,
                        HookConfig.DEFAULT_SDK_HOOK_ENABLED
                );
        boolean stepEnabled = intent.getBooleanExtra(
                HookConfig.KEY_STEP_HOOK_ENABLED,
                HookConfig.DEFAULT_STEP_HOOK_ENABLED
        );
        int stepCount = HookConfig.clampStepCount(intent.getIntExtra(
                HookConfig.KEY_STEP_COUNT,
                HookConfig.DEFAULT_STEP_COUNT
        ));
        return context.getSharedPreferences(TARGET_PREFS_NAME, 0)
                .edit()
                .putBoolean(HookConfig.KEY_SDK_HOOK_ENABLED, sdkEnabled)
                .putBoolean(HookConfig.KEY_STEP_HOOK_ENABLED, stepEnabled)
                .putInt(HookConfig.KEY_STEP_COUNT, stepCount)
                .commit();
    }

    static void publishToTarget(Context context, HookConfig.Snapshot snapshot) {
        Intent intent = new Intent(ACTION_APPLY_CONFIG)
                .setPackage(TARGET_PACKAGE)
                .putExtra(HookConfig.KEY_SDK_HOOK_ENABLED, snapshot.sdkHookEnabled)
                .putExtra(HookConfig.KEY_STEP_HOOK_ENABLED, snapshot.stepHookEnabled)
                .putExtra(HookConfig.KEY_STEP_COUNT, snapshot.stepCount);
        context.sendBroadcast(intent);
    }

    private static HookConfig.Snapshot readPreferences(SharedPreferences preferences) {
        return new HookConfig.Snapshot(
                Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                        && preferences.getBoolean(
                                HookConfig.KEY_SDK_HOOK_ENABLED,
                                HookConfig.DEFAULT_SDK_HOOK_ENABLED
                        ),
                preferences.getBoolean(
                        HookConfig.KEY_STEP_HOOK_ENABLED,
                        HookConfig.DEFAULT_STEP_HOOK_ENABLED
                ),
                HookConfig.clampStepCount(preferences.getInt(
                        HookConfig.KEY_STEP_COUNT,
                        HookConfig.DEFAULT_STEP_COUNT
                ))
        );
    }
}
