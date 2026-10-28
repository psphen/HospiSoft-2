package com.hospisoft.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hospisoft.dao.InMemoryMedicoRepository;
import com.hospisoft.dao.InMemoryPacienteRepository;
import com.hospisoft.dao.UsuarioRepository;
import com.hospisoft.model.Medico;
import com.hospisoft.model.Paciente;
import com.hospisoft.model.Rol;
import com.hospisoft.service.AuthService;
import com.hospisoft.service.MedicoService;
import com.hospisoft.service.PacienteService;
import com.hospisoft.service.SessionContext;
import com.hospisoft.service.SessionHolder;
import com.hospisoft.service.StartupStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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

/** End-to-end through the async bridge with in-memory repositories (no database). */
class CrudBridgeTest {

    private static final SessionContext ADMIN = new SessionContext(1, "admin", Rol.ADMIN, null, "Administrador");
    private static final SessionContext MEDICO = new SessionContext(2, "dr.garcia", Rol.MEDICO, 10L, "Carlos Garcia");
    private static final SessionContext PACIENTE = new SessionContext(3, "paciente1", Rol.PACIENTE, 20L, "Maria");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-26T12:00:00Z"), ZoneOffset.UTC);
    private static final UsuarioRepository NO_USERS = username -> Optional.empty();

    /** Delivers resolved envelopes to the test thread. */
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
    private InMemoryPacienteRepository pacientes;
    private Bridge bridge;
    private int sequence;

    @BeforeEach
    void setUp() {
        medicos = new InMemoryMedicoRepository();
        pacientes = new InMemoryPacienteRepository();
        for (int i = 1; i <= 12; i++) {
            medicos.add(new Medico(null, "100" + i, "Nombre" + i, "Apellido" + String.format("%02d", i),
                i % 2 == 0 ? "Pediatria" : "Cardiologia", null, null, i != 12));
        }
        pacientes.add(new Paciente(20L, "2001", "Maria", "Torres", LocalDate.of(1990, 5, 14), "F", null, null, null, true));
        pacientes.add(new Paciente(21L, "2002", "Juan", "Martinez", LocalDate.of(1985, 11, 2), "M", null, null, null, true));
        pacientes.add(new Paciente(22L, "2003", "Ana", "Ramirez", LocalDate.of(2015, 3, 21), "F", null, null, null, true));
        pacientes.markAttended(20L, 10L);

        bridge = new Bridge(new AuthService(NO_USERS), sessions, StartupStatus::ok, page -> { }, sink);
        CrudMethods.register(bridge,
            new MedicoService(medicos, sessions::current),
            new PacienteService(pacientes, sessions::current, CLOCK));
        sessions.set(ADMIN);
    }

    @AfterEach
    void tearDown() {
        bridge.shutdown();
    }

    /** Calls an async method and waits for its envelope. */
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
    void allNineMethodsAreRegistered() {
        assertTrue(bridge.asyncMethodNames().containsAll(Set.of("listMedicos", "getMedico", "saveMedico",
            "deleteMedico", "listEspecialidades", "listPacientes", "getPaciente", "savePaciente", "deletePaciente")));
    }

    // ------------------------------------------------------------ medicos

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
        assertEquals(1L, filtered.get("total")); // 10 is Pediatria+active and matches "apellido1"; 12 is inactive
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

        // activo omitted on update: the stored value (false) is kept
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

    // ------------------------------------------------------------ pacientes

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
        assertEquals("FORBIDDEN", invoke("listMedicos", "{}").get("code"));
        assertEquals("FORBIDDEN", invoke("listEspecialidades", "").get("code"));
    }

    @Test
    void pacienteRoleIsForbiddenEverywhere() throws InterruptedException {
        sessions.set(PACIENTE);
        for (String method : List.of("listMedicos", "getMedico", "saveMedico", "deleteMedico", "listEspecialidades",
            "listPacientes", "getPaciente", "savePaciente", "deletePaciente")) {
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
}
