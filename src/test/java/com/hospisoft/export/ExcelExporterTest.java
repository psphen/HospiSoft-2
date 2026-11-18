package com.hospisoft.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class ExcelExporterTest {

    private static XSSFWorkbook open(byte[] bytes) throws IOException {
        return new XSSFWorkbook(new ByteArrayInputStream(bytes));
    }

    @Test
    void writesTitleSubtitleHeadersAndRows() throws IOException {
        try (XSSFWorkbook book = open(new ExcelExporter().export(ExportTestData.table()))) {
            XSSFSheet sheet = book.getSheetAt(0);
            assertEquals(ExportTestData.TITLE, sheet.getSheetName());
            assertEquals(ExportTestData.TITLE, sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals(ExportTestData.SUBTITLE, sheet.getRow(1).getCell(0).getStringCellValue());

            Row header = sheet.getRow(ExcelExporter.HEADER_ROW);
            List<String> headers = new ArrayList<>();
            header.forEach(cell -> headers.add(cell.getStringCellValue()));
            assertEquals(ExportTestData.HEADERS, headers);

            Row first = sheet.getRow(ExcelExporter.HEADER_ROW + 1);
            assertEquals("María Peña Núñez", first.getCell(2).getStringCellValue());
            assertEquals(ExcelExporter.HEADER_ROW + 3, sheet.getLastRowNum());
        }
    }

    @Test
    void everyValueIsAStringCellNeverAFormula() throws IOException {
        try (XSSFWorkbook book = open(new ExcelExporter().export(ExportTestData.table()))) {
            XSSFSheet sheet = book.getSheetAt(0);
            for (int r = ExcelExporter.HEADER_ROW + 1; r <= sheet.getLastRowNum(); r++) {
                for (Cell cell : sheet.getRow(r)) {
                    assertEquals(CellType.STRING, cell.getCellType(), "row " + r + " col " + cell.getColumnIndex());
                }
            }
            assertEquals("=cmd|' /C calc'!A0", sheet.getRow(ExcelExporter.HEADER_ROW + 2).getCell(1).getStringCellValue());
            assertEquals("+SUM(1,2)", sheet.getRow(ExcelExporter.HEADER_ROW + 3).getCell(1).getStringCellValue());
            assertEquals("", sheet.getRow(ExcelExporter.HEADER_ROW + 3).getCell(2).getStringCellValue());
        }
    }

    @Test
    void headerIsBoldFilledFrozenAndFiltered() throws IOException {
        try (XSSFWorkbook book = open(new ExcelExporter().export(ExportTestData.table()))) {
            XSSFSheet sheet = book.getSheetAt(0);
            XSSFCellStyle style = (XSSFCellStyle) sheet.getRow(ExcelExporter.HEADER_ROW).getCell(0).getCellStyle();
            assertTrue(style.getFont().getBold());
            assertEquals(FillPatternType.SOLID_FOREGROUND, style.getFillPattern());

            assertNotNull(sheet.getPaneInformation(), "the header must be frozen");
            assertEquals(ExcelExporter.HEADER_ROW + 1, sheet.getPaneInformation().getHorizontalSplitPosition());

            assertTrue(sheet.getCTWorksheet().isSetAutoFilter(), "the header must carry an auto-filter");
            assertEquals("A4:D7", sheet.getCTWorksheet().getAutoFilter().getRef());
        }
    }

    @Test
    void columnsAreSizedToTheirContentWithACap() throws IOException {
        String longText = "x".repeat(500);
        TableExport table = new TableExport("Detalle", "", List.of("#", "Detalle"),
            List.of(List.of("1", longText)), null);
        try (XSSFWorkbook book = open(new ExcelExporter().export(table))) {
            XSSFSheet sheet = book.getSheetAt(0);
            assertTrue(sheet.getColumnWidth(0) >= ExcelExporter.MIN_COLUMN_CHARS * 256);
            assertEquals(ExcelExporter.MAX_COLUMN_CHARS * 256, sheet.getColumnWidth(1));
            assertEquals(longText, sheet.getRow(ExcelExporter.HEADER_ROW + 1).getCell(1).getStringCellValue());
        }
    }

    @Test
    void anEmptyTableStillHasItsHeaderAndAValidSheetName() throws IOException {
        TableExport table = new TableExport("Auditoría: acciones [2026]", "", List.of("Fecha"), List.of(), null);
        try (XSSFWorkbook book = open(new ExcelExporter().export(table))) {
            XSSFSheet sheet = book.getSheetAt(0);
            assertEquals("Fecha", sheet.getRow(ExcelExporter.HEADER_ROW).getCell(0).getStringCellValue());
            assertEquals(ExcelExporter.HEADER_ROW, sheet.getLastRowNum());
            assertTrue(!sheet.getSheetName().contains(":") && !sheet.getSheetName().contains("["));
        }
    }

    @Test
    void cellsLongerThanExcelAllowsAreTruncated() throws IOException {
        String huge = "a".repeat(ExcelExporter.MAX_CELL_CHARS + 10);
        TableExport table = new TableExport("T", "", List.of("A"), List.of(List.of(huge)), null);
        try (XSSFWorkbook book = open(new ExcelExporter().export(table))) {
            assertEquals(ExcelExporter.MAX_CELL_CHARS,
                book.getSheetAt(0).getRow(ExcelExporter.HEADER_ROW + 1).getCell(0).getStringCellValue().length());
        }
    }
}
