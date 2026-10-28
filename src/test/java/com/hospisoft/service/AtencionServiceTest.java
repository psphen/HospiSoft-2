package com.hospisoft.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hospisoft.dao.InMemoryAtencionRepository;
import com.hospisoft.dao.InMemoryCitaRepository;
import com.hospisoft.dao.InMemoryMedicamentoRepository;
import com.hospisoft.dao.InMemoryMedicoRepository;
import com.hospisoft.dao.InMemoryPacienteRepository;
import com.hospisoft.model.Cita;
import com.hospisoft.model.CitaView;
import com.hospisoft.model.EstadoCita;
import com.hospisoft.model.HistoriaView;
import com.hospisoft.model.Medicamento;
import com.hospisoft.model.Medico;
import com.hospisoft.model.Paciente;
import com.hospisoft.model.Page;
import com.hospisoft.model.Receta;
import com.hospisoft.model.RecetaView;
import com.hospisoft.model.Rol;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AtencionServiceTest {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    private static final AppZone ZONE = new AppZone(BOGOTA);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-27T13:00:00Z"), BOGOTA);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);

    private static final SessionContext ADMIN = new SessionContext(1, "admin", Rol.ADMIN, null, "Administrador");
    private static final SessionContext GARCIA = new SessionContext(2, "dr.garcia", Rol.MEDICO, 10L, "Carlos Garcia");
    private static final SessionContext LOPEZ = new SessionContext(4, "dra.lopez", Rol.MEDICO, 11L, "Laura Lopez");
    private static final SessionContext MARIA = new SessionContext(3, "paciente1", Rol.PACIENTE, 20L, "Maria Torres");
    private static final SessionContext JUAN = new SessionContext(5, "paciente2", Rol.PACIENTE, 21L, "Juan Martinez");

    private InMemoryMedicoRepository medicos;
    private InMemoryPacienteRepository pacientes;
    private InMemoryMedicamentoRepository medicamentos;
    private InMemoryCitaRepository citas;
    private InMemoryAtencionRepository atenciones;
    private SessionContext current;
    private AtencionService service;

    private static AtencionInput.RecetaInput line(long idMedicamento, Object cantidad, String observacion) {
        return new AtencionInput.RecetaInput(idMedicamento, cantidad, "1 cada 8 horas", "3 dias", observacion);
    }

    private static AtencionInput closing(long idCita, AtencionInput.RecetaInput... lines) {
        return new AtencionInput(idCita, "Gripe", "Reposo", "Sin alarma", "Atendido", List.of(lines));
    }

    @BeforeEach
    void setUp() {
        medicos = new InMemoryMedicoRepository();
        medicos.add(new Medico(10L, "1001", "Carlos", "Garcia", "Medicina General", null, null, true));
        medicos.add(new Medico(11L, "1002", "Laura", "Lopez", "Pediatria", null, null, true));
        pacientes = new InMemoryPacienteRepository();
        pacientes.add(new Paciente(20L, "2001", "Maria", "Torres", LocalDate.of(1990, 5, 14), "F", null, null, null, true));
        pacientes.add(new Paciente(21L, "2002", "Juan", "Martinez", LocalDate.of(1985, 11, 2), "M", null, null, null, true));
        medicamentos = new InMemoryMedicamentoRepository();
        medicamentos.add(new Medicamento(5L, "MED-001", "Acetaminofen", null, "Caja x 20", 150, 30, true));
        medicamentos.add(new Medicamento(6L, "MED-005", "Loratadina", null, "Caja x 10", 2, 15, true));
        medicamentos.add(new Medicamento(7L, "MED-010", "Diclofenaco", null, "Tubo", 50, 10, false));
        citas = new InMemoryCitaRepository(medicos, pacientes);
        // 1: garcia/maria PROGRAMADA, 2: garcia/juan EN_CURSO, 3: lopez/juan PROGRAMADA, 4: garcia/maria FINALIZADA
        citas.add(new Cita(1L, 10L, 20L, ZONE.at(TODAY, LocalTime.of(9, 0)), "Control", EstadoCita.PROGRAMADA, null, null, null));
        citas.add(new Cita(2L, 10L, 21L, ZONE.at(TODAY, LocalTime.of(10, 0)), "Dolor", EstadoCita.EN_CURSO,
            ZONE.at(TODAY, LocalTime.of(10, 1)), null, null));
        citas.add(new Cita(3L, 11L, 21L, ZONE.at(TODAY, LocalTime.of(11, 0)), "Vacuna", EstadoCita.PROGRAMADA, null, null, null));
        citas.add(new Cita(4L, 10L, 20L, ZONE.at(TODAY.minusDays(3), LocalTime.of(9, 0)), "Vieja", EstadoCita.FINALIZADA,
            null, ZONE.at(TODAY.minusDays(3), LocalTime.of(9, 25)), null));
        atenciones = new InMemoryAtencionRepository(citas, medicos, pacientes, medicamentos);
        current = GARCIA;
        service = new AtencionService(atenciones, citas, medicamentos, pacientes,
            () -> Optional.ofNullable(current), CLOCK, ZONE);
    }

    private ValidationException invalidClose(AtencionInput input) {
        return assertThrows(ValidationException.class, () -> service.finalizar(input));
    }

    // ------------------------------------------------------------ iniciar

    @Test
    void iniciarMovesTheOwnProgramadaCitaToEnCurso() {
        // cita 2 is EN_CURSO in the fixture: close it first so the medico is free
        service.finalizar(closing(2));
        CitaView started = service.iniciar(1);
        assertEquals(EstadoCita.EN_CURSO, started.cita().estado());
        assertEquals("medico:10", atenciones.locks.get(0));
        assertNull(citas.raw(1).horaFinReal());
        assertEquals(EstadoCita.EN_CURSO, citas.raw(1).estado());
    }

    @Test
    void onlyOneCitaMayBeEnCursoPerMedico() {
        ValidationException ex = assertThrows(ValidationException.class, () -> service.iniciar(1));
        assertEquals(AtencionService.ALREADY_EN_CURSO, ex.errors().get("estado"));
        assertEquals(EstadoCita.PROGRAMADA, citas.raw(1).estado());
    }

    @Test
    void iniciarTwiceIsAConflictAndOtherMedicosCitasAreNotFound() {
        service.finalizar(closing(2));
        service.iniciar(1);
        service.finalizar(closing(1));
        ConflictException conflict = assertThrows(ConflictException.class, () -> service.iniciar(1));
        assertEquals(AtencionService.NOT_PROGRAMADA, conflict.userMessage());
        assertThrows(NotFoundException.class, () -> service.iniciar(3));   // another medico's cita
        assertThrows(NotFoundException.class, () -> service.iniciar(999)); // missing
    }

    @Test
    void adminAndPacienteCannotRunTheLifecycle() {
        for (SessionContext session : List.of(ADMIN, MARIA)) {
            current = session;
            assertThrows(PermissionException.class, () -> service.iniciar(1));
            assertThrows(PermissionException.class, () -> service.finalizar(closing(2)));
        }
        current = null;
        assertThrows(PermissionException.class, () -> service.iniciar(1));
    }

    // ------------------------------------------------------------ finalizar

    @Test
    void happyPathWritesCitaHistoriaRecetasAndDecrementsStock() {
        Atencion result = service.finalizar(closing(2, line(5, 9, null), line(6, 1, null)));

        assertEquals(EstadoCita.FINALIZADA, citas.raw(2).estado());
        assertEquals("Atendido", citas.raw(2).observaciones());
        assertTrue(citas.raw(2).horaFinReal() != null);
        assertEquals(1, atenciones.historiaCount());
        assertEquals("Gripe", atenciones.historiaOfCita(2).diagnostico());
        assertEquals(2, result.recetas().size());
        assertTrue(result.recetas().stream().allMatch(r -> r.receta().entregado()));
        assertEquals(141, medicamentos.findById(5).orElseThrow().stock());
        assertEquals(1, medicamentos.findById(6).orElseThrow().stock());
        assertEquals(List.of("medico:10", "medicamento:5", "medicamento:6"), atenciones.locks,
            "medico first, then medicamentos in ascending id order");
    }

    @Test
    void medicamentosAreLockedInAscendingIdOrderWhateverTheFormOrder() {
        service.finalizar(closing(2, line(6, 1, null), line(5, 1, null)));
        assertEquals(List.of("medico:10", "medicamento:5", "medicamento:6"), atenciones.locks);
    }

    @Test
    void insufficientStockLeavesTheLinePendingAndTheStockUntouched() {
        ValidationException ex = invalidClose(closing(2, line(6, 5, null)));
        assertEquals(AtencionService.OBSERVACION_REQUIRED, ex.errors().get("recetas[0].observacionNoDisponible"));
        assertEquals(2, medicamentos.findById(6).orElseThrow().stock());
        assertEquals(EstadoCita.EN_CURSO, citas.raw(2).estado(), "nothing was written");

        Atencion result = service.finalizar(closing(2, line(6, 5, "Sin existencias, comprar afuera")));
        RecetaView stored = result.recetas().get(0);
        assertFalse(stored.receta().entregado());
        assertEquals("Sin existencias, comprar afuera", stored.receta().observacionNoDisponible());
        assertEquals(2, medicamentos.findById(6).orElseThrow().stock(), "stock untouched");
        assertEquals(EstadoCita.FINALIZADA, citas.raw(2).estado());
    }

    @Test
    void mixedLinesDeliverOnlyWhatFits() {
        Atencion result = service.finalizar(closing(2,
            line(5, 10, null),                 // enough stock -> delivered
            line(6, 99, "No hay"),             // not enough -> pending
            line(7, 1, "Medicamento inactivo"))); // inactive -> never delivered
        assertEquals(List.of(true, false, false),
            result.recetas().stream().map(r -> r.receta().entregado()).toList());
        assertEquals(140, medicamentos.findById(5).orElseThrow().stock());
        assertEquals(2, medicamentos.findById(6).orElseThrow().stock());
        assertEquals(50, medicamentos.findById(7).orElseThrow().stock());
    }

    @Test
    void inactiveMedicamentoAlwaysNeedsAnObservation() {
        ValidationException ex = invalidClose(closing(2, line(7, 1, null)));
        assertEquals(AtencionService.OBSERVACION_REQUIRED, ex.errors().get("recetas[0].observacionNoDisponible"));
    }

    @Test
    void twoLinesOfTheSameMedicamentoConsumeStockCumulatively() {
        Atencion result = service.finalizar(closing(2, line(6, 1, null), line(6, 2, "Ya no alcanza")));
        assertEquals(List.of(true, false), result.recetas().stream().map(r -> r.receta().entregado()).toList());
        assertEquals(1, medicamentos.findById(6).orElseThrow().stock());
        assertEquals(List.of("medico:10", "medicamento:6"), atenciones.locks, "locked once");
    }

    @Test
    void closingWithoutRecetasIsValid() {
        Atencion result = service.finalizar(closing(2));
        assertEquals(0, result.recetas().size());
        assertEquals(1, atenciones.historiaCount());
        assertEquals(0, atenciones.recetaCount());
    }

    @Test
    void secondFinalizeIsAConflictAndTheHistoriaStaysUnique() {
        service.finalizar(closing(2, line(5, 1, null)));
        ConflictException ex = assertThrows(ConflictException.class, () -> service.finalizar(closing(2)));
        assertEquals(AtencionService.ALREADY_CLOSED, ex.userMessage());
        assertEquals(1, atenciones.historiaCount());
        assertEquals(1, atenciones.recetaCount());
        assertEquals(149, medicamentos.findById(5).orElseThrow().stock(), "stock decremented once");
    }

    @Test
    void finalizarOnlyWorksOnOwnEnCursoCitas() {
        assertThrows(NotFoundException.class, () -> service.finalizar(closing(3)));   // another medico
        assertThrows(NotFoundException.class, () -> service.finalizar(closing(999))); // missing
        ConflictException notStarted = assertThrows(ConflictException.class, () -> service.finalizar(closing(1)));
        assertEquals(AtencionService.NOT_EN_CURSO, notStarted.userMessage());
    }

    @Test
    void everythingRollsBackWhenAWriteFailsLate() {
        atenciones.failOnRecetaInsert = 1; // the second receta insert blows up
        assertThrows(IllegalStateException.class,
            () -> service.finalizar(closing(2, line(5, 9, null), line(6, 1, null))));
        assertEquals(0, atenciones.historiaCount(), "historia rolled back");
        assertEquals(0, atenciones.recetaCount());
        assertEquals(150, medicamentos.findById(5).orElseThrow().stock(), "stock rolled back");
        assertEquals(EstadoCita.EN_CURSO, citas.raw(2).estado(), "cita state rolled back");
    }

    @Test
    void validationMatrix() {
        ValidationException ex = invalidClose(new AtencionInput(2L, " ", null, "n".repeat(1001),
            "o".repeat(501), List.of(new AtencionInput.RecetaInput(null, null, "", " ", "x".repeat(201)))));
        assertEquals(Validation.REQUIRED, ex.errors().get("diagnostico"));
        assertEquals(Validation.REQUIRED, ex.errors().get("tratamiento"));
        assertEquals("Máximo 1000 caracteres", ex.errors().get("notas"));
        assertEquals("Máximo 500 caracteres", ex.errors().get("observaciones"));
        assertEquals(Validation.REQUIRED, ex.errors().get("recetas[0].idMedicamento"));
        assertEquals(Validation.REQUIRED, ex.errors().get("recetas[0].cantidad"));
        assertEquals(Validation.REQUIRED, ex.errors().get("recetas[0].dosis"));
        assertEquals(Validation.REQUIRED, ex.errors().get("recetas[0].duracion"));
        assertEquals("Máximo 200 caracteres", ex.errors().get("recetas[0].observacionNoDisponible"));

        assertEquals("Máximo 500 caracteres",
            invalidClose(new AtencionInput(2L, "d".repeat(501), "t", null, null, List.of())).errors().get("diagnostico"));
        assertEquals(Validation.NOT_EXISTS,
            invalidClose(closing(2, line(999, 1, null))).errors().get("recetas[0].idMedicamento"));
        assertEquals(AtencionService.CANTIDAD_RANGE,
            invalidClose(closing(2, line(5, 0, null))).errors().get("recetas[0].cantidad"));
        assertEquals(AtencionService.CANTIDAD_RANGE,
            invalidClose(closing(2, line(5, 10000, null))).errors().get("recetas[0].cantidad"));
        assertEquals(Validation.NOT_AN_INTEGER,
            invalidClose(closing(2, line(5, 2.5, null))).errors().get("recetas[0].cantidad"));
        assertEquals(Validation.REQUIRED,
            assertThrows(ValidationException.class, () -> service.finalizar(
                new AtencionInput(null, "d", "t", null, null, List.of()))).errors().get("idCita"));
        // index is part of the field name
        ValidationException second = invalidClose(closing(2, line(5, 1, null), line(999, 1, null)));
        assertEquals(Validation.NOT_EXISTS, second.errors().get("recetas[1].idMedicamento"));
    }

    // ------------------------------------------------------------ reads

    @Test
    void historiaDeCitaRespectsTheScope() {
        service.finalizar(closing(2, line(5, 1, null)));
        HistoriaView own = service.historiaDeCita(2);
        assertEquals("Gripe", own.historia().diagnostico());
        assertEquals(1, own.recetas().size());

        current = LOPEZ;
        assertThrows(NotFoundException.class, () -> service.historiaDeCita(2));
        current = JUAN;
        assertEquals("Gripe", service.historiaDeCita(2).historia().diagnostico());
        current = MARIA;
        assertThrows(NotFoundException.class, () -> service.historiaDeCita(2));
        current = ADMIN;
        assertEquals("Gripe", service.historiaDeCita(2).historia().diagnostico());
        assertThrows(NotFoundException.class, () -> service.historiaDeCita(1)); // no historia yet
        assertThrows(NotFoundException.class, () -> service.historiaDeCita(999));
    }

    @Test
    void historialPacienteScopeMatrix() {
        service.finalizar(closing(2, line(5, 1, null)));
        pacientes.markAttended(21L, 10L); // juan is garcia's patient

        current = ADMIN;
        Page<HistoriaView> admin = service.historialPaciente(21, 1, 10);
        assertEquals(1, admin.total());
        assertEquals(List.of(), admin.items().get(0).recetas(), "list rows carry no recetas");

        current = GARCIA;
        assertEquals(1, service.historialPaciente(21, 1, 10).total());
        assertThrows(NotFoundException.class, () -> service.historialPaciente(20, 1, 10), "not attended yet");

        current = JUAN;
        assertEquals(1, service.historialPaciente(21, 1, 10).total());
        assertThrows(NotFoundException.class, () -> service.historialPaciente(20, 1, 10));

        current = LOPEZ;
        assertThrows(NotFoundException.class, () -> service.historialPaciente(21, 1, 10));
    }

    @Test
    void misRecetasIsPacienteOnlyAndScopedToTheOwnPatient() {
        service.finalizar(closing(2, line(5, 1, null), line(6, 99, "No hay")));
        current = JUAN;
        Page<RecetaView> mine = service.misRecetas(1, 10);
        assertEquals(2, mine.total());
        assertTrue(mine.items().stream().allMatch(r -> r.receta().fechaHistoria() != null));
        current = MARIA;
        assertEquals(0, service.misRecetas(1, 10).total());
        for (SessionContext session : List.of(ADMIN, GARCIA)) {
            current = session;
            assertThrows(PermissionException.class, () -> service.misRecetas(1, 10));
        }
    }

    @Test
    void medicamentosParaRecetarIsForMedicoAndAdmin() {
        assertEquals(List.of("MED-001", "MED-005"),
            service.medicamentosParaRecetar().stream().map(Medicamento::codigo).toList());
        current = ADMIN;
        assertEquals(2, service.medicamentosParaRecetar().size());
        current = MARIA;
        assertThrows(PermissionException.class, () -> service.medicamentosParaRecetar());
    }

    // ------------------------------------------------------------ boxed ids, scope and limits

    @Test
    void scopeWorksForIdsAboveTheLongCache() {
        // ids above 127 are not cached: comparing boxed Longs with == would deny access here
        medicos.add(new Medico(1010L, "9001", "Big", "Id", "Medicina General", null, null, true));
        pacientes.add(new Paciente(2020L, "9002", "Big", "Patient", LocalDate.of(1990, 1, 1), "F",
            null, null, null, true));
        citas.add(new Cita(3030L, 1010L, 2020L, ZONE.at(TODAY, LocalTime.of(12, 0)), "Grande",
            EstadoCita.EN_CURSO, ZONE.at(TODAY, LocalTime.of(12, 1)), null, null));
        pacientes.markAttended(2020L, 1010L);
        SessionContext bigMedico = new SessionContext(900, "dr.big", Rol.MEDICO, 1010L, "Big Id");
        SessionContext bigPaciente = new SessionContext(901, "big.paciente", Rol.PACIENTE, 2020L, "Big Patient");

        current = bigMedico;
        service.finalizar(closing(3030, line(5, 1, null)));
        assertEquals("Gripe", service.historiaDeCita(3030).historia().diagnostico());
        assertEquals(1, service.historialPaciente(2020, 1, 10).total());

        current = bigPaciente;
        assertEquals("Gripe", service.historiaDeCita(3030).historia().diagnostico());
        assertEquals(1, service.historialPaciente(2020, 1, 10).total());
        assertEquals(1, service.misRecetas(1, 10).total());
    }

    @Test
    void medicoOnlySeesItsOwnEntriesOfASharedPatient() {
        // garcia closes cita 2 (juan); lopez closes a cita of the same patient
        pacientes.markAttended(21L, 10L); // juan is also garcia's patient
        service.finalizar(closing(2, line(5, 1, null)));
        citas.add(new Cita(10L, 11L, 21L, ZONE.at(TODAY, LocalTime.of(14, 0)), "Otra", EstadoCita.EN_CURSO,
            ZONE.at(TODAY, LocalTime.of(14, 1)), null, null));
        pacientes.markAttended(21L, 11L);
        current = LOPEZ;
        service.finalizar(new AtencionInput(10L, "Otro diagnostico", "Otro tratamiento", null, null, List.of()));

        current = ADMIN;
        assertEquals(2, service.historialPaciente(21, 1, 10).total(), "ADMIN sees every entry");

        current = GARCIA;
        Page<HistoriaView> mine = service.historialPaciente(21, 1, 10);
        assertEquals(1, mine.total(), "a MEDICO only sees the entries it wrote");
        assertEquals("Gripe", mine.items().get(0).historia().diagnostico());

        current = LOPEZ;
        Page<HistoriaView> theirs = service.historialPaciente(21, 1, 10);
        assertEquals(1, theirs.total());
        assertEquals("Otro diagnostico", theirs.items().get(0).historia().diagnostico());

        current = JUAN;
        assertEquals(2, service.historialPaciente(21, 1, 10).total(), "the patient sees the whole record");
    }

    @Test
    void stockRaceRollsEverythingBackAndReportsAConflict() {
        atenciones.failDecrementStock = true;
        ConflictException ex = assertThrows(ConflictException.class,
            () -> service.finalizar(closing(2, line(5, 1, null))));
        assertEquals(AtencionService.STOCK_RACE, ex.userMessage());
        assertEquals("El stock cambió durante la atención. Intente finalizar nuevamente.", ex.userMessage());
        assertEquals(0, atenciones.historiaCount(), "historia rolled back");
        assertEquals(0, atenciones.recetaCount());
        assertEquals(150, medicamentos.findById(5).orElseThrow().stock());
        assertEquals(EstadoCita.EN_CURSO, citas.raw(2).estado());
    }

    @Test
    void anInactiveMedicoAccountCannotWork() {
        medicos.replace(new Medico(10L, "1001", "Carlos", "Garcia", "Medicina General", null, null, false));
        assertThrows(PermissionException.class, () -> service.finalizar(closing(2)));
        assertEquals(EstadoCita.EN_CURSO, citas.raw(2).estado());
        assertThrows(PermissionException.class, () -> service.iniciar(1));
    }

    @Test
    void closingAnAlreadyFinishedCitaSaysSo() {
        service.finalizar(closing(2));
        ConflictException closed = assertThrows(ConflictException.class, () -> service.finalizar(closing(2)));
        assertEquals("La atención ya fue finalizada", closed.userMessage());
        // a cita that was never started reports the generic message instead
        ConflictException notStarted = assertThrows(ConflictException.class, () -> service.finalizar(closing(1)));
        assertEquals(AtencionService.NOT_EN_CURSO, notStarted.userMessage());
        citas.replace(new Cita(1L, 10L, 20L, citas.raw(1).fechaHoraProgramada(), "Control", EstadoCita.CANCELADA,
            null, null, null));
        ConflictException cancelled = assertThrows(ConflictException.class, () -> service.finalizar(closing(1)));
        assertEquals(AtencionService.NOT_EN_CURSO, cancelled.userMessage());
    }

    @Test
    void aDuplicateHistoriaIsReportedAsAlreadyClosed() {
        service.finalizar(closing(2));
        // the cita is forced back to EN_CURSO: the historia is still there and must win
        Cita cita = citas.raw(2);
        citas.replace(new Cita(cita.id(), cita.idMedico(), cita.idPaciente(), cita.fechaHoraProgramada(),
            cita.motivo(), EstadoCita.EN_CURSO, cita.horaInicioReal(), null, null));
        ConflictException ex = assertThrows(ConflictException.class, () -> service.finalizar(closing(2)));
        assertEquals("La atención ya fue finalizada", ex.userMessage());
        assertEquals(1, atenciones.historiaCount());
    }

    @Test
    void recetasAreCappedAtFiftyLines() {
        AtencionInput.RecetaInput[] lines = new AtencionInput.RecetaInput[AtencionService.MAX_RECETAS + 1];
        for (int i = 0; i < lines.length; i++) {
            lines[i] = line(5, 1, null);
        }
        ValidationException ex = invalidClose(closing(2, lines));
        assertEquals("Máximo 50 medicamentos por atención", ex.errors().get("recetas"));
        assertEquals(1, ex.errors().size(), "the per-line checks are skipped once the cap is hit");
    }

    @Test
    void negativeCantidadReportsTheRange() {
        assertEquals(AtencionService.CANTIDAD_RANGE,
            invalidClose(closing(2, line(5, -1, null))).errors().get("recetas[0].cantidad"));
        assertEquals(AtencionService.CANTIDAD_RANGE,
            invalidClose(closing(2, line(5, 1000000000L, null))).errors().get("recetas[0].cantidad"));
        assertEquals(1, service.finalizar(closing(2, line(5, "5", null))).recetas().get(0).receta().cantidad() / 5,
            "numeric strings are accepted");
    }

    @Test
    void threeLinesOfTheSameMedicamentoWithMixedAvailability() {
        // stock 2 of medicamento 6: 1 fits, the next 1 fits, the third does not
        Atencion result = service.finalizar(closing(2,
            line(6, 1, null), line(6, 1, null), line(6, 1, "Ya no queda")));
        assertEquals(List.of(true, true, false),
            result.recetas().stream().map(r -> r.receta().entregado()).toList());
        assertEquals(0, medicamentos.findById(6).orElseThrow().stock());
        assertEquals(List.of("medico:10", "medicamento:6"), atenciones.locks);
    }

    @Test
    void recetasKeepTheirOrderAndPagingWorks() {
        service.finalizar(closing(2, line(5, 1, null), line(6, 1, null), line(7, 1, "Inactivo")));
        current = JUAN;
        Page<RecetaView> first = service.misRecetas(1, 2);
        assertEquals(3, first.total());
        assertEquals(2, first.items().size());
        Page<RecetaView> second = service.misRecetas(2, 2);
        assertEquals(1, second.items().size());
        List<Long> ids = new java.util.ArrayList<>();
        first.items().forEach(r -> ids.add(r.receta().id()));
        second.items().forEach(r -> ids.add(r.receta().id()));
        assertEquals(List.of(1L, 2L, 3L), ids, "lines of one historia keep their insertion order");
    }

    @Test
    void historialPacientePaging() {
        pacientes.markAttended(21L, 10L);
        service.finalizar(closing(2));
        citas.add(new Cita(11L, 10L, 21L, ZONE.at(TODAY, LocalTime.of(15, 0)), "Otra", EstadoCita.EN_CURSO,
            ZONE.at(TODAY, LocalTime.of(15, 1)), null, null));
        service.finalizar(new AtencionInput(11L, "Segunda", "Tratamiento", null, null, List.of()));
        Page<HistoriaView> first = service.historialPaciente(21, 1, 1);
        assertEquals(2, first.total());
        assertEquals(2, first.totalPages());
        assertEquals("Segunda", first.items().get(0).historia().diagnostico(), "newest first");
        Page<HistoriaView> second = service.historialPaciente(21, 2, 1);
        assertEquals("Gripe", second.items().get(0).historia().diagnostico());
    }

    @Test
    void storedRecetaKeepsTheMedicamentoDisplayColumns() {
        Atencion result = service.finalizar(closing(2, line(5, 1, null)));
        RecetaView view = result.recetas().get(0);
        assertEquals("MED-001", view.codigo());
        assertEquals("Acetaminofen", view.nombre());
        assertEquals("Caja x 20", view.presentacion());
        Receta stored = atenciones.recetasOf(result.idHistoria()).get(0);
        assertEquals(1, stored.cantidad());
        assertEquals("1 cada 8 horas", stored.dosis());
        assertEquals("3 dias", stored.duracion());
    }
}
