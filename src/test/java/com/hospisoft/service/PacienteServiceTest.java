package com.hospisoft.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hospisoft.dao.InMemoryPacienteRepository;
import com.hospisoft.dao.PacienteFilter;
import com.hospisoft.model.Paciente;
import com.hospisoft.model.Page;
import com.hospisoft.model.Rol;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PacienteServiceTest {

    private static final SessionContext ADMIN = new SessionContext(1, "admin", Rol.ADMIN, null, "Administrador");
    private static final SessionContext MEDICO = new SessionContext(2, "dr.garcia", Rol.MEDICO, 10L, "Carlos Garcia");
    private static final SessionContext PACIENTE = new SessionContext(3, "paciente1", Rol.PACIENTE, 20L, "Maria");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 26);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-26T12:00:00Z"), ZoneOffset.UTC);

    private InMemoryPacienteRepository repository;
    private SessionContext current;
    private PacienteService service;

    private static Paciente paciente(Long id, String documento, String nombres, String apellidos, LocalDate birth,
                                     String sexo, boolean activo) {
        return new Paciente(id, documento, nombres, apellidos, birth, sexo, "3001112233", "p@x.co", "Calle 1", activo);
    }

    @BeforeEach
    void setUp() {
        repository = new InMemoryPacienteRepository();
        repository.add(paciente(20L, "2001", "Maria", "Torres", LocalDate.of(1990, 5, 14), "F", true));
        repository.add(paciente(21L, "2002", "Juan", "Martinez", LocalDate.of(1985, 11, 2), "M", true));
        repository.add(paciente(22L, "2003", "Ana", "Ramirez", LocalDate.of(2015, 3, 21), "F", false));
        repository.add(paciente(23L, "2004", "Pedro", "Sanchez", LocalDate.of(1958, 8, 9), "M", true));
        repository.add(paciente(24L, "2005", "Luis", "Vega", LocalDate.of(1970, 1, 1), "M", true));
        repository.markAttended(20L, 10L);      // dr.garcia attended Maria
        repository.markAttended(23L, 10L);      // and Pedro
        repository.markAttended(21L, 11L);      // Juan was attended by another medico
        repository.markCita(24L, 10L, true);    // Luis: only a cancelled cita with dr.garcia
        current = ADMIN;
        service = new PacienteService(repository, () -> Optional.ofNullable(current), CLOCK);
    }

    // ------------------------------------------------------------ permissions

    @Test
    void pacienteRoleHasNoAccess() {
        current = PACIENTE;
        assertThrows(PermissionException.class, () -> service.list(PacienteFilter.NONE, 1, 10));
        assertThrows(PermissionException.class, () -> service.get(20));
        assertThrows(PermissionException.class, () -> service.save(paciente(null, "9", "A", "B", TODAY, "M", true)));
        assertThrows(PermissionException.class, () -> service.delete(20));
    }

    @Test
    void medicoIsReadOnly() {
        current = MEDICO;
        assertThrows(PermissionException.class, () -> service.save(paciente(null, "9", "A", "B", TODAY, "M", true)));
        assertThrows(PermissionException.class, () -> service.save(paciente(20L, "2001", "A", "B", TODAY, "M", true)));
        assertThrows(PermissionException.class, () -> service.delete(20));
    }

    @Test
    void medicoOnlySeesPatientsWithANonCancelledCita() {
        current = MEDICO;
        Page<Paciente> page = service.list(PacienteFilter.NONE, 1, 10);
        assertEquals(2, page.total());
        assertEquals("Sanchez", page.items().get(0).apellidos());
        assertEquals("Torres", page.items().get(1).apellidos());
        assertEquals(1, service.list(new PacienteFilter("F", null, null), 1, 10).total());
        assertEquals(0, service.list(new PacienteFilter(null, null, "Juan"), 1, 10).total());
        assertEquals(0, service.list(new PacienteFilter(null, null, "Vega"), 1, 10).total(), "cancelled grants nothing");
        assertEquals("Maria", service.get(20).nombres());
    }

    @Test
    void medicoScopeWorksForIdsAboveTheLongCache() {
        // ids above 127 are not cached: a reference comparison would hide the patient from its own medico
        repository.add(paciente(2020L, "9002", "Big", "Patient", LocalDate.of(1990, 1, 1), "F", true));
        repository.markAttended(2020L, 1010L);
        current = new SessionContext(900, "dr.big", Rol.MEDICO, 1010L, "Big Id");
        assertEquals("Big", service.get(2020).nombres());
        assertEquals(1, service.list(PacienteFilter.NONE, 1, 10).total());
    }

    @Test
    void medicoGetsNotFoundForOutOfScopeAndMissingIdsAlike() {
        current = MEDICO;
        assertThrows(NotFoundException.class, () -> service.get(21));   // attended by someone else
        assertThrows(NotFoundException.class, () -> service.get(24));   // only a cancelled cita
        assertThrows(NotFoundException.class, () -> service.get(999)); // does not exist
    }

    @Test
    void medicoSessionWithoutLinkedRowIsForbidden() {
        current = new SessionContext(9, "dr.x", Rol.MEDICO, null, "X");
        assertThrows(PermissionException.class, () -> service.list(PacienteFilter.NONE, 1, 10));
    }

    @Test
    void noSessionIsForbidden() {
        current = null;
        assertThrows(PermissionException.class, () -> service.list(PacienteFilter.NONE, 1, 10));
    }

    // ------------------------------------------------------------ list / get (admin)

    @Test
    void adminListsEverythingWithFiltersAndPaging() {
        Page<Paciente> all = service.list(null, 1, 3);
        assertEquals(5, all.total());
        assertEquals(3, all.items().size());
        assertEquals(2, all.totalPages());
        assertEquals("Martinez", all.items().get(0).apellidos());
        assertEquals(2, service.list(PacienteFilter.NONE, 2, 3).items().size());
        assertEquals(3, service.list(new PacienteFilter("M", null, null), 1, 10).total());
        assertEquals(1, service.list(new PacienteFilter(null, false, null), 1, 10).total());
        assertEquals(1, service.list(new PacienteFilter(null, null, "ram"), 1, 10).total());
        assertEquals("Pedro", service.get(23).nombres());
        assertThrows(NotFoundException.class, () -> service.get(999));
    }

    @Test
    void freeTextFilterIsCappedAt100Characters() {
        ValidationException ex = assertThrows(ValidationException.class,
            () -> service.list(new PacienteFilter(null, null, "x".repeat(101)), 1, 10));
        assertEquals("Máximo 100 caracteres", ex.errors().get("texto"));
    }

    // ------------------------------------------------------------ save

    @Test
    void insertTrimsNormalizesAndAssignsId() {
        Paciente saved = service.save(new Paciente(0L, " 3001 ", " Eva ", " Mora ", LocalDate.of(2000, 1, 1), " f ",
            "", "  ", null, true));
        assertTrue(saved.id() > 0);
        assertEquals("3001", saved.documento());
        assertEquals("Eva", saved.nombres());
        assertEquals("F", saved.sexo());
        assertNull(saved.telefono());
        assertNull(saved.correo());
        assertNull(saved.direccion());
        assertEquals(6, repository.size());
    }

    @Test
    void updateKeepsIdAndChecksExistence() {
        Paciente updated = service.save(paciente(20L, "2001", "Maria Fernanda", "Torres", LocalDate.of(1990, 5, 14), "F", false));
        assertEquals(20L, updated.id());
        assertEquals("Maria Fernanda", repository.findById(20).orElseThrow().nombres());
        assertFalse(repository.findById(20).orElseThrow().activo());
        assertThrows(NotFoundException.class,
            () -> service.save(paciente(999L, "5555", "X", "Y", TODAY, "M", true)));
    }

    @Test
    void activoNullKeepsStoredValueOnUpdateAndDefaultsTrueOnInsert() {
        repository.setActivo(20, false);
        Paciente kept = service.save(paciente(20L, "2001", "Maria", "Torres", LocalDate.of(1990, 5, 14), "F", true), null);
        assertFalse(kept.activo());
        assertFalse(repository.findById(20).orElseThrow().activo());
        Paciente inserted = service.save(paciente(null, "6001", "N", "A", TODAY, "M", false), null);
        assertTrue(inserted.activo());
    }

    @Test
    void validationMatrix() {
        ValidationException ex = assertThrows(ValidationException.class, () -> service.save(
            new Paciente(null, "", " ", null, null, "X", "t".repeat(21), "bad@", "d".repeat(161), true)));
        assertEquals(Validation.REQUIRED, ex.errors().get("documento"));
        assertEquals(Validation.REQUIRED, ex.errors().get("nombres"));
        assertEquals(Validation.REQUIRED, ex.errors().get("apellidos"));
        assertEquals(Validation.REQUIRED, ex.errors().get("fechaNacimiento"));
        assertEquals(PacienteService.INVALID_SEXO, ex.errors().get("sexo"));
        assertEquals("Máximo 20 caracteres", ex.errors().get("telefono"));
        assertEquals(Validation.INVALID_EMAIL, ex.errors().get("correo"));
        assertEquals("Máximo 160 caracteres", ex.errors().get("direccion"));
        assertEquals(8, ex.errors().size());
    }

    @Test
    void birthDateMustBeBetween1900AndToday() {
        ValidationException future = assertThrows(ValidationException.class,
            () -> service.save(paciente(null, "4001", "A", "B", TODAY.plusDays(1), "M", true)));
        assertEquals(PacienteService.FUTURE_BIRTH_DATE, future.errors().get("fechaNacimiento"));
        ValidationException old = assertThrows(ValidationException.class,
            () -> service.save(paciente(null, "4002", "A", "B", LocalDate.of(1899, 12, 31), "M", true)));
        assertEquals(PacienteService.BIRTH_DATE_TOO_OLD, old.errors().get("fechaNacimiento"));
        service.save(paciente(null, "4003", "A", "B", TODAY, "M", true));          // born today is fine
        service.save(paciente(null, "4004", "A", "B", LocalDate.of(1900, 1, 1), "OTRO", true));
    }

    @Test
    void sexoMustBeMFOrOtroAndIsUpperCased() {
        ValidationException ex = assertThrows(ValidationException.class,
            () -> service.save(paciente(null, "4005", "A", "B", TODAY, "Masculino", true)));
        assertEquals(PacienteService.INVALID_SEXO, ex.errors().get("sexo"));
        ValidationException missing = assertThrows(ValidationException.class,
            () -> service.save(paciente(null, "4006", "A", "B", TODAY, " ", true)));
        assertEquals(Validation.REQUIRED, missing.errors().get("sexo"));
        assertEquals("OTRO", service.save(paciente(null, "4007", "A", "B", TODAY, "otro", true)).sexo());
    }

    @Test
    void documentoMustBeUniqueExcludingSelf() {
        ValidationException ex = assertThrows(ValidationException.class,
            () -> service.save(paciente(null, "2001", "Otra", "Persona", TODAY, "F", true)));
        assertEquals(Validation.DUPLICATE_DOCUMENTO, ex.errors().get("documento"));
        service.save(paciente(20L, "2001", "Maria", "Torres", LocalDate.of(1990, 5, 14), "F", true)); // self
        assertThrows(ValidationException.class,
            () -> service.save(paciente(20L, "2002", "Maria", "Torres", LocalDate.of(1990, 5, 14), "F", true)));
    }

    // ------------------------------------------------------------ delete

    @Test
    void deleteWithoutHistoryRemovesTheRow() {
        assertEquals(DeleteOutcome.DELETED, service.delete(22));
        assertTrue(repository.findById(22).isEmpty());
    }

    @Test
    void deleteWithHistoryDeactivatesInstead() {
        assertEquals(DeleteOutcome.DEACTIVATED, service.delete(20));
        assertFalse(repository.findById(20).orElseThrow().activo());
        assertThrows(NotFoundException.class, () -> service.delete(999));
    }

    @Test
    void foreignKeyRaceOnDeleteDeactivatesInstead() {
        repository.failDeleteWithHistory(22);
        assertEquals(DeleteOutcome.DEACTIVATED, service.delete(22));
        assertFalse(repository.findById(22).orElseThrow().activo());
    }

    // ------------------------------------------------------------ age

    @Test
    void ageIsComputedFromTheServiceClock() {
        assertEquals(TODAY, service.today());
        assertEquals(36, repository.findById(20).orElseThrow().edad(service.today()));
        assertEquals(11, repository.findById(22).orElseThrow().edad(service.today()));
    }

    @Test
    void ageBoundaries() {
        Paciente p = new Paciente(1L, "1", "A", "B", LocalDate.of(1990, 5, 14), "F", null, null, null, true);
        assertEquals(36, p.edad(LocalDate.of(2026, 10, 26)));
        assertEquals(35, p.edad(LocalDate.of(2026, 5, 13)));
        assertEquals(36, p.edad(LocalDate.of(2026, 5, 14)));
        assertNull(p.edad(LocalDate.of(1989, 1, 1)));
        Paciente leap = new Paciente(2L, "2", "A", "B", LocalDate.of(2000, 2, 29), "M", null, null, null, true);
        assertEquals(0, leap.edad(LocalDate.of(2001, 2, 28)));
        assertEquals(1, leap.edad(LocalDate.of(2001, 3, 1)));
        assertEquals(4, leap.edad(LocalDate.of(2004, 2, 29)));
        Paciente unknown = new Paciente(3L, "3", "A", "B", null, "F", null, null, null, true);
        assertNull(unknown.edad(LocalDate.of(2026, 1, 1)));
    }
}
