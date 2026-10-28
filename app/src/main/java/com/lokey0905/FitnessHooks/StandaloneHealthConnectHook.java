package com.lokey0905.FitnessHooks;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Routes only Pikmin's Health Connect integration to the standalone APK provider. */
final class StandaloneHealthConnectHook {
    private static final Set<Method> INSTALLED = ConcurrentHashMap.newKeySet();

    private StandaloneHealthConnectHook() {
    }

    static int install(ClassLoader classLoader) {
        if (Build.VERSION.SDK_INT < 34) {
            return 0;
        }
        try {
            Class<?> companion = findClass("androidx.health.connect.client.HealthConnectClient$Companion", classLoader);
            Class<?> fitness = findClass("com.nianticproject.ichigo.fitness.FitnessClientHealthConnect", classLoader);
            Class<?> activity = findClass("com.nianticproject.ichigo.IchigoUnityPlayerActivity", classLoader);
            Class<?> callbackType = findClass("com.nianticproject.ichigo.util.BiConsumer", classLoader);
            Class<?> apkClientType = findClass(
                    "androidx.health.connect.client.impl.HealthConnectClientImpl", classLoader);
            Class<?> platformClientType = findClass(
                    "androidx.health.connect.client.impl.HealthConnectClientUpsideDownImpl", classLoader);
            Method sdkStatus = companion.getDeclaredMethod("getSdkStatus", Context.class, String.class);
            Method createClient = companion.getDeclaredMethod("getOrCreate", Context.class, String.class);
            Method settingsAction = companion.getDeclaredMethod("getHealthConnectSettingsAction");
            Method openSettings = fitness.getDeclaredMethod("openHealthConnect", BiConsumer.class);
            Method getClient = fitness.getDeclaredMethod("getClient");
            Field cachedClient = fitness.getDeclaredField("cachedClient");
            cachedClient.setAccessible(true);
            Field clientContext = fitness.getDeclaredField("context");
            clientContext.setAccessible(true);
            Method getActivity = activity.getDeclaredMethod("getActivity");
            getActivity.setAccessible(true);
            Method launch = activity.getDeclaredMethod("startActivityForResult", Intent.class, callbackType);
            launch.setAccessible(true);

            int count = 0;
            count += hook(createClient, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!selected(classLoader)) {
                        return;
                    }
                    // Suppress the platform factory even if reflection or provider validation fails.
                    param.setResult(null);
                    try {
                        param.setResult(StandaloneHealthConnect.createClient((Context) param.args[0], classLoader));
                    } catch (Throwable failure) {
                        fail(param, failure);
                    }
                }
            });
            count += hook(sdkStatus, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (selected(classLoader)) {
                        param.setResult(StandaloneHealthConnect.sdkStatus((Context) param.args[0]));
                    }
                }
            });
            count += hook(settingsAction, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (selected(classLoader)) {
                        param.setResult(StandaloneHealthConnect.SETTINGS_ACTION);
                    }
                }
            });
            count += hook(openSettings, new XC_MethodHook() {
                @Override
                @SuppressWarnings("unchecked")
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!selected(classLoader)) {
                        return;
                    }
                    param.setResult(null);
                    BiConsumer<Integer, Intent> callback = (BiConsumer<Integer, Intent>) param.args[0];
                    AtomicBoolean delivered = new AtomicBoolean(false);
                    try {
                        Object currentActivity = getActivity.invoke(null);
                        Intent intent = StandaloneHealthConnect.settingsIntent((Context) currentActivity)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        Object wrapper = Proxy.newProxyInstance(classLoader, new Class<?>[] {callbackType},
                                (proxy, method, args) -> {
                                    if ("accept".equals(method.getName()) && args != null && args.length == 2) {
                                        if (callback != null && delivered.compareAndSet(false, true)) {
                                            callback.accept((Integer) args[0], (Intent) args[1]);
                                        }
                                        return null;
                                    }
                                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                                    if ("equals".equals(method.getName())) return proxy == args[0];
                                    if ("toString".equals(method.getName())) return "StandaloneHealthConnectCallback";
                                    return null;
                                });
                        launch.invoke(currentActivity, intent, wrapper);
                    } catch (Throwable failure) {
                        FitnessHooksEntry.log("standalone HC settings failed: " + failure);
                        if (callback != null && delivered.compareAndSet(false, true)) {
                            try {
                                callback.accept(Activity.RESULT_CANCELED, null);
                            } catch (Throwable callbackFailure) {
                                FitnessHooksEntry.log("standalone HC cancellation callback failed: " + callbackFailure);
                            }
                        }
                    }
                }
            });
            count += hook(getClient, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        boolean standalone = selected(classLoader);
                        Class<?> expectedType = standalone ? apkClientType : platformClientType;
                        Object cached = cachedClient.get(param.thisObject);
                        // Inspect the actual value every time: an older in-flight factory can
                        // overwrite the cache after another call has already switched providers.
                        if (cached != null && !expectedType.isInstance(cached)) {
                            cachedClient.set(param.thisObject, null);
                        }
                        if (standalone && StandaloneHealthConnect.sdkStatus(
                                (Context) clientContext.get(param.thisObject)) != StandaloneHealthConnect.SDK_AVAILABLE) {
                            cachedClient.set(param.thisObject, null);
                            param.setResult(null);
                        }
                    } catch (Throwable failure) {
                        // An unvalidated cache may belong to the other provider; never return it.
                        clearCache(cachedClient, param.thisObject);
                        param.setResult(null);
                        FitnessHooksEntry.log("standalone HC cache validation failed: " + failure);
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        // Re-read the route after construction; it may have changed during the
                        // original method, and its returned object need not equal the final cache.
                        boolean standalone = selected(classLoader);
                        Class<?> expectedType = standalone ? apkClientType : platformClientType;
                        Object returned = param.getResult();
                        Object cached = cachedClient.get(param.thisObject);
                        boolean unavailable = standalone && StandaloneHealthConnect.sdkStatus(
                                (Context) clientContext.get(param.thisObject)) != StandaloneHealthConnect.SDK_AVAILABLE;
                        if (unavailable || (returned != null && !expectedType.isInstance(returned))
                                || (cached != null && !expectedType.isInstance(cached))) {
                            cachedClient.set(param.thisObject, null);
                            if (returned != null) {
                                param.setResult(null);
                            }
                        }
                    } catch (Throwable failure) {
                        clearCache(cachedClient, param.thisObject);
                        if (param.getResult() != null) {
                            param.setResult(null);
                        }
                        FitnessHooksEntry.log("standalone HC result validation failed: " + failure);
                    }
                }
            });
            FitnessHooksEntry.log("standalone HC routing hooks installed: " + count);
            return count;
        } catch (Throwable failure) {
            FitnessHooksEntry.log("standalone HC hook installation failed: " + failure);
            XposedBridge.log(failure);
            return 0;
        }
    }

    private static boolean selected(ClassLoader classLoader) {
        return HookConfig.readFromTarget(classLoader).standaloneHcEnabled;
    }

    private static void clearCache(Field cache, Object receiver) {
        try {
            cache.set(receiver, null);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            FitnessHooksEntry.log("standalone HC cache clearing failed: " + failure);
        }
    }

    private static Class<?> findClass(String name, ClassLoader classLoader) throws ClassNotFoundException {
        Class<?> type = XposedHelpers.findClassIfExists(name, classLoader);
        if (type == null) throw new ClassNotFoundException(name);
        return type;
    }

    private static int hook(Method method, XC_MethodHook callback) {
        if (!INSTALLED.add(method)) return 0;
        try {
            method.setAccessible(true);
            XposedBridge.hookMethod(method, callback);
            return 1;
        } catch (Throwable failure) {
            INSTALLED.remove(method);
            throw failure;
        }
    }

    private static void fail(XC_MethodHook.MethodHookParam param, Throwable failure) {
        FitnessHooksEntry.log("standalone HC client failed: " + failure);
        try {
            // The reduced compile-only stub omits this API; real LSPosed exposes it.
            param.getClass().getMethod("setThrowable", Throwable.class).invoke(param, failure);
        } catch (ReflectiveOperationException unavailable) {
            // setResult(null) above already suppresses the platform factory.
            FitnessHooksEntry.log("could not report standalone HC exception: " + unavailable);
        }
    }
}
