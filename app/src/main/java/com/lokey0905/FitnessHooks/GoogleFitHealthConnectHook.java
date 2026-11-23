package com.lokey0905.FitnessHooks;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Routes Fit's inspected HC client and permission contracts without changing the real SDK. */
final class GoogleFitHealthConnectHook {
    private static final String SYSTEM_SETTINGS = "android.health.connect.action.HEALTH_HOME_SETTINGS";
    private static final String APK_GRANTS = "granted_permissions_string";
    private static final String PLATFORM_PERMISSIONS = "androidx.activity.result.contract.extra.PERMISSIONS";
    private static final String PLATFORM_GRANTS = "androidx.activity.result.contract.extra.PERMISSION_GRANT_RESULTS";
    private static final Set<Method> INSTALLED = ConcurrentHashMap.newKeySet();

    private GoogleFitHealthConnectHook() {
    }

    static synchronized int install(ClassLoader loader) {
        if (Build.VERSION.SDK_INT < 34) return 0;
        try {
            Shape shape = new Shape(loader);
            Method[] methods = {shape.factory, shape.status, shape.createPermission,
                    shape.parsePermission, shape.openSettings};
            for (Method method : methods) {
                if (INSTALLED.contains(method)) return 0;
            }
            State state = new State();
            install(shape.factory, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!state.inFitProcess()) return;
                    try {
                        if (!GoogleFitRoute.selected()) return;
                        // Never return the framework client after an opted-in APK route fails.
                        param.setResult(null);
                        Object value = shape.factoryContext.get(param.thisObject);
                        if (!(value instanceof Context) || !fitContext((Context) value)) {
                            throw new IllegalStateException("Fit HC factory context mismatch");
                        }
                        Context context = (Context) value;
                        int status = StandaloneHealthConnect.sdkStatus(context);
                        if (status != StandaloneHealthConnect.SDK_AVAILABLE) {
                            throw new IllegalStateException("Fit standalone HC unavailable (status=" + status + ")");
                        }
                        param.setResult(shape.apkClient.newInstance(context));
                        if (state.activationLogged.compareAndSet(false, true)) {
                            FitnessHooksEntry.log("Fit standalone HC client route active");
                        }
                    } catch (Throwable failure) {
                        fail(param, failure);
                    }
                }
            });
            install(shape.status, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!fitContext((Context) param.args[0]) || !state.inFitProcess()) return;
                    try {
                        if (!GoogleFitRoute.selected()) return;
                        param.setResult(StandaloneHealthConnect.sdkStatus((Context) param.args[0]));
                    } catch (Throwable failure) {
                        // A pending config must not expose framework availability or pin a
                        // provider. Fit may query again after the verified value arrives.
                        param.setResult(StandaloneHealthConnect.SDK_UNAVAILABLE);
                        FitnessHooksEntry.log("Fit HC status unavailable: " + failure);
                    }
                }
            });
            install(shape.createPermission, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!fitContext((Context) param.args[0]) || !state.inFitProcess()) return;
                    try {
                        // Let btg.a perform Fit's original permission-name/set validation.
                        // Keep the selected delegate on the contract for its later parse call.
                        // Parsing a restored result can temporarily select the other parser;
                        // each new request must use this process's fixed provider again.
                        shape.selectDelegate(param.thisObject, GoogleFitRoute.selected());
                    } catch (Throwable failure) {
                        param.setResult(null);
                        fail(param, failure);
                    }
                }
            });
            install(shape.parsePermission, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!state.inFitProcess()) return;
                    Intent result = (Intent) param.args[1];
                    if (result == null) return;
                    try {
                        boolean apk = result.hasExtra(APK_GRANTS);
                        boolean platform = result.hasExtra(PLATFORM_PERMISSIONS)
                                || result.hasExtra(PLATFORM_GRANTS);
                        // Result payloads identify the request's provider even after recreation
                        // or a setting change. The real Fit delegate still parses actual grants.
                        // Ambiguous/unknown payloads keep the original delegate untouched.
                        if (apk != platform) shape.selectDelegate(param.thisObject, apk);
                    } catch (Throwable failure) {
                        param.setResult(null);
                        fail(param, failure);
                    }
                }
            });
            install(shape.openSettings, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof Activity)
                            || !fitContext((Activity) param.thisObject) || !state.inFitProcess()) return;
                    Intent original = (Intent) param.args[0];
                    if (original == null || !SYSTEM_SETTINGS.equals(original.getAction())) return;
                    try {
                        if (!GoogleFitRoute.selected()) return;
                        Intent apk = new Intent(original);
                        apk.setSelector(null);
                        apk.setComponent(null);
                        apk.setAction(StandaloneHealthConnect.SETTINGS_ACTION);
                        apk.setPackage(StandaloneHealthConnect.PROVIDER_PACKAGE);
                        param.args[0] = apk;
                    } catch (Throwable failure) {
                        fail(param, failure);
                    }
                }
            });
            for (Method method : methods) INSTALLED.add(method);
            state.ready = true;
            FitnessHooksEntry.log("Fit HC routing hooks installed: " + methods.length);
            return methods.length;
        } catch (Throwable failure) {
            // Callbacks from a partial installation keep ready=false and never alter calls.
            FitnessHooksEntry.log("Fit HC hooks unavailable; preserving original route: " + failure);
            return 0;
        }
    }

    private static boolean fitContext(Context context) {
        return context != null && ConfigTransport.FIT_PACKAGE.equals(context.getPackageName());
    }

    private static void install(Method method, XC_MethodHook hook) {
        method.setAccessible(true);
        XposedBridge.hookMethod(method, hook);
    }

    private static void fail(XC_MethodHook.MethodHookParam param, Throwable failure) {
        // Throwing from a callback is not enough: Xposed can catch it and run the
        // original. Suppress that invocation even if exception delivery is unavailable.
        param.setResult(null);
        Throwable cause = failure instanceof InvocationTargetException
                && failure.getCause() != null ? failure.getCause() : failure;
        FitnessHooksEntry.log("Fit standalone HC operation failed: " + cause);
        try {
            // Real LSPosed exposes this method; the compile-only stub omits it.
            param.getClass().getMethod("setThrowable", Throwable.class).invoke(param, cause);
        } catch (ReflectiveOperationException unavailable) {
            FitnessHooksEntry.log("Could not report Fit standalone HC exception: " + unavailable);
        }
    }

    private static final class State {
        private volatile boolean ready;
        private final AtomicBoolean activationLogged = new AtomicBoolean();

        boolean inFitProcess() {
            return ready && GoogleFitRoute.areHooksReady() && Build.VERSION.SDK_INT >= 34
                    && fitContext(TargetConfigBridge.getApplicationContext());
        }

    }

    private static final class Shape {
        final Method factory;
        final Method status;
        final Method createPermission;
        final Method parsePermission;
        final Method openSettings;
        final Field factoryContext;
        final Field delegate;
        final Constructor<?> apkClient;
        final Constructor<?> apkContract;
        final Class<?> platformContractType;
        final Constructor<?> permissionContractConstructor;

        Shape(ClassLoader loader) throws ReflectiveOperationException {
            Class<?> factoryType = type("gkv", loader);
            Class<?> statusType = type("bsy", loader);
            Class<?> clientType = type("bsz", loader);
            Class<?> permissionsType = type("btc", loader);
            Class<?> apkType = type("btt", loader);
            Class<?> platformType = type("bud", loader);
            Class<?> baseContract = type("qw", loader);
            Class<?> permissionContract = type("btg", loader);
            Class<?> apkContractType = type("buj", loader);
            platformContractType = type("buk", loader);
            for (Class<?> type : new Class<?>[]{factoryType, statusType, apkType,
                    platformType, permissionContract, apkContractType, platformContractType}) {
                if (!Modifier.isPublic(type.getModifiers()) || !Modifier.isFinal(type.getModifiers())) {
                    throw new NoSuchMethodException("Unexpected Fit HC class shape: " + type.getName());
                }
            }
            if (!clientType.isInterface() || !permissionsType.isInterface()
                    || !Modifier.isAbstract(baseContract.getModifiers())
                    || permissionContract.getSuperclass() != baseContract
                    || apkContractType.getSuperclass() != baseContract
                    || platformContractType.getSuperclass() != baseContract) {
                throw new NoSuchMethodException("Unexpected Fit HC interfaces or permission contracts");
            }
            for (Class<?> type : new Class<?>[]{apkType, platformType}) {
                if (!clientType.isAssignableFrom(type) || !permissionsType.isAssignableFrom(type)) {
                    throw new NoSuchMethodException("Unexpected Fit HC client implementation");
                }
                publicConstructor(type, Context.class);
            }
            Method permissionAccessor = clientType.getDeclaredMethod("b");
            if (permissionAccessor.getReturnType() != permissionsType) {
                throw new NoSuchMethodException("Unexpected Fit HC permission accessor");
            }
            factory = method(factoryType, "B", clientType, false);
            method(factoryType, "A", int.class, false);
            factoryContext = field(factoryType, "a", Object.class, true);
            field(factoryType, "b", Object.class, true);
            status = method(statusType, "a", int.class, true, Context.class);
            Field settingsAction = statusType.getDeclaredField("a");
            if (settingsAction.getType() != String.class
                    || !Modifier.isPublic(settingsAction.getModifiers())
                    || !Modifier.isStatic(settingsAction.getModifiers())
                    || !Modifier.isFinal(settingsAction.getModifiers())) {
                throw new NoSuchFieldException("Unexpected Fit HC settings action");
            }
            delegate = field(permissionContract, "a", baseContract, false);
            createPermission = method(permissionContract, "a", Intent.class, false,
                    Context.class, Object.class);
            parsePermission = method(permissionContract, "b", Object.class, false,
                    int.class, Intent.class);
            for (Class<?> type : new Class<?>[]{apkContractType, platformContractType}) {
                method(type, "a", Intent.class, false, Context.class, Object.class);
                method(type, "b", Object.class, false, int.class, Intent.class);
            }
            apkClient = publicConstructor(apkType, Context.class);
            apkContract = publicConstructor(apkContractType);
            permissionContractConstructor = publicConstructor(permissionContract);
            openSettings = Activity.class.getDeclaredMethod("startActivityForResult",
                    Intent.class, int.class, Bundle.class);
        }

        void selectDelegate(Object contract, boolean apk) throws ReflectiveOperationException {
            Class<?> expected = apk ? apkContract.getDeclaringClass() : platformContractType;
            Object current = delegate.get(contract);
            if (!expected.isInstance(current)) {
                // R8 removed buk's constructor in the inspected DEX. btg's own constructor
                // still creates its platform delegate on real Android 14+, so reuse that
                // existing instance instead of assuming buk can be constructed by reflection.
                Object replacement = apk ? apkContract.newInstance()
                        : delegate.get(permissionContractConstructor.newInstance());
                if (!expected.isInstance(replacement)) {
                    throw new IllegalStateException("Fit HC permission delegate type mismatch");
                }
                delegate.set(contract, replacement);
            }
        }

        private static Class<?> type(String name, ClassLoader loader) throws ClassNotFoundException {
            Class<?> result = XposedHelpers.findClassIfExists(name, loader);
            if (result == null) throw new ClassNotFoundException(name);
            return result;
        }

        private static Constructor<?> publicConstructor(Class<?> type, Class<?>... args)
                throws NoSuchMethodException {
            Constructor<?> result = type.getDeclaredConstructor(args);
            if (!Modifier.isPublic(result.getModifiers())) throw new NoSuchMethodException(type.getName());
            result.setAccessible(true);
            return result;
        }

        private static Field field(Class<?> type, String name, Class<?> valueType, boolean isPublic)
                throws ReflectiveOperationException {
            Field result = type.getDeclaredField(name);
            int modifiers = result.getModifiers();
            if (result.getType() != valueType || !Modifier.isFinal(modifiers) || Modifier.isStatic(modifiers)
                    || (isPublic ? !Modifier.isPublic(modifiers) : !Modifier.isPrivate(modifiers))) {
                throw new NoSuchFieldException("Unexpected Fit HC field: " + type.getName() + "." + name);
            }
            result.setAccessible(true);
            return result;
        }

        private static Method method(Class<?> type, String name, Class<?> resultType,
                boolean isStatic, Class<?>... args) throws NoSuchMethodException {
            Method result = type.getDeclaredMethod(name, args);
            int modifiers = result.getModifiers();
            if (result.getReturnType() != resultType || !Modifier.isPublic(modifiers)
                    || !Modifier.isFinal(modifiers) || Modifier.isStatic(modifiers) != isStatic) {
                throw new NoSuchMethodException("Unexpected Fit HC method: " + type.getName() + "." + name);
            }
            result.setAccessible(true);
            return result;
        }
    }
}
