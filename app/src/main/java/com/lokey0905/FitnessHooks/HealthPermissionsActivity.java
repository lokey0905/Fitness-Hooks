package com.lokey0905.FitnessHooks;

import android.app.Activity;
import android.os.Bundle;

import com.google.android.material.color.DynamicColors;

public final class HealthPermissionsActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (!getSharedPreferences(HookConfig.PREFS_NAME, MODE_PRIVATE).getBoolean(
                HookConfig.KEY_DISABLE_DYNAMIC_COLORS, HookConfig.DEFAULT_DISABLE_DYNAMIC_COLORS)) {
            DynamicColors.applyToActivityIfAvailable(this);
        }
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_health_permissions);
        findViewById(R.id.close_privacy).setOnClickListener(view -> finish());
    }
}
