package com.lokey0905.FitnessHooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.view.ViewCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.materialswitch.MaterialSwitch;

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
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, shadows = {
        ShadowAppUpdateViewModel.class,
        AppInstallationActivityTest.ProbeShadow.class,
        AppInstallationActivityTest.InstallationShadow.class
})
@LooperMode(LooperMode.Mode.PAUSED)
public final class AppInstallationActivityTest {
    private static final Set<String> EXPECTED_PACKAGES = Set.of(
            ConfigTransport.FIT_PACKAGE, ConfigTransport.HC_PACKAGE, ConfigTransport.TARGET_PACKAGE);

    private ActivityController<HookSettingsActivity> controller;
    private SharedPreferences preferences;

    @Before
    public void setUp() {
        InstallationShadow.checks.clear();
        InstallationShadow.states.clear();
        for (String packageName : EXPECTED_PACKAGES) {
            InstallationShadow.states.put(packageName, AppInstallation.State.INSTALLED);
        }
        ProbeShadow.supported = true;
        ProbeShadow.osSupported = true;
        ProbeShadow.throwSupport = false;
        preferences = RuntimeEnvironment.getApplication().getSharedPreferences(
                HookConfig.PREFS_NAME, Context.MODE_PRIVATE);
        assertTrue(preferences.edit().clear()
                .putString(HookConfig.KEY_STEP_MODE, HookConfig.MODE_RECORDING)
                .putBoolean(HookConfig.KEY_SDK_HOOK_ENABLED, false)
                .putBoolean(HookConfig.KEY_DISABLE_DYNAMIC_COLORS, true)
                .commit());
    }

    @After
    public void tearDown() {
        destroy();
    }

    @Test
    public void sectionsStartCollapsedAndCanBeExpandedIndependently() {
        HookSettingsActivity activity = start(null);
        assertVisibility(R.id.compatibility_check_details, View.GONE);
        assertVisibility(R.id.app_check_details, View.GONE);
        assertText(R.id.compatibility_check_summary, R.string.hc_probe_passed);

        activity.findViewById(R.id.compatibility_check_header).performClick();
        assertVisibility(R.id.compatibility_check_details, View.VISIBLE);
        assertVisibility(R.id.app_check_details, View.GONE);
        assertEquals(activity.getString(R.string.diagnostics_expanded),
                ViewCompat.getStateDescription(activity.findViewById(R.id.compatibility_check_header)));

        activity.findViewById(R.id.app_check_header).performClick();
        activity.findViewById(R.id.compatibility_check_header).performClick();
        assertVisibility(R.id.compatibility_check_details, View.GONE);
        assertVisibility(R.id.app_check_details, View.VISIBLE);
        assertEquals(180f, activity.findViewById(R.id.app_check_arrow).getRotation(), 0f);
        assertEquals(activity.getString(R.string.diagnostics_collapsed),
                ViewCompat.getStateDescription(activity.findViewById(R.id.compatibility_check_header)));
    }

    @Test
    public void manuallyCollapsedFailuresStayCollapsedAfterRefreshAndRecreation() {
        ProbeShadow.supported = false;
        InstallationShadow.states.put(ConfigTransport.FIT_PACKAGE, AppInstallation.State.NOT_INSTALLED);
        HookSettingsActivity activity = start(null);
        assertVisibility(R.id.compatibility_check_details, View.VISIBLE);
        assertVisibility(R.id.app_check_details, View.VISIBLE);
        activity.findViewById(R.id.app_check_header).performClick();
        activity.findViewById(R.id.compatibility_check_header).performClick();
        resumeAgain();
        assertVisibility(R.id.compatibility_check_details, View.GONE);
        assertVisibility(R.id.app_check_details, View.GONE);
        assertText(R.id.compatibility_check_summary, R.string.hc_probe_failed);
        assertText(R.id.app_fit_result, R.string.app_check_not_installed);

        Bundle state = new Bundle();
        controller.saveInstanceState(state);
        destroy();

        start(state);
        resumeAgain();
        assertVisibility(R.id.compatibility_check_details, View.GONE);
        assertVisibility(R.id.app_check_details, View.GONE);
        destroy();

        start(null);
        assertVisibility(R.id.compatibility_check_details, View.VISIBLE);
        assertVisibility(R.id.app_check_details, View.VISIBLE);
    }

    @Test
    public void manualExpansionSurvivesRecoveryAndRecreationUntilANewLaunch() {
        ProbeShadow.supported = false;
        InstallationShadow.states.put(ConfigTransport.FIT_PACKAGE, AppInstallation.State.NOT_INSTALLED);
        HookSettingsActivity activity = start(null);
        // Explicitly close and reopen, so these expansions represent the user's choice.
        activity.findViewById(R.id.compatibility_check_header).performClick();
        activity.findViewById(R.id.compatibility_check_header).performClick();
        activity.findViewById(R.id.app_check_header).performClick();
        activity.findViewById(R.id.app_check_header).performClick();

        ProbeShadow.supported = true;
        InstallationShadow.states.put(ConfigTransport.FIT_PACKAGE, AppInstallation.State.INSTALLED);
        resumeAgain();
        assertVisibility(R.id.compatibility_check_details, View.VISIBLE);
        assertVisibility(R.id.app_check_details, View.VISIBLE);
        assertText(R.id.compatibility_check_summary, R.string.hc_probe_passed);
        assertText(R.id.app_fit_result, R.string.app_check_installed);

        Bundle state = new Bundle();
        controller.saveInstanceState(state);
        destroy();
        start(state);
        assertVisibility(R.id.compatibility_check_details, View.VISIBLE);
        assertVisibility(R.id.app_check_details, View.VISIBLE);
        destroy();

        start(null);
        assertVisibility(R.id.compatibility_check_details, View.GONE);
        assertVisibility(R.id.app_check_details, View.GONE);
    }

    @Test
    @Config(sdk = {33, 34})
    public void allThreeAppsAreCheckedEvenWithExperimentsDisabledOnOlderAndroid() {
        start(null);
        assertFalse(preferences.getBoolean(HookConfig.KEY_EXPERIMENTAL_FEATURES_ENABLED, false));
        assertCheckedAllAppsSince(0);
        assertAllRowsPresent();
        assertText(R.id.app_fit_result, R.string.app_check_installed);
        assertText(R.id.app_hc_result, R.string.app_check_installed);
        assertText(R.id.app_pikmin_result, R.string.app_check_installed);
    }

    @Test
    public void switchingModesAndExperimentalSettingKeepsTheSameThreeApps() {
        HookSettingsActivity activity = start(null);
        activity.findViewById(R.id.app_check_header).performClick();
        MaterialButtonToggleGroup modes = activity.findViewById(R.id.step_mode_group);
        modes.check(R.id.mode_health);
        dismissDialog();
        recheckAndAssertAllApps();

        MaterialToolbar toolbar = activity.findViewById(R.id.toolbar);
        assertTrue(toolbar.getMenu().performIdentifierAction(R.id.action_settings, 0));
        AlertDialog settings = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(settings);
        MaterialSwitch experimental = settings.findViewById(R.id.experimental_features_switch);
        experimental.setChecked(true);
        settings.dismiss();
        ShadowLooper.idleMainLooper();
        recheckAndAssertAllApps();

        modes.check(R.id.mode_recording);
        dismissDialog();
        assertTrue(preferences.edit().putBoolean(HookConfig.KEY_EXPERIMENTAL_FEATURES_ENABLED, false).commit());
        resumeAgain();
        recheckAndAssertAllApps();
    }

    @Test
    public void missingAppsExpandTheSectionAndSuccessfulRefreshCollapsesIt() {
        InstallationShadow.states.put(ConfigTransport.HC_PACKAGE, AppInstallation.State.NOT_INSTALLED);
        InstallationShadow.states.put(ConfigTransport.TARGET_PACKAGE, AppInstallation.State.NOT_INSTALLED);
        HookSettingsActivity activity = start(null);
        assertVisibility(R.id.app_check_details, View.VISIBLE);
        assertVisibility(R.id.compatibility_check_details, View.GONE);
        assertText(R.id.app_fit_icon, R.string.device_info_icon_passed);
        assertText(R.id.app_hc_icon, R.string.device_info_icon_failed);
        assertText(R.id.app_pikmin_icon, R.string.device_info_icon_failed);
        assertText(R.id.app_hc_result, R.string.app_check_not_installed);
        assertText(R.id.app_pikmin_result, R.string.app_check_not_installed);

        InstallationShadow.states.put(ConfigTransport.HC_PACKAGE, AppInstallation.State.INSTALLED);
        InstallationShadow.states.put(ConfigTransport.TARGET_PACKAGE, AppInstallation.State.INSTALLED);
        activity.findViewById(R.id.app_check_refresh).performClick();
        assertVisibility(R.id.app_check_details, View.GONE);
        assertText(R.id.app_hc_result, R.string.app_check_installed);
        assertText(R.id.app_pikmin_icon, R.string.device_info_icon_passed);
    }

    @Test
    public void unknownResultsDoNotAutoExpandButKnownFailuresStillDo() {
        // PackageManager exceptions map to UNKNOWN in AppInstallation; this checks its UI contract.
        InstallationShadow.states.put(ConfigTransport.HC_PACKAGE, AppInstallation.State.UNKNOWN);
        ProbeShadow.throwSupport = true;
        start(null);
        assertVisibility(R.id.compatibility_check_details, View.GONE);
        assertVisibility(R.id.app_check_details, View.GONE);
        assertText(R.id.compatibility_check_summary, R.string.hc_probe_unknown);
        assertText(R.id.hc_is_supported_result, R.string.hc_probe_unknown);
        assertText(R.id.app_hc_result, R.string.app_check_unknown);
        assertText(R.id.app_hc_icon, R.string.device_info_icon_unknown);

        ProbeShadow.osSupported = false;
        resumeAgain();
        assertVisibility(R.id.compatibility_check_details, View.VISIBLE);
        assertVisibility(R.id.app_check_details, View.GONE);
        assertText(R.id.compatibility_check_summary, R.string.hc_probe_failed);

        InstallationShadow.states.put(ConfigTransport.TARGET_PACKAGE, AppInstallation.State.NOT_INSTALLED);
        resumeAgain();
        assertVisibility(R.id.app_check_details, View.VISIBLE);
        assertText(R.id.app_hc_result, R.string.app_check_unknown);
        assertText(R.id.app_pikmin_result, R.string.app_check_not_installed);
        assertText(R.id.app_pikmin_icon, R.string.device_info_icon_failed);
    }

    @Test
    public void manualRefreshAndReturningToForegroundReadNewInstallationState() {
        InstallationShadow.states.put(ConfigTransport.TARGET_PACKAGE, AppInstallation.State.NOT_INSTALLED);
        HookSettingsActivity activity = start(null);
        assertText(R.id.app_pikmin_result, R.string.app_check_not_installed);

        int beforeRefresh = InstallationShadow.checks.size();
        InstallationShadow.states.put(ConfigTransport.TARGET_PACKAGE, AppInstallation.State.INSTALLED);
        activity.findViewById(R.id.app_check_refresh).performClick();
        assertCheckedAllAppsSince(beforeRefresh);
        assertText(R.id.app_pikmin_result, R.string.app_check_installed);

        int beforeResume = InstallationShadow.checks.size();
        InstallationShadow.states.put(ConfigTransport.FIT_PACKAGE, AppInstallation.State.NOT_INSTALLED);
        resumeAgain();
        assertCheckedAllAppsSince(beforeResume);
        assertText(R.id.app_fit_result, R.string.app_check_not_installed);
        assertText(R.id.app_pikmin_result, R.string.app_check_installed);
    }

    @Test
    public void automaticExpansionAndRefreshRemainIndependentBetweenSections() {
        ProbeShadow.supported = false;
        HookSettingsActivity activity = start(null);
        assertVisibility(R.id.compatibility_check_details, View.VISIBLE);
        assertVisibility(R.id.app_check_details, View.GONE);
        assertText(R.id.compatibility_check_summary, R.string.hc_probe_failed);

        ProbeShadow.supported = true;
        InstallationShadow.states.put(ConfigTransport.HC_PACKAGE, AppInstallation.State.NOT_INSTALLED);
        resumeAgain();
        assertVisibility(R.id.compatibility_check_details, View.GONE);
        assertVisibility(R.id.app_check_details, View.VISIBLE);
        assertText(R.id.compatibility_check_summary, R.string.hc_probe_passed);
        assertText(R.id.app_hc_result, R.string.app_check_not_installed);

        ProbeShadow.supported = false;
        InstallationShadow.states.put(ConfigTransport.HC_PACKAGE, AppInstallation.State.INSTALLED);
        activity.findViewById(R.id.app_check_refresh).performClick();
        assertVisibility(R.id.app_check_details, View.GONE);
        assertText(R.id.app_hc_result, R.string.app_check_installed);
        // App-only refresh must not replace the last compatibility result.
        assertVisibility(R.id.compatibility_check_details, View.GONE);
        assertText(R.id.compatibility_check_summary, R.string.hc_probe_passed);
    }

    private HookSettingsActivity start(Bundle state) {
        controller = Robolectric.buildActivity(HookSettingsActivity.class);
        if (state == null) {
            controller.setup();
        } else {
            controller.create(state).start().restoreInstanceState(state).resume().visible();
        }
        dismissDialog();
        return controller.get();
    }

    private void destroy() {
        if (controller == null) return;
        controller.pause().stop().destroy();
        controller = null;
    }

    private void resumeAgain() {
        controller.pause().resume();
        ShadowLooper.idleMainLooper();
    }

    private void dismissDialog() {
        ShadowLooper.idleMainLooper();
        if (ShadowDialog.getLatestDialog() != null) ShadowDialog.getLatestDialog().dismiss();
        ShadowLooper.idleMainLooper();
    }

    private void assertVisibility(int id, int expected) {
        assertEquals(expected, controller.get().findViewById(id).getVisibility());
    }

    private void assertText(int id, int expectedString) {
        HookSettingsActivity activity = controller.get();
        assertEquals(activity.getString(expectedString),
                ((TextView) activity.findViewById(id)).getText().toString());
    }

    private void assertAllRowsPresent() {
        assertVisibility(R.id.app_fit_row, View.VISIBLE);
        assertVisibility(R.id.app_hc_row, View.VISIBLE);
        assertVisibility(R.id.app_pikmin_row, View.VISIBLE);
    }

    private void recheckAndAssertAllApps() {
        int before = InstallationShadow.checks.size();
        controller.get().findViewById(R.id.app_check_refresh).performClick();
        assertCheckedAllAppsSince(before);
        assertAllRowsPresent();
    }

    private static void assertCheckedAllAppsSince(int index) {
        assertEquals(EXPECTED_PACKAGES,
                new HashSet<>(InstallationShadow.checks.subList(index, InstallationShadow.checks.size())));
    }

    @Implements(PikminHealthConnectProbe.class)
    public static final class ProbeShadow {
        static boolean supported;
        static boolean osSupported;
        static boolean throwSupport;

        @Implementation
        protected boolean isSupported() {
            if (throwSupport) throw new IllegalStateException("Provider status unavailable");
            return supported;
        }

        @Implementation
        protected boolean osSupportsHealthConnect() {
            return osSupported;
        }
    }

    @Implements(AppInstallation.class)
    public static final class InstallationShadow {
        static final List<String> checks = new ArrayList<>();
        static final Map<String, AppInstallation.State> states = new LinkedHashMap<>();

        @Implementation
        protected static AppInstallation.State check(Context context, String packageName) {
            checks.add(packageName);
            return states.getOrDefault(packageName, AppInstallation.State.UNKNOWN);
        }
    }
}
