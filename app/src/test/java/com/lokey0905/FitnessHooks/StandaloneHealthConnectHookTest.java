package com.lokey0905.FitnessHooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

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

/** Exercises real registered callbacks, not native LSPosed interception or Provider RPCs. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, instrumentedPackages = "de.robv.android.xposed", shadows = {
        SdkGateSelfHookTest.BridgeShadow.class, StandaloneHealthConnectHookTest.HelpersShadow.class})
public final class StandaloneHealthConnectHookTest {
    private Context context;

    @Before
    public void reset() throws Exception {
        context = RuntimeEnvironment.getApplication();
        SdkGateSelfHookTest.BridgeShadow.hooks.clear();
        SdkGateSelfHookTest.BridgeShadow.logs.clear();
        ((Set<?>) privateField("INSTALLED")).clear();
        assertTrue(context.getSharedPreferences(ConfigTransport.TARGET_PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit());
        ReflectionHelpers.setStaticField(TargetConfigBridge.class, "applicationContext", context);
        TargetActivity.current = new TargetActivity(context);
    }

    @Test
    @Config(sdk = {28, 33})
    public void olderAndroidInstallsNothing() {
        assertEquals(0, StandaloneHealthConnectHook.install(getClass().getClassLoader()));
        assertTrue(SdkGateSelfHookTest.BridgeShadow.hooks.isEmpty());
    }

    @Test
    public void installsOnlyFiveMethodsAndDoesNotDuplicateThem() {
        installHooks();
        assertEquals(5, SdkGateSelfHookTest.BridgeShadow.hooks.size());
        assertEquals(0, StandaloneHealthConnectHook.install(getClass().getClassLoader()));
    }

    @Test
    public void defaultsLeaveFactoriesSettingsAndExistingCacheUntouched() throws Throwable {
        installHooks();
        assertFalse(invoke(companionMethod("getSdkStatus", Context.class, String.class), null,
                context, StandaloneHealthConnect.PROVIDER_PACKAGE).resultSet);
        assertFalse(invoke(companionMethod("getOrCreate", Context.class, String.class), null,
                context, StandaloneHealthConnect.PROVIDER_PACKAGE).resultSet);
        assertFalse(invoke(companionMethod("getHealthConnectSettingsAction"), null).resultSet);
        assertFalse(invoke(TargetFitness.class.getDeclaredMethod("openHealthConnect", BiConsumer.class),
                null, (Object) null).resultSet);
        TargetFitness fitness = new TargetFitness(context);
        Object systemClient = fitness.cachedClient;
        assertFalse(invoke(TargetFitness.class.getDeclaredMethod("getClient"), fitness).resultSet);
        assertSame(systemClient, fitness.cachedClient);
    }

    @Test
    public void selectedMissingProviderCannotReachTheOriginalFactory() throws Throwable {
        installHooks();
        select(true);
        assertEquals(StandaloneHealthConnect.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED,
                invoke(companionMethod("getSdkStatus", Context.class, String.class), null,
                        context, StandaloneHealthConnect.PROVIDER_PACKAGE).getResult());
        RecordingParam result = invoke(companionMethod("getOrCreate", Context.class, String.class),
                null, context, StandaloneHealthConnect.PROVIDER_PACKAGE);
        assertTrue(result.resultSet);
        assertNull(result.getResult());
        assertTrue(result.failure instanceof IllegalStateException);
        assertEquals(StandaloneHealthConnect.SETTINGS_ACTION,
                invoke(companionMethod("getHealthConnectSettingsAction"), null).getResult());
    }

    @Test
    public void selectedFactoryCreatesTheActualApkClient() throws Throwable {
        installHooks();
        select(true);
        provider(true);
        RecordingParam result = invoke(companionMethod("getOrCreate", Context.class, String.class),
                null, context, "ignored.requested.provider");
        assertEquals("androidx.health.connect.client.impl.HealthConnectClientImpl",
                result.getResult().getClass().getName());
        assertNull(result.failure);
    }

    @Test
    public void modeChangesAndProviderDisappearanceInvalidateEachInstanceCache() throws Throwable {
        installHooks();
        select(true);
        provider(true);
        TargetFitness fitness = new TargetFitness(context);
        Method getClient = TargetFitness.class.getDeclaredMethod("getClient");
        assertFalse(invoke(getClient, fitness).resultSet);
        assertNull(fitness.cachedClient);
        Object apkClient = new ApkClient();
        fitness.cachedClient = apkClient;
        invoke(getClient, fitness);
        assertSame(apkClient, fitness.cachedClient);
        select(false);
        invoke(getClient, fitness);
        assertNull(fitness.cachedClient);
        fitness.cachedClient = new PlatformClient();
        select(true);
        invoke(getClient, fitness);
        assertNull(fitness.cachedClient);
        fitness.cachedClient = apkClient;
        provider(false);
        RecordingParam invalid = invoke(getClient, fitness);
        assertTrue(invalid.resultSet);
        assertNull(invalid.getResult());
        assertNull(fitness.cachedClient);
    }

    @Test
    public void settingsAreExplicitAndCustomCallbackPreservesResultAndIntent() throws Throwable {
        installHooks();
        select(true);
        provider(true);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger code = new AtomicInteger();
        AtomicReference<Intent> data = new AtomicReference<>();
        BiConsumer<Integer, Intent> callback = (resultCode, resultData) -> {
            calls.incrementAndGet();
            code.set(resultCode);
            data.set(resultData);
        };
        RecordingParam result = invoke(TargetFitness.class.getDeclaredMethod("openHealthConnect", BiConsumer.class),
                null, callback);
        assertTrue(result.resultSet);
        assertEquals(StandaloneHealthConnect.PROVIDER_PACKAGE, TargetActivity.current.intent.getPackage());
        assertEquals(StandaloneHealthConnect.SETTINGS_ACTION, TargetActivity.current.intent.getAction());
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, TargetActivity.current.intent.getFlags());
        assertEquals(0, calls.get());
        Intent returned = new Intent("test.return");
        TargetActivity.current.callback.accept(Activity.RESULT_OK, returned);
        assertEquals(1, calls.get());
        assertEquals(Activity.RESULT_OK, code.get());
        assertSame(returned, data.get());
        TargetActivity.current.callback.accept(Activity.RESULT_CANCELED, null);
        assertEquals(1, calls.get());
    }

    @Test
    public void missingProviderCancelsSettingsCallbackWithoutLaunchingSystem() throws Throwable {
        installHooks();
        select(true);
        AtomicInteger calls = new AtomicInteger();
        BiConsumer<Integer, Intent> callback = (code, data) -> {
            assertEquals(Integer.valueOf(Activity.RESULT_CANCELED), code);
            assertNull(data);
            calls.incrementAndGet();
        };
        RecordingParam result = invoke(TargetFitness.class.getDeclaredMethod("openHealthConnect", BiConsumer.class),
                null, callback);
        assertTrue(result.resultSet);
        assertEquals(1, calls.get());
        assertNull(TargetActivity.current.intent);
    }

    @Test
    public void latePlatformWriterCannotPoisonAlreadySelectedApkCache() throws Throwable {
        installHooks();
        provider(true);
        TargetFitness fitness = new TargetFitness(context);
        Method getClient = TargetFitness.class.getDeclaredMethod("getClient");
        fitness.cachedClient = null;
        RecordingParam olderPlatformCall = invoke(getClient, fitness);
        select(true);
        RecordingParam newerApkCall = invoke(getClient, fitness);
        ApkClient apk = new ApkClient();
        fitness.cachedClient = apk;
        newerApkCall.originalResult(apk);
        after(newerApkCall);
        assertSame(apk, newerApkCall.getResult());
        // The older call finishes after the new route has successfully populated its cache.
        PlatformClient latePlatform = new PlatformClient();
        fitness.cachedClient = latePlatform;
        olderPlatformCall.originalResult(latePlatform);
        after(olderPlatformCall);
        assertNull(olderPlatformCall.getResult());
        assertNull(fitness.cachedClient);
        // Even an unobserved later write is detected before the next call; no mode toggle needed.
        fitness.cachedClient = latePlatform;
        assertFalse(invoke(getClient, fitness).resultSet);
        assertNull(fitness.cachedClient);
    }

    @Test
    public void switchDuringApkConstructionRejectsLateApkResultAndCache() throws Throwable {
        installHooks();
        provider(true);
        select(true);
        TargetFitness fitness = new TargetFitness(context);
        Method getClient = TargetFitness.class.getDeclaredMethod("getClient");
        RecordingParam pendingApkCall = invoke(getClient, fitness);
        select(false);
        RecordingParam platformCall = invoke(getClient, fitness);
        PlatformClient platform = new PlatformClient();
        fitness.cachedClient = platform;
        platformCall.originalResult(platform);
        after(platformCall);
        assertSame(platform, platformCall.getResult());
        ApkClient lateApk = new ApkClient();
        fitness.cachedClient = lateApk;
        pendingApkCall.originalResult(lateApk);
        after(pendingApkCall);
        assertNull(pendingApkCall.getResult());
        assertNull(fitness.cachedClient);
    }

    @Test
    public void afterChecksBothReturnedValueAndFinalCache() throws Throwable {
        installHooks();
        provider(true);
        select(true);
        TargetFitness fitness = new TargetFitness(context);
        Method getClient = TargetFitness.class.getDeclaredMethod("getClient");
        RecordingParam mismatchedReturn = invoke(getClient, fitness);
        fitness.cachedClient = new ApkClient();
        mismatchedReturn.originalResult(new PlatformClient());
        after(mismatchedReturn);
        assertNull(mismatchedReturn.getResult());
        assertNull(fitness.cachedClient);
        RecordingParam mismatchedCache = invoke(getClient, fitness);
        fitness.cachedClient = new PlatformClient();
        mismatchedCache.originalResult(new ApkClient());
        after(mismatchedCache);
        assertNull(mismatchedCache.getResult());
        assertNull(fitness.cachedClient);
    }

    @Test
    public void providerDisappearingDuringConstructionRejectsResultAndPreservesOriginalFailure() throws Throwable {
        installHooks();
        provider(true);
        select(true);
        TargetFitness fitness = new TargetFitness(context);
        Method getClient = TargetFitness.class.getDeclaredMethod("getClient");
        RecordingParam pending = invoke(getClient, fitness);
        provider(false);
        ApkClient obsolete = new ApkClient();
        fitness.cachedClient = obsolete;
        pending.originalResult(obsolete);
        after(pending);
        assertNull(pending.getResult());
        assertNull(fitness.cachedClient);
        RecordingParam failed = new RecordingParam(getClient, fitness, new Object[0]);
        RuntimeException originalFailure = new IllegalStateException("Provider was removed");
        failed.setThrowable(originalFailure);
        fitness.cachedClient = obsolete;
        after(failed);
        assertSame(originalFailure, failed.failure);
        assertNull(fitness.cachedClient);
    }

    @Test
    public void unrecognizedCacheTypeIsNeverReturned() throws Throwable {
        installHooks();
        provider(true);
        TargetFitness fitness = new TargetFitness(context);
        Method getClient = TargetFitness.class.getDeclaredMethod("getClient");
        for (boolean standalone : new boolean[] {false, true}) {
            select(standalone);
            fitness.cachedClient = new Object();
            invoke(getClient, fitness);
            assertNull(fitness.cachedClient);
            RecordingParam param = new RecordingParam(getClient, fitness, new Object[0]);
            param.originalResult(new Object());
            after(param);
            assertNull(param.getResult());
        }
    }

    private void installHooks() {
        assertEquals(SdkGateSelfHookTest.BridgeShadow.logs.toString(), 5,
                StandaloneHealthConnectHook.install(getClass().getClassLoader()));
    }

    private void select(boolean enabled) {
        assertTrue(context.getSharedPreferences(ConfigTransport.TARGET_PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(HookConfig.KEY_STANDALONE_HC_ENABLED, enabled).commit());
    }

    private void provider(boolean enabled) {
        StandaloneHealthConnectTest.installProvider(context, enabled,
                StandaloneHealthConnect.MIN_VERSION_CODE, true, true);
    }

    private Method companionMethod(String name, Class<?>... types) throws Exception {
        return Class.forName("androidx.health.connect.client.HealthConnectClient$Companion")
                .getDeclaredMethod(name, types);
    }

    private RecordingParam invoke(Method method, Object receiver, Object... args) throws Throwable {
        RecordingParam param = new RecordingParam(method, receiver, args);
        invokeCallback("beforeHookedMethod", param);
        return param;
    }

    private void after(RecordingParam param) throws Throwable {
        invokeCallback("afterHookedMethod", param);
    }

    private void invokeCallback(String callbackName, RecordingParam param) throws Throwable {
        XC_MethodHook callback = SdkGateSelfHookTest.BridgeShadow.hooks.get(param.method);
        assertNotNull(SdkGateSelfHookTest.BridgeShadow.logs.toString(), callback);
        Method before = callback.getClass().getDeclaredMethod(callbackName, XC_MethodHook.MethodHookParam.class);
        before.setAccessible(true);
        try {
            before.invoke(callback, param);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static Object privateField(String name) throws Exception {
        Field field = StandaloneHealthConnectHook.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    public static final class RecordingParam extends XC_MethodHook.MethodHookParam {
        private Object result;
        boolean resultSet;
        Throwable failure;

        RecordingParam(Method method, Object receiver, Object[] args) {
            this.method = method;
            this.thisObject = receiver;
            this.args = args;
        }

        @Override public Object getResult() { return result; }

        @Override public void setResult(Object result) {
            this.result = result;
            resultSet = true;
            failure = null;
        }

        public void setThrowable(Throwable failure) {
            this.failure = failure;
            result = null;
            resultSet = true;
        }

        void originalResult(Object result) {
            this.result = result;
            this.resultSet = false;
            this.failure = null;
        }
    }

    public static final class TargetFitness {
        final Context context;
        Object cachedClient = new PlatformClient();

        TargetFitness(Context context) { this.context = context; }
        public Object getClient() { return cachedClient; }
        public static void openHealthConnect(BiConsumer<Integer, Intent> callback) { }
    }

    public static final class ApkClient { }
    public static final class PlatformClient { }

    public interface TargetCallback {
        void accept(Object code, Object intent);
    }

    public static final class TargetActivity extends ContextWrapper {
        static TargetActivity current;
        Intent intent;
        TargetCallback callback;

        TargetActivity(Context base) { super(base); }
        public static TargetActivity getActivity() { return current; }
        public void startActivityForResult(Intent intent, TargetCallback callback) {
            this.intent = intent;
            this.callback = callback;
        }
    }

    @Implements(value = XposedHelpers.class, isInAndroidSdk = false)
    public static final class HelpersShadow {
        @Implementation
        protected static Class<?> findClassIfExists(String name, ClassLoader loader) {
            switch (name) {
                case "com.nianticproject.ichigo.fitness.FitnessClientHealthConnect": return TargetFitness.class;
                case "com.nianticproject.ichigo.IchigoUnityPlayerActivity": return TargetActivity.class;
                case "com.nianticproject.ichigo.util.BiConsumer": return TargetCallback.class;
                case "androidx.health.connect.client.impl.HealthConnectClientImpl": return ApkClient.class;
                case "androidx.health.connect.client.impl.HealthConnectClientUpsideDownImpl": return PlatformClient.class;
                default:
                    try {
                        return Class.forName(name, false, loader);
                    } catch (ClassNotFoundException | LinkageError absent) {
                        return null;
                    }
            }
        }
    }
}
