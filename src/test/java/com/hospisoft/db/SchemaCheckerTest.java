package com.hospisoft.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SchemaCheckerTest {

    @Test
    void requiresTheSevenSpecTables() {
        assertEquals(
            List.of("usuario", "medico", "paciente", "cita", "medicamento", "historia_clinica", "receta"),
            SchemaChecker.REQUIRED_TABLES);
    }

    @Test
    void reportsNothingMissingWhenAllTablesExist() {
        Set<String> found = Set.of("usuario", "medico", "paciente", "cita", "medicamento",
            "historia_clinica", "receta", "extra_table");
        assertTrue(SchemaChecker.missingFrom(found).isEmpty());
    }

    @Test
    void reportsMissingTablesInSpecOrder() {
        Set<String> found = Set.of("usuario", "paciente", "medicamento");
        assertEquals(List.of("medico", "cita", "historia_clinica", "receta"), SchemaChecker.missingFrom(found));
    }

    @Test
    void comparisonIgnoresCase() {
        Set<String> found = Set.of("USUARIO", "Medico", "paciente", "cita", "medicamento", "historia_clinica", "receta");
        assertTrue(SchemaChecker.missingFrom(found).isEmpty());
    }
}
