package com.hospisoft.bridge;

import com.hospisoft.model.Medico;
import com.hospisoft.model.Paciente;
import com.hospisoft.model.Page;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Conversions between model records and the plain JSON objects exchanged with the page.
 * Dates travel as ISO strings (yyyy-MM-dd); ids as numbers. {@code activo} is optional in input:
 * the record carries true when omitted and the service decides (insert: true; update: keep stored).
 */
public final class Dtos {

    private Dtos() {
    }

    public static Map<String, Object> toMap(Medico m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", m.id());
        map.put("documento", m.documento());
        map.put("nombres", m.nombres());
        map.put("apellidos", m.apellidos());
        map.put("nombreCompleto", m.nombreCompleto());
        map.put("especialidad", m.especialidad());
        map.put("telefono", m.telefono());
        map.put("correo", m.correo());
        map.put("activo", m.activo());
        return map;
    }

    public static Medico medicoFrom(Map<String, Object> body) {
        return new Medico(
            Payload.optLong(body, "id"),
            Payload.optString(body, "documento"),
            Payload.optString(body, "nombres"),
            Payload.optString(body, "apellidos"),
            Payload.optString(body, "especialidad"),
            Payload.optString(body, "telefono"),
            Payload.optString(body, "correo"),
            Payload.optBoolean(body, "activo", true));
    }

    /** @param today used to compute {@code edad} */
    public static Map<String, Object> toMap(Paciente p, LocalDate today) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", p.id());
        map.put("documento", p.documento());
        map.put("nombres", p.nombres());
        map.put("apellidos", p.apellidos());
        map.put("nombreCompleto", p.nombreCompleto());
        map.put("fechaNacimiento", p.fechaNacimiento() == null ? null : p.fechaNacimiento().toString());
        map.put("edad", p.edad(today));
        map.put("sexo", p.sexo());
        map.put("telefono", p.telefono());
        map.put("correo", p.correo());
        map.put("direccion", p.direccion());
        map.put("activo", p.activo());
        return map;
    }

    public static Paciente pacienteFrom(Map<String, Object> body) {
        return new Paciente(
            Payload.optLong(body, "id"),
            Payload.optString(body, "documento"),
            Payload.optString(body, "nombres"),
            Payload.optString(body, "apellidos"),
            Payload.optDate(body, "fechaNacimiento"),
            Payload.optString(body, "sexo"),
            Payload.optString(body, "telefono"),
            Payload.optString(body, "correo"),
            Payload.optString(body, "direccion"),
            Payload.optBoolean(body, "activo", true));
    }

    /** {@code {items, total, page, pageSize, totalPages}} */
    public static <T> Map<String, Object> toMap(Page<T> page, Function<T, Map<String, Object>> item) {
        List<Map<String, Object>> items = new ArrayList<>(page.items().size());
        for (T t : page.items()) {
            items.add(item.apply(t));
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("items", items);
        map.put("total", page.total());
        map.put("page", page.page());
        map.put("pageSize", page.pageSize());
        map.put("totalPages", page.totalPages());
        return map;
    }
}
