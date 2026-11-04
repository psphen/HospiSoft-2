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
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
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
 */
public final class AuditoriaController implements Controller {

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AuditoriaService auditoria;

    public AuditoriaController(AuditoriaService auditoria) {
        this.auditoria = auditoria;
    }

    @Override
    public void register(Bridge bridge) {
        bridge.register("listAuditoria", this::list);
    }

    private Object list(String payload) {
        Map<String, Object> body = Payload.object(payload);
        Map<String, Object> f = Payload.optObject(body, "filter");
        AuditoriaFilter filter = new AuditoriaFilter(Payload.optString(f, "texto"),
            code(Payload.optString(f, "accion"), AccionAuditoria::valueOf, "accion"),
            code(Payload.optString(f, "modulo"), ModuloAuditoria::valueOf, "modulo"),
            Payload.optDate(f, "desde"), Payload.optDate(f, "hasta"));
        return Dtos.toMap(auditoria.list(filter, Paging.page(body), Paging.pageSize(body)),
            entry -> toMap(entry, auditoria.zone()));
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
