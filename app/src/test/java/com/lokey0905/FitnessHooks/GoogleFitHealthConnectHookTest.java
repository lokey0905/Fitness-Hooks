package com.lokey0905.FitnessHooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/** Real hook callbacks with contract fixtures; does not claim native interception or Fit sync. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, instrumentedPackages = "de.robv.android.xposed", shadows = {
        StandaloneProviderHookTest.BridgeShadow.class, GoogleFitHealthConnectHookTest.HelpersShadow.class,
        GoogleFitHealthConnectHookTest.ProviderShadow.class})
public final class GoogleFitHealthConnectHookTest {
    private static final String SYSTEM_SETTINGS = "android.health.connect.action.HEALTH_HOME_SETTINGS";
    private static final String APK_GRANTS = "granted_permissions_string";
    private static final String PLATFORM_PERMISSIONS = "androidx.activity.result.contract.extra.PERMISSIONS";
    private static final String PLATFORM_GRANTS = "androidx.activity.result.contract.extra.PERMISSION_GRANT_RESULTS";
    private static final String READ = "android.permission.health.READ_STEPS";
    private static final String WRITE = "android.permission.health.WRITE_STEPS";
    private Context context;

    @Before
    public void reset() {
        StandaloneProviderHookTest.BridgeShadow.hooks.clear();
        StandaloneProviderHookTest.BridgeShadow.logs.clear();
        StandaloneProviderHookTest.BridgeShadow.failAt = 0;
        StandaloneProviderHookTest.BridgeShadow.attempts = 0;
        ((Set<?>) ReflectionHelpers.getStaticField(GoogleFitHealthConnectHook.class, "INSTALLED")).clear();
        HelpersShadow.classes.clear();
        HelpersShadow.classes.put("gkv", Factory.class);
        HelpersShadow.classes.put("bsy", Status.class);
        HelpersShadow.classes.put("bsz", HealthClient.class);
        HelpersShadow.classes.put("btc", PermissionClient.class);
        HelpersShadow.classes.put("btt", ApkClient.class);
        HelpersShadow.classes.put("bud", PlatformClient.class);
        HelpersShadow.classes.put("qw", Contract.class);
        HelpersShadow.classes.put("btg", PermissionContract.class);
        HelpersShadow.classes.put("buj", ApkContract.class);
        HelpersShadow.classes.put("buk", PlatformContract.class);
        context = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public String getPackageName() { return ConfigTransport.FIT_PACKAGE; }
        };
        ReflectionHelpers.setStaticField(TargetConfigBridge.class, "applicationContext", context);
        select(false);
        ReflectionHelpers.setStaticField(GoogleFitRoute.class, "route", null);
        ReflectionHelpers.setStaticField(GoogleFitRoute.class, "hooksReady", true);
        ReflectionHelpers.setStaticField(GoogleFitRoute.class, "restartLogged", false);
        ProviderShadow.status = StandaloneHealthConnect.SDK_AVAILABLE;
        ProviderShadow.calls = 0;
        ApkClient.created = 0;
        ApkClient.failConstruction = false;
    }

    @Test
    @Config(sdk = {28, 33})
    public void olderAndroidInstallsNothing() {
        select(true);
        assertEquals(0, install());
        assertTrue(StandaloneProviderHookTest.BridgeShadow.hooks.isEmpty());
    }

    @Test
    public void validatesAndInstallsExactlyFiveMethodsOnce() throws Exception {
        assertEquals(5, install());
        assertEquals(Set.of(factory(), status(), create(), parse(), settings()),
                StandaloneProviderHookTest.BridgeShadow.hooks.keySet());
        assertEquals(0, install());
    }

    @Test
    public void unselectedModeKeepsFactoryStatusPermissionsAndSettingsOriginal() throws Throwable {
        assertEquals(5, install());
        PermissionContract contract = new PermissionContract();
        Contract original = contract.a;
        assertFalse(before(factory(), new Factory(context)).resultSet);
        assertFalse(before(status(), null, context).resultSet);
        assertFalse(before(create(), contract, context, Set.of(READ)).resultSet);
        assertSame(original, contract.a);
        Intent intent = new Intent(SYSTEM_SETTINGS);
        assertSame(intent, before(settings(), new FitActivity(), intent, 11, null).args[0]);
        assertEquals(0, ProviderShadow.calls);
    }

    @Test
    public void pendingConfigurationSuppressesEveryProviderSpecificOriginal() throws Throwable {
        select(null);
        assertEquals(5, install());
        RecordingParam factoryResult = before(factory(), new Factory(context));
        assertTrue(factoryResult.resultSet);
        assertNull(factoryResult.getResult());
        assertTrue(factoryResult.failure instanceof IllegalStateException);

        RecordingParam statusResult = before(status(), null, context);
        assertTrue(statusResult.resultSet);
        assertEquals(StandaloneHealthConnect.SDK_UNAVAILABLE, statusResult.getResult());
        assertNull(statusResult.failure);

        PermissionContract contract = new PermissionContract();
        Contract original = contract.a;
        RecordingParam permissionResult = before(create(), contract, context, Set.of(READ));
        assertTrue(permissionResult.resultSet);
        assertTrue(permissionResult.failure instanceof IllegalStateException);
        assertSame(original, contract.a);

        Intent intent = new Intent(SYSTEM_SETTINGS);
        RecordingParam settingsResult = before(settings(), new FitActivity(), intent, 11, null);
        assertTrue(settingsResult.resultSet);
        assertTrue(settingsResult.failure instanceof IllegalStateException);
        assertSame(intent, settingsResult.args[0]);
        assertEquals(0, ProviderShadow.calls);
        assertEquals(0, ApkClient.created);
        assertNull(ReflectionHelpers.getStaticField(GoogleFitRoute.class, "route"));
    }

    @Test
    public void pendingConfigurationDoesNotBlockUnrelatedActivityIntents() throws Throwable {
        select(null);
        assertEquals(5, install());
        for (Intent intent : new Intent[]{null, new Intent(Intent.ACTION_VIEW),
                new Intent(SYSTEM_SETTINGS + ".OTHER")}) {
            RecordingParam result = before(settings(), new FitActivity(), intent, 11, null);
            assertFalse(result.resultSet);
            assertNull(result.failure);
            assertSame(intent, result.args[0]);
        }
        assertNull(ReflectionHelpers.getStaticField(GoogleFitRoute.class, "route"));
    }

    @Test
    public void lateVerifiedApkChoiceResumesPreviouslyBlockedOperations() throws Throwable {
        select(null);
        assertEquals(5, install());
        assertTrue(before(factory(), new Factory(context)).failure instanceof IllegalStateException);
        select(true);
        assertTrue(call(factory(), new Factory(context)) instanceof ApkClient);
        assertEquals(StandaloneHealthConnect.SDK_AVAILABLE, call(status(), null, context));
        PermissionContract contract = new PermissionContract();
        Intent request = (Intent) call(create(), contract, context, Set.of(READ));
        assertEquals(StandaloneHealthConnect.PROVIDER_PACKAGE, request.getPackage());
        Intent original = new Intent(SYSTEM_SETTINGS);
        RecordingParam settingsResult = before(settings(), new FitActivity(), original, 11, null);
        assertFalse(settingsResult.resultSet);
        assertEquals(StandaloneHealthConnect.PROVIDER_PACKAGE,
                ((Intent) settingsResult.args[0]).getPackage());
        assertEquals(Boolean.TRUE, ReflectionHelpers.getStaticField(GoogleFitRoute.class, "route"));
    }

    @Test
    public void lateVerifiedSystemChoiceKeepsOriginalCallsAndRequiresRestartToChange() throws Throwable {
        select(null);
        assertEquals(5, install());
        assertEquals(StandaloneHealthConnect.SDK_UNAVAILABLE, call(status(), null, context));
        select(false);
        assertFalse(before(factory(), new Factory(context)).resultSet);
        assertFalse(before(status(), null, context).resultSet);
        PermissionContract contract = new PermissionContract();
        assertFalse(before(create(), contract, context, Set.of(READ)).resultSet);
        assertTrue(contract.a instanceof PlatformContract);
        Intent original = new Intent(SYSTEM_SETTINGS);
        RecordingParam settingsResult = before(settings(), new FitActivity(), original, 11, null);
        assertFalse(settingsResult.resultSet);
        assertSame(original, settingsResult.args[0]);

        select(true);
        assertTrue(call(factory(), new Factory(context)) instanceof PlatformClient);
        call(create(), contract, context, Set.of(READ));
        assertTrue(contract.a instanceof PlatformContract);
        assertSame(original, before(settings(), new FitActivity(), original, 11, null).args[0]);
        assertEquals(0, ProviderShadow.calls);
        assertEquals(Boolean.FALSE, ReflectionHelpers.getStaticField(GoogleFitRoute.class, "route"));
        assertEquals(Boolean.TRUE, ReflectionHelpers.getStaticField(GoogleFitRoute.class, "restartLogged"));
    }

    @Test
    public void selectedFactoryUsesFreshApkClientsAndPreservesFeatureImplementation() throws Throwable {
        select(true);
        assertEquals(5, install());
        Factory factory = new Factory(context);
        Object first = call(factory(), factory);
        Object second = call(factory(), factory);
        assertTrue(first instanceof ApkClient);
        assertTrue(second instanceof ApkClient);
        assertNotSame(first, second);
        assertSame(context, ((ApkClient) first).context);
        assertEquals(23, ((ApkClient) first).a());
        assertSame(first, ((ApkClient) first).b());
        assertEquals(2, ApkClient.created);
        assertEquals(34, Build.VERSION.SDK_INT);
        assertEquals(StandaloneHealthConnect.SDK_AVAILABLE, call(status(), null, context));
    }

    @Test
    public void missingProviderReportsRealStatusAndCannotFallBackToFramework() throws Throwable {
        select(true);
        ProviderShadow.status = StandaloneHealthConnect.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED;
        assertEquals(5, install());
        assertEquals(ProviderShadow.status, call(status(), null, context));
        RecordingParam result = before(factory(), new Factory(context));
        assertTrue(result.resultSet);
        assertNull(result.getResult());
        assertTrue(result.failure instanceof IllegalStateException);
        assertEquals(0, ApkClient.created);
    }

    @Test
    public void constructorFailureSuppressesOriginalAndReportsItsCause() throws Throwable {
        select(true);
        ApkClient.failConstruction = true;
        assertEquals(5, install());
        RecordingParam result = before(factory(), new Factory(context));
        assertTrue(result.resultSet);
        assertNull(result.getResult());
        assertTrue(result.failure instanceof SecurityException);
    }

    @Test
    public void missingOrForeignProcessContextDoesNotActivateHooks() throws Throwable {
        select(true);
        assertEquals(5, install());
        ReflectionHelpers.setStaticField(TargetConfigBridge.class, "applicationContext", null);
        assertFalse(before(factory(), new Factory(context)).resultSet);
        ReflectionHelpers.setStaticField(TargetConfigBridge.class, "applicationContext",
                RuntimeEnvironment.getApplication());
        assertFalse(before(factory(), new Factory(context)).resultSet);
        assertEquals(0, ProviderShadow.calls);
    }

    @Test
    public void incompleteSyncStateInstallationCannotActivateClientHooks() throws Throwable {
        select(true);
        ReflectionHelpers.setStaticField(GoogleFitRoute.class, "hooksReady", false);
        assertEquals(5, install());
        assertFalse(before(factory(), new Factory(context)).resultSet);
        assertFalse(before(status(), null, context).resultSet);
        PermissionContract contract = new PermissionContract();
        Contract original = contract.a;
        before(parse(), contract, Activity.RESULT_OK, apkResult(READ));
        before(create(), contract, context, Set.of(READ));
        assertSame(original, contract.a);
    }

    @Test
    public void malformedFactoryFieldOrMissingClientStopsInstallationBeforeAnyHook() {
        HelpersShadow.classes.put("gkv", WrongFactory.class);
        assertEquals(0, install());
        assertTrue(StandaloneProviderHookTest.BridgeShadow.hooks.isEmpty());
        HelpersShadow.classes.put("gkv", Factory.class);
        HelpersShadow.classes.remove("bud");
        assertEquals(0, install());
        assertTrue(StandaloneProviderHookTest.BridgeShadow.hooks.isEmpty());
    }

    @Test
    public void partialInstallationDoesNotChangePreviouslyInstalledCallbacks() throws Throwable {
        select(true);
        StandaloneProviderHookTest.BridgeShadow.failAt = 3;
        assertEquals(0, install());
        assertFalse(before(factory(), new Factory(context)).resultSet);
        assertFalse(before(status(), null, context).resultSet);
        assertEquals(0, ProviderShadow.calls);
        StandaloneProviderHookTest.BridgeShadow.failAt = 0;
        assertEquals(5, install());
        assertTrue(call(factory(), new Factory(context)) instanceof ApkClient);
    }

    @Test
    public void apkPermissionDelegateRetainsOriginalInputValidationAndPayload() throws Throwable {
        select(true);
        assertEquals(5, install());
        PermissionContract contract = new PermissionContract();
        Intent intent = (Intent) call(create(), contract, context, Set.of(READ, WRITE));
        assertEquals("androidx.health.ACTION_REQUEST_PERMISSIONS", intent.getAction());
        assertEquals(StandaloneHealthConnect.PROVIDER_PACKAGE, intent.getPackage());
        assertEquals(Set.of(READ, WRITE), new LinkedHashSet<>(
                intent.getStringArrayListExtra("requested_permissions_string")));
        assertTrue(contract.a instanceof ApkContract);
        assertThrows(IllegalArgumentException.class,
                () -> callUnchecked(create(), contract, context, Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> callUnchecked(create(), contract, context, Set.of("android.permission.CAMERA")));
    }

    @Test
    public void apkResultAfterRecreationParsesOnlyActualGrantsEvenWhenModeIsOff() throws Throwable {
        assertEquals(5, install());
        PermissionContract recreated = new PermissionContract();
        assertTrue(recreated.a instanceof PlatformContract);
        assertEquals(Set.of(READ), call(parse(), recreated, Activity.RESULT_OK, apkResult(READ)));
        assertTrue(recreated.a instanceof ApkContract);
        assertEquals(Set.of(), call(parse(), recreated, Activity.RESULT_CANCELED, null));
        Intent nextRequest = (Intent) call(create(), recreated, context, Set.of(READ));
        assertTrue(recreated.a instanceof PlatformContract);
        assertEquals("androidx.activity.result.contract.action.REQUEST_PERMISSIONS", nextRequest.getAction());
    }

    @Test
    public void actualPermissionResultsRemainParseableWhileConfigurationIsPending() throws Throwable {
        select(null);
        assertEquals(5, install());
        PermissionContract contract = new PermissionContract();
        assertEquals(Set.of(READ), call(parse(), contract, Activity.RESULT_OK, apkResult(READ)));
        assertTrue(contract.a instanceof ApkContract);
        Intent platformResult = new Intent().putExtra(PLATFORM_PERMISSIONS, new String[]{READ, WRITE})
                .putExtra(PLATFORM_GRANTS, new int[]{0, -1});
        assertEquals(Set.of(READ), call(parse(), contract, Activity.RESULT_OK, platformResult));
        assertTrue(contract.a instanceof PlatformContract);
        assertNull(ReflectionHelpers.getStaticField(GoogleFitRoute.class, "route"));
    }

    @Test
    public void platformResultSelectsPlatformParserAndKeepsDeniedPermissionDenied() throws Throwable {
        select(true);
        assertEquals(5, install());
        PermissionContract contract = new PermissionContract();
        call(create(), contract, context, Set.of(READ, WRITE));
        select(false);
        Intent result = new Intent().putExtra(PLATFORM_PERMISSIONS, new String[]{READ, WRITE})
                .putExtra(PLATFORM_GRANTS, new int[]{0, -1});
        assertEquals(Set.of(READ), call(parse(), contract, Activity.RESULT_OK, result));
        assertTrue(contract.a instanceof PlatformContract);
        assertEquals(Set.of(), call(parse(), contract, Activity.RESULT_CANCELED, result));
        // The live setting changed, but this process remains pinned to its original APK route.
        call(create(), contract, context, Set.of(READ));
        assertTrue(contract.a instanceof ApkContract);
    }

    @Test
    public void unknownOrAmbiguousResultsKeepTheExistingDelegate() throws Throwable {
        assertEquals(5, install());
        PermissionContract contract = new PermissionContract();
        Contract original = contract.a;
        assertFalse(before(parse(), contract, Activity.RESULT_OK, new Intent()).resultSet);
        assertSame(original, contract.a);
        Intent ambiguous = apkResult(READ).putExtra(PLATFORM_PERMISSIONS, new String[]{WRITE});
        assertFalse(before(parse(), contract, Activity.RESULT_OK, ambiguous).resultSet);
        assertSame(original, contract.a);
    }

    @Test
    public void settingsRedirectCopiesOnlyExactSystemActionAndPreservesLaunchArguments() throws Throwable {
        select(true);
        assertEquals(5, install());
        Intent original = new Intent(SYSTEM_SETTINGS)
                .setComponent(new ComponentName("system.controller", "system.controller.Home"))
                .putExtra("from_fit", "keep").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Bundle options = new Bundle();
        RecordingParam param = before(settings(), new FitActivity(), original, 123, options);
        Intent redirected = (Intent) param.args[0];
        assertNotSame(original, redirected);
        assertEquals(SYSTEM_SETTINGS, original.getAction());
        assertNotNull(original.getComponent());
        assertEquals(StandaloneHealthConnect.SETTINGS_ACTION, redirected.getAction());
        assertEquals(StandaloneHealthConnect.PROVIDER_PACKAGE, redirected.getPackage());
        assertNull(redirected.getComponent());
        assertEquals("keep", redirected.getStringExtra("from_fit"));
        assertEquals(original.getFlags(), redirected.getFlags());
        assertEquals(123, param.args[1]);
        assertSame(options, param.args[2]);
        Intent unrelated = new Intent(Intent.ACTION_VIEW).putExtra("from_fit", "unchanged");
        assertSame(unrelated, before(settings(), new FitActivity(), unrelated, 1, null).args[0]);
    }

    private int install() { return GoogleFitHealthConnectHook.install(getClass().getClassLoader()); }
    private void select(Boolean enabled) {
        ReflectionHelpers.setStaticField(TargetConfigBridge.class, "standaloneRouteSelection", enabled);
    }
    private Method factory() throws Exception { return Factory.class.getDeclaredMethod("B"); }
    private Method status() throws Exception { return Status.class.getDeclaredMethod("a", Context.class); }
    private Method create() throws Exception {
        return PermissionContract.class.getDeclaredMethod("a", Context.class, Object.class);
    }
    private Method parse() throws Exception {
        return PermissionContract.class.getDeclaredMethod("b", int.class, Intent.class);
    }
    private Method settings() throws Exception {
        return Activity.class.getDeclaredMethod("startActivityForResult", Intent.class, int.class, Bundle.class);
    }
    private RecordingParam before(Method method, Object receiver, Object... args) throws Throwable {
        XC_MethodHook hook = StandaloneProviderHookTest.BridgeShadow.hooks.get(method);
        assertNotNull(StandaloneProviderHookTest.BridgeShadow.logs.toString(), hook);
        RecordingParam param = new RecordingParam();
        param.method = method;
        param.thisObject = receiver;
        param.args = args;
        Method before = hook.getClass().getDeclaredMethod("beforeHookedMethod", XC_MethodHook.MethodHookParam.class);
        before.setAccessible(true);
        try { before.invoke(hook, param); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
        return param;
    }
    private Object call(Method method, Object receiver, Object... args) throws Throwable {
        RecordingParam param = before(method, receiver, args);
        if (param.failure != null) throw param.failure;
        if (param.resultSet) return param.getResult();
        try { return method.invoke(receiver, param.args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    private Object callUnchecked(Method method, Object receiver, Object... args) throws Exception {
        try { return call(method, receiver, args); }
        catch (Exception failure) { throw failure; }
        catch (Throwable failure) { throw new AssertionError(failure); }
    }
    private static Intent apkResult(String... permissions) {
        ArrayList<Bundle> payload = new ArrayList<>();
        for (String name : permissions) {
            Bundle value = new Bundle();
            value.putString("permission", name);
            payload.add(value);
        }
        return new Intent().putParcelableArrayListExtra(APK_GRANTS, payload);
    }

    public interface PermissionClient {}
    public interface HealthClient { int a(); PermissionClient b(); }
    public static final class Factory {
        public final Object a;
        public final Object b = new Object();
        public Factory(Context context) { a = context; }
        public final int A() { return Status.a((Context) a); }
        public final HealthClient B() { return new PlatformClient((Context) a); }
    }
    public static final class WrongFactory {
        public final Context a = null;
        public final Object b = new Object();
        public final int A() { return 3; }
        public final HealthClient B() { return null; }
    }
    public static final class Status {
        public static final String a = SYSTEM_SETTINGS;
        public static final int a(Context context) { return 42; }
    }
    public static final class ApkClient implements HealthClient, PermissionClient {
        static int created;
        static boolean failConstruction;
        final Context context;
        public ApkClient(Context context) {
            if (failConstruction) throw new SecurityException("real provider construction failed");
            this.context = context;
            created++;
        }
        public final int a() { return 23; }
        public final PermissionClient b() { return this; }
    }
    public static final class PlatformClient implements HealthClient, PermissionClient {
        public PlatformClient(Context context) {}
        public final int a() { return 42; }
        public final PermissionClient b() { return this; }
    }
    public abstract static class Contract {
        public abstract Intent a(Context context, Object requested);
        public abstract Object b(int resultCode, Intent result);
    }
    public static final class PermissionContract extends Contract {
        private final Contract a;
        public PermissionContract() { a = new PlatformContract(); }
        public final Intent a(Context context, Object requested) {
            Set<?> permissions = (Set<?>) requested;
            if (permissions.isEmpty()) throw new IllegalArgumentException("At least one permission is required!");
            for (Object permission : permissions) {
                if (!((String) permission).startsWith("android.permission.health.")) {
                    throw new IllegalArgumentException("Unsupported health connect permission");
                }
            }
            return a.a(context, requested);
        }
        public final Object b(int resultCode, Intent result) { return a.b(resultCode, result); }
    }
    public static final class ApkContract extends Contract {
        public ApkContract() {}
        @SuppressWarnings("unchecked")
        public final Intent a(Context context, Object requested) {
            return new Intent("androidx.health.ACTION_REQUEST_PERMISSIONS")
                    .setPackage(StandaloneHealthConnect.PROVIDER_PACKAGE)
                    .putStringArrayListExtra("requested_permissions_string", new ArrayList<>((Set<String>) requested));
        }
        public final Object b(int resultCode, Intent result) {
            Set<String> grants = new LinkedHashSet<>();
            if (result != null) {
                ArrayList<Bundle> payload = result.getParcelableArrayListExtra(APK_GRANTS);
                if (payload != null) for (Bundle value : payload) grants.add(value.getString("permission"));
            }
            return grants;
        }
    }
    public static final class PlatformContract extends Contract {
        // The real R8 DEX has no buk constructor; keep this inaccessible to the hook so
        // tests require obtaining the instance from PermissionContract's native constructor.
        private PlatformContract() {}
        public final Intent a(Context context, Object requested) {
            return new Intent("androidx.activity.result.contract.action.REQUEST_PERMISSIONS");
        }
        public final Object b(int resultCode, Intent result) {
            Set<String> grants = new LinkedHashSet<>();
            if (resultCode != Activity.RESULT_OK || result == null) return grants;
            String[] permissions = result.getStringArrayExtra(PLATFORM_PERMISSIONS);
            int[] results = result.getIntArrayExtra(PLATFORM_GRANTS);
            if (permissions == null || results == null) return grants;
            for (int i = 0; i < Math.min(permissions.length, results.length); i++) {
                if (results[i] == 0) grants.add(permissions[i]);
            }
            return grants;
        }
    }
    public static final class FitActivity extends Activity {
        @Override public String getPackageName() { return ConfigTransport.FIT_PACKAGE; }
    }
    public static final class RecordingParam extends XC_MethodHook.MethodHookParam {
        boolean resultSet;
        Throwable failure;
        private Object result;
        @Override public Object getResult() { return result; }
        @Override public void setResult(Object value) { result = value; resultSet = true; failure = null; }
        public void setThrowable(Throwable value) { failure = value; result = null; resultSet = true; }
    }
    @Implements(value = XposedHelpers.class, isInAndroidSdk = false)
    public static final class HelpersShadow {
        static final Map<String, Class<?>> classes = new LinkedHashMap<>();
        @Implementation protected static Class<?> findClassIfExists(String name, ClassLoader loader) {
            return classes.get(name);
        }
    }
    @Implements(value = StandaloneHealthConnect.class, isInAndroidSdk = false)
    public static final class ProviderShadow {
        static int status;
        static int calls;
        @Implementation protected static int sdkStatus(Context context) { calls++; return status; }
    }
}
