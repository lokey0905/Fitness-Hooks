package com.lokey0905.FitnessHooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Parcelable;
import android.os.ext.SdkExtensions;

import androidx.activity.result.contract.ActivityResultContract;
import androidx.health.connect.client.HealthConnectClient;
import androidx.health.connect.client.HealthConnectFeatures;

import java.util.ArrayList;
import java.util.Set;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;

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
import org.robolectric.shadows.ShadowApplication;

/** Exercises the real AndroidX APK client and permission contracts without a device or HC RPC. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, shadows = StandaloneModuleRoutingTest.ExtensionShadow.class)
public final class StandaloneModuleRoutingTest {
    private static final String APK_SETTINGS = "androidx.health.ACTION_HEALTH_CONNECT_SETTINGS";
    private static final String APK_REQUEST = "androidx.health.ACTION_REQUEST_PERMISSIONS";
    private static final String SYSTEM_REQUEST = "androidx.activity.result.contract.action.REQUEST_PERMISSIONS";
    private static final String WRITE_STEPS = "android.permission.health.WRITE_STEPS";
    private Context context;
    private ShadowPackageManager packages;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        packages = Shadow.extract(context.getPackageManager());
        context.getSharedPreferences(HookConfig.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit();
        selectApk(true);
        ExtensionShadow.calls = 0;
    }

    @Test
    public void missingSelectedApkRequiresInstallAndNeverCreatesSystemClient() {
        assertEquals(HealthConnectSteps.Availability.INSTALL_REQUIRED,
                HealthConnectSteps.availability(context));
        assertTrue(ConfigTransport.readModuleSettings(context).standaloneHcEnabled);
        assertThrows(IllegalStateException.class, () -> HealthConnectSteps.createClient(context));
        assertFalse(new PikminHealthConnectProbe(context).isSupported());
    }

    @Test
    public void oldApkRequiresUpdateRatherThanPlatformFallback() {
        installApk(171301, true);
        assertEquals(HealthConnectSteps.Availability.UPDATE_REQUIRED,
                HealthConnectSteps.availability(context));
        assertThrows(IllegalStateException.class, () -> HealthConnectSteps.createClient(context));
    }

    @Test
    public void missingApkStillStopsWhenTheFrameworkProviderIsAvailable() throws Exception {
        FrameworkContext framework = new FrameworkContext(context);
        Class<?> serviceType = Class.forName("android.health.connect.aidl.IHealthConnectService");
        Object service = Proxy.newProxyInstance(serviceType.getClassLoader(),
                new Class<?>[] {serviceType}, (proxy, method, args) -> {
                    throw new AssertionError("No framework data/permission RPC expected: " + method.getName());
                });
        Constructor<?> constructor = Class.forName("android.health.connect.HealthConnectManager")
                .getDeclaredConstructor(Context.class, serviceType);
        constructor.setAccessible(true);
        framework.manager = constructor.newInstance(framework, service);
        assertEquals(HealthConnectClient.SDK_AVAILABLE, HealthConnectClient.getSdkStatus(framework));
        assertEquals(HealthConnectSteps.Availability.INSTALL_REQUIRED,
                HealthConnectSteps.availability(framework));
        assertThrows(IllegalStateException.class, () -> HealthConnectSteps.createClient(framework));
    }

    @Test
    public void apkWithoutBindableServiceCannotBeUsed() {
        installApk(171302, false);
        assertEquals(HealthConnectSteps.Availability.UPDATE_REQUIRED,
                HealthConnectSteps.availability(context));
        assertThrows(IllegalStateException.class, () -> HealthConnectSteps.createClient(context));
    }

    @Test
    @Config(sdk = {34, 35, 36})
    public void selectedApkUsesRealApkClientFeaturesAndLocalProbeWithoutExtensionOverride() {
        installApk(171302, true);
        assertEquals(HealthConnectSteps.Availability.AVAILABLE,
                HealthConnectSteps.availability(context));
        HealthConnectClient client = HealthConnectSteps.createClient(context);
        assertEquals("androidx.health.connect.client.impl.HealthConnectClientImpl",
                client.getClass().getName());
        assertEquals("androidx.health.connect.client.feature.HealthConnectFeaturesApkImpl",
                client.getFeatures().getClass().getName());
        assertEquals(HealthConnectFeatures.FEATURE_STATUS_AVAILABLE,
                client.getFeatures().getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND));
        PikminHealthConnectProbe probe = new PikminHealthConnectProbe(context);
        assertTrue(probe.isSupported());
        assertTrue(probe.osSupportsHealthConnect());
        assertEquals(0, ExtensionShadow.calls);
        assertEquals(12, SdkExtensions.getExtensionVersion(34));
    }

    @Test
    public void disablingSelectionRestoresNormalFrameworkSelection() {
        installApk(171302, true);
        selectApk(false);
        // This test context has no framework HC service. An installed APK must not change that.
        assertEquals(HealthConnectClient.SDK_UNAVAILABLE, HealthConnectSteps.sdkStatus(context));
        assertThrows(UnsupportedOperationException.class, () -> HealthConnectSteps.createClient(context));
        assertEquals("android.health.connect.action.HEALTH_HOME_SETTINGS",
                HealthConnectSteps.settingsIntent(context).getAction());
        assertNull(HealthConnectSteps.settingsIntent(context).getPackage());
    }

    @Test
    public void selectedSettingsExplicitlyTargetApkAndDoNotOpenSystemUi() {
        installApk(171302, true);
        Intent settings = HealthConnectSteps.settingsIntent(context);
        assertEquals(APK_SETTINGS, settings.getAction());
        assertEquals(HealthConnectSteps.PROVIDER_PACKAGE, settings.getPackage());
        assertTrue((settings.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
    }

    @Test
    public void registeredPermissionContractFollowsSelectionAtLaunchTime() {
        selectApk(false);
        ActivityResultContract<Set<String>, Set<String>> contract = HealthConnectSteps.permissionContract();
        assertEquals(SYSTEM_REQUEST, contract.createIntent(context, HealthConnectSteps.writePermissions()).getAction());
        selectApk(true);
        Intent request = contract.createIntent(context, HealthConnectSteps.writePermissions());
        assertEquals(APK_REQUEST, request.getAction());
        assertEquals(HealthConnectSteps.PROVIDER_PACKAGE, request.getPackage());
        assertTrue(request.hasExtra("requested_permissions_string"));
        selectApk(false);
        assertEquals(SYSTEM_REQUEST, contract.createIntent(context, HealthConnectSteps.writePermissions()).getAction());
    }

    @Test
    public void systemGrantDoesNotShortCircuitApkPermissionRequest() {
        ShadowApplication application = Shadow.extract(context);
        application.grantPermissions(WRITE_STEPS);
        ActivityResultContract<Set<String>, Set<String>> contract = HealthConnectSteps.permissionContract();
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(WRITE_STEPS));
        assertNull(contract.getSynchronousResult(context, HealthConnectSteps.writePermissions()));
        assertEquals(APK_REQUEST, contract.createIntent(context, HealthConnectSteps.writePermissions()).getAction());
    }

    @Test
    @SuppressWarnings("deprecation")
    public void recreatedContractParsesApkResultEvenAfterProviderSelectionChanges() {
        ActivityResultContract<Set<String>, Set<String>> contract = HealthConnectSteps.permissionContract();
        Intent request = contract.createIntent(context, HealthConnectSteps.writePermissions());
        ArrayList<Parcelable> permissions = request.getParcelableArrayListExtra("requested_permissions_string");
        assertNotNull(permissions);
        Intent response = new Intent().putParcelableArrayListExtra("granted_permissions_string", permissions);
        selectApk(false);
        assertEquals(Set.of(WRITE_STEPS), HealthConnectSteps.permissionContract().parseResult(Activity.RESULT_OK, response));
        assertTrue(HealthConnectSteps.permissionContract().parseResult(Activity.RESULT_CANCELED, null).isEmpty());
    }

    @Test
    public void recreatedContractParsesSystemResultEvenAfterSelectingApk() {
        Intent response = new Intent()
                .putExtra("androidx.activity.result.contract.extra.PERMISSIONS", new String[] {WRITE_STEPS})
                .putExtra("androidx.activity.result.contract.extra.PERMISSION_GRANT_RESULTS",
                        new int[] {PackageManager.PERMISSION_GRANTED});
        assertEquals(Set.of(WRITE_STEPS), HealthConnectSteps.permissionContract().parseResult(Activity.RESULT_OK, response));
    }

    @Test
    @Config(sdk = 33)
    public void android13KeepsItsExistingApkRouteWithoutExperimentalSelection() {
        installApk(171302, true);
        assertFalse(StandaloneHealthConnect.isModuleSelected(context));
        assertEquals("androidx.health.connect.client.impl.HealthConnectClientImpl",
                HealthConnectSteps.createClient(context).getClass().getName());
        assertEquals(APK_REQUEST, HealthConnectSteps.permissionContract()
                .createIntent(context, HealthConnectSteps.writePermissions()).getAction());
    }

    private void selectApk(boolean selected) {
        assertTrue(context.getSharedPreferences(HookConfig.PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(HookConfig.KEY_EXPERIMENTAL_FEATURES_ENABLED, true)
                .putBoolean(HookConfig.KEY_STANDALONE_HC_ENABLED, selected).commit());
    }

    private void installApk(long version, boolean bindable) {
        ApplicationInfo app = new ApplicationInfo();
        app.packageName = HealthConnectSteps.PROVIDER_PACKAGE;
        app.enabled = true;
        PackageInfo provider = new PackageInfo();
        provider.packageName = app.packageName;
        provider.applicationInfo = app;
        provider.setLongVersionCode(version);
        packages.installPackage(provider);
        if (bindable) {
            ServiceInfo service = new ServiceInfo();
            service.packageName = app.packageName;
            service.name = app.packageName + ".HealthDataService";
            service.applicationInfo = app;
            service.enabled = true;
            service.exported = true;
            ResolveInfo resolve = new ResolveInfo();
            resolve.serviceInfo = service;
            packages.addResolveInfoForIntent(new Intent("androidx.health.ACTION_BIND_HEALTH_DATA_SERVICE")
                    .setPackage(app.packageName), resolve);
        }
    }

    @Implements(className = "android.os.ext.SdkExtensions", minSdk = 30)
    public static final class ExtensionShadow {
        static int calls;

        @Implementation
        protected static int getExtensionVersion(int sdk) {
            calls++;
            return 12;
        }
    }

    private static final class FrameworkContext extends ContextWrapper {
        Object manager;

        FrameworkContext(Context base) {
            super(base);
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public Object getSystemService(String name) {
            return "healthconnect".equals(name) ? manager : super.getSystemService(name);
        }
    }
}
