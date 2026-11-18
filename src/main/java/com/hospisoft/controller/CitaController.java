package com.hospisoft.controller;

import com.hospisoft.bridge.BadRequestException;
import com.hospisoft.bridge.Bridge;
import com.hospisoft.bridge.Dtos;
import com.hospisoft.bridge.Payload;
import com.hospisoft.model.CitaView;
import com.hospisoft.model.EstadoCita;
import com.hospisoft.model.Medico;
import com.hospisoft.model.ModuloAuditoria;
import com.hospisoft.model.Paciente;
import com.hospisoft.service.Agenda;
import com.hospisoft.service.AppZone;
import com.hospisoft.service.CitaFilter;
import com.hospisoft.service.CitaInput;
import com.hospisoft.service.CitaRango;
import com.hospisoft.service.CitaService;
import com.hospisoft.service.ExportService;
import com.hospisoft.service.ExportSpec;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MVC controller of the cita module: receives the View's request from the JS bridge, parses the
 * payload, delegates to {@link CitaService} (the Model) and returns the DTO.
 *
 * <p>listCitas {@code {"filter":{"texto","fecha":"yyyy-MM-dd","estado","idMedico","rango"},"page","pageSize"}}
 * (exportCitas takes the same filter plus {@code "format":"xlsx|pdf"}, see {@link Exports});
 * getCita / cancelarCita {@code {"id"}}; saveCita {@code {"id":0|N,"idMedico","idPaciente","fecha","hora","motivo"}}
 * (id &gt; 0 reschedules); agendaMedico {@code {"idMedico"?,"fecha"?}}; listMedicosSelect / listPacientesSelect
 * (ADMIN). When {@code filter.fecha} is present the {@code rango} preset is ignored. Agenda slots carry
 * {@code "citas": [dto]} (every non-cancelled cita intersecting the slot). Error codes beyond the common
 * ones: VALIDATION on hora/fecha/estado/idMedico/idPaciente, NOT_FOUND (missing or out of scope),
 * CONFLICT (the cita left PROGRAMADA between the check and the write; reload and retry).
 * Cita DTO: id, idMedico, medicoNombre, especialidad, idPaciente, pacienteNombre,
 * pacienteDocumento, fecha (yyyy-MM-dd), hora (HH:mm), fechaHora (ISO-8601 with offset), motivo, estado,
 * horaInicioReal, horaFinReal, observaciones.
 */
public final class CitaController implements Controller {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    static final ExportSpec EXPORT = new ExportSpec(ModuloAuditoria.CITA, "citas", "Listado de Citas",
        List.of("#", "Paciente", "Documento", "Médico", "Especialidad", "Fecha", "Hora", "Estado"),
        List.of(1, 5, 3, 5, 4, 2, 1, 2));
    private static final Map<EstadoCita, String> ESTADO_LABEL = Map.of(
        EstadoCita.PROGRAMADA, "Programada",
        EstadoCita.EN_CURSO, "En curso",
        EstadoCita.FINALIZADA, "Finalizada",
        EstadoCita.CANCELADA, "Cancelada");

    private final CitaService citas;
    private final ExportService exports;

    public CitaController(CitaService citas, ExportService exports) {
        this.citas = citas;
        this.exports = exports;
    }

    @Override
    public void register(Bridge bridge) {
        bridge.register("listCitas", this::list);
        bridge.register("getCita", this::get);
        bridge.register("saveCita", this::save);
        bridge.register("cancelarCita", this::cancel);
        bridge.register("agendaMedico", this::agenda);
        bridge.register("listMedicosSelect", this::medicosForSelect);
        bridge.register("listPacientesSelect", this::pacientesForSelect);
        bridge.register("exportCitas", this::export);
    }

    private static CitaFilter filter(Map<String, Object> body) {
        Map<String, Object> f = Payload.optObject(body, "filter");
        return new CitaFilter(Payload.optString(f, "texto"), Payload.optDate(f, "fecha"),
            estado(Payload.optString(f, "estado")), Payload.optLong(f, "idMedico"),
            rango(Payload.optString(f, "rango")));
    }

    private Object list(String payload) {
        Map<String, Object> body = Payload.object(payload);
        return Dtos.toMap(citas.list(filter(body), Paging.page(body), Paging.pageSize(body)),
            view -> toMap(view, citas.zone()));
    }

    private Object export(String payload) {
        Map<String, Object> body = Payload.object(payload);
        CitaFilter filter = filter(body);
        var format = Exports.format(body);
        AppZone zone = citas.zone();
        return Exports.toMap(exports.export(EXPORT, format, citas.listForExport(filter).map(v -> row(v, zone))));
    }

    private static List<String> row(CitaView view, AppZone zone) {
        var c = view.cita();
        return List.of(Exports.text(c.id()), view.pacienteNombre(), Exports.text(view.pacienteDocumento()),
            view.medicoNombre(), Exports.text(view.especialidad()),
            zone.dateOf(c.fechaHoraProgramada()).toString(), zone.timeOf(c.fechaHoraProgramada()).format(TIME),
            ESTADO_LABEL.getOrDefault(c.estado(), Exports.text(c.estado())));
    }

    private Object get(String payload) {
        return toMap(citas.get(Payload.requireLong(Payload.object(payload), "id")), citas.zone());
    }

    /** Creates the cita when {@code id} is missing or 0; otherwise reschedules it. */
    private Object save(String payload) {
        Map<String, Object> body = Payload.object(payload);
        Long id = Payload.optLong(body, "id");
        CitaInput input = new CitaInput(Payload.optLong(body, "idMedico"), Payload.optLong(body, "idPaciente"),
            Payload.optString(body, "fecha"), Payload.optString(body, "hora"), Payload.optString(body, "motivo"));
        CitaView saved = id == null || id == 0 ? citas.create(input) : citas.reschedule(id, input);
        return toMap(saved, citas.zone());
    }

    private Object cancel(String payload) {
        return toMap(citas.cancel(Payload.requireLong(Payload.object(payload), "id")), citas.zone());
    }

    private Object agenda(String payload) {
        Map<String, Object> body = Payload.object(payload);
        Agenda agenda = citas.agenda(Payload.optLong(body, "idMedico"), Payload.optDate(body, "fecha"));
        return toMap(agenda, citas.zone());
    }

    private Object medicosForSelect(String payload) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Medico m : citas.medicosForSelect()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", m.id());
            map.put("nombres", m.nombres());
            map.put("apellidos", m.apellidos());
            map.put("especialidad", m.especialidad());
            items.add(map);
        }
        return items;
    }

    private Object pacientesForSelect(String payload) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Paciente p : citas.pacientesForSelect()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", p.id());
            map.put("documento", p.documento());
            map.put("nombres", p.nombres());
            map.put("apellidos", p.apellidos());
            items.add(map);
        }
        return items;
    }

    private static EstadoCita estado(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return EstadoCita.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Unknown estado: " + raw);
        }
    }

    private static CitaRango rango(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return CitaRango.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Unknown rango: " + raw);
        }
    }

    /** Cita DTO; also used by {@link AtencionController}, whose responses embed the cita. */
    static Map<String, Object> toMap(CitaView view, AppZone zone) {
        var c = view.cita();
        LocalDate fecha = zone.dateOf(c.fechaHoraProgramada());
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", c.id());
        map.put("idMedico", c.idMedico());
        map.put("medicoNombre", view.medicoNombre());
        map.put("especialidad", view.especialidad());
        map.put("idPaciente", c.idPaciente());
        map.put("pacienteNombre", view.pacienteNombre());
        map.put("pacienteDocumento", view.pacienteDocumento());
        map.put("fecha", fecha.toString());
        map.put("hora", zone.timeOf(c.fechaHoraProgramada()).format(TIME));
        map.put("fechaHora", iso(c.fechaHoraProgramada()));
        map.put("motivo", c.motivo());
        map.put("estado", c.estado());
        map.put("horaInicioReal", iso(c.horaInicioReal()));
        map.put("horaFinReal", iso(c.horaFinReal()));
        map.put("observaciones", c.observaciones());
        return map;
    }

    private static Map<String, Object> toMap(Agenda agenda, AppZone zone) {
        List<Map<String, Object>> slots = new ArrayList<>(agenda.slots().size());
        for (Agenda.Slot slot : agenda.slots()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("hora", slot.hora().format(TIME));
            List<Map<String, Object>> citas = new ArrayList<>(slot.citas().size());
            for (CitaView view : slot.citas()) {
                citas.add(toMap(view, zone));
            }
            s.put("citas", citas);
            slots.add(s);
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("idMedico", agenda.idMedico());
        map.put("fecha", agenda.fecha().toString());
        map.put("slots", slots);
        return map;
    }

    private static String iso(OffsetDateTime value) {
        return value == null ? null : value.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
