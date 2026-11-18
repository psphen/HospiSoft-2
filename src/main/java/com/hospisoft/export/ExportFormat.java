package com.hospisoft.export;

import java.util.Locale;
import java.util.Optional;

/** File formats a list can be exported to. The code sent by the page is the lower-case extension. */
public enum ExportFormat {
    XLSX("xlsx", "Excel", "Libro de Excel (*.xlsx)"),
    PDF("pdf", "PDF", "Documento PDF (*.pdf)");

    private final String extension;
    private final String label;
    private final String description;

    ExportFormat(String extension, String label, String description) {
        this.extension = extension;
        this.label = label;
        this.description = description;
    }

    /** Without the dot, e.g. "xlsx". */
    public String extension() {
        return extension;
    }

    /** Short Spanish name used in messages and in the audit trail ("Excel", "PDF"). */
    public String label() {
        return label;
    }

    /** Spanish description for the save dialog's extension filter. */
    public String description() {
        return description;
    }

    /** Appends the extension unless the name already ends with it (ignoring case). */
    public String withExtension(String fileName) {
        String suffix = "." + extension;
        return fileName.toLowerCase(Locale.ROOT).endsWith(suffix) ? fileName : fileName + suffix;
    }

    /** @return the format for "xlsx" / "pdf" (case and surrounding blanks ignored); empty otherwise */
    public static Optional<ExportFormat> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String clean = code.trim().toLowerCase(Locale.ROOT);
        for (ExportFormat format : values()) {
            if (format.extension.equals(clean)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }
}
