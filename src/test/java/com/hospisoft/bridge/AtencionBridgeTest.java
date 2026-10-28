package com.hospisoft.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hospisoft.dao.InMemoryAtencionRepository;
import com.hospisoft.dao.InMemoryCitaRepository;
import com.hospisoft.dao.InMemoryMedicamentoRepository;
import com.hospisoft.dao.InMemoryMedicoRepository;
import com.hospisoft.dao.InMemoryPacienteRepository;
import com.hospisoft.dao.UsuarioRepository;
import com.hospisoft.model.Cita;
import com.hospisoft.model.EstadoCita;
import com.hospisoft.model.Medicamento;
import com.hospisoft.model.Medico;
import com.hospisoft.model.Paciente;
import com.hospisoft.model.Rol;
import com.hospisoft.service.AppZone;
import com.hospisoft.service.AtencionService;
import com.hospisoft.service.AuthService;
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

/** End-to-end attention methods through the async bridge with in-memory repositories. */
class AtencionBridgeTest {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    private static final AppZone ZONE = new AppZone(BOGOTA);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-27T13:00:00Z"), BOGOTA);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);
    private static final SessionContext ADMIN = new SessionContext(1, "admin", Rol.ADMIN, null, "Administrador");
    private static final SessionContext GARCIA = new SessionContext(2, "dr.garcia", Rol.MEDICO, 10L, "Carlos Garcia");
    private static final SessionContext JUAN = new SessionContext(5, "paciente2", Rol.PACIENTE, 21L, "Juan Martinez");
    private static final UsuarioRepository NO_USERS = username -> Optional.empty();

    private final SessionHolder sessions = new SessionHolder();
    private final BlockingQueue<String> scripts = new ArrayBlockingQueue<>(16);
    private InMemoryMedicamentoRepository medicamentos;
    private InMemoryCitaRepository citas;
    private Bridge bridge;
    private int sequence;

    @BeforeEach
    void setUp() {
        InMemoryMedicoRepository medicos = new InMemoryMedicoRepository();
        medicos.add(new Medico(10L, "1001", "Carlos", "Garcia", "Medicina General", null, null, true));
        InMemoryPacienteRepository pacientes = new InMemoryPacienteRepository();
        pacientes.add(new Paciente(21L, "2002", "Juan", "Martinez", LocalDate.of(1985, 11, 2), "M", null, null, null, true));
        pacientes.markAttended(21L, 10L);
        medicamentos = new InMemoryMedicamentoRepository();
        medicamentos.add(new Medicamento(5L, "MED-001", "Acetaminofen", null, "Caja x 20", 150, 30, true));
        medicamentos.add(new Medicamento(6L, "MED-005", "Loratadina", null, "Caja x 10", 1, 15, true));
        citas = new InMemoryCitaRepository(medicos, pacientes);
        citas.add(new Cita(1L, 10L, 21L, ZONE.at(TODAY, LocalTime.of(9, 0)), "Control", EstadoCita.PROGRAMADA, null, null, null));
        InMemoryAtencionRepository atenciones = new InMemoryAtencionRepository(citas, medicos, pacientes, medicamentos);
        bridge = new Bridge(new AuthService(NO_USERS), sessions, StartupStatus::ok, page -> { }, scripts::add);
        AtencionMethods.register(bridge, new AtencionService(atenciones, citas, medicamentos, pacientes,
            sessions::current, CLOCK, ZONE));
        sessions.set(GARCIA);
    }

    @AfterEach
    void tearDown() {
        bridge.shutdown();
    }

    private Map<String, Object> invoke(String method, String payload) throws InterruptedException {
        String requestId = "a" + (++sequence);
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
    private static Map<String, Object> errors(Map<String, Object> envelope) {
        assertEquals("VALIDATION", envelope.get("code"), "expected VALIDATION: " + envelope);
        return (Map<String, Object>) envelope.get("errors");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @Test
    @SuppressWarnings("unchecked")
    void fullRoundTripFromIniciarToHistoria() throws InterruptedException {
        Map<String, Object> started = data(invoke("iniciarAtencion", "{\"idCita\":1}"));
        assertEquals("EN_CURSO", started.get("estado"));
        assertEquals("Juan Martinez", started.get("pacienteNombre"));

        List<Map<String, Object>> catalogo = list(invoke("medicamentosParaRecetar", "{}").get("data"));
        assertEquals(List.of("MED-001", "MED-005"), catalogo.stream().map(m -> m.get("codigo")).toList());
        assertEquals(150L, catalogo.get(0).get("stock"));

        Map<String, Object> closed = data(invoke("finalizarAtencion",
            "{\"idCita\":1,\"diagnostico\":\"Gripe\",\"tratamiento\":\"Reposo\",\"notas\":\"Sin alarma\","
                + "\"observaciones\":\"Atendido\",\"recetas\":["
                + "{\"idMedicamento\":5,\"cantidad\":9,\"dosis\":\"1 c/8h\",\"duracion\":\"3 dias\"},"
                + "{\"idMedicamento\":6,\"cantidad\":5,\"dosis\":\"1 c/24h\",\"duracion\":\"5 dias\","
                + "\"observacionNoDisponible\":\"Sin existencias\"}]}"));
        assertNotNull(closed.get("idHistoria"));
        assertEquals("FINALIZADA", ((Map<String, Object>) closed.get("cita")).get("estado"));
        List<Map<String, Object>> recetas = list(closed.get("recetas"));
        assertEquals(2, recetas.size());
        assertEquals(true, recetas.get(0).get("entregado"));
        assertEquals("MED-001", recetas.get(0).get("codigo"));
        assertEquals("Caja x 20", recetas.get(0).get("presentacion"));
        assertEquals(9L, recetas.get(0).get("cantidad"));
        assertEquals(false, recetas.get(1).get("entregado"));
        assertEquals("Sin existencias", recetas.get(1).get("observacionNoDisponible"));
        assertEquals(141, medicamentos.findById(5).orElseThrow().stock());
        assertEquals(1, medicamentos.findById(6).orElseThrow().stock());

        Map<String, Object> historia = data(invoke("getHistoriaCita", "{\"idCita\":1}"));
        assertEquals("Gripe", historia.get("diagnostico"));
        assertEquals("Reposo", historia.get("tratamiento"));
        assertEquals("Juan Martinez", historia.get("pacienteNombre"));
        assertEquals("Carlos Garcia", historia.get("medicoNombre"));
        assertEquals("Medicina General", historia.get("especialidad"));
        assertNotNull(historia.get("fecha"));
        assertEquals(2, list(historia.get("recetas")).size());

        Map<String, Object> historial = data(invoke("historialPaciente", "{\"idPaciente\":21}"));
        assertEquals(1L, historial.get("total"));
        assertEquals("Gripe", list(historial.get("items")).get(0).get("diagnostico"));
    }

    @Test
    void validationAndConflictEnvelopes() throws InterruptedException {
        Map<String, Object> notStarted = invoke("finalizarAtencion",
            "{\"idCita\":1,\"diagnostico\":\"d\",\"tratamiento\":\"t\"}");
        assertEquals("CONFLICT", notStarted.get("code"));
        assertEquals("La atención no está en curso", notStarted.get("message"));

        invoke("iniciarAtencion", "{\"idCita\":1}");
        Map<String, Object> conflict = invoke("iniciarAtencion", "{\"idCita\":1}");
        assertEquals("CONFLICT", conflict.get("code"));
        assertEquals("La cita ya no está programada", conflict.get("message"));

        Map<String, Object> missingFields = invoke("finalizarAtencion",
            "{\"idCita\":1,\"recetas\":[{\"idMedicamento\":6,\"cantidad\":99,\"dosis\":\"d\",\"duracion\":\"x\"}]}");
        Map<String, Object> errors = errors(missingFields);
        assertEquals("Campo obligatorio", errors.get("diagnostico"));
        assertEquals("Campo obligatorio", errors.get("tratamiento"));

        Map<String, Object> noObservation = invoke("finalizarAtencion",
            "{\"idCita\":1,\"diagnostico\":\"d\",\"tratamiento\":\"t\",\"recetas\":"
                + "[{\"idMedicamento\":6,\"cantidad\":99,\"dosis\":\"d\",\"duracion\":\"x\"}]}");
        assertEquals("Indique por qué no se entrega",
            errors(noObservation).get("recetas[0].observacionNoDisponible"));

        assertEquals("BAD_REQUEST", invoke("finalizarAtencion", "{\"idCita\":1,\"recetas\":\"x\"}").get("code"));
        assertEquals("BAD_REQUEST", invoke("finalizarAtencion", "{\"idCita\":1,\"recetas\":[1]}").get("code"));
        assertEquals("NOT_FOUND", invoke("getHistoriaCita", "{\"idCita\":1}").get("code"));
        assertEquals("BAD_REQUEST", invoke("iniciarAtencion", "{}").get("code"));
    }

    @Test
    void rolesAndScope() throws InterruptedException {
        invoke("iniciarAtencion", "{\"idCita\":1}");
        invoke("finalizarAtencion", "{\"idCita\":1,\"diagnostico\":\"Gripe\",\"tratamiento\":\"Reposo\","
            + "\"recetas\":[{\"idMedicamento\":5,\"cantidad\":1,\"dosis\":\"d\",\"duracion\":\"x\"}]}");

        sessions.set(JUAN);
        Map<String, Object> mias = data(invoke("misRecetas", "{\"page\":1,\"pageSize\":10}"));
        assertEquals(1L, mias.get("total"));
        Map<String, Object> receta = list(mias.get("items")).get(0);
        assertEquals("Acetaminofen", receta.get("nombre"));
        assertNotNull(receta.get("fecha"));
        assertEquals("Gripe", data(invoke("getHistoriaCita", "{\"idCita\":1}")).get("diagnostico"));
        assertEquals("FORBIDDEN", invoke("iniciarAtencion", "{\"idCita\":1}").get("code"));
        assertEquals("FORBIDDEN", invoke("medicamentosParaRecetar", "{}").get("code"));
        assertEquals("NOT_FOUND", invoke("historialPaciente", "{\"idPaciente\":20}").get("code"));

        sessions.set(ADMIN);
        assertEquals("FORBIDDEN", invoke("iniciarAtencion", "{\"idCita\":1}").get("code"));
        assertEquals("FORBIDDEN", invoke("misRecetas", "{}").get("code"));
        assertEquals(1L, data(invoke("historialPaciente", "{\"idPaciente\":21}")).get("total"));
        assertEquals(2, list(invoke("medicamentosParaRecetar", "{}").get("data")).size());

        sessions.clear();
        assertEquals("FORBIDDEN", invoke("getHistoriaCita", "{\"idCita\":1}").get("code"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void cantidadIsValidatedThroughTheBridge() throws InterruptedException {
        invoke("iniciarAtencion", "{\"idCita\":1}");
        String base = "{\"idCita\":1,\"diagnostico\":\"d\",\"tratamiento\":\"t\",\"recetas\":"
            + "[{\"idMedicamento\":5,\"cantidad\":%s,\"dosis\":\"d\",\"duracion\":\"x\"}]}";

        // a numeric string is accepted
        Map<String, Object> ok = data(invoke("finalizarAtencion", String.format(base, "\"5\"")));
        assertEquals(5L, list(ok.get("recetas")).get(0).get("cantidad"));

        // out-of-range values report the range, not the generic integer message
        Map<String, Object> huge = invoke("finalizarAtencion", String.format(base, "1000000000"));
        assertEquals("Debe estar entre 1 y 9999",
            ((Map<String, Object>) huge.get("errors")).get("recetas[0].cantidad"));
        Map<String, Object> negative = invoke("finalizarAtencion", String.format(base, "-1"));
        assertEquals("Debe estar entre 1 y 9999",
            ((Map<String, Object>) negative.get("errors")).get("recetas[0].cantidad"));
        Map<String, Object> fraction = invoke("finalizarAtencion", String.format(base, "2.5"));
        assertEquals("Debe ser un número entero",
            ((Map<String, Object>) fraction.get("errors")).get("recetas[0].cantidad"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void tooManyRecetasAreRejected() throws InterruptedException {
        invoke("iniciarAtencion", "{\"idCita\":1}");
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < 51; i++) {
            lines.append(i == 0 ? "" : ",")
                .append("{\"idMedicamento\":5,\"cantidad\":1,\"dosis\":\"d\",\"duracion\":\"x\"}");
        }
        Map<String, Object> response = invoke("finalizarAtencion",
            "{\"idCita\":1,\"diagnostico\":\"d\",\"tratamiento\":\"t\",\"recetas\":[" + lines + "]}");
        assertEquals("VALIDATION", response.get("code"));
        assertEquals("Máximo 50 medicamentos por atención",
            ((Map<String, Object>) response.get("errors")).get("recetas"));
    }

    @Test
    void misRecetasAndHistorialPaginate() throws InterruptedException {
        invoke("iniciarAtencion", "{\"idCita\":1}");
        invoke("finalizarAtencion", "{\"idCita\":1,\"diagnostico\":\"Gripe\",\"tratamiento\":\"Reposo\",\"recetas\":["
            + "{\"idMedicamento\":5,\"cantidad\":1,\"dosis\":\"a\",\"duracion\":\"x\"},"
            + "{\"idMedicamento\":6,\"cantidad\":1,\"dosis\":\"b\",\"duracion\":\"y\"}]}");

        sessions.set(JUAN);
        Map<String, Object> first = data(invoke("misRecetas", "{\"page\":1,\"pageSize\":1}"));
        assertEquals(2L, first.get("total"));
        assertEquals(2L, first.get("totalPages"));
        assertEquals("a", list(first.get("items")).get(0).get("dosis"), "lines keep their insertion order");
        Map<String, Object> second = data(invoke("misRecetas", "{\"page\":2,\"pageSize\":1}"));
        assertEquals("b", list(second.get("items")).get(0).get("dosis"));

        Map<String, Object> historial = data(invoke("historialPaciente",
            "{\"idPaciente\":21,\"page\":2,\"pageSize\":1}"));
        assertEquals(1L, historial.get("total"));
        assertEquals(0, list(historial.get("items")).size(), "page past the end is empty");
    }

    @Test
    void recetaDtoHasNoFechaOutsideTheOrderList() throws InterruptedException {
        invoke("iniciarAtencion", "{\"idCita\":1}");
        Map<String, Object> closed = data(invoke("finalizarAtencion",
            "{\"idCita\":1,\"diagnostico\":\"d\",\"tratamiento\":\"t\",\"recetas\":"
                + "[{\"idMedicamento\":5,\"cantidad\":1,\"dosis\":\"d\",\"duracion\":\"x\"}]}"));
        assertNull(list(closed.get("recetas")).get(0).get("fecha"));
    }
}
