package com.hospisoft.export;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Format-neutral table handed to the exporters: a title, a subtitle (e.g. "Generado el ... por ..."),
 * the column headers and the rows as display-ready text. Null cells become empty strings.
 *
 * @param widths relative column width hints (one positive weight per column); null or empty means
 *               equal widths. The PDF uses them as relative widths; Excel sizes columns by content.
 */
public record TableExport(String title, String subtitle, List<String> headers, List<List<String>> rows,
                          List<Integer> widths) {

    public TableExport {
        title = title == null ? "" : title;
        subtitle = subtitle == null ? "" : subtitle;
        if (headers == null || headers.isEmpty()) {
            throw new IllegalArgumentException("at least one header is required");
        }
        for (String header : headers) { // List.of(...).contains(null) would throw instead of answering
            if (header == null) {
                throw new IllegalArgumentException("headers must not be null");
            }
        }
        headers = List.copyOf(headers);
        int columns = headers.size();

        List<List<String>> copy = new ArrayList<>(rows == null ? 0 : rows.size());
        if (rows != null) {
            for (List<String> row : rows) {
                if (row == null || row.size() != columns) {
                    throw new IllegalArgumentException("every row must have " + columns + " cells");
                }
                List<String> cells = new ArrayList<>(columns);
                for (String cell : row) {
                    cells.add(cell == null ? "" : cell);
                }
                copy.add(Collections.unmodifiableList(cells));
            }
        }
        rows = Collections.unmodifiableList(copy);

        if (widths == null || widths.isEmpty()) {
            widths = Collections.nCopies(columns, 1);
        } else {
            if (widths.size() != columns) {
                throw new IllegalArgumentException("one width hint per column is required");
            }
            for (Integer width : widths) {
                if (width == null || width < 1) {
                    throw new IllegalArgumentException("width hints must be positive");
                }
            }
            widths = List.copyOf(widths);
        }
    }

    public int columnCount() {
        return headers.size();
    }
}
