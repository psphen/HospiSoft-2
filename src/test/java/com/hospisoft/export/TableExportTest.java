package com.hospisoft.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class TableExportTest {

    @Test
    void nullCellsBecomeEmptyAndListsAreCopied() {
        TableExport table = ExportTestData.table();
        assertEquals("", table.rows().get(2).get(2));
        assertEquals(3, table.rows().size());
        assertThrows(UnsupportedOperationException.class, () -> table.rows().add(List.of()));
    }

    @Test
    void rowsMustMatchTheHeaderCount() {
        assertThrows(IllegalArgumentException.class, () -> new TableExport("T", null, List.of("A", "B"),
            List.of(List.of("only one")), List.of()));
    }

    @Test
    void widthHintsAreOptionalButMustMatchWhenGiven() {
        TableExport noHints = new TableExport("T", null, List.of("A", "B"), List.of(), null);
        assertEquals(List.of(1, 1), noHints.widths(), "missing hints mean equal widths");
        assertEquals("", noHints.subtitle());
        assertThrows(IllegalArgumentException.class, () -> new TableExport("T", null, List.of("A", "B"),
            List.of(), List.of(1)));
        assertThrows(IllegalArgumentException.class, () -> new TableExport("T", null, List.of("A"),
            List.of(), List.of(0)));
    }

    @Test
    void atLeastOneHeaderIsRequired() {
        assertThrows(IllegalArgumentException.class, () -> new TableExport("T", "", List.of(), List.of(), null));
        assertThrows(IllegalArgumentException.class,
            () -> new TableExport("T", "", Arrays.asList("A", null), List.of(), null));
    }

    @Test
    void formatsCarryExtensionAndSpanishDescription() {
        assertEquals("xlsx", ExportFormat.XLSX.extension());
        assertEquals("pdf", ExportFormat.PDF.extension());
        assertEquals("Libro de Excel (*.xlsx)", ExportFormat.XLSX.description());
        assertEquals("Documento PDF (*.pdf)", ExportFormat.PDF.description());
        assertEquals("Excel", ExportFormat.XLSX.label());
        assertEquals("PDF", ExportFormat.PDF.label());
        assertEquals(ExportFormat.XLSX, ExportFormat.fromCode(" XLSX ").orElseThrow());
        assertEquals(ExportFormat.PDF, ExportFormat.fromCode("pdf").orElseThrow());
        assertEquals(true, ExportFormat.fromCode("csv").isEmpty());
        assertEquals(true, ExportFormat.fromCode(null).isEmpty());
        assertEquals(true, ExportFormat.fromCode("").isEmpty());
    }

    @Test
    void fileNamesGetTheFormatExtensionOnce() {
        assertEquals("pacientes.xlsx", ExportFormat.XLSX.withExtension("pacientes"));
        assertEquals("pacientes.XLSX", ExportFormat.XLSX.withExtension("pacientes.XLSX"));
        assertEquals("informe.pdf.xlsx", ExportFormat.XLSX.withExtension("informe.pdf"));
    }
}
