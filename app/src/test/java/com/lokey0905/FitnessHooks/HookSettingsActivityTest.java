package com.lokey0905.FitnessHooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.VectorDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.vectordrawable.graphics.drawable.VectorDrawableCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, shadows = ShadowAppUpdateViewModel.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class HookSettingsActivityTest {
    private ActivityController<HookSettingsActivity> controller;
    private SharedPreferences preferences;

    @Before
    public void resetSettingsToRecording() {
        ProbeShadow.supported = false;
        ProbeShadow.osSupported = false;
        ProbeShadow.supportFailure = null;
        ProbeShadow.osFailure = null;
        AvailableHealthConnectShadow.result = HealthConnectSteps.Availability.AVAILABLE;
        DynamicColorsShadow.calls = 0;
        preferences = RuntimeEnvironment.getApplication()
                .getSharedPreferences(HookConfig.PREFS_NAME, Context.MODE_PRIVATE);
        assertTrue(preferences.edit().clear()
                .putString(HookConfig.KEY_STEP_MODE, HookConfig.MODE_RECORDING).commit());
    }

    @After
    public void destroyActivity() {
        if (controller != null) {
            controller.pause().stop().destroy();
            controller = null;
        }
    }

    @Test
    @Config(sdk = {28, 33, 34})
    public void freshSettingsDefaultToLeftHealthConnectWithoutRecordingOverride() {
        assertTrue(preferences.edit().remove(HookConfig.KEY_STEP_MODE)
                .putBoolean(HookConfig.KEY_STEP_HOOK_ENABLED, true).commit());
        assertFalse(ConfigTransport.readModuleSettings(RuntimeEnvironment.getApplication()).stepHookEnabled);

        HookSettingsActivity activity = start(null);
        MaterialButtonToggleGroup mode = activity.findViewById(R.id.step_mode_group);

        assertEquals(R.id.mode_health, mode.getChildAt(0).getId());
        assertEquals(R.id.mode_recording, mode.getChildAt(1).getId());
        assertEquals(R.id.mode_health, mode.getCheckedButtonId());
        assertFalse(ConfigTransport.readModuleSettings(activity).stepHookEnabled);
    }

    @Test
    public void savedRecordingChoiceSurvivesNewDefaultAndRecreation() {
        HookSettingsActivity activity = start(null);
        assertEquals(R.id.mode_recording,
                ((MaterialButtonToggleGroup) activity.findViewById(R.id.step_mode_group)).getCheckedButtonId());
        destroyActivity();
        activity = start(null);
        assertEquals(R.id.mode_recording,
                ((MaterialButtonToggleGroup) activity.findViewById(R.id.step_mode_group)).getCheckedButtonId());
    }

    @Test
    public void sdkSwitchChoicesPersistImmediatelyAcrossActivityRecreation() {
        installHealthProvider(true);
        HookSettingsActivity activity = start(null);
        MaterialSwitch sdkSwitch = activity.findViewById(R.id.sdk_hook_switch);
        assertTrue(sdkSwitch.isEnabled());
        assertTrue(sdkSwitch.isChecked());
        assertFalse(preferences.contains(HookConfig.KEY_SDK_HOOK_ENABLED));

        // SDK settings save independently from the step form and its Save button.
        ((TextInputEditText) activity.findViewById(R.id.step_count_input)).setText("invalid");
        sdkSwitch.performClick();
        assertFalse(preferences.getBoolean(HookConfig.KEY_SDK_HOOK_ENABLED, true));
        assertFalse(ConfigTransport.readModuleSettings(activity).sdkHookEnabled);
        destroyActivity();

        activity = start(null);
        sdkSwitch = activity.findViewById(R.id.sdk_hook_switch);
        assertFalse(sdkSwitch.isChecked());
        sdkSwitch.performClick();
        assertTrue(preferences.getBoolean(HookConfig.KEY_SDK_HOOK_ENABLED, false));
        assertTrue(ConfigTransport.readModuleSettings(activity).sdkHookEnabled);
        destroyActivity();

        activity = start(null);
        assertTrue(((MaterialSwitch) activity.findViewById(R.id.sdk_hook_switch)).isChecked());
    }

    @Test
    public void unavailableProviderTemporarilyDisablesSdkWithoutErasingSavedChoice() {
        installHealthProvider(true);
        assertTrue(preferences.edit().putBoolean(HookConfig.KEY_SDK_HOOK_ENABLED, true).commit());
        HookSettingsActivity activity = start(null);
        MaterialSwitch sdkSwitch = activity.findViewById(R.id.sdk_hook_switch);

        installHealthProvider(false);
        controller.pause().resume();
        assertFalse(sdkSwitch.isEnabled());
        assertFalse(sdkSwitch.isChecked());
        assertTrue(preferences.getBoolean(HookConfig.KEY_SDK_HOOK_ENABLED, false));
        assertFalse(ConfigTransport.readModuleSettings(activity).sdkHookEnabled);

        ((TextInputEditText) activity.findViewById(R.id.step_count_input)).setText("2222");
        activity.findViewById(R.id.save_button).performClick();
        assertEquals(2222, preferences.getInt(HookConfig.KEY_STEP_COUNT, -1));
        assertTrue(preferences.getBoolean(HookConfig.KEY_SDK_HOOK_ENABLED, false));
        destroyActivity();
        activity = start(null);
        assertFalse(((MaterialSwitch) activity.findViewById(R.id.sdk_hook_switch)).isChecked());

        installHealthProvider(true);
        controller.pause().resume();
        sdkSwitch = activity.findViewById(R.id.sdk_hook_switch);
        assertTrue(sdkSwitch.isEnabled());
        assertTrue(sdkSwitch.isChecked());
        assertTrue(ConfigTransport.readModuleSettings(activity).sdkHookEnabled);
    }

    @Test
    public void newlyAvailableProviderRestoresDefaultWithoutWritingAnExplicitChoice() {
        HookSettingsActivity activity = start(null);
        assertFalse(((MaterialSwitch) activity.findViewById(R.id.sdk_hook_switch)).isChecked());
        assertFalse(preferences.contains(HookConfig.KEY_SDK_HOOK_ENABLED));
        activity.findViewById(R.id.save_button).performClick();
        assertFalse(preferences.contains(HookConfig.KEY_SDK_HOOK_ENABLED));

        installHealthProvider(true);
        controller.pause().resume();
        assertTrue(((MaterialSwitch) activity.findViewById(R.id.sdk_hook_switch)).isChecked());
        assertFalse(preferences.contains(HookConfig.KEY_SDK_HOOK_ENABLED));
    }

    @Test
    public void savedOffChoiceSurvivesStaleViewRestorationAndProviderRecovery() {
        installHealthProvider(true);
        HookSettingsActivity activity = start(null);
        Bundle staleState = new Bundle();
        controller.saveInstanceState(staleState);
        ((MaterialSwitch) activity.findViewById(R.id.sdk_hook_switch)).performClick();
        assertFalse(preferences.getBoolean(HookConfig.KEY_SDK_HOOK_ENABLED, true));
        destroyActivity();

        activity = start(staleState);
        assertFalse(((MaterialSwitch) activity.findViewById(R.id.sdk_hook_switch)).isChecked());
        assertFalse(preferences.getBoolean(HookConfig.KEY_SDK_HOOK_ENABLED, true));
        installHealthProvider(false);
        controller.pause().resume();
        installHealthProvider(true);
        controller.pause().resume();
        MaterialSwitch sdkSwitch = activity.findViewById(R.id.sdk_hook_switch);
        assertTrue(sdkSwitch.isEnabled());
        assertFalse(sdkSwitch.isChecked());
        assertFalse(preferences.getBoolean(HookConfig.KEY_SDK_HOOK_ENABLED, true));
    }

    @Test
    @Config(sdk = 34)
    public void android14KeepsCompatibilityDisabledWithoutErasingSavedIntent() {
        assertTrue(preferences.edit().putBoolean(HookConfig.KEY_SDK_HOOK_ENABLED, true).commit());
        HookSettingsActivity activity = start(null);
        MaterialSwitch sdkSwitch = activity.findViewById(R.id.sdk_hook_switch);
        assertFalse(sdkSwitch.isEnabled());
        assertFalse(sdkSwitch.isChecked());
        activity.findViewById(R.id.save_button).performClick();
        assertTrue(preferences.getBoolean(HookConfig.KEY_SDK_HOOK_ENABLED, false));
        assertFalse(ConfigTransport.readModuleSettings(activity).sdkHookEnabled);
    }

    @Test
    @Config(sdk = {28, 33}, qualifiers = "w320dp-h480dp")
    public void versionCardExplainsOlderAndroidWithoutReportingExtensionZero() {
        HookSettingsActivity activity = start(null);
        View card = activity.findViewById(R.id.device_info_card);
        View common = activity.findViewById(R.id.common_controls);
        ViewGroup content = (ViewGroup) card.getParent();

        assertTrue(content.indexOfChild(card) < content.indexOfChild(common));
        assertEquals(activity.getString(R.string.device_info_extension_not_applicable, 34),
                ((TextView) activity.findViewById(R.id.device_info_extension)).getText().toString());
        assertEquals(activity.getString(R.string.hc_probe_failed),
                ((TextView) activity.findViewById(R.id.hc_is_supported_result)).getText().toString());
        assertEquals(activity.getString(R.string.device_info_icon_failed),
                ((TextView) activity.findViewById(R.id.hc_os_supports_icon)).getText().toString());
    }

    @Test
    @Config(sdk = 34, qualifiers = "w320dp-h480dp-night", shadows = {
            ShadowAppUpdateViewModel.class, PikminVersionInfoTest.ExtensionShadow.class})
    public void versionCardRefreshesFrom12To16OnResumeWithoutChangingStepDraft() {
        PikminVersionInfoTest.ExtensionShadow.version = 12;
        PikminVersionInfoTest.ExtensionShadow.failure = null;
        HookSettingsActivity activity = start(null);
        TextView extension = activity.findViewById(R.id.device_info_extension);
        TextInputEditText input = activity.findViewById(R.id.step_count_input);
        input.setText("1234");

        assertEquals(activity.getString(R.string.device_info_extension_format, 12, 13),
                extension.getText().toString());

        PikminVersionInfoTest.ExtensionShadow.version = 16;
        controller.pause().resume();

        assertEquals(activity.getString(R.string.device_info_extension_format, 16, 13),
                extension.getText().toString());
        assertEquals("1234", input.getText().toString());

        PikminVersionInfoTest.ExtensionShadow.failure = new IllegalStateException("Unavailable");
        controller.pause().resume();
        assertEquals(activity.getString(R.string.device_info_extension_unknown),
                extension.getText().toString());
    }

    @Test
    @Config(sdk = 33, qualifiers = "w320dp-h480dp-night", shadows = {
            ShadowAppUpdateViewModel.class, ProbeShadow.class})
    public void compatibilityResultsRefreshIndependentlyOnResumeAndAfterSave() {
        // This shadow verifies UI rendering and refreshes, not actual LSPosed interception.
        ProbeShadow.osSupported = true;
        HookSettingsActivity activity = start(null);
        dismissStartupReminder();
        assertEquals(activity.getString(R.string.device_info_icon_failed),
                ((TextView) activity.findViewById(R.id.hc_is_supported_icon)).getText().toString());
        assertEquals(activity.getString(R.string.hc_probe_passed),
                ((TextView) activity.findViewById(R.id.hc_os_supports_result)).getText().toString());

        ProbeShadow.supportFailure = new IllegalStateException("HC initialization failed");
        ProbeShadow.osFailure = new NoSuchMethodError("HC API unavailable");
        controller.pause().resume();
        assertEquals(activity.getString(R.string.hc_probe_unknown),
                ((TextView) activity.findViewById(R.id.hc_is_supported_result)).getText().toString());
        assertEquals(activity.getString(R.string.device_info_icon_unknown),
                ((TextView) activity.findViewById(R.id.hc_os_supports_icon)).getText().toString());

        ProbeShadow.supportFailure = null;
        ProbeShadow.osFailure = null;
        ProbeShadow.supported = true;
        ProbeShadow.osSupported = false;
        activity.findViewById(R.id.save_button).performClick();
        assertEquals(activity.getString(R.string.device_info_icon_passed),
                ((TextView) activity.findViewById(R.id.hc_is_supported_icon)).getText().toString());
        assertEquals(activity.getString(R.string.hc_probe_failed),
                ((TextView) activity.findViewById(R.id.hc_os_supports_result)).getText().toString());
    }

    @Implements(PikminHealthConnectProbe.class)
    public static final class ProbeShadow {
        static boolean supported;
        static boolean osSupported;
        static RuntimeException supportFailure;
        static LinkageError osFailure;

        @Implementation
        protected boolean isSupported() {
            if (supportFailure != null) {
                throw supportFailure;
            }
            return supported;
        }

        @Implementation
        protected boolean osSupportsHealthConnect() {
            if (osFailure != null) {
                throw osFailure;
            }
            return osSupported;
        }
    }

    @Test
    @Config(sdk = {28, 33, 34})
    public void startupShowsCurrentVersionAndSixAdjustmentAmounts() {
        HookSettingsActivity activity = start(null);

        MaterialToolbar toolbar = activity.findViewById(R.id.toolbar);
        assertEquals("fitness hook " + BuildConfig.VERSION_NAME, toolbar.getTitle().toString());
        assertTrue(toolbar.getMenu().findItem(R.id.action_settings).isVisible());
        Drawable settingsIcon = toolbar.getMenu().findItem(R.id.action_settings).getIcon();
        assertTrue(settingsIcon instanceof VectorDrawable || settingsIcon instanceof VectorDrawableCompat);
        assertTrue(toolbar.getMenu().findItem(R.id.action_about).isVisible());
        Drawable aboutIcon = toolbar.getMenu().findItem(R.id.action_about).getIcon();
        assertNotNull(aboutIcon);
        assertTrue(aboutIcon instanceof VectorDrawable || aboutIcon instanceof VectorDrawableCompat);
        int iconSize = Math.round(24 * activity.getResources().getDisplayMetrics().density);
        assertEquals(iconSize, aboutIcon.getIntrinsicWidth());
        assertEquals(iconSize, aboutIcon.getIntrinsicHeight());
        int[] expected = {1, 10, 100, 500, 1000, 5000};
        for (int id : new int[] {R.id.increment_buttons, R.id.decrement_buttons}) {
            GridLayout grid = activity.findViewById(id);
            assertEquals(3, grid.getColumnCount());
            assertEquals(2, grid.getRowCount());
            assertEquals(expected.length, grid.getChildCount());
            for (int index = 0; index < expected.length; index++) {
                assertEquals(String.valueOf(expected[index]),
                        ((MaterialButton) grid.getChildAt(index)).getText().toString());
            }
        }
    }

    @Test
    @Config(sdk = {28, 33, 34}, qualifiers = "w320dp-h480dp")
    public void aboutActionShowsAppVersionAndAuthorAndCanClose() {
        HookSettingsActivity activity = start(null);
        MaterialToolbar toolbar = activity.findViewById(R.id.toolbar);

        assertTrue(toolbar.getMenu().performIdentifierAction(R.id.action_about, 0));
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        assertEquals("Fitness Hooks",
                ((TextView) dialog.findViewById(R.id.about_app_name)).getText().toString());
        assertEquals(BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")",
                ((TextView) dialog.findViewById(R.id.about_version)).getText().toString());
        assertEquals(activity.getString(R.string.about_maker),
                ((TextView) dialog.findViewById(R.id.about_maker)).getText().toString());
        assertEquals(activity.getString(R.string.about_disclaimer),
                ((TextView) dialog.findViewById(R.id.about_disclaimer)).getText().toString());

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertFalse(dialog.isShowing());
    }

    @Test
    public void missingAndroid13ProviderBlocksSyncWithoutChangingStoredHealthTotal() {
        long hour = HealthConnectSteps.currentHourStartSeconds();
        assertTrue(preferences.edit()
                .putString(HookConfig.KEY_STEP_MODE, HookConfig.MODE_HEALTH_CONNECT)
                .putInt(HookConfig.KEY_HEALTH_STEP_COUNT, 600)
                .putLong(HookConfig.KEY_HEALTH_STEP_HOUR, hour)
                .commit());
        HookSettingsActivity activity = start(null);

        assertEquals(HealthConnectSteps.Availability.INSTALL_REQUIRED,
                HealthConnectSteps.availability(activity));
        assertFalse(activity.findViewById(R.id.save_button).isEnabled());
        assertFalse(activity.findViewById(R.id.step_count_input).isEnabled());
        assertFalse(activity.findViewById(R.id.sdk_hook_switch).isEnabled());
        assertEquals(activity.getString(R.string.health_download),
                ((TextView) activity.findViewById(R.id.health_action_title)).getText().toString());
        assertEquals(600, preferences.getInt(HookConfig.KEY_HEALTH_STEP_COUNT, -1));
        assertEquals(hour, preferences.getLong(HookConfig.KEY_HEALTH_STEP_HOUR, -1));
        assertNull(((ShadowActivity) Shadow.extract(activity)).getNextStartedActivity());
    }

    @Test
    @Config(sdk = {28, 33, 34}, qualifiers = "w320dp-h480dp")
    public void compatibilityCardsFollowModeSelectorAndOnlyAppearForHealthConnect() {
        HookSettingsActivity activity = start(null);
        View common = activity.findViewById(R.id.common_controls);
        ViewGroup health = activity.findViewById(R.id.health_controls);
        MaterialButtonToggleGroup mode = activity.findViewById(R.id.step_mode_group);
        ViewGroup content = (ViewGroup) common.getParent();

        assertEquals(content, mode.getParent());
        assertTrue(content.indexOfChild(common) < content.indexOfChild(mode));
        assertTrue(content.indexOfChild(mode) < content.indexOfChild(health));
        assertEquals(R.id.sdk_hook_card, health.getChildAt(0).getId());
        assertNull(((ViewGroup) common).findViewById(R.id.sdk_hook_card));
        assertFalse(activity.findViewById(R.id.sdk_hook_card).isShown());
        assertEquals(View.GONE, activity.findViewById(R.id.u_extension_hook_card).getVisibility());

        mode.check(R.id.mode_health);

        assertTrue(activity.findViewById(R.id.sdk_hook_card).isShown());
        assertFalse(activity.findViewById(R.id.u_extension_hook_card).isShown());
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            assertFalse(activity.findViewById(R.id.sdk_hook_switch).isEnabled());
        }

        mode.check(R.id.mode_recording);
        assertFalse(activity.findViewById(R.id.sdk_hook_card).isShown());
    }

    @Test
    @Config(sdk = {28, 33}, qualifiers = "w320dp-h480dp")
    public void settingsDefaultOffAndExperimentalOptionDoesNotEnableUExtensionOnOlderAndroid() {
        installHealthProvider(true);
        assertTrue(preferences.edit().putString(HookConfig.KEY_STEP_MODE,
                HookConfig.MODE_HEALTH_CONNECT).commit());
        HookSettingsActivity activity = start(null);
        AlertDialog dialog = openAppSettings(activity);
        MaterialSwitch experimental = dialog.findViewById(R.id.experimental_features_switch);
        MaterialSwitch colors = dialog.findViewById(R.id.disable_dynamic_colors_switch);
        assertFalse(experimental.isChecked());
        assertFalse(colors.isChecked());

        experimental.performClick();
        assertTrue(preferences.getBoolean(HookConfig.KEY_EXPERIMENTAL_FEATURES_ENABLED, false));
        assertTrue(activity.findViewById(R.id.u_extension_hook_card).isShown());
        assertFalse(activity.findViewById(R.id.u_extension_hook_switch).isEnabled());
        assertFalse(((MaterialSwitch) activity.findViewById(R.id.u_extension_hook_switch)).isChecked());
        assertEquals(activity.getString(R.string.u_extension_hook_older_android),
                ((TextView) activity.findViewById(R.id.u_extension_hook_summary)).getText().toString());
        assertTrue(activity.findViewById(R.id.sdk_hook_switch).isEnabled());
        dialog.dismiss();
        destroyActivity();

        activity = start(null);
        dialog = openAppSettings(activity);
        assertTrue(((MaterialSwitch) dialog.findViewById(R.id.experimental_features_switch)).isChecked());
        assertTrue(activity.findViewById(R.id.u_extension_hook_card).isShown());
    }

    @Test
    @Config(sdk = 34, shadows = {ShadowAppUpdateViewModel.class, AvailableHealthConnectShadow.class})
    public void experimentalMasterAndUExtensionChoicePersistWithoutErasingSavedIntent() {
        assertTrue(preferences.edit().putString(HookConfig.KEY_STEP_MODE,
                HookConfig.MODE_HEALTH_CONNECT).commit());
        HookSettingsActivity activity = start(null);
        assertEquals(View.GONE, activity.findViewById(R.id.u_extension_hook_card).getVisibility());
        AlertDialog dialog = openAppSettings(activity);
        ((MaterialSwitch) dialog.findViewById(R.id.experimental_features_switch)).performClick();
        assertTrue(preferences.getBoolean(HookConfig.KEY_EXPERIMENTAL_FEATURES_ENABLED, false));
        dialog.dismiss();

        MaterialSwitch extension = activity.findViewById(R.id.u_extension_hook_switch);
        assertTrue(extension.isEnabled());
        assertFalse(extension.isChecked());
        assertFalse(activity.findViewById(R.id.sdk_hook_switch).isEnabled());
        // Compatibility choices do not require a valid step draft or pressing Sync.
        ((TextInputEditText) activity.findViewById(R.id.step_count_input)).setText("invalid");
        extension.performClick();
        assertTrue(preferences.getBoolean(HookConfig.KEY_U_EXTENSION_HOOK_ENABLED, false));
        assertTrue(ConfigTransport.readModuleSettings(activity).uExtensionHookEnabled);
        Bundle staleState = new Bundle();
        controller.saveInstanceState(staleState);
        extension.performClick();
        assertFalse(preferences.getBoolean(HookConfig.KEY_U_EXTENSION_HOOK_ENABLED, true));
        destroyActivity();

        activity = start(staleState);
        extension = activity.findViewById(R.id.u_extension_hook_switch);
        assertFalse(extension.isChecked());
        extension.performClick();
        dialog = openAppSettings(activity);
        ((MaterialSwitch) dialog.findViewById(R.id.experimental_features_switch)).performClick();
        assertFalse(ConfigTransport.readModuleSettings(activity).uExtensionHookEnabled);
        assertTrue(preferences.getBoolean(HookConfig.KEY_U_EXTENSION_HOOK_ENABLED, false));
        assertEquals(View.GONE, activity.findViewById(R.id.u_extension_hook_card).getVisibility());
        dialog.dismiss();
        destroyActivity();

        activity = start(null);
        assertFalse(((MaterialSwitch) activity.findViewById(R.id.u_extension_hook_switch)).isChecked());
        dialog = openAppSettings(activity);
        ((MaterialSwitch) dialog.findViewById(R.id.experimental_features_switch)).performClick();
        assertTrue(((MaterialSwitch) activity.findViewById(R.id.u_extension_hook_switch)).isChecked());
        assertTrue(ConfigTransport.readModuleSettings(activity).uExtensionHookEnabled);
        dialog.dismiss();
        ((MaterialButtonToggleGroup) activity.findViewById(R.id.step_mode_group)).check(R.id.mode_recording);
        assertFalse(activity.findViewById(R.id.u_extension_hook_card).isShown());
    }

    @Test
    @Config(sdk = 34, shadows = {ShadowAppUpdateViewModel.class, AvailableHealthConnectShadow.class})
    public void unavailableHealthConnectDisablesUExtensionWithoutErasingChoice() {
        assertTrue(preferences.edit()
                .putString(HookConfig.KEY_STEP_MODE, HookConfig.MODE_HEALTH_CONNECT)
                .putBoolean(HookConfig.KEY_EXPERIMENTAL_FEATURES_ENABLED, true)
                .putBoolean(HookConfig.KEY_U_EXTENSION_HOOK_ENABLED, true).commit());
        HookSettingsActivity activity = start(null);
        MaterialSwitch extension = activity.findViewById(R.id.u_extension_hook_switch);
        assertTrue(extension.isChecked());

        AvailableHealthConnectShadow.result = HealthConnectSteps.Availability.PROFILE_UNSUPPORTED;
        controller.pause().resume();
        assertFalse(extension.isEnabled());
        assertFalse(extension.isChecked());
        assertTrue(preferences.getBoolean(HookConfig.KEY_U_EXTENSION_HOOK_ENABLED, false));
        assertFalse(ConfigTransport.readModuleSettings(activity).uExtensionHookEnabled);

        AvailableHealthConnectShadow.result = HealthConnectSteps.Availability.AVAILABLE;
        controller.pause().resume();
        assertTrue(extension.isEnabled());
        assertTrue(extension.isChecked());
    }

    @Test
    @Config(shadows = {ShadowAppUpdateViewModel.class, AvailableHealthConnectShadow.class,
            DynamicColorsShadow.class})
    public void dynamicColorPreferenceRecreatesAfterDialogDismissAndPreservesBothDrafts() {
        HookSettingsActivity activity = start(null);
        assertEquals(1, DynamicColorsShadow.calls);
        ((TextInputEditText) activity.findViewById(R.id.step_count_input)).setText("1234");
        ((MaterialButtonToggleGroup) activity.findViewById(R.id.step_mode_group)).check(R.id.mode_health);
        ((TextInputEditText) activity.findViewById(R.id.step_count_input)).setText("5678");
        AlertDialog dialog = openAppSettings(activity);
        ((MaterialSwitch) dialog.findViewById(R.id.disable_dynamic_colors_switch)).performClick();
        assertTrue(preferences.getBoolean(HookConfig.KEY_DISABLE_DYNAMIC_COLORS, false));
        assertSame(activity, controller.get());

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        HookSettingsActivity recreated = controller.get();
        assertNotSame(activity, recreated);
        assertEquals(1, DynamicColorsShadow.calls);
        assertEquals("5678", ((TextInputEditText) recreated.findViewById(R.id.step_count_input))
                .getText().toString());
        ((MaterialButtonToggleGroup) recreated.findViewById(R.id.step_mode_group)).check(R.id.mode_recording);
        assertEquals("1234", ((TextInputEditText) recreated.findViewById(R.id.step_count_input))
                .getText().toString());

        dialog = openAppSettings(recreated);
        MaterialSwitch colors = dialog.findViewById(R.id.disable_dynamic_colors_switch);
        assertTrue(colors.isChecked());
        colors.performClick();
        dialog.dismiss();
        ShadowLooper.idleMainLooper();
        assertNotSame(recreated, controller.get());
        assertEquals(2, DynamicColorsShadow.calls);
        assertFalse(preferences.getBoolean(HookConfig.KEY_DISABLE_DYNAMIC_COLORS, true));
    }

    @Test
    @Config(shadows = DynamicColorsShadow.class)
    public void healthPermissionsPageUsesTheSameDynamicColorPreference() {
        try (ActivityController<HealthPermissionsActivity> privacy =
                Robolectric.buildActivity(HealthPermissionsActivity.class).setup()) {
            assertEquals(1, DynamicColorsShadow.calls);
        }
        assertTrue(preferences.edit().putBoolean(HookConfig.KEY_DISABLE_DYNAMIC_COLORS, true).commit());
        try (ActivityController<HealthPermissionsActivity> privacy =
                Robolectric.buildActivity(HealthPermissionsActivity.class).setup()) {
            assertEquals(1, DynamicColorsShadow.calls);
        }
    }

    @Implements(HealthConnectSteps.class)
    public static final class AvailableHealthConnectShadow {
        static HealthConnectSteps.Availability result;

        @Implementation
        protected static HealthConnectSteps.Availability availability(Context context) {
            return result;
        }

        @Implementation
        protected void checkPermission(Context context, HealthConnectSteps.Callback callback) {
            callback.onResult(HealthConnectSteps.Result.READY);
        }
    }

    @Implements(DynamicColors.class)
    public static final class DynamicColorsShadow {
        static int calls;

        @Implementation
        protected static void applyToActivityIfAvailable(Activity activity) {
            calls++;
        }
    }

    @Test
    public void healthSettingsRowsMatchSdkStyleAndDownloadActionStillWorks() {
        assertTrue(preferences.edit()
                .putString(HookConfig.KEY_STEP_MODE, HookConfig.MODE_HEALTH_CONNECT).commit());
        HookSettingsActivity activity = start(null);
        MaterialCardView sdk = activity.findViewById(R.id.sdk_hook_card);
        for (int id : new int[] {R.id.health_action, R.id.health_settings}) {
            MaterialCardView row = activity.findViewById(id);
            assertEquals(sdk.getRadius(), row.getRadius(), 0.01f);
            assertEquals(sdk.getCardBackgroundColor().getDefaultColor(),
                    row.getCardBackgroundColor().getDefaultColor());
            assertTrue(row.isClickable());
            assertTrue(row.isFocusable());
        }
        assertFalse(activity.findViewById(R.id.health_settings).isEnabled());

        assertTrue(activity.findViewById(R.id.health_action).performClick());
        Intent download = ((ShadowActivity) Shadow.extract(activity)).getNextStartedActivity();
        assertNotNull(download);
        assertEquals(Intent.ACTION_VIEW, download.getAction());
        assertEquals("market://details?id=com.google.android.apps.healthdata",
                download.getDataString());
    }

    @Test
    public void stepWarningTracksTypedAndQuickAdjustmentThreshold() {
        assertTrue(preferences.edit().putBoolean(HookConfig.KEY_STEP_HOOK_ENABLED, true).commit());
        HookSettingsActivity activity = start(null);
        TextInputEditText input = activity.findViewById(R.id.step_count_input);
        View warning = activity.findViewById(R.id.step_limit_warning);

        input.setText("50000");
        assertEquals(View.GONE, warning.getVisibility());
        ((GridLayout) activity.findViewById(R.id.increment_buttons)).getChildAt(0).performClick();
        assertEquals("50001", input.getText().toString());
        assertEquals(View.VISIBLE, warning.getVisibility());
        ((GridLayout) activity.findViewById(R.id.decrement_buttons)).getChildAt(0).performClick();
        assertEquals("50000", input.getText().toString());
        assertEquals(View.GONE, warning.getVisibility());
        input.setText("60000");
        assertEquals(View.VISIBLE, warning.getVisibility());
        input.setText("");
        assertEquals(View.GONE, warning.getVisibility());
        assertTrue(activity.findViewById(R.id.step_time_zone_notice).isShown());
    }

    @Test
    public void highStepCountNeedsExplicitConfirmationAndCancelDoesNotSave() {
        assertTrue(preferences.edit().putBoolean(HookConfig.KEY_STEP_HOOK_ENABLED, true)
                .putInt(HookConfig.KEY_STEP_COUNT, 100).commit());
        HookSettingsActivity activity = start(null);
        dismissStartupReminder();
        ((TextInputEditText) activity.findViewById(R.id.step_count_input)).setText("50001");

        activity.findViewById(R.id.save_button).performClick();
        AlertDialog warning = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(warning.isShowing());
        assertEquals(activity.getString(R.string.step_limit_continue),
                warning.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        assertEquals(100, preferences.getInt(HookConfig.KEY_STEP_COUNT, -1));
        warning.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals(100, preferences.getInt(HookConfig.KEY_STEP_COUNT, -1));

        activity.findViewById(R.id.save_button).performClick();
        warning = (AlertDialog) ShadowDialog.getLatestDialog();
        warning.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals(50001, preferences.getInt(HookConfig.KEY_STEP_COUNT, -1));
    }

    @Test
    public void exactlyFiftyThousandSavesWithoutWarning() {
        assertTrue(preferences.edit().putBoolean(HookConfig.KEY_STEP_HOOK_ENABLED, true).commit());
        HookSettingsActivity activity = start(null);
        dismissStartupReminder();
        ((TextInputEditText) activity.findViewById(R.id.step_count_input)).setText("50000");

        activity.findViewById(R.id.save_button).performClick();

        assertEquals(50000, preferences.getInt(HookConfig.KEY_STEP_COUNT, -1));
        assertFalse(ShadowDialog.getLatestDialog().isShowing());
    }

    @Test
    public void confirmationCannotSaveChangedInput() {
        assertTrue(preferences.edit().putBoolean(HookConfig.KEY_STEP_HOOK_ENABLED, true)
                .putInt(HookConfig.KEY_STEP_COUNT, 100).commit());
        HookSettingsActivity activity = start(null);
        dismissStartupReminder();
        TextInputEditText input = activity.findViewById(R.id.step_count_input);
        input.setText("50001");
        activity.findViewById(R.id.save_button).performClick();
        AlertDialog warning = (AlertDialog) ShadowDialog.getLatestDialog();

        input.setText("60000");
        warning.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals(100, preferences.getInt(HookConfig.KEY_STEP_COUNT, -1));
    }

    @Test
    public void confirmationCannotSaveAfterModeChange() {
        assertTrue(preferences.edit().putBoolean(HookConfig.KEY_STEP_HOOK_ENABLED, true)
                .putInt(HookConfig.KEY_STEP_COUNT, 100).commit());
        HookSettingsActivity activity = start(null);
        dismissStartupReminder();
        ((TextInputEditText) activity.findViewById(R.id.step_count_input)).setText("50001");
        activity.findViewById(R.id.save_button).performClick();
        AlertDialog warning = (AlertDialog) ShadowDialog.getLatestDialog();

        ((MaterialButtonToggleGroup) activity.findViewById(R.id.step_mode_group)).check(R.id.mode_health);
        warning.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals(100, preferences.getInt(HookConfig.KEY_STEP_COUNT, -1));
        assertFalse(preferences.contains(HookConfig.KEY_HEALTH_STEP_COUNT));
    }

    @Test
    public void confirmationRechecksHealthHourBeforeSaving() {
        long hour = HealthConnectSteps.currentHourStartSeconds();
        assertTrue(preferences.edit()
                .putString(HookConfig.KEY_STEP_MODE, HookConfig.MODE_HEALTH_CONNECT)
                .putInt(HookConfig.KEY_HEALTH_STEP_COUNT, 400)
                .putLong(HookConfig.KEY_HEALTH_STEP_HOUR, hour).commit());
        HookSettingsActivity activity = start(null);
        dismissStartupReminder();
        ((TextInputEditText) activity.findViewById(R.id.step_count_input)).setText("50001");
        // Exercise the pre-sync confirmation without a provider in the test environment.
        activity.findViewById(R.id.save_button).setEnabled(true);
        activity.findViewById(R.id.save_button).performClick();
        AlertDialog warning = (AlertDialog) ShadowDialog.getLatestDialog();
        assertEquals(activity.getString(R.string.step_limit_continue),
                warning.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());

        ReflectionHelpers.setField(activity, "healthHour", hour - 3600);
        warning.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals(activity.getString(R.string.health_hour_changed),
                ((TextView) activity.findViewById(R.id.health_status)).getText().toString());
        assertEquals(400, preferences.getInt(HookConfig.KEY_HEALTH_STEP_COUNT, -1));
        assertEquals(hour, preferences.getLong(HookConfig.KEY_HEALTH_STEP_HOUR, -1));
        assertNull(((ShadowActivity) Shadow.extract(activity)).getNextStartedActivity());
    }

    @Test
    public void healthModePersistsAndExcludesRecordingOverride() {
        assertTrue(preferences.edit()
                .putBoolean(HookConfig.KEY_STEP_HOOK_ENABLED, true)
                .commit());
        HookSettingsActivity activity = start(null);
        assertTrue(ConfigTransport.readModuleSettings(activity).stepHookEnabled);

        MaterialButtonToggleGroup mode = activity.findViewById(R.id.step_mode_group);
        mode.check(R.id.mode_health);

        assertEquals(HookConfig.MODE_HEALTH_CONNECT,
                preferences.getString(HookConfig.KEY_STEP_MODE, ""));
        assertFalse(ConfigTransport.readModuleSettings(activity).stepHookEnabled);
        assertTrue(preferences.getBoolean(HookConfig.KEY_STEP_HOOK_ENABLED, false));
        destroyActivity();
        activity = start(null);
        assertEquals(R.id.mode_health,
                ((MaterialButtonToggleGroup) activity.findViewById(R.id.step_mode_group))
                        .getCheckedButtonId());
        assertFalse(ConfigTransport.readModuleSettings(activity).stepHookEnabled);
    }

    @Test
    public void rotationAcrossHourCannotRestorePreviousHourText() {
        long hour = HealthConnectSteps.currentHourStartSeconds();
        assertTrue(preferences.edit()
                .putString(HookConfig.KEY_STEP_MODE, HookConfig.MODE_HEALTH_CONNECT)
                .putInt(HookConfig.KEY_HEALTH_STEP_COUNT, 400)
                .putLong(HookConfig.KEY_HEALTH_STEP_HOUR, hour)
                .commit());
        HookSettingsActivity activity = start(null);
        ((TextInputEditText) activity.findViewById(R.id.step_count_input)).setText("800");
        Bundle state = new Bundle();
        controller.saveInstanceState(state);
        // Keep the saved view text, but make its associated draft hour stale.
        state.putLong("health_hour", hour - 3600);
        destroyActivity();

        activity = start(state);

        assertEquals("0", ((TextInputEditText) activity.findViewById(R.id.step_count_input))
                .getText().toString());
        assertEquals(400, preferences.getInt(HookConfig.KEY_HEALTH_STEP_COUNT, -1));
        assertNull(((ShadowActivity) Shadow.extract(activity)).getNextStartedActivity());
    }

    private void installHealthProvider(boolean enabled) {
        ShadowPackageManager packageManager = Shadow.extract(
                RuntimeEnvironment.getApplication().getPackageManager());
        ApplicationInfo application = new ApplicationInfo();
        application.packageName = HealthConnectSteps.PROVIDER_PACKAGE;
        application.enabled = enabled;
        PackageInfo provider = new PackageInfo();
        provider.packageName = application.packageName;
        provider.applicationInfo = application;
        provider.setLongVersionCode(68623);
        packageManager.installPackage(provider);

        ServiceInfo service = new ServiceInfo();
        service.packageName = application.packageName;
        service.name = application.packageName + ".HealthDataService";
        service.applicationInfo = application;
        service.enabled = enabled;
        service.exported = true;
        ResolveInfo result = new ResolveInfo();
        result.serviceInfo = service;
        packageManager.addResolveInfoForIntent(new Intent(
                "androidx.health.ACTION_BIND_HEALTH_DATA_SERVICE").setPackage(application.packageName), result);
    }

    private void dismissStartupReminder() {
        ShadowLooper.idleMainLooper();
        if (ShadowDialog.getLatestDialog() != null) {
            ShadowDialog.getLatestDialog().dismiss();
        }
    }

    private AlertDialog openAppSettings(HookSettingsActivity activity) {
        dismissStartupReminder();
        MaterialToolbar toolbar = activity.findViewById(R.id.toolbar);
        assertTrue(toolbar.getMenu().performIdentifierAction(R.id.action_settings, 0));
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        return dialog;
    }

    private HookSettingsActivity start(Bundle state) {
        controller = Robolectric.buildActivity(HookSettingsActivity.class);
        if (state == null) {
            controller.setup();
        } else {
            controller.create(state).start().restoreInstanceState(state).resume().visible();
        }
        return controller.get();
    }
}
