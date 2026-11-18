package com.hospisoft.service;

import com.hospisoft.dao.DashboardRepository.EstadoCounts;
import com.hospisoft.model.CitaView;
import com.hospisoft.model.Rol;
import java.util.List;

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

    public record Kpi(String clave, String etiqueta, long valor, String detalle) {
    }
}
