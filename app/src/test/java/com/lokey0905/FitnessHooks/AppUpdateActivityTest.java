package com.lokey0905.FitnessHooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.appbar.MaterialToolbar;

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
import org.robolectric.util.ReflectionHelpers.ClassParameter;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, shadows = ShadowAppUpdateViewModel.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class AppUpdateActivityTest {
    private ActivityController<HookSettingsActivity> controller;

    @Before
    public void setUp() {
        assertTrue(RuntimeEnvironment.getApplication()
                .getSharedPreferences(HookConfig.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().putString(HookConfig.KEY_STEP_MODE, HookConfig.MODE_RECORDING)
                .commit());
        controller = Robolectric.buildActivity(HookSettingsActivity.class).setup();
        ShadowLooper.idleMainLooper();
    }

    @After
    public void tearDown() {
        if (controller != null) {
            controller.pause().stop().destroy();
        }
    }

    @Test
    @Config(sdk = {28, 33, 34})
    public void startupChecksAutomaticallyAndWaitsForModeReminder() {
        ShadowAppUpdateViewModel shadow = Shadow.extract(model());
        assertEquals(1, shadow.checks);
        AlertDialog reminder = latestDialog();
        deliver(200, releaseJson());
        assertSame(reminder, latestDialog());
        assertNull(updateDialog());

        dismissLatest();

        AlertDialog update = updateDialog();
        assertNotNull(update);
        assertTrue(update.isShowing());
        String message = ((TextView) update.findViewById(android.R.id.message)).getText().toString();
        assertTrue(message.contains(BuildConfig.VERSION_NAME));
        assertTrue(message.contains("2.3.0"));
        assertTrue(message.contains("Release notes"));
    }

    @Test
    public void failedMissingAndCurrentReleasesStayQuiet() {
        dismissLatest();
        deliver(404, "");
        assertNull(updateDialog());
        deliver(429, "");
        assertNull(updateDialog());
        deliver(500, "");
        assertNull(updateDialog());
        deliver(200, releaseJson().replace("2.3.0", BuildConfig.VERSION_NAME));
        assertNull(updateDialog());
    }

    @Test
    public void pausedResultWaitsUntilResume() {
        dismissLatest();
        controller.pause().stop();
        deliver(200, releaseJson());
        assertNull(updateDialog());

        controller.start().resume().visible();

        assertNotNull(updateDialog());
        assertTrue(updateDialog().isShowing());
    }

    @Test
    public void aboutDialogAlsoDefersTheUpdatePrompt() {
        dismissLatest();
        MaterialToolbar toolbar = controller.get().findViewById(R.id.toolbar);
        toolbar.getMenu().performIdentifierAction(R.id.action_about, 0);
        AlertDialog about = latestDialog();
        deliver(200, releaseJson());
        assertSame(about, latestDialog());
        assertNull(updateDialog());

        dismissLatest();

        assertTrue(updateDialog().isShowing());
    }

    @Test
    public void explicitDownloadActionOnlyOpensTheReleasePage() {
        dismissLatest();
        deliver(200, releaseJson());
        ShadowActivity shadow = Shadow.extract(controller.get());
        assertNull(shadow.getNextStartedActivity());

        updateDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        Intent intent = shadow.getNextStartedActivity();
        assertEquals(Intent.ACTION_VIEW, intent.getAction());
        assertEquals(GitHubReleaseClient.RELEASES_URL + "/tag/v2.3.0", intent.getDataString());
        assertNull(model().pendingUpdate());
    }

    @Test
    public void visibleUpdateSurvivesRecreationUsingTheSameModel() {
        dismissLatest();
        deliver(200, releaseJson());
        AppUpdateViewModel originalModel = model();
        AlertDialog originalDialog = updateDialog();

        controller.recreate();
        ShadowLooper.idleMainLooper();

        assertSame(originalModel, model());
        assertFalse(originalDialog.isShowing());
        assertTrue(updateDialog().isShowing());
    }

    @Test
    public void laterDoesNotRepromptOnResumeOrRotation() {
        dismissLatest();
        deliver(200, releaseJson());
        updateDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        ShadowLooper.idleMainLooper();

        controller.pause().stop().start().resume().visible();
        controller.recreate();
        ShadowLooper.idleMainLooper();

        assertNull(model().pendingUpdate());
        assertNull(updateDialog());
    }

    @Test
    public void restoredSyncCompletionReleasesPendingUpdate() {
        dismissLatest();
        ReflectionHelpers.setField(controller.get(), "restoringSync", true);
        deliver(200, releaseJson());
        assertNull(updateDialog());

        ReflectionHelpers.callInstanceMethod(controller.get(), "restoreSyncTotal");
        ShadowLooper.idleMainLooper();

        assertTrue(updateDialog().isShowing());
    }

    private void deliver(int status, String body) {
        GitHubReleaseClient.Result result = new GitHubReleaseClient(
                () -> new GitHubReleaseClient.Response(status, body, false))
                .check(BuildConfig.VERSION_NAME);
        ReflectionHelpers.callInstanceMethod(model(), "finishCheck",
                ClassParameter.from(GitHubReleaseClient.Result.class, result));
        ShadowLooper.idleMainLooper();
    }

    private AppUpdateViewModel model() {
        return ReflectionHelpers.getField(controller.get(), "updates");
    }

    private AlertDialog updateDialog() {
        return ReflectionHelpers.getField(controller.get(), "updateDialog");
    }

    private AlertDialog latestDialog() {
        return (AlertDialog) ShadowDialog.getLatestDialog();
    }

    private void dismissLatest() {
        latestDialog().dismiss();
        ShadowLooper.idleMainLooper();
    }

    private String releaseJson() {
        return "{\"tag_name\":\"v2.3.0\",\"draft\":false,\"prerelease\":false,"
                + "\"body\":\"Release notes\",\"html_url\":\""
                + GitHubReleaseClient.RELEASES_URL + "/tag/v2.3.0\"}";
    }
}
