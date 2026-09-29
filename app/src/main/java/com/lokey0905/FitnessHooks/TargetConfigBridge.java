package com.lokey0905.FitnessHooks;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.core.content.ContextCompat;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

final class TargetConfigBridge {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);
    private static volatile Context applicationContext;

    private TargetConfigBridge() {
    }

    @SuppressLint("DiscouragedPrivateApi")
    static int install() {
        try {
            XposedBridge.hookMethod(
                    Application.class.getDeclaredMethod("attach", Context.class),
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Context context = (Context) param.args[0];
                            initialize(context);
                        }
                    }
            );
            return 1;
        } catch (Throwable throwable) {
            FitnessHooksEntry.log("config bridge hook failed: " + throwable);
            XposedBridge.log(throwable);
            return 0;
        }
    }

    static Context getApplicationContext() {
        return applicationContext;
    }

    private static void initialize(Context context) {
        Context appContext = context.getApplicationContext();
        applicationContext = appContext != null ? appContext : context;
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }

        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, Intent intent) {
                    if (!ConfigTransport.ACTION_APPLY_CONFIG.equals(intent.getAction())) {
                        return;
                    }
                    boolean saved = ConfigTransport.storeTargetSettings(
                            applicationContext,
                            intent
                    );
                    HookConfig.Snapshot snapshot = ConfigTransport.readTargetSettings(
                            applicationContext
                    );
                    FitnessHooksEntry.log("target config received; saved=" + saved
                            + "; sdk=" + snapshot.sdkHookEnabled
                            + "; steps=" + snapshot.stepHookEnabled
                            + "; count=" + snapshot.stepCount);
                }
            };
            IntentFilter filter = new IntentFilter(ConfigTransport.ACTION_APPLY_CONFIG);
            ContextCompat.registerReceiver(
                    applicationContext,
                    receiver,
                    filter,
                    ConfigTransport.CONFIG_PERMISSION,
                    null,
                    ContextCompat.RECEIVER_EXPORTED
            );
            requestCurrentConfig();
            FitnessHooksEntry.log("target config receiver registered");
        } catch (Throwable throwable) {
            REGISTERED.set(false);
            FitnessHooksEntry.log("target config receiver failed: " + throwable);
            XposedBridge.log(throwable);
        }
    }

    private static void requestCurrentConfig() {
        Intent request = new Intent(ConfigTransport.ACTION_REQUEST_CONFIG)
                .setComponent(new ComponentName(
                        ConfigTransport.MODULE_PACKAGE,
                        ConfigRequestReceiver.class.getName()
                ))
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        applicationContext.sendBroadcast(request);
    }
}
