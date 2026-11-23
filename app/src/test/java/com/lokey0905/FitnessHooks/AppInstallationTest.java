package com.lokey0905.FitnessHooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowApplicationPackageManager;
import org.robolectric.shadows.ShadowPackageManager;

import java.util.ArrayList;
import java.util.List;

/** PackageManager contract tests; does not assert real device/work-profile package visibility. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, shadows = AppInstallationTest.LookupPackageManagerShadow.class)
public final class AppInstallationTest {
    private static final String[] PACKAGES = {
            ConfigTransport.TARGET_PACKAGE, ConfigTransport.HC_PACKAGE, ConfigTransport.FIT_PACKAGE};
    private LookupContext context;
    private ShadowPackageManager packages;

    @Before public void setUp() {
        LookupPackageManagerShadow.failure = null;
        LookupPackageManagerShadow.names.clear();
        LookupPackageManagerShadow.flags.clear();
        context = new LookupContext(RuntimeEnvironment.getApplication());
        packages = Shadow.extract(context.getPackageManager());
        context.managerCalls = 0;
        LookupPackageManagerShadow.names.clear();
        LookupPackageManagerShadow.flags.clear();
    }

    @After public void clearFailure() {
        LookupPackageManagerShadow.failure = null;
    }

    @Test public void declaredAppsAreDetectedUsingOnlyTheCurrentContextsPackageManagerAndZeroFlags() {
        for (String packageName : PACKAGES) install(packageName, true);
        LookupPackageManagerShadow.names.clear();
        LookupPackageManagerShadow.flags.clear();
        for (String packageName : PACKAGES) {
            assertEquals(AppInstallation.State.INSTALLED, AppInstallation.check(context, packageName));
        }
        assertEquals(List.of(PACKAGES), LookupPackageManagerShadow.names);
        assertEquals(List.of(0, 0, 0), LookupPackageManagerShadow.flags);
        assertEquals(3, context.managerCalls);
    }

    @Test public void missingDeclaredAppsAreNotInstalled() {
        for (String packageName : PACKAGES) {
            assertEquals(AppInstallation.State.NOT_INSTALLED, AppInstallation.check(context, packageName));
        }
    }

    @Test public void disabledAppsStillCountAsInstalled() {
        install(ConfigTransport.HC_PACKAGE, false);
        assertEquals(AppInstallation.State.INSTALLED,
                AppInstallation.check(context, ConfigTransport.HC_PACKAGE));
    }

    @Test public void lookupErrorsAndUnavailablePackageManagerStayUnknown() {
        for (RuntimeException failure : new RuntimeException[]{
                new SecurityException("visibility denied"), new IllegalStateException("service unavailable")}) {
            LookupPackageManagerShadow.failure = failure;
            assertEquals(AppInstallation.State.UNKNOWN,
                    AppInstallation.check(context, ConfigTransport.FIT_PACKAGE));
        }
        LookupPackageManagerShadow.failure = null;
        context.nullManager = true;
        assertEquals(AppInstallation.State.UNKNOWN,
                AppInstallation.check(context, ConfigTransport.FIT_PACKAGE));
        context.nullManager = false;
        context.failManager = true;
        assertEquals(AppInstallation.State.UNKNOWN,
                AppInstallation.check(context, ConfigTransport.FIT_PACKAGE));
        assertEquals(AppInstallation.State.UNKNOWN,
                AppInstallation.check(null, ConfigTransport.FIT_PACKAGE));
    }

    @Test public void undeclaredOrNullPackageNamesAreNotQueriedOrCalledUninstalled() {
        for (String packageName : new String[]{"example.not.visible", ConfigTransport.MODULE_PACKAGE, null}) {
            assertEquals(AppInstallation.State.UNKNOWN, AppInstallation.check(context, packageName));
        }
        assertEquals(0, context.managerCalls);
        assertTrue(LookupPackageManagerShadow.names.isEmpty());
    }

    @Test public void repeatedChecksReadCurrentInstallationInsteadOfRememberingAnEarlierResult() {
        assertEquals(AppInstallation.State.NOT_INSTALLED,
                AppInstallation.check(context, ConfigTransport.FIT_PACKAGE));
        install(ConfigTransport.FIT_PACKAGE, true);
        assertEquals(AppInstallation.State.INSTALLED,
                AppInstallation.check(context, ConfigTransport.FIT_PACKAGE));
        packages.removePackage(ConfigTransport.FIT_PACKAGE);
        assertEquals(AppInstallation.State.NOT_INSTALLED,
                AppInstallation.check(context, ConfigTransport.FIT_PACKAGE));
    }

    @Test
    @Config(sdk = {34, 36})
    public void systemProviderCountsAsInstalledWithoutTheStandaloneApkOrHealthPermissionChecks() {
        // An opaque service fixture has no permission/feature methods: existence alone is sufficient.
        context.systemHealthConnect = new Object();
        assertEquals(AppInstallation.State.INSTALLED,
                AppInstallation.check(context, ConfigTransport.HC_PACKAGE));
        assertEquals(1, context.systemHealthCalls);
        assertEquals(List.of(ConfigTransport.HC_PACKAGE), LookupPackageManagerShadow.names);
        assertEquals(List.of(0), LookupPackageManagerShadow.flags);
    }

    @Test public void android14AloneDoesNotCountAsInstalledWhenBothProvidersAreAbsent() {
        assertEquals(AppInstallation.State.NOT_INSTALLED,
                AppInstallation.check(context, ConfigTransport.HC_PACKAGE));
        assertEquals(1, context.systemHealthCalls);
    }

    @Test public void systemLookupErrorsStayUnknownButCannotHideAnInstalledStandaloneApp() {
        context.systemFailure = new SecurityException("service unavailable in this context");
        assertEquals(AppInstallation.State.UNKNOWN,
                AppInstallation.check(context, ConfigTransport.HC_PACKAGE));
        context.systemFailure = new IllegalStateException("service lookup failed");
        assertEquals(AppInstallation.State.UNKNOWN,
                AppInstallation.check(context, ConfigTransport.HC_PACKAGE));
        install(ConfigTransport.HC_PACKAGE, false);
        assertEquals(AppInstallation.State.INSTALLED,
                AppInstallation.check(context, ConfigTransport.HC_PACKAGE));
        assertEquals(2, context.systemHealthCalls);
    }

    @Test public void realSystemPresenceCanResolveAnUnknownApkLookupButMissingServiceCannot() {
        LookupPackageManagerShadow.failure = new SecurityException("package lookup unavailable");
        context.systemHealthConnect = new Object();
        assertEquals(AppInstallation.State.INSTALLED,
                AppInstallation.check(context, ConfigTransport.HC_PACKAGE));
        context.systemHealthConnect = null;
        assertEquals(AppInstallation.State.UNKNOWN,
                AppInstallation.check(context, ConfigTransport.HC_PACKAGE));
    }

    @Test
    @Config(sdk = {28, 33})
    public void olderAndroidOnlyChecksTheStandalonePackageAndNeverTouchesTheSystemService() {
        context.systemHealthConnect = new Object();
        assertEquals(AppInstallation.State.NOT_INSTALLED,
                AppInstallation.check(context, ConfigTransport.HC_PACKAGE));
        install(ConfigTransport.HC_PACKAGE, true);
        assertEquals(AppInstallation.State.INSTALLED,
                AppInstallation.check(context, ConfigTransport.HC_PACKAGE));
        assertEquals(0, context.systemHealthCalls);
    }

    @Test public void nonHcPackageChecksNeverUseTheSystemHealthService() {
        context.systemHealthConnect = new Object();
        assertEquals(AppInstallation.State.NOT_INSTALLED,
                AppInstallation.check(context, ConfigTransport.FIT_PACKAGE));
        assertEquals(AppInstallation.State.NOT_INSTALLED,
                AppInstallation.check(context, ConfigTransport.TARGET_PACKAGE));
        assertEquals(0, context.systemHealthCalls);
    }

    private void install(String packageName, boolean enabled) {
        ApplicationInfo application = new ApplicationInfo();
        application.packageName = packageName;
        application.enabled = enabled;
        PackageInfo info = new PackageInfo();
        info.packageName = packageName;
        info.applicationInfo = application;
        packages.installPackage(info);
    }

    private static final class LookupContext extends ContextWrapper {
        int managerCalls;
        boolean nullManager;
        boolean failManager;
        Object systemHealthConnect;
        RuntimeException systemFailure;
        int systemHealthCalls;

        LookupContext(Context base) { super(base); }

        @Override public PackageManager getPackageManager() {
            managerCalls++;
            if (failManager) throw new SecurityException("package service unavailable");
            return nullManager ? null : super.getPackageManager();
        }

        @Override public Object getSystemService(String name) {
            if (Context.HEALTHCONNECT_SERVICE.equals(name)) {
                systemHealthCalls++;
                if (systemFailure != null) throw systemFailure;
                return systemHealthConnect;
            }
            return super.getSystemService(name);
        }

        @Override public void startActivity(Intent intent) {
            throw new AssertionError("Installation checks must not start apps");
        }

        @Override public void sendBroadcast(Intent intent) {
            throw new AssertionError("Installation checks must not probe hooks with broadcasts");
        }
    }

    @Implements(className = "android.app.ApplicationPackageManager")
    public static final class LookupPackageManagerShadow extends ShadowApplicationPackageManager {
        static final List<String> names = new ArrayList<>();
        static final List<Integer> flags = new ArrayList<>();
        static RuntimeException failure;

        @Implementation
        protected ApplicationInfo getApplicationInfo(String packageName, int queryFlags)
                throws PackageManager.NameNotFoundException {
            names.add(packageName);
            flags.add(queryFlags);
            if (failure != null) throw failure;
            return super.getApplicationInfo(packageName, queryFlags);
        }
    }
}
