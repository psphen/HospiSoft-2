package com.hospisoft.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hospisoft.dao.AuditoriaQuery;
import com.hospisoft.dao.InMemoryAuditoriaRepository;
import com.hospisoft.db.DatabaseUnavailableException;
import com.hospisoft.model.AccionAuditoria;
import com.hospisoft.model.Auditoria;
import com.hospisoft.model.ModuloAuditoria;
import com.hospisoft.model.Page;
import com.hospisoft.model.Rol;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuditoriaServiceTest {

    private static final SessionContext ADMIN = new SessionContext(1, "admin", Rol.ADMIN, null, "Administrador");
    private static final SessionContext MEDICO = new SessionContext(2, "dr.garcia", Rol.MEDICO, 10L, "Carlos Garcia");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-04T15:00:00Z"), ZoneOffset.UTC);
    private static final AppZone ZONE = new AppZone(ZoneId.of("America/Bogota"));

    private InMemoryAuditoriaRepository repository;
    private SessionContext current;
    private final List<String> logged = new ArrayList<>();
    private AuditoriaService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryAuditoriaRepository(CLOCK);
        current = ADMIN;
        service = new AuditoriaService(repository, () -> Optional.ofNullable(current), ZONE, logged::add);
    }

    private Auditoria only() {
        assertEquals(1, repository.rows().size(), "expected exactly one audit row");
        return repository.rows().get(0);
    }

    @Test
    void recordStampsTheSessionUser() {
        current = MEDICO;
        service.record(AccionAuditoria.INICIAR_ATENCION, ModuloAuditoria.ATENCION, 12L, "Atención iniciada - cita #12");
        Auditoria row = only();
        assertEquals(2L, row.idUsuario());
        assertEquals("dr.garcia", row.username());
        assertEquals(Rol.MEDICO, row.rol());
        assertEquals(AccionAuditoria.INICIAR_ATENCION, row.accion());
        assertEquals(ModuloAuditoria.ATENCION, row.modulo());
        assertEquals(12L, row.idRegistro());
        assertEquals("Atención iniciada - cita #12", row.detalle());
    }

    @Test
    void recordWithoutASessionStillWritesAnAnonymousRow() {
        current = null;
        service.record(AccionAuditoria.CREAR, ModuloAuditoria.PACIENTE, 5L, "x");
        Auditoria row = only();
        assertNull(row.idUsuario());
        assertNull(row.username());
        assertNull(row.rol());
    }

    @Test
    void recordAsUsesTheGivenActorNotTheSession() {
        current = null;
        service.recordAs(MEDICO, AccionAuditoria.LOGIN, ModuloAuditoria.SESION, null, "Ingreso al sistema");
        Auditoria row = only();
        assertEquals(2L, row.idUsuario());
        assertEquals("dr.garcia", row.username());
        assertEquals(AccionAuditoria.LOGIN, row.accion());
    }

    @Test
    void failedLoginKeepsOnlyTheAttemptedUsername() {
        service.recordFailedLogin("  ghost  ", "Usuario o contraseña incorrectos");
        Auditoria row = only();
        assertNull(row.idUsuario());
        assertNull(row.rol());
        assertEquals("ghost", row.username());
        assertEquals(AccionAuditoria.LOGIN_FALLIDO, row.accion());
        assertEquals(ModuloAuditoria.SESION, row.modulo());
        assertNull(row.idRegistro());
    }

    @Test
    void aFailedLoginOfAnUnknownUserStoresNoUsername() {
        service.recordFailedLogin(null, "Usuario inexistente");
        Auditoria row = only();
        assertNull(row.username());
        assertNull(row.idUsuario());
        assertNull(row.rol());
        assertEquals("Usuario inexistente", row.detalle());
    }

    @Test
    void longValuesAreTruncatedToTheColumnLimits() {
        service.recordFailedLogin("u".repeat(200), "d".repeat(900));
        Auditoria row = only();
        assertEquals(AuditoriaService.MAX_USERNAME, row.username().length());
        assertEquals(AuditoriaService.MAX_DETALLE, row.detalle().length());
    }

    @Test
    void truncationNeverSplitsASurrogatePair() {
        String detalle = "a".repeat(AuditoriaService.MAX_DETALLE - 1) + "😀tail";
        String cut = AuditoriaService.truncate(detalle, AuditoriaService.MAX_DETALLE);
        assertEquals(AuditoriaService.MAX_DETALLE - 1, cut.length());
        assertFalse(Character.isHighSurrogate(cut.charAt(cut.length() - 1)));
        assertEquals("abc", AuditoriaService.truncate("abc", 10));
        assertNull(AuditoriaService.truncate("   ", 10), "blank text is stored as NULL");
        assertNull(AuditoriaService.truncate(null, 10));
    }

    @Test
    void aWriteFailureIsSwallowedAndLoggedWithoutContents() {
        repository.failInserts(() -> new DatabaseUnavailableException("down",
            new SQLException("Key (documento)=(2001) secret", "08006")));
        assertDoesNotThrow(() -> service.record(AccionAuditoria.CREAR, ModuloAuditoria.PACIENTE, 20L,
            "Paciente 2001 - Maria Torres"));
        assertDoesNotThrow(() -> service.recordFailedLogin("ghost", "Usuario o contraseña incorrectos"));
        assertDoesNotThrow(() -> service.recordAs(ADMIN, AccionAuditoria.LOGOUT, ModuloAuditoria.SESION, null, "x"));

        assertEquals(3, logged.size());
        String line = logged.get(0);
        assertTrue(line.startsWith("[audit] "), line);
        assertTrue(line.contains("CREAR") && line.contains("PACIENTE"), line);
        assertTrue(line.contains(DatabaseUnavailableException.class.getName()), line);
        assertTrue(line.contains("SQLState 08006"), line);
        for (String entry : logged) {
            assertFalse(entry.contains("Maria") || entry.contains("2001") || entry.contains("ghost")
                || entry.contains("secret"), "the log must not carry row data: " + entry);
        }
    }

    @Test
    void anyRuntimeFailureIsSwallowed() {
        repository.failInserts(() -> new IllegalStateException("boom"));
        assertDoesNotThrow(() -> service.record(AccionAuditoria.EDITAR, ModuloAuditoria.MEDICO, 1L, "x"));
        assertEquals(1, logged.size());
        assertFalse(logged.get(0).contains("boom"), "exception messages are not logged");
    }

    @Test
    void aFailingSessionSupplierIsSwallowedToo() {
        AuditoriaService broken = new AuditoriaService(repository, () -> {
            throw new IllegalStateException("no session store");
        }, ZONE, logged::add);
        assertDoesNotThrow(() -> broken.record(AccionAuditoria.CREAR, ModuloAuditoria.CITA, 1L, "x"));
        assertEquals(1, logged.size());
    }

    @Test
    void noneIsANoOp() {
        assertDoesNotThrow(() -> {
            AuditLog.NONE.record(AccionAuditoria.CREAR, ModuloAuditoria.CITA, 1L, "x");
            AuditLog.NONE.recordAs(ADMIN, AccionAuditoria.LOGIN, ModuloAuditoria.SESION, null, "x");
            AuditLog.NONE.recordFailedLogin("x", "y");
        });
    }

    @Test
    void onlyAdminCanList() {
        current = MEDICO;
        assertThrows(PermissionException.class, () -> service.list(AuditoriaFilter.NONE, 1, 10));
        current = new SessionContext(3, "paciente1", Rol.PACIENTE, 20L, "Maria");
        assertThrows(PermissionException.class, () -> service.list(AuditoriaFilter.NONE, 1, 10));
        current = null;
        assertThrows(PermissionException.class, () -> service.list(AuditoriaFilter.NONE, 1, 10));
    }

    @Test
    void listConvertsTheLocalDaysToAbsoluteBoundsInTheAppZone() {
        service.list(new AuditoriaFilter(" torres ", AccionAuditoria.CREAR, ModuloAuditoria.PACIENTE,
            LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 3)), 1, 10);
        AuditoriaQuery query = repository.lastQuery();
        assertEquals("torres", query.texto());
        assertEquals(AccionAuditoria.CREAR, query.accion());
        assertEquals(ModuloAuditoria.PACIENTE, query.modulo());
        assertEquals(OffsetDateTime.parse("2026-11-01T00:00-05:00"), query.from());
        assertEquals(OffsetDateTime.parse("2026-11-04T00:00-05:00"), query.to(), "hasta is inclusive");
    }

    @Test
    void openRangesAndNullFilter() {
        service.list(null, 1, 10);
        assertEquals(AuditoriaQuery.NONE, repository.lastQuery());
        service.list(new AuditoriaFilter(null, null, null, null, LocalDate.of(2026, 11, 3)), 1, 10);
        assertNull(repository.lastQuery().from());
        assertEquals(OffsetDateTime.parse("2026-11-04T00:00-05:00"), repository.lastQuery().to());
    }

    @Test
    void anInvertedRangeIsAValidationError() {
        ValidationException ex = assertThrows(ValidationException.class, () -> service.list(
            new AuditoriaFilter(null, null, null, LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 1)), 1, 10));
        assertEquals(AuditoriaService.INVERTED_RANGE, ex.errors().get("hasta"));
    }

    @Test
    void anOverlongFilterTextIsRejected() {
        assertThrows(ValidationException.class, () -> service.list(
            new AuditoriaFilter("x".repeat(Validation.MAX_FILTER_TEXT + 1), null, null, null, null), 1, 10));
    }

    @Test
    void listReturnsNewestFirst() {
        service.record(AccionAuditoria.CREAR, ModuloAuditoria.PACIENTE, 1L, "first");
        service.record(AccionAuditoria.EDITAR, ModuloAuditoria.PACIENTE, 1L, "second");
        Page<Auditoria> page = service.list(AuditoriaFilter.NONE, 1, 10);
        assertEquals(2, page.total());
        assertEquals("second", page.items().get(0).detalle());
        assertEquals(ZONE, service.zone());
    }

    @Test
    void exportUsesTheListFilterAndIsAdminOnly() {
        service.record(AccionAuditoria.CREAR, ModuloAuditoria.PACIENTE, 20L, "first");
        service.record(AccionAuditoria.EDITAR, ModuloAuditoria.MEDICO, 10L, "second");
        AuditoriaFilter medicos = new AuditoriaFilter(null, null, ModuloAuditoria.MEDICO, null, null);
        ExportRows<Auditoria> rows = service.listForExport(medicos);
        assertEquals(service.list(medicos, 1, 100).items(), rows.items());
        assertEquals(List.of("second"), rows.items().stream().map(Auditoria::detalle).toList());
        assertEquals(2, service.listForExport(null).items().size());

        assertThrows(ValidationException.class, () -> service.listForExport(new AuditoriaFilter(null, null, null,
            LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 1))));
        current = MEDICO;
        assertThrows(PermissionException.class, () -> service.listForExport(AuditoriaFilter.NONE));
    }
}
