package com.hospisoft.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hospisoft.dao.InMemoryMedicoRepository;
import com.hospisoft.dao.MedicoFilter;
import com.hospisoft.model.Medico;
import com.hospisoft.model.Page;
import com.hospisoft.model.Rol;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MedicoServiceTest {

    private static final SessionContext ADMIN = new SessionContext(1, "admin", Rol.ADMIN, null, "Administrador");
    private static final SessionContext MEDICO = new SessionContext(2, "dr.garcia", Rol.MEDICO, 10L, "Carlos Garcia");
    private static final SessionContext PACIENTE = new SessionContext(3, "paciente1", Rol.PACIENTE, 20L, "Maria");

    private InMemoryMedicoRepository repository;
    private SessionContext current;
    private MedicoService service;

    private static Medico medico(Long id, String documento, String nombres, String apellidos, String especialidad,
                                 boolean activo) {
        return new Medico(id, documento, nombres, apellidos, especialidad, "3001112233", "x@y.co", activo);
    }

    @BeforeEach
    void setUp() {
        repository = new InMemoryMedicoRepository();
        repository.add(medico(10L, "1001", "Carlos", "Garcia", "Medicina General", true));
        repository.add(medico(11L, "1002", "Laura", "Lopez", "Pediatria", true));
        repository.add(medico(12L, "1003", "Jorge", "Ruiz", "Cardiologia", false));
        current = ADMIN;
        service = new MedicoService(repository, () -> Optional.ofNullable(current));
    }

    // ------------------------------------------------------------ permissions

    @Test
    void medicoAndPacienteRolesHaveNoAccess() {
        for (SessionContext session : List.of(MEDICO, PACIENTE)) {
            current = session;
            assertThrows(PermissionException.class, () -> service.list(MedicoFilter.NONE, 1, 10));
            assertThrows(PermissionException.class, () -> service.get(10));
            assertThrows(PermissionException.class, () -> service.especialidades());
            assertThrows(PermissionException.class, () -> service.save(medico(null, "9", "A", "B", "C", true)));
            assertThrows(PermissionException.class, () -> service.delete(10));
        }
    }

    @Test
    void noSessionIsForbidden() {
        current = null;
        assertThrows(PermissionException.class, () -> service.list(MedicoFilter.NONE, 1, 10));
    }

    // ------------------------------------------------------------ list / get

    @Test
    void listsWithFiltersAndPaging() {
        Page<Medico> all = service.list(MedicoFilter.NONE, 1, 2);
        assertEquals(3, all.total());
        assertEquals(2, all.items().size());
        assertEquals(2, all.totalPages());
        assertEquals("Garcia", all.items().get(0).apellidos());

        Page<Medico> second = service.list(MedicoFilter.NONE, 2, 2);
        assertEquals(1, second.items().size());
        assertEquals("Ruiz", second.items().get(0).apellidos());

        assertEquals(1, service.list(new MedicoFilter("Pediatria", null, null), 1, 10).total());
        assertEquals(2, service.list(new MedicoFilter(null, true, null), 1, 10).total());
        assertEquals(1, service.list(new MedicoFilter(null, null, "ruiz"), 1, 10).total());
        assertEquals(1, service.list(new MedicoFilter(null, null, "1002"), 1, 10).total());
        assertEquals(1, service.list(new MedicoFilter(null, null, "laura lopez"), 1, 10).total());
        assertEquals(3, service.list(null, 1, 10).total());
    }

    @Test
    void getReturnsRowOrNotFound() {
        assertEquals("Carlos", service.get(10).nombres());
        assertThrows(NotFoundException.class, () -> service.get(999));
    }

    @Test
    void especialidadesAreDistinctAndSorted() {
        assertEquals(List.of("Cardiologia", "Medicina General", "Pediatria"), service.especialidades());
    }

    // ------------------------------------------------------------ save

    @Test
    void insertTrimsAndAssignsId() {
        Medico saved = service.save(new Medico(0L, "  2001 ", "  Ana ", " Torres ", " Neurologia ", "  ", "", true));
        assertTrue(saved.id() > 0);
        assertEquals("2001", saved.documento());
        assertEquals("Ana", saved.nombres());
        assertEquals("Torres", saved.apellidos());
        assertEquals("Neurologia", saved.especialidad());
        assertNull(saved.telefono());
        assertNull(saved.correo());
        assertEquals(4, repository.size());
        assertEquals("Ana", repository.findById(saved.id()).orElseThrow().nombres());
    }

    @Test
    void updateKeepsIdAndChecksExistence() {
        Medico updated = service.save(medico(10L, "1001", "Carlos Andres", "Garcia", "Medicina General", false));
        assertEquals(10L, updated.id());
        assertEquals("Carlos Andres", repository.findById(10).orElseThrow().nombres());
        assertFalse(repository.findById(10).orElseThrow().activo());
        assertThrows(NotFoundException.class,
            () -> service.save(medico(999L, "5555", "X", "Y", "Z", true)));
    }

    @Test
    void validationMatrix() {
        ValidationException ex = assertThrows(ValidationException.class,
            () -> service.save(new Medico(null, " ", null, "", null, "x".repeat(21), "not-an-email", true)));
        assertEquals(ValidationException.MESSAGE, ex.getMessage());
        assertEquals(Validation.REQUIRED, ex.errors().get("documento"));
        assertEquals(Validation.REQUIRED, ex.errors().get("nombres"));
        assertEquals(Validation.REQUIRED, ex.errors().get("apellidos"));
        assertEquals(Validation.REQUIRED, ex.errors().get("especialidad"));
        assertEquals("Máximo 20 caracteres", ex.errors().get("telefono"));
        assertEquals(Validation.INVALID_EMAIL, ex.errors().get("correo"));
        assertEquals(6, ex.errors().size());
    }

    @Test
    void maxLengthsFollowTheSchema() {
        ValidationException ex = assertThrows(ValidationException.class, () -> service.save(new Medico(null,
            "d".repeat(21), "n".repeat(81), "a".repeat(81), "e".repeat(81), null, "c".repeat(116) + "@x.co", true)));
        assertEquals("Máximo 20 caracteres", ex.errors().get("documento"));
        assertEquals("Máximo 80 caracteres", ex.errors().get("nombres"));
        assertEquals("Máximo 80 caracteres", ex.errors().get("apellidos"));
        assertEquals("Máximo 80 caracteres", ex.errors().get("especialidad"));
        assertEquals("Máximo 120 caracteres", ex.errors().get("correo"));
    }

    @Test
    void optionalFieldsMayBeEmptyAndValidEmailPasses() {
        Medico saved = service.save(new Medico(null, "3001", "Eva", "Mora", "Dermatologia", null, "eva.mora@clinica.co", true));
        assertEquals("eva.mora@clinica.co", saved.correo());
    }

    @Test
    void documentoMustBeUniqueOnInsert() {
        ValidationException ex = assertThrows(ValidationException.class,
            () -> service.save(medico(null, "1001", "Otro", "Medico", "X", true)));
        assertEquals(Validation.DUPLICATE_DOCUMENTO, ex.errors().get("documento"));
        assertEquals(1, ex.errors().size());
    }

    @Test
    void documentoUniquenessExcludesSelfOnUpdate() {
        service.save(medico(10L, "1001", "Carlos", "Garcia", "Medicina General", true)); // same documento, same row
        ValidationException ex = assertThrows(ValidationException.class,
            () -> service.save(medico(10L, "1002", "Carlos", "Garcia", "Medicina General", true)));
        assertEquals(Validation.DUPLICATE_DOCUMENTO, ex.errors().get("documento"));
    }

    // ------------------------------------------------------------ delete

    @Test
    void deleteWithoutHistoryRemovesTheRow() {
        assertEquals(DeleteOutcome.DELETED, service.delete(12));
        assertTrue(repository.findById(12).isEmpty());
    }

    @Test
    void deleteWithHistoryDeactivatesInstead() {
        repository.markHistory(10);
        assertEquals(DeleteOutcome.DEACTIVATED, service.delete(10));
        Medico still = repository.findById(10).orElseThrow();
        assertFalse(still.activo());
    }

    @Test
    void foreignKeyRaceOnDeleteDeactivatesInstead() {
        repository.failDeleteWithHistory(12);
        assertEquals(DeleteOutcome.DEACTIVATED, service.delete(12));
        assertFalse(repository.findById(12).orElseThrow().activo());
    }

    @Test
    void activoNullKeepsStoredValueOnUpdateAndDefaultsTrueOnInsert() {
        repository.setActivo(10, false);
        Medico kept = service.save(medico(10L, "1001", "Carlos", "Garcia", "Medicina General", true), null);
        assertFalse(kept.activo());
        assertFalse(repository.findById(10).orElseThrow().activo());
        Medico forced = service.save(medico(10L, "1001", "Carlos", "Garcia", "Medicina General", false), true);
        assertTrue(forced.activo());
        Medico inserted = service.save(medico(null, "5001", "N", "A", "E", false), null);
        assertTrue(inserted.activo());
    }

    @Test
    void freeTextFilterIsCappedAt100Characters() {
        ValidationException ex = assertThrows(ValidationException.class,
            () -> service.list(new MedicoFilter(null, null, "x".repeat(101)), 1, 10));
        assertEquals(Validation.maxLengthMessage(100), ex.errors().get("texto"));
        assertEquals(0, service.list(new MedicoFilter(null, null, "x".repeat(100)), 1, 10).total());
    }

    @Test
    void deleteUnknownIsNotFound() {
        assertThrows(NotFoundException.class, () -> service.delete(999));
    }
}
