package com.hospisoft.bridge;

import com.hospisoft.model.CitaView;
import com.hospisoft.service.DashboardService;
import com.hospisoft.service.DashboardView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registers the dashboard method (phase 6). One call answers the whole panel:
 * {@code dashboard {}} ->
 * {@code {"rol","kpis":[{"clave","etiqueta","valor","detalle"}],"proximasCitas":[citaDto],
 * "resumenHoy":{"total","atendidas","enCurso","pendientes","canceladas"},"atendidasPct":number|null,
 * "duracionPromedioMin":number|null}}. Values are already scoped to the session's role; the page
 * only renders what the payload carries (labels included).
 */
public final class DashboardMethods {

    private DashboardMethods() {
    }

    public static void register(Bridge bridge, DashboardService dashboard) {
        bridge.register("dashboard", payload -> toMap(dashboard.load(), dashboard.zone()));
    }

    /** Slim projection: only what the panel paints, never the clinical detail of the cita. */
    static Map<String, Object> proximaToMap(CitaView view, com.hospisoft.service.AppZone zone) {
        var cita = view.cita();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", cita.id());
        map.put("medicoNombre", view.medicoNombre());
        map.put("pacienteNombre", view.pacienteNombre());
        map.put("especialidad", view.especialidad());
        map.put("fecha", zone.dateOf(cita.fechaHoraProgramada()).toString());
        map.put("hora", zone.timeOf(cita.fechaHoraProgramada()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")));
        map.put("estado", cita.estado());
        return map;
    }

    static Map<String, Object> toMap(DashboardView view, com.hospisoft.service.AppZone zone) {
        List<Map<String, Object>> kpis = new ArrayList<>(view.kpis().size());
        for (DashboardView.Kpi kpi : view.kpis()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("clave", kpi.clave());
            entry.put("etiqueta", kpi.etiqueta());
            entry.put("valor", kpi.valor());
            entry.put("detalle", kpi.detalle());
            kpis.add(entry);
        }
        List<Map<String, Object>> proximas = new ArrayList<>(view.proximasCitas().size());
        for (CitaView cita : view.proximasCitas()) {
            proximas.add(proximaToMap(cita, zone));
        }
        Map<String, Object> resumen = new LinkedHashMap<>();
        resumen.put("total", view.resumenHoy().total());
        resumen.put("atendidas", view.resumenHoy().atendidas());
        resumen.put("enCurso", view.resumenHoy().enCurso());
        resumen.put("pendientes", view.resumenHoy().pendientes());
        resumen.put("canceladas", view.resumenHoy().canceladas());

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("rol", view.rol());
        map.put("kpis", kpis);
        map.put("proximasCitas", proximas);
        map.put("resumenHoy", resumen);
        map.put("atendidasPct", view.atendidasPct());
        map.put("duracionPromedioMin", view.duracionPromedioMin());
        return map;
    }
}
