package com.lokey0905.FitnessHooks;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.GridLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;

public final class HookSettingsActivity extends Activity {
    private static final int[] STEP_DELTAS = {1, 10, 100, 500, 1000, 5000};
    private static final int STEP_BUTTON_COLUMNS = 3;

    private final List<MaterialButton> adjustmentButtons = new ArrayList<>();
    private MaterialSwitch sdkHookSwitch;
    private MaterialSwitch stepHookSwitch;
    private TextView sdkHookSummary;
    private TextInputLayout stepCountLayout;
    private TextInputEditText stepCountInput;
    private boolean sdkHookAllowed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hook_settings);

        findViewById(R.id.module_status_card).setVisibility(
                isModuleActive() ? View.GONE : View.VISIBLE
        );
        sdkHookAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
        sdkHookSwitch = findViewById(R.id.sdk_hook_switch);
        stepHookSwitch = findViewById(R.id.step_hook_switch);
        sdkHookSummary = findViewById(R.id.sdk_hook_summary);
        stepCountLayout = findViewById(R.id.step_count_layout);
        stepCountInput = findViewById(R.id.step_count_input);

        addAdjustmentButtons(findViewById(R.id.increment_buttons), true);
        addAdjustmentButtons(findViewById(R.id.decrement_buttons), false);

        stepHookSwitch.setOnCheckedChangeListener(
                (button, checked) -> setStepControlsEnabled(checked)
        );
        findViewById(R.id.save_button).setOnClickListener(view -> saveSettings());

        loadSettings();
    }

    public static boolean isModuleActive() {
        return false;
    }

    private void loadSettings() {
        HookConfig.Snapshot snapshot = ConfigTransport.readModuleSettings(this);
        sdkHookSwitch.setChecked(snapshot.sdkHookEnabled);
        sdkHookSwitch.setEnabled(sdkHookAllowed);
        if (!sdkHookAllowed) {
            sdkHookSwitch.setChecked(false);
            sdkHookSummary.setText(R.string.sdk_hook_unavailable);
            getSharedPreferences(HookConfig.PREFS_NAME, 0)
                    .edit()
                    .putBoolean(HookConfig.KEY_SDK_HOOK_ENABLED, false)
                    .apply();
        }

        stepHookSwitch.setChecked(snapshot.stepHookEnabled);
        stepCountInput.setText(String.valueOf(snapshot.stepCount));
        setStepControlsEnabled(snapshot.stepHookEnabled);
        ConfigTransport.publishToTarget(this, snapshot);
    }

    private void saveSettings() {
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

        boolean sdkEnabled = sdkHookAllowed && sdkHookSwitch.isChecked();
        boolean saved = getSharedPreferences(HookConfig.PREFS_NAME, 0)
                .edit()
                .putBoolean(HookConfig.KEY_SDK_HOOK_ENABLED, sdkEnabled)
                .putBoolean(HookConfig.KEY_STEP_HOOK_ENABLED, stepHookSwitch.isChecked())
                .putInt(HookConfig.KEY_STEP_COUNT, stepCount)
                .commit();
        if (saved) {
            ConfigTransport.publishToTarget(this, new HookConfig.Snapshot(
                    sdkEnabled,
                    stepHookSwitch.isChecked(),
                    stepCount
            ));
        }
        Toast.makeText(
                this,
                saved ? R.string.saved : R.string.save_failed,
                Toast.LENGTH_LONG
        ).show();
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
