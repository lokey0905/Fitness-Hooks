package com.lokey0905.FitnessHooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import android.os.Process;
import android.os.UserManager;

import androidx.health.connect.client.HealthConnectClient;
import androidx.health.connect.client.HealthConnectFeatures;

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowUserManager;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class PikminHealthConnectProbeTest {
    private Context context;
    private RecordingSource source;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        source = new RecordingSource();
        ExtensionShadow.version = 0;
        ExtensionShadow.requestedSdk = -1;
        ExtensionShadow.calls = 0;
    }

    @Test
    @Config(sdk = {28, 33})
    public void olderAndroidShortCircuitsBeforeAnyHealthConnectCall() {
        source.statusFailure = new AssertionError("Provider must not be queried on older Android");
        source.featureFailure = new AssertionError("Client must not be created on older Android");
        PikminHealthConnectProbe probe = new PikminHealthConnectProbe(context, source);
        int originalSdk = Build.VERSION.SDK_INT;

        assertFalse(probe.isSupported());
        assertFalse(probe.osSupportsHealthConnect());

        assertTrue(source.calls.isEmpty());
        assertEquals(originalSdk, Build.VERSION.SDK_INT);
    }

    @Test
    @Config(sdk = {34, 35, 36})
    public void unavailableOrUnknownProviderNeverCreatesAClient() {
        source.featureFailure = new AssertionError("Unavailable provider must short circuit");
        PikminHealthConnectProbe probe = new PikminHealthConnectProbe(context, source);
        int[] statuses = {HealthConnectClient.SDK_UNAVAILABLE,
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED, 0, 99};

        for (int status : statuses) {
            source.sdkStatus = status;
            source.calls.clear();

            assertFalse(probe.isSupported());
            assertEquals(Arrays.asList("status"), source.calls);
            assertFalse(probe.osSupportsHealthConnect());
            assertEquals(Arrays.asList("status", "status"), source.calls);
        }
    }

    @Test
    @Config(sdk = {34, 35, 36})
    public void availableProviderStillRequiresAvailableBackgroundReadFeature() {
        PikminHealthConnectProbe probe = new PikminHealthConnectProbe(context, source);
        int[] featureStatuses = {HealthConnectFeatures.FEATURE_STATUS_UNAVAILABLE, 0, 99,
                HealthConnectFeatures.FEATURE_STATUS_AVAILABLE};

        for (int featureStatus : featureStatuses) {
            source.featureStatus = featureStatus;
            source.calls.clear();

            assertEquals(featureStatus == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE,
                    probe.isSupported());
            assertEquals(Arrays.asList("status", "feature"), source.calls);
        }
    }

    @Test
    public void managerGateFollowsTheClientGateWhenAvailabilityChanges() {
        PikminHealthConnectProbe probe = new PikminHealthConnectProbe(context, source);

        assertTrue(probe.osSupportsHealthConnect());
        assertEquals(Arrays.asList("status", "feature"), source.calls);

        source.featureStatus = HealthConnectFeatures.FEATURE_STATUS_UNAVAILABLE;
        source.calls.clear();
        assertFalse(probe.osSupportsHealthConnect());
        assertEquals(Arrays.asList("status", "feature"), source.calls);

        source.sdkStatus = HealthConnectClient.SDK_UNAVAILABLE;
        source.calls.clear();
        assertFalse(probe.osSupportsHealthConnect());
        assertEquals(Arrays.asList("status"), source.calls);
    }

    @Test
    public void providerRuntimeFailurePropagatesAndDoesNotQueryFeature() {
        IllegalStateException failure = new IllegalStateException("Provider changed during query");
        source.statusFailure = failure;
        PikminHealthConnectProbe probe = new PikminHealthConnectProbe(context, source);

        assertSame(failure, assertThrows(IllegalStateException.class, probe::isSupported));
        assertSame(failure, assertThrows(IllegalStateException.class, probe::osSupportsHealthConnect));
        assertEquals(Arrays.asList("status", "status"), source.calls);
    }

    @Test
    public void featureRuntimeFailurePropagatesInsteadOfBecomingSupported() {
        SecurityException failure = new SecurityException("Feature query unavailable");
        source.featureFailure = failure;
        PikminHealthConnectProbe probe = new PikminHealthConnectProbe(context, source);

        assertSame(failure, assertThrows(SecurityException.class, probe::isSupported));
        assertSame(failure, assertThrows(SecurityException.class, probe::osSupportsHealthConnect));
        assertEquals(Arrays.asList("status", "feature", "status", "feature"), source.calls);
    }

    @Test
    public void missingPlatformApiPropagatesFromEitherStage() {
        NoSuchMethodError failure = new NoSuchMethodError("Platform method missing");
        PikminHealthConnectProbe probe = new PikminHealthConnectProbe(context, source);
        source.statusFailure = failure;

        assertSame(failure, assertThrows(NoSuchMethodError.class, probe::isSupported));
        assertEquals(Arrays.asList("status"), source.calls);

        source.statusFailure = null;
        source.featureFailure = failure;
        source.calls.clear();
        assertSame(failure, assertThrows(NoSuchMethodError.class, probe::osSupportsHealthConnect));
        assertEquals(Arrays.asList("status", "feature"), source.calls);
    }

    @Test
    public void probeNormalizesContextAndDoesNotChangeSdkOrCheckPermissions() {
        Context permissionRejectingContext = new ContextWrapper(context) {
            @Override
            public Context getApplicationContext() {
                return this;
            }

            @Override
            public int checkSelfPermission(String permission) {
                throw new AssertionError("Support gate must not check permission grants");
            }

            @Override
            public int checkPermission(String permission, int pid, int uid) {
                throw new AssertionError("Support gate must not check permission grants");
            }

            @Override
            public int checkCallingOrSelfPermission(String permission) {
                throw new AssertionError("Support gate must not check permission grants");
            }
        };
        Context activityLikeContext = new ContextWrapper(permissionRejectingContext);
        PikminHealthConnectProbe probe = new PikminHealthConnectProbe(activityLikeContext, source);
        int originalSdk = Build.VERSION.SDK_INT;

        assertTrue(probe.isSupported());
        assertTrue(probe.osSupportsHealthConnect());

        assertSame(permissionRejectingContext, probe.getContext());
        assertSame(permissionRejectingContext, source.lastContext);
        assertEquals(originalSdk, Build.VERSION.SDK_INT);
    }

    @Test
    @Config(sdk = {34, 35, 36}, shadows = ExtensionShadow.class)
    public void androidXPublicApiUsesUExtension13OnEverySupportedAndroidVersion() throws Exception {
        context = withHealthConnectManager(context);
        assertEquals(HealthConnectClient.SDK_AVAILABLE,
                HealthConnectClient.getSdkStatus(context, HealthConnectSteps.PROVIDER_PACKAGE));
        PikminHealthConnectProbe probe = new PikminHealthConnectProbe(context);
        int originalSdk = Build.VERSION.SDK_INT;

        for (int extension : new int[] {0, 12, 13, 16}) {
            ExtensionShadow.version = extension;
            ExtensionShadow.requestedSdk = -1;
            ExtensionShadow.calls = 0;

            assertEquals(extension >= 13, probe.isSupported());
            assertEquals(extension >= 13, probe.osSupportsHealthConnect());
            assertEquals(34, ExtensionShadow.requestedSdk);
            assertTrue(ExtensionShadow.calls >= 2);
        }
        assertEquals(originalSdk, Build.VERSION.SDK_INT);
    }

    @Test
    @Config(sdk = 34, shadows = ExtensionShadow.class)
    public void androidXRejectsProfileBeforeLookingUpExtensionEvenWhenExtensionIsHigh() throws Exception {
        context = withHealthConnectManager(context);
        assertEquals(HealthConnectClient.SDK_AVAILABLE,
                HealthConnectClient.getSdkStatus(context, HealthConnectSteps.PROVIDER_PACKAGE));
        UserManager userManager = context.getSystemService(UserManager.class);
        ShadowUserManager shadowUserManager = Shadow.extract(userManager);
        shadowUserManager.addUser(Process.myUid() / 100000, "Test profile",
                ShadowUserManager.FLAG_PROFILE);
        shadowUserManager.setManagedProfile(false);
        ExtensionShadow.version = 16;
        PikminHealthConnectProbe probe = new PikminHealthConnectProbe(context);

        assertTrue(userManager.isProfile());
        assertFalse(probe.isSupported());
        assertFalse(probe.osSupportsHealthConnect());
        assertEquals(0, ExtensionShadow.calls);
    }

    private static Context withHealthConnectManager(Context base) throws Exception {
        // Robolectric has the framework manager class but no default Health Connect binder.
        // Supply a real manager in this context only; keep AndroidX status/client/feature code real.
        HealthConnectContext context = new HealthConnectContext(base);
        Class<?> serviceType = Class.forName("android.health.connect.aidl.IHealthConnectService");
        Object service = Proxy.newProxyInstance(serviceType.getClassLoader(),
                new Class<?>[] {serviceType}, (proxy, method, arguments) -> {
                    throw new AssertionError("Support gate must not call HC data/permission RPC: "
                            + method.getName());
                });
        Class<?> managerType = Class.forName("android.health.connect.HealthConnectManager");
        Constructor<?> constructor = managerType.getDeclaredConstructor(Context.class, serviceType);
        constructor.setAccessible(true);
        context.manager = constructor.newInstance(context, service);
        return context;
    }

    private static final class HealthConnectContext extends ContextWrapper {
        Object manager;

        HealthConnectContext(Context base) {
            super(base);
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public Object getSystemService(String name) {
            return "healthconnect".equals(name) ? manager : super.getSystemService(name);
        }
    }

    private static final class RecordingSource implements PikminHealthConnectProbe.Source {
        int sdkStatus = HealthConnectClient.SDK_AVAILABLE;
        int featureStatus = HealthConnectFeatures.FEATURE_STATUS_AVAILABLE;
        Throwable statusFailure;
        Throwable featureFailure;
        Context lastContext;
        final List<String> calls = new ArrayList<>();

        @Override
        public int sdkStatus(Context context) {
            calls.add("status");
            lastContext = context;
            rethrow(statusFailure);
            return sdkStatus;
        }

        @Override
        public int backgroundReadFeatureStatus(Context context) {
            calls.add("feature");
            lastContext = context;
            rethrow(featureFailure);
            return featureStatus;
        }

        private static void rethrow(Throwable failure) {
            if (failure instanceof RuntimeException) {
                throw (RuntimeException) failure;
            }
            if (failure instanceof Error) {
                throw (Error) failure;
            }
        }
    }

    @Implements(className = "android.os.ext.SdkExtensions", minSdk = 30)
    public static final class ExtensionShadow {
        static int version;
        static int requestedSdk;
        static int calls;

        @Implementation
        protected static int getExtensionVersion(int sdk) {
            requestedSdk = sdk;
            calls++;
            return version;
        }
    }
}
