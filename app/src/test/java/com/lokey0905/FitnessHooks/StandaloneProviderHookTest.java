package com.lokey0905.FitnessHooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.util.ReflectionHelpers;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, instrumentedPackages = "de.robv.android.xposed",
        shadows = {StandaloneProviderHookTest.BridgeShadow.class, StandaloneProviderHookTest.HelpersShadow.class})
public final class StandaloneProviderHookTest {
    private Context context;

    @Before
    public void reset() throws Exception {
        BridgeShadow.hooks.clear();
        BridgeShadow.logs.clear();
        BridgeShadow.failAt = 0;
        BridgeShadow.attempts = 0;
        HelpersShadow.checker = ValidChecker.class;
        Field installed = StandaloneProviderHook.class.getDeclaredField("INSTALLED");
        installed.setAccessible(true);
        ((Set<?>) installed.get(null)).clear();
        context = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override
            public String getPackageName() {
                return StandaloneHealthConnect.PROVIDER_PACKAGE;
            }
        };
        ReflectionHelpers.setStaticField(TargetConfigBridge.class, "applicationContext", context);
        select(false);
        installProvider(283955L);
    }

    @Test
    @Config(sdk = {28, 33})
    public void androidBefore34InstallsNothing() {
        select(true);
        assertEquals(0, install());
        assertTrue(BridgeShadow.hooks.isEmpty());
    }

    @Test
    public void installsOnlyBothMigrationGatesAndDoesNotInstallTwice() throws Exception {
        assertEquals(2, install());
        assertEquals(Set.of(ValidChecker.class.getDeclaredMethod("a"),
                ValidChecker.class.getDeclaredMethod("b")), BridgeShadow.hooks.keySet());
        assertEquals(0, install());
        assertEquals(2, BridgeShadow.attempts);
    }

    @Test
    public void absentTargetLeavesOriginalRoute() {
        HelpersShadow.checker = null;
        assertEquals(0, install());
        assertTrue(BridgeShadow.hooks.isEmpty());
    }

    @Test
    public void wrongFieldShapeIsRejectedBeforeEitherHook() {
        HelpersShadow.checker = WrongFieldChecker.class;
        assertEquals(0, install());
        assertTrue(BridgeShadow.hooks.isEmpty());
    }

    @Test
    public void wrongMethodShapeIsRejectedBeforeEitherHook() {
        HelpersShadow.checker = WrongMethodChecker.class;
        assertEquals(0, install());
        assertTrue(BridgeShadow.hooks.isEmpty());
    }

    @Test
    public void wrongConstructorIsRejectedBeforeEitherHook() {
        HelpersShadow.checker = WrongConstructorChecker.class;
        assertEquals(0, install());
        assertTrue(BridgeShadow.hooks.isEmpty());
    }

    @Test
    public void unknownAndUnselectedRoutesPreserveBothOriginalMethods() throws Throwable {
        assertEquals(2, install());
        for (Boolean selected : new Boolean[]{null, false}) {
            select(selected);
            assertFalse(invoke("a").resultSet);
            assertFalse(invoke("b").resultSet);
        }
    }

    @Test
    public void selectionDisablesBothMigrationGatesWithoutChangingSdk() throws Throwable {
        assertEquals(2, install());
        select(true);
        assertOverriddenFalse(invoke("a"));
        assertOverriddenFalse(invoke("b"));
        assertEquals(34, android.os.Build.VERSION.SDK_INT);
        assertEquals(1, BridgeShadow.logs.stream().filter(line -> line.contains("provider route active")).count());
    }

    @Test
    public void selectionChangesTakeEffectWithoutReinstalling() throws Throwable {
        assertEquals(2, install());
        assertFalse(invoke("a").resultSet);
        select(true);
        assertOverriddenFalse(invoke("a"));
        assertOverriddenFalse(invoke("b"));
        select(false);
        assertFalse(invoke("a").resultSet);
        assertFalse(invoke("b").resultSet);
        select(true);
        assertOverriddenFalse(invoke("a"));
        assertOverriddenFalse(invoke("b"));
    }

    @Test
    public void missingContextPreservesOriginalUntilBootstrapCompletes() throws Throwable {
        assertEquals(2, install());
        select(true);
        ReflectionHelpers.setStaticField(TargetConfigBridge.class, "applicationContext", null);
        assertFalse(invoke("a").resultSet);
        assertFalse(invoke("b").resultSet);
        ReflectionHelpers.setStaticField(TargetConfigBridge.class, "applicationContext", context);
        assertOverriddenFalse(invoke("a"));
        assertOverriddenFalse(invoke("b"));
    }

    @Test
    public void contextFromAnotherPackageCannotEnableHooks() throws Throwable {
        assertEquals(2, install());
        select(true);
        ReflectionHelpers.setStaticField(TargetConfigBridge.class, "applicationContext",
                RuntimeEnvironment.getApplication());
        assertFalse(invoke("a").resultSet);
        assertFalse(invoke("b").resultSet);
    }

    @Test
    public void newerProviderVersionCanUseMatchingMigrationChecker() throws Throwable {
        installProvider(283956L);
        select(true);
        assertEquals(2, install());
        assertOverriddenFalse(invoke("a"));
        assertOverriddenFalse(invoke("b"));
    }

    @Test
    public void olderProviderVersionCanUseMatchingMigrationChecker() throws Throwable {
        installProvider(171302L);
        select(true);
        assertEquals(2, install());
        assertOverriddenFalse(invoke("a"));
        assertOverriddenFalse(invoke("b"));
    }

    @Test
    public void aPartialInstallationNeverActivatesOnlyOneMigrationGate() throws Throwable {
        BridgeShadow.failAt = 2;
        select(true);
        assertEquals(0, install());
        assertEquals(1, BridgeShadow.hooks.size());
        assertFalse(invoke("a").resultSet);
    }

    @Test
    public void retryAfterPartialInstallationCanActivateBothGates() throws Throwable {
        BridgeShadow.failAt = 2;
        select(true);
        assertEquals(0, install());
        BridgeShadow.failAt = 0;
        assertEquals(2, install());
        assertOverriddenFalse(invoke("a"));
        assertOverriddenFalse(invoke("b"));
    }

    private int install() {
        return StandaloneProviderHook.install(getClass().getClassLoader());
    }

    private void select(Boolean selected) {
        ReflectionHelpers.setStaticField(TargetConfigBridge.class, "standaloneRouteSelection", selected);
    }

    private void installProvider(long version) {
        ApplicationInfo application = new ApplicationInfo();
        application.packageName = StandaloneHealthConnect.PROVIDER_PACKAGE;
        application.enabled = true;
        PackageInfo provider = new PackageInfo();
        provider.packageName = application.packageName;
        provider.applicationInfo = application;
        provider.setLongVersionCode(version);
        ShadowPackageManager packages = Shadow.extract(context.getPackageManager());
        packages.installPackage(provider);
    }

    private SdkGateSelfHookTest.RecordingParam invoke(String name) throws Throwable {
        Method target = ValidChecker.class.getDeclaredMethod(name);
        XC_MethodHook callback = BridgeShadow.hooks.get(target);
        assertNotNull(BridgeShadow.logs.toString(), callback);
        SdkGateSelfHookTest.RecordingParam param = new SdkGateSelfHookTest.RecordingParam();
        param.method = target;
        param.thisObject = new ValidChecker(true, true);
        param.args = new Object[0];
        Method before = callback.getClass().getDeclaredMethod("beforeHookedMethod", XC_MethodHook.MethodHookParam.class);
        before.setAccessible(true);
        try {
            before.invoke(callback, param);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
        return param;
    }

    private void assertOverriddenFalse(SdkGateSelfHookTest.RecordingParam result) {
        assertTrue(result.resultSet);
        assertEquals(Boolean.FALSE, result.getResult());
    }

    public static final class ValidChecker {
        private static final Object a = new Object();
        private final boolean b;
        private final boolean c;

        public ValidChecker(boolean b, boolean c) { this.b = b; this.c = c; }
        public final boolean a() { return c; }
        public final boolean b() { return b && c; }
    }

    public static final class WrongFieldChecker {
        private static final Object a = new Object();
        private final int b;
        private final boolean c;

        public WrongFieldChecker(boolean b, boolean c) { this.b = b ? 1 : 0; this.c = c; }
        public final boolean a() { return c; }
        public final boolean b() { return b != 0 && c; }
    }

    public static final class WrongMethodChecker {
        private static final Object a = new Object();
        private final boolean b;
        private final boolean c;

        public WrongMethodChecker(boolean b, boolean c) { this.b = b; this.c = c; }
        public final boolean a() { return c; }
        public final int b() { return b && c ? 1 : 0; }
    }

    public static final class WrongConstructorChecker {
        private static final Object a = new Object();
        private final boolean b;
        private final boolean c;

        public WrongConstructorChecker() { this.b = true; this.c = true; }
        public final boolean a() { return c; }
        public final boolean b() { return b && c; }
    }

    @Implements(value = XposedBridge.class, isInAndroidSdk = false)
    public static final class BridgeShadow {
        static final Map<Member, XC_MethodHook> hooks = new LinkedHashMap<>();
        static final List<String> logs = new ArrayList<>();
        static int failAt;
        static int attempts;

        @Implementation
        protected static XC_MethodHook.Unhook hookMethod(Member method, XC_MethodHook callback) {
            attempts++;
            if (attempts == failAt) throw new IllegalStateException("test hook installation failure");
            hooks.put(method, callback);
            return null;
        }

        @Implementation
        protected static void log(String message) { logs.add(message); }

        @Implementation
        protected static void log(Throwable failure) { logs.add(failure.toString()); }
    }

    @Implements(value = XposedHelpers.class, isInAndroidSdk = false)
    public static final class HelpersShadow {
        static Class<?> checker;

        @Implementation
        protected static Class<?> findClassIfExists(String name, ClassLoader loader) {
            return "cvv".equals(name) ? checker : null;
        }
    }
}
