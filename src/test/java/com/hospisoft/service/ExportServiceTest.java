package com.hospisoft.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hospisoft.export.ExcelExporter;
import com.hospisoft.export.ExportFormat;
import com.hospisoft.export.FakeFileSaver;
import com.hospisoft.model.AccionAuditoria;
import com.hospisoft.model.ModuloAuditoria;
import com.hospisoft.model.Page;
import com.hospisoft.model.Rol;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Render, ask where to save, write, audit: with a fake dialog and a temporary folder. */
class ExportServiceTest {

    private static final SessionContext ADMIN = new SessionContext(1, "admin", Rol.ADMIN, null, "Administrador");
    /** 2026-11-04 10:00 in Bogota (UTC-5). */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-04T15:00:00Z"), ZoneOffset.UTC);
    private static final AppZone ZONE = new AppZone(ZoneId.of("America/Bogota"));
    private static final ExportSpec SPEC = new ExportSpec(ModuloAuditoria.PACIENTE, "pacientes", "Listado de Pacientes",
        List.of("#", "Nombre"), List.of(1, 4));

    @TempDir
    Path dir;

    private SessionContext current;
    private RecordingAuditLog audit;
    private FakeFileSaver saver;
    private ExportService service;

    @BeforeEach
    void setUp() {
        current = ADMIN;
        audit = new RecordingAuditLog();
        saver = new FakeFileSaver(dir.resolve("salida.xlsx"));
        service = new ExportService(saver, audit, () -> Optional.ofNullable(current), CLOCK, ZONE);
    }

    private static ExportRows<List<String>> rows(int count, long total) {
        List<List<String>> items = IntStream.rangeClosed(1, count)
            .mapToObj(i -> List.of(String.valueOf(i), "Peña " + i)).toList();
        return ExportRows.collect((page, size) -> new Page<>(page == 1 ? items : List.of(), total, page, size),
            count, Math.max(1, count));
    }

    private static XSSFSheet sheet(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return new XSSFWorkbook(in).getSheetAt(0);
        }
    }

    @Test
    void writesTheWorkbookWhereTheUserChoseAndAuditsIt() throws IOException {
        ExportResult result = service.export(SPEC, ExportFormat.XLSX, rows(2, 2));

        assertTrue(result.saved());
        assertEquals("salida.xlsx", result.fileName());
        assertEquals(2, result.rows());
        assertEquals(new FakeFileSaver.Request("pacientes_2026-11-04.xlsx", ExportFormat.XLSX), saver.last());

        XSSFSheet sheet = sheet(dir.resolve("salida.xlsx"));
        assertEquals("Listado de Pacientes", sheet.getRow(0).getCell(0).getStringCellValue());
        assertEquals("Generado el 2026-11-04 10:00 por Administrador", sheet.getRow(1).getCell(0).getStringCellValue());
        assertEquals("Nombre", sheet.getRow(ExcelExporter.HEADER_ROW).getCell(1).getStringCellValue());
        assertEquals("Peña 2", sheet.getRow(ExcelExporter.HEADER_ROW + 2).getCell(1).getStringCellValue());

        RecordingAuditLog.Entry entry = audit.only();
        assertEquals(AccionAuditoria.EXPORTAR, entry.accion());
        assertEquals(ModuloAuditoria.PACIENTE, entry.modulo());
        assertNull(entry.idRegistro());
        assertEquals("Exportación Excel - 2 registro(s)", entry.detalle());
    }

    @Test
    void writesAPdfAndAuditsTheFormat() throws IOException {
        saver.answer(dir.resolve("reporte.pdf"));
        ExportResult result = service.export(SPEC, ExportFormat.PDF, rows(3, 3));
        assertEquals("reporte.pdf", result.fileName());
        assertEquals(new FakeFileSaver.Request("pacientes_2026-11-04.pdf", ExportFormat.PDF), saver.last());
        PdfReader reader = new PdfReader(Files.readAllBytes(dir.resolve("reporte.pdf")));
        try {
            String text = new PdfTextExtractor(reader).getTextFromPage(1);
            assertTrue(text.contains("Listado de Pacientes") && text.contains("Peña 3"), text);
        } finally {
            reader.close();
        }
        assertEquals("Exportación PDF - 3 registro(s)", audit.only().detalle());
    }

    @Test
    void cancellingTheDialogIsNotAnErrorAndLeavesNoTrace() throws IOException {
        saver.answer(null);
        ExportResult result = service.export(SPEC, ExportFormat.XLSX, rows(2, 2));
        assertFalse(result.saved());
        assertNull(result.fileName());
        try (var files = Files.list(dir)) {
            assertEquals(0, files.count());
        }
        assertTrue(audit.entries().isEmpty(), "nothing was exported");
    }

    @Test
    void theExtensionIsAddedWhenTheUserTypedNone() {
        saver.answer(dir.resolve("mis pacientes"));
        ExportResult result = service.export(SPEC, ExportFormat.PDF, rows(1, 1));
        assertEquals("mis pacientes.pdf", result.fileName());
        assertTrue(Files.isRegularFile(dir.resolve("mis pacientes.pdf")));
        assertFalse(Files.exists(dir.resolve("mis pacientes")));
    }

    @Test
    void anExistingFileIsReplacedAndNoTemporaryFileRemains() throws IOException {
        Files.writeString(dir.resolve("salida.xlsx"), "old");
        service.export(SPEC, ExportFormat.XLSX, rows(2, 2));
        assertEquals("Peña 1", sheet(dir.resolve("salida.xlsx")).getRow(ExcelExporter.HEADER_ROW + 1).getCell(1)
            .getStringCellValue());
        try (var files = Files.list(dir)) {
            assertEquals(List.of("salida.xlsx"), files.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void aTruncatedExportSaysSoInTheSubtitleAndTheAudit() throws IOException {
        service.export(SPEC, ExportFormat.XLSX, rows(2, 7123));
        String subtitle = sheet(dir.resolve("salida.xlsx")).getRow(1).getCell(0).getStringCellValue();
        assertEquals("Generado el 2026-11-04 10:00 por Administrador - Se exportaron los primeros 2 de 7123 registros",
            subtitle);
        assertEquals("Exportación Excel - 2 de 7123 registro(s)", audit.only().detalle());
    }

    @Test
    void theUsernameIsUsedWhenThereIsNoDisplayName() throws IOException {
        current = new SessionContext(1, "admin", Rol.ADMIN, null, " ");
        service.export(SPEC, ExportFormat.XLSX, rows(1, 1));
        assertEquals("Generado el 2026-11-04 10:00 por admin",
            sheet(dir.resolve("salida.xlsx")).getRow(1).getCell(0).getStringCellValue());
    }

    @Test
    void aWriteFailureIsACleanErrorWithoutAudit() throws IOException {
        Path folder = Files.createDirectory(dir.resolve("carpeta.xlsx"));
        Files.writeString(folder.resolve("dentro.txt"), "x"); // a non-empty folder cannot be replaced by a file
        saver.answer(folder);
        ExportFailedException ex = assertThrows(ExportFailedException.class,
            () -> service.export(SPEC, ExportFormat.XLSX, rows(1, 1)));
        assertEquals(ExportFailedException.WRITE_FAILED, ex.userMessage());
        assertTrue(audit.entries().isEmpty());
        try (var files = Files.list(dir)) {
            assertEquals(List.of("carpeta.xlsx"), files.map(p -> p.getFileName().toString()).toList(),
                "the temporary file is cleaned up");
        }
    }

    @Test
    void aMissingFolderIsACleanError() {
        saver.answer(dir.resolve("no-existe").resolve("salida.xlsx"));
        ExportFailedException ex = assertThrows(ExportFailedException.class,
            () -> service.export(SPEC, ExportFormat.XLSX, rows(1, 1)));
        assertEquals(ExportFailedException.WRITE_FAILED, ex.userMessage());
    }
}
