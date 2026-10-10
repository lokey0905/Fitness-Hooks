package com.lokey0905.FitnessHooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, shadows = ShadowAppUpdateViewModel.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class HookSettingsActivityTest {
    private ActivityController<HookSettingsActivity> controller;
    private SharedPreferences preferences;

    @Before
    public void resetSettingsToRecording() {
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
    @Config(sdk = {28, 33, 34})
    public void startupShowsCurrentVersionAndSixAdjustmentAmounts() {
        HookSettingsActivity activity = start(null);

        MaterialToolbar toolbar = activity.findViewById(R.id.toolbar);
        assertEquals("fitness hook " + BuildConfig.VERSION_NAME, toolbar.getTitle().toString());
        assertEquals("2.2.0", BuildConfig.VERSION_NAME);
        assertEquals(4, BuildConfig.VERSION_CODE);
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
    public void sharedSdkControlPrecedesModeSelectorAndStaysVisible() {
        HookSettingsActivity activity = start(null);
        View common = activity.findViewById(R.id.common_controls);
        MaterialButtonToggleGroup mode = activity.findViewById(R.id.step_mode_group);
        ViewGroup content = (ViewGroup) common.getParent();

        assertEquals(content, mode.getParent());
        assertTrue(content.indexOfChild(common) < content.indexOfChild(mode));
        assertNotNull(((ViewGroup) common).findViewById(R.id.sdk_hook_card));
        assertTrue(activity.findViewById(R.id.sdk_hook_card).isShown());

        mode.check(R.id.mode_health);

        assertTrue(activity.findViewById(R.id.sdk_hook_card).isShown());
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            assertFalse(activity.findViewById(R.id.sdk_hook_switch).isEnabled());
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

    private void dismissStartupReminder() {
        ShadowLooper.idleMainLooper();
        if (ShadowDialog.getLatestDialog() != null) {
            ShadowDialog.getLatestDialog().dismiss();
        }
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
