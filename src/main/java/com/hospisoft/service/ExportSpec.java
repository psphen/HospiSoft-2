package com.hospisoft.service;

import com.hospisoft.model.ModuloAuditoria;
import java.util.List;

/**
 * Fixed description of one module's export: audit module, base of the suggested file name,
 * document title and columns.
 *
 * @param fileBaseName e.g. "pacientes": the dialog suggests pacientes_yyyy-MM-dd.xlsx
 * @param widths       relative column widths for the PDF (one per header)
 */
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
