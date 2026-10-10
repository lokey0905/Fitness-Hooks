package com.lokey0905.FitnessHooks;

import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.ViewModel;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class AppUpdateViewModel extends ViewModel {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Callable<GitHubReleaseClient.Result> check;
    private final ExecutorService executor;
    private Future<?> request;
    private GitHubReleaseClient.Result result;
    private Runnable listener;
    private boolean started;
    private boolean dismissed;
    private volatile boolean cleared;

    public AppUpdateViewModel() {
        this(() -> new GitHubReleaseClient().check(BuildConfig.VERSION_NAME),
                Executors.newSingleThreadExecutor());
    }

    AppUpdateViewModel(Callable<GitHubReleaseClient.Result> check, ExecutorService executor) {
        this.check = check;
        this.executor = executor;
    }

    public void checkOnce() {
        if (started || cleared) {
            return;
        }
        started = true;
        request = executor.submit(() -> {
            try {
                GitHubReleaseClient.Result checked = check.call();
                if (!cleared) {
                    handler.post(() -> finishCheck(checked));
                }
            } catch (Exception ignored) {
                // An automatic check must not interrupt settings when offline.
            }
        });
    }

    private void finishCheck(GitHubReleaseClient.Result checked) {
        if (cleared) {
            return;
        }
        result = checked;
        if (listener != null) {
            listener.run();
        }
    }

    public void setListener(Runnable listener) {
        this.listener = listener;
        if (listener != null && result != null) {
            listener.run();
        }
    }

    public GitHubReleaseClient.Release pendingUpdate() {
        return !dismissed && result != null
                && result.state == GitHubReleaseClient.State.UPDATE_AVAILABLE
                ? result.release : null;
    }

    public void dismissUpdate() {
        dismissed = true;
    }

    @Override
    protected void onCleared() {
        cleared = true;
        listener = null;
        handler.removeCallbacksAndMessages(null);
        if (request != null) {
            request.cancel(true);
        }
        executor.shutdownNow();
    }
}
