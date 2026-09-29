package com.lokey0905.FitnessHooks;

import android.content.Context;

import java.lang.reflect.Method;

final class HookConfig {
    static final String PREFS_NAME = "hook_config";
    static final String KEY_SDK_HOOK_ENABLED = "sdk_hook_enabled";
    static final String KEY_STEP_HOOK_ENABLED = "step_hook_enabled";
    static final String KEY_STEP_COUNT = "step_count";
    static final boolean DEFAULT_SDK_HOOK_ENABLED = true;
    static final boolean DEFAULT_STEP_HOOK_ENABLED = false;
    static final int DEFAULT_STEP_COUNT = 1000;
    static final int MAX_STEP_COUNT = 1_000_000;

    private static volatile boolean failureLogged;
    private static volatile String lastLoggedConfig;

    private HookConfig() {
    }

    static Snapshot readFromTarget(ClassLoader classLoader) {
        try {
            Context context = TargetConfigBridge.getApplicationContext();
            if (context != null) {
                return read(context);
            }

            Class<?> sharedContext = Class.forName(
                    "com.nianticproject.ichigo.util.SharedContext",
                    false,
                    classLoader
            );
            Method getter = sharedContext.getDeclaredMethod("getApplicationContext");
            getter.setAccessible(true);
            Object value = getter.invoke(null);
            if (value instanceof Context) {
                return read((Context) value);
            }
            throw new IllegalStateException("Pikmin application context is unavailable");
        } catch (Throwable throwable) {
            logFailureOnce(throwable);
            return defaults();
        }
    }

    private static Snapshot read(Context context) {
        Snapshot snapshot = ConfigTransport.readTargetSettings(context);
        logConfigIfChanged(snapshot);
        return snapshot;
    }

    private static void logFailureOnce(Throwable throwable) {
        if (!failureLogged) {
            failureLogged = true;
            FitnessHooksEntry.log(
                    "target configuration unavailable; using defaults: " + throwable
                );
        }
    }

    private static void logConfigIfChanged(Snapshot snapshot) {
        String summary = "sdk=" + snapshot.sdkHookEnabled
                + "; steps=" + snapshot.stepHookEnabled
                + "; count=" + snapshot.stepCount;
        if (!summary.equals(lastLoggedConfig)) {
            lastLoggedConfig = summary;
            FitnessHooksEntry.log("config loaded from target storage; " + summary);
        }
    }

    static int clampStepCount(int value) {
        return Math.max(0, Math.min(MAX_STEP_COUNT, value));
    }

    private static Snapshot defaults() {
        return new Snapshot(
                DEFAULT_SDK_HOOK_ENABLED,
                DEFAULT_STEP_HOOK_ENABLED,
                DEFAULT_STEP_COUNT
        );
    }

    static final class Snapshot {
        final boolean sdkHookEnabled;
        final boolean stepHookEnabled;
        final int stepCount;

        Snapshot(boolean sdkHookEnabled, boolean stepHookEnabled, int stepCount) {
            this.sdkHookEnabled = sdkHookEnabled;
            this.stepHookEnabled = stepHookEnabled;
            this.stepCount = stepCount;
        }
    }
}
