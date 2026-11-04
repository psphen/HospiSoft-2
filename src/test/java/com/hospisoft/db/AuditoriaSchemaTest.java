package com.hospisoft.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hospisoft.model.AccionAuditoria;
import com.hospisoft.model.ModuloAuditoria;
import com.hospisoft.model.Rol;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The audit table in db/schema.sql must stay in sync with the Java enums and keep its append-only
 * guarantees: no foreign keys, row and statement triggers that reject UPDATE, DELETE and TRUNCATE,
 * row level security enabled like every other table.
 */
class AuditoriaSchemaTest {

    private static final Pattern TABLE = Pattern.compile(
        "CREATE TABLE IF NOT EXISTS auditoria \\((.*?)\\n\\);", Pattern.DOTALL);
    private static final Pattern QUOTED = Pattern.compile("'([A-Z_]+)'");

    private static String schema;
    private static String table;

    @BeforeAll
    static void readSchema() throws IOException {
        schema = Files.readString(Paths.get("db", "schema.sql"), StandardCharsets.UTF_8).replace("\r\n", "\n");
        Matcher matcher = TABLE.matcher(schema);
        assertTrue(matcher.find(), "auditoria table not found in db/schema.sql");
        table = matcher.group(1);
    }

    /** Values listed in the {@code CHECK (column IN (...))} of the given column. */
    private static Set<String> checkValues(String column) {
        Matcher check = Pattern.compile("CHECK \\(" + column + " IN \\(([^)]*)\\)\\)").matcher(table);
        assertTrue(check.find(), "no CHECK list for auditoria." + column);
        Set<String> values = new LinkedHashSet<>();
        Matcher quoted = QUOTED.matcher(check.group(1));
        while (quoted.find()) {
            values.add(quoted.group(1));
        }
        return values;
    }

    private static Set<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Test
    void accionCheckMatchesTheEnum() {
        assertEquals(names(AccionAuditoria.values()), checkValues("accion"));
    }

    @Test
    void moduloCheckMatchesTheEnum() {
        assertEquals(names(ModuloAuditoria.values()), checkValues("modulo"));
    }

    @Test
    void rolCheckMatchesTheEnum() {
        assertEquals(names(Rol.values()), checkValues("rol"));
    }

    @Test
    void enumCodesFitTheirColumns() {
        for (AccionAuditoria accion : AccionAuditoria.values()) {
            assertTrue(accion.name().length() <= 20, accion.name());
        }
        for (ModuloAuditoria modulo : ModuloAuditoria.values()) {
            assertTrue(modulo.name().length() <= 30, modulo.name());
        }
        assertTrue(table.contains("accion      VARCHAR(20)"), "accion must be VARCHAR(20)");
        assertTrue(table.contains("modulo      VARCHAR(30)"), "modulo must be VARCHAR(30)");
    }

    @Test
    void everyEnumValueHasASpanishLabel() {
        for (AccionAuditoria accion : AccionAuditoria.values()) {
            assertFalse(accion.label().isBlank(), accion.name());
        }
        for (ModuloAuditoria modulo : ModuloAuditoria.values()) {
            assertFalse(modulo.label().isBlank(), modulo.name());
        }
        assertEquals("Inicio de sesión fallido", AccionAuditoria.LOGIN_FALLIDO.label());
        assertEquals("Médicos", ModuloAuditoria.MEDICO.label());
    }

    @Test
    void rowsAreSnapshotsWithoutForeignKeys() {
        assertFalse(table.contains("REFERENCES"), "auditoria must not reference other tables");
        assertTrue(table.contains("fecha       TIMESTAMPTZ  NOT NULL DEFAULT now()"));
        assertTrue(table.contains("id_usuario  BIGINT       NULL"));
    }

    @Test
    void tableIsAppendOnly() {
        assertTrue(schema.contains("CREATE OR REPLACE FUNCTION auditoria_immutable()"));
        assertTrue(schema.contains("DROP TRIGGER IF EXISTS trg_auditoria_immutable ON auditoria;"));
        assertTrue(schema.contains("BEFORE UPDATE OR DELETE ON auditoria\n    FOR EACH ROW EXECUTE FUNCTION auditoria_immutable();"));
        assertTrue(schema.contains("DROP TRIGGER IF EXISTS trg_auditoria_no_truncate ON auditoria;"));
        assertTrue(schema.contains("BEFORE TRUNCATE ON auditoria\n    FOR EACH STATEMENT EXECUTE FUNCTION auditoria_immutable();"));
    }

    @Test
    void indexesAndRowLevelSecurity() {
        assertTrue(schema.contains("CREATE INDEX IF NOT EXISTS idx_auditoria_fecha   ON auditoria (fecha DESC);"));
        assertTrue(schema.contains("CREATE INDEX IF NOT EXISTS idx_auditoria_usuario ON auditoria (id_usuario);"));
        assertTrue(schema.contains("ALTER TABLE auditoria        ENABLE ROW LEVEL SECURITY;"));
    }

    @Test
    void everyTableHasRowLevelSecurity() {
        Matcher created = Pattern.compile("CREATE TABLE IF NOT EXISTS (\\w+) \\(").matcher(schema);
        int tables = 0;
        while (created.find()) {
            tables++;
            String name = created.group(1);
            assertTrue(Pattern.compile("ALTER TABLE " + name + "\\s+ENABLE ROW LEVEL SECURITY;").matcher(schema).find(),
                "RLS not enabled on " + name);
        }
        assertEquals(SchemaChecker.REQUIRED_TABLES.size(), tables, "schema.sql and SchemaChecker disagree on the tables");
    }
}
