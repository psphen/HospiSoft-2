package com.hospisoft.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hospisoft.bridge.Bridge;
import com.hospisoft.bridge.Json;
import com.hospisoft.dao.InMemoryAuditoriaRepository;
import com.hospisoft.dao.UsuarioRepository;
import com.hospisoft.export.FakeFileSaver;
import com.hospisoft.model.AccionAuditoria;
import com.hospisoft.model.ModuloAuditoria;
import com.hospisoft.model.Rol;
import com.hospisoft.service.AppZone;
import com.hospisoft.service.AuditoriaService;
import com.hospisoft.service.AuthService;
import com.hospisoft.service.ExportService;
import com.hospisoft.service.RecordingAuditLog;
import com.hospisoft.service.SessionContext;
import com.hospisoft.service.SessionHolder;
import com.hospisoft.service.StartupStatus;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditoriaControllerTest {

    private static final SessionContext ADMIN = new SessionContext(1, "admin", Rol.ADMIN, null, "Administrador");
    private static final SessionContext MEDICO = new SessionContext(2, "dr.garcia", Rol.MEDICO, 10L, "Carlos Garcia");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-04T15:00:05Z"), ZoneOffset.UTC);
    private static final AppZone ZONE = new AppZone(ZoneId.of("America/Bogota"));
    private static final UsuarioRepository NO_USERS = username -> Optional.empty();

    private final SessionHolder sessions = new SessionHolder();
    private final BlockingQueue<String> scripts = new ArrayBlockingQueue<>(16);
    private AuditoriaService service;
    private FakeFileSaver saver;
    private RecordingAuditLog exportAudit;
    private ExportService exports;
    private Bridge bridge;
    @TempDir
    Path dir;
    private int sequence;

    @BeforeEach
    void setUp() {
        InMemoryAuditoriaRepository repository = new InMemoryAuditoriaRepository(CLOCK);
        service = new AuditoriaService(repository, sessions::current, ZONE, line -> { });
        sessions.set(ADMIN);
        service.record(AccionAuditoria.CREAR, ModuloAuditoria.PACIENTE, 20L, "Paciente 2001 - Maria Torres");
        service.recordFailedLogin("ghost", "Usuario o contraseña incorrectos");

        bridge = new Bridge(new AuthService(NO_USERS), sessions, StartupStatus::ok, page -> { }, scripts::add);
        saver = new FakeFileSaver(null);
        exportAudit = new RecordingAuditLog();
        exports = ControllerTestSupport.exports(saver, exportAudit, sessions);
        new AuditoriaController(service, exports).register(bridge);
    }

    @AfterEach
    void tearDown() {
        bridge.shutdown();
    }

    private Map<String, Object> invoke(String method, String payload) throws InterruptedException {
        String requestId = "a" + (++sequence);
        Map<String, Object> ack = Json.parseObject(bridge.call(requestId, method, payload));
        assertEquals(true, ack.get("ok"), "call was not queued: " + ack);
        String script = scripts.poll(10, TimeUnit.SECONDS);
        assertTrue(script != null, "no response for " + method);
        String prefix = "if (window.__hospisoft) { window.__hospisoft.resolve(";
        String args = script.substring(prefix.length(), script.length() - "); }".length());
        @SuppressWarnings("unchecked")
        List<Object> parsed = (List<Object>) Json.parse("[" + args + "]");
        assertEquals(requestId, parsed.get(0));
        return Json.parseObject((String) parsed.get(1));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> envelope) {
        assertEquals(true, envelope.get("ok"), "expected success: " + envelope);
        return (Map<String, Object>) envelope.get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    @Test
    void registersExactlyTheAuditoriaMethods() {
        assertEquals(Set.of("listAuditoria", "exportAuditoria"),
            ControllerTestSupport.registeredNames(new AuditoriaController(service, exports)));
    }

    @Test
    void listReturnsDisplayReadyRowsNewestFirst() throws InterruptedException {
        Map<String, Object> page = data(invoke("listAuditoria", "{\"page\":1,\"pageSize\":10}"));
        assertEquals(2L, page.get("total"));
        assertEquals(1L, page.get("totalPages"));
        Map<String, Object> failed = items(page).get(0);
        assertEquals("LOGIN_FALLIDO", failed.get("accion"));
        assertEquals("Inicio de sesión fallido", failed.get("accionLabel"));
        assertEquals("SESION", failed.get("modulo"));
        assertEquals("Sesión", failed.get("moduloLabel"));
        assertEquals("ghost", failed.get("username"));
        assertEquals(null, failed.get("rol"));
        assertEquals(null, failed.get("idUsuario"));

        Map<String, Object> created = items(page).get(1);
        assertEquals("2026-11-04 10:00:05", created.get("fecha"), "formatted in the app zone");
        assertEquals("2026-11-04T15:00:05Z", created.get("fechaHora"));
        assertEquals(1L, created.get("idUsuario"));
        assertEquals("admin", created.get("username"));
        assertEquals("ADMIN", created.get("rol"));
        assertEquals("CREAR", created.get("accion"));
        assertEquals("Crear", created.get("accionLabel"));
        assertEquals("Pacientes", created.get("moduloLabel"));
        assertEquals(20L, created.get("idRegistro"));
        assertEquals("Paciente 2001 - Maria Torres", created.get("detalle"));
    }

    @Test
    void filtersAreParsedAndApplied() throws InterruptedException {
        Map<String, Object> byAccion = data(invoke("listAuditoria",
            "{\"filter\":{\"accion\":\"login_fallido\",\"modulo\":\"SESION\",\"texto\":\"gho\","
                + "\"desde\":\"2026-11-04\",\"hasta\":\"2026-11-04\"}}"));
        assertEquals(1L, byAccion.get("total"));
        assertEquals("ghost", items(byAccion).get(0).get("username"));

        Map<String, Object> otherDay = data(invoke("listAuditoria", "{\"filter\":{\"desde\":\"2026-11-05\"}}"));
        assertEquals(0L, otherDay.get("total"));

        Map<String, Object> blank = data(invoke("listAuditoria", "{\"filter\":{\"accion\":\"\",\"modulo\":\" \"}}"));
        assertEquals(2L, blank.get("total"), "blank selects mean no filter");
    }

    @Test
    void badFiltersAreRejected() throws InterruptedException {
        assertEquals("BAD_REQUEST", invoke("listAuditoria", "{\"filter\":{\"accion\":\"BORRAR\"}}").get("code"));
        assertEquals("BAD_REQUEST", invoke("listAuditoria", "{\"filter\":{\"modulo\":\"USUARIO\"}}").get("code"));
        assertEquals("BAD_REQUEST", invoke("listAuditoria", "{\"filter\":{\"desde\":\"04/11/2026\"}}").get("code"));
        assertEquals("BAD_REQUEST", invoke("listAuditoria", "{\"pageSize\":1000}").get("code"));
        Map<String, Object> inverted = invoke("listAuditoria",
            "{\"filter\":{\"desde\":\"2026-11-05\",\"hasta\":\"2026-11-01\"}}");
        assertEquals("VALIDATION", inverted.get("code"));
        assertTrue(((Map<?, ?>) inverted.get("errors")).containsKey("hasta"));
    }

    @Test
    void onlyAdminMayReadTheAuditLog() throws InterruptedException {
        sessions.set(MEDICO);
        Map<String, Object> envelope = invoke("listAuditoria", "{}");
        assertEquals("FORBIDDEN", envelope.get("code"));
        assertEquals(Bridge.FORBIDDEN_MESSAGE, envelope.get("message"));
        sessions.clear();
        assertEquals("FORBIDDEN", invoke("listAuditoria", "{}").get("code"));
    }

    @Test
    void exportWritesTheFilteredLogNewestFirst() throws Exception {
        saver.answer(dir.resolve("auditoria.xlsx"));
        Map<String, Object> result = data(invoke("exportAuditoria", "{\"filter\":{\"desde\":\"2026-11-04\"},\"format\":\"xlsx\"}"));
        assertEquals(2L, result.get("rows"));
        assertEquals("auditoria_2026-11-04.xlsx", saver.last().suggestedFileName());
        List<List<String>> table = ControllerTestSupport.table(dir.resolve("auditoria.xlsx"));
        assertEquals(List.of(
            List.of("Fecha", "Usuario", "Rol", "Acción", "Módulo", "Registro", "Detalle"),
            List.of("2026-11-04 10:00:05", "ghost", "–", "Inicio de sesión fallido", "Sesión", "–",
                "Usuario o contraseña incorrectos"),
            List.of("2026-11-04 10:00:05", "admin", "ADMIN", "Crear", "Pacientes", "#20",
                "Paciente 2001 - Maria Torres")), table);

        RecordingAuditLog.Entry entry = exportAudit.only();
        assertEquals(AccionAuditoria.EXPORTAR, entry.accion());
        assertEquals(ModuloAuditoria.AUDITORIA, entry.modulo());
    }

    @Test
    void exportIsAdminOnlyAndValidatesFiltersAndFormat() throws Exception {
        assertEquals("BAD_REQUEST", invoke("exportAuditoria", "{\"format\":\"txt\"}").get("code"));
        assertEquals("BAD_REQUEST", invoke("exportAuditoria", "{\"filter\":{\"accion\":\"BORRAR\"},\"format\":\"pdf\"}")
            .get("code"));
        assertEquals("VALIDATION", invoke("exportAuditoria",
            "{\"filter\":{\"desde\":\"2026-11-05\",\"hasta\":\"2026-11-01\"},\"format\":\"pdf\"}").get("code"));
        sessions.set(MEDICO);
        assertEquals("FORBIDDEN", invoke("exportAuditoria", "{\"format\":\"pdf\"}").get("code"));
        assertTrue(saver.requests().isEmpty());
    }
}
