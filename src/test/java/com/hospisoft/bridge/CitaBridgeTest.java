package com.hospisoft.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hospisoft.dao.InMemoryCitaRepository;
import com.hospisoft.dao.InMemoryMedicoRepository;
import com.hospisoft.dao.InMemoryPacienteRepository;
import com.hospisoft.dao.UsuarioRepository;
import com.hospisoft.model.Cita;
import com.hospisoft.model.EstadoCita;
import com.hospisoft.model.Medico;
import com.hospisoft.model.Paciente;
import com.hospisoft.model.Rol;
import com.hospisoft.service.AppZone;
import com.hospisoft.service.AuthService;
import com.hospisoft.service.CitaService;
import com.hospisoft.service.SessionContext;
import com.hospisoft.service.SessionHolder;
import com.hospisoft.service.StartupStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** End-to-end cita methods through the async bridge with in-memory repositories (fixed clock and zone). */
class CitaBridgeTest {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    private static final AppZone ZONE = new AppZone(BOGOTA);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-27T13:00:00Z"), BOGOTA); // 08:00 local
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);
    private static final SessionContext ADMIN = new SessionContext(1, "admin", Rol.ADMIN, null, "Administrador");
    private static final SessionContext GARCIA = new SessionContext(2, "dr.garcia", Rol.MEDICO, 10L, "Carlos Garcia");
    private static final SessionContext MARIA = new SessionContext(3, "paciente1", Rol.PACIENTE, 20L, "Maria Torres");
    private static final UsuarioRepository NO_USERS = username -> Optional.empty();

    private final SessionHolder sessions = new SessionHolder();
    private final BlockingQueue<String> scripts = new ArrayBlockingQueue<>(16);
    private InMemoryCitaRepository citas;
    private Bridge bridge;
    private int sequence;

    @BeforeEach
    void setUp() {
        InMemoryMedicoRepository medicos = new InMemoryMedicoRepository();
        medicos.add(new Medico(10L, "1001", "Carlos", "Garcia", "Medicina General", null, null, true));
        medicos.add(new Medico(11L, "1002", "Laura", "Lopez", "Pediatria", null, null, true));
        InMemoryPacienteRepository pacientes = new InMemoryPacienteRepository();
        pacientes.add(new Paciente(20L, "2001", "Maria", "Torres", LocalDate.of(1990, 5, 14), "F", null, null, null, true));
        pacientes.add(new Paciente(21L, "2002", "Juan", "Martinez", LocalDate.of(1985, 11, 2), "M", null, null, null, true));
        citas = new InMemoryCitaRepository(medicos, pacientes);
        citas.add(new Cita(1L, 10L, 20L, ZONE.at(TODAY, LocalTime.of(9, 0)), "Control", EstadoCita.PROGRAMADA, null, null, null));
        citas.add(new Cita(2L, 11L, 21L, ZONE.at(TODAY.plusDays(1), LocalTime.of(10, 0)), "Vacuna", EstadoCita.PROGRAMADA, null, null, null));
        bridge = new Bridge(new AuthService(NO_USERS), sessions, StartupStatus::ok, page -> { }, scripts::add);
        CitaMethods.register(bridge, new CitaService(citas, medicos, pacientes, sessions::current, CLOCK, ZONE));
        sessions.set(ADMIN);
    }

    @AfterEach
    void tearDown() {
        bridge.shutdown();
    }

    private Map<String, Object> invoke(String method, String payload) throws InterruptedException {
        String requestId = "c" + (++sequence);
        assertEquals(true, Json.parseObject(bridge.call(requestId, method, payload)).get("ok"));
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> errors(Map<String, Object> envelope) {
        assertEquals("VALIDATION", envelope.get("code"), "expected VALIDATION: " + envelope);
        return (Map<String, Object>) envelope.get("errors");
    }

    @Test
    void listAndDtoShape() throws InterruptedException {
        Map<String, Object> page = data(invoke("listCitas", "{\"filter\":{\"rango\":\"hoy_y_futuras\"}}"));
        assertEquals(2L, page.get("total"));
        Map<String, Object> first = items(page).get(0);
        assertEquals(1L, first.get("id"));
        assertEquals("Carlos Garcia", first.get("medicoNombre"));
        assertEquals("Medicina General", first.get("especialidad"));
        assertEquals("Maria Torres", first.get("pacienteNombre"));
        assertEquals("2001", first.get("pacienteDocumento"));
        assertEquals("2026-10-27", first.get("fecha"));
        assertEquals("09:00", first.get("hora"));
        assertEquals("2026-10-27T09:00:00-05:00", first.get("fechaHora"));
        assertEquals("PROGRAMADA", first.get("estado"));
        assertNull(first.get("horaInicioReal"));

        assertEquals(1L, data(invoke("listCitas", "{\"filter\":{\"fecha\":\"2026-10-28\"}}")).get("total"));
        assertEquals(1L, data(invoke("listCitas", "{\"filter\":{\"idMedico\":11,\"estado\":\"programada\"}}")).get("total"));
        assertEquals("BAD_REQUEST", invoke("listCitas", "{\"filter\":{\"estado\":\"x\"}}").get("code"));
        assertEquals("BAD_REQUEST", invoke("listCitas", "{\"filter\":{\"rango\":\"x\"}}").get("code"));
        assertEquals("BAD_REQUEST", invoke("listCitas", "{\"filter\":{\"fecha\":\"27/10\"}}").get("code"));
    }

    @Test
    void saveCreateRescheduleCancelAndValidationEnvelopes() throws InterruptedException {
        Map<String, Object> created = data(invoke("saveCita",
            "{\"id\":0,\"idMedico\":10,\"idPaciente\":21,\"fecha\":\"2026-10-27\",\"hora\":\"10:00\",\"motivo\":\"Nueva\"}"));
        long id = (Long) created.get("id");
        assertEquals("PROGRAMADA", created.get("estado"));
        assertEquals("10:00", created.get("hora"));

        Map<String, Object> overlap = invoke("saveCita",
            "{\"idMedico\":10,\"idPaciente\":21,\"fecha\":\"2026-10-27\",\"hora\":\"09:15\",\"motivo\":\"x\"}");
        assertEquals("El médico ya tiene una cita en ese horario", errors(overlap).get("hora"));
        Map<String, Object> past = invoke("saveCita",
            "{\"idMedico\":11,\"idPaciente\":21,\"fecha\":\"2026-10-27\",\"hora\":\"07:00\",\"motivo\":\"x\"}");
        assertEquals("La cita no puede estar en el pasado", errors(past).get("fecha"));
        Map<String, Object> pacienteOverlap = invoke("saveCita",
            "{\"idMedico\":11,\"idPaciente\":20,\"fecha\":\"2026-10-27\",\"hora\":\"09:00\",\"motivo\":\"x\"}");
        assertEquals("El paciente ya tiene una cita en ese horario", errors(pacienteOverlap).get("hora"));

        Map<String, Object> moved = data(invoke("saveCita",
            "{\"id\":" + id + ",\"idMedico\":10,\"idPaciente\":21,\"fecha\":\"2026-10-27\",\"hora\":\"11:00\",\"motivo\":\"Movida\"}"));
        assertEquals("11:00", moved.get("hora"));
        assertEquals("Movida", moved.get("motivo"));

        Map<String, Object> cancelled = data(invoke("cancelarCita", "{\"id\":" + id + "}"));
        assertEquals("CANCELADA", cancelled.get("estado"));
        Map<String, Object> again = invoke("cancelarCita", "{\"id\":" + id + "}");
        assertEquals("Solo se pueden modificar citas programadas", errors(again).get("estado"));
        assertEquals("NOT_FOUND", invoke("cancelarCita", "{\"id\":999}").get("code"));
        assertEquals("BAD_REQUEST", invoke("saveCita", "{\"idMedico\":\"abc\"}").get("code"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void agendaAndSelects() throws InterruptedException {
        Map<String, Object> agenda = data(invoke("agendaMedico", "{\"idMedico\":10,\"fecha\":\"2026-10-27\"}"));
        assertEquals(10L, agenda.get("idMedico"));
        assertEquals("2026-10-27", agenda.get("fecha"));
        List<Map<String, Object>> slots = (List<Map<String, Object>>) agenda.get("slots");
        assertEquals(24, slots.size());
        assertEquals("07:00", slots.get(0).get("hora"));
        assertEquals(List.of(), slots.get(0).get("citas"));
        assertEquals("09:00", slots.get(4).get("hora"));
        List<Map<String, Object>> nine = (List<Map<String, Object>>) slots.get(4).get("citas");
        assertEquals(1, nine.size());
        assertEquals("Maria Torres", nine.get(0).get("pacienteNombre"));
        assertEquals("VALIDATION", invoke("agendaMedico", "{}").get("code"));
        assertEquals("NOT_FOUND", invoke("agendaMedico", "{\"idMedico\":999}").get("code"));

        List<Map<String, Object>> medicos = (List<Map<String, Object>>) invoke("listMedicosSelect", "").get("data");
        assertEquals(List.of("Garcia", "Lopez"), medicos.stream().map(m -> m.get("apellidos")).toList());
        List<Map<String, Object>> pacientes = (List<Map<String, Object>>) invoke("listPacientesSelect", "").get("data");
        assertEquals("2002", pacientes.get(0).get("documento"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rolesAndScope() throws InterruptedException {
        sessions.set(GARCIA);
        assertEquals(1L, data(invoke("listCitas", "{}")).get("total"));
        assertEquals("NOT_FOUND", invoke("getCita", "{\"id\":2}").get("code"));
        assertEquals("FORBIDDEN", invoke("saveCita", "{\"idMedico\":10}").get("code"));
        assertEquals("FORBIDDEN", invoke("cancelarCita", "{\"id\":1}").get("code"));
        assertEquals("FORBIDDEN", invoke("listMedicosSelect", "").get("code"));
        Map<String, Object> own = data(invoke("agendaMedico", "{}"));
        assertEquals(10L, own.get("idMedico"));
        assertEquals("2026-10-27", own.get("fecha"));
        assertEquals("FORBIDDEN", invoke("agendaMedico", "{\"idMedico\":11}").get("code"));

        sessions.set(MARIA);
        assertEquals(1L, data(invoke("listCitas", "{}")).get("total"));
        assertEquals("Control", data(invoke("getCita", "{\"id\":1}")).get("motivo"));
        assertEquals("FORBIDDEN", invoke("agendaMedico", "{}").get("code"));
        sessions.clear();
        assertEquals("FORBIDDEN", invoke("listCitas", "{}").get("code"));
    }
}
