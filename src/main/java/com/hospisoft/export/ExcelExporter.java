package com.hospisoft.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.List;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Writes a {@link TableExport} as an .xlsx workbook with Apache POI: title and subtitle on top,
 * a bold filled header row that stays frozen and carries an auto-filter, then one row per record.
 *
 * <p>Every value is written as a STRING cell, never as a formula, so text such as
 * {@code =cmd|...} or {@code +SUM(...)} coming from the database stays inert (no formula injection).
 * Column widths follow the longest value, capped at {@link #MAX_COLUMN_CHARS}.
 */
public final class ExcelExporter {

    /** 0-based index of the header row (title, subtitle and a blank row come first). */
    public static final int HEADER_ROW = 3;
    static final int MIN_COLUMN_CHARS = 6;
    static final int MAX_COLUMN_CHARS = 60;
    /** Excel's limit for the text of one cell. */
    static final int MAX_CELL_CHARS = 32_767;
    private static final int CHAR_WIDTH_UNITS = 256;
    private static final String DEFAULT_SHEET = "Datos";

    public byte[] export(TableExport table) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(table, out);
        return out.toByteArray();
    }

    public void write(TableExport table, OutputStream out) {
        try (XSSFWorkbook book = new XSSFWorkbook()) {
            XSSFSheet sheet = book.createSheet(sheetName(table.title()));
            Styles styles = new Styles(book);
            int columns = table.columnCount();
            int[] widest = new int[columns];

            text(sheet.createRow(0), 0, table.title(), styles.title);
            text(sheet.createRow(1), 0, table.subtitle(), styles.subtitle);

            Row header = sheet.createRow(HEADER_ROW);
            for (int c = 0; c < columns; c++) {
                String value = table.headers().get(c);
                text(header, c, value, styles.header);
                widest[c] = Math.max(widest[c], value.length());
            }

            int r = HEADER_ROW + 1;
            for (List<String> cells : table.rows()) {
                Row row = sheet.createRow(r++);
                for (int c = 0; c < columns; c++) {
                    String value = cap(cells.get(c));
                    text(row, c, value, styles.body);
                    widest[c] = Math.max(widest[c], longestLine(value));
                }
            }

            int lastRow = HEADER_ROW + table.rows().size();
            sheet.createFreezePane(0, HEADER_ROW + 1);
            sheet.setAutoFilter(new CellRangeAddress(HEADER_ROW, lastRow, 0, columns - 1));
            for (int c = 0; c < columns; c++) {
                int chars = Math.min(MAX_COLUMN_CHARS, Math.max(MIN_COLUMN_CHARS, widest[c] + 2));
                sheet.setColumnWidth(c, chars * CHAR_WIDTH_UNITS);
            }
            book.write(out);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** Always a string cell: {@code setCellValue(String)} never creates a formula. */
    private static void text(Row row, int column, String value, CellStyle style) {
        var cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private static String cap(String value) {
        return value.length() <= MAX_CELL_CHARS ? value : value.substring(0, MAX_CELL_CHARS);
    }

    private static int longestLine(String value) {
        int longest = 0;
        for (String line : value.split("\n", -1)) {
            longest = Math.max(longest, line.length());
        }
        return longest;
    }

    /** Excel sheet names: at most 31 chars, none of {@code : \ / ? * [ ]}. */
    static String sheetName(String title) {
        String clean = title == null ? "" : title.replaceAll("[:\\\\/?*\\[\\]]", " ").trim();
        return clean.isEmpty() ? DEFAULT_SHEET : WorkbookUtil.createSafeSheetName(clean);
    }

    private static final class Styles {
        final CellStyle title;
        final CellStyle subtitle;
        final CellStyle header;
        final CellStyle body;

        Styles(XSSFWorkbook book) {
            Font titleFont = book.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            title = book.createCellStyle();
            title.setFont(titleFont);

            Font subtitleFont = book.createFont();
            subtitleFont.setItalic(true);
            subtitleFont.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
            subtitle = book.createCellStyle();
            subtitle.setFont(subtitleFont);

            Font headerFont = book.createFont();
            headerFont.setBold(true);
            header = book.createCellStyle();
            header.setFont(headerFont);
            header.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setBorderBottom(BorderStyle.THIN);
            header.setVerticalAlignment(VerticalAlignment.CENTER);

            body = book.createCellStyle();
            body.setVerticalAlignment(VerticalAlignment.TOP);
        }
    }
}
