package com.hospisoft.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The table list is pinned here; AuditoriaSchemaTest checks db/schema.sql creates exactly these tables. */
class SchemaCheckerTest {

    @Test
    void requiresTheSpecTablesAndTheAuditLog() {
        assertEquals(
            List.of("usuario", "medico", "paciente", "cita", "medicamento", "historia_clinica", "receta", "auditoria"),
            SchemaChecker.REQUIRED_TABLES);
    }

    @Test
    void reportsNothingMissingWhenAllTablesExist() {
        Set<String> found = Set.of("usuario", "medico", "paciente", "cita", "medicamento",
            "historia_clinica", "receta", "auditoria", "extra_table");
        assertTrue(SchemaChecker.missingFrom(found).isEmpty());
    }

    @Test
    void reportsMissingTablesInSpecOrder() {
        Set<String> found = Set.of("usuario", "paciente", "medicamento");
        assertEquals(List.of("medico", "cita", "historia_clinica", "receta", "auditoria"),
            SchemaChecker.missingFrom(found));
    }

    @Test
    void aDatabaseFromBeforeTheAuditLogReportsOnlyAuditoria() {
        Set<String> found = Set.of("usuario", "medico", "paciente", "cita", "medicamento", "historia_clinica", "receta");
        assertEquals(List.of("auditoria"), SchemaChecker.missingFrom(found));
    }

    @Test
    void comparisonIgnoresCase() {
        Set<String> found = Set.of("USUARIO", "Medico", "paciente", "cita", "medicamento", "historia_clinica", "receta",
            "AUDITORIA");
        assertTrue(SchemaChecker.missingFrom(found).isEmpty());
    }
}
