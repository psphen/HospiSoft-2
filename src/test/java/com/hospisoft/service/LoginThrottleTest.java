package com.hospisoft.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class LoginThrottleTest {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final LoginThrottle throttle = new LoginThrottle(now::get);

    @Test
    void fourFailuresDoNotLock() {
        for (int i = 0; i < LoginThrottle.MAX_FAILURES - 1; i++) {
            throttle.recordFailure("admin");
        }
        assertDoesNotThrow(() -> throttle.check("admin"));
    }

    @Test
    void fifthFailureLocksForThirtySeconds() {
        for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) {
            throttle.recordFailure("admin");
        }
        AuthenticationException ex = assertThrows(AuthenticationException.class, () -> throttle.check("admin"));
        assertEquals("Demasiados intentos. Espere 30 segundos.", ex.getMessage());
        now.addAndGet(LoginThrottle.LOCK_MILLIS - 1);
        assertThrows(AuthenticationException.class, () -> throttle.check("admin"));
        now.addAndGet(1);
        assertDoesNotThrow(() -> throttle.check("admin"));
        // counter restarted after the lock expired
        for (int i = 0; i < LoginThrottle.MAX_FAILURES - 1; i++) {
            throttle.recordFailure("admin");
        }
        assertDoesNotThrow(() -> throttle.check("admin"));
    }

    @Test
    void successResetsTheCounter() {
        for (int i = 0; i < LoginThrottle.MAX_FAILURES - 1; i++) {
            throttle.recordFailure("admin");
        }
        throttle.recordSuccess("admin");
        for (int i = 0; i < LoginThrottle.MAX_FAILURES - 1; i++) {
            throttle.recordFailure("admin");
        }
        assertDoesNotThrow(() -> throttle.check("admin"));
    }

    @Test
    void usernamesAreIsolatedAndNormalized() {
        for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) {
            throttle.recordFailure("  Admin ");
        }
        assertThrows(AuthenticationException.class, () -> throttle.check("admin"));
        assertDoesNotThrow(() -> throttle.check("dr.garcia"));
    }
}
