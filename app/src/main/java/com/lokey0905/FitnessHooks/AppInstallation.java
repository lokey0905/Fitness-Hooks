package com.lokey0905.FitnessHooks;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

/** Installation visibility in the current Android user; says nothing about hooks or permissions. */
final class AppInstallation {
    enum State { INSTALLED, NOT_INSTALLED, UNKNOWN }

    static State check(Context context, String packageName) {
        // These three packages are explicitly declared in the manifest's queries section.
        // An undeclared package could be invisible, so absence would not prove uninstall.
        if (!ConfigTransport.TARGET_PACKAGE.equals(packageName)
                && !ConfigTransport.HC_PACKAGE.equals(packageName)
                && !ConfigTransport.FIT_PACKAGE.equals(packageName)) return State.UNKNOWN;
        State app = checkPackage(context, packageName);
        if (!ConfigTransport.HC_PACKAGE.equals(packageName) || Build.VERSION.SDK_INT < 34
                || app == State.INSTALLED) return app;
        State system = checkSystemHealthConnect(context);
        if (system == State.INSTALLED) return State.INSTALLED;
        return app == State.UNKNOWN || system == State.UNKNOWN ? State.UNKNOWN : State.NOT_INSTALLED;
    }

    private static State checkPackage(Context context, String packageName) {
        try {
            PackageManager packages = context == null ? null : context.getPackageManager();
            if (packages == null) return State.UNKNOWN;
            // Flags 0 intentionally excludes other users and retained data from uninstalled apps.
            // Disabled apps still count as installed.
            return packages.getApplicationInfo(packageName, 0) == null
                    ? State.UNKNOWN : State.INSTALLED;
        } catch (PackageManager.NameNotFoundException missing) {
            return State.NOT_INSTALLED;
        } catch (RuntimeException unavailable) {
            return State.UNKNOWN;
        }
    }

    private static State checkSystemHealthConnect(Context context) {
        if (Build.VERSION.SDK_INT < 34 || context == null) return State.UNKNOWN;
        try {
            // This checks the actual framework provider, not just the OS version.
            // Do not request records/permissions or use feature/profile availability as installation evidence.
            // The string API also avoids referencing an API 34 class on older Android versions.
            return context.getSystemService(Context.HEALTHCONNECT_SERVICE) != null
                    ? State.INSTALLED : State.NOT_INSTALLED;
        } catch (RuntimeException unavailable) {
            return State.UNKNOWN;
        }
    }

    private AppInstallation() {}
}
