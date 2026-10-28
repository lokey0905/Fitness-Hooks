package com.lokey0905.FitnessHooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.ext.SdkExtensions;

import androidx.health.connect.client.HealthConnectClient;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/** Invokes registered Java callbacks; this does not install native LSPosed hooks. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, instrumentedPackages = "de.robv.android.xposed", shadows = {
        SdkGateSelfHookTest.BridgeShadow.class, UExtensionHookTest.HelpersShadow.class})
public final class UExtensionHookTest {
    private Context context;
    private SharedPreferences preferences;

    @Before
    public void reset() throws Exception {
        SdkGateSelfHookTest.BridgeShadow.hooks.clear();
        SdkGateSelfHookTest.BridgeShadow.logs.clear();
        ((AtomicBoolean) privateField("API_HOOK_INSTALLED")).set(false);
        ((Set<?>) privateField("SCOPED_METHODS")).clear();
        ((ThreadLocal<?>) privateField("SCOPES")).remove();
        context = RuntimeEnvironment.getApplication();
        if (Build.VERSION.SDK_INT >= 34) {
            context = withHealthConnectManager(context);
            assertEquals(HealthConnectClient.SDK_AVAILABLE,
                    HealthConnectClient.getSdkStatus(context, HealthConnectSteps.PROVIDER_PACKAGE));
        }
        preferences = context.getSharedPreferences(HookConfig.PREFS_NAME, Context.MODE_PRIVATE);
        assertTrue(preferences.edit().clear().commit());
        assertTrue(context.getSharedPreferences(ConfigTransport.TARGET_PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit());
        ReflectionHelpers.setStaticField(TargetConfigBridge.class, "applicationContext", null);
    }

    @After
    public void clearScope() throws Exception {
        ((ThreadLocal<?>) privateField("SCOPES")).remove();
        ReflectionHelpers.setStaticField(TargetConfigBridge.class, "applicationContext", null);
    }

    @Test
    @Config(sdk = {28, 33})
    public void olderAndroidDoesNotInstallEitherHook() {
        assertEquals(0, UExtensionHook.installSelf(getClass().getClassLoader()));
        assertEquals(0, UExtensionHook.install(getClass().getClassLoader()));
        assertTrue(SdkGateSelfHookTest.BridgeShadow.hooks.isEmpty());
    }

    @Test
    @Config(sdk = {34, 35, 36})
    public void installsOneApiHookAndOneLocalScopeOnlyOnce() throws Exception {
        installSelf();
        assertEquals(Set.of(apiMethod(), selfMethod()), SdkGateSelfHookTest.BridgeShadow.hooks.keySet());
        assertEquals(0, UExtensionHook.installSelf(getClass().getClassLoader()));
        assertEquals(2, SdkGateSelfHookTest.BridgeShadow.hooks.size());
    }

    @Test
    public void bothSavedSwitchesMustBeEnabledAndDefaultIsOff() throws Throwable {
        installSelf();
        assertLocalRead(12, 12);
        saveChoices(true, false);
        assertLocalRead(12, 12);
        saveChoices(false, true);
        assertLocalRead(12, 12);
        saveChoices(true, true);
        assertLocalRead(12, 13);
        assertEquals(34, Build.VERSION.SDK_INT);
    }

    @Test
    public void onlyNonNegativeUValuesBelowThirteenAreRaisedInsideScope() throws Throwable {
        installSelf();
        saveChoices(true, true);
        RecordingParam scope = enterSelf();
        assertFalse(scope.resultSet); // The gate's Boolean result itself is never overridden.
        try {
            for (int original : new int[] {0, 1, 12, 13, 16, -1}) {
                RecordingParam result = apiResult(34, original);
                assertEquals(original >= 0 && original < 13 ? 13 : original, result.getResult());
                assertEquals(original >= 0 && original < 13, result.resultSet);
            }
            for (int otherSdk : new int[] {30, 31, 33, 35, 36}) {
                RecordingParam result = apiResult(otherSdk, 12);
                assertEquals(12, result.getResult());
                assertFalse(result.resultSet);
            }
        } finally {
            leave(scope);
        }
    }

    @Test
    public void rawReadsOutsideScopeAndAfterScopeKeepOriginalValue() throws Throwable {
        installSelf();
        saveChoices(true, true);
        assertEquals(12, apiResult(34, 12).getResult());
        RecordingParam scope = enterSelf();
        try {
            assertEquals(13, apiResult(34, 12).getResult());
        } finally {
            leave(scope);
        }
        RecordingParam original = apiResult(34, 12);
        assertEquals(12, original.getResult());
        assertFalse(original.resultSet);
    }

    @Test
    public void nullApiResultPreservesFailureAndGateFailureStillClosesScope() throws Throwable {
        installSelf();
        saveChoices(true, true);
        RecordingParam scope = enterSelf();
        RuntimeException failure = new IllegalStateException("Platform API failed");
        RecordingParam api = new RecordingParam(apiMethod(), null, new Object[] {34}, null);
        api.failure = failure;
        invoke("afterHookedMethod", api);
        assertFalse(api.resultSet);
        assertSame(failure, api.failure);
        scope.failure = failure;
        leave(scope);
        assertSame(failure, scope.failure);
        assertFalse(scope.resultSet);
        assertEquals(12, apiResult(34, 12).getResult());
    }

    @Test
    public void nestedDisabledScopeDoesNotInheritOuterOverride() throws Throwable {
        installSelf();
        saveChoices(true, true);
        RecordingParam outer = enterSelf();
        try {
            saveChoices(true, false);
            RecordingParam inner = enterSelf();
            try {
                assertEquals(12, apiResult(34, 12).getResult());
            } finally {
                leave(inner);
            }
            assertEquals(13, apiResult(34, 12).getResult());
        } finally {
            leave(outer);
        }
        assertEquals(12, apiResult(34, 12).getResult());
    }

    @Test
    public void configurationFailureIsDisabledAndDoesNotLeakScope() throws Throwable {
        installSelf();
        saveChoices(true, true);
        RecordingParam scope = new RecordingParam(selfMethod(), new Object(), new Object[0], null);
        invoke("beforeHookedMethod", scope); // Wrong receiver makes getContext reflection fail.
        try {
            assertFalse(scope.resultSet);
            assertEquals(12, apiResult(34, 12).getResult());
        } finally {
            leave(scope);
        }
        assertEquals(12, apiResult(34, 12).getResult());
    }

    @Test
    public void unavailableHealthConnectDoesNotActivateEvenWithBothChoicesSaved() throws Throwable {
        installSelf();
        saveChoices(true, true);
        Context withoutService = RuntimeEnvironment.getApplication();
        RecordingParam scope = new RecordingParam(selfMethod(), new PikminHealthConnectProbe(withoutService),
                new Object[0], null);
        invoke("beforeHookedMethod", scope);
        try {
            assertEquals(12, apiResult(34, 12).getResult());
            assertFalse(scope.resultSet);
        } finally {
            leave(scope);
        }
    }

    @Test
    public void anotherThreadCannotUseTheActiveScope() throws Throwable {
        installSelf();
        saveChoices(true, true);
        RecordingParam scope = enterSelf();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertEquals(12, executor.submit(() -> {
                try {
                    return apiResult(34, 12).getResult();
                } catch (Throwable failure) {
                    throw new AssertionError(failure);
                }
            }).get(5, TimeUnit.SECONDS));
            assertEquals(13, apiResult(34, 12).getResult());
        } finally {
            executor.shutdownNow();
            leave(scope);
        }
    }

    @Test
    public void targetScopeUsesItsOwnValidatedConfigurationAndSharesSingleApiHook() throws Throwable {
        installSelf();
        saveChoices(false, false);
        ReflectionHelpers.setStaticField(TargetConfigBridge.class, "applicationContext", context);
        assertTrue(context.getSharedPreferences(ConfigTransport.TARGET_PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(HookConfig.KEY_U_EXTENSION_HOOK_ENABLED, true).commit());
        assertEquals(1, UExtensionHook.install(getClass().getClassLoader()));
        assertEquals(3, SdkGateSelfHookTest.BridgeShadow.hooks.size());
        assertLocalRead(12, 12);
        RecordingParam scope = new RecordingParam(TargetGate.class.getDeclaredMethod("isSupported"),
                null, new Object[0], Boolean.FALSE);
        invoke("beforeHookedMethod", scope);
        try {
            assertEquals(13, apiResult(34, 12).getResult());
            assertFalse(scope.resultSet);
            assertEquals(Boolean.FALSE, scope.getResult());
        } finally {
            leave(scope);
        }
        assertEquals(12, apiResult(34, 12).getResult());
    }

    private void installSelf() {
        assertEquals(SdkGateSelfHookTest.BridgeShadow.logs.toString(), 2,
                UExtensionHook.installSelf(getClass().getClassLoader()));
    }

    private void saveChoices(boolean experiments, boolean extension) {
        assertTrue(preferences.edit()
                .putBoolean(HookConfig.KEY_EXPERIMENTAL_FEATURES_ENABLED, experiments)
                .putBoolean(HookConfig.KEY_U_EXTENSION_HOOK_ENABLED, extension).commit());
    }

    private void assertLocalRead(int original, int expected) throws Throwable {
        RecordingParam scope = enterSelf();
        try {
            assertEquals(expected, apiResult(34, original).getResult());
        } finally {
            leave(scope);
        }
    }

    private RecordingParam enterSelf() throws Throwable {
        RecordingParam param = new RecordingParam(selfMethod(), new PikminHealthConnectProbe(context),
                new Object[0], null);
        invoke("beforeHookedMethod", param);
        return param;
    }

    private void leave(RecordingParam param) throws Throwable {
        invoke("afterHookedMethod", param);
    }

    private RecordingParam apiResult(int sdk, Object original) throws Throwable {
        RecordingParam param = new RecordingParam(apiMethod(), null, new Object[] {sdk}, original);
        invoke("afterHookedMethod", param);
        return param;
    }

    private void invoke(String callbackName, RecordingParam param) throws Throwable {
        XC_MethodHook callback = SdkGateSelfHookTest.BridgeShadow.hooks.get(param.method);
        assertNotNull(SdkGateSelfHookTest.BridgeShadow.logs.toString(), callback);
        Method method = callback.getClass().getDeclaredMethod(callbackName, XC_MethodHook.MethodHookParam.class);
        method.setAccessible(true);
        try {
            method.invoke(callback, param);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static Method selfMethod() throws Exception {
        return PikminHealthConnectProbe.class.getDeclaredMethod("isSupported");
    }

    private static Method apiMethod() throws Exception {
        return SdkExtensions.class.getDeclaredMethod("getExtensionVersion", int.class);
    }

    private static Object privateField(String name) throws Exception {
        Field field = UExtensionHook.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static Context withHealthConnectManager(Context base) throws Exception {
        Object[] manager = new Object[1];
        Context context = new ContextWrapper(base) {
            @Override
            public Context getApplicationContext() {
                return this;
            }

            @Override
            public Object getSystemService(String name) {
                return "healthconnect".equals(name) ? manager[0] : super.getSystemService(name);
            }
        };
        Class<?> serviceType = Class.forName("android.health.connect.aidl.IHealthConnectService");
        Object service = Proxy.newProxyInstance(serviceType.getClassLoader(), new Class<?>[] {serviceType},
                (proxy, method, arguments) -> {
                    throw new AssertionError("Unexpected HC data/permission RPC: " + method.getName());
                });
        Constructor<?> constructor = Class.forName("android.health.connect.HealthConnectManager")
                .getDeclaredConstructor(Context.class, serviceType);
        constructor.setAccessible(true);
        manager[0] = constructor.newInstance(context, service);
        return context;
    }

    public static final class RecordingParam extends XC_MethodHook.MethodHookParam {
        private Object result;
        boolean resultSet;
        Throwable failure;

        RecordingParam(Method method, Object receiver, Object[] args, Object result) {
            this.method = method;
            this.thisObject = receiver;
            this.args = args;
            this.result = result;
        }

        @Override
        public Object getResult() {
            return result;
        }

        @Override
        public void setResult(Object result) {
            this.result = result;
            resultSet = true;
            failure = null;
        }
    }

    public static final class TargetGate {
        public static boolean isSupported() {
            return false;
        }
    }

    @Implements(value = XposedHelpers.class, isInAndroidSdk = false)
    public static final class HelpersShadow {
        @Implementation
        protected static Class<?> findClassIfExists(String name, ClassLoader loader) {
            if ("com.nianticproject.ichigo.fitness.FitnessClientHealthConnect".equals(name)) {
                return TargetGate.class;
            }
            try {
                return Class.forName(name, false, loader);
            } catch (ClassNotFoundException | LinkageError ignored) {
                return null;
            }
        }
    }
}
