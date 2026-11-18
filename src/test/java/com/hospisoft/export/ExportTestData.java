package com.hospisoft.export;

import java.util.List;

final class ExportTestData {

    static final String TITLE = "Listado de Pacientes";
    static final String SUBTITLE = "Generado el 2026-11-04 10:00 por Administrador";
    static final List<String> HEADERS = List.of("#", "Documento", "Nombre completo", "Género");

    private ExportTestData() {
    }

    static TableExport table() {
        return new TableExport(TITLE, SUBTITLE, HEADERS, List.of(
            List.of("1", "2001", "María Peña Núñez", "Femenino"),
            List.of("2", "=cmd|' /C calc'!A0", "José Ángel Ruiz", "Masculino"),
            java.util.Arrays.asList("3", "+SUM(1,2)", null, "Otro")),
            List.of(1, 3, 6, 3));
    }
}
