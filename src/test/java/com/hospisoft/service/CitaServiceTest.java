package com.hospisoft.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hospisoft.dao.InMemoryCitaRepository;
import com.hospisoft.dao.InMemoryMedicoRepository;
import com.hospisoft.dao.InMemoryPacienteRepository;
import com.hospisoft.model.Cita;
import com.hospisoft.model.CitaView;
import com.hospisoft.model.EstadoCita;
import com.hospisoft.model.Medico;
import com.hospisoft.model.Paciente;
import com.hospisoft.model.Page;
import com.hospisoft.model.Rol;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CitaServiceTest {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    private static final AppZone ZONE = new AppZone(BOGOTA);
    /** 2026-10-27 08:00 in Bogota (UTC-5). */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-27T13:00:00Z"), BOGOTA);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);
    private static final LocalDate TOMORROW = TODAY.plusDays(1);

    private static final SessionContext ADMIN = new SessionContext(1, "admin", Rol.ADMIN, null, "Administrador");
    private static final SessionContext GARCIA = new SessionContext(2, "dr.garcia", Rol.MEDICO, 10L, "Carlos Garcia");
    private static final SessionContext MARIA = new SessionContext(3, "paciente1", Rol.PACIENTE, 20L, "Maria Torres");

    private InMemoryMedicoRepository medicos;
    private InMemoryPacienteRepository pacientes;
    private InMemoryCitaRepository citas;
    private SessionContext current;
    private CitaService service;

    private static OffsetDateTime at(LocalDate date, int hour, int minute) {
        return ZONE.at(date, LocalTime.of(hour, minute));
    }

    private static CitaInput input(Long medico, Long paciente, String fecha, String hora, String motivo) {
        return new CitaInput(medico, paciente, fecha, hora, motivo);
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
        citas = new InMemoryCitaRepository(medicos, pacientes);
        // today 09:00 garcia/maria PROGRAMADA; today 07:00 garcia/juan FINALIZADA; tomorrow 10:00 lopez/juan;
        // yesterday 09:00 garcia/maria CANCELADA; today 16:00 garcia/juan CANCELADA
        citas.add(new Cita(1L, 10L, 20L, at(TODAY, 9, 0), "Control", EstadoCita.PROGRAMADA, null, null, null));
        citas.add(new Cita(2L, 10L, 21L, at(TODAY, 7, 0), "Gripe", EstadoCita.FINALIZADA, at(TODAY, 7, 0), at(TODAY, 7, 25), null));
        citas.add(new Cita(3L, 11L, 21L, at(TOMORROW, 10, 0), "Vacuna", EstadoCita.PROGRAMADA, null, null, null));
        citas.add(new Cita(4L, 10L, 20L, at(TODAY.minusDays(1), 9, 0), "Vieja", EstadoCita.CANCELADA, null, null, null));
        citas.add(new Cita(5L, 10L, 21L, at(TODAY, 16, 0), "Cancelada", EstadoCita.CANCELADA, null, null, null));
        current = ADMIN;
        service = new CitaService(citas, medicos, pacientes, () -> Optional.ofNullable(current), CLOCK, ZONE);
    }

    private ValidationException invalid(CitaInput in) {
        return assertThrows(ValidationException.class, () -> service.create(in));
    }

    // ------------------------------------------------------------ zone

    @Test
    void localWallTimeIsConvertedThroughTheZone() {
        OffsetDateTime nine = ZONE.at(TODAY, LocalTime.of(9, 0));
        assertEquals(OffsetDateTime.of(2026, 10, 27, 9, 0, 0, 0, ZoneOffset.ofHours(-5)), nine);
        assertEquals(Instant.parse("2026-10-27T14:00:00Z"), nine.toInstant());
        assertEquals(TODAY, service.today());
        CitaView created = service.create(input(10L, 20L, "2026-10-28", "09:00", "Control"));
        assertEquals(Instant.parse("2026-10-28T14:00:00Z"), created.cita().fechaHoraProgramada().toInstant());
        assertEquals(LocalTime.of(9, 0), ZONE.timeOf(created.cita().fechaHoraProgramada()));
    }

    // ------------------------------------------------------------ create: overlap matrix and locks

    @Test
    void adjacentSlotsAreAllowedAndLocksFollowTheGlobalOrder() {
        service.create(input(10L, 21L, TODAY.toString(), "09:30", "Siguiente"));
        service.create(input(10L, 21L, TODAY.toString(), "08:30", "Anterior"));
        assertEquals(List.of("medico:10", "paciente:21", "medico:10", "paciente:21"), citas.locks);
    }

    @Test
    void sameStartAndPartialOverlapAreRejectedForTheMedico() {
        assertEquals(CitaService.MEDICO_OVERLAP, invalid(input(10L, 21L, TODAY.toString(), "09:00", "x")).errors().get("hora"));
        assertEquals(CitaService.MEDICO_OVERLAP, invalid(input(10L, 21L, TODAY.toString(), "09:15", "x")).errors().get("hora"));
        assertEquals(CitaService.MEDICO_OVERLAP, invalid(input(10L, 21L, TODAY.toString(), "08:45", "x")).errors().get("hora"));
        assertEquals(CitaService.MEDICO_OVERLAP, invalid(input(10L, 21L, TODAY.toString(), "09:25", "x")).errors().get("hora"));
    }

    @Test
    void cancelledCitasDoNotBlock() {
        service.create(input(10L, 21L, TODAY.toString(), "16:00", "Libre otra vez")); // cita 5 is CANCELADA
    }

    @Test
    void pacienteOverlapIsRejectedWithItsOwnMessage() {
        ValidationException ex = invalid(input(11L, 20L, TODAY.toString(), "09:15", "x"));
        assertEquals(CitaService.PACIENTE_OVERLAP, ex.errors().get("hora"));
        service.create(input(11L, 20L, TODAY.toString(), "09:30", "ok"));
    }

    @Test
    void rowsAreRecheckedInsideTheTransaction() {
        citas.lockOverrides.put("medico:10", Optional.of(false)); // deactivated after the fast-path check
        ValidationException inactive = invalid(input(10L, 21L, TODAY.toString(), "10:00", "x"));
        assertEquals(Validation.MUST_BE_ACTIVE, inactive.errors().get("idMedico"));
        citas.lockOverrides.put("medico:10", Optional.empty()); // deleted meanwhile
        ValidationException missing = invalid(input(10L, 21L, TODAY.toString(), "10:00", "x"));
        assertEquals(Validation.NOT_EXISTS, missing.errors().get("idMedico"));
        citas.lockOverrides.clear();
        citas.lockOverrides.put("paciente:21", Optional.of(false));
        assertEquals(Validation.MUST_BE_ACTIVE, invalid(input(10L, 21L, TODAY.toString(), "10:00", "x")).errors().get("idPaciente"));
        citas.lockOverrides.put("paciente:21", Optional.empty());
        assertEquals(Validation.NOT_EXISTS, invalid(input(10L, 21L, TODAY.toString(), "10:00", "x")).errors().get("idPaciente"));
    }

    @Test
    void rescheduleExcludesItselfAndKeepsEveryRule() {
        CitaView moved = service.reschedule(1, input(10L, 20L, TODAY.toString(), "09:00", "Mismo horario"));
        assertEquals("Mismo horario", moved.cita().motivo());
        moved = service.reschedule(1, input(10L, 20L, TODAY.toString(), "11:00", "Movida"));
        assertEquals(LocalTime.of(11, 0), ZONE.timeOf(moved.cita().fechaHoraProgramada()));
        assertEquals(EstadoCita.PROGRAMADA, moved.cita().estado());
        service.create(input(10L, 21L, TODAY.toString(), "09:00", "Slot freed"));
        ValidationException ex = assertThrows(ValidationException.class,
            () -> service.reschedule(1, input(10L, 20L, TODAY.toString(), "09:15", "collide")));
        assertEquals(CitaService.MEDICO_OVERLAP, ex.errors().get("hora"));

        // to another medico whose 10:00 tomorrow is taken by cita 3
        ValidationException other = assertThrows(ValidationException.class,
            () -> service.reschedule(1, input(11L, 20L, TOMORROW.toString(), "10:15", "other medico")));
        assertEquals(CitaService.MEDICO_OVERLAP, other.errors().get("hora"));
        service.reschedule(1, input(11L, 20L, TOMORROW.toString(), "10:30", "other medico ok"));
        assertEquals(11L, citas.raw(1).idMedico());

        ValidationException past = assertThrows(ValidationException.class,
            () -> service.reschedule(1, input(10L, 20L, TODAY.toString(), "07:30", "past")));
        assertEquals(CitaService.PAST, past.errors().get("fecha"));
        ValidationException step = assertThrows(ValidationException.class,
            () -> service.reschedule(1, input(10L, 20L, TOMORROW.toString(), "10:07", "step")));
        assertEquals(CitaService.BAD_MINUTE, step.errors().get("hora"));
    }

    // ------------------------------------------------------------ create: validation

    @Test
    void pastDatesAreRejected() {
        assertEquals(CitaService.PAST, invalid(input(10L, 21L, TODAY.toString(), "07:30", "x")).errors().get("fecha"));
        assertEquals(CitaService.PAST, invalid(input(10L, 21L, "2026-10-26", "10:00", "x")).errors().get("fecha"));
        service.create(input(10L, 21L, TODAY.toString(), "08:00", "now is fine")); // 08:00 == now
    }

    @Test
    void workingHoursAndHorizon() {
        assertEquals(CitaService.OUTSIDE_HOURS, invalid(input(10L, 21L, TOMORROW.toString(), "18:45", "x")).errors().get("hora"));
        assertEquals(CitaService.OUTSIDE_HOURS, invalid(input(10L, 21L, TOMORROW.toString(), "23:45", "x")).errors().get("hora"));
        assertEquals(CitaService.OUTSIDE_HOURS, invalid(input(10L, 21L, TOMORROW.toString(), "06:30", "x")).errors().get("hora"));
        service.create(input(10L, 21L, TOMORROW.toString(), "18:30", "last slot"));
        service.create(input(10L, 21L, TOMORROW.toString(), "07:00", "first slot"));
        assertEquals(CitaService.TOO_FAR, invalid(input(10L, 21L, TODAY.plusYears(2).toString(), "10:00", "x")).errors().get("fecha"));
        service.create(input(10L, 21L, TODAY.plusDays(365).toString(), "10:00", "one year"));
        assertEquals(CitaService.TOO_FAR, invalid(input(10L, 21L, TODAY.plusDays(366).toString(), "10:00", "x")).errors().get("fecha"));
    }

    @Test
    void validationMatrix() {
        ValidationException ex = invalid(new CitaInput(null, null, "", " ", null));
        assertEquals(Validation.REQUIRED, ex.errors().get("idMedico"));
        assertEquals(Validation.REQUIRED, ex.errors().get("idPaciente"));
        assertEquals(Validation.REQUIRED, ex.errors().get("fecha"));
        assertEquals(Validation.REQUIRED, ex.errors().get("hora"));
        assertEquals(Validation.REQUIRED, ex.errors().get("motivo"));
        assertEquals(5, ex.errors().size());

        ex = invalid(input(999L, 22L, "27/10/2026", "9:00", "m".repeat(201)));
        assertEquals(CitaService.MEDICO_NOT_FOUND, ex.errors().get("idMedico"));
        assertEquals(CitaService.PACIENTE_INACTIVE, ex.errors().get("idPaciente"));
        assertEquals(CitaService.BAD_DATE, ex.errors().get("fecha"));
        assertEquals(CitaService.BAD_TIME, ex.errors().get("hora"));
        assertEquals("Máximo 200 caracteres", ex.errors().get("motivo"));

        ex = invalid(input(12L, 999L, TOMORROW.toString(), "09:07", "x"));
        assertEquals(CitaService.MEDICO_INACTIVE, ex.errors().get("idMedico"));
        assertEquals(CitaService.PACIENTE_NOT_FOUND, ex.errors().get("idPaciente"));
        assertEquals(CitaService.BAD_MINUTE, ex.errors().get("hora"));
        assertEquals(CitaService.BAD_TIME, invalid(input(10L, 20L, TOMORROW.toString(), "25:00", "x")).errors().get("hora"));
        assertEquals(CitaService.BAD_DATE, invalid(input(10L, 20L, "2026-02-30", "09:00", "x")).errors().get("fecha"));
        assertEquals(CitaService.BAD_DATE, invalid(input(10L, 20L, "2026-1-5", "09:00", "x")).errors().get("fecha"));
    }

    @Test
    void newCitasAreAlwaysProgramadaAndTrimmed() {
        CitaView created = service.create(input(10L, 21L, TOMORROW.toString(), "09:05", "  Control  "));
        assertEquals(EstadoCita.PROGRAMADA, created.cita().estado());
        assertEquals("Control", created.cita().motivo());
        assertNull(created.cita().horaInicioReal());
        assertEquals("Carlos Garcia", created.medicoNombre());
    }

    // ------------------------------------------------------------ state guards

    @Test
    void onlyProgramadaCanBeCancelledOrRescheduled() {
        CitaView cancelled = service.cancel(1);
        assertEquals(EstadoCita.CANCELADA, cancelled.cita().estado());
        for (long id : new long[] {1, 2, 4}) {
            ValidationException cancel = assertThrows(ValidationException.class, () -> service.cancel(id));
            assertEquals(CitaService.NOT_PROGRAMADA, cancel.errors().get("estado"));
            ValidationException move = assertThrows(ValidationException.class,
                () -> service.reschedule(id, input(10L, 20L, TOMORROW.toString(), "09:00", "x")));
            assertEquals(CitaService.NOT_PROGRAMADA, move.errors().get("estado"));
        }
        assertThrows(NotFoundException.class, () -> service.cancel(999));
    }

    // ------------------------------------------------------------ roles and scope

    @Test
    void medicoAndPacienteAreReadOnly() {
        for (SessionContext session : List.of(GARCIA, MARIA)) {
            current = session;
            assertThrows(PermissionException.class, () -> service.create(input(10L, 21L, TOMORROW.toString(), "09:00", "x")));
            assertThrows(PermissionException.class, () -> service.reschedule(1, input(10L, 21L, TOMORROW.toString(), "09:00", "x")));
            assertThrows(PermissionException.class, () -> service.cancel(1));
            assertThrows(PermissionException.class, () -> service.medicosForSelect());
            assertThrows(PermissionException.class, () -> service.pacientesForSelect());
        }
        current = null;
        assertThrows(PermissionException.class, () -> service.list(CitaFilter.NONE, 1, 10));
        current = ADMIN;
        assertEquals(List.of("Garcia", "Lopez"), service.medicosForSelect().stream().map(Medico::apellidos).toList());
        assertEquals(List.of("Martinez", "Torres"), service.pacientesForSelect().stream().map(Paciente::apellidos).toList());
    }

    @Test
    void medicoSeesOnlyOwnCitas() {
        current = GARCIA;
        Page<CitaView> all = service.list(new CitaFilter(null, null, null, 11L, CitaRango.TODAS), 1, 10);
        assertEquals(4, all.total(), "filter idMedico is ignored for MEDICO: scope wins");
        assertTrue(all.items().stream().allMatch(v -> v.cita().idMedico() == 10L));
        Page<CitaView> todayAndFuture = service.list(new CitaFilter(null, null, null, null, CitaRango.HOY_Y_FUTURAS), 1, 10);
        assertEquals(3, todayAndFuture.total());
        assertEquals(1L, service.get(1).cita().id());
        assertThrows(NotFoundException.class, () -> service.get(3));
        assertThrows(NotFoundException.class, () -> service.get(999));
    }

    @Test
    void scopeWorksForIdsAboveTheLongCache() {
        // ids above 127 are not cached: comparing boxed Longs with == would deny access here
        medicos.add(new Medico(1010L, "9001", "Big", "Id", "Medicina General", null, null, true));
        pacientes.add(new Paciente(2020L, "9002", "Big", "Patient", LocalDate.of(1990, 1, 1), "F",
            null, null, null, true));
        citas.add(new Cita(3030L, 1010L, 2020L, at(TODAY, 12, 0), "Grande", EstadoCita.PROGRAMADA, null, null, null));

        current = new SessionContext(900, "dr.big", Rol.MEDICO, 1010L, "Big Id");
        assertEquals(3030L, service.get(3030).cita().id());
        assertEquals(1, service.list(CitaFilter.NONE, 1, 10).total());

        current = new SessionContext(901, "big.paciente", Rol.PACIENTE, 2020L, "Big Patient");
        assertEquals(3030L, service.get(3030).cita().id());
        assertEquals(1, service.list(CitaFilter.NONE, 1, 10).total());
    }

    @Test
    void pacienteSeesOnlyOwnCitas() {
        current = MARIA;
        Page<CitaView> all = service.list(CitaFilter.NONE, 1, 10);
        assertEquals(2, all.total());
        assertTrue(all.items().stream().allMatch(v -> v.cita().idPaciente() == 20L));
        assertThrows(NotFoundException.class, () -> service.get(2));
        assertThrows(PermissionException.class, () -> service.agenda(null, TODAY));
    }

    @Test
    void adminListFiltersAndRanges() {
        assertEquals(5, service.list(CitaFilter.NONE, 1, 10).total());
        assertEquals(3, service.list(new CitaFilter(null, TODAY, null, null, null), 1, 10).total());
        assertEquals(3, service.list(new CitaFilter(null, null, null, null, CitaRango.HOY), 1, 10).total());
        // now is 08:00: 09:00 today, 16:00 today (cancelled but still listed) and tomorrow are future
        assertEquals(3, service.list(new CitaFilter(null, null, null, null, CitaRango.FUTURAS), 1, 10).total());
        assertEquals(2, service.list(new CitaFilter(null, null, null, null, CitaRango.PASADAS), 1, 10).total());
        assertEquals(4, service.list(new CitaFilter(null, null, null, null, CitaRango.HOY_Y_FUTURAS), 1, 10).total());
        assertEquals(2, service.list(new CitaFilter(null, null, EstadoCita.CANCELADA, null, null), 1, 10).total());
        assertEquals(1, service.list(new CitaFilter(null, null, null, 11L, null), 1, 10).total());
        assertEquals(2, service.list(new CitaFilter("torres", null, null, null, null), 1, 10).total()); // citas 1 and 4
        assertEquals(1, service.list(new CitaFilter("2002", null, null, 11L, null), 1, 10).total());
        // an explicit day wins over the range preset
        assertEquals(1, service.list(new CitaFilter(null, TODAY.minusDays(1), null, null, CitaRango.FUTURAS), 1, 10).total());
        Page<CitaView> ordered = service.list(CitaFilter.NONE, 1, 10);
        assertEquals(4L, ordered.items().get(0).cita().id(), "ordered by fecha_hora");
    }

    // ------------------------------------------------------------ agenda

    @Test
    void agendaGridOccupancyAndExtraSlots() {
        Agenda empty = service.agenda(11L, TODAY);
        assertEquals(24, empty.slots().size());
        assertEquals(LocalTime.of(7, 0), empty.slots().get(0).hora());
        assertEquals(LocalTime.of(18, 30), empty.slots().get(23).hora());
        assertTrue(empty.slots().stream().allMatch(Agenda.Slot::libre));

        Agenda garcia = service.agenda(10L, TODAY);
        assertEquals(24, garcia.slots().size(), "cancelled 16:00 frees its slot");
        assertEquals(1L, garcia.slots().get(4).citas().get(0).cita().id()); // 09:00 is the 5th slot
        assertEquals(2L, garcia.slots().get(0).citas().get(0).cita().id()); // 07:00 finalizada still shown
        assertTrue(garcia.slots().get(18).libre());                          // 16:00 free (cancelled)

        // 09:15 (another medico's patient, different paciente) lands in the 09:00 slot together with cita 1
        citas.add(new Cita(50L, 10L, 21L, at(TODAY, 9, 15), "Intercalada", EstadoCita.PROGRAMADA, null, null, null));
        citas.add(new Cita(51L, 10L, 22L, at(TODAY, 9, 0), "Mismo inicio", EstadoCita.EN_CURSO, null, null, null));
        citas.add(new Cita(52L, 10L, 20L, at(TODAY, 20, 0), "Fuera de horario", EstadoCita.PROGRAMADA, null, null, null));
        Agenda crowded = service.agenda(10L, TODAY);
        assertEquals(25, crowded.slots().size());
        assertEquals(List.of(1L, 51L, 50L), crowded.slots().get(4).citas().stream().map(v -> v.cita().id()).toList(),
            "ordered by time then id: both 09:00 citas before the 09:15 one");
        assertTrue(crowded.slots().get(5).libre(), "09:30 stays free");
        assertEquals(LocalTime.of(20, 0), crowded.slots().get(24).hora());
        assertEquals(52L, crowded.slots().get(24).citas().get(0).cita().id());

        assertThrows(NotFoundException.class, () -> service.agenda(999L, TODAY));
    }

    @Test
    void medicoAgendaIsSelfOnly() {
        current = GARCIA;
        Agenda own = service.agenda(null, null);
        assertEquals(10L, own.idMedico());
        assertEquals(TODAY, own.fecha());
        assertEquals(10L, service.agenda(10L, TODAY).idMedico());
        assertThrows(PermissionException.class, () -> service.agenda(11L, TODAY));
        current = ADMIN;
        ValidationException ex = assertThrows(ValidationException.class, () -> service.agenda(null, TODAY));
        assertEquals(Validation.REQUIRED, ex.errors().get("idMedico"));
    }
}
