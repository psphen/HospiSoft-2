package com.hospisoft.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Every service max-length constant must fit the VARCHAR(n) of the column it protects. */
class SchemaLengthParityTest {

    private static final Pattern CREATE_TABLE = Pattern.compile("CREATE TABLE IF NOT EXISTS (\\w+) \\((.*?)\\n\\);", Pattern.DOTALL);
    private static final Pattern VARCHAR_COLUMN = Pattern.compile("^\\s{4}(\\w+)\\s+VARCHAR\\((\\d+)\\)", Pattern.MULTILINE);

    private static Map<String, Integer> schemaLengths() throws IOException {
        String sql = Files.readString(Paths.get("db", "schema.sql"), StandardCharsets.UTF_8);
        Map<String, Integer> lengths = new HashMap<>();
        Matcher table = CREATE_TABLE.matcher(sql);
        while (table.find()) {
            Matcher column = VARCHAR_COLUMN.matcher(table.group(2));
            while (column.find()) {
                lengths.put(table.group(1) + "." + column.group(1), Integer.parseInt(column.group(2)));
            }
        }
        assertTrue(lengths.containsKey("cita.motivo"), "schema parse failed: " + lengths.keySet());
        return lengths;
    }

    /** TEXT columns are unbounded: these constants are business limits, so only their column must exist. */
    @Test
    void textColumnConstantsAreBusinessLimits() throws IOException {
        String sql = Files.readString(Paths.get("db", "schema.sql"), StandardCharsets.UTF_8);
        for (String column : java.util.List.of("diagnostico", "tratamiento", "notas", "observaciones")) {
            assertTrue(Pattern.compile("^\s{4}" + column + "\s+TEXT", Pattern.MULTILINE).matcher(sql).find(),
                "expected an unbounded TEXT column: " + column);
        }
        assertTrue(AtencionService.MAX_DIAGNOSTICO > 0 && AtencionService.MAX_TRATAMIENTO > 0
            && AtencionService.MAX_NOTAS > 0 && AtencionService.MAX_OBSERVACIONES > 0);
    }

    @Test
    void serviceConstantsFitTheColumns() throws IOException {
        Map<String, Integer> schema = schemaLengths();
        Map<String, Integer> constants = new java.util.LinkedHashMap<>();
        constants.put("medico.documento", MedicoService.MAX_DOCUMENTO);
        constants.put("medico.nombres", MedicoService.MAX_NOMBRES);
        constants.put("medico.apellidos", MedicoService.MAX_APELLIDOS);
        constants.put("medico.especialidad", MedicoService.MAX_ESPECIALIDAD);
        constants.put("medico.telefono", MedicoService.MAX_TELEFONO);
        constants.put("medico.correo", MedicoService.MAX_CORREO);
        constants.put("paciente.documento", PacienteService.MAX_DOCUMENTO);
        constants.put("paciente.nombres", PacienteService.MAX_NOMBRES);
        constants.put("paciente.apellidos", PacienteService.MAX_APELLIDOS);
        constants.put("paciente.telefono", PacienteService.MAX_TELEFONO);
        constants.put("paciente.correo", PacienteService.MAX_CORREO);
        constants.put("paciente.direccion", PacienteService.MAX_DIRECCION);
        constants.put("medicamento.codigo", MedicamentoService.MAX_CODIGO);
        constants.put("medicamento.nombre", MedicamentoService.MAX_NOMBRE);
        constants.put("medicamento.descripcion", MedicamentoService.MAX_DESCRIPCION);
        constants.put("medicamento.presentacion", MedicamentoService.MAX_PRESENTACION);
        constants.put("cita.motivo", CitaService.MAX_MOTIVO);
        constants.put("receta.dosis", AtencionService.MAX_DOSIS);
        constants.put("receta.duracion", AtencionService.MAX_DURACION);
        constants.put("receta.observacion_no_disponible", AtencionService.MAX_OBSERVACION_NO_DISPONIBLE);
        for (Map.Entry<String, Integer> entry : constants.entrySet()) {
            Integer columnLength = schema.get(entry.getKey());
            assertTrue(columnLength != null, "column not found in schema: " + entry.getKey());
            assertTrue(entry.getValue() <= columnLength,
                entry.getKey() + ": service allows " + entry.getValue() + " but column is VARCHAR(" + columnLength + ")");
        }
    }
}
