package com.lokey0905.FitnessHooks;

import android.content.Context;
import android.os.Bundle;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Bounded startup wait; null means unknown, never an explicit system-provider choice. */
final class StandaloneConfigBootstrap {
    private static final long TIMEOUT_MS = 750;

    private StandaloneConfigBootstrap() {
    }

    static Boolean read(Context context, Consumer<Boolean> onLateResult) {
        return readWithin(() -> {
            Bundle result = context.getContentResolver().call(
                    StandaloneConfigProvider.URI,
                    StandaloneConfigProvider.METHOD_GET_ROUTE, null, null);
            return parseRoute(result);
        }, TIMEOUT_MS, onLateResult);
    }

    @SuppressWarnings("deprecation")
    static Boolean parseRoute(Bundle result) {
        if (result == null) return null;
        Object value = result.get(HookConfig.KEY_STANDALONE_HC_ENABLED);
        return value instanceof Boolean ? (Boolean) value : null;
    }

    static Boolean readWithin(Callable<Boolean> read, long timeoutMs,
            Consumer<Boolean> onLateResult) {
        CompletableFuture<Boolean> task = new CompletableFuture<>();
        Thread worker = new Thread(() -> {
            try {
                task.complete(read.call());
            } catch (Throwable failure) {
                task.completeExceptionally(failure);
            }
        }, "FitnessHooks-hc-config");
        worker.setDaemon(true);
        worker.start();
        try {
            return task.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            FitnessHooksEntry.log("HC startup configuration interrupted; route remains pending");
        } catch (Exception failure) {
            FitnessHooksEntry.log("HC startup configuration unavailable; route remains pending: "
                    + failure.getClass().getSimpleName());
        }
        // Keep the same in-flight read. The bridge rejects results made stale by a newer
        // signed broadcast. Registering after timeout also handles a completion race.
        task.whenComplete((value, failure) -> {
            if (failure != null || value == null) return;
            try {
                onLateResult.accept(value);
            } catch (Throwable callbackFailure) {
                FitnessHooksEntry.log("HC late startup configuration failed: " + callbackFailure);
            }
        });
        return null;
    }
}
