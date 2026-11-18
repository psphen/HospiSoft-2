package com.hospisoft.controller;

import com.hospisoft.bridge.BadRequestException;
import com.hospisoft.bridge.Bridge;
import com.hospisoft.bridge.Dtos;
import com.hospisoft.bridge.Payload;
import com.hospisoft.model.AccionAuditoria;
import com.hospisoft.model.Auditoria;
import com.hospisoft.model.ModuloAuditoria;
import com.hospisoft.service.AppZone;
import com.hospisoft.service.AuditoriaFilter;
import com.hospisoft.service.AuditoriaService;
import com.hospisoft.service.ExportService;
import com.hospisoft.service.ExportSpec;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * MVC controller of the audit log (read-only): receives the View's request from the JS bridge,
 * parses the payload, delegates to {@link AuditoriaService} (the Model, ADMIN only) and returns
 * display-ready DTOs.
 *
 * <p>listAuditoria {@code {"filter":{"texto","accion","modulo","desde":"yyyy-MM-dd","hasta":"yyyy-MM-dd"},
 * "page","pageSize"}} -> {@code {"items":[dto],"total","page","pageSize","totalPages"}}, newest first.
 * {@code desde}/{@code hasta} are inclusive local days; blank selects mean "all". Unknown accion/modulo
 * codes and malformed dates are BAD_REQUEST; {@code hasta} before {@code desde} is VALIDATION.
 * DTO: id, fecha (yyyy-MM-dd HH:mm:ss in the app zone), fechaHora (ISO-8601 with offset), idUsuario,
 * username, rol, accion, accionLabel, modulo, moduloLabel, idRegistro, detalle.
 *
 * <p>exportAuditoria takes the same filter plus {@code "format":"xlsx|pdf"} (see {@link Exports}).
 */
public final class AuditoriaController implements Controller {

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    static final ExportSpec EXPORT = new ExportSpec(ModuloAuditoria.AUDITORIA, "auditoria", "Registro de Auditoría",
        List.of("Fecha", "Usuario", "Rol", "Acción", "Módulo", "Registro", "Detalle"),
        List.of(3, 3, 2, 3, 2, 1, 9));

    private final AuditoriaService auditoria;
    private final ExportService exports;

    public AuditoriaController(AuditoriaService auditoria, ExportService exports) {
        this.auditoria = auditoria;
        this.exports = exports;
    }

    @Override
    public void register(Bridge bridge) {
        bridge.register("listAuditoria", this::list);
        bridge.register("exportAuditoria", this::export);
    }

    private static AuditoriaFilter filter(Map<String, Object> body) {
        Map<String, Object> f = Payload.optObject(body, "filter");
        return new AuditoriaFilter(Payload.optString(f, "texto"),
            code(Payload.optString(f, "accion"), AccionAuditoria::valueOf, "accion"),
            code(Payload.optString(f, "modulo"), ModuloAuditoria::valueOf, "modulo"),
            Payload.optDate(f, "desde"), Payload.optDate(f, "hasta"));
    }

    private Object list(String payload) {
        Map<String, Object> body = Payload.object(payload);
        return Dtos.toMap(auditoria.list(filter(body), Paging.page(body), Paging.pageSize(body)),
            entry -> toMap(entry, auditoria.zone()));
    }

    /** Exporting the trail is itself recorded (EXPORTAR on module AUDITORIA). */
    private Object export(String payload) {
        Map<String, Object> body = Payload.object(payload);
        AuditoriaFilter filter = filter(body);
        var format = Exports.format(body);
        AppZone zone = auditoria.zone();
        return Exports.toMap(exports.export(EXPORT, format, auditoria.listForExport(filter).map(a -> row(a, zone))));
    }

    private static List<String> row(Auditoria a, AppZone zone) {
        return List.of(
            a.fecha() == null ? "" : a.fecha().atZoneSameInstant(zone.zone()).format(FECHA),
            a.username() == null ? Exports.NONE : a.username(),
            a.rol() == null ? Exports.NONE : a.rol().name(),
            a.accion().label(),
            a.modulo().label(),
            a.idRegistro() == null ? Exports.NONE : "#" + a.idRegistro(),
            Exports.text(a.detalle()));
    }

    private static <E extends Enum<E>> E code(String raw, Function<String, E> parse, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return parse.apply(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Unknown " + field + ": " + raw);
        }
    }

    static Map<String, Object> toMap(Auditoria a, AppZone zone) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", a.id());
        map.put("fecha", a.fecha() == null ? null : a.fecha().atZoneSameInstant(zone.zone()).format(FECHA));
        map.put("fechaHora", a.fecha() == null ? null : a.fecha().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        map.put("idUsuario", a.idUsuario());
        map.put("username", a.username());
        map.put("rol", a.rol());
        map.put("accion", a.accion());
        map.put("accionLabel", a.accion().label());
        map.put("modulo", a.modulo());
        map.put("moduloLabel", a.modulo().label());
        map.put("idRegistro", a.idRegistro());
        map.put("detalle", a.detalle());
        return map;
    }
}
