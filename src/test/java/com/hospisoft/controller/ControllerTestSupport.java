package com.hospisoft.controller;

import com.hospisoft.bridge.Bridge;
import com.hospisoft.service.AuthService;
import com.hospisoft.service.SessionHolder;
import com.hospisoft.service.StartupStatus;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** Shared helpers for the controller tests. */
final class ControllerTestSupport {

    private ControllerTestSupport() {
    }

    /** Method names {@code controller} adds to a fresh bridge (the bridge's own built-ins excluded). */
    static Set<String> registeredNames(Controller controller) {
        Bridge bridge = new Bridge(new AuthService(username -> Optional.empty()), new SessionHolder(),
            StartupStatus::ok, page -> { }, script -> { });
        try {
            Set<String> builtIn = bridge.asyncMethodNames();
            controller.register(bridge);
            Set<String> added = new HashSet<>(bridge.asyncMethodNames());
            added.removeAll(builtIn);
            return added;
        } finally {
            bridge.shutdown();
        }
    }
}
