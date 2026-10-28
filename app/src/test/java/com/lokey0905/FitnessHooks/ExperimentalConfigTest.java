package com.lokey0905.FitnessHooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/** Route-selection policy tests; the availability shadow does not simulate a real HC provider. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, shadows = ExperimentalConfigTest.AvailabilityShadow.class)
public final class ExperimentalConfigTest {
    private Context context;
    private SharedPreferences preferences;

    @Before
    public void reset() {
        context = RuntimeEnvironment.getApplication();
        preferences = context.getSharedPreferences(HookConfig.PREFS_NAME, Context.MODE_PRIVATE);
        assertTrue(preferences.edit().clear().commit());
        assertTrue(context.getSharedPreferences(ConfigTransport.TARGET_PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit());
        AvailabilityShadow.result = HealthConnectSteps.Availability.AVAILABLE;
        AvailabilityShadow.calls = 0;
    }

    @Test
    public void experimentalOptionsAndUninitializedTargetDefaultToOff() {
        assertFalse(HookConfig.DEFAULT_EXPERIMENTAL_FEATURES_ENABLED);
        assertFalse(HookConfig.DEFAULT_STANDALONE_HC_ENABLED);
        assertFalse(HookConfig.DEFAULT_DISABLE_DYNAMIC_COLORS);
        assertFalse(ConfigTransport.readModuleSettings(context).standaloneHcEnabled);
        assertFalse(ConfigTransport.readTargetSettings(context).standaloneHcEnabled);
        assertFalse(new HookConfig.Snapshot(true, true, 123).standaloneHcEnabled);

        assertTrue(preferences.edit()
                .putBoolean(HookConfig.KEY_EXPERIMENTAL_FEATURES_ENABLED, true).commit());
        assertFalse(ConfigTransport.readModuleSettings(context).standaloneHcEnabled);
    }

    @Test
    public void masterSwitchControlsSelectedRouteWithoutErasingTheSavedChoice() {
        assertTrue(preferences.edit()
                .putBoolean(HookConfig.KEY_STANDALONE_HC_ENABLED, true).commit());
        assertFalse(ConfigTransport.readModuleSettings(context).standaloneHcEnabled);

        assertTrue(preferences.edit()
                .putBoolean(HookConfig.KEY_EXPERIMENTAL_FEATURES_ENABLED, true).commit());
        assertTrue(ConfigTransport.readModuleSettings(context).standaloneHcEnabled);

        assertTrue(preferences.edit()
                .putBoolean(HookConfig.KEY_EXPERIMENTAL_FEATURES_ENABLED, false).commit());
        assertFalse(ConfigTransport.readModuleSettings(context).standaloneHcEnabled);
        assertTrue(preferences.getBoolean(HookConfig.KEY_STANDALONE_HC_ENABLED, false));

        assertTrue(preferences.edit()
                .putBoolean(HookConfig.KEY_EXPERIMENTAL_FEATURES_ENABLED, true)
                .putBoolean(HookConfig.KEY_DISABLE_DYNAMIC_COLORS, true).commit());
        assertTrue(ConfigTransport.readModuleSettings(context).standaloneHcEnabled);
    }

    @Test
    public void unavailableProvidersAndProfilesDoNotChangeTheSelectedRoute() {
        enableExperimentalChoice();
        for (HealthConnectSteps.Availability unavailable : new HealthConnectSteps.Availability[] {
                HealthConnectSteps.Availability.INSTALL_REQUIRED,
                HealthConnectSteps.Availability.UPDATE_REQUIRED,
                HealthConnectSteps.Availability.UNAVAILABLE,
                HealthConnectSteps.Availability.PROFILE_UNSUPPORTED,
                HealthConnectSteps.Availability.WORK_PROFILE_UNSUPPORTED}) {
            AvailabilityShadow.result = unavailable;
            assertTrue(unavailable.name(), ConfigTransport.readModuleSettings(context).standaloneHcEnabled);
            assertTrue(preferences.getBoolean(HookConfig.KEY_STANDALONE_HC_ENABLED, false));
        }
        AvailabilityShadow.result = HealthConnectSteps.Availability.AVAILABLE;
        assertTrue(ConfigTransport.readModuleSettings(context).standaloneHcEnabled);
        assertEquals(0, AvailabilityShadow.calls);
    }

    @Test
    public void legacyUExtensionPreferencesNeverSelectTheStandaloneDatabase() {
        assertTrue(preferences.edit()
                .putBoolean(HookConfig.KEY_EXPERIMENTAL_FEATURES_ENABLED, true)
                .putBoolean("u_extension_hook_enabled", true).commit());
        assertTrue(context.getSharedPreferences(ConfigTransport.TARGET_PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean("u_extension_hook_enabled", true).commit());

        assertFalse(ConfigTransport.readModuleSettings(context).standaloneHcEnabled);
        assertFalse(ConfigTransport.readTargetSettings(context).standaloneHcEnabled);
        assertFalse(preferences.contains(HookConfig.KEY_STANDALONE_HC_ENABLED));
    }

    @Test
    @Config(sdk = {28, 33})
    public void android13AndOlderKeepTheExistingSdkHookWithoutSelectingStandaloneHc() {
        enableExperimentalChoice();
        HookConfig.Snapshot module = ConfigTransport.readModuleSettings(context);
        assertTrue(module.sdkHookEnabled);
        assertFalse(module.standaloneHcEnabled);
        AvailabilityShadow.result = HealthConnectSteps.Availability.INSTALL_REQUIRED;
        assertFalse(ConfigTransport.readModuleSettings(context).sdkHookEnabled);
        assertTrue(preferences.getBoolean(HookConfig.KEY_SDK_HOOK_ENABLED,
                HookConfig.DEFAULT_SDK_HOOK_ENABLED));
        AvailabilityShadow.result = HealthConnectSteps.Availability.AVAILABLE;
        assertTrue(ConfigTransport.readModuleSettings(context).sdkHookEnabled);

        assertTrue(ConfigTransport.storeTargetSettings(context, new Intent()
                .putExtra(HookConfig.KEY_SDK_HOOK_ENABLED, true)
                .putExtra(HookConfig.KEY_STANDALONE_HC_ENABLED, true)));
        HookConfig.Snapshot target = ConfigTransport.readTargetSettings(context);
        assertTrue(target.sdkHookEnabled);
        assertFalse(target.standaloneHcEnabled);
    }

    @Test
    @Config(sdk = {34, 36})
    public void newerTargetsAcceptValidatedStandaloneRouteButRejectTheOlderSdkHook() {
        assertTrue(ConfigTransport.storeTargetSettings(context, new Intent()
                .putExtra(HookConfig.KEY_SDK_HOOK_ENABLED, true)
                .putExtra(HookConfig.KEY_STANDALONE_HC_ENABLED, true)
                .putExtra(HookConfig.KEY_STEP_HOOK_ENABLED, true)
                .putExtra(HookConfig.KEY_STEP_COUNT, 2468)));
        HookConfig.Snapshot target = ConfigTransport.readTargetSettings(context);
        assertFalse(target.sdkHookEnabled);
        assertTrue(target.standaloneHcEnabled);
        assertTrue(target.stepHookEnabled);
        assertEquals(2468, target.stepCount);
    }

    @Test
    public void omittedStandaloneFieldClearsAStaleTargetFlag() {
        assertTrue(ConfigTransport.storeTargetSettings(context, new Intent()
                .putExtra(HookConfig.KEY_STANDALONE_HC_ENABLED, true)));
        assertTrue(ConfigTransport.readTargetSettings(context).standaloneHcEnabled);

        assertTrue(ConfigTransport.storeTargetSettings(context, new Intent()
                .putExtra("u_extension_hook_enabled", true)
                .putExtra(HookConfig.KEY_STEP_HOOK_ENABLED, true)
                .putExtra(HookConfig.KEY_STEP_COUNT, 4321)));
        HookConfig.Snapshot target = ConfigTransport.readTargetSettings(context);
        assertFalse(target.standaloneHcEnabled);
        assertTrue(target.stepHookEnabled);
        assertEquals(4321, target.stepCount);
    }

    @Test
    public void broadcastCarriesEffectiveFlagsWithoutSendingAppOnlyPreferences() {
        enableExperimentalChoice();
        AvailabilityShadow.result = HealthConnectSteps.Availability.INSTALL_REQUIRED;
        assertTrue(preferences.edit()
                .putString(HookConfig.KEY_STEP_MODE, HookConfig.MODE_RECORDING)
                .putBoolean(HookConfig.KEY_STEP_HOOK_ENABLED, true)
                .putInt(HookConfig.KEY_STEP_COUNT, 6789)
                .putBoolean(HookConfig.KEY_DISABLE_DYNAMIC_COLORS, true).commit());
        BroadcastContext recordingContext = new BroadcastContext(context);

        ConfigTransport.publishToTarget(recordingContext, ConfigTransport.readModuleSettings(context));
        Intent enabled = recordingContext.lastBroadcast;
        assertNotNull(enabled);
        assertEquals(ConfigTransport.ACTION_APPLY_CONFIG, enabled.getAction());
        assertEquals(ConfigTransport.TARGET_PACKAGE, enabled.getPackage());
        assertFalse(enabled.getBooleanExtra(HookConfig.KEY_SDK_HOOK_ENABLED, true));
        assertTrue(enabled.getBooleanExtra(HookConfig.KEY_STANDALONE_HC_ENABLED, false));
        assertTrue(enabled.getBooleanExtra(HookConfig.KEY_STEP_HOOK_ENABLED, false));
        assertEquals(6789, enabled.getIntExtra(HookConfig.KEY_STEP_COUNT, -1));
        assertFalse(enabled.hasExtra(HookConfig.KEY_EXPERIMENTAL_FEATURES_ENABLED));
        assertFalse(enabled.hasExtra(HookConfig.KEY_DISABLE_DYNAMIC_COLORS));
        assertFalse(enabled.hasExtra("u_extension_hook_enabled"));

        assertTrue(preferences.edit()
                .putBoolean(HookConfig.KEY_EXPERIMENTAL_FEATURES_ENABLED, false)
                .putString(HookConfig.KEY_STEP_MODE, HookConfig.MODE_HEALTH_CONNECT).commit());
        ConfigTransport.publishToTarget(recordingContext, ConfigTransport.readModuleSettings(context));
        Intent disabled = recordingContext.lastBroadcast;
        assertFalse(disabled.getBooleanExtra(HookConfig.KEY_STANDALONE_HC_ENABLED, true));
        assertFalse(disabled.getBooleanExtra(HookConfig.KEY_STEP_HOOK_ENABLED, true));
        assertEquals(6789, disabled.getIntExtra(HookConfig.KEY_STEP_COUNT, -1));
        assertTrue(preferences.getBoolean(HookConfig.KEY_STANDALONE_HC_ENABLED, false));
    }

    private void enableExperimentalChoice() {
        assertTrue(preferences.edit()
                .putBoolean(HookConfig.KEY_EXPERIMENTAL_FEATURES_ENABLED, true)
                .putBoolean(HookConfig.KEY_STANDALONE_HC_ENABLED, true).commit());
    }

    private static final class BroadcastContext extends ContextWrapper {
        Intent lastBroadcast;

        BroadcastContext(Context base) {
            super(base);
        }

        @Override
        public void sendBroadcast(Intent intent) {
            lastBroadcast = intent;
        }
    }

    @Implements(HealthConnectSteps.class)
    public static final class AvailabilityShadow {
        static HealthConnectSteps.Availability result;
        static int calls;

        @Implementation
        protected static HealthConnectSteps.Availability availability(Context context) {
            calls++;
            return result;
        }
    }
}
