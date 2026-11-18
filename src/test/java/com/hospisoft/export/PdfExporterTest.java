package com.hospisoft.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lowagie.text.PageSize;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PdfExporterTest {

    private static String text(PdfReader reader, int page) throws IOException {
        return new PdfTextExtractor(reader).getTextFromPage(page);
    }

    @Test
    void isA4LandscapeWithTitleSubtitleHeadersAndAccentedRows() throws IOException {
        byte[] bytes = new PdfExporter().export(ExportTestData.table());
        assertTrue(new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).startsWith("%PDF-"));
        PdfReader reader = new PdfReader(bytes);
        try {
            Rectangle size = reader.getPageSizeWithRotation(1);
            assertEquals(PageSize.A4.getHeight(), size.getWidth(), 0.5f, "landscape: width is the A4 height");
            assertEquals(PageSize.A4.getWidth(), size.getHeight(), 0.5f);

            String page = text(reader, 1);
            assertTrue(page.contains(ExportTestData.TITLE), page);
            assertTrue(page.contains(ExportTestData.SUBTITLE), page);
            for (String header : ExportTestData.HEADERS) {
                assertTrue(page.contains(header), "missing header " + header + " in " + page);
            }
            assertTrue(page.contains("María Peña Núñez"), page);
            assertTrue(page.contains("José Ángel Ruiz"), page);
            assertTrue(page.contains("Página 1"), page);
            assertEquals("Listado de Pacientes", reader.getInfo().get("Title"));
        } finally {
            reader.close();
        }
    }

    @Test
    void longTablesRepeatTheHeaderOnEveryPageAndNumberThePages() throws IOException {
        List<List<String>> rows = new ArrayList<>();
        for (int i = 1; i <= 200; i++) {
            rows.add(List.of(String.valueOf(i), "Fila número " + i));
        }
        TableExport table = new TableExport("Listado largo", "", List.of("Código", "Descripción"), rows, List.of(1, 4));
        PdfReader reader = new PdfReader(new PdfExporter().export(table));
        try {
            int pages = reader.getNumberOfPages();
            assertTrue(pages > 1, "200 rows must span several pages");
            for (int p = 1; p <= pages; p++) {
                String page = text(reader, p);
                assertTrue(page.contains("Código") && page.contains("Descripción"), "header missing on page " + p);
                assertTrue(page.contains("Página " + p + " de"), "page number missing on page " + p + ": " + page);
            }
            assertTrue(text(reader, pages).contains("Fila número 200"));
        } finally {
            reader.close();
        }
    }

    @Test
    void anEmptyTableSaysSo() throws IOException {
        TableExport table = new TableExport("Vacío", "", List.of("A", "B"), List.of(), null);
        PdfReader reader = new PdfReader(new PdfExporter().export(table));
        try {
            String page = text(reader, 1);
            assertTrue(page.contains(PdfExporter.NO_ROWS), page);
            assertTrue(page.contains("A") && page.contains("B"));
        } finally {
            reader.close();
        }
    }
}
