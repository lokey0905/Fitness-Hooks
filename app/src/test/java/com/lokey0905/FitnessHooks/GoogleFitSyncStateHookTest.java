package com.lokey0905.FitnessHooks;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, instrumentedPackages = "de.robv.android.xposed",
        shadows = {GoogleFitSyncStateHookTest.BridgeShadow.class,
                GoogleFitSyncStateHookTest.HelpersShadow.class})
public final class GoogleFitSyncStateHookTest {
    @Before
    public void reset() {
        BridgeShadow.hooks.clear();
        BridgeShadow.failAt = 0;
        BridgeShadow.attempts = 0;
        HelpersShadow.missingDatabaseBuilder = false;
        ((Set<?>) ReflectionHelpers.getStaticField(GoogleFitSyncStateHook.class, "INSTALLED")).clear();
        ReflectionHelpers.setStaticField(GoogleFitRoute.class, "route", null);
        ReflectionHelpers.setStaticField(GoogleFitRoute.class, "hooksReady", false);
        ReflectionHelpers.setStaticField(GoogleFitRoute.class, "restartLogged", false);
        select(true);
        Context context = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public String getPackageName() { return ConfigTransport.FIT_PACKAGE; }
        };
        ReflectionHelpers.setStaticField(TargetConfigBridge.class, "applicationContext", context);
    }

    @Test
    public void allFiveHcStoresAndBothDatabasesUseSeparateNamesWithoutDoubleSuffix() throws Throwable {
        assertEquals(2, install());
        GoogleFitRoute.markHooksReady();
        for (String name : new String[]{"AhpConsent", "AhpDownsyncState", "AhpDownsyncDebugInfo",
                "AhpUpsyncState", "AhpUpsyncDebugInfo"}) {
            StoreBuilder builder = new StoreBuilder();
            builder.a = name;
            invoke(builder);
            assertEquals(name + "_fitnesshooks_hc_apk", builder.a);
            invoke(builder);
            assertEquals(name + "_fitnesshooks_hc_apk", builder.a);
        }
        for (String name : new String[]{"ahp_downsync_interval_queue.db", "ahp_mapping.db"}) {
            DatabaseBuilder builder = new DatabaseBuilder();
            builder.a = name;
            invoke(builder);
            assertEquals(name.substring(0, name.length() - 3) + "_fitnesshooks_hc_apk.db", builder.a);
            String isolated = builder.a;
            invoke(builder);
            assertEquals(isolated, builder.a);
        }
    }

    @Test
    public void unrelatedNamesAndNullDoNotChangeOrFreezeTheProviderRoute() throws Throwable {
        assertEquals(2, install());
        GoogleFitRoute.markHooksReady();
        select(null);
        for (String name : new String[]{null, "AccountDisplayInfoStore", "AhpConsentBackup",
                "AhpUpsyncState_fitnesshooks_hc_apk"}) {
            StoreBuilder builder = new StoreBuilder();
            builder.a = name;
            assertFalse(invoke(builder).resultSet);
            assertEquals(name, builder.a);
        }
        DatabaseBuilder database = new DatabaseBuilder();
        database.a = "OtherFit.db";
        invoke(database);
        assertEquals("OtherFit.db", database.a);
        assertNull(ReflectionHelpers.getStaticField(GoogleFitRoute.class, "route"));
    }

    @Test
    public void mainClientHooksMustAlsoBeReadyBeforeAnyStateIsIsolated() throws Throwable {
        assertEquals(2, install());
        StoreBuilder builder = new StoreBuilder();
        builder.a = "AhpConsent";
        invoke(builder);
        assertEquals("AhpConsent", builder.a);
        assertNull(ReflectionHelpers.getStaticField(GoogleFitRoute.class, "route"));
        GoogleFitRoute.markHooksReady();
        invoke(builder);
        assertEquals("AhpConsent_fitnesshooks_hc_apk", builder.a);
    }

    @Test
    public void missingOrPartiallyInstalledStoreHooksCannotRenameAnyState() throws Throwable {
        HelpersShadow.missingDatabaseBuilder = true;
        assertEquals(0, install());
        assertTrue(BridgeShadow.hooks.isEmpty());
        HelpersShadow.missingDatabaseBuilder = false;
        BridgeShadow.failAt = 2;
        assertEquals(0, install());
        GoogleFitRoute.markHooksReady();
        StoreBuilder builder = new StoreBuilder();
        builder.a = "AhpConsent";
        invoke(builder);
        assertEquals("AhpConsent", builder.a);
    }

    @Test
    public void routeAndNamespaceStayTogetherUntilFitRestarts() throws Throwable {
        assertEquals(2, install());
        GoogleFitRoute.markHooksReady();
        assertTrue(GoogleFitRoute.selected());
        select(false);
        StoreBuilder builder = new StoreBuilder();
        builder.a = "AhpUpsyncState";
        invoke(builder);
        assertEquals("AhpUpsyncState_fitnesshooks_hc_apk", builder.a);
        assertTrue(GoogleFitRoute.selected());

        // A fresh process selects the system route and its original stored state.
        ReflectionHelpers.setStaticField(GoogleFitRoute.class, "route", null);
        assertFalse(GoogleFitRoute.selected());
        select(true);
        builder.a = "AhpUpsyncState";
        invoke(builder);
        assertEquals("AhpUpsyncState", builder.a);
        assertFalse(GoogleFitRoute.selected());
    }

    @Test
    public void pendingConfigurationDoesNotPinSystemAndAcceptsLateApkChoice() {
        GoogleFitRoute.markHooksReady();
        select(null);
        assertThrows(IllegalStateException.class, GoogleFitRoute::selected);
        assertNull(ReflectionHelpers.getStaticField(GoogleFitRoute.class, "route"));
        select(true);
        assertTrue(GoogleFitRoute.selected());
    }

    @Test
    public void pendingConfigurationAcceptsLateExplicitSystemChoiceWithoutHotSwitching() {
        GoogleFitRoute.markHooksReady();
        select(null);
        assertThrows(IllegalStateException.class, GoogleFitRoute::selected);
        assertNull(ReflectionHelpers.getStaticField(GoogleFitRoute.class, "route"));
        select(false);
        assertFalse(GoogleFitRoute.selected());
        select(true);
        assertFalse(GoogleFitRoute.selected());
        assertEquals(Boolean.TRUE, ReflectionHelpers.getStaticField(GoogleFitRoute.class, "restartLogged"));
    }

    @Test
    public void pendingStoresAndDatabasesAreBlockedUntilTheirRouteIsVerified() throws Throwable {
        assertEquals(2, install());
        GoogleFitRoute.markHooksReady();
        select(null);
        StoreBuilder store = new StoreBuilder();
        store.a = "AhpConsent";
        DatabaseBuilder database = new DatabaseBuilder();
        database.a = "ahp_mapping.db";
        for (Object builder : new Object[]{store, database}) {
            RecordingParam pending = invoke(builder);
            assertTrue(pending.resultSet);
            assertTrue(pending.failure instanceof IllegalStateException);
        }
        assertEquals("AhpConsent", store.a);
        assertEquals("ahp_mapping.db", database.a);
        assertNull(ReflectionHelpers.getStaticField(GoogleFitRoute.class, "route"));

        select(true);
        assertFalse(invoke(store).resultSet);
        assertFalse(invoke(database).resultSet);
        assertEquals("AhpConsent_fitnesshooks_hc_apk", store.a);
        assertEquals("ahp_mapping_fitnesshooks_hc_apk.db", database.a);
    }

    @Test
    public void pinnedRouteSurvivesAConfigurationReadBecomingUnavailable() {
        GoogleFitRoute.markHooksReady();
        for (boolean selected : new boolean[]{false, true}) {
            ReflectionHelpers.setStaticField(GoogleFitRoute.class, "route", null);
            select(selected);
            assertEquals(selected, GoogleFitRoute.selected());
            select(null);
            assertEquals(selected, GoogleFitRoute.selected());
            assertEquals(Boolean.FALSE, ReflectionHelpers.getStaticField(GoogleFitRoute.class, "restartLogged"));
        }
    }

    @Test
    public void absentOrUnrelatedContextCannotSelectApkRoute() {
        GoogleFitRoute.markHooksReady();
        ReflectionHelpers.setStaticField(TargetConfigBridge.class, "applicationContext", null);
        assertFalse(GoogleFitRoute.selected());
        ReflectionHelpers.setStaticField(TargetConfigBridge.class, "applicationContext",
                RuntimeEnvironment.getApplication());
        assertFalse(GoogleFitRoute.selected());
        assertNull(ReflectionHelpers.getStaticField(GoogleFitRoute.class, "route"));
    }

    @Test
    @Config(sdk = 33)
    public void olderAndroidKeepsItsOriginalFitStorageAndRouting() {
        GoogleFitRoute.markHooksReady();
        assertEquals(0, install());
        assertTrue(BridgeShadow.hooks.isEmpty());
        assertFalse(GoogleFitRoute.selected());
    }

    private void select(Boolean selected) {
        ReflectionHelpers.setStaticField(TargetConfigBridge.class, "standaloneRouteSelection", selected);
    }

    private int install() { return GoogleFitSyncStateHook.install(getClass().getClassLoader()); }

    private RecordingParam invoke(Object builder) throws Throwable {
        Method target = builder.getClass().getDeclaredMethod("a");
        XC_MethodHook callback = BridgeShadow.hooks.get(target);
        assertNotNull(callback);
        RecordingParam param = new RecordingParam();
        param.method = target;
        param.thisObject = builder;
        param.args = new Object[0];
        Method before = callback.getClass().getDeclaredMethod("beforeHookedMethod", XC_MethodHook.MethodHookParam.class);
        before.setAccessible(true);
        try { before.invoke(callback, param); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
        return param;
    }

    public static final class StoreBuilder {
        public String a;
        public final StoreConfig a() { return new StoreConfig(); }
    }
    public static final class StoreConfig {}
    public static final class DatabaseBuilder {
        public String a;
        public final DatabaseConfig a() { return new DatabaseConfig(); }
    }
    public static final class DatabaseConfig {}
    public static final class RecordingParam extends XC_MethodHook.MethodHookParam {
        boolean resultSet;
        Throwable failure;
        @Override public void setResult(Object value) { resultSet = true; }
        public void setThrowable(Throwable value) { failure = value; resultSet = true; }
    }

    @Implements(value = XposedBridge.class, isInAndroidSdk = false)
    public static final class BridgeShadow {
        static final Map<Member, XC_MethodHook> hooks = new LinkedHashMap<>();
        static int attempts;
        static int failAt;
        @Implementation protected static XC_MethodHook.Unhook hookMethod(Member method, XC_MethodHook callback) {
            if (++attempts == failAt) throw new IllegalStateException("test hook failure");
            hooks.put(method, callback);
            return null;
        }
        @Implementation protected static void log(String message) {}
        @Implementation protected static void log(Throwable failure) {}
    }
    @Implements(value = XposedHelpers.class, isInAndroidSdk = false)
    public static final class HelpersShadow {
        static boolean missingDatabaseBuilder;
        @Implementation protected static Class<?> findClassIfExists(String name, ClassLoader loader) {
            return switch (name) {
                case "ppv" -> StoreBuilder.class;
                case "ppw" -> StoreConfig.class;
                case "pqa" -> missingDatabaseBuilder ? null : DatabaseBuilder.class;
                case "pqb" -> DatabaseConfig.class;
                default -> null;
            };
        }
    }
}
