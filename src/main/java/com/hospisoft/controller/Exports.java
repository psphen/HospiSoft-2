package com.hospisoft.controller;

import com.hospisoft.bridge.BadRequestException;
import com.hospisoft.bridge.Payload;
import com.hospisoft.export.ExportFormat;
import com.hospisoft.service.ExportResult;
import java.util.LinkedHashMap;
import java.util.Map;

final class Exports {
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
