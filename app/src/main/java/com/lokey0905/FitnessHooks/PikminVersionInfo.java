package com.lokey0905.FitnessHooks;

import android.os.Build;
import android.os.ext.SdkExtensions;

import androidx.annotation.RequiresApi;

/** The version checks used by Pikmin Bloom 153.0 and 156.0, without HC or permission checks. */
final class PikminVersionInfo {
    static final int MIN_SDK = 34;
    static final int MIN_U_EXTENSION = 13;

    enum Status {
        SDK_TOO_OLD,
        EXTENSION_TOO_OLD,
        READY,
        UNKNOWN
    }

    final int sdkInt;
    final String androidRelease;
    final String manufacturer;
    final String model;
    final Integer uExtensionVersion;

    PikminVersionInfo(int sdkInt, String androidRelease, String manufacturer, String model,
            Integer uExtensionVersion) {
        this.sdkInt = sdkInt;
        this.androidRelease = androidRelease;
        this.manufacturer = manufacturer;
        this.model = model;
        this.uExtensionVersion = sdkInt >= MIN_SDK && uExtensionVersion != null
                && uExtensionVersion >= 0 ? uExtensionVersion : null;
    }

    static PikminVersionInfo read() {
        Integer extension = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                extension = Api34Impl.readUExtensionVersion();
            } catch (RuntimeException | LinkageError ignored) {
                // Unknown is distinct from an actual extension value below the required version.
            }
        }
        return new PikminVersionInfo(Build.VERSION.SDK_INT, Build.VERSION.RELEASE,
                Build.MANUFACTURER, Build.MODEL, extension);
    }

    boolean hasRequiredSdk() {
        return sdkInt >= MIN_SDK;
    }

    Status status() {
        if (!hasRequiredSdk()) {
            return Status.SDK_TOO_OLD;
        }
        if (uExtensionVersion == null) {
            return Status.UNKNOWN;
        }
        return uExtensionVersion >= MIN_U_EXTENSION ? Status.READY : Status.EXTENSION_TOO_OLD;
    }

    @RequiresApi(34)
    private static final class Api34Impl {
        static int readUExtensionVersion() {
            // Pikmin checks U (34), including on Android 15 and later.
            return SdkExtensions.getExtensionVersion(Build.VERSION_CODES.UPSIDE_DOWN_CAKE);
        }
    }
}
