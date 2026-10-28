package com.lokey0905.FitnessHooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.UserManager;
import android.os.ext.SdkExtensions;

import androidx.health.connect.client.HealthConnectClient;
import androidx.health.connect.client.HealthConnectFeatures;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowUserManager;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class StandaloneHealthConnectTest {
    private Context context;

    @Before
    public void reset() {
        context = RuntimeEnvironment.getApplication();
        assertTrue(context.getSharedPreferences(HookConfig.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit());
        ((ShadowUserManager) Shadow.extract(context.getSystemService(UserManager.class))).setManagedProfile(false);
    }

    @Test
    @Config(sdk = {28, 33})
    public void olderAndroidKeepsExistingRouting() {
        saveChoices(true, true);
        installProvider(context, true, StandaloneHealthConnect.MIN_VERSION_CODE, true, true);
        assertFalse(StandaloneHealthConnect.isModuleSelected(context));
        assertEquals(StandaloneHealthConnect.SDK_UNAVAILABLE, StandaloneHealthConnect.sdkStatus(context));
        assertThrows(IllegalStateException.class,
                () -> StandaloneHealthConnect.createClient(context, getClass().getClassLoader()));
    }

    @Test
    public void rawSelectionSurvivesMissingProviderAndRequiresBothChoices() {
        assertFalse(StandaloneHealthConnect.isModuleSelected(context));
        saveChoices(true, false);
        assertFalse(StandaloneHealthConnect.isModuleSelected(context));
        saveChoices(false, true);
        assertFalse(StandaloneHealthConnect.isModuleSelected(context));
        saveChoices(true, true);
        assertTrue(StandaloneHealthConnect.isModuleSelected(context));
        assertEquals(StandaloneHealthConnect.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED,
                StandaloneHealthConnect.sdkStatus(context));
    }

    @Test
    public void invalidProvidersCannotConstructClientOrOpenSettings() {
        assertUnavailable();
        installProvider(context, true, StandaloneHealthConnect.MIN_VERSION_CODE - 1, true, true);
        assertUnavailable();
        installProvider(context, false, StandaloneHealthConnect.MIN_VERSION_CODE, true, true);
        assertUnavailable();
    }

    @Test
    public void bindServiceMustExistAndBeExported() {
        installProvider(context, true, StandaloneHealthConnect.MIN_VERSION_CODE, false, false);
        assertUnavailable();
        installProvider(context, true, StandaloneHealthConnect.MIN_VERSION_CODE, true, false);
        assertUnavailable();
    }

    @Test
    public void managedProfileRemainsUnsupported() {
        installProvider(context, true, StandaloneHealthConnect.MIN_VERSION_CODE, true, true);
        ((ShadowUserManager) Shadow.extract(context.getSystemService(UserManager.class))).setManagedProfile(true);
        assertEquals(StandaloneHealthConnect.SDK_UNAVAILABLE, StandaloneHealthConnect.sdkStatus(context));
        assertThrows(IllegalStateException.class,
                () -> StandaloneHealthConnect.createClient(context, getClass().getClassLoader()));
    }

    @Test
    @Config(sdk = {34, 35, 36})
    public void createsActualApkClientAndApkFeatureImplementationWithoutSpoofing() {
        installProvider(context, true, StandaloneHealthConnect.MIN_VERSION_CODE, true, true);
        int sdk = Build.VERSION.SDK_INT;
        int extension = SdkExtensions.getExtensionVersion(34);
        assertEquals(StandaloneHealthConnect.SDK_AVAILABLE, StandaloneHealthConnect.sdkStatus(context));
        Object result = StandaloneHealthConnect.createClient(context, getClass().getClassLoader());
        assertEquals("androidx.health.connect.client.impl.HealthConnectClientImpl", result.getClass().getName());
        HealthConnectClient client = (HealthConnectClient) result;
        assertEquals("androidx.health.connect.client.feature.HealthConnectFeaturesApkImpl",
                client.getFeatures().getClass().getName());
        assertEquals(HealthConnectFeatures.FEATURE_STATUS_AVAILABLE,
                client.getFeatures().getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND));
        assertEquals(sdk, Build.VERSION.SDK_INT);
        assertEquals(extension, SdkExtensions.getExtensionVersion(34));
        Intent settings = StandaloneHealthConnect.settingsIntent(context);
        assertEquals(StandaloneHealthConnect.SETTINGS_ACTION, settings.getAction());
        assertEquals(StandaloneHealthConnect.PROVIDER_PACKAGE, settings.getPackage());
    }

    @Test
    public void missingClientImplementationThrowsInsteadOfUsingPlatformFactory() {
        installProvider(context, true, StandaloneHealthConnect.MIN_VERSION_CODE, true, true);
        ClassLoader missingImplementation = new ClassLoader(getClass().getClassLoader()) {
            @Override
            public Class<?> loadClass(String name) throws ClassNotFoundException {
                if (name.equals("androidx.health.connect.client.impl.HealthConnectClientImpl")) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name);
            }
        };
        assertThrows(IllegalStateException.class,
                () -> StandaloneHealthConnect.createClient(context, missingImplementation));
    }

    private void assertUnavailable() {
        assertEquals(StandaloneHealthConnect.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED,
                StandaloneHealthConnect.sdkStatus(context));
        assertThrows(IllegalStateException.class,
                () -> StandaloneHealthConnect.createClient(context, getClass().getClassLoader()));
        assertThrows(IllegalStateException.class, () -> StandaloneHealthConnect.settingsIntent(context));
    }

    private void saveChoices(boolean experiments, boolean standalone) {
        assertTrue(context.getSharedPreferences(HookConfig.PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(HookConfig.KEY_EXPERIMENTAL_FEATURES_ENABLED, experiments)
                .putBoolean(HookConfig.KEY_STANDALONE_HC_ENABLED, standalone).commit());
    }

    static void installProvider(Context context, boolean enabled, long version,
            boolean bindable, boolean exported) {
        ApplicationInfo application = new ApplicationInfo();
        application.packageName = StandaloneHealthConnect.PROVIDER_PACKAGE;
        application.enabled = enabled;
        PackageInfo provider = new PackageInfo();
        provider.packageName = application.packageName;
        provider.applicationInfo = application;
        provider.setLongVersionCode(version);
        ShadowPackageManager packages = Shadow.extract(context.getPackageManager());
        packages.installPackage(provider);
        if (bindable) {
            ServiceInfo service = new ServiceInfo();
            service.packageName = application.packageName;
            service.name = application.packageName + ".HealthDataService";
            service.applicationInfo = application;
            service.enabled = true;
            service.exported = exported;
            ResolveInfo result = new ResolveInfo();
            result.serviceInfo = service;
            packages.addResolveInfoForIntent(
                    new Intent(StandaloneHealthConnect.BIND_ACTION).setPackage(application.packageName), result);
        }
    }
}
