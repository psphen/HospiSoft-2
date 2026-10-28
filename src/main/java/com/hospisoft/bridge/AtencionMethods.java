package com.hospisoft.bridge;

import com.hospisoft.model.HistoriaView;
import com.hospisoft.model.Medicamento;
import com.hospisoft.model.RecetaView;
import com.hospisoft.service.Atencion;
import com.hospisoft.service.AtencionInput;
import com.hospisoft.service.AtencionService;
import com.hospisoft.service.AppZone;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registers the attention methods on the bridge (phase 5).
 *
 * <p>Payloads:
 * <ul>
 *   <li>iniciarAtencion {@code {"idCita":N}} -> cita dto (MEDICO, own cita, PROGRAMADA)</li>
 *   <li>finalizarAtencion {@code {"idCita","diagnostico","tratamiento","notas","observaciones",
 *       "recetas":[{"idMedicamento","cantidad","dosis","duracion","observacionNoDisponible"}]}} ->
 *       {@code {"idHistoria","cita":citaDto,"recetas":[recetaDto]}}</li>
 *   <li>getHistoriaCita {@code {"idCita"}} -> historia dto</li>
 *   <li>historialPaciente {@code {"idPaciente","page","pageSize"}} -> page of historia dtos (no recetas)</li>
 *   <li>misRecetas {@code {"page","pageSize"}} -> page of receta dtos with {@code fecha} (PACIENTE)</li>
 *   <li>medicamentosParaRecetar {@code {}} -> {@code [{id, codigo, nombre, presentacion, stock}]}</li>
 * </ul>
 *
 * <p>Error codes beyond the common ones: VALIDATION (field messages, including
 * {@code recetas[i].<field>}), NOT_FOUND (missing or out of scope), CONFLICT (the cita left its
 * expected state, or the attention was already closed).
 */
public final class AtencionMethods {

    private AtencionMethods() {
    }

    public static void register(Bridge bridge, AtencionService atenciones) {
        bridge.register("iniciarAtencion", payload ->
            CitaMethods.toMap(atenciones.iniciar(Payload.requireLong(Payload.object(payload), "idCita")),
                atenciones.zone()));
        bridge.register("finalizarAtencion", payload -> {
            Atencion result = atenciones.finalizar(inputFrom(Payload.object(payload)));
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("idHistoria", result.idHistoria());
            map.put("cita", CitaMethods.toMap(result.cita(), atenciones.zone()));
            map.put("recetas", recetas(result.recetas()));
            return map;
        });
        bridge.register("getHistoriaCita", payload ->
            toMap(atenciones.historiaDeCita(Payload.requireLong(Payload.object(payload), "idCita"))));
        bridge.register("historialPaciente", payload -> {
            Map<String, Object> body = Payload.object(payload);
            long idPaciente = Payload.requireLong(body, "idPaciente");
            return Dtos.toMap(atenciones.historialPaciente(idPaciente, CrudMethods.page(body),
                CrudMethods.pageSize(body)), AtencionMethods::toMap);
        });
        bridge.register("misRecetas", payload -> {
            Map<String, Object> body = Payload.object(payload);
            return Dtos.toMap(atenciones.misRecetas(CrudMethods.page(body), CrudMethods.pageSize(body)),
                AtencionMethods::toMap);
        });
        bridge.register("medicamentosParaRecetar", payload -> {
            List<Map<String, Object>> items = new ArrayList<>();
            for (Medicamento m : atenciones.medicamentosParaRecetar()) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", m.id());
                map.put("codigo", m.codigo());
                map.put("nombre", m.nombre());
                map.put("presentacion", m.presentacion());
                map.put("stock", m.stock());
                items.add(map);
            }
            return items;
        });
    }

    static AtencionInput inputFrom(Map<String, Object> body) {
        List<AtencionInput.RecetaInput> recetas = new ArrayList<>();
        Object raw = body.get("recetas");
        if (raw != null) {
            if (!(raw instanceof List<?> list)) {
                throw new BadRequestException("Field must be an array: recetas");
            }
            for (Object element : list) {
                if (!(element instanceof Map)) {
                    throw new BadRequestException("Each receta must be an object");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> line = (Map<String, Object>) element;
                recetas.add(new AtencionInput.RecetaInput(
                    Payload.optLong(line, "idMedicamento"),
                    line.get("cantidad"),
                    Payload.optString(line, "dosis"),
                    Payload.optString(line, "duracion"),
                    Payload.optString(line, "observacionNoDisponible")));
            }
        }
        return new AtencionInput(
            Payload.optLong(body, "idCita"),
            Payload.optString(body, "diagnostico"),
            Payload.optString(body, "tratamiento"),
            Payload.optString(body, "notas"),
            Payload.optString(body, "observaciones"),
            recetas);
    }

    public static Map<String, Object> toMap(HistoriaView view) {
        var h = view.historia();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", h.id());
        map.put("idCita", h.idCita());
        map.put("fecha", iso(h.fecha()));
        map.put("idPaciente", h.idPaciente());
        map.put("pacienteNombre", view.pacienteNombre());
        map.put("pacienteDocumento", view.pacienteDocumento());
        map.put("idMedico", h.idMedico());
        map.put("medicoNombre", view.medicoNombre());
        map.put("especialidad", view.especialidad());
        map.put("diagnostico", h.diagnostico());
        map.put("tratamiento", h.tratamiento());
        map.put("notas", h.notas());
        map.put("recetas", recetas(view.recetas()));
        return map;
    }

    public static Map<String, Object> toMap(RecetaView view) {
        var r = view.receta();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", r.id());
        map.put("idMedicamento", r.idMedicamento());
        map.put("codigo", view.codigo());
        map.put("nombre", view.nombre());
        map.put("presentacion", view.presentacion());
        map.put("cantidad", r.cantidad());
        map.put("dosis", r.dosis());
        map.put("duracion", r.duracion());
        map.put("entregado", r.entregado());
        map.put("observacionNoDisponible", r.observacionNoDisponible());
        map.put("fecha", iso(r.fechaHistoria()));
        return map;
    }

    private static List<Map<String, Object>> recetas(List<RecetaView> views) {
        List<Map<String, Object>> items = new ArrayList<>(views.size());
        for (RecetaView view : views) {
            items.add(toMap(view));
        }
        return items;
    }

    private static String iso(OffsetDateTime value) {
        return value == null ? null : value.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    /** Kept for symmetry with the other method registries. */
    static AppZone zoneOf(AtencionService service) {
        return service.zone();
    }
}
