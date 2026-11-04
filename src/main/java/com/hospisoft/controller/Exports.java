package com.hospisoft.controller;

import com.hospisoft.bridge.BadRequestException;
import com.hospisoft.bridge.Payload;
import com.hospisoft.export.ExportFormat;
import com.hospisoft.service.ExportResult;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Payload and response helpers shared by the export methods of every module controller.
 *
 * <p>Export payload: {@code {"filter":{...same fields as the list...},"format":"xlsx"|"pdf"}}; paging
 * fields are ignored (the whole filter is exported, up to the row cap). Response:
 * {@code {"saved":true,"fileName":"x.xlsx","rows":N}} or {@code {"saved":false}} when the dialog was
 * cancelled. A missing or unknown format is BAD_REQUEST; a file that cannot be written is EXPORT_FAILED.
 */
final class Exports {

    /** Shown in place of a missing value, like the list tables do. */
    static final String NONE = "–";

    private Exports() {
    }

    static ExportFormat format(Map<String, Object> body) {
        String raw = Payload.optString(body, "format");
        return ExportFormat.fromCode(raw)
            .orElseThrow(() -> new BadRequestException("format must be xlsx or pdf"));
    }

    static Map<String, Object> toMap(ExportResult result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("saved", result.saved());
        if (result.saved()) {
            map.put("fileName", result.fileName());
            map.put("rows", result.rows());
        }
        return map;
    }

    static String yesNo(boolean value) {
        return value ? "Sí" : "No";
    }

    static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
