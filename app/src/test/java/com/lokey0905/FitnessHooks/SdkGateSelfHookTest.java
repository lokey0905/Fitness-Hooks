package com.lokey0905.FitnessHooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.ext.SdkExtensions;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowPackageManager;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Exercises registered callbacks; this harness does not install native LSPosed hooks. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, instrumentedPackages = "de.robv.android.xposed", shadows = {
        SdkGateSelfHookTest.BridgeShadow.class, SdkGateSelfHookTest.HelpersShadow.class})
public final class SdkGateSelfHookTest {
    private Context context;
    private SharedPreferences preferences;

    @Before
    public void reset() {
        BridgeShadow.hooks.clear();
        BridgeShadow.logs.clear();
        context = RuntimeEnvironment.getApplication();
        preferences = context.getSharedPreferences(HookConfig.PREFS_NAME, Context.MODE_PRIVATE);
        assertTrue(preferences.edit().clear().commit());
        assertTrue(context.getSharedPreferences(ConfigTransport.TARGET_PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit());
    }

    @Test
    public void moduleEntryInstallsOnlyActivationAndBothLocalGates() throws Throwable {
        loadPackage(ConfigTransport.MODULE_PACKAGE);

        assertEquals(BridgeShadow.logs.toString(), Set.of(
                HookSettingsActivity.class.getName() + ".isModuleActive",
                PikminHealthConnectProbe.class.getName() + ".isSupported",
                PikminHealthConnectProbe.class.getName() + ".osSupportsHealthConnect"),
                registeredMethods());
        assertEquals(Boolean.TRUE, invokeBefore(
                HookSettingsActivity.class.getDeclaredMethod("isModuleActive"), null).getResult());
        assertEquals(33, Build.VERSION.SDK_INT);
    }

    @Test
    @Config(sdk = {34, 35, 36})
    public void android14AndNewerUseExtensionScopeInsteadOfSdkGateOverride() {
        int originalSdk = Build.VERSION.SDK_INT;
        loadPackage(ConfigTransport.MODULE_PACKAGE);

        assertEquals(BridgeShadow.logs.toString(), Set.of(
                HookSettingsActivity.class.getName() + ".isModuleActive",
                PikminHealthConnectProbe.class.getName() + ".isSupported",
                SdkExtensions.class.getName() + ".getExtensionVersion"), registeredMethods());
        assertEquals(originalSdk, Build.VERSION.SDK_INT);
    }

    @Test
    public void availableProviderAndDefaultSettingOverrideBothGatesWithoutTargetSettings()
            throws Throwable {
        installProvider();
        assertFalse(ConfigTransport.readTargetSettings(context).sdkHookEnabled);
        assertTrue(ConfigTransport.readModuleSettings(context).sdkHookEnabled);
        installSelf();

        for (String name : new String[] {"isSupported", "osSupportsHealthConnect"}) {
            RecordingParam result = invokeProbeBefore(name);
            assertTrue(result.resultSet);
            assertEquals(Boolean.TRUE, result.getResult());
        }
        assertEquals(33, Build.VERSION.SDK_INT);
    }

    @Test
    public void callbacksFollowSavedModuleSettingEvenWhenTargetSettingDisagrees() throws Throwable {
        installProvider();
        assertTrue(preferences.edit().putBoolean(HookConfig.KEY_SDK_HOOK_ENABLED, false).commit());
        assertTrue(context.getSharedPreferences(ConfigTransport.TARGET_PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(HookConfig.KEY_SDK_HOOK_ENABLED, true).commit());
        installSelf();

        for (String name : new String[] {"isSupported", "osSupportsHealthConnect"}) {
            assertFalse(invokeProbeBefore(name).resultSet);
        }
        assertTrue(preferences.edit().putBoolean(HookConfig.KEY_SDK_HOOK_ENABLED, true).commit());
        for (String name : new String[] {"isSupported", "osSupportsHealthConnect"}) {
            assertEquals(Boolean.TRUE, invokeProbeBefore(name).getResult());
        }
        assertTrue(preferences.edit().putBoolean(HookConfig.KEY_SDK_HOOK_ENABLED, false).commit());
        assertFalse(invokeProbeBefore("isSupported").resultSet);
    }

    @Test
    public void missingProviderDoesNotOverrideDespiteSavedEnabledSetting() throws Throwable {
        assertTrue(preferences.edit().putBoolean(HookConfig.KEY_SDK_HOOK_ENABLED, true).commit());
        installSelf();

        assertFalse(invokeProbeBefore("isSupported").resultSet);
        assertFalse(invokeProbeBefore("osSupportsHealthConnect").resultSet);
    }

    @Test
    public void unrelatedPackageDoesNotInstallAnyHooks() {
        loadPackage("example.unrelated");

        assertTrue(BridgeShadow.hooks.isEmpty());
    }

    private void loadPackage(String packageName) {
        XC_LoadPackage.LoadPackageParam param = new XC_LoadPackage.LoadPackageParam();
        param.packageName = packageName;
        param.classLoader = getClass().getClassLoader();
        new FitnessHooksEntry().handleLoadPackage(param);
    }

    private void installSelf() {
        assertEquals(BridgeShadow.logs.toString(), 2,
                SdkGateHook.installSelf(getClass().getClassLoader()));
    }

    private Set<String> registeredMethods() {
        return BridgeShadow.hooks.keySet().stream()
                .map(method -> method.getDeclaringClass().getName() + "." + method.getName())
                .collect(Collectors.toSet());
    }

    private RecordingParam invokeProbeBefore(String name) throws Throwable {
        return invokeBefore(PikminHealthConnectProbe.class.getDeclaredMethod(name),
                new PikminHealthConnectProbe(context));
    }

    private RecordingParam invokeBefore(Method method, Object receiver) throws Throwable {
        XC_MethodHook callback = BridgeShadow.hooks.get(method);
        assertNotNull(BridgeShadow.logs.toString(), callback);
        RecordingParam param = new RecordingParam();
        param.method = method;
        param.thisObject = receiver;
        param.args = new Object[0];
        Method before = callback.getClass().getDeclaredMethod(
                "beforeHookedMethod", XC_MethodHook.MethodHookParam.class);
        before.setAccessible(true);
        try {
            before.invoke(callback, param);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
        return param;
    }

    private void installProvider() {
        ApplicationInfo application = new ApplicationInfo();
        application.packageName = HealthConnectSteps.PROVIDER_PACKAGE;
        application.enabled = true;
        PackageInfo provider = new PackageInfo();
        provider.packageName = application.packageName;
        provider.applicationInfo = application;
        provider.setLongVersionCode(68623);
        ShadowPackageManager packages = Shadow.extract(context.getPackageManager());
        packages.installPackage(provider);

        ServiceInfo service = new ServiceInfo();
        service.packageName = application.packageName;
        service.name = application.packageName + ".HealthDataService";
        service.applicationInfo = application;
        service.enabled = true;
        service.exported = true;
        ResolveInfo result = new ResolveInfo();
        result.serviceInfo = service;
        packages.addResolveInfoForIntent(new Intent("androidx.health.ACTION_BIND_HEALTH_DATA_SERVICE")
                .setPackage(application.packageName), result);
    }

    public static final class RecordingParam extends XC_MethodHook.MethodHookParam {
        boolean resultSet;
        private Object result;

        @Override
        public Object getResult() {
            return result;
        }

        @Override
        public void setResult(Object result) {
            this.result = result;
            resultSet = true;
        }
    }

    @Implements(value = XposedBridge.class, isInAndroidSdk = false)
    public static final class BridgeShadow {
        static final Map<Member, XC_MethodHook> hooks = new LinkedHashMap<>();
        static final List<String> logs = new ArrayList<>();

        @Implementation
        protected static XC_MethodHook.Unhook hookMethod(Member method, XC_MethodHook callback) {
            hooks.put(method, callback);
            return null;
        }

        @Implementation
        protected static void log(String message) {
            logs.add(message);
        }

        @Implementation
        protected static void log(Throwable failure) {
            logs.add(failure.toString());
        }
    }

    @Implements(value = XposedHelpers.class, isInAndroidSdk = false)
    public static final class HelpersShadow {
        @Implementation
        protected static Class<?> findClassIfExists(String name, ClassLoader loader) {
            try {
                return Class.forName(name, false, loader);
            } catch (ClassNotFoundException | LinkageError ignored) {
                return null;
            }
        }
    }
}
