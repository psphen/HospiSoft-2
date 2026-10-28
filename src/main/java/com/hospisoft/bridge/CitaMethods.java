package com.hospisoft.bridge;

import com.hospisoft.model.CitaView;
import com.hospisoft.model.EstadoCita;
import com.hospisoft.model.Medico;
import com.hospisoft.model.Paciente;
import com.hospisoft.service.Agenda;
import com.hospisoft.service.AppZone;
import com.hospisoft.service.CitaFilter;
import com.hospisoft.service.CitaInput;
import com.hospisoft.service.CitaRango;
import com.hospisoft.service.CitaService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Registers the cita methods on the bridge (phase 4).
 *
 * <p>listCitas {@code {"filter":{"texto","fecha":"yyyy-MM-dd","estado","idMedico","rango"},"page","pageSize"}};
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
public final class CitaMethods {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private CitaMethods() {
    }

    public static void register(Bridge bridge, CitaService citas) {
        bridge.register("listCitas", payload -> {
            Map<String, Object> body = Payload.object(payload);
            Map<String, Object> f = Payload.optObject(body, "filter");
            CitaFilter filter = new CitaFilter(Payload.optString(f, "texto"), Payload.optDate(f, "fecha"),
                estado(Payload.optString(f, "estado")), Payload.optLong(f, "idMedico"),
                rango(Payload.optString(f, "rango")));
            return Dtos.toMap(citas.list(filter, CrudMethods.page(body), CrudMethods.pageSize(body)),
                view -> toMap(view, citas.zone()));
        });
        bridge.register("getCita", payload ->
            toMap(citas.get(Payload.requireLong(Payload.object(payload), "id")), citas.zone()));
        bridge.register("saveCita", payload -> {
            Map<String, Object> body = Payload.object(payload);
            Long id = Payload.optLong(body, "id");
            CitaInput input = new CitaInput(Payload.optLong(body, "idMedico"), Payload.optLong(body, "idPaciente"),
                Payload.optString(body, "fecha"), Payload.optString(body, "hora"), Payload.optString(body, "motivo"));
            CitaView saved = id == null || id == 0 ? citas.create(input) : citas.reschedule(id, input);
            return toMap(saved, citas.zone());
        });
        bridge.register("cancelarCita", payload ->
            toMap(citas.cancel(Payload.requireLong(Payload.object(payload), "id")), citas.zone()));
        bridge.register("agendaMedico", payload -> {
            Map<String, Object> body = Payload.object(payload);
            Agenda agenda = citas.agenda(Payload.optLong(body, "idMedico"), Payload.optDate(body, "fecha"));
            return toMap(agenda, citas.zone());
        });
        bridge.register("listMedicosSelect", payload -> {
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
        });
        bridge.register("listPacientesSelect", payload -> {
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
        });
    }

    static EstadoCita estado(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return EstadoCita.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Unknown estado: " + raw);
        }
    }

    static CitaRango rango(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return CitaRango.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Unknown rango: " + raw);
        }
    }

    public static Map<String, Object> toMap(CitaView view, AppZone zone) {
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

    static Map<String, Object> toMap(Agenda agenda, AppZone zone) {
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
