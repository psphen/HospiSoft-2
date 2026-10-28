package com.hospisoft.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Ties db/schema.sql to the hashing implementation: every seeded account must verify against the
 * documented plaintext password with the current PasswordHasher parameters.
 */
class SchemaSeedTest {

    private static final Path SCHEMA = Paths.get("db", "schema.sql");

    /** Documented test credentials (also listed in the header of schema.sql and in README.md). */
    private static final Map<String, String> PLAINTEXT = Map.of(
        "admin", "Admin123*",
        "dr.garcia", "Medico123*",
        "dra.lopez", "Medico123*",
        "dr.ruiz", "Medico123*",
        "paciente1", "Paciente123*",
        "paciente2", "Paciente123*");

    /** One usuario VALUES row: ('username', 'hash', 'salt', 'ROL', ... */
    private static final Pattern ROW = Pattern.compile(
        "^\\s*\\('([^']+)',\\s*'([^']+)',\\s*'([^']+)',\\s*'(ADMIN|MEDICO|PACIENTE)'", Pattern.MULTILINE);

    private static Map<String, String[]> seededUsers() throws IOException {
        String sql = Files.readString(SCHEMA, StandardCharsets.UTF_8);
        int start = sql.indexOf("INSERT INTO usuario");
        assertTrue(start >= 0, "usuario INSERT not found in " + SCHEMA);
        int end = sql.indexOf("ON CONFLICT (username)", start);
        assertTrue(end > start, "usuario INSERT is not terminated by ON CONFLICT (username)");
        Matcher matcher = ROW.matcher(sql.substring(start, end));
        Map<String, String[]> users = new LinkedHashMap<>();
        while (matcher.find()) {
            users.put(matcher.group(1), new String[] {matcher.group(2), matcher.group(3), matcher.group(4)});
        }
        return users;
    }

    @Test
    void everyDocumentedAccountIsSeeded() throws IOException {
        Map<String, String[]> users = seededUsers();
        assertEquals(PLAINTEXT.keySet(), users.keySet());
        assertEquals("ADMIN", users.get("admin")[2]);
        assertEquals("MEDICO", users.get("dr.garcia")[2]);
        assertEquals("PACIENTE", users.get("paciente1")[2]);
    }

    @Test
    void seededHashesVerifyWithTheCurrentHasher() throws IOException {
        for (Map.Entry<String, String[]> entry : seededUsers().entrySet()) {
            String username = entry.getKey();
            String hash = entry.getValue()[0];
            String salt = entry.getValue()[1];
            String plaintext = PLAINTEXT.get(username);
            assertTrue(PasswordHasher.verify(plaintext.toCharArray(), salt, hash),
                "seed hash for " + username + " does not verify; regenerate it with PasswordHasher.main");
        }
    }

    /** One medicamento VALUES row: ('codigo', 'nombre', 'descripcion', 'presentacion', stock, stock_minimo, ... */
    private static final Pattern MED_ROW = Pattern.compile(
        "^\\s*\\('(MED-[0-9]+)',\\s*'[^']+',\\s*'[^']*',\\s*'[^']+',\\s*(\\d+),\\s*(\\d+)", Pattern.MULTILINE);

    @Test
    void seededMedicamentosCoverEveryStockStateWithUniqueCodes() throws IOException {
        String sql = Files.readString(SCHEMA, StandardCharsets.UTF_8);
        int start = sql.indexOf("INSERT INTO medicamento");
        int end = sql.indexOf("ON CONFLICT (codigo)", start);
        assertTrue(start >= 0 && end > start, "medicamento INSERT not found");
        Matcher matcher = MED_ROW.matcher(sql.substring(start, end));
        java.util.Set<String> codes = new java.util.HashSet<>();
        int lowStock = 0;
        int outOfStock = 0;
        int rows = 0;
        while (matcher.find()) {
            rows++;
            assertTrue(codes.add(matcher.group(1)), "duplicate codigo " + matcher.group(1));
            int stock = Integer.parseInt(matcher.group(2));
            int minimo = Integer.parseInt(matcher.group(3));
            if (stock == 0) {
                outOfStock++;
            } else if (stock <= minimo) {
                lowStock++;
            }
        }
        assertEquals(10, rows);
        assertTrue(lowStock >= 2, "at least 2 STOCK_BAJO seeds expected, found " + lowStock);
        assertTrue(outOfStock >= 1, "at least 1 AGOTADO seed expected");
    }

    /** One cita VALUES row: ('doc medico', 'doc paciente', interval '<offset>', '[seed] ...', 'ESTADO', ... */
    private static final String INTERVAL_OR_NULL = "(NULL(?:::interval)?|interval '[^']+')";
    private static final Pattern CITA_ROW = Pattern.compile(
        "^\\s*\\('(\\d+)',\\s*'(\\d+)',\\s*interval '([^']+)',\\s*'\\[seed\\][^']*',\\s*'(PROGRAMADA|EN_CURSO|FINALIZADA|CANCELADA)',\\s*"
            + INTERVAL_OR_NULL + ",\\s*" + INTERVAL_OR_NULL,
        Pattern.MULTILINE);
    private static final Pattern INTERVAL_PART = Pattern.compile("(\\d+)\\s*(day|days|hour|hours|minute|minutes)");

    /**
     * Minutes relative to the base hour. Negative literals must be single-unit minutes
     * ("-155 minutes") because PostgreSQL applies the sign per field in multi-field literals.
     */
    static long offsetMinutes(String literal) {
        String text = literal.trim();
        boolean negative = text.startsWith("-");
        if (negative) {
            assertTrue(text.matches("-\\d+ minutes"), "negative seed intervals must be '-N minutes': " + literal);
            return -Long.parseLong(text.substring(1, text.indexOf(' ')));
        }
        long minutes = 0;
        Matcher m = INTERVAL_PART.matcher(text);
        while (m.find()) {
            long n = Long.parseLong(m.group(1));
            String unit = m.group(2);
            if (unit.startsWith("day")) {
                minutes += n * 24 * 60;
            } else if (unit.startsWith("hour")) {
                minutes += n * 60;
            } else {
                minutes += n;
            }
        }
        return minutes;
    }

    /** null for NULL / NULL::interval, otherwise the minutes of the interval literal. */
    private static Long optionalMinutes(String token) {
        if (token.startsWith("NULL")) {
            return null;
        }
        return offsetMinutes(token.substring("interval '".length(), token.length() - 1));
    }

    @Test
    void seededCitasRespectTheGridAndNeverOverlapPerMedicoOrPaciente() throws IOException {
        String sql = Files.readString(SCHEMA, StandardCharsets.UTF_8);
        int start = sql.indexOf("INSERT INTO cita");
        int end = sql.indexOf("JOIN medico", start);
        assertTrue(start >= 0 && end > start, "cita INSERT not found");
        Matcher matcher = CITA_ROW.matcher(sql.substring(start, end));
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        while (matcher.find()) {
            rows.add(new String[] {matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4)});
            Long inicio = optionalMinutes(matcher.group(5));
            Long fin = optionalMinutes(matcher.group(6));
            switch (matcher.group(4)) {
                case "FINALIZADA" -> {
                    assertTrue(inicio != null && fin != null, "FINALIZADA needs inicio and fin: " + matcher.group());
                    assertTrue(inicio <= fin, "hora_inicio_real must be <= hora_fin_real: " + matcher.group());
                }
                case "EN_CURSO" -> assertTrue(inicio != null && fin == null, "EN_CURSO needs inicio only: " + matcher.group());
                default -> assertTrue(inicio == null && fin == null, matcher.group(4) + " must have no real times: " + matcher.group());
            }
        }
        assertEquals(8, rows.size(), "expected 8 seed citas");
        int slot = com.hospisoft.service.CitaService.SLOT_MINUTES;
        java.util.Map<String, Integer> byEstado = new java.util.HashMap<>();
        for (String[] row : rows) {
            long offset = offsetMinutes(row[2]);
            assertEquals(0, Math.floorMod(offset, 5), "not on the 5-minute grid: " + row[2]);
            assertEquals(0, Math.floorMod(offset, slot), "not on the 30-minute grid: " + row[2]);
            byEstado.merge(row[3], 1, Integer::sum);
        }
        assertTrue(byEstado.getOrDefault("EN_CURSO", 0) >= 1);
        assertTrue(byEstado.getOrDefault("CANCELADA", 0) >= 2);
        assertTrue(byEstado.getOrDefault("FINALIZADA", 0) >= 2);
        assertTrue(byEstado.getOrDefault("PROGRAMADA", 0) >= 2);
        for (int i = 0; i < rows.size(); i++) {
            for (int j = i + 1; j < rows.size(); j++) {
                String[] a = rows.get(i);
                String[] b = rows.get(j);
                if (a[3].equals("CANCELADA") || b[3].equals("CANCELADA")) {
                    continue;
                }
                boolean sameMedico = a[0].equals(b[0]);
                boolean samePaciente = a[1].equals(b[1]);
                if (sameMedico || samePaciente) {
                    long gap = Math.abs(offsetMinutes(a[2]) - offsetMinutes(b[2]));
                    assertTrue(gap >= slot, "overlapping seed citas: " + a[2] + " / " + b[2]);
                }
            }
        }
        // dr.garcia (1001001001) has one future PROGRAMADA and one earlier FINALIZADA today
        assertTrue(rows.stream().anyMatch(r -> r[0].equals("1001001001") && r[3].equals("PROGRAMADA") && offsetMinutes(r[2]) > 0));
        assertTrue(rows.stream().anyMatch(r -> r[0].equals("1001001001") && r[3].equals("FINALIZADA")
            && offsetMinutes(r[2]) < 0 && offsetMinutes(r[2]) > -12 * 60));
    }


    private static final Pattern HISTORIA_FOR_MOTIVO = Pattern.compile(
        "INSERT INTO historia_clinica .*?c\\.motivo = '(\\[seed\\][^']*)'", Pattern.DOTALL);
    private static final Pattern RECETA_LINE = Pattern.compile(
        "SELECT h\\.id, md\\.id, (\\d+), '([^']*)', '([^']*)', (TRUE|FALSE), (NULL|'[^']*')"
            + ".*?md\\.codigo = '(MED-\\d+)'", Pattern.DOTALL);

    @Test
    void seededHistoriasExistOnlyForFinalizadaCitas() throws IOException {
        String sql = Files.readString(SCHEMA, StandardCharsets.UTF_8);
        int start = sql.indexOf("INSERT INTO cita");
        int end = sql.indexOf("JOIN medico", start);
        Matcher citaMatcher = Pattern.compile(
            "^\\s*\\('\\d+',\\s*'\\d+',\\s*interval '[^']+',\\s*'(\\[seed\\][^']*)',\\s*'(\\w+)'",
            Pattern.MULTILINE)
            .matcher(sql.substring(start, end));
        java.util.Set<String> finalizadas = new java.util.HashSet<>();
        java.util.Set<String> others = new java.util.HashSet<>();
        while (citaMatcher.find()) {
            if ("FINALIZADA".equals(citaMatcher.group(2))) {
                finalizadas.add(citaMatcher.group(1));
            } else {
                others.add(citaMatcher.group(1));
            }
        }
        assertEquals(2, finalizadas.size(), "expected 2 FINALIZADA seed citas");

        java.util.List<String> withHistoria = new java.util.ArrayList<>();
        Matcher historiaMatcher = HISTORIA_FOR_MOTIVO.matcher(sql);
        while (historiaMatcher.find()) {
            withHistoria.add(historiaMatcher.group(1));
        }
        assertEquals(finalizadas, new java.util.HashSet<>(withHistoria),
            "every FINALIZADA cita needs exactly one historia and no other state may have one");
        assertEquals(withHistoria.size(), new java.util.HashSet<>(withHistoria).size(), "duplicate historia insert");
        withHistoria.forEach(motivo -> assertFalse(others.contains(motivo)));
    }

    @Test
    void seededRecetasAreConsistentWithTheMedicamentoStock() throws IOException {
        String sql = Files.readString(SCHEMA, StandardCharsets.UTF_8);
        java.util.Map<String, Integer> stockByCodigo = new java.util.HashMap<>();
        Matcher med = Pattern.compile(
            "\\('(MED-\\d+)',\\s*'[^']+',\\s*'[^']*',\\s*'[^']+',\\s*(\\d+),\\s*(\\d+)")
            .matcher(sql);
        while (med.find()) {
            stockByCodigo.put(med.group(1), Integer.parseInt(med.group(2)));
        }
        assertTrue(stockByCodigo.size() >= 10, "medicamento seeds not found");

        int lines = 0;
        int pending = 0;
        Matcher receta = RECETA_LINE.matcher(sql);
        while (receta.find()) {
            lines++;
            int cantidad = Integer.parseInt(receta.group(1));
            boolean entregado = "TRUE".equals(receta.group(4));
            String observacion = receta.group(5);
            String codigo = receta.group(6);
            assertTrue(cantidad > 0, "cantidad must be positive");
            assertFalse(receta.group(2).isBlank(), "dosis is required");
            assertFalse(receta.group(3).isBlank(), "duracion is required");
            Integer stock = stockByCodigo.get(codigo);
            assertTrue(stock != null, "receta references an unseeded medicamento: " + codigo);
            if (entregado) {
                assertEquals("NULL", observacion, "a delivered receta carries no observation");
                assertTrue(stock > 0, "delivered receta on an out-of-stock medicamento: " + codigo);
            } else {
                pending++;
                assertFalse("NULL".equals(observacion), "a pending receta needs an observation");
                assertEquals(0, (int) stock, "the pending receta should be the out-of-stock medicamento");
            }
        }
        assertEquals(3, lines, "expected 3 seed recetas");
        assertTrue(pending >= 1, "at least one receta must be pending delivery");
    }

    @Test
    void seededHashesUseUniqueSalts() throws IOException {
        Map<String, String[]> users = seededUsers();
        long distinctSalts = users.values().stream().map(v -> v[1]).distinct().count();
        assertEquals(users.size(), distinctSalts);
    }
}
