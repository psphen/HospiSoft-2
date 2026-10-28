package com.hospisoft.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CachedStartupProbeTest {

    @Test
    void reportsPendingUntilTheBackgroundCheckFinishes() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        CachedStartupProbe probe = new CachedStartupProbe(() -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return StartupStatus.ok();
        });

        assertEquals(StartupStatus.State.PENDING, probe.probe().state());
        probe.refresh(status -> done.countDown());
        assertEquals(StartupStatus.State.PENDING, probe.probe().state());

        release.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(StartupStatus.State.OK, probe.probe().state());
    }

    @Test
    void delegateFailureBecomesDbUnavailable() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        CachedStartupProbe probe = new CachedStartupProbe(() -> {
            throw new IllegalStateException("boom");
        });
        probe.refresh(status -> done.countDown());
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(StartupStatus.State.DB_UNAVAILABLE, probe.probe().state());
    }

    @Test
    void concurrentRefreshRunsTheDelegateOnce() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        CachedStartupProbe probe = new CachedStartupProbe(() -> {
            calls.incrementAndGet();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return StartupStatus.ok();
        });
        probe.refresh(status -> done.countDown());
        probe.refresh();
        probe.refresh();
        release.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(1, calls.get());
    }

    @Test
    void probeBlockingComputesNowAndCaches() {
        AtomicInteger calls = new AtomicInteger();
        CachedStartupProbe probe = new CachedStartupProbe(() -> {
            calls.incrementAndGet();
            return StartupStatus.tablesMissing(java.util.List.of("cita"));
        });
        assertEquals(StartupStatus.State.PENDING, probe.probe().state());
        assertEquals(StartupStatus.State.TABLES_MISSING, probe.probeBlocking().state());
        assertEquals(StartupStatus.State.TABLES_MISSING, probe.probe().state());
        assertEquals(1, calls.get());
    }

    @Test
    void listenerIsNotifiedAfterRefreshAndCacheIsAlreadyUpdated() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<StartupStatus> seenByListener = new AtomicReference<>();
        AtomicReference<StartupStatus> cachedAtNotification = new AtomicReference<>();
        CachedStartupProbe probe = new CachedStartupProbe(StartupStatus::dbAuthFailed);
        probe.setListener(status -> {
            seenByListener.set(status);
            cachedAtNotification.set(probe.probe());
            done.countDown();
        });
        probe.refresh();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(StartupStatus.State.DB_AUTH_FAILED, seenByListener.get().state());
        assertEquals(StartupStatus.State.DB_AUTH_FAILED, cachedAtNotification.get().state());
    }
}
