package com.hospisoft.service;

import com.hospisoft.dao.DashboardRepository;
import com.hospisoft.dao.DashboardRepository.DashboardSnapshot;
import com.hospisoft.dao.DashboardRepository.Scope;
import com.hospisoft.dao.MedicamentoRepository;
import com.hospisoft.model.Rol;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Builds the dashboard for the current session. Every number is scoped by role: ADMIN sees the
 * whole hospital, a MEDICO only its own citas and patients, a PACIENTE only its own records.
 * "Hoy" is the local day of {@link AppZone}, with the bounds computed here and passed as parameters.
 */
public final class DashboardService {

    /** How many upcoming citas the panel lists. */
    public static final int PROXIMAS_LIMIT = 5;

    private final DashboardRepository dashboard;
    private final MedicamentoRepository medicamentos;
    private final Access access;
    private final Supplier<Optional<SessionContext>> session;
    private final Clock clock;
    private final AppZone zone;

    public DashboardService(DashboardRepository dashboard, MedicamentoRepository medicamentos,
                            Supplier<Optional<SessionContext>> session, Clock clock, AppZone zone) {
        this.dashboard = dashboard;
        this.medicamentos = medicamentos;
        this.access = new Access(session);
        this.session = session;
        this.clock = clock;
        this.zone = zone;
    }

    public AppZone zone() {
        return zone;
    }

    public DashboardView load() {
        SessionContext current = access.require(Rol.ADMIN, Rol.MEDICO, Rol.PACIENTE);
        LocalDate today = LocalDate.now(clock.withZone(zone.zone()));
        OffsetDateTime dayStart = zone.dayStart(today);
        OffsetDateTime dayEnd = zone.dayEnd(today);
        OffsetDateTime now = OffsetDateTime.now(clock);

        Scope scope = switch (current.rol()) {
            case ADMIN -> Scope.ALL;
            case MEDICO -> Scope.medico(referenceOf(current));
            case PACIENTE -> Scope.paciente(referenceOf(current));
        };
        DashboardSnapshot snapshot = dashboard.snapshot(scope, dayStart, dayEnd, now, PROXIMAS_LIMIT);
        List<DashboardView.Kpi> kpis = switch (current.rol()) {
            case ADMIN -> List.of(
                new DashboardView.Kpi("medicos", "Médicos", snapshot.medicosActivos(), "activos"),
                new DashboardView.Kpi("pacientes", "Pacientes", snapshot.pacientesActivos(), "activos"),
                new DashboardView.Kpi("citas", "Citas", snapshot.citasHoy(), "hoy"),
                new DashboardView.Kpi("medicamentos", "Medicamentos", snapshot.medicamentosActivos(), "activos"));
            case MEDICO -> List.of(
                new DashboardView.Kpi("medicos", "Mis pacientes", snapshot.pacientesAtendidos(), "con citas"),
                new DashboardView.Kpi("pacientes", "Citas de hoy", snapshot.citasHoy(), "hoy"),
                new DashboardView.Kpi("citas", "Citas pendientes", snapshot.citasPendientes(), "programadas"),
                new DashboardView.Kpi("medicamentos", "Medicamentos", medicamentos.countLowStock(),
                    "en o bajo el mínimo"));
            case PACIENTE -> List.of(
                new DashboardView.Kpi("medicos", "Mis citas", snapshot.citasTotales(), "en total"),
                new DashboardView.Kpi("pacientes", "Citas próximas", snapshot.citasPendientes(), "programadas"),
                new DashboardView.Kpi("citas", "Mis órdenes", snapshot.recetasTotales(), "medicamentos"),
                new DashboardView.Kpi("medicamentos", "Historias", snapshot.historiasTotales(), "clínicas"));
        };
        // the share is over the citas that still counted as work today: cancelled ones are excluded
        long base = snapshot.resumenHoy().noCanceladas();
        Double atendidasPct = base == 0 ? null : (100.0 * snapshot.resumenHoy().atendidas()) / base;
        return new DashboardView(current.rol(), kpis, snapshot.proximasCitas(), snapshot.resumenHoy(),
            atendidasPct, snapshot.duracionPromedioMin());
    }

    private static long referenceOf(SessionContext session) {
        if (session.idReferencia() == null) {
            throw new PermissionException(session.rol() + " session without a linked row");
        }
        return session.idReferencia();
    }
}
