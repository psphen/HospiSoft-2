package com.hospisoft.dao;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Every column referenced by the DAO SQL constants must exist in db/schema.sql.
 *
 * <p>Known limitation: identifiers are checked against the union of every table's columns, not as
 * table.column pairs, so a column that exists in another table would pass. Pairing them would mean
 * resolving table aliases across joins and subselects; the DAO tests already pin each statement's
 * exact SQL, which covers that gap.
 */
class DaoSchemaConsistencyTest {

    private static final Pattern CREATE_TABLE = Pattern.compile(
        "CREATE TABLE IF NOT EXISTS (\\w+) \\((.*?)\\n\\);", Pattern.DOTALL);
    private static final Pattern COLUMN_LINE = Pattern.compile("^\\s{4}([a-z_]+)\\s", Pattern.MULTILINE);
    private static final Pattern IDENTIFIER = Pattern.compile("\\b([a-z][a-z_0-9]*)\\b");
    private static final Pattern QUOTED = Pattern.compile("'[^']*'");

    private static final Set<String> SQL_WORDS = Set.of(
        "select", "from", "where", "and", "or", "not", "is", "null", "as", "on", "left", "join", "exists",
        "distinct", "order", "by", "limit", "offset", "insert", "into", "values", "returning", "update", "set",
        "delete", "ilike", "escape", "count", "coalesce", "true", "false",
        "for", "no", "key", "make_interval", "mins", "desc", "asc", "now", "avg", "extract", "epoch",
        "medico_nombres", "medico_apellidos", "paciente_nombres", "paciente_apellidos", "paciente_documento");

    /** Table aliases used in the joins. */
    private static final Set<String> ALIASES = Set.of("md", "r", "h", "c", "m", "p", "u");

    /** Only constants that really are SQL are scanned (messages and constraint names are not). */
    private static boolean looksLikeSql(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("select ") || lower.contains("insert into") || lower.contains("update ")
            || lower.contains("delete from");
    }

    private static Set<String> schemaColumns() throws IOException {
        String sql = Files.readString(Paths.get("db", "schema.sql"), StandardCharsets.UTF_8);
        Set<String> names = new HashSet<>();
        Matcher table = CREATE_TABLE.matcher(sql);
        while (table.find()) {
            names.add(table.group(1));
            Matcher column = COLUMN_LINE.matcher(table.group(2));
            while (column.find()) {
                names.add(column.group(1));
            }
        }
        assertTrue(names.contains("fecha_nacimiento") && names.contains("historia_clinica"), "schema parse failed");
        return names;
    }

    private static List<String> sqlConstants(Class<?> dao) throws IllegalAccessException {
        List<String> values = new ArrayList<>();
        for (Field field : dao.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                field.setAccessible(true);
                String value = (String) field.get(null);
                if (looksLikeSql(value)) {
                    values.add(value);
                }
            }
        }
        assertTrue(!values.isEmpty(), dao.getSimpleName() + " exposes no SQL constants");
        return values;
    }

    @Test
    void daoIdentifiersExistInTheSchema() throws Exception {
        Set<String> known = schemaColumns();
        List<String> unknown = new ArrayList<>();
        for (Class<?> dao : List.of(MedicoDao.class, PacienteDao.class, MedicamentoDao.class, CitaDao.class, AtencionDao.class, DashboardDao.class, UsuarioDao.class)) {
            for (String sql : sqlConstants(dao)) {
                String text = QUOTED.matcher(sql).replaceAll(" ").toLowerCase(Locale.ROOT);
                Matcher m = IDENTIFIER.matcher(text);
                while (m.find()) {
                    String word = m.group(1);
                    if (word.length() <= 1 || SQL_WORDS.contains(word) || ALIASES.contains(word)
                        || word.equals("display_name")) {
                        continue; // aliases (p, u, m, c), keywords, computed alias
                    }
                    if (!known.contains(word)) {
                        unknown.add(dao.getSimpleName() + ": " + word);
                    }
                }
            }
        }
        assertTrue(unknown.isEmpty(), "identifiers missing from db/schema.sql: " + unknown);
    }
}
