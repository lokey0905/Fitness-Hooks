package com.lokey0905.FitnessHooks;

import android.content.Context;
import android.os.Build;

/** One provider per Fit process: its DI caches and sync state must use the same route. */
final class GoogleFitRoute {
    private static boolean hooksReady;
    private static Boolean route;
    private static boolean restartLogged;

    private GoogleFitRoute() {}

    static synchronized void markHooksReady() {
        hooksReady = true;
    }

    static synchronized boolean areHooksReady() {
        return hooksReady;
    }

    static synchronized boolean selected() {
        if (!hooksReady || Build.VERSION.SDK_INT < 34) return false;
        Context context = TargetConfigBridge.getApplicationContext();
        if (context == null || !ConfigTransport.FIT_PACKAGE.equals(context.getPackageName())) return false;
        Boolean current = TargetConfigBridge.getStandaloneRouteSelection();
        if (route == null) {
            // Pending is not an explicit choice of the system provider. Block this HC
            // operation until a verified value arrives, without freezing a false route.
            if (current == null) {
                throw new IllegalStateException("Google Fit HC route is awaiting verified configuration");
            }
            route = current;
            FitnessHooksEntry.log("Google Fit HC route fixed for this process: "
                    + (route ? "standalone APK" : "system"));
        }
        if (current != null && !route.equals(current) && !restartLogged) {
            restartLogged = true;
            FitnessHooksEntry.log("Google Fit HC choice changed; restart Fit to switch its client and sync state");
        }
        return route;
    }
}
