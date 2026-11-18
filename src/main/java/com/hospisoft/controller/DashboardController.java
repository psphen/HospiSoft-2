package com.hospisoft.controller;

import com.hospisoft.bridge.Bridge;
import com.hospisoft.model.CitaView;
import com.hospisoft.service.AppZone;
import com.hospisoft.service.DashboardService;
import com.hospisoft.service.DashboardView;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MVC controller of the dashboard: receives the View's request from the JS bridge, delegates to
 * {@link DashboardService} (the Model) and returns the DTO. One call answers the whole panel:
 * {@code dashboard {}} ->
 * {@code {"rol","kpis":[{"clave","etiqueta","valor","detalle"}],"proximasCitas":[citaDto],
 * "resumenHoy":{"total","atendidas","enCurso","pendientes","canceladas"},"atendidasPct":number|null,
 * "duracionPromedioMin":number|null}}. Values are already scoped to the session's role; the page
 * only renders what the payload carries (labels included).
 */
public final class DashboardController implements Controller {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final DashboardService dashboard;

    public DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @Override
    public void register(Bridge bridge) {
        bridge.register("dashboard", this::load);
    }

    /** The payload is ignored. */
    private Object load(String payload) {
        return toMap(dashboard.load(), dashboard.zone());
    }

    /** Slim projection: only what the panel paints, never the clinical detail of the cita. */
    private static Map<String, Object> proximaToMap(CitaView view, AppZone zone) {
        var cita = view.cita();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", cita.id());
        map.put("medicoNombre", view.medicoNombre());
        map.put("pacienteNombre", view.pacienteNombre());
        map.put("especialidad", view.especialidad());
        map.put("fecha", zone.dateOf(cita.fechaHoraProgramada()).toString());
        map.put("hora", zone.timeOf(cita.fechaHoraProgramada()).format(TIME));
        map.put("estado", cita.estado());
        return map;
    }

    private static Map<String, Object> toMap(DashboardView view, AppZone zone) {
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
