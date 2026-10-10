package com.lokey0905.FitnessHooks;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.GridLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class HookSettingsActivity extends ComponentActivity {
    private static final int[] STEP_DELTAS = {1, 10, 100, 500, 1000, 5000};
    private static final int STEP_BUTTON_COLUMNS = 3;
    private static final int STEP_WARNING_THRESHOLD = 50_000;

    private final List<MaterialButton> adjustmentButtons = new ArrayList<>();
    private MaterialSwitch sdkHookSwitch;
    private MaterialSwitch stepHookSwitch;
    private TextView sdkHookSummary;
    private TextInputLayout stepCountLayout;
    private TextInputEditText stepCountInput;
    private boolean sdkHookAllowed;
    private final HealthConnectSteps healthConnect = new HealthConnectSteps();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MaterialButtonToggleGroup modeGroup;
    private MaterialButton saveButton;
    private TextView healthStatus;
    private ActivityResultLauncher<Set<String>> permissionLauncher;
    private String selectedMode;
    private String recordingDraft;
    private String healthDraft;
    private long healthHour;
    private boolean changingMode;
    private boolean busy;
    private boolean restoringSync;
    private AppUpdateViewModel updates;
    private final List<AlertDialog> settingsDialogs = new ArrayList<>();
    private AlertDialog updateDialog;
    private boolean resumed;
    private boolean destroying;
    private boolean startupReminderPending;
    private int statusGeneration;
    private HealthConnectSteps.Availability availability;
    private final Runnable hourCheck = new Runnable() {
        @Override
        public void run() {
            if (healthMode() && !busy
                    && HealthConnectSteps.currentHourStartSeconds() != healthHour) {
                refreshHour();
            }
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hook_settings);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(
                getString(R.string.settings_title_version, BuildConfig.VERSION_NAME)
        );
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_about) {
                showAboutDialog();
                return true;
            }
            return false;
        });
        sdkHookSwitch = findViewById(R.id.sdk_hook_switch);
        stepHookSwitch = findViewById(R.id.step_hook_switch);
        sdkHookSummary = findViewById(R.id.sdk_hook_summary);
        stepCountLayout = findViewById(R.id.step_count_layout);
        stepCountInput = findViewById(R.id.step_count_input);
        modeGroup = findViewById(R.id.step_mode_group);
        saveButton = findViewById(R.id.save_button);
        healthStatus = findViewById(R.id.health_status);
        permissionLauncher = registerForActivityResult(
                HealthConnectSteps.permissionContract(), granted -> {
                    refreshEnvironment();
                    if (healthMode()) {
                        healthStatus.setText(granted.containsAll(HealthConnectSteps.writePermissions())
                                ? R.string.health_permission_granted : R.string.health_permission_denied);
                    }
                }
        );

        addAdjustmentButtons(findViewById(R.id.increment_buttons), true);
        addAdjustmentButtons(findViewById(R.id.decrement_buttons), false);

        stepHookSwitch.setOnCheckedChangeListener(
                (button, checked) -> updateModeViews()
        );
        sdkHookSwitch.setOnCheckedChangeListener((button, checked) -> updateModeViews());
        saveButton.setOnClickListener(view -> saveSettings(false));
        findViewById(R.id.health_action).setOnClickListener(view -> healthAction());
        findViewById(R.id.health_settings).setOnClickListener(
                view -> openExternal(HealthConnectSteps.settingsIntent(this))
        );
        stepCountInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable text) {
                updateStepWarning();
            }
        });

        loadSettings(savedInstanceState);
        modeGroup.addOnButtonCheckedListener((group, checkedId, checked) -> {
            if (checked && !changingMode) {
                selectMode(checkedId == R.id.mode_health
                        ? HookConfig.MODE_HEALTH_CONNECT : HookConfig.MODE_RECORDING);
            }
        });
        updates = new ViewModelProvider(this).get(AppUpdateViewModel.class);
        startupReminderPending = savedInstanceState == null;
        if (startupReminderPending) {
            modeGroup.post(() -> {
                startupReminderPending = false;
                showModeReminder();
            });
        }
        updates.checkOnce();
    }

    private void showAboutDialog() {
        View content = getLayoutInflater().inflate(R.layout.dialog_about, null);
        ((TextView) content.findViewById(R.id.about_version)).setText(
                getString(R.string.about_version_format,
                        BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        );
        showSettingsDialog(new MaterialAlertDialogBuilder(this)
                .setView(content)
                .setPositiveButton(R.string.about_close, null));
    }

    private void showSettingsDialog(MaterialAlertDialogBuilder builder) {
        AlertDialog dialog = builder.create();
        settingsDialogs.add(dialog);
        dialog.setOnDismissListener(ignored -> {
            settingsDialogs.remove(dialog);
            handler.post(this::maybeShowUpdate);
        });
        dialog.show();
    }

    private void maybeShowUpdate() {
        if (!resumed || destroying || isFinishing() || isDestroyed()
                || startupReminderPending || !settingsDialogs.isEmpty()
                || updateDialog != null || busy || restoringSync || updates == null) {
            return;
        }
        GitHubReleaseClient.Release release = updates.pendingUpdate();
        if (release == null) {
            return;
        }
        String message = getString(R.string.update_version_format,
                BuildConfig.VERSION_NAME, release.version);
        if (!release.notes.isEmpty()) {
            message += "\n\n" + release.notes;
        }
        updateDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.update_available_title)
                .setMessage(message)
                .setNegativeButton(R.string.update_later, null)
                .setPositiveButton(R.string.update_open_release, (dialog, which) ->
                        openExternal(new Intent(Intent.ACTION_VIEW, Uri.parse(release.url))))
                .create();
        updateDialog.setOnDismissListener(ignored -> {
            updates.dismissUpdate();
            updateDialog = null;
        });
        updateDialog.show();
    }

    public static boolean isModuleActive() {
        return false;
    }

    private SharedPreferences preferences() {
        return getSharedPreferences(HookConfig.PREFS_NAME, 0);
    }

    private boolean healthMode() {
        return HookConfig.MODE_HEALTH_CONNECT.equals(selectedMode);
    }

    private void loadSettings(Bundle state) {
        HookConfig.Snapshot snapshot = ConfigTransport.readModuleSettings(this);
        selectedMode = preferences().getString(HookConfig.KEY_STEP_MODE, HookConfig.DEFAULT_STEP_MODE);
        recordingDraft = String.valueOf(snapshot.stepCount);
        healthHour = preferences().getLong(HookConfig.KEY_HEALTH_STEP_HOUR, -1);
        healthDraft = String.valueOf(preferences().getInt(HookConfig.KEY_HEALTH_STEP_COUNT, 0));
        if (state != null) {
            recordingDraft = state.getString("recording_draft", recordingDraft);
            healthDraft = state.getString("health_draft", healthDraft);
            healthHour = state.getLong("health_hour", healthHour);
            restoringSync = state.getBoolean("sync_pending", false);
        }
        sdkHookSwitch.setChecked(snapshot.sdkHookEnabled);
        stepHookSwitch.setChecked(preferences().getBoolean(
                HookConfig.KEY_STEP_HOOK_ENABLED, HookConfig.DEFAULT_STEP_HOOK_ENABLED
        ));
        modeGroup.check(healthMode() ? R.id.mode_health : R.id.mode_recording);
        stepCountInput.setText(healthMode() ? healthDraft : recordingDraft);
        refreshHour();
        ConfigTransport.publishToTarget(this, snapshot);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshHour();
        refreshEnvironment();
        handler.post(hourCheck);
        resumed = true;
        updates.setListener(this::maybeShowUpdate);
        maybeShowUpdate();
    }

    @Override
    protected void onPause() {
        resumed = false;
        updates.setListener(null);
        handler.removeCallbacks(hourCheck);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroying = true;
        updates.setListener(null);
        for (AlertDialog dialog : settingsDialogs) {
            dialog.setOnDismissListener(null);
            dialog.dismiss();
        }
        settingsDialogs.clear();
        if (updateDialog != null) {
            updateDialog.setOnDismissListener(null);
            updateDialog.dismiss();
            updateDialog = null;
        }
        handler.removeCallbacksAndMessages(null);
        healthConnect.close();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        rememberDraft();
        outState.putString("recording_draft", recordingDraft);
        outState.putString("health_draft", healthDraft);
        outState.putLong("health_hour", healthHour);
        outState.putBoolean("sync_pending", busy || restoringSync);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        // Drafts carry their original hour; restored view text must not undo rollover handling.
        stepCountInput.setText(healthMode() ? healthDraft : recordingDraft);
        refreshHour();
    }

    private void rememberDraft() {
        if (healthMode()) {
            healthDraft = String.valueOf(stepCountInput.getText());
        } else {
            recordingDraft = String.valueOf(stepCountInput.getText());
        }
    }

    private void selectMode(String mode) {
        if (mode.equals(selectedMode) || busy) {
            return;
        }
        rememberDraft();
        if (!preferences().edit().putString(HookConfig.KEY_STEP_MODE, mode).commit()) {
            changingMode = true;
            modeGroup.check(healthMode() ? R.id.mode_health : R.id.mode_recording);
            changingMode = false;
            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_LONG).show();
            return;
        }
        selectedMode = mode;
        stepCountInput.setText(healthMode() ? healthDraft : recordingDraft);
        stepCountLayout.setError(null);
        refreshHour();
        refreshEnvironment();
        showModeReminder();
    }

    private void showModeReminder() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (healthMode() && availability != HealthConnectSteps.Availability.AVAILABLE) {
            MaterialAlertDialogBuilder dialog = new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.mode_health)
                    .setMessage(availabilityMessage())
                    .setNegativeButton(R.string.cancel, null);
            if (downloadAvailable()) {
                dialog.setPositiveButton(R.string.health_download, (ignored, which) -> openDownload());
            } else {
                dialog.setPositiveButton(R.string.acknowledge, null);
            }
            showSettingsDialog(dialog);
            return;
        }
        showSettingsDialog(new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.mode_reminder_title)
                .setMessage(healthMode() ? R.string.mode_reminder_health : R.string.mode_reminder_recording)
                .setPositiveButton(R.string.acknowledge, null));
    }

    private boolean refreshHour() {
        long currentHour = HealthConnectSteps.currentHourStartSeconds();
        boolean changed = currentHour != healthHour;
        if (changed) {
            healthHour = currentHour;
            healthDraft = "0";
            if (healthMode()) {
                stepCountInput.setText(healthDraft);
                stepCountLayout.setError(null);
            }
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM/dd HH:mm xxx")
                .withZone(ZoneId.systemDefault());
        String interval = formatter.format(Instant.ofEpochSecond(healthHour)) + " - "
                + DateTimeFormatter.ofPattern("HH:mm xxx").withZone(ZoneId.systemDefault())
                        .format(Instant.ofEpochSecond(healthHour + 3600));
        ((TextView) findViewById(R.id.health_hour)).setText(
                getString(R.string.health_hour_format, interval)
        );
        return changed;
    }

    private void refreshEnvironment() {
        availability = HealthConnectSteps.availability(this);
        sdkHookAllowed = Build.VERSION.SDK_INT < 34
                && availability == HealthConnectSteps.Availability.AVAILABLE;
        if (!sdkHookAllowed) {
            sdkHookSwitch.setChecked(false);
            preferences().edit().putBoolean(HookConfig.KEY_SDK_HOOK_ENABLED, false).apply();
        }
        sdkHookSummary.setText(Build.VERSION.SDK_INT >= 34 ? R.string.sdk_hook_unavailable
                : sdkHookAllowed ? R.string.sdk_hook_summary : R.string.sdk_hook_requires_health);
        ConfigTransport.publishToTarget(this, ConfigTransport.readModuleSettings(this));
        updateModeViews();
        int generation = ++statusGeneration;
        if (!healthMode() || busy) {
            return;
        }
        if (availability != HealthConnectSteps.Availability.AVAILABLE) {
            restoreSyncTotal();
            updateModeViews();
            healthStatus.setText(availabilityMessage());
            return;
        }
        healthStatus.setText(R.string.health_checking);
        healthConnect.checkPermission(this, result -> {
            if (generation != statusGeneration || !healthMode() || busy) {
                return;
            }
            restoreSyncTotal();
            updateModeViews();
            healthStatus.setText(result == HealthConnectSteps.Result.READY ? R.string.health_ready
                    : result == HealthConnectSteps.Result.PERMISSION_REQUIRED
                    ? R.string.health_permission_required : R.string.health_failed);
            findViewById(R.id.health_action).setVisibility(
                    result == HealthConnectSteps.Result.READY ? View.GONE : View.VISIBLE
            );
        });
    }

    private void restoreSyncTotal() {
        if (!restoringSync) {
            return;
        }
        restoringSync = false;
        refreshHour();
        int count = preferences().getLong(HookConfig.KEY_HEALTH_STEP_HOUR, -1) == healthHour
                ? preferences().getInt(HookConfig.KEY_HEALTH_STEP_COUNT, 0) : 0;
        healthDraft = String.valueOf(count);
        if (healthMode()) {
            stepCountInput.setText(healthDraft);
        }
        handler.post(this::maybeShowUpdate);
    }

    private void updateModeViews() {
        boolean health = healthMode();
        findViewById(R.id.health_controls).setVisibility(health ? View.VISIBLE : View.GONE);
        findViewById(R.id.recording_controls).setVisibility(health ? View.GONE : View.VISIBLE);
        findViewById(R.id.health_hour).setVisibility(health ? View.VISIBLE : View.GONE);
        findViewById(R.id.health_step_explanation).setVisibility(health ? View.VISIBLE : View.GONE);
        findViewById(R.id.module_status_card).setVisibility(
                !isModuleActive() && (!health || sdkHookSwitch.isChecked()) ? View.VISIBLE : View.GONE
        );
        boolean working = busy || restoringSync;
        sdkHookSwitch.setEnabled(sdkHookAllowed && !working);
        stepHookSwitch.setEnabled(!working);
        findViewById(R.id.mode_recording).setEnabled(!working);
        findViewById(R.id.mode_health).setEnabled(!working);
        boolean available = availability == HealthConnectSteps.Availability.AVAILABLE;
        setStepControlsEnabled(!working && (health ? available : stepHookSwitch.isChecked()));
        stepCountLayout.setHint(getString(health ? R.string.health_step_count : R.string.step_count));
        saveButton.setText(working ? R.string.health_syncing : health ? R.string.health_sync : R.string.save);
        saveButton.setEnabled(!working && (!health || available));
        View action = findViewById(R.id.health_action);
        int actionTitle = availability == HealthConnectSteps.Availability.INSTALL_REQUIRED
                ? R.string.health_download : availability == HealthConnectSteps.Availability.UPDATE_REQUIRED
                ? R.string.health_update : R.string.health_grant;
        int actionSummary = downloadAvailable()
                ? R.string.health_download_summary : R.string.health_grant_summary;
        ((TextView) findViewById(R.id.health_action_title)).setText(actionTitle);
        ((TextView) findViewById(R.id.health_action_summary)).setText(actionSummary);
        action.setContentDescription(getString(actionTitle) + ", " + getString(actionSummary));
        action.setVisibility(available || downloadAvailable() ? View.VISIBLE : View.GONE);
        action.setEnabled(!working);
        action.setAlpha(working ? 0.55f : 1.0f);
        View settings = findViewById(R.id.health_settings);
        settings.setEnabled(!working && available);
        settings.setAlpha(!working && available ? 1.0f : 0.55f);
    }

    private boolean downloadAvailable() {
        return Build.VERSION.SDK_INT < 34 && (availability == HealthConnectSteps.Availability.INSTALL_REQUIRED
                || availability == HealthConnectSteps.Availability.UPDATE_REQUIRED);
    }

    private int availabilityMessage() {
        if (availability == HealthConnectSteps.Availability.INSTALL_REQUIRED) {
            return R.string.health_missing;
        }
        if (availability == HealthConnectSteps.Availability.UPDATE_REQUIRED) {
            return R.string.health_update_required;
        }
        if (availability == HealthConnectSteps.Availability.WORK_PROFILE_UNSUPPORTED) {
            return R.string.health_work_profile_unsupported;
        }
        return availability == HealthConnectSteps.Availability.PROFILE_UNSUPPORTED
                ? R.string.health_profile_unsupported : R.string.health_unavailable;
    }

    private void healthAction() {
        availability = HealthConnectSteps.availability(this);
        if (downloadAvailable()) {
            openDownload();
        } else if (availability == HealthConnectSteps.Availability.AVAILABLE) {
            showSettingsDialog(new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.health_grant)
                    .setMessage(R.string.health_privacy_body)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.health_grant, (dialog, which) -> {
                        try {
                            permissionLauncher.launch(HealthConnectSteps.writePermissions());
                        } catch (ActivityNotFoundException | SecurityException | IllegalStateException exception) {
                            healthStatus.setText(R.string.health_failed);
                        }
                    }));
        } else {
            refreshEnvironment();
            showModeReminder();
        }
    }

    private void openDownload() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(
                    "market://details?id=com.google.android.apps.healthdata"
            )));
        } catch (ActivityNotFoundException exception) {
            openExternal(new Intent(Intent.ACTION_VIEW, Uri.parse(
                    "https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata"
            )));
        } catch (SecurityException exception) {
            Toast.makeText(this, R.string.health_no_handler, Toast.LENGTH_LONG).show();
        }
    }

    private void openExternal(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException exception) {
            Toast.makeText(this, R.string.health_no_handler, Toast.LENGTH_LONG).show();
        }
    }

    private void updateStepWarning() {
        boolean overLimit = false;
        try {
            overLimit = Integer.parseInt(String.valueOf(stepCountInput.getText())) > STEP_WARNING_THRESHOLD;
        } catch (NumberFormatException ignored) {
        }
        findViewById(R.id.step_limit_warning).setVisibility(overLimit ? View.VISIBLE : View.GONE);
    }

    private void showStepWarning() {
        String expectedMode = selectedMode;
        String expectedInput = String.valueOf(stepCountInput.getText());
        long expectedHour = healthHour;
        showSettingsDialog(new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.step_limit_warning_title)
                .setMessage(getString(R.string.step_limit_warning) + "\n\n"
                        + getString(R.string.step_time_zone_notice))
                .setNegativeButton(R.string.step_limit_edit, (dialog, which) -> stepCountInput.requestFocus())
                .setPositiveButton(R.string.step_limit_continue, (dialog, which) -> {
                    if (isFinishing() || isDestroyed() || !expectedMode.equals(selectedMode)) {
                        return;
                    }
                    // A confirmation belongs to its original draft, not a new hour or input.
                    if (healthMode() && (refreshHour() || expectedHour != healthHour)) {
                        healthStatus.setText(R.string.health_hour_changed);
                        return;
                    }
                    if (expectedInput.equals(String.valueOf(stepCountInput.getText()))) {
                        saveSettings(true);
                    }
                }));
    }

    private void saveSettings(boolean stepLimitConfirmed) {
        if (busy || restoringSync) {
            return;
        }
        if (healthMode() && refreshHour()) {
            healthStatus.setText(R.string.health_hour_changed);
            return;
        }
        int stepCount;
        try {
            stepCount = Integer.parseInt(String.valueOf(stepCountInput.getText()));
        } catch (NumberFormatException exception) {
            stepCountLayout.setError(getString(R.string.invalid_step_count));
            return;
        }

        if (stepCount < 0 || stepCount > HookConfig.MAX_STEP_COUNT) {
            stepCountLayout.setError(getString(R.string.invalid_step_count));
            return;
        }
        stepCountLayout.setError(null);

        if (stepCount > STEP_WARNING_THRESHOLD && !stepLimitConfirmed) {
            showStepWarning();
            return;
        }

        boolean sdkEnabled = sdkHookAllowed && sdkHookSwitch.isChecked();
        SharedPreferences.Editor editor = preferences()
                .edit()
                .putBoolean(HookConfig.KEY_SDK_HOOK_ENABLED, sdkEnabled)
                .putBoolean(HookConfig.KEY_STEP_HOOK_ENABLED, stepHookSwitch.isChecked());
        if (!healthMode()) {
            editor.putInt(HookConfig.KEY_STEP_COUNT, stepCount);
        }
        if (!editor.commit()) {
            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_LONG).show();
            return;
        }
        ConfigTransport.publishToTarget(this, ConfigTransport.readModuleSettings(this));
        rememberDraft();
        if (!healthMode()) {
            Toast.makeText(this, R.string.saved, Toast.LENGTH_LONG).show();
            return;
        }
        final long writtenHour = healthHour;
        busy = true;
        ++statusGeneration;
        updateModeViews();
        healthStatus.setText(R.string.health_syncing);
        healthConnect.sync(this, stepCount, writtenHour, result -> {
            busy = false;
            updateModeViews();
            switch (result) {
                case SYNCED:
                case CLEARED:
                    refreshHour();
                    healthStatus.setText(healthHour != writtenHour ? R.string.health_previous_hour_synced
                            : result == HealthConnectSteps.Result.SYNCED
                                    ? R.string.health_synced : R.string.health_cleared);
                    break;
                case PERMISSION_REQUIRED:
                    healthStatus.setText(R.string.health_permission_required);
                    healthAction();
                    break;
                case HOUR_CHANGED:
                    refreshHour();
                    healthStatus.setText(R.string.health_hour_changed);
                    break;
                case UNAVAILABLE:
                    refreshEnvironment();
                    showModeReminder();
                    break;
                default:
                    healthStatus.setText(R.string.health_failed);
                    break;
            }
            maybeShowUpdate();
        });
    }

    private void addAdjustmentButtons(GridLayout container, boolean increment) {
        int backgroundAttr = increment
                ? com.google.android.material.R.attr.colorSecondaryContainer
                : com.google.android.material.R.attr.colorTertiaryContainer;
        int textAttr = increment
                ? com.google.android.material.R.attr.colorOnSecondaryContainer
                : com.google.android.material.R.attr.colorOnTertiaryContainer;
        int backgroundColor = MaterialColors.getColor(container, backgroundAttr);
        int textColor = MaterialColors.getColor(container, textAttr);

        for (int index = 0; index < STEP_DELTAS.length; index++) {
            int delta = STEP_DELTAS[index];
            int signedDelta = increment ? delta : -delta;
            MaterialButton button = new MaterialButton(this);
            button.setText(String.valueOf(delta));
            button.setContentDescription(getString(
                    increment ? R.string.step_add_format : R.string.step_subtract_format,
                    delta
            ));
            button.setTextColor(textColor);
            button.setBackgroundTintList(ColorStateList.valueOf(backgroundColor));
            button.setCornerRadius(dp(20));
            button.setMinWidth(0);
            button.setMinimumWidth(0);
            button.setInsetTop(0);
            button.setInsetBottom(0);
            button.setOnClickListener(view -> adjustStepCount(signedDelta));

            GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                    GridLayout.spec(index / STEP_BUTTON_COLUMNS),
                    GridLayout.spec(index % STEP_BUTTON_COLUMNS, 1.0f)
            );
            params.width = 0;
            params.height = dp(48);
            params.setMargins(dp(4), dp(4), dp(4), dp(4));
            container.addView(button, params);
            adjustmentButtons.add(button);
        }
    }

    private void adjustStepCount(int delta) {
        int current;
        try {
            current = Integer.parseInt(String.valueOf(stepCountInput.getText()));
        } catch (NumberFormatException exception) {
            current = 0;
        }

        long adjusted = (long) current + delta;
        int clamped = (int) Math.max(0, Math.min(HookConfig.MAX_STEP_COUNT, adjusted));
        stepCountInput.setText(String.valueOf(clamped));
        stepCountInput.setSelection(stepCountInput.length());
        stepCountLayout.setError(null);
    }

    private void setStepControlsEnabled(boolean enabled) {
        stepCountLayout.setEnabled(enabled);
        stepCountLayout.setAlpha(enabled ? 1.0f : 0.55f);
        for (MaterialButton button : adjustmentButtons) {
            button.setEnabled(enabled);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
