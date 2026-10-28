package com.hospisoft.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hospisoft.dao.UsuarioRepository;
import com.hospisoft.service.AuthService;
import com.hospisoft.service.SessionHolder;
import com.hospisoft.service.StartupStatus;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class JsApiTest {

    /** The exact surface reachable from the page. Widening it is a deliberate, reviewed change. */
    private static final Set<String> ALLOWED = Set.of(
        "call", "login", "logout", "getSession", "getAllowedPages", "getStartupStatus", "navigate", "log");

    @Test
    void publicSurfaceIsExactlyTheAllowedSet() {
        Set<String> declared = new TreeSet<>();
        for (Method method : JsApi.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers()) && !method.isSynthetic()) {
                declared.add(method.getName());
            }
        }
        assertEquals(new TreeSet<>(ALLOWED), declared);
        assertTrue(Modifier.isFinal(JsApi.class.getModifiers()));
        assertEquals(Object.class, JsApi.class.getSuperclass());
    }

    @Test
    void delegatesToTheBridge() {
        UsuarioRepository none = username -> Optional.empty();
        SessionHolder sessions = new SessionHolder();
        Bridge bridge = new Bridge(new AuthService(none), sessions, StartupStatus::ok, page -> { }, script -> { });
        JsApi api = new JsApi(bridge);
        Map<String, Object> session = Json.parseObject(api.getSession());
        assertEquals(true, session.get("ok"));
        assertEquals("OK", ((Map<?, ?>) Json.parseObject(api.getStartupStatus()).get("data")).get("state"));
        assertEquals("BAD_REQUEST", Json.parseObject(api.navigate("nope")).get("code"));
        assertEquals("BAD_REQUEST", Json.parseObject(api.call("bad id!", "login", "{}")).get("code"));
        api.log("hello");
        bridge.shutdown();
    }
}
