package com.lokey0905.FitnessHooks;

import android.content.Context;
import android.os.Build;

import androidx.annotation.Keep;
import androidx.health.connect.client.HealthConnectClient;
import androidx.health.connect.client.HealthConnectFeatures;

/** Local equivalents of the public HC gates in Pikmin Bloom 153/156. */
@Keep
final class PikminHealthConnectProbe {
    interface Source {
        int sdkStatus(Context context);
        int backgroundReadFeatureStatus(Context context);
    }

    private static final Source ANDROIDX = new Source() {
        @Override
        public int sdkStatus(Context context) {
            return HealthConnectSteps.sdkStatus(context);
        }

        @Override
        public int backgroundReadFeatureStatus(Context context) {
            return HealthConnectSteps.createClient(context).getFeatures().getFeatureStatus(
                    HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND);
        }
    };

    private final Context context;
    private final Source source;

    PikminHealthConnectProbe(Context context) {
        this(context, ANDROIDX);
    }

    PikminHealthConnectProbe(Context context, Source source) {
        this.context = context.getApplicationContext();
        this.source = source;
    }

    // Resolved reflectively by the module's class loader for self-hook configuration.
    public Context getContext() {
        return context;
    }

    public boolean isSupported() {
        // Preserve Pikmin's short-circuit order and allow callers to distinguish API failures.
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                && source.sdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
                && source.backgroundReadFeatureStatus(context)
                        == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE;
    }

    public boolean osSupportsHealthConnect() {
        return isSupported();
    }
}
