package com.lokey0905.FitnessHooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowBinder;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowProcess;

/** Provider authorization and switch policy; does not simulate Binder or a real HC process. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class StandaloneConfigProviderTest {
    private static final int MODULE_UID = 11001;
    private static final int HC_UID = 11002;
    private static final int OTHER_UID = 11003;
    private static final int FIT_UID = 11004;
    private static final int USER_10_OFFSET = 1000000;

    private SharedPreferences preferences;
    private ShadowPackageManager packages;
    private StandaloneConfigProvider provider;

    @Before
    public void setUp() {
        ShadowProcess.setUid(MODULE_UID);
        ShadowBinder.setCallingUid(HC_UID);
        Context context = RuntimeEnvironment.getApplication();
        preferences = context.getSharedPreferences(HookConfig.PREFS_NAME, Context.MODE_PRIVATE);
        assertTrue(preferences.edit().clear().commit());
        packages = Shadow.extract(context.getPackageManager());
        packages.setPackagesForUid(HC_UID, ConfigTransport.HC_PACKAGE);
        packages.setPackagesForUid(FIT_UID, ConfigTransport.FIT_PACKAGE);
        provider = Robolectric.setupContentProvider(
                StandaloneConfigProvider.class, StandaloneConfigProvider.AUTHORITY);
    }

    @After
    public void resetCallerIdentity() {
        ShadowBinder.reset();
        ShadowProcess.reset();
    }

    @Test
    public void sameUserHealthConnectAndFitReceiveOnlyOneEffectiveBoolean() {
        setChoice(true, true);
        assertTrue(preferences.edit()
                .putBoolean(HookConfig.KEY_SDK_HOOK_ENABLED, true)
                .putBoolean(HookConfig.KEY_STEP_HOOK_ENABLED, true)
                .putInt(HookConfig.KEY_STEP_COUNT, 45678)
                .putBoolean(HookConfig.KEY_DISABLE_DYNAMIC_COLORS, true)
                .commit());

        for (int uid : new int[]{HC_UID, FIT_UID}) {
            ShadowBinder.setCallingUid(uid);
            Bundle result = readRoute();
            assertEquals(1, result.size());
            assertTrue(result.containsKey(HookConfig.KEY_STANDALONE_HC_ENABLED));
            assertEquals(Boolean.TRUE, result.get(HookConfig.KEY_STANDALONE_HC_ENABLED));
        }
    }

    @Test
    public void missingPreferencesDefaultToDisabled() {
        assertFalse(readRoute().getBoolean(HookConfig.KEY_STANDALONE_HC_ENABLED));
    }

    @Test
    public void masterAndSavedChoiceAreBothRequired() {
        for (boolean master : new boolean[] {false, true}) {
            for (boolean choice : new boolean[] {false, true}) {
                setChoice(master, choice);
                assertEquals("master=" + master + ", choice=" + choice,
                        master && choice,
                        readRoute().getBoolean(HookConfig.KEY_STANDALONE_HC_ENABLED));
            }
        }
    }

    @Test
    @Config(sdk = {28, 33})
    public void android13AndOlderCannotEnableStandaloneRouting() {
        setChoice(true, true);
        assertFalse(readRoute().getBoolean(HookConfig.KEY_STANDALONE_HC_ENABLED));
        assertTrue(preferences.getBoolean(HookConfig.KEY_STANDALONE_HC_ENABLED, false));
    }

    @Test
    @Config(sdk = {34, 36})
    public void android14AndNewerReturnTheChoiceWithoutAnInstalledHcProvider() {
        // setPackagesForUid supplies caller identity only, not an installed HC service/client.
        setChoice(true, true);
        assertTrue(readRoute().getBoolean(HookConfig.KEY_STANDALONE_HC_ENABLED));
    }

    @Test
    public void otherPackageCannotReadEvenWhenClaimingToBeHealthConnectOrFit() {
        packages.setPackagesForUid(OTHER_UID, ConfigTransport.TARGET_PACKAGE);
        ShadowBinder.setCallingUid(OTHER_UID);
        for (String packageName : new String[]{ConfigTransport.HC_PACKAGE, ConfigTransport.FIT_PACKAGE}) {
            Bundle forgedExtras = new Bundle();
            forgedExtras.putString("packageName", packageName);
            forgedExtras.putBoolean(HookConfig.KEY_STANDALONE_HC_ENABLED, true);
            assertThrows(SecurityException.class, () -> provider.call(
                    StandaloneConfigProvider.METHOD_GET_ROUTE, packageName, forgedExtras));
        }
    }

    @Test
    public void callerWithoutPackageMappingCannotRead() {
        ShadowBinder.setCallingUid(OTHER_UID);
        assertThrows(SecurityException.class, this::readRoute);
    }

    @Test
    public void healthConnectAndFitInAnotherAndroidUserCannotRead() {
        packages.setPackagesForUid(USER_10_OFFSET + HC_UID, ConfigTransport.HC_PACKAGE);
        packages.setPackagesForUid(USER_10_OFFSET + FIT_UID, ConfigTransport.FIT_PACKAGE);
        for (int uid : new int[]{HC_UID, FIT_UID}) {
            ShadowBinder.setCallingUid(USER_10_OFFSET + uid);
            assertThrows(SecurityException.class, this::readRoute);
        }
    }

    @Test
    public void matchingUser10IsAllowedButOwnerUserCannotReadItsConfiguration() {
        ShadowProcess.setUid(USER_10_OFFSET + MODULE_UID);
        packages.setPackagesForUid(USER_10_OFFSET + HC_UID, ConfigTransport.HC_PACKAGE);
        packages.setPackagesForUid(USER_10_OFFSET + FIT_UID, ConfigTransport.FIT_PACKAGE);
        setChoice(true, true);
        for (int uid : new int[]{HC_UID, FIT_UID}) {
            ShadowBinder.setCallingUid(USER_10_OFFSET + uid);
            assertTrue(readRoute().getBoolean(HookConfig.KEY_STANDALONE_HC_ENABLED));
            ShadowBinder.setCallingUid(uid);
            assertThrows(SecurityException.class, this::readRoute);
        }
    }

    @Test
    public void argumentsCannotChangeTheSavedChoiceAndUnknownMethodsAreRejected() {
        setChoice(false, false);
        Bundle extras = new Bundle();
        extras.putBoolean(HookConfig.KEY_EXPERIMENTAL_FEATURES_ENABLED, true);
        extras.putBoolean(HookConfig.KEY_STANDALONE_HC_ENABLED, true);

        Bundle result = provider.call(StandaloneConfigProvider.METHOD_GET_ROUTE, "true", extras);
        assertFalse(result.getBoolean(HookConfig.KEY_STANDALONE_HC_ENABLED));
        assertThrows(IllegalArgumentException.class,
                () -> provider.call("set_standalone_route", null, extras));
        assertFalse(preferences.getBoolean(HookConfig.KEY_EXPERIMENTAL_FEATURES_ENABLED, true));
        assertFalse(preferences.getBoolean(HookConfig.KEY_STANDALONE_HC_ENABLED, true));
    }

    @Test
    public void queryAndAllWriteEntrypointsAreUnsupported() {
        ContentValues values = new ContentValues();
        values.put(HookConfig.KEY_STANDALONE_HC_ENABLED, true);
        assertThrows(UnsupportedOperationException.class,
                () -> provider.query(StandaloneConfigProvider.URI, null, null, null, null));
        assertThrows(UnsupportedOperationException.class,
                () -> provider.insert(StandaloneConfigProvider.URI, values));
        assertThrows(UnsupportedOperationException.class,
                () -> provider.update(StandaloneConfigProvider.URI, values, null, null));
        assertThrows(UnsupportedOperationException.class,
                () -> provider.delete(StandaloneConfigProvider.URI, null, null));
        assertThrows(UnsupportedOperationException.class,
                () -> provider.bulkInsert(StandaloneConfigProvider.URI, new ContentValues[] {values}));
        assertNull(provider.getType(StandaloneConfigProvider.URI));
        assertTrue(preferences.getAll().isEmpty());
    }

    private Bundle readRoute() {
        return provider.call(StandaloneConfigProvider.METHOD_GET_ROUTE, null, null);
    }

    private void setChoice(boolean master, boolean choice) {
        assertTrue(preferences.edit()
                .putBoolean(HookConfig.KEY_EXPERIMENTAL_FEATURES_ENABLED, master)
                .putBoolean(HookConfig.KEY_STANDALONE_HC_ENABLED, choice)
                .commit());
    }
}
