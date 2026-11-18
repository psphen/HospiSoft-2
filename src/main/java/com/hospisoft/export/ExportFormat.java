package com.hospisoft.export;

import java.util.Locale;
import java.util.Optional;

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

    public String extension() {
        return extension;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public String withExtension(String fileName) {
        String suffix = "." + extension;
        return fileName.toLowerCase(Locale.ROOT).endsWith(suffix) ? fileName : fileName + suffix;
    }

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
