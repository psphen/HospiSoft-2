package com.hospisoft.service;

import com.hospisoft.export.ExcelExporter;
import com.hospisoft.export.ExportFormat;
import com.hospisoft.export.FileSaver;
import com.hospisoft.export.PdfExporter;
import com.hospisoft.export.TableExport;
import com.hospisoft.model.AccionAuditoria;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Turns already permission-checked rows (see {@link ExportRows}) into a file: renders the
 * workbook or PDF, asks the user where to save it, writes it and records EXPORTAR in the audit log.
 *
 * <p>Rendering happens before the dialog, so a failure never leaves a half-written file. The file
 * is written to a temporary sibling and then moved over the target, so an existing file is either
 * fully replaced or left untouched. Cancelling the dialog is a normal outcome, not an error, and
 * is not audited (nothing left the application).
 */
public final class ExportService {

    private static final DateTimeFormatter GENERATED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String UNKNOWN_USER = "usuario desconocido";

    private final FileSaver saver;
    private final AuditLog audit;
    private final Supplier<Optional<SessionContext>> session;
    private final Clock clock;
    private final AppZone zone;
    private final Consumer<String> errorLog;
    private final ExcelExporter excel = new ExcelExporter();
    private final PdfExporter pdf = new PdfExporter();

    public ExportService(FileSaver saver, AuditLog audit, Supplier<Optional<SessionContext>> session, Clock clock,
                         AppZone zone) {
        this(saver, audit, session, clock, zone, System.err::println);
    }

    /** @param errorLog receives one line per failed export (class names only, never paths or data) */
    public ExportService(FileSaver saver, AuditLog audit, Supplier<Optional<SessionContext>> session, Clock clock,
                         AppZone zone, Consumer<String> errorLog) {
        this.saver = saver;
        this.audit = audit;
        this.session = session;
        this.clock = clock;
        this.zone = zone;
        this.errorLog = errorLog;
    }

    /**
     * @param rows display-ready cells, one list per record, in the order of {@code spec.headers()}
     * @throws ExportFailedException when the file cannot be generated or written
     */
    public ExportResult export(ExportSpec spec, ExportFormat format, ExportRows<List<String>> rows) {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone.zone()));
        TableExport table = new TableExport(spec.title(), subtitle(now, rows), spec.headers(), rows.items(),
            spec.widths());
        byte[] bytes = render(table, format);

        Optional<Path> chosen = saver.choose(suggestedFileName(spec, format, now.toLocalDate()), format);
        if (chosen.isEmpty()) {
            return ExportResult.CANCELLED;
        }
        Path target = withExtension(chosen.get(), format);
        write(target, bytes);
        audit.record(AccionAuditoria.EXPORTAR, spec.modulo(), null, detalle(format, rows));
        return ExportResult.saved(target.getFileName().toString(), rows.items().size());
    }

    static String suggestedFileName(ExportSpec spec, ExportFormat format, LocalDate day) {
        return format.withExtension(spec.fileBaseName() + "_" + day);
    }

    private String subtitle(ZonedDateTime now, ExportRows<?> rows) {
        String text = "Generado el " + now.format(GENERATED_AT) + " por " + userName();
        if (rows.truncated()) {
            text += " - Se exportaron los primeros " + rows.items().size() + " de " + rows.total() + " registros";
        }
        return text;
    }

    private String userName() {
        return session.get().map(s -> {
            String display = Validation.clean(s.displayName());
            return display != null ? display : Validation.clean(s.username());
        }).filter(name -> name != null).orElse(UNKNOWN_USER);
    }

    /** Audit text, e.g. "Exportación PDF - 37 registro(s)" or "Exportación Excel - 5000 de 7123 registro(s)". */
    private static String detalle(ExportFormat format, ExportRows<?> rows) {
        String count = rows.truncated()
            ? rows.items().size() + " de " + rows.total()
            : String.valueOf(rows.items().size());
        return "Exportación " + format.label() + " - " + count + " registro(s)";
    }

    private byte[] render(TableExport table, ExportFormat format) {
        try {
            return format == ExportFormat.PDF ? pdf.export(table) : excel.export(table);
        } catch (RuntimeException ex) {
            log("could not render " + format, ex);
            throw new ExportFailedException(ExportFailedException.RENDER_FAILED, ex);
        }
    }

    private static Path withExtension(Path chosen, ExportFormat format) {
        String name = chosen.getFileName().toString();
        String fixed = format.withExtension(name);
        return fixed.equals(name) ? chosen : chosen.resolveSibling(fixed);
    }

    /** Temporary sibling first, then a move over the target: the target is never left half-written. */
    private void write(Path target, byte[] bytes) {
        Path temp = null;
        try {
            if (Files.isDirectory(target)) {
                throw new IOException("target is a directory");
            }
            Path folder = target.toAbsolutePath().getParent();
            temp = Files.createTempFile(folder, ".hospisoft-export-", ".tmp");
            Files.write(temp, bytes);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException ex) {
            deleteQuietly(temp);
            log("could not write the file", ex);
            throw new ExportFailedException(ExportFailedException.WRITE_FAILED, ex);
        }
    }

    private static void deleteQuietly(Path temp) {
        if (temp == null) {
            return;
        }
        try {
            Files.deleteIfExists(temp);
        } catch (IOException ignored) {
            // best effort: a leftover hidden .tmp file is harmless
        }
    }

    private void log(String what, Throwable ex) {
        try {
            errorLog.accept("[export] " + what + ": " + AuditoriaService.describe(ex));
        } catch (RuntimeException ignored) {
            // logging must never mask the real outcome
        }
    }
}
