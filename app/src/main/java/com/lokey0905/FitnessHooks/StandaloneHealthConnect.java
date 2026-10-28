package com.lokey0905.FitnessHooks;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.UserManager;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/** Explicit APK-provider routing shared by the module UI and the target hooks. */
public final class StandaloneHealthConnect {
    public static final String PROVIDER_PACKAGE = "com.google.android.apps.healthdata";
    public static final long MIN_VERSION_CODE = 171302L;
    public static final int SDK_UNAVAILABLE = 1;
    public static final int SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED = 2;
    public static final int SDK_AVAILABLE = 3;
    public static final String SETTINGS_ACTION = "androidx.health.ACTION_HEALTH_CONNECT_SETTINGS";
    static final String BIND_ACTION = "androidx.health.ACTION_BIND_HEALTH_DATA_SERVICE";
    private static final String CLIENT_CLASS = "androidx.health.connect.client.impl.HealthConnectClientImpl";
    private static final String FEATURES_CLASS =
            "androidx.health.connect.client.feature.HealthConnectFeaturesApkImpl";

    private StandaloneHealthConnect() {
    }

    /** Reads only the user's choice. Availability must never turn routing back to the platform. */
    public static boolean isModuleSelected(Context context) {
        if (Build.VERSION.SDK_INT < 34) {
            return false;
        }
        SharedPreferences preferences = context.getSharedPreferences(HookConfig.PREFS_NAME, Context.MODE_PRIVATE);
        return preferences.getBoolean(HookConfig.KEY_EXPERIMENTAL_FEATURES_ENABLED, false)
                && preferences.getBoolean(HookConfig.KEY_STANDALONE_HC_ENABLED, false);
    }

    /** Checks this Android user's APK and service without invoking the AndroidX factory. */
    public static int sdkStatus(Context context) {
        if (Build.VERSION.SDK_INT < 34 || context == null) {
            return SDK_UNAVAILABLE;
        }
        try {
            UserManager users = context.getSystemService(UserManager.class);
            if (users == null || users.isManagedProfile()) {
                return SDK_UNAVAILABLE;
            }
            PackageManager packages = context.getPackageManager();
            PackageInfo provider = packages.getPackageInfo(PROVIDER_PACKAGE, 0);
            ApplicationInfo application = provider.applicationInfo;
            if (application == null || !application.enabled
                    || provider.getLongVersionCode() < MIN_VERSION_CODE) {
                return SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED;
            }
            int enabled = packages.getApplicationEnabledSetting(PROVIDER_PACKAGE);
            if (enabled != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                    && enabled != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                return SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED;
            }
            List<ResolveInfo> services = packages.queryIntentServices(
                    new Intent(BIND_ACTION).setPackage(PROVIDER_PACKAGE), 0);
            for (ResolveInfo result : services) {
                ServiceInfo service = result.serviceInfo;
                if (service != null && service.enabled && service.exported
                        && PROVIDER_PACKAGE.equals(service.packageName)
                        && service.applicationInfo != null && service.applicationInfo.enabled) {
                    return SDK_AVAILABLE;
                }
            }
            return SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED;
        } catch (PackageManager.NameNotFoundException absent) {
            return SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED;
        } catch (RuntimeException unavailable) {
            return SDK_UNAVAILABLE;
        }
    }

    /** Never invokes getOrCreate: that factory selects the framework on real Android 14+. */
    public static Object createClient(Context context, ClassLoader classLoader) {
        int status = sdkStatus(context);
        if (status != SDK_AVAILABLE) {
            throw new IllegalStateException("Standalone Health Connect unavailable (status=" + status + ")");
        }
        try {
            Class<?> clientType = Class.forName(CLIENT_CLASS, false, classLoader);
            Constructor<?> constructor = clientType.getDeclaredConstructor(Context.class, String.class);
            constructor.setAccessible(true);
            Object client = constructor.newInstance(context, PROVIDER_PACKAGE);
            Method getFeatures = clientType.getMethod("getFeatures");
            Object features = getFeatures.invoke(client);
            if (features == null || !FEATURES_CLASS.equals(features.getClass().getName())) {
                throw new IllegalStateException("Standalone Health Connect APK feature implementation missing");
            }
            Object featureStatus = features.getClass().getMethod("getFeatureStatus", int.class)
                    .invoke(features, 1);
            if (!Integer.valueOf(2).equals(featureStatus)) {
                throw new IllegalStateException("Standalone Health Connect background read is unavailable");
            }
            return client;
        } catch (ReflectiveOperationException failure) {
            Throwable cause = failure instanceof InvocationTargetException
                    ? ((InvocationTargetException) failure).getCause() : failure;
            throw new IllegalStateException("Cannot construct standalone Health Connect client", cause);
        }
    }

    public static Intent settingsIntent(Context context) {
        if (sdkStatus(context) != SDK_AVAILABLE) {
            throw new IllegalStateException("Standalone Health Connect settings are unavailable");
        }
        Intent intent = new Intent(SETTINGS_ACTION).setPackage(PROVIDER_PACKAGE);
        if (!(context instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        return intent;
    }
}
