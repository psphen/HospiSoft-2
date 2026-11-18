package com.hospisoft.controller;

import com.hospisoft.bridge.Bridge;
import com.hospisoft.export.ExcelExporter;
import com.hospisoft.export.FakeFileSaver;
import com.hospisoft.service.AppZone;
import com.hospisoft.service.AuditLog;
import com.hospisoft.service.AuthService;
import com.hospisoft.service.ExportService;
import com.hospisoft.service.SessionHolder;
import com.hospisoft.service.StartupStatus;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Shared helpers for the controller tests. */
final class ControllerTestSupport {

    /** 2026-11-04 10:00 in Bogota: the export file names end in _2026-11-04. */
    static final Clock EXPORT_CLOCK = Clock.fixed(Instant.parse("2026-11-04T15:00:00Z"), ZoneOffset.UTC);
    static final AppZone EXPORT_ZONE = new AppZone(ZoneId.of("America/Bogota"));

    private ControllerTestSupport() {
    }

    /** Method names {@code controller} adds to a fresh bridge (the bridge's own built-ins excluded). */
    static Set<String> registeredNames(Controller controller) {
        Bridge bridge = new Bridge(new AuthService(username -> Optional.empty()), new SessionHolder(),
            StartupStatus::ok, page -> { }, script -> { });
        try {
            Set<String> builtIn = bridge.asyncMethodNames();
            controller.register(bridge);
            Set<String> added = new HashSet<>(bridge.asyncMethodNames());
            added.removeAll(builtIn);
            return added;
        } finally {
            bridge.shutdown();
        }
    }

    /** Export use case writing through {@code saver}, auditing into {@code audit}. */
    static ExportService exports(FakeFileSaver saver, AuditLog audit, SessionHolder sessions) {
        return new ExportService(saver, audit, sessions::current, EXPORT_CLOCK, EXPORT_ZONE, line -> { });
    }

    /** The header row and the data rows of an exported workbook, as text. */
    static List<List<String>> table(Path xlsx) throws IOException {
        try (InputStream in = Files.newInputStream(xlsx); XSSFWorkbook book = new XSSFWorkbook(in)) {
            XSSFSheet sheet = book.getSheetAt(0);
            List<List<String>> rows = new ArrayList<>();
            for (int r = ExcelExporter.HEADER_ROW; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                List<String> cells = new ArrayList<>();
                for (Cell cell : row) {
                    cells.add(cell.getStringCellValue());
                }
                rows.add(cells);
            }
            return rows;
        }
    }
}
