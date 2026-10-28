package com.hospisoft.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hospisoft.dao.InMemoryAtencionRepository;
import com.hospisoft.dao.InMemoryCitaRepository;
import com.hospisoft.dao.InMemoryDashboardRepository;
import com.hospisoft.dao.InMemoryMedicamentoRepository;
import com.hospisoft.dao.InMemoryMedicoRepository;
import com.hospisoft.dao.InMemoryPacienteRepository;
import com.hospisoft.model.Cita;
import com.hospisoft.model.EstadoCita;
import com.hospisoft.model.Medicamento;
import com.hospisoft.model.Medico;
import com.hospisoft.model.Paciente;
import com.hospisoft.model.Rol;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DashboardServiceTest {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    private static final AppZone ZONE = new AppZone(BOGOTA);
    /** 2026-10-27 08:00 local. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-27T13:00:00Z"), BOGOTA);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);

    private static final SessionContext ADMIN = new SessionContext(1, "admin", Rol.ADMIN, null, "Administrador");
    private static final SessionContext GARCIA = new SessionContext(2, "dr.garcia", Rol.MEDICO, 10L, "Carlos Garcia");
    private static final SessionContext JUAN = new SessionContext(5, "paciente2", Rol.PACIENTE, 21L, "Juan Martinez");

    private InMemoryMedicoRepository medicos;
    private InMemoryPacienteRepository pacientes;
    private InMemoryMedicamentoRepository medicamentos;
    private InMemoryCitaRepository citas;
    private InMemoryAtencionRepository atenciones;
    private SessionContext current;
    private DashboardService service;

    private static java.time.OffsetDateTime at(LocalDate date, int hour, int minute) {
        return ZONE.at(date, LocalTime.of(hour, minute));
    }

    @BeforeEach
    void setUp() {
        medicos = new InMemoryMedicoRepository();
        medicos.add(new Medico(10L, "1001", "Carlos", "Garcia", "Medicina General", null, null, true));
        medicos.add(new Medico(11L, "1002", "Laura", "Lopez", "Pediatria", null, null, true));
        medicos.add(new Medico(12L, "1003", "Jorge", "Ruiz", "Cardiologia", null, null, false));
        pacientes = new InMemoryPacienteRepository();
        pacientes.add(new Paciente(20L, "2001", "Maria", "Torres", LocalDate.of(1990, 5, 14), "F", null, null, null, true));
        pacientes.add(new Paciente(21L, "2002", "Juan", "Martinez", LocalDate.of(1985, 11, 2), "M", null, null, null, true));
        pacientes.add(new Paciente(22L, "2003", "Ana", "Ramirez", LocalDate.of(2015, 3, 21), "F", null, null, null, false));
        medicamentos = new InMemoryMedicamentoRepository();
        medicamentos.add(new Medicamento(5L, "MED-001", "Acetaminofen", null, "Caja", 150, 30, true));
        medicamentos.add(new Medicamento(6L, "MED-005", "Loratadina", null, "Caja", 2, 15, true));
        medicamentos.add(new Medicamento(7L, "MED-010", "Diclofenaco", null, "Tubo", 50, 10, false));
        citas = new InMemoryCitaRepository(medicos, pacientes);
        // today: garcia 07:00 FINALIZADA (20 min), garcia 09:00 PROGRAMADA, garcia 16:00 CANCELADA,
        //        lopez 10:00 PROGRAMADA; tomorrow: garcia 09:00 PROGRAMADA
        citas.add(new Cita(1L, 10L, 21L, at(TODAY, 7, 0), "Gripe", EstadoCita.FINALIZADA,
            at(TODAY, 7, 0), at(TODAY, 7, 20), null));
        citas.add(new Cita(2L, 10L, 20L, at(TODAY, 9, 0), "Control", EstadoCita.PROGRAMADA, null, null, null));
        citas.add(new Cita(3L, 10L, 21L, at(TODAY, 16, 0), "Cancelada", EstadoCita.CANCELADA, null, null, null));
        citas.add(new Cita(4L, 11L, 21L, at(TODAY, 10, 0), "Vacuna", EstadoCita.PROGRAMADA, null, null, null));
        citas.add(new Cita(5L, 10L, 21L, at(TODAY.plusDays(1), 9, 0), "Control", EstadoCita.PROGRAMADA, null, null, null));
        atenciones = new InMemoryAtencionRepository(citas, medicos, pacientes, medicamentos);
        current = ADMIN;
        service = new DashboardService(
            new InMemoryDashboardRepository(citas, medicos, pacientes, medicamentos, atenciones),
            medicamentos, () -> Optional.ofNullable(current), CLOCK, ZONE);
    }

    @Test
    void adminSeesTheWholeHospital() {
        DashboardView view = service.load();
        assertEquals(Rol.ADMIN, view.rol());
        assertEquals(List.of("Médicos", "Pacientes", "Citas", "Medicamentos"),
            view.kpis().stream().map(DashboardView.Kpi::etiqueta).toList());
        assertEquals(List.of(2L, 2L, 4L, 2L), view.kpis().stream().map(DashboardView.Kpi::valor).toList());
        assertEquals(List.of("activos", "activos", "hoy", "activos"),
            view.kpis().stream().map(DashboardView.Kpi::detalle).toList());
        assertEquals(List.of("medicos", "pacientes", "citas", "medicamentos"),
            view.kpis().stream().map(DashboardView.Kpi::clave).toList(), "the card slots are stable");

        assertEquals(4, view.resumenHoy().total());
        assertEquals(1, view.resumenHoy().atendidas());
        assertEquals(0, view.resumenHoy().enCurso());
        assertEquals(2, view.resumenHoy().pendientes());
        assertEquals(1, view.resumenHoy().canceladas());
        // 1 of the 3 citas that were not cancelled, never 1 of 4
        assertEquals(100.0 / 3, view.atendidasPct(), 1e-9);
        assertEquals(20.0, view.duracionPromedioMin());
        // upcoming: today 09:00 and 10:00 plus tomorrow 09:00; the cancelled one never shows
        assertEquals(List.of(2L, 4L, 5L), view.proximasCitas().stream().map(c -> c.cita().id()).toList());
    }

    @Test
    void medicoOnlySeesItsOwnNumbers() {
        current = GARCIA;
        DashboardView view = service.load();
        assertEquals(Rol.MEDICO, view.rol());
        assertEquals(List.of("Mis pacientes", "Citas de hoy", "Citas pendientes", "Medicamentos"),
            view.kpis().stream().map(DashboardView.Kpi::etiqueta).toList());
        // two distinct patients with a non-cancelled cita, 3 citas today, 2 upcoming scheduled, 1 low stock
        assertEquals(List.of(2L, 3L, 2L, 1L), view.kpis().stream().map(DashboardView.Kpi::valor).toList());
        assertEquals("con citas", view.kpis().get(0).detalle(), "a cita is not an attention");
        assertEquals("en o bajo el mínimo", view.kpis().get(3).detalle(), "the count includes stock = minimo");
        assertEquals(3, view.resumenHoy().total(), "lopez's cita is not counted");
        assertEquals(1, view.resumenHoy().atendidas());
        assertEquals(50.0, view.atendidasPct(), 1e-9);
        assertEquals(20.0, view.duracionPromedioMin());
        assertTrue(view.proximasCitas().stream().allMatch(c -> c.cita().idMedico() == 10L));
        assertEquals(List.of(2L, 5L), view.proximasCitas().stream().map(c -> c.cita().id()).toList());
    }

    @Test
    void pacienteOnlySeesItsOwnRecords() {
        current = JUAN;
        DashboardView view = service.load();
        assertEquals(Rol.PACIENTE, view.rol());
        assertEquals(List.of("Mis citas", "Citas próximas", "Mis órdenes", "Historias"),
            view.kpis().stream().map(DashboardView.Kpi::etiqueta).toList());
        // 4 citas in total (one cancelled); 2 still scheduled ahead (today 10:00 and tomorrow 09:00)
        assertEquals(List.of(4L, 2L, 0L, 0L), view.kpis().stream().map(DashboardView.Kpi::valor).toList());
        assertEquals(3, view.resumenHoy().total());
        assertEquals(1, view.resumenHoy().atendidas());
        assertTrue(view.proximasCitas().stream().allMatch(c -> c.cita().idPaciente() == 21L));
        assertEquals(List.of(4L, 5L), view.proximasCitas().stream().map(c -> c.cita().id()).toList());
    }

    @Test
    void anEmptyDayReportsZerosAndNullAverages() {
        current = new SessionContext(9, "paciente.sin.citas", Rol.PACIENTE, 20L, "Maria Torres");
        // maria only has the 09:00 cita today, so move it away to empty her day
        citas.replace(new Cita(2L, 10L, 20L, at(TODAY.plusDays(5), 9, 0), "Control", EstadoCita.PROGRAMADA,
            null, null, null));
        DashboardView view = service.load();
        assertEquals(0, view.resumenHoy().total());
        assertNull(view.atendidasPct(), "no citas today: no percentage to show");
        assertNull(view.duracionPromedioMin());
        assertEquals(1, view.proximasCitas().size());
        assertEquals(List.of(1L, 1L, 0L, 0L), view.kpis().stream().map(DashboardView.Kpi::valor).toList());
    }

    @Test
    void theAveragesCoverEveryFinishedCitaOfTheDay() {
        // a second finished cita of 40 minutes: the average becomes 30 and the share 2 of 4
        citas.add(new Cita(6L, 10L, 20L, at(TODAY, 6, 0), "Otra", EstadoCita.FINALIZADA,
            at(TODAY, 6, 0), at(TODAY, 6, 40), null));
        DashboardView view = service.load();
        assertEquals(5, view.resumenHoy().total());
        assertEquals(2, view.resumenHoy().atendidas());
        assertEquals(50.0, view.atendidasPct(), 1e-9, "2 of the 4 citas that were not cancelled");
        assertEquals(30.0, view.duracionPromedioMin());
    }

    @Test
    void theFiveCountsOfTheDayAddUpToTheTotal() {
        citas.replace(new Cita(4L, 11L, 21L, at(TODAY, 10, 0), "Vacuna", EstadoCita.EN_CURSO,
            at(TODAY, 10, 0), null, null));
        DashboardView view = service.load();
        var resumen = view.resumenHoy();
        assertEquals(1, resumen.enCurso());
        assertEquals(resumen.total(),
            resumen.atendidas() + resumen.enCurso() + resumen.pendientes() + resumen.canceladas(),
            "the tiles of the card must add up to the total");
        assertEquals(3, resumen.noCanceladas());
        assertEquals(100.0 / 3, view.atendidasPct(), 1e-9);
    }

    @Test
    void aDayWhereEveryCitaWasCancelledHasNoPercentage() {
        for (long id : List.of(1L, 2L, 4L)) {
            Cita original = citas.findById(id).orElseThrow().cita();
            citas.replace(new Cita(original.id(), original.idMedico(), original.idPaciente(),
                original.fechaHoraProgramada(), original.motivo(), EstadoCita.CANCELADA, null, null, null));
        }
        DashboardView view = service.load();
        assertEquals(4, view.resumenHoy().total());
        assertEquals(4, view.resumenHoy().canceladas());
        assertEquals(0, view.resumenHoy().noCanceladas());
        assertNull(view.atendidasPct(), "dividing by the cancelled citas would be a lie, not a zero");
    }

    @Test
    void theUpcomingListIsCappedAtFive() {
        for (int i = 0; i < 10; i++) {
            citas.add(new Cita(100L + i, 10L, 20L, at(TODAY.plusDays(2 + i), 9, 0), "Futura",
                EstadoCita.PROGRAMADA, null, null, null));
        }
        assertEquals(DashboardService.PROXIMAS_LIMIT, service.load().proximasCitas().size());
    }

    @Test
    void theDayWindowFollowsTheLocalCalendarNotUtc() {
        // 02:00Z of the 28th is still 21:00 of the 27th in Bogota: the panel must not jump a day
        Clock lateNight = Clock.fixed(Instant.parse("2026-10-28T02:00:00Z"), BOGOTA);
        DashboardService atNight = new DashboardService(
            new InMemoryDashboardRepository(citas, medicos, pacientes, medicamentos, atenciones),
            medicamentos, () -> Optional.ofNullable(current), lateNight, ZONE);
        DashboardView view = atNight.load();
        assertEquals(4, view.resumenHoy().total(), "the citas of the 27th still belong to today");
        assertEquals(1, view.resumenHoy().atendidas());
        // everything scheduled earlier today is past: only tomorrow's 09:00 is still ahead
        assertEquals(List.of(5L), view.proximasCitas().stream().map(c -> c.cita().id()).toList());
    }

    @Test
    void aSessionIsRequired() {
        current = null;
        assertThrows(PermissionException.class, () -> service.load());
        current = new SessionContext(9, "dr.x", Rol.MEDICO, null, "Sin ficha");
        assertThrows(PermissionException.class, () -> service.load());
    }
}
