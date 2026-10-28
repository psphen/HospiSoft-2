package com.hospisoft.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Result of the startup health check shown on the login page. */
public record StartupStatus(State state, String expectedPath, List<String> missingKeys, List<String> missingTables) {

    public enum State {
        PENDING,
        OK,
        CONFIG_MISSING,
        CONFIG_READ_ERROR,
        CONFIG_KEYS_MISSING,
        DB_UNAVAILABLE,
        DB_AUTH_FAILED,
        TABLES_MISSING
    }

    public static StartupStatus pending() {
        return new StartupStatus(State.PENDING, null, List.of(), List.of());
    }

    public static StartupStatus ok() {
        return new StartupStatus(State.OK, null, List.of(), List.of());
    }

    public static StartupStatus configMissing(String expectedPath) {
        return new StartupStatus(State.CONFIG_MISSING, expectedPath, List.of(), List.of());
    }

    public static StartupStatus configReadError(String expectedPath) {
        return new StartupStatus(State.CONFIG_READ_ERROR, expectedPath, List.of(), List.of());
    }

    public static StartupStatus keysMissing(String expectedPath, List<String> missingKeys) {
        return new StartupStatus(State.CONFIG_KEYS_MISSING, expectedPath, List.copyOf(missingKeys), List.of());
    }

    public static StartupStatus dbUnavailable() {
        return new StartupStatus(State.DB_UNAVAILABLE, null, List.of(), List.of());
    }

    public static StartupStatus dbAuthFailed() {
        return new StartupStatus(State.DB_AUTH_FAILED, null, List.of(), List.of());
    }

    public static StartupStatus tablesMissing(List<String> missingTables) {
        return new StartupStatus(State.TABLES_MISSING, null, List.of(), List.copyOf(missingTables));
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("state", state);
        map.put("expectedPath", expectedPath);
        map.put("missingKeys", missingKeys);
        map.put("missingTables", missingTables);
        return map;
    }
}
