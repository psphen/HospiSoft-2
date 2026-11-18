package com.hospisoft.service;

import com.hospisoft.dao.AuditoriaQuery;
import com.hospisoft.dao.AuditoriaRepository;
import com.hospisoft.model.AccionAuditoria;
import com.hospisoft.model.Auditoria;
import com.hospisoft.model.ModuloAuditoria;
import com.hospisoft.model.Page;
import com.hospisoft.model.Rol;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class AuditoriaService implements AuditLog {
    public static final int MAX_DETALLE = 500;
    public static final int MAX_USERNAME = 60;

    static final String INVERTED_RANGE = "La fecha final no puede ser anterior a la inicial";

    private final AuditoriaRepository auditoria;
    private final Supplier<Optional<SessionContext>> session;
    private final Access access;
    private final AppZone zone;
    private final Consumer<String> errorLog;

    public AuditoriaService(AuditoriaRepository auditoria, Supplier<Optional<SessionContext>> session, AppZone zone) {
        this(auditoria, session, zone, System.err::println);
    }

    public AuditoriaService(AuditoriaRepository auditoria, Supplier<Optional<SessionContext>> session, AppZone zone,
                            Consumer<String> errorLog) {
        this.auditoria = auditoria;
        this.session = session;
        this.access = new Access(session);
        this.zone = zone;
        this.errorLog = errorLog;
    }

    public AppZone zone() {
        return zone;
    }

    @Override
    public void record(AccionAuditoria accion, ModuloAuditoria modulo, Long idRegistro, String detalle) {
        safely(accion, modulo, () -> write(session.get().orElse(null), accion, modulo, idRegistro, detalle));
    }

    @Override
    public void recordAs(SessionContext actor, AccionAuditoria accion, ModuloAuditoria modulo, Long idRegistro,
                         String detalle) {
        safely(accion, modulo, () -> write(actor, accion, modulo, idRegistro, detalle));
    }

    @Override
    public void recordFailedLogin(String username, String detalle) {
        safely(AccionAuditoria.LOGIN_FALLIDO, ModuloAuditoria.SESION, () -> auditoria.insert(new Auditoria(null, null,
            null, truncate(username, MAX_USERNAME), null, AccionAuditoria.LOGIN_FALLIDO, ModuloAuditoria.SESION, null,
            truncate(detalle, MAX_DETALLE))));
    }

    private void write(SessionContext actor, AccionAuditoria accion, ModuloAuditoria modulo, Long idRegistro,
                       String detalle) {
        Long idUsuario = actor == null ? null : actor.userId();
        String username = actor == null ? null : truncate(actor.username(), MAX_USERNAME);
        Rol rol = actor == null ? null : actor.rol();
        auditoria.insert(new Auditoria(null, null, idUsuario, username, rol, accion, modulo, idRegistro,
            truncate(detalle, MAX_DETALLE)));
    }

    private void safely(AccionAuditoria accion, ModuloAuditoria modulo, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException ex) {
            try {
                errorLog.accept("[audit] could not record " + accion + " on " + modulo + ": " + describe(ex));
            } catch (RuntimeException ignored) {}
        }
    }

    static String describe(Throwable ex) {
        StringBuilder sb = new StringBuilder();
        Throwable current = ex;
        int depth = 0;
        while (current != null && depth++ < 5) {
            if (sb.length() > 0) {
                sb.append(" <- ");
            }
            sb.append(current.getClass().getName());
            if (current instanceof SQLException sql && sql.getSQLState() != null) {
                sb.append("[SQLState ").append(sql.getSQLState()).append(']');
            }
            current = current.getCause();
        }
        return sb.toString();
    }

    static String truncate(String value, int max) {
        String clean = Validation.clean(value);
        if (clean == null || clean.length() <= max) {
            return clean;
        }
        int end = Character.isHighSurrogate(clean.charAt(max - 1)) ? max - 1 : max;
        return clean.substring(0, end);
    }

    public Page<Auditoria> list(AuditoriaFilter filter, int page, int pageSize) {
        access.require(Rol.ADMIN);
        AuditoriaFilter f = filter == null ? AuditoriaFilter.NONE : filter;
        Validation.checkFilterText(f.texto());
        if (f.desde() != null && f.hasta() != null && f.hasta().isBefore(f.desde())) {
            throw new ValidationException(Map.of("hasta", INVERTED_RANGE));
        }
        OffsetDateTime from = f.desde() == null ? null : zone.dayStart(f.desde());
        OffsetDateTime to = f.hasta() == null ? null : zone.dayEnd(f.hasta());
        return auditoria.findPage(new AuditoriaQuery(f.texto(), f.accion(), f.modulo(), from, to), page, pageSize);
    }

    public ExportRows<Auditoria> listForExport(AuditoriaFilter filter) {
        return ExportRows.collect((page, pageSize) -> list(filter, page, pageSize));
    }
}
