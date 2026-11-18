package com.hospisoft.service;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class CachedStartupProbe implements StartupProbe {

    private final StartupProbe delegate;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile StartupStatus cached;
    private volatile Consumer<StartupStatus> listener;

    public CachedStartupProbe(StartupProbe delegate) {
        this.delegate = delegate;
    }

    public void setListener(Consumer<StartupStatus> listener) {
        this.listener = listener;
    }

    @Override
    public StartupStatus probe() {
        StartupStatus current = cached;
        return current != null ? current : StartupStatus.pending();
    }

    @Override
    public StartupStatus probeBlocking() {
        StartupStatus result = compute();
        cached = result;
        return result;
    }

    @Override
    public void refresh() {
        refresh(null);
    }

    public void refresh(Consumer<StartupStatus> onDone) {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Thread worker = new Thread(() -> {
            StartupStatus result = compute();
            cached = result;
            running.set(false);
            System.out.println("[startup] status " + result.state());
            Consumer<StartupStatus> current = listener;
            if (current != null) {
                current.accept(result);
            }
            if (onDone != null) {
                onDone.accept(result);
            }
        }, "hospisoft-startup-probe");
        worker.setDaemon(true);
        worker.start();
    }

    private StartupStatus compute() {
        try {
            return delegate.probe();
        } catch (RuntimeException ex) {
            System.err.println("[startup] probe failed: " + ex);
            return StartupStatus.dbUnavailable();
        }
    }
}
