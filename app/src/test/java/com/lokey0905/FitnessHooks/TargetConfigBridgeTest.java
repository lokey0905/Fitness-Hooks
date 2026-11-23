package com.lokey0905.FitnessHooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, instrumentedPackages = "de.robv.android.xposed",
        shadows = {TargetConfigBridgeTest.BootstrapShadow.class,
                StandaloneProviderHookTest.BridgeShadow.class})
public final class TargetConfigBridgeTest {
    private RecordingContext context;

    @Before
    public void reset() {
        AtomicBoolean registered = ReflectionHelpers.getStaticField(TargetConfigBridge.class, "REGISTERED");
        registered.set(false);
        ReflectionHelpers.setStaticField(TargetConfigBridge.class, "applicationContext", null);
        ReflectionHelpers.setStaticField(TargetConfigBridge.class, "standaloneRouteSelection", null);
        ReflectionHelpers.setStaticField(TargetConfigBridge.class, "configGeneration", 0L);
        context = new RecordingContext(RuntimeEnvironment.getApplication());
        BootstrapShadow.result = false;
        BootstrapShadow.calls = 0;
        BootstrapShadow.events = context.events;
        BootstrapShadow.lateResult = null;
        context.getSharedPreferences(ConfigTransport.TARGET_PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().putBoolean(HookConfig.KEY_STANDALONE_HC_ENABLED, true).commit();
    }

    @Test
    public void bootstrapPrecedesRequestAndIgnoresOldLocalFlag() {
        initialize();
        assertEquals(List.of("register", "bootstrap", "request"), context.events);
        assertEquals(ConfigTransport.CONFIG_PERMISSION, context.senderPermission);
        assertEquals(Context.RECEIVER_EXPORTED, context.receiverFlags);
        assertFalse(TargetConfigBridge.isStandaloneProviderEnabled());
        assertTrue(TargetConfigBridge.isStandaloneRouteInitialized());
        assertEquals(Boolean.FALSE, TargetConfigBridge.getStandaloneRouteSelection());
        assertNotNull(context.request);
        assertEquals(ConfigTransport.MODULE_PACKAGE, context.request.getComponent().getPackageName());
        assertEquals(ConfigTransport.ACTION_REQUEST_CONFIG, context.request.getAction());
    }

    @Test
    public void validStartupReadAndLiveBroadcastsControlOnlyProviderChoice() {
        BootstrapShadow.result = true;
        initialize();
        assertTrue(TargetConfigBridge.isStandaloneProviderEnabled());
        context.receiver.onReceive(context, new Intent("wrong.action"));
        assertTrue(TargetConfigBridge.isStandaloneProviderEnabled());
        context.receiver.onReceive(context, new Intent(ConfigTransport.ACTION_APPLY_CONFIG));
        assertTrue(TargetConfigBridge.isStandaloneProviderEnabled());
        context.receiver.onReceive(context, new Intent(ConfigTransport.ACTION_APPLY_CONFIG)
                .putExtra(HookConfig.KEY_STANDALONE_HC_ENABLED, false));
        assertFalse(TargetConfigBridge.isStandaloneProviderEnabled());
        context.receiver.onReceive(context, new Intent(ConfigTransport.ACTION_APPLY_CONFIG)
                .putExtra(HookConfig.KEY_STANDALONE_HC_ENABLED, true)
                .putExtra(HookConfig.KEY_STEP_COUNT, 9999));
        assertTrue(TargetConfigBridge.isStandaloneProviderEnabled());
        assertFalse(context.getSharedPreferences(ConfigTransport.TARGET_PREFS_NAME,
                Context.MODE_PRIVATE).contains(HookConfig.KEY_STEP_COUNT));
        initialize();
        assertEquals(1, BootstrapShadow.calls);
    }

    @Test
    public void fitBootstrapsBeforeRequestAndOnlyUpdatesItsLiveRoute() {
        context.packageName = ConfigTransport.FIT_PACKAGE;
        BootstrapShadow.result = true;
        initialize();

        assertEquals(List.of("register", "bootstrap", "request"), context.events);
        assertEquals(ConfigTransport.CONFIG_PERMISSION, context.senderPermission);
        assertEquals(Context.RECEIVER_EXPORTED, context.receiverFlags);
        assertTrue(TargetConfigBridge.isStandaloneRouteEnabled());
        assertEquals(ConfigTransport.MODULE_PACKAGE, context.request.getComponent().getPackageName());
        context.receiver.onReceive(context, new Intent("wrong.action"));
        assertTrue(TargetConfigBridge.isStandaloneRouteEnabled());

        context.receiver.onReceive(context, new Intent(ConfigTransport.ACTION_APPLY_CONFIG));
        assertTrue(TargetConfigBridge.isStandaloneRouteEnabled());
        context.receiver.onReceive(context, new Intent(ConfigTransport.ACTION_APPLY_CONFIG)
                .putExtra(HookConfig.KEY_STANDALONE_HC_ENABLED, false));
        assertFalse(TargetConfigBridge.isStandaloneRouteEnabled());
        context.receiver.onReceive(context, new Intent(ConfigTransport.ACTION_APPLY_CONFIG)
                .putExtra(HookConfig.KEY_STANDALONE_HC_ENABLED, true)
                .putExtra(HookConfig.KEY_STEP_HOOK_ENABLED, true)
                .putExtra(HookConfig.KEY_STEP_COUNT, 9999));
        assertTrue(TargetConfigBridge.isStandaloneRouteEnabled());
        assertTrue(TargetConfigBridge.isStandaloneProviderEnabled());
        assertFalse(context.getSharedPreferences(ConfigTransport.TARGET_PREFS_NAME,
                Context.MODE_PRIVATE).contains(HookConfig.KEY_STEP_COUNT));
        assertFalse(context.getSharedPreferences(ConfigTransport.TARGET_PREFS_NAME,
                Context.MODE_PRIVATE).contains(HookConfig.KEY_STEP_HOOK_ENABLED));
        initialize();
        assertEquals(1, BootstrapShadow.calls);
    }

    @Test
    public void fitStartupFailureCannotReuseTheOldTargetPreference() {
        context.packageName = ConfigTransport.FIT_PACKAGE;
        BootstrapShadow.result = null;
        initialize();
        assertFalse(TargetConfigBridge.isStandaloneRouteEnabled());
        assertFalse(TargetConfigBridge.isStandaloneRouteInitialized());
        assertNull(TargetConfigBridge.getStandaloneRouteSelection());
        assertEquals(1, BootstrapShadow.calls);
        assertTrue(context.getSharedPreferences(ConfigTransport.TARGET_PREFS_NAME,
                Context.MODE_PRIVATE).getBoolean(HookConfig.KEY_STANDALONE_HC_ENABLED, false));
    }

    @Test
    public void missingOrWrongTypedBroadcastDoesNotChooseOrChangeAProvider() {
        context.packageName = ConfigTransport.FIT_PACKAGE;
        BootstrapShadow.result = null;
        initialize();
        List<Intent> invalid = List.of(
                new Intent(ConfigTransport.ACTION_APPLY_CONFIG),
                new Intent(ConfigTransport.ACTION_APPLY_CONFIG)
                        .putExtra(HookConfig.KEY_STANDALONE_HC_ENABLED, "false"),
                new Intent(ConfigTransport.ACTION_APPLY_CONFIG)
                        .putExtra(HookConfig.KEY_STANDALONE_HC_ENABLED, 0),
                new Intent(ConfigTransport.ACTION_APPLY_CONFIG)
                        .putExtra(HookConfig.KEY_STANDALONE_HC_ENABLED, (String) null),
                new Intent("wrong.action").putExtra(HookConfig.KEY_STANDALONE_HC_ENABLED, true));
        for (Intent intent : invalid) {
            context.receiver.onReceive(context, intent);
            assertNull(TargetConfigBridge.getStandaloneRouteSelection());
        }
        // Invalid payloads must not advance the generation and discard a valid pending read.
        BootstrapShadow.lateResult.accept(true);
        assertEquals(Boolean.TRUE, TargetConfigBridge.getStandaloneRouteSelection());
        for (boolean selected : new boolean[]{true, false}) {
            broadcast(selected);
            for (Intent intent : invalid) {
                context.receiver.onReceive(context, intent);
                assertEquals(selected, TargetConfigBridge.getStandaloneRouteSelection());
            }
        }
    }

    @Test
    public void pendingStartupCanResolveToEitherVerifiedRouteForHcAndFit() {
        for (String packageName : new String[]{ConfigTransport.HC_PACKAGE, ConfigTransport.FIT_PACKAGE}) {
            for (boolean selected : new boolean[]{true, false}) {
                reset();
                context.packageName = packageName;
                BootstrapShadow.result = null;
                initialize();
                assertNull(TargetConfigBridge.getStandaloneRouteSelection());
                assertNotNull(BootstrapShadow.lateResult);
                BootstrapShadow.lateResult.accept(null);
                assertNull(TargetConfigBridge.getStandaloneRouteSelection());
                BootstrapShadow.lateResult.accept(selected);
                assertTrue(TargetConfigBridge.isStandaloneRouteInitialized());
                assertEquals(selected, TargetConfigBridge.getStandaloneRouteSelection());
            }
        }
    }

    @Test
    public void newerSignedChoiceWinsOverAnOppositeLateBootstrapResult() {
        for (String packageName : new String[]{ConfigTransport.HC_PACKAGE, ConfigTransport.FIT_PACKAGE}) {
            for (boolean selected : new boolean[]{true, false}) {
                reset();
                context.packageName = packageName;
                BootstrapShadow.result = null;
                initialize();
                Consumer<Boolean> oldRead = BootstrapShadow.lateResult;
                broadcast(selected);
                oldRead.accept(!selected);
                assertEquals(selected, TargetConfigBridge.getStandaloneRouteSelection());
                assertTrue(TargetConfigBridge.isStandaloneRouteInitialized());
            }
        }
    }

    @Test
    @Config(sdk = 33)
    public void android13FitDoesNotBootstrapOrEnableStandaloneOverride() {
        context.packageName = ConfigTransport.FIT_PACKAGE;
        BootstrapShadow.result = true;
        initialize();
        assertEquals(0, BootstrapShadow.calls);
        context.receiver.onReceive(context, new Intent(ConfigTransport.ACTION_APPLY_CONFIG)
                .putExtra(HookConfig.KEY_STANDALONE_HC_ENABLED, true));
        assertFalse(TargetConfigBridge.isStandaloneRouteEnabled());
    }

    @Test
    @Config(sdk = 33)
    public void olderAndroidDoesNotBootstrapOrAcceptProviderEnableFlag() {
        BootstrapShadow.result = true;
        initialize();
        assertEquals(0, BootstrapShadow.calls);
        context.receiver.onReceive(context, new Intent(ConfigTransport.ACTION_APPLY_CONFIG)
                .putExtra(HookConfig.KEY_STANDALONE_HC_ENABLED, true));
        assertFalse(TargetConfigBridge.isStandaloneProviderEnabled());
    }

    @Test
    public void registrationFailureLeavesProviderUnknownAndAllowsRetry() {
        context.failRegistration = true;
        BootstrapShadow.result = true;
        initialize();
        assertEquals(0, BootstrapShadow.calls);
        assertFalse(TargetConfigBridge.isStandaloneProviderEnabled());
        assertNull(TargetConfigBridge.getStandaloneRouteSelection());
        assertFalse(TargetConfigBridge.isStandaloneRouteInitialized());
        assertTrue(context.events.isEmpty());
        AtomicBoolean registered = ReflectionHelpers.getStaticField(TargetConfigBridge.class, "REGISTERED");
        assertFalse(registered.get());
        context.failRegistration = false;
        initialize();
        assertEquals(1, BootstrapShadow.calls);
        assertEquals(Boolean.TRUE, TargetConfigBridge.getStandaloneRouteSelection());
    }

    @Test
    public void requestFailureRetainsVerifiedValueAndTheRegisteredReceiver() {
        context.packageName = ConfigTransport.FIT_PACKAGE;
        BootstrapShadow.result = true;
        context.failRequest = true;
        initialize();
        assertEquals(List.of("register", "bootstrap", "request"), context.events);
        assertEquals(Boolean.TRUE, TargetConfigBridge.getStandaloneRouteSelection());
        AtomicBoolean registered = ReflectionHelpers.getStaticField(TargetConfigBridge.class, "REGISTERED");
        assertTrue(registered.get());
        BroadcastReceiver receiver = context.receiver;
        broadcast(false);
        assertEquals(Boolean.FALSE, TargetConfigBridge.getStandaloneRouteSelection());
        initialize();
        assertEquals(1, BootstrapShadow.calls);
        assertEquals(List.of("register", "bootstrap", "request"), context.events);
        assertEquals(receiver, context.receiver);
    }

    @Test
    public void pikminKeepsItsExistingBroadcastConfigurationWithoutHcBootstrap() {
        context.packageName = ConfigTransport.TARGET_PACKAGE;
        initialize();
        assertEquals(0, BootstrapShadow.calls);
        context.receiver.onReceive(context, new Intent(ConfigTransport.ACTION_APPLY_CONFIG)
                .putExtra(HookConfig.KEY_STANDALONE_HC_ENABLED, true)
                .putExtra(HookConfig.KEY_STEP_HOOK_ENABLED, true)
                .putExtra(HookConfig.KEY_STEP_COUNT, 456));
        HookConfig.Snapshot saved = ConfigTransport.readTargetSettings(context);
        assertTrue(saved.standaloneHcEnabled);
        assertTrue(saved.stepHookEnabled);
        assertEquals(456, saved.stepCount);
        assertFalse(TargetConfigBridge.isStandaloneProviderEnabled());
    }

    @Test
    public void unrelatedProcessNeverRegistersOrReads() {
        context.packageName = "example.unrelated";
        initialize();
        assertTrue(context.events.isEmpty());
        assertEquals(0, BootstrapShadow.calls);
    }

    private void initialize() {
        ReflectionHelpers.callStaticMethod(TargetConfigBridge.class, "initialize",
                ReflectionHelpers.ClassParameter.from(Context.class, context));
    }

    private void broadcast(boolean selected) {
        context.receiver.onReceive(context, new Intent(ConfigTransport.ACTION_APPLY_CONFIG)
                .putExtra(HookConfig.KEY_STANDALONE_HC_ENABLED, selected));
    }

    private static final class RecordingContext extends ContextWrapper {
        final List<String> events = new ArrayList<>();
        String packageName = ConfigTransport.HC_PACKAGE;
        String senderPermission;
        int receiverFlags;
        boolean failRegistration;
        boolean failRequest;
        BroadcastReceiver receiver;
        Intent request;

        RecordingContext(Context base) { super(base); }

        @Override public String getPackageName() { return packageName; }
        @Override public Context getApplicationContext() { return this; }

        @Override
        public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
                String permission, Handler scheduler, int flags) {
            if (failRegistration) throw new SecurityException("registration failed");
            this.receiver = receiver;
            senderPermission = permission;
            receiverFlags = flags;
            events.add("register");
            return null;
        }

        @Override public void sendBroadcast(Intent intent) {
            request = intent;
            events.add("request");
            if (failRequest) throw new SecurityException("request failed");
        }
    }

    @Implements(value = StandaloneConfigBootstrap.class, isInAndroidSdk = false)
    public static final class BootstrapShadow {
        static Boolean result;
        static int calls;
        static List<String> events;
        static Consumer<Boolean> lateResult;

        @Implementation protected static Boolean read(Context context, Consumer<Boolean> onLateResult) {
            calls++;
            events.add("bootstrap");
            lateResult = onLateResult;
            return result;
        }
    }
}
