package com.hospisoft.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hospisoft.bridge.Bridge;
import com.hospisoft.bridge.Json;
import com.hospisoft.dao.InMemoryPacienteRepository;
import com.hospisoft.dao.UsuarioRepository;
import com.hospisoft.export.FakeFileSaver;
import com.hospisoft.model.AccionAuditoria;
import com.hospisoft.model.ModuloAuditoria;
import com.hospisoft.model.Paciente;
import com.hospisoft.model.Rol;
import com.hospisoft.service.AuditLog;
import com.hospisoft.service.AuthService;
import com.hospisoft.service.ExportService;
import com.hospisoft.service.PacienteService;
import com.hospisoft.service.RecordingAuditLog;
import com.hospisoft.service.SessionContext;
import com.hospisoft.service.SessionHolder;
import com.hospisoft.service.StartupStatus;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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

class PacienteControllerTest {

    private static final SessionContext ADMIN = new SessionContext(1, "admin", Rol.ADMIN, null, "Administrador");
    private static final SessionContext MEDICO = new SessionContext(2, "dr.garcia", Rol.MEDICO, 10L, "Carlos Garcia");
    private static final SessionContext PACIENTE = new SessionContext(3, "paciente1", Rol.PACIENTE, 20L, "Maria");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-26T12:00:00Z"), ZoneOffset.UTC);
    private static final UsuarioRepository NO_USERS = username -> Optional.empty();

    private final SessionHolder sessions = new SessionHolder();
    private final BlockingQueue<String> scripts = new ArrayBlockingQueue<>(16);
    private InMemoryPacienteRepository pacientes;
    private PacienteService service;
    private FakeFileSaver saver;
    private RecordingAuditLog exportAudit;
    private ExportService exports;
    private Bridge bridge;
    @TempDir
    Path dir;
    private int sequence;

    @BeforeEach
    void setUp() {
        pacientes = new InMemoryPacienteRepository();
        pacientes.add(new Paciente(20L, "2001", "Maria", "Torres", LocalDate.of(1990, 5, 14), "F", null, null, null, true));
        pacientes.add(new Paciente(21L, "2002", "Juan", "Martinez", LocalDate.of(1985, 11, 2), "M", null, null, null, true));
        pacientes.add(new Paciente(22L, "2003", "Ana", "Ramirez", LocalDate.of(2015, 3, 21), "F", null, null, null, true));
        pacientes.markAttended(20L, 10L);

        bridge = new Bridge(new AuthService(NO_USERS), sessions, StartupStatus::ok, page -> { }, scripts::add);
        service = new PacienteService(pacientes, sessions::current, CLOCK, AuditLog.NONE);
        saver = new FakeFileSaver(null);
        exportAudit = new RecordingAuditLog();
        exports = ControllerTestSupport.exports(saver, exportAudit, sessions);
        new PacienteController(service, exports).register(bridge);
        sessions.set(ADMIN);
    }

    @AfterEach
    void tearDown() {
        bridge.shutdown();
    }

    private Map<String, Object> invoke(String method, String payload) throws InterruptedException {
        String requestId = "p" + (++sequence);
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
    void registersExactlyThePacienteMethods() {
        assertEquals(Set.of("listPacientes", "getPaciente", "savePaciente", "deletePaciente", "exportPacientes"),
            ControllerTestSupport.registeredNames(new PacienteController(service, exports)));
    }

    @Test
    void pacienteRoundTripWithDatesAndAge() throws InterruptedException {
        Map<String, Object> list = data(invoke("listPacientes", "{\"filter\":{\"sexo\":\"F\"}}"));
        assertEquals(2L, list.get("total"));
        Map<String, Object> ana = items(list).get(0);
        assertEquals("Ramirez", ana.get("apellidos"));
        assertEquals("2015-03-21", ana.get("fechaNacimiento"));
        assertEquals(11L, ana.get("edad"));

        Map<String, Object> saved = data(invoke("savePaciente",
            "{\"documento\":\"3001\",\"nombres\":\"Luis\",\"apellidos\":\"Perez\",\"fechaNacimiento\":\"2000-02-29\","
                + "\"sexo\":\"m\",\"direccion\":\" Calle 9 \"}"));
        assertEquals("M", saved.get("sexo"));
        assertEquals("Calle 9", saved.get("direccion"));
        assertEquals(26L, saved.get("edad"));
        assertEquals(true, saved.get("activo"));

        Map<String, Object> badDate = invoke("savePaciente",
            "{\"documento\":\"3002\",\"nombres\":\"A\",\"apellidos\":\"B\",\"fechaNacimiento\":\"31/12/2000\",\"sexo\":\"M\"}");
        assertEquals("BAD_REQUEST", badDate.get("code"));

        Map<String, Object> future = invoke("savePaciente",
            "{\"documento\":\"3002\",\"nombres\":\"A\",\"apellidos\":\"B\",\"fechaNacimiento\":\"2030-01-01\",\"sexo\":\"M\"}");
        assertEquals("VALIDATION", future.get("code"));

        assertEquals("DEACTIVATED", data(invoke("deletePaciente", "{\"id\":20}")).get("outcome"));
        assertEquals("DELETED", data(invoke("deletePaciente", "{\"id\":22}")).get("outcome"));
    }

    @Test
    void medicoSeesOnlyAttendedPatientsAndCannotWrite() throws InterruptedException {
        sessions.set(MEDICO);
        Map<String, Object> list = data(invoke("listPacientes", "{}"));
        assertEquals(1L, list.get("total"));
        assertEquals("Torres", items(list).get(0).get("apellidos"));
        assertEquals("Maria Torres", data(invoke("getPaciente", "{\"id\":20}")).get("nombreCompleto"));
        assertEquals("NOT_FOUND", invoke("getPaciente", "{\"id\":21}").get("code"), "out of scope must look missing");
        assertEquals("NOT_FOUND", invoke("getPaciente", "{\"id\":999}").get("code"));
        assertEquals("FORBIDDEN", invoke("savePaciente", "{\"documento\":\"x\"}").get("code"));
        assertEquals("FORBIDDEN", invoke("deletePaciente", "{\"id\":20}").get("code"));
    }

    @Test
    void pacienteRoleIsForbiddenEverywhere() throws InterruptedException {
        sessions.set(PACIENTE);
        for (String method : List.of("listPacientes", "getPaciente", "savePaciente", "deletePaciente")) {
            Map<String, Object> envelope = invoke(method, "{\"id\":20}");
            assertEquals("FORBIDDEN", envelope.get("code"), method);
            assertEquals(Bridge.FORBIDDEN_MESSAGE, envelope.get("message"));
        }
    }

    @Test
    void exportWritesTheFilteredListWithReadableValues() throws Exception {
        saver.answer(dir.resolve("pacientes.xlsx"));
        Map<String, Object> result = data(invoke("exportPacientes", "{\"filter\":{\"sexo\":\"F\"},\"format\":\"xlsx\"}"));
        assertEquals(Map.of("saved", true, "fileName", "pacientes.xlsx", "rows", 2L), result);
        assertEquals("pacientes_2026-11-04.xlsx", saver.last().suggestedFileName());

        List<List<String>> table = ControllerTestSupport.table(dir.resolve("pacientes.xlsx"));
        assertEquals(List.of("#", "Documento", "Nombre completo", "Edad", "Género", "Teléfono", "Email", "Activo"),
            table.get(0));
        assertEquals(3, table.size(), "header plus the two women");
        assertTrue(table.contains(List.of("20", "2001", "Maria Torres", "36", "Femenino", "", "", "Sí")), table.toString());

        RecordingAuditLog.Entry entry = exportAudit.only();
        assertEquals(AccionAuditoria.EXPORTAR, entry.accion());
        assertEquals(ModuloAuditoria.PACIENTE, entry.modulo());
        assertEquals("Exportación Excel - 2 registro(s)", entry.detalle());
    }

    @Test
    void exportKeepsTheMedicoScopeAndHandlesCancel() throws Exception {
        sessions.set(MEDICO);
        saver.answer(dir.resolve("mios.pdf"));
        assertEquals(1L, data(invoke("exportPacientes", "{\"format\":\"PDF\"}")).get("rows"),
            "a medico exports only the patients it attended");

        saver.answer(null);
        assertEquals(Map.of("saved", false), data(invoke("exportPacientes", "{\"format\":\"pdf\"}")));
        assertEquals(1, exportAudit.entries().size(), "a cancelled dialog is not audited");
    }

    @Test
    void exportRejectsBadFormatsAndForbiddenRolesBeforeTheDialog() throws Exception {
        assertEquals("BAD_REQUEST", invoke("exportPacientes", "{\"format\":\"csv\"}").get("code"));
        assertEquals("BAD_REQUEST", invoke("exportPacientes", "{}").get("code"));
        sessions.set(PACIENTE);
        assertEquals("FORBIDDEN", invoke("exportPacientes", "{\"format\":\"xlsx\"}").get("code"));
        assertTrue(saver.requests().isEmpty(), "the save dialog never opened");
    }

    @Test
    void aFileThatCannotBeWrittenIsAnExportFailedEnvelope() throws Exception {
        saver.answer(dir.resolve("no-existe").resolve("pacientes.xlsx"));
        Map<String, Object> envelope = invoke("exportPacientes", "{\"format\":\"xlsx\"}");
        assertEquals("EXPORT_FAILED", envelope.get("code"));
        assertEquals(com.hospisoft.service.ExportFailedException.WRITE_FAILED, envelope.get("message"));
        assertTrue(exportAudit.entries().isEmpty(), "nothing was exported");
    }
}
