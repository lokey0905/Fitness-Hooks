package com.lokey0905.FitnessHooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.Build;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, shadows = PikminVersionInfoTest.ExtensionShadow.class)
public final class PikminVersionInfoTest {
    @Before
    public void resetExtension() {
        ExtensionShadow.version = 12;
        ExtensionShadow.calls = 0;
        ExtensionShadow.requestedSdk = -1;
        ExtensionShadow.failure = null;
    }

    @Test
    public void android14WithExtension12DoesNotMeetVersionRequirements() {
        PikminVersionInfo info = PikminVersionInfo.read();

        assertEquals(34, info.sdkInt);
        assertEquals(Build.VERSION.RELEASE, info.androidRelease);
        assertEquals(Build.MANUFACTURER, info.manufacturer);
        assertEquals(Build.MODEL, info.model);
        assertEquals(Integer.valueOf(12), info.uExtensionVersion);
        assertTrue(info.hasRequiredSdk());
        assertEquals(PikminVersionInfo.Status.EXTENSION_TOO_OLD, info.status());
    }

    @Test
    public void extension13MeetsTheBoundary() {
        ExtensionShadow.version = 13;

        assertEquals(PikminVersionInfo.Status.READY, PikminVersionInfo.read().status());
    }

    @Test
    public void reportedExtension16AlsoMeetsTheBoundary() {
        ExtensionShadow.version = 16;

        assertEquals(PikminVersionInfo.Status.READY, PikminVersionInfo.read().status());
    }

    @Test
    public void zeroIsAnActualUnsupportedValueRatherThanAReadFailure() {
        ExtensionShadow.version = 0;

        PikminVersionInfo info = PikminVersionInfo.read();
        assertEquals(Integer.valueOf(0), info.uExtensionVersion);
        assertEquals(PikminVersionInfo.Status.EXTENSION_TOO_OLD, info.status());
    }

    @Test
    @Config(sdk = {28, 33})
    public void olderAndroidNeverCallsTheExtensionApi() {
        ExtensionShadow.failure = new IllegalStateException("Must not be called");

        PikminVersionInfo info = PikminVersionInfo.read();
        assertFalse(info.hasRequiredSdk());
        assertNull(info.uExtensionVersion);
        assertEquals(PikminVersionInfo.Status.SDK_TOO_OLD, info.status());
        assertEquals(0, ExtensionShadow.calls);
    }

    @Test
    @Config(sdk = {35, 36})
    public void newerAndroidStillQueriesUAndDoesNotAssumeSupport() {
        PikminVersionInfo info = PikminVersionInfo.read();

        assertEquals(34, ExtensionShadow.requestedSdk);
        assertEquals(1, ExtensionShadow.calls);
        assertEquals(PikminVersionInfo.Status.EXTENSION_TOO_OLD, info.status());
    }

    @Test
    public void failedReadIsUnknownInsteadOfZeroOrReady() {
        ExtensionShadow.failure = new IllegalStateException("Unavailable");

        PikminVersionInfo info = PikminVersionInfo.read();
        assertNull(info.uExtensionVersion);
        assertEquals(PikminVersionInfo.Status.UNKNOWN, info.status());
    }

    @Test
    public void missingPlatformMethodIsUnknown() {
        ExtensionShadow.failure = new NoSuchMethodError("Unavailable");

        PikminVersionInfo info = PikminVersionInfo.read();
        assertNull(info.uExtensionVersion);
        assertEquals(PikminVersionInfo.Status.UNKNOWN, info.status());
    }

    @Test
    public void negativePlatformValueIsUnknown() {
        ExtensionShadow.version = -1;

        PikminVersionInfo info = PikminVersionInfo.read();
        assertNull(info.uExtensionVersion);
        assertEquals(PikminVersionInfo.Status.UNKNOWN, info.status());
    }

    @Implements(className = "android.os.ext.SdkExtensions", minSdk = 30)
    public static final class ExtensionShadow {
        static int version;
        static int calls;
        static int requestedSdk;
        static Throwable failure;

        @Implementation
        protected static int getExtensionVersion(int sdk) {
            calls++;
            requestedSdk = sdk;
            if (failure instanceof RuntimeException) {
                throw (RuntimeException) failure;
            }
            if (failure instanceof LinkageError) {
                throw (LinkageError) failure;
            }
            return version;
        }
    }
}
