package com.lokey0905.FitnessHooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Process;
import android.os.UserManager;

import com.google.android.material.materialswitch.MaterialSwitch;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowUserManager;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, shadows = ShadowAppUpdateViewModel.class)
public final class HealthConnectAvailabilityTest {
    private static final int MIN_PROVIDER_VERSION = 68623;
    private static final String BIND_ACTION = "androidx.health.ACTION_BIND_HEALTH_DATA_SERVICE";

    private Context context;
    private ShadowPackageManager packageManager;
    private UserManager userManager;
    private ShadowUserManager shadowUserManager;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        packageManager = Shadow.extract(context.getPackageManager());
        userManager = context.getSystemService(UserManager.class);
        shadowUserManager = Shadow.extract(userManager);
        shadowUserManager.setManagedProfile(false);
    }

    @Test
    public void missingAndroid13ProviderRequiresInstallation() {
        assertEquals(HealthConnectSteps.Availability.INSTALL_REQUIRED,
                HealthConnectSteps.availability(context));
        assertFalse(ConfigTransport.readModuleSettings(context).sdkHookEnabled);
        assertTrue(context.getSharedPreferences(HookConfig.PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(HookConfig.KEY_SDK_HOOK_ENABLED, true).commit());
        assertFalse(ConfigTransport.readModuleSettings(context).sdkHookEnabled);
    }

    @Test
    public void enabledCurrentAndroid13ProviderIsAvailable() {
        installProvider(true, MIN_PROVIDER_VERSION, true);

        assertEquals(HealthConnectSteps.Availability.AVAILABLE,
                HealthConnectSteps.availability(context));
        assertTrue(ConfigTransport.readModuleSettings(context).sdkHookEnabled);
    }

    @Test
    public void supportedSettingsDefaultSdkCompatibilityToOn() {
        installProvider(true, MIN_PROVIDER_VERSION, true);
        assertTrue(context.getSharedPreferences(HookConfig.PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(HookConfig.KEY_STEP_MODE, HookConfig.MODE_RECORDING).commit());
        ActivityController<HookSettingsActivity> controller =
                Robolectric.buildActivity(HookSettingsActivity.class).setup();
        try {
            MaterialSwitch sdkSwitch = controller.get().findViewById(R.id.sdk_hook_switch);
            assertTrue(sdkSwitch.isEnabled());
            assertTrue(sdkSwitch.isChecked());
        } finally {
            controller.pause().stop().destroy();
        }
    }

    @Test
    public void savedOffChoiceOverridesEnabledDefault() {
        installProvider(true, MIN_PROVIDER_VERSION, true);
        assertTrue(context.getSharedPreferences(HookConfig.PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(HookConfig.KEY_SDK_HOOK_ENABLED, false).commit());

        assertFalse(ConfigTransport.readModuleSettings(context).sdkHookEnabled);
    }

    @Test
    @Config(sdk = 34)
    public void android14CompatibilityStaysOffEvenWhenRequested() {
        assertFalse(ConfigTransport.readModuleSettings(context).sdkHookEnabled);
        assertTrue(context.getSharedPreferences(HookConfig.PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(HookConfig.KEY_SDK_HOOK_ENABLED, true).commit());
        assertFalse(ConfigTransport.readModuleSettings(context).sdkHookEnabled);
        assertTrue(ConfigTransport.storeTargetSettings(context,
                new Intent().putExtra(HookConfig.KEY_SDK_HOOK_ENABLED, true)));
        assertFalse(ConfigTransport.readTargetSettings(context).sdkHookEnabled);
    }

    @Test
    public void missingTargetSettingsAndMissingIntentFieldStayOff() {
        assertFalse(ConfigTransport.readTargetSettings(context).sdkHookEnabled);
        assertTrue(ConfigTransport.storeTargetSettings(context, new Intent()));
        assertFalse(ConfigTransport.readTargetSettings(context).sdkHookEnabled);
    }

    @Test
    public void disabledAndroid13ProviderRequiresUpdate() {
        installProvider(false, MIN_PROVIDER_VERSION, true);

        assertEquals(HealthConnectSteps.Availability.UPDATE_REQUIRED,
                HealthConnectSteps.availability(context));
    }

    @Test
    public void outdatedAndroid13ProviderRequiresUpdate() {
        installProvider(true, MIN_PROVIDER_VERSION - 1, true);

        assertEquals(HealthConnectSteps.Availability.UPDATE_REQUIRED,
                HealthConnectSteps.availability(context));
    }

    @Test
    public void android13ProviderWithoutBindableServiceRequiresUpdate() {
        installProvider(true, MIN_PROVIDER_VERSION, false);

        assertEquals(HealthConnectSteps.Availability.UPDATE_REQUIRED,
                HealthConnectSteps.availability(context));
    }

    @Test
    public void nonManagedAndroid13ProfileUsesAvailableProvider() {
        setProfile(false);
        installProvider(true, MIN_PROVIDER_VERSION, true);

        assertTrue(userManager.isProfile());
        assertFalse(userManager.isManagedProfile());
        assertEquals(HealthConnectSteps.Availability.AVAILABLE,
                HealthConnectSteps.availability(context));
    }

    @Test
    @Config(sdk = 34)
    public void android14ProfileRemainsUnavailableThroughAndroidx() {
        setProfile(false);

        assertTrue(userManager.isProfile());
        assertFalse(userManager.isManagedProfile());
        assertEquals(HealthConnectSteps.Availability.PROFILE_UNSUPPORTED,
                HealthConnectSteps.availability(context));
    }

    @Test
    public void managedAndroid13ProfileIsRejectedEvenWithAvailableProvider() {
        setProfile(true);
        installProvider(true, MIN_PROVIDER_VERSION, true);

        assertTrue(userManager.isManagedProfile());
        assertEquals(HealthConnectSteps.Availability.WORK_PROFILE_UNSUPPORTED,
                HealthConnectSteps.availability(context));
    }

    private void setProfile(boolean managed) {
        int flags = ShadowUserManager.FLAG_PROFILE;
        if (managed) {
            flags |= ShadowUserManager.FLAG_MANAGED_PROFILE;
        }
        shadowUserManager.addUser(Process.myUid() / 100000, "Test profile", flags);
        shadowUserManager.setManagedProfile(managed);
    }

    private void installProvider(boolean enabled, int version, boolean bindable) {
        ApplicationInfo application = new ApplicationInfo();
        application.packageName = HealthConnectSteps.PROVIDER_PACKAGE;
        application.enabled = enabled;

        PackageInfo provider = new PackageInfo();
        provider.packageName = application.packageName;
        provider.applicationInfo = application;
        provider.setLongVersionCode(version);
        packageManager.installPackage(provider);

        if (bindable) {
            ServiceInfo service = new ServiceInfo();
            service.packageName = application.packageName;
            service.name = application.packageName + ".HealthDataService";
            service.applicationInfo = application;
            service.enabled = true;
            service.exported = true;
            ResolveInfo result = new ResolveInfo();
            result.serviceInfo = service;
            packageManager.addResolveInfoForIntent(
                    new Intent(BIND_ACTION).setPackage(application.packageName), result);
        }
    }
}
