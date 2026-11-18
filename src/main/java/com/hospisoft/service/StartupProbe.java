package com.hospisoft.service;

@FunctionalInterface
public interface StartupProbe {
    StartupStatus probe();

    default StartupStatus probeBlocking() {
        return probe();
    }

    default void refresh() {
    }
}
