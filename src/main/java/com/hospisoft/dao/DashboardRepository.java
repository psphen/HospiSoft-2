package com.hospisoft.dao;

import com.hospisoft.model.CitaView;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Read-only aggregates for the dashboard. One {@link #snapshot} call answers the whole panel from a
 * single connection; which extra counts it computes depends on the {@link Scope}, never on a role
 * (the role lives in the service).
 */
public interface DashboardRepository {

    /** Whose data to count: nobody set = the whole hospital (administration). */
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

    /** Citas of the day grouped by state; the four states add up to the total. */
    record EstadoCounts(long total, long atendidas, long enCurso, long pendientes, long canceladas) {

        public static final EstadoCounts ZERO = new EstadoCounts(0, 0, 0, 0, 0);

        /** Citas that still count as real work today: everything except the cancelled ones. */
        public long noCanceladas() {
            return total - canceladas;
        }
    }

    /**
     * Everything the dashboard needs. Fields outside the scope stay at 0: the global counts are
     * only computed for {@link Scope#ALL}, {@code pacientesAtendidos} only for a medico scope and
     * the patient totals only for a paciente scope.
     *
     * @param duracionPromedioMin average minutes of today's finished citas, null when there are none
     */
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

    /**
     * @param dayStart inclusive start of the local day
     * @param dayEnd   exclusive end of the local day
     * @param now      lower bound of "upcoming"
     * @param limit    how many upcoming citas to return
     */
    DashboardSnapshot snapshot(Scope scope, OffsetDateTime dayStart, OffsetDateTime dayEnd, OffsetDateTime now,
                               int limit);
}
