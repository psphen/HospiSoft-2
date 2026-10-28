package com.hospisoft.service;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * In-memory brute-force throttle: after {@link #MAX_FAILURES} consecutive failures for the same
 * username, further attempts are rejected for {@link #LOCK_MILLIS}. A success clears the counter.
 * Per-process only (the desktop app is single-user), which is the intended scope.
 */
public final class LoginThrottle {

    public static final int MAX_FAILURES = 5;
    public static final long LOCK_MILLIS = 30_000;
    public static final String LOCKED_MESSAGE = "Demasiados intentos. Espere 30 segundos.";

    private static final class Entry {
        int failures;
        long lockedUntil;
    }

    private final LongSupplier clock;
    private final Map<String, Entry> entries = new HashMap<>();

    public LoginThrottle() {
        this(System::currentTimeMillis);
    }

    public LoginThrottle(LongSupplier clock) {
        this.clock = clock;
    }

    /** @throws AuthenticationException when the username is currently locked */
    public synchronized void check(String username) {
        Entry entry = entries.get(key(username));
        if (entry == null) {
            return;
        }
        long now = clock.getAsLong();
        if (entry.lockedUntil > now) {
            throw new AuthenticationException(LOCKED_MESSAGE);
        }
        if (entry.lockedUntil != 0 && entry.lockedUntil <= now) {
            entries.remove(key(username)); // lock expired: start over
        }
    }

    public synchronized void recordFailure(String username) {
        Entry entry = entries.computeIfAbsent(key(username), k -> new Entry());
        entry.failures++;
        if (entry.failures >= MAX_FAILURES) {
            entry.lockedUntil = clock.getAsLong() + LOCK_MILLIS;
            entry.failures = 0;
        }
    }

    public synchronized void recordSuccess(String username) {
        entries.remove(key(username));
    }

    private static String key(String username) {
        return username == null ? "" : username.trim().toLowerCase();
    }
}
