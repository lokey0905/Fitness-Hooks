package com.lokey0905.FitnessHooks;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import androidx.core.content.ContextCompat;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

final class TargetConfigBridge {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);
    private static final Object ROUTE_LOCK = new Object();
    private static volatile Context applicationContext;
    private static volatile Boolean standaloneRouteSelection;
    private static long configGeneration;

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

    static boolean isStandaloneProviderEnabled() {
        return isStandaloneRouteEnabled();
    }

    static boolean isStandaloneRouteEnabled() {
        return Boolean.TRUE.equals(standaloneRouteSelection);
    }

    static boolean isStandaloneRouteInitialized() {
        return standaloneRouteSelection != null;
    }

    static Boolean getStandaloneRouteSelection() {
        return standaloneRouteSelection;
    }

    private static void applyBootstrapRoute(Boolean selected, long generation, String label) {
        if (selected == null) return;
        synchronized (ROUTE_LOCK) {
            if (configGeneration != generation) return;
            standaloneRouteSelection = Build.VERSION.SDK_INT >= 34 && selected;
            configGeneration++;
            FitnessHooksEntry.log(label + " startup config verified; standaloneHc="
                    + standaloneRouteSelection);
        }
    }

    private static void applyBroadcastRoute(Boolean selected, String label) {
        if (selected == null) return;
        synchronized (ROUTE_LOCK) {
            configGeneration++;
            standaloneRouteSelection = Build.VERSION.SDK_INT >= 34 && selected;
            FitnessHooksEntry.log(label + " provider config received; standaloneHc="
                    + standaloneRouteSelection);
        }
    }

    private static void initialize(Context context) {
        String packageName = context.getPackageName();
        boolean providerProcess = ConfigTransport.HC_PACKAGE.equals(packageName);
        boolean routeOnlyProcess = providerProcess || ConfigTransport.FIT_PACKAGE.equals(packageName);
        String routeLabel = providerProcess ? "HC" : "Fit HC";
        if (!routeOnlyProcess && !ConfigTransport.TARGET_PACKAGE.equals(packageName)) {
            return;
        }
        Context appContext = context.getApplicationContext();
        applicationContext = appContext != null ? appContext : context;
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }

        boolean receiverRegistered = false;
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, Intent intent) {
                    if (!ConfigTransport.ACTION_APPLY_CONFIG.equals(intent.getAction())) {
                        return;
                    }
                    if (routeOnlyProcess) {
                        // This receiver requires the module's signature permission.
                        // Live state does not depend on a stale or unwritable preference file.
                        applyBroadcastRoute(StandaloneConfigBootstrap.parseRoute(intent.getExtras()),
                                routeLabel);
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
                            + "; standaloneHc=" + snapshot.standaloneHcEnabled
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
            receiverRegistered = true;
            if (routeOnlyProcess) {
                final long generation;
                synchronized (ROUTE_LOCK) {
                    generation = configGeneration;
                }
                Boolean selected = Build.VERSION.SDK_INT >= 34
                        ? StandaloneConfigBootstrap.read(applicationContext,
                                late -> applyBootstrapRoute(late, generation, routeLabel))
                        : Boolean.FALSE;
                applyBootstrapRoute(selected, generation, routeLabel);
                if (getStandaloneRouteSelection() == null) {
                    FitnessHooksEntry.log(routeLabel + " startup config pending; provider not selected");
                }
            }
            requestCurrentConfig();
            FitnessHooksEntry.log("target config receiver registered");
        } catch (Throwable throwable) {
            // A registration/request failure is not evidence that the user selected system HC.
            // Keep a registered receiver alive so a later signed configuration can resolve it.
            if (!receiverRegistered) REGISTERED.set(false);
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
