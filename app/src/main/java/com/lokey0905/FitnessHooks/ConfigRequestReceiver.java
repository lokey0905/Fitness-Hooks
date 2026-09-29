package com.lokey0905.FitnessHooks;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public final class ConfigRequestReceiver extends BroadcastReceiver {
    private static final String TAG = "FitnessHooks";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null
                || !ConfigTransport.ACTION_REQUEST_CONFIG.equals(intent.getAction())) {
            return;
        }

        HookConfig.Snapshot snapshot = ConfigTransport.readModuleSettings(context);
        ConfigTransport.publishToTarget(context, snapshot);
        Log.i(TAG, "Published config to target; sdk=" + snapshot.sdkHookEnabled
                + "; steps=" + snapshot.stepHookEnabled
                + "; count=" + snapshot.stepCount);
    }
}
