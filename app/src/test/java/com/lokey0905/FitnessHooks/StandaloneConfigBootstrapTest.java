package com.lokey0905.FitnessHooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, instrumentedPackages = "de.robv.android.xposed",
        shadows = StandaloneProviderHookTest.BridgeShadow.class)
public final class StandaloneConfigBootstrapTest {
    @Test
    public void distinguishesVerifiedFalseFromUnknownAndFailure() {
        AtomicInteger lateCalls = new AtomicInteger();
        assertEquals(Boolean.TRUE, StandaloneConfigBootstrap.readWithin(() -> true, 1000,
                value -> lateCalls.incrementAndGet()));
        assertEquals(Boolean.FALSE, StandaloneConfigBootstrap.readWithin(() -> false, 1000,
                value -> lateCalls.incrementAndGet()));
        assertNull(StandaloneConfigBootstrap.readWithin(() -> null, 1000,
                value -> lateCalls.incrementAndGet()));
        assertNull(StandaloneConfigBootstrap.readWithin(() -> {
            throw new SecurityException("provider denied caller");
        }, 1000, value -> lateCalls.incrementAndGet()));
        assertEquals(0, lateCalls.get());
    }

    @Test
    public void acceptsOnlyTypedBooleanProviderResponses() {
        assertNull(StandaloneConfigBootstrap.parseRoute(null));
        Bundle result = new Bundle();
        assertNull(StandaloneConfigBootstrap.parseRoute(result));
        result.putString(HookConfig.KEY_STANDALONE_HC_ENABLED, "false");
        assertNull(StandaloneConfigBootstrap.parseRoute(result));
        result.putInt(HookConfig.KEY_STANDALONE_HC_ENABLED, 1);
        assertNull(StandaloneConfigBootstrap.parseRoute(result));
        result.putBoolean(HookConfig.KEY_STANDALONE_HC_ENABLED, false);
        assertEquals(Boolean.FALSE, StandaloneConfigBootstrap.parseRoute(result));
        result.putBoolean(HookConfig.KEY_STANDALONE_HC_ENABLED, true);
        assertEquals(Boolean.TRUE, StandaloneConfigBootstrap.parseRoute(result));
    }

    @Test
    public void timedOutReadDeliversItsVerifiedLateResult() throws Exception {
        assertLateResult(true);
        assertLateResult(false);
    }

    private void assertLateResult(boolean expected) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Boolean> delivered = new AtomicReference<>();
        try {
            assertNull(StandaloneConfigBootstrap.readWithin(() -> {
                release.await();
                return expected;
            }, 20, value -> {
                delivered.set(value);
                finished.countDown();
            }));
            assertNull(delivered.get());
            release.countDown();
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            assertEquals(Boolean.valueOf(expected), delivered.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    public void interruptedWaitPreservesInterruptAndAllowsLateVerification() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(1);
        Thread.currentThread().interrupt();
        try {
            assertNull(StandaloneConfigBootstrap.readWithin(() -> {
                release.await();
                return true;
            }, 1000, value -> delivered.countDown()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
            release.countDown();
        }
        assertTrue(delivered.await(2, TimeUnit.SECONDS));
    }
}
