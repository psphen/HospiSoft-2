package com.hospisoft.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hospisoft.dao.InMemoryAtencionRepository;
import com.hospisoft.dao.InMemoryCitaRepository;
import com.hospisoft.dao.InMemoryDashboardRepository;
import com.hospisoft.dao.InMemoryMedicamentoRepository;
import com.hospisoft.dao.InMemoryMedicoRepository;
import com.hospisoft.dao.InMemoryPacienteRepository;
import com.hospisoft.dao.UsuarioRepository;
import com.hospisoft.db.DatabaseUnavailableException;
import com.hospisoft.model.Cita;
import com.hospisoft.model.EstadoCita;
import com.hospisoft.model.Medicamento;
import com.hospisoft.model.Medico;
import com.hospisoft.model.Paciente;
import com.hospisoft.model.Rol;
import com.hospisoft.service.AppZone;
import com.hospisoft.service.AuthService;
import com.hospisoft.service.DashboardService;
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

/** The dashboard answers the whole panel in one call, already scoped to the session's role. */
class DashboardBridgeTest {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    private static final AppZone ZONE = new AppZone(BOGOTA);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-27T13:00:00Z"), BOGOTA);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);
    private static final SessionContext ADMIN = new SessionContext(1, "admin", Rol.ADMIN, null, "Administrador");
    private static final SessionContext GARCIA = new SessionContext(2, "dr.garcia", Rol.MEDICO, 10L, "Carlos Garcia");
    private static final UsuarioRepository NO_USERS = username -> Optional.empty();

    private final SessionHolder sessions = new SessionHolder();
    private final BlockingQueue<String> scripts = new ArrayBlockingQueue<>(8);
    private Bridge bridge;
    private int sequence;

    @BeforeEach
    void setUp() {
        InMemoryMedicoRepository medicos = new InMemoryMedicoRepository();
        medicos.add(new Medico(10L, "1001", "Carlos", "Garcia", "Medicina General", null, null, true));
        InMemoryPacienteRepository pacientes = new InMemoryPacienteRepository();
        pacientes.add(new Paciente(21L, "2002", "Juan", "Martinez", LocalDate.of(1985, 11, 2), "M", null, null, null, true));
        InMemoryMedicamentoRepository medicamentos = new InMemoryMedicamentoRepository();
        medicamentos.add(new Medicamento(5L, "MED-001", "Acetaminofen", null, "Caja", 150, 30, true));
        InMemoryCitaRepository citas = new InMemoryCitaRepository(medicos, pacientes);
        citas.add(new Cita(1L, 10L, 21L, ZONE.at(TODAY, LocalTime.of(7, 0)), "Gripe", EstadoCita.FINALIZADA,
            ZONE.at(TODAY, LocalTime.of(7, 0)), ZONE.at(TODAY, LocalTime.of(7, 30)), null));
        citas.add(new Cita(2L, 10L, 21L, ZONE.at(TODAY, LocalTime.of(9, 0)), "Control", EstadoCita.PROGRAMADA,
            null, null, null));
        InMemoryAtencionRepository atenciones = new InMemoryAtencionRepository(citas, medicos, pacientes, medicamentos);
        bridge = new Bridge(new AuthService(NO_USERS), sessions, StartupStatus::ok, page -> { }, scripts::add);
        DashboardMethods.register(bridge, new DashboardService(
            new InMemoryDashboardRepository(citas, medicos, pacientes, medicamentos, atenciones),
            medicamentos, sessions::current, CLOCK, ZONE));
        sessions.set(ADMIN);
    }

    @AfterEach
    void tearDown() {
        bridge.shutdown();
    }

    private Map<String, Object> invoke(String method, String payload) throws InterruptedException {
        String requestId = "d" + (++sequence);
        assertEquals(true, Json.parseObject(bridge.call(requestId, method, payload)).get("ok"));
        String script = scripts.poll(10, TimeUnit.SECONDS);
        assertTrue(script != null, "no response for " + method);
        String prefix = "if (window.__hospisoft) { window.__hospisoft.resolve(";
        String args = script.substring(prefix.length(), script.length() - "); }".length());
        @SuppressWarnings("unchecked")
        List<Object> parsed = (List<Object>) Json.parse("[" + args + "]");
        return Json.parseObject((String) parsed.get(1));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> envelope) {
        assertEquals(true, envelope.get("ok"), "expected success: " + envelope);
        return (Map<String, Object>) envelope.get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @Test
    @SuppressWarnings("unchecked")
    void thePayloadCarriesLabelsCountsAndUpcomingCitas() throws InterruptedException {
        Map<String, Object> data = data(invoke("dashboard", "{}"));
        assertEquals("ADMIN", data.get("rol"));

        List<Map<String, Object>> kpis = list(data.get("kpis"));
        assertEquals(4, kpis.size());
        assertEquals(List.of("medicos", "pacientes", "citas", "medicamentos"),
            kpis.stream().map(k -> k.get("clave")).toList());
        assertEquals("Médicos", kpis.get(0).get("etiqueta"));
        assertEquals(1L, kpis.get(0).get("valor"));
        assertEquals("activos", kpis.get(0).get("detalle"));

        Map<String, Object> resumen = (Map<String, Object>) data.get("resumenHoy");
        assertEquals(2L, resumen.get("total"));
        assertEquals(1L, resumen.get("atendidas"));
        assertEquals(0L, resumen.get("enCurso"));
        assertEquals(1L, resumen.get("pendientes"));
        assertEquals(0L, resumen.get("canceladas"));
        assertEquals(50.0, data.get("atendidasPct"));
        assertEquals(30.0, data.get("duracionPromedioMin"));

        List<Map<String, Object>> proximas = list(data.get("proximasCitas"));
        assertEquals(1, proximas.size());
        assertEquals("Juan Martinez", proximas.get(0).get("pacienteNombre"));
        assertEquals("Carlos Garcia", proximas.get(0).get("medicoNombre"));
        assertEquals("09:00", proximas.get(0).get("hora"));
        assertEquals("PROGRAMADA", proximas.get(0).get("estado"));
    }

    @Test
    void theUpcomingListCarriesOnlyWhatThePanelPaints() throws InterruptedException {
        Map<String, Object> data = data(invoke("dashboard", "{}"));
        Map<String, Object> cita = list(data.get("proximasCitas")).get(0);
        assertEquals(List.of("id", "medicoNombre", "pacienteNombre", "especialidad", "fecha", "hora", "estado"),
            List.copyOf(cita.keySet()), "no clinical detail leaks into the panel");
        assertEquals(2L, cita.get("id"));
        assertEquals("2026-10-27", cita.get("fecha"));
        assertEquals("Medicina General", cita.get("especialidad"));
    }

    @Test
    void aDatabaseOutageAnswersDbUnavailable() throws InterruptedException {
        Bridge failing = new Bridge(new AuthService(NO_USERS), sessions, StartupStatus::ok, page -> { }, scripts::add);
        try {
            DashboardMethods.register(failing, new DashboardService(
                (scope, dayStart, dayEnd, now, limit) -> {
                    throw new DatabaseUnavailableException("pool down", null);
                },
                new InMemoryMedicamentoRepository(), sessions::current, CLOCK, ZONE));
            scripts.clear();
            assertEquals(true, Json.parseObject(failing.call("fail1", "dashboard", "{}")).get("ok"));
            String script = scripts.poll(10, TimeUnit.SECONDS);
            assertTrue(script != null, "no response");
            assertTrue(script.contains("DB_UNAVAILABLE"), script);
        } finally {
            failing.shutdown();
        }
    }

    @Test
    void labelsFollowTheRole() throws InterruptedException {
        sessions.set(GARCIA);
        Map<String, Object> data = data(invoke("dashboard", "{}"));
        assertEquals("MEDICO", data.get("rol"));
        assertEquals("Mis pacientes", list(data.get("kpis")).get(0).get("etiqueta"));
        assertEquals(1L, list(data.get("kpis")).get(0).get("valor"));
    }

    @Test
    void withoutASessionTheCallIsForbidden() throws InterruptedException {
        sessions.clear();
        assertEquals("FORBIDDEN", invoke("dashboard", "{}").get("code"));
    }

    @Test
    void nullAveragesTravelAsNull() throws InterruptedException {
        sessions.set(new SessionContext(7, "paciente.vacio", Rol.PACIENTE, 99L, "Sin datos"));
        Map<String, Object> data = data(invoke("dashboard", "{}"));
        assertNull(data.get("atendidasPct"));
        assertNull(data.get("duracionPromedioMin"));
        assertTrue(data.containsKey("atendidasPct"));
        assertEquals(List.of(), data.get("proximasCitas"));
    }
}
