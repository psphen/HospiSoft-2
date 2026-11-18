package com.hospisoft.dao;

import com.hospisoft.model.CitaView;
import java.time.OffsetDateTime;
import java.util.List;

public interface DashboardRepository {
    record Scope(Long idMedico, Long idPaciente) {

        public Scope {
            if (idMedico != null && idPaciente != null) {
                throw new IllegalArgumentException("A dashboard scope is either a medico or a paciente, never both");
            }
        }

        public static final Scope ALL = new Scope(null, null);

        public static Scope medico(long idMedico) {
            return new Scope(idMedico, null);
        }

        public static Scope paciente(long idPaciente) {
            return new Scope(null, idPaciente);
        }

        public boolean isAll() {
            return idMedico == null && idPaciente == null;
        }
    }

    record EstadoCounts(long total, long atendidas, long enCurso, long pendientes, long canceladas) {
        public long noCanceladas() {
            return total - canceladas;
        }
    }

    record DashboardSnapshot(
        long citasHoy,
        long citasPendientes,
        EstadoCounts resumenHoy,
        Double duracionPromedioMin,
        List<CitaView> proximasCitas,
        long medicosActivos,
        long pacientesActivos,
        long medicamentosActivos,
        long pacientesAtendidos,
        long citasTotales,
        long recetasTotales,
        long historiasTotales
    ) {
        public DashboardSnapshot {
            proximasCitas = List.copyOf(proximasCitas);
        }
    }

    DashboardSnapshot snapshot(Scope scope, OffsetDateTime dayStart, OffsetDateTime dayEnd, OffsetDateTime now,
                               int limit);
}
