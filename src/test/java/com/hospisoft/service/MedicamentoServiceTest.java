package com.hospisoft.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hospisoft.dao.InMemoryMedicamentoRepository;
import com.hospisoft.dao.MedicamentoFilter;
import com.hospisoft.dao.MedicamentoRepository;
import com.hospisoft.model.Medicamento;
import com.hospisoft.model.Page;
import com.hospisoft.model.Rol;
import com.hospisoft.model.StockState;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MedicamentoServiceTest {

    private static final SessionContext ADMIN = new SessionContext(1, "admin", Rol.ADMIN, null, "Administrador");
    private static final SessionContext MEDICO = new SessionContext(2, "dr.garcia", Rol.MEDICO, 10L, "Carlos");
    private static final SessionContext PACIENTE = new SessionContext(3, "paciente1", Rol.PACIENTE, 20L, "Maria");

    private InMemoryMedicamentoRepository repository;
    private SessionContext current;
    private MedicamentoService service;

    /** Insert input (no stockExpected). */
    private static MedicamentoInput input(String codigo, String nombre, String presentacion, Object stock,
                                          Object minimo) {
        return new MedicamentoInput(null, codigo, nombre, null, presentacion, stock, minimo, null);
    }

    /** Update input carrying the stock the client loaded. */
    private static MedicamentoInput update(long id, String codigo, String nombre, String presentacion, Object stock,
                                           Object minimo, Long stockExpected) {
        return new MedicamentoInput(id, codigo, nombre, null, presentacion, stock, minimo, stockExpected);
    }

    @BeforeEach
    void setUp() {
        repository = new InMemoryMedicamentoRepository();
        repository.add(new Medicamento(1L, "MED-001", "Acetaminofen", "Analgesico", "Caja x 20", 150, 30, true));
        repository.add(new Medicamento(2L, "MED-002", "Ibuprofeno", null, "Caja x 10", 25, 30, true));
        repository.add(new Medicamento(3L, "MED-005", "Loratadina", null, "Caja x 10", 0, 15, true));
        repository.add(new Medicamento(4L, "MED-010", "Diclofenaco", null, "Tubo", 35, 10, false));
        current = ADMIN;
        service = new MedicamentoService(repository, () -> Optional.ofNullable(current));
    }

    @Test
    void everyRoleMayReadButOnlyAdminWrites() {
        for (SessionContext session : List.of(ADMIN, MEDICO, PACIENTE)) {
            current = session;
            assertEquals(4, service.list(MedicamentoFilter.NONE, 1, 10).total());
            assertEquals("Ibuprofeno", service.get(2).nombre());
        }
        for (SessionContext session : List.of(MEDICO, PACIENTE)) {
            current = session;
            assertThrows(PermissionException.class, () -> service.save(input("MED-X", "N", "P", 1L, 1L), null));
            assertThrows(PermissionException.class, () -> service.delete(1));
        }
        current = null;
        assertThrows(PermissionException.class, () -> service.list(MedicamentoFilter.NONE, 1, 10));
    }

    @Test
    void listFiltersAndStockStates() {
        Page<Medicamento> page = service.list(null, 1, 2);
        assertEquals(4, page.total());
        assertEquals("Acetaminofen", page.items().get(0).nombre());
        assertEquals(1, service.list(new MedicamentoFilter("ibu", null, null), 1, 10).total());
        assertEquals(1, service.list(new MedicamentoFilter("tubo", null, null), 1, 10).total());
        assertEquals(1, service.list(new MedicamentoFilter(null, false, null), 1, 10).total());
        assertEquals(1, service.list(new MedicamentoFilter(null, null, StockState.STOCK_BAJO), 1, 10).total());
        assertEquals(1, service.list(new MedicamentoFilter(null, null, StockState.AGOTADO), 1, 10).total());
        assertEquals(2, service.list(new MedicamentoFilter(null, null, StockState.DISPONIBLE), 1, 10).total());
        assertThrows(ValidationException.class,
            () -> service.list(new MedicamentoFilter("x".repeat(101), null, null), 1, 10));
        assertThrows(NotFoundException.class, () -> service.get(999));
    }

    @Test
    void insertTrimsAcceptsNumericStringsAndDefaultsActivo() {
        Medicamento saved = service.save(new MedicamentoInput(0L, " MED-020 ", " Omeprazol ", "  ", " Caja ", "40", 10.0, null), null);
        assertTrue(saved.id() > 0);
        assertEquals("MED-020", saved.codigo());
        assertNull(saved.descripcion());
        assertEquals(40, saved.stock());
        assertEquals(10, saved.stockMinimo());
        assertTrue(saved.activo());
        assertEquals(StockState.DISPONIBLE, saved.stockState());
        assertEquals(5, repository.size());
    }

    @Test
    void updateKeepsStoredActivoWhenOmittedAndRequiresStockExpected() {
        Medicamento kept = service.save(update(4, "MED-010", "Diclofenaco gel", "Tubo", 35L, 10L, 35L), null);
        assertFalse(kept.activo());
        Medicamento forced = service.save(update(4, "MED-010", "Diclofenaco gel", "Tubo", 35L, 10L, 35L), true);
        assertTrue(forced.activo());
        assertTrue(repository.findById(4).orElseThrow().activo());
        assertThrows(NotFoundException.class, () -> service.save(update(999, "X", "N", "P", 1L, 1L, 1L), null));

        ValidationException missing = assertThrows(ValidationException.class,
            () -> service.save(update(4, "MED-010", "Diclofenaco gel", "Tubo", 35L, 10L, null), null));
        assertEquals(Validation.RELOAD_RECORD, missing.errors().get("stock"));
    }

    @Test
    void staleStockIsAConflict() {
        // another session (phase 5 dispensing) changed the stock after the form was loaded
        repository.update(new Medicamento(1L, "MED-001", "Acetaminofen", "Analgesico", "Caja x 20", 140, 30, true), 150);
        ConflictException ex = assertThrows(ConflictException.class,
            () -> service.save(update(1, "MED-001", "Acetaminofen", "Caja x 20", 150L, 30L, 150L), null));
        assertEquals(ConflictException.MESSAGE, "El registro cambió desde que lo abrió. Recargue e intente de nuevo.");
        assertTrue(ex.getMessage().contains("stock"));
        assertEquals(140, repository.findById(1).orElseThrow().stock());
        // after reloading, the fresh expected value goes through
        Medicamento saved = service.save(update(1, "MED-001", "Acetaminofen", "Caja x 20", 160L, 30L, 140L), null);
        assertEquals(160, saved.stock());
    }

    @Test
    void validationMatrix() {
        ValidationException ex = assertThrows(ValidationException.class, () -> service.save(
            new MedicamentoInput(null, "", null, "d".repeat(201), " ", null, "abc", null), null));
        assertEquals(Validation.REQUIRED, ex.errors().get("codigo"));
        assertEquals(Validation.REQUIRED, ex.errors().get("nombre"));
        assertEquals(Validation.REQUIRED, ex.errors().get("presentacion"));
        assertEquals("Máximo 200 caracteres", ex.errors().get("descripcion"));
        assertEquals(Validation.REQUIRED, ex.errors().get("stock"));
        assertEquals(Validation.NOT_AN_INTEGER, ex.errors().get("stockMinimo"));
        assertEquals(6, ex.errors().size());
    }

    @Test
    void stockValuesMustBeNonNegativeIntegers() {
        ValidationException ex = assertThrows(ValidationException.class,
            () -> service.save(input("MED-030", "N", "P", 3.7, -1L), null));
        assertEquals(Validation.NOT_AN_INTEGER, ex.errors().get("stock"));
        assertEquals(Validation.NEGATIVE, ex.errors().get("stockMinimo"));
        ValidationException blank = assertThrows(ValidationException.class,
            () -> service.save(input("MED-033", "N", "P", "   ", 1L), null));
        assertEquals(Validation.REQUIRED, blank.errors().get("stock"));
        Medicamento zero = service.save(input("MED-032", "N", "P", 0L, 0L), null);
        assertEquals(StockState.AGOTADO, zero.stockState());
    }

    @Test
    void stockAboveIntRangeReportsTheBound() {
        String bound = "Debe ser menor o igual a 2147483647";
        assertEquals(bound, Validation.TOO_LARGE);
        ValidationException longValue = assertThrows(ValidationException.class,
            () -> service.save(input("MED-031", "N", "P", 3000000000L, 0L), null));
        assertEquals(bound, longValue.errors().get("stock"));
        ValidationException doubleValue = assertThrows(ValidationException.class,
            () -> service.save(input("MED-031", "N", "P", 1.0E30, 0L), null));
        assertEquals(bound, doubleValue.errors().get("stock"));
        ValidationException stringValue = assertThrows(ValidationException.class,
            () -> service.save(input("MED-031", "N", "P", "99999999999999999999", 0L), null));
        assertEquals(bound, stringValue.errors().get("stock"));
        ValidationException unexpectedType = assertThrows(ValidationException.class,
            () -> service.save(input("MED-031", "N", "P", java.math.BigDecimal.ONE, 0L), null));
        assertEquals(Validation.NOT_AN_INTEGER, unexpectedType.errors().get("stock"));
        assertEquals(Integer.MAX_VALUE, service.save(input("MED-034", "N", "P", (long) Integer.MAX_VALUE, 0L), null).stock());
    }

    @Test
    void maxLengthsFollowTheSchema() {
        ValidationException ex = assertThrows(ValidationException.class, () -> service.save(
            input("c".repeat(21), "n".repeat(101), "p".repeat(81), 1L, 1L), null));
        assertEquals("Máximo 20 caracteres", ex.errors().get("codigo"));
        assertEquals("Máximo 100 caracteres", ex.errors().get("nombre"));
        assertEquals("Máximo 80 caracteres", ex.errors().get("presentacion"));
    }

    @Test
    void codigoMustBeUniqueExcludingSelf() {
        ValidationException ex = assertThrows(ValidationException.class,
            () -> service.save(input("MED-001", "Otro", "P", 1L, 1L), null));
        assertEquals(Validation.DUPLICATE_CODIGO, ex.errors().get("codigo"));
        service.save(update(1, "MED-001", "Acetaminofen", "Caja x 20", 150L, 30L, 150L), null); // self
        assertThrows(ValidationException.class,
            () -> service.save(update(1, "MED-002", "Acetaminofen", "Caja x 20", 150L, 30L, 150L), null));
    }

    @Test
    void duplicateCodigoRaceFromTheDatabaseIsAValidationError() {
        // the pre-check passes (no such codigo yet) but the DAO reports 23505 on insert
        MedicamentoRepository racing = new InMemoryMedicamentoRepository() {
            @Override
            public long insert(Medicamento m) {
                throw new ValidationException(Map.of("codigo", Validation.DUPLICATE_CODIGO));
            }
        };
        MedicamentoService racingService = new MedicamentoService(racing, () -> Optional.of(ADMIN));
        ValidationException ex = assertThrows(ValidationException.class,
            () -> racingService.save(input("MED-040", "N", "P", 1L, 1L), null));
        assertEquals(Validation.DUPLICATE_CODIGO, ex.errors().get("codigo"));
    }

    @Test
    void deleteOutcomes() {
        assertEquals(DeleteOutcome.DELETED, service.delete(4));
        assertTrue(repository.findById(4).isEmpty());
        repository.markHistory(1);
        assertEquals(DeleteOutcome.DEACTIVATED, service.delete(1));
        assertFalse(repository.findById(1).orElseThrow().activo());
        repository.failDeleteWithHistory(2);
        assertEquals(DeleteOutcome.DEACTIVATED, service.delete(2));
        assertFalse(repository.findById(2).orElseThrow().activo());
        assertThrows(NotFoundException.class, () -> service.delete(999));
    }

    @Test
    void hooksForLaterPhases() {
        assertEquals(2, repository.countLowStock()); // MED-002 (25<=30) and MED-005 (0); MED-010 is inactive
        assertEquals(List.of("Acetaminofen", "Ibuprofeno", "Loratadina"),
            repository.findAllActive().stream().map(Medicamento::nombre).toList());
    }
}
