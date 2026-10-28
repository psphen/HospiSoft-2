package com.hospisoft.service;

/** Computes the startup status. Implemented against the real config and database; faked in tests. */
@FunctionalInterface
public interface StartupProbe {

    /** Current status. Cached implementations answer immediately (possibly PENDING). */
    StartupStatus probe();

    /** Fresh status, computed now on the calling thread (may block on a connect timeout). */
    default StartupStatus probeBlocking() {
        return probe();
    }

    /** Asks for a background re-check; cached implementations notify their listener when done. */
    default void refresh() {
    }
}
