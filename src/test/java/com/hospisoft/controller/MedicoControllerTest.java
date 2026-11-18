package com.hospisoft.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hospisoft.bridge.Bridge;
import com.hospisoft.bridge.Json;
import com.hospisoft.dao.InMemoryMedicoRepository;
import com.hospisoft.dao.UsuarioRepository;
import com.hospisoft.export.FakeFileSaver;
import com.hospisoft.model.Medico;
import com.hospisoft.model.ModuloAuditoria;
import com.hospisoft.model.Rol;
import com.hospisoft.service.AuditLog;
import com.hospisoft.service.AuthService;
import com.hospisoft.service.ExportService;
import com.hospisoft.service.MedicoService;
import com.hospisoft.service.RecordingAuditLog;
import com.hospisoft.service.SessionContext;
import com.hospisoft.service.SessionHolder;
import com.hospisoft.service.StartupStatus;
import java.nio.file.Path;
import java.util.ArrayList;
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

class MedicoControllerTest {

    private static final SessionContext ADMIN = new SessionContext(1, "admin", Rol.ADMIN, null, "Administrador");
    private static final SessionContext MEDICO = new SessionContext(2, "dr.garcia", Rol.MEDICO, 10L, "Carlos Garcia");
    private static final SessionContext PACIENTE = new SessionContext(3, "paciente1", Rol.PACIENTE, 20L, "Maria");
    private static final UsuarioRepository NO_USERS = username -> Optional.empty();

    static final class QueueSink implements Bridge.ScriptSink {
        final BlockingQueue<String> scripts = new ArrayBlockingQueue<>(16);

        @Override
        public void run(String script) {
            scripts.add(script);
        }
    }

    private final SessionHolder sessions = new SessionHolder();
    private final QueueSink sink = new QueueSink();
    private InMemoryMedicoRepository medicos;
    private MedicoService service;
    private FakeFileSaver saver;
    private RecordingAuditLog exportAudit;
    private ExportService exports;
    private Bridge bridge;
    @TempDir
    Path dir;
    private int sequence;

    @BeforeEach
    void setUp() {
        medicos = new InMemoryMedicoRepository();
        for (int i = 1; i <= 12; i++) {
            medicos.add(new Medico(null, "100" + i, "Nombre" + i, "Apellido" + String.format("%02d", i),
                i % 2 == 0 ? "Pediatria" : "Cardiologia", null, null, i != 12));
        }

        bridge = new Bridge(new AuthService(NO_USERS), sessions, StartupStatus::ok, page -> { }, sink);
        service = new MedicoService(medicos, sessions::current, AuditLog.NONE);
        saver = new FakeFileSaver(null);
        exportAudit = new RecordingAuditLog();
        exports = ControllerTestSupport.exports(saver, exportAudit, sessions);
        new MedicoController(service, exports).register(bridge);
        sessions.set(ADMIN);
    }

    @AfterEach
    void tearDown() {
        bridge.shutdown();
    }

    private Map<String, Object> invoke(String method, String payload) throws InterruptedException {
        String requestId = "req" + (++sequence);
        Map<String, Object> ack = Json.parseObject(bridge.call(requestId, method, payload));
        assertEquals(true, ack.get("ok"), "call was not queued: " + ack);
        String script = sink.scripts.poll(10, TimeUnit.SECONDS);
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
    void registersExactlyTheMedicoMethods() {
        assertEquals(Set.of("listMedicos", "getMedico", "saveMedico", "deleteMedico", "listEspecialidades",
                "exportMedicos"),
            ControllerTestSupport.registeredNames(new MedicoController(service, exports)));
    }

    @Test
    void listMedicosPaginatesWithDefaultsAndFilters() throws InterruptedException {
        Map<String, Object> first = data(invoke("listMedicos", "{}"));
        assertEquals(12L, first.get("total"));
        assertEquals(1L, first.get("page"));
        assertEquals(10L, first.get("pageSize"));
        assertEquals(2L, first.get("totalPages"));
        assertEquals(10, items(first).size());
        assertEquals("Apellido01", items(first).get(0).get("apellidos"));
        assertEquals("Nombre1 Apellido01", items(first).get(0).get("nombreCompleto"));

        Map<String, Object> second = data(invoke("listMedicos", "{\"page\":2,\"pageSize\":10}"));
        assertEquals(2, items(second).size());
        assertEquals("Apellido11", items(second).get(0).get("apellidos"));

        Map<String, Object> filtered = data(invoke("listMedicos",
            "{\"filter\":{\"especialidad\":\"Pediatria\",\"activo\":true,\"texto\":\"apellido1\"},\"page\":1,\"pageSize\":5}"));
        assertEquals(1L, filtered.get("total"));
        assertEquals("Apellido10", items(filtered).get(0).get("apellidos"));

        assertEquals("BAD_REQUEST", invoke("listMedicos", "{\"page\":0}").get("code"));
        assertEquals("BAD_REQUEST", invoke("listMedicos", "{\"page\":1000001}").get("code"));
        assertEquals("BAD_REQUEST", invoke("listMedicos", "{\"page\":2.5}").get("code"));
        assertEquals("VALIDATION",
            invoke("listMedicos", "{\"filter\":{\"texto\":\"" + "x".repeat(101) + "\"}}").get("code"));
        assertEquals("BAD_REQUEST", invoke("listMedicos", "{\"pageSize\":1000}").get("code"));
        assertEquals("BAD_REQUEST", invoke("listMedicos", "{\"filter\":\"x\"}").get("code"));
    }

    @Test
    void saveMedicoRoundTripInsertThenUpdate() throws InterruptedException {
        Map<String, Object> saved = data(invoke("saveMedico",
            "{\"id\":0,\"documento\":\" 7001 \",\"nombres\":\"Eva\",\"apellidos\":\"Mora\",\"especialidad\":\"Dermatologia\","
                + "\"telefono\":\"\",\"correo\":\"eva@clinica.co\",\"activo\":true}"));
        long id = (Long) saved.get("id");
        assertTrue(id > 0);
        assertEquals("7001", saved.get("documento"));
        assertNull(saved.get("telefono"));
        assertEquals(13, medicos.size());

        Map<String, Object> fetched = data(invoke("getMedico", "{\"id\":" + id + "}"));
        assertEquals("Eva Mora", fetched.get("nombreCompleto"));

        Map<String, Object> updated = data(invoke("saveMedico",
            "{\"id\":" + id + ",\"documento\":\"7001\",\"nombres\":\"Eva Maria\",\"apellidos\":\"Mora\","
                + "\"especialidad\":\"Dermatologia\",\"activo\":false}"));
        assertEquals("Eva Maria", updated.get("nombres"));
        assertEquals(false, updated.get("activo"));
        assertEquals("Eva Maria", medicos.findById(id).orElseThrow().nombres());

        Map<String, Object> kept = data(invoke("saveMedico",
            "{\"id\":" + id + ",\"documento\":\"7001\",\"nombres\":\"Eva\",\"apellidos\":\"Mora\",\"especialidad\":\"Dermatologia\"}"));
        assertEquals(false, kept.get("activo"));
        assertEquals(false, medicos.findById(id).orElseThrow().activo());

        assertEquals("NOT_FOUND", invoke("getMedico", "{\"id\":999}").get("code"));
        assertEquals("BAD_REQUEST", invoke("getMedico", "{}").get("code"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void saveMedicoValidationEnvelopeCarriesFieldErrors() throws InterruptedException {
        Map<String, Object> envelope = invoke("saveMedico", "{\"documento\":\"1001\",\"correo\":\"bad\"}");
        assertEquals(false, envelope.get("ok"));
        assertEquals("VALIDATION", envelope.get("code"));
        assertEquals("Datos inválidos", envelope.get("message"));
        Map<String, Object> errors = (Map<String, Object>) envelope.get("errors");
        assertEquals("Campo obligatorio", errors.get("nombres"));
        assertEquals("Campo obligatorio", errors.get("apellidos"));
        assertEquals("Campo obligatorio", errors.get("especialidad"));
        assertEquals("Correo electrónico inválido", errors.get("correo"));
        assertEquals("Ya existe un registro con este documento", errors.get("documento"));
    }

    @Test
    void deleteMedicoReportsOutcome() throws InterruptedException {
        assertEquals("DELETED", data(invoke("deleteMedico", "{\"id\":12}")).get("outcome"));
        medicos.markHistory(1);
        assertEquals("DEACTIVATED", data(invoke("deleteMedico", "{\"id\":1}")).get("outcome"));
        assertEquals(false, medicos.findById(1).orElseThrow().activo());
        assertEquals("NOT_FOUND", invoke("deleteMedico", "{\"id\":12}").get("code"));
    }

    @Test
    void listEspecialidades() throws InterruptedException {
        Map<String, Object> envelope = invoke("listEspecialidades", null);
        assertEquals(List.of("Cardiologia", "Pediatria"), envelope.get("data"));
    }

    @Test
    void medicoRoleCannotReadTheMedicoCatalog() throws InterruptedException {
        sessions.set(MEDICO);
        assertEquals("FORBIDDEN", invoke("listMedicos", "{}").get("code"));
        assertEquals("FORBIDDEN", invoke("listEspecialidades", "").get("code"));
    }

    @Test
    void pacienteRoleIsForbiddenEverywhere() throws InterruptedException {
        sessions.set(PACIENTE);
        for (String method : List.of("listMedicos", "getMedico", "saveMedico", "deleteMedico", "listEspecialidades")) {
            Map<String, Object> envelope = invoke(method, "{\"id\":20}");
            assertEquals("FORBIDDEN", envelope.get("code"), method);
            assertEquals(Bridge.FORBIDDEN_MESSAGE, envelope.get("message"));
        }
    }

    @Test
    void noSessionIsForbidden() throws InterruptedException {
        sessions.clear();
        assertEquals("FORBIDDEN", invoke("listMedicos", "{}").get("code"));
    }

    @Test
    void malformedPayloadIsBadRequestNotInternal() throws InterruptedException {
        assertEquals("BAD_REQUEST", invoke("saveMedico", "{bad json").get("code"));
        assertEquals("BAD_REQUEST", invoke("saveMedico", "[1]").get("code"));
        assertEquals("BAD_REQUEST", invoke("getMedico", "{\"id\":\"abc\"}").get("code"));
    }

    private static List<String> names(List<Map<String, Object>> rows) {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            names.add((String) row.get("apellidos"));
        }
        return names;
    }

    @Test
    void paginationMathAtTheLastPage() throws InterruptedException {
        Map<String, Object> page = data(invoke("listMedicos", "{\"page\":3,\"pageSize\":5}"));
        assertEquals(3L, page.get("totalPages"));
        assertEquals(List.of("Apellido11", "Apellido12"), names(items(page)));
        Map<String, Object> beyond = data(invoke("listMedicos", "{\"page\":4,\"pageSize\":5}"));
        assertEquals(0, items(beyond).size());
        assertEquals(12L, beyond.get("total"));
    }

    @Test
    void exportWritesEveryMatchingMedicoNotJustOnePage() throws Exception {
        saver.answer(dir.resolve("medicos.pdf"));
        Map<String, Object> all = data(invoke("exportMedicos", "{\"format\":\"pdf\",\"page\":2,\"pageSize\":5}"));
        assertEquals(12L, all.get("rows"), "paging fields are ignored: the export covers the whole filter");
        assertEquals("medicos_2026-11-04.pdf", saver.last().suggestedFileName());
        assertEquals("Exportación PDF - 12 registro(s)", exportAudit.last().detalle());
        assertEquals(ModuloAuditoria.MEDICO, exportAudit.last().modulo());

        saver.answer(dir.resolve("pediatras.xlsx"));
        Map<String, Object> pediatras = data(invoke("exportMedicos",
            "{\"filter\":{\"especialidad\":\"Pediatria\",\"activo\":true},\"format\":\"xlsx\"}"));
        assertEquals(5L, pediatras.get("rows"));
        List<List<String>> table = ControllerTestSupport.table(dir.resolve("pediatras.xlsx"));
        assertEquals(List.of("#", "Documento", "Nombre completo", "Especialidad", "Teléfono", "Email", "Activo"),
            table.get(0));
        List<String> second = table.stream().filter(r -> r.get(1).equals("1002")).findFirst().orElseThrow();
        assertEquals(List.of("Nombre2 Apellido02", "Pediatria", "", "", "Sí"), second.subList(2, 7));
    }

    @Test
    void exportIsAdminOnlyAndValidatesTheFormat() throws Exception {
        assertEquals("BAD_REQUEST", invoke("exportMedicos", "{\"format\":\"docx\"}").get("code"));
        sessions.set(MEDICO);
        assertEquals("FORBIDDEN", invoke("exportMedicos", "{\"format\":\"xlsx\"}").get("code"));
        assertTrue(saver.requests().isEmpty());
        assertTrue(exportAudit.entries().isEmpty());
    }
}
