package com.hospisoft.service;

public record ExportResult(boolean saved, String fileName, int rows) {

    public static final ExportResult CANCELLED = new ExportResult(false, null, 0);

    public static ExportResult saved(String fileName, int rows) {
        return new ExportResult(true, fileName, rows);
    }
}
