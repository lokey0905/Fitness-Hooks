package com.lokey0905.FitnessHooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.os.Looper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class AppUpdateViewModelTest {
    @Test
    public void checkOnceSchedulesOnlyOneRequest() {
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger calls = new AtomicInteger();
        AppUpdateViewModel model = new AppUpdateViewModel(() -> {
            calls.incrementAndGet();
            return updateResult();
        }, executor);

        model.checkOnce();
        model.checkOnce();
        assertEquals(1, executor.tasks.size());
        assertEquals(0, calls.get());
        executor.runNext();
        ShadowLooper.idleMainLooper();
        model.checkOnce();
        assertEquals(1, calls.get());
        assertTrue(executor.tasks.isEmpty());
        model.onCleared();
    }

    @Test
    public void updateAndListenerAreDeliveredByMainHandler() {
        ManualExecutor executor = new ManualExecutor();
        AppUpdateViewModel model = new AppUpdateViewModel(AppUpdateViewModelTest::updateResult, executor);
        AtomicInteger notifications = new AtomicInteger();
        AtomicBoolean onMain = new AtomicBoolean();
        model.setListener(() -> {
            notifications.incrementAndGet();
            onMain.set(Looper.myLooper() == Looper.getMainLooper());
        });

        model.checkOnce();
        executor.runNext();
        assertNull(model.pendingUpdate());
        assertEquals(0, notifications.get());
        ShadowLooper.idleMainLooper();
        assertNotNull(model.pendingUpdate());
        assertEquals("2.3.0", model.pendingUpdate().version);
        assertEquals(1, notifications.get());
        assertTrue(onMain.get());
        model.onCleared();
    }

    @Test
    public void detachedListenerIsNotCalledAndReattachmentReceivesResult() {
        ManualExecutor executor = new ManualExecutor();
        AppUpdateViewModel model = new AppUpdateViewModel(AppUpdateViewModelTest::updateResult, executor);
        AtomicInteger oldNotifications = new AtomicInteger();
        AtomicInteger newNotifications = new AtomicInteger();
        model.setListener(oldNotifications::incrementAndGet);
        model.checkOnce();
        model.setListener(null);
        executor.runNext();
        ShadowLooper.idleMainLooper();
        assertEquals(0, oldNotifications.get());
        assertNotNull(model.pendingUpdate());

        GitHubReleaseClient.Release retained = model.pendingUpdate();
        model.setListener(newNotifications::incrementAndGet);
        assertEquals(1, newNotifications.get());
        assertSame(retained, model.pendingUpdate());
        assertTrue(executor.tasks.isEmpty());
        model.onCleared();
    }

    @Test
    public void dismissedUpdateDoesNotBecomePendingOnReattachment() {
        ManualExecutor executor = new ManualExecutor();
        AppUpdateViewModel model = new AppUpdateViewModel(AppUpdateViewModelTest::updateResult, executor);
        model.checkOnce();
        executor.runNext();
        ShadowLooper.idleMainLooper();
        assertNotNull(model.pendingUpdate());
        model.dismissUpdate();
        model.setListener(null);
        AtomicBoolean wouldPrompt = new AtomicBoolean();
        model.setListener(() -> wouldPrompt.set(model.pendingUpdate() != null));
        model.checkOnce();
        assertNull(model.pendingUpdate());
        assertFalse(wouldPrompt.get());
        assertTrue(executor.tasks.isEmpty());
        model.onCleared();
    }

    @Test
    public void clearingCancelsRequestAndShutsDownExecutor() {
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger calls = new AtomicInteger();
        AppUpdateViewModel model = new AppUpdateViewModel(() -> {
            calls.incrementAndGet();
            return updateResult();
        }, executor);
        model.checkOnce();
        Future<?> request = (Future<?>) executor.tasks.peek();
        assertNotNull(request);
        model.onCleared();
        assertTrue(request.isCancelled());
        assertTrue(executor.isShutdown());
        assertTrue(executor.isTerminated());
        model.checkOnce();
        assertEquals(0, calls.get());
        assertTrue(executor.tasks.isEmpty());
    }

    @Test
    public void clearingDropsAlreadyQueuedMainCallback() {
        ManualExecutor executor = new ManualExecutor();
        AppUpdateViewModel model = new AppUpdateViewModel(AppUpdateViewModelTest::updateResult, executor);
        AtomicInteger notifications = new AtomicInteger();
        model.setListener(notifications::incrementAndGet);
        model.checkOnce();
        executor.runNext();
        model.onCleared();
        ShadowLooper.idleMainLooper();
        assertEquals(0, notifications.get());
        assertNull(model.pendingUpdate());
    }

    @Test
    public void resultReturningAfterClearIsIgnored() {
        ManualExecutor executor = new ManualExecutor();
        AppUpdateViewModel[] holder = new AppUpdateViewModel[1];
        holder[0] = new AppUpdateViewModel(() -> {
            holder[0].onCleared();
            return updateResult();
        }, executor);
        AtomicInteger notifications = new AtomicInteger();
        holder[0].setListener(notifications::incrementAndGet);
        holder[0].checkOnce();
        try {
            executor.runNext();
        } finally {
            // This manual executor runs the canceled worker on the test thread.
            Thread.interrupted();
        }
        ShadowLooper.idleMainLooper();
        assertEquals(0, notifications.get());
        assertNull(holder[0].pendingUpdate());
        assertTrue(executor.isShutdown());
    }

    @Test
    public void callableFailureStaysQuietWithoutRetry() {
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger calls = new AtomicInteger();
        AppUpdateViewModel model = new AppUpdateViewModel(() -> {
            calls.incrementAndGet();
            throw new IOException("offline");
        }, executor);
        AtomicInteger notifications = new AtomicInteger();
        model.setListener(notifications::incrementAndGet);
        model.checkOnce();
        executor.runNext();
        ShadowLooper.idleMainLooper();
        model.checkOnce();
        assertEquals(1, calls.get());
        assertEquals(0, notifications.get());
        assertNull(model.pendingUpdate());
        assertTrue(executor.tasks.isEmpty());
        model.onCleared();
    }

    @Test
    public void noReleaseOrFailedResponseNeverBecomesPendingUpdate() {
        for (int status : new int[]{404, 429, 500}) {
            ManualExecutor executor = new ManualExecutor();
            AppUpdateViewModel model = new AppUpdateViewModel(() ->
                    GitHubReleaseClient.parseResponse(
                            new GitHubReleaseClient.Response(status, null, false), "2.2.0"), executor);
            model.checkOnce();
            executor.runNext();
            ShadowLooper.idleMainLooper();
            assertNull(model.pendingUpdate());
            model.onCleared();
        }
    }

    private static GitHubReleaseClient.Result updateResult() {
        return GitHubReleaseClient.parseResponse(new GitHubReleaseClient.Response(200,
                "{\"tag_name\":\"v2.3.0\",\"draft\":false,\"prerelease\":false,"
                        + "\"body\":\"Changes\",\"html_url\":\""
                        + GitHubReleaseClient.RELEASES_URL + "/tag/v2.3.0\"}", false), "2.2.0");
    }

    private static final class ManualExecutor extends AbstractExecutorService {
        final Queue<Runnable> tasks = new ArrayDeque<>();
        private boolean shutdown;

        @Override
        public void execute(Runnable command) {
            if (shutdown) {
                throw new RejectedExecutionException();
            }
            tasks.add(command);
        }

        void runNext() {
            Runnable task = tasks.remove();
            task.run();
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> pending = new ArrayList<>(tasks);
            tasks.clear();
            return pending;
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown && tasks.isEmpty();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }
    }
}
