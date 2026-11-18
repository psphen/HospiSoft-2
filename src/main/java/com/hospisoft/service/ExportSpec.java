package com.hospisoft.service;

import com.hospisoft.model.ModuloAuditoria;
import java.util.List;

public record ExportSpec(ModuloAuditoria modulo, String fileBaseName, String title, List<String> headers,
                         List<Integer> widths) {

    public ExportSpec {
        headers = List.copyOf(headers);
        widths = List.copyOf(widths);
        if (widths.size() != headers.size()) {
            throw new IllegalArgumentException("one width per header is required");
        }
    }
}
