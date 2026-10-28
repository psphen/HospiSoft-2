package com.hospisoft.service;

import com.hospisoft.dao.DashboardRepository.EstadoCounts;
import com.hospisoft.model.CitaView;
import com.hospisoft.model.Rol;
import java.util.List;

/**
 * Everything the dashboard shows, already scoped to the session's role.
 *
 * @param kpis                 the four cards, in display order
 * @param atendidasPct         share of today's citas already finished, null when there are none
 * @param duracionPromedioMin  average minutes of today's finished citas, null when there are none
 */
public record DashboardView(
    Rol rol,
    List<Kpi> kpis,
    List<CitaView> proximasCitas,
    EstadoCounts resumenHoy,
    Double atendidasPct,
    Double duracionPromedioMin
) {
    public DashboardView {
        kpis = List.copyOf(kpis);
        proximasCitas = List.copyOf(proximasCitas);
    }

    /**
     * One KPI card.
     *
     * @param clave    stable identifier for the UI (the card position it fills)
     * @param etiqueta Spanish label shown under the number
     * @param detalle  small line under the label; a fact, never an invented trend
     */
    public record Kpi(String clave, String etiqueta, long valor, String detalle) {
    }
}
