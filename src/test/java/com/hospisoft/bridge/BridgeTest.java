package com.hospisoft.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hospisoft.dao.UsuarioRepository;
import com.hospisoft.db.DatabaseAuthException;
import com.hospisoft.db.DatabaseUnavailableException;
import com.hospisoft.model.Rol;
import com.hospisoft.model.Usuario;
import com.hospisoft.security.PasswordHasher;
import com.hospisoft.service.AuthService;
import com.hospisoft.service.SessionContext;
import com.hospisoft.service.SessionHolder;
import com.hospisoft.service.StartupProbe;
import com.hospisoft.service.StartupStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BridgeTest {

    private static Usuario admin;

    @BeforeAll
    static void hashFixture() {
        PasswordHasher.Hashed hashed = PasswordHasher.hash("Admin123*".toCharArray());
        admin = new Usuario(1L, "admin", hashed.hashBase64(), hashed.saltBase64(), Rol.ADMIN, true, null, null);
    }

    private static final UsuarioRepository WORKING_REPOSITORY =
        username -> "admin".equals(username) ? Optional.of(admin) : Optional.empty();

    private static final UsuarioRepository DEAD_REPOSITORY = username -> {
        throw new DatabaseUnavailableException("connection refused", null);
    };

    private static final UsuarioRepository BAD_CREDENTIALS_REPOSITORY = username -> {
        throw new DatabaseAuthException("password authentication failed", null);
    };

    /** Captures scripts the bridge wants to run in the page. */
    static final class FakeSink implements Bridge.ScriptSink {
        final List<String> scripts = new ArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);

        @Override
        public synchronized void run(String script) {
            scripts.add(script);
            latch.countDown();
        }

        String await() throws InterruptedException {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "no response delivered");
            synchronized (this) {
                return scripts.get(scripts.size() - 1);
            }
        }
    }

    private final List<String> navigations = new ArrayList<>();
    private final SessionHolder sessions = new SessionHolder();
    private final FakeSink sink = new FakeSink();
    private final List<Bridge> created = new ArrayList<>();

    private Bridge bridge(UsuarioRepository repository) {
        return bridge(repository, StartupStatus::ok);
    }

    private Bridge bridge(UsuarioRepository repository, StartupProbe probe) {
        Bridge.Navigator navigator = new Bridge.Navigator() {
            @Override
            public void navigate(String page) {
                navigations.add(page);
            }

            @Override
            public boolean exists(String page) {
                return !"citas.html".equals(page); // simulate one missing resource
            }
        };
        Bridge bridge = new Bridge(new AuthService(repository), sessions, probe, navigator, sink);
        created.add(bridge);
        return bridge;
    }

    @AfterEach
    void shutdown() {
        created.forEach(Bridge::shutdown);
    }

    /** Extracts the envelope from {@code if (window.__hospisoft) { window.__hospisoft.resolve("id", "json"); }}. */
    private static Map<String, Object> envelopeOf(String script, String expectedRequestId) {
        String prefix = "if (window.__hospisoft) { window.__hospisoft.resolve(";
        String suffix = "); }";
        assertTrue(script.startsWith(prefix), script);
        assertTrue(script.endsWith(suffix), script);
        String args = script.substring(prefix.length(), script.length() - suffix.length());
        @SuppressWarnings("unchecked")
        List<Object> parsed = (List<Object>) Json.parse("[" + args + "]");
        assertEquals(expectedRequestId, parsed.get(0));
        return Json.parseObject((String) parsed.get(1));
    }

    private void login(Bridge bridge) {
        Map<String, Object> login = Json.parseObject(bridge.login("{\"username\":\"admin\",\"password\":\"Admin123*\"}"));
        assertEquals(true, login.get("ok"));
    }

    // ------------------------------------------------------------ sync envelope

    @Test
    void loginWithMalformedJsonReturnsBadRequestEnvelope() {
        Map<String, Object> response = Json.parseObject(bridge(WORKING_REPOSITORY).login("{not json"));
        assertEquals(false, response.get("ok"));
        assertEquals("BAD_REQUEST", response.get("code"));
        assertTrue(response.get("message") instanceof String);
    }

    @Test
    void loginWithNullOrMissingFieldsReturnsBadRequest() {
        Bridge bridge = bridge(WORKING_REPOSITORY);
        assertEquals("BAD_REQUEST", Json.parseObject(bridge.login(null)).get("code"));
        assertEquals("BAD_REQUEST", Json.parseObject(bridge.login("{\"username\":\"a\"}")).get("code"));
        assertEquals("BAD_REQUEST", Json.parseObject(bridge.login("[1,2]")).get("code"));
    }

    @Test
    void databaseFailureIsTranslatedIntoDbUnavailableEnvelope() {
        String raw = bridge(DEAD_REPOSITORY).login("{\"username\":\"admin\",\"password\":\"Admin123*\"}");
        Map<String, Object> response = Json.parseObject(raw);
        assertEquals(false, response.get("ok"));
        assertEquals("DB_UNAVAILABLE", response.get("code"));
        assertEquals("Sin conexi\u00f3n con la base de datos", response.get("message"));
    }

    @Test
    void databaseAuthFailureIsTranslatedIntoDbAuthFailedEnvelope() {
        String raw = bridge(BAD_CREDENTIALS_REPOSITORY).login("{\"username\":\"admin\",\"password\":\"Admin123*\"}");
        Map<String, Object> response = Json.parseObject(raw);
        assertEquals("DB_AUTH_FAILED", response.get("code"));
        assertEquals(Bridge.DB_AUTH_MESSAGE, response.get("message"));
    }

    @Test
    void dbUnavailableLoginTriggersAStartupRefresh() {
        AtomicInteger refreshes = new AtomicInteger();
        StartupProbe probe = new StartupProbe() {
            @Override
            public StartupStatus probe() {
                return StartupStatus.dbUnavailable();
            }

            @Override
            public void refresh() {
                refreshes.incrementAndGet();
            }
        };
        bridge(DEAD_REPOSITORY, probe).login("{\"username\":\"admin\",\"password\":\"Admin123*\"}");
        assertEquals(1, refreshes.get());
        bridge(WORKING_REPOSITORY, probe).login("{\"username\":\"admin\",\"password\":\"nope\"}");
        assertEquals(1, refreshes.get(), "credential failures must not refresh");
    }

    @Test
    void wrongCredentialsReturnAuthFailedWithGenericMessage() {
        String raw = bridge(WORKING_REPOSITORY).login("{\"username\":\"admin\",\"password\":\"nope\"}");
        Map<String, Object> response = Json.parseObject(raw);
        assertEquals(false, response.get("ok"));
        assertEquals("AUTH_FAILED", response.get("code"));
        assertEquals(AuthService.GENERIC_FAILURE, response.get("message"));
        assertTrue(sessions.current().isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void successfulLoginStoresSessionAndLogoutClearsIt() {
        Bridge bridge = bridge(WORKING_REPOSITORY);
        Map<String, Object> login = Json.parseObject(bridge.login("{\"username\":\"admin\",\"password\":\"Admin123*\"}"));
        assertEquals(true, login.get("ok"));
        Map<String, Object> data = (Map<String, Object>) login.get("data");
        assertEquals("admin", data.get("username"));
        assertEquals("ADMIN", data.get("rol"));
        assertEquals("Administrador", data.get("displayName"));
        assertEquals(1L, data.get("userId"));

        Map<String, Object> session = Json.parseObject(bridge.getSession());
        assertEquals(true, session.get("ok"));
        assertEquals("admin", ((Map<String, Object>) session.get("data")).get("username"));

        assertEquals(true, Json.parseObject(bridge.logout()).get("ok"));
        Map<String, Object> after = Json.parseObject(bridge.getSession());
        assertEquals(true, after.get("ok"));
        assertNull(after.get("data"));
        assertTrue(after.containsKey("data"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void startupStatusReportsEachState() {
        Map<String, Object> ok = (Map<String, Object>) Json.parseObject(
            bridge(WORKING_REPOSITORY, StartupStatus::ok).getStartupStatus()).get("data");
        assertEquals("OK", ok.get("state"));

        Map<String, Object> cfg = (Map<String, Object>) Json.parseObject(bridge(WORKING_REPOSITORY,
            () -> StartupStatus.configMissing("C:/app/config.properties")).getStartupStatus()).get("data");
        assertEquals("CONFIG_MISSING", cfg.get("state"));
        assertEquals("C:/app/config.properties", cfg.get("expectedPath"));

        Map<String, Object> tables = (Map<String, Object>) Json.parseObject(bridge(WORKING_REPOSITORY,
            () -> StartupStatus.tablesMissing(List.of("cita", "receta"))).getStartupStatus()).get("data");
        assertEquals("TABLES_MISSING", tables.get("state"));
        assertEquals(List.of("cita", "receta"), tables.get("missingTables"));

        Map<String, Object> keys = (Map<String, Object>) Json.parseObject(bridge(WORKING_REPOSITORY,
            () -> StartupStatus.keysMissing("C:/x", List.of("db.user"))).getStartupStatus()).get("data");
        assertEquals("CONFIG_KEYS_MISSING", keys.get("state"));
        assertEquals(List.of("db.user"), keys.get("missingKeys"));

        Map<String, Object> down = (Map<String, Object>) Json.parseObject(bridge(WORKING_REPOSITORY,
            StartupStatus::dbUnavailable).getStartupStatus()).get("data");
        assertEquals("DB_UNAVAILABLE", down.get("state"));

        Map<String, Object> auth = (Map<String, Object>) Json.parseObject(bridge(WORKING_REPOSITORY,
            StartupStatus::dbAuthFailed).getStartupStatus()).get("data");
        assertEquals("DB_AUTH_FAILED", auth.get("state"));

        Map<String, Object> read = (Map<String, Object>) Json.parseObject(bridge(WORKING_REPOSITORY,
            () -> StartupStatus.configReadError("C:/x")).getStartupStatus()).get("data");
        assertEquals("CONFIG_READ_ERROR", read.get("state"));
    }

    @Test
    void unexpectedExceptionsNeverEscapeAndNeverLeakTheirMessage() {
        Bridge broken = bridge(WORKING_REPOSITORY, () -> {
            throw new IllegalArgumentException("invalid enum value 'X' from column rol");
        });
        Map<String, Object> response = Json.parseObject(broken.getStartupStatus());
        assertEquals(false, response.get("ok"));
        assertEquals("INTERNAL_ERROR", response.get("code"));
        assertEquals(Bridge.INTERNAL_ERROR_MESSAGE, response.get("message"));
        assertFalse(((String) response.get("message")).contains("enum"));
    }

    // ------------------------------------------------------------ navigation and roles

    @Test
    void navigateDelegatesToNavigatorAndRejectsUnknownPages() {
        Bridge bridge = bridge(WORKING_REPOSITORY);
        login(bridge);
        assertEquals(true, Json.parseObject(bridge.navigate("medicos.html")).get("ok"));
        assertEquals(List.of("medicos.html"), navigations);
        assertEquals("BAD_REQUEST", Json.parseObject(bridge.navigate("../etc/passwd")).get("code"));
        assertEquals("BAD_REQUEST", Json.parseObject(bridge.navigate(null)).get("code"));
        assertEquals(1, navigations.size());
    }

    @Test
    void navigateWithoutSessionOnlyAllowsLogin() {
        Bridge bridge = bridge(WORKING_REPOSITORY);
        assertEquals("FORBIDDEN", Json.parseObject(bridge.navigate("index.html")).get("code"));
        assertEquals(true, Json.parseObject(bridge.navigate("login.html")).get("ok"));
        assertEquals(List.of("login.html"), navigations);
    }

    @Test
    void navigateEnforcesRolePermissions() {
        Bridge bridge = bridge(WORKING_REPOSITORY);
        sessions.set(new SessionContext(5, "dr.garcia", Rol.MEDICO, 10L, "Carlos"));
        Map<String, Object> forbidden = Json.parseObject(bridge.navigate("medicos.html"));
        assertEquals("FORBIDDEN", forbidden.get("code"));
        assertEquals(Bridge.FORBIDDEN_MESSAGE, forbidden.get("message"));
        assertEquals(true, Json.parseObject(bridge.navigate("pacientes.html")).get("ok"));

        sessions.set(new SessionContext(6, "paciente1", Rol.PACIENTE, 20L, "Maria"));
        assertEquals("FORBIDDEN", Json.parseObject(bridge.navigate("pacientes.html")).get("code"));
        assertEquals(true, Json.parseObject(bridge.navigate("medicamentos.html")).get("ok"));
        assertEquals(List.of("pacientes.html", "medicamentos.html"), navigations);
    }

    @Test
    void navigateToAMissingResourceIsAnInternalError() {
        Bridge bridge = bridge(WORKING_REPOSITORY);
        login(bridge);
        Map<String, Object> response = Json.parseObject(bridge.navigate("citas.html"));
        assertEquals("INTERNAL_ERROR", response.get("code"));
        assertTrue(navigations.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void allowedPagesFollowTheSessionRole() {
        Bridge bridge = bridge(WORKING_REPOSITORY);
        assertEquals(List.of(), Json.parseObject(bridge.getAllowedPages()).get("data"));
        sessions.set(new SessionContext(6, "paciente1", Rol.PACIENTE, 20L, "Maria"));
        assertEquals(List.of("index.html", "citas.html", "medicamentos.html"),
            Json.parseObject(bridge.getAllowedPages()).get("data"));
        sessions.set(new SessionContext(1, "admin", Rol.ADMIN, null, "Administrador"));
        List<Object> all = (List<Object>) Json.parseObject(bridge.getAllowedPages()).get("data");
        assertEquals(5, all.size());
    }

    // ------------------------------------------------------------ async path

    @Test
    void asyncCallWithUnknownMethodResolvesWithBadRequest() throws InterruptedException {
        Bridge bridge = bridge(WORKING_REPOSITORY);
        Map<String, Object> ack = Json.parseObject(bridge.call("r1", "nope", ""));
        assertEquals(true, ack.get("ok"));
        Map<String, Object> envelope = envelopeOf(sink.await(), "r1");
        assertEquals(false, envelope.get("ok"));
        assertEquals("BAD_REQUEST", envelope.get("code"));
    }

    @Test
    void asyncLoginDbFailureResolvesWithDbUnavailable() throws InterruptedException {
        Bridge bridge = bridge(DEAD_REPOSITORY);
        bridge.call("abc-123", "login", "{\"username\":\"admin\",\"password\":\"Admin123*\"}");
        Map<String, Object> envelope = envelopeOf(sink.await(), "abc-123");
        assertEquals("DB_UNAVAILABLE", envelope.get("code"));
        assertEquals(Bridge.DB_UNAVAILABLE_MESSAGE, envelope.get("message"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void asyncLoginSuccessResolvesWithSessionAndStoresIt() throws InterruptedException {
        Bridge bridge = bridge(WORKING_REPOSITORY);
        bridge.call("r2", "login", "{\"username\":\"admin\",\"password\":\"Admin123*\"}");
        Map<String, Object> envelope = envelopeOf(sink.await(), "r2");
        assertEquals(true, envelope.get("ok"));
        assertEquals("admin", ((Map<String, Object>) envelope.get("data")).get("username"));
        assertTrue(sessions.isLoggedIn());
    }

    @Test
    @SuppressWarnings("unchecked")
    void asyncStartupStatusUsesTheBlockingProbe() throws InterruptedException {
        StartupProbe probe = new StartupProbe() {
            @Override
            public StartupStatus probe() {
                return StartupStatus.pending();
            }

            @Override
            public StartupStatus probeBlocking() {
                return StartupStatus.tablesMissing(List.of("receta"));
            }
        };
        Bridge bridge = bridge(WORKING_REPOSITORY, probe);
        bridge.call("r3", "getStartupStatus", null);
        Map<String, Object> envelope = envelopeOf(sink.await(), "r3");
        assertEquals("TABLES_MISSING", ((Map<String, Object>) envelope.get("data")).get("state"));
    }

    @Test
    void asyncCallRejectsBadRequestIdsSynchronously() {
        Bridge bridge = bridge(WORKING_REPOSITORY);
        assertEquals("BAD_REQUEST", Json.parseObject(bridge.call(null, "login", "{}")).get("code"));
        assertEquals("BAD_REQUEST", Json.parseObject(bridge.call("x\"); alert(1); (\"", "login", "{}")).get("code"));
        assertTrue(sink.scripts.isEmpty());
    }

    @Test
    void asyncResponseIsASafeJsStringLiteral() {
        String script = Bridge.resolveScript("r1", "{\"ok\":false,\"message\":\"a\\\"b\u2028</script>\"}");
        assertFalse(script.contains("\u2028"));
        assertTrue(script.startsWith("if (window.__hospisoft) { window.__hospisoft.resolve(\"r1\", \""));
    }

    @Test
    void callAfterShutdownReturnsInternalErrorInsteadOfThrowing() {
        Bridge bridge = bridge(WORKING_REPOSITORY);
        bridge.shutdown();
        assertEquals("INTERNAL_ERROR", Json.parseObject(bridge.call("r9", "login", "{}")).get("code"));
    }

    // ------------------------------------------------------------ log

    @Test
    void logNeverThrows() {
        bridge(WORKING_REPOSITORY).log(null);
        bridge(WORKING_REPOSITORY).log("hello from js");
    }

    @Test
    void logIsCappedAndControlCharactersAreStripped() {
        assertEquals("null", Bridge.sanitizeLog(null));
        assertEquals("a\tbc[forged] line", Bridge.sanitizeLog("a\tb\nc\r[forged] line\u0000\u001b"));
        String sanitized = Bridge.sanitizeLog("x".repeat(Bridge.MAX_LOG_CHARS + 500));
        assertEquals(Bridge.MAX_LOG_CHARS + " [truncated]".length(), sanitized.length());
        assertTrue(sanitized.endsWith(" [truncated]"));
    }
}
