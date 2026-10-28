package com.hospisoft.dao;

import com.hospisoft.model.Cita;
import com.hospisoft.model.CitaView;
import com.hospisoft.model.EstadoCita;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** In-memory fake computing the same aggregates from the other in-memory repositories. */
public final class InMemoryDashboardRepository implements DashboardRepository {

    private final InMemoryCitaRepository citas;
    private final InMemoryMedicoRepository medicos;
    private final InMemoryPacienteRepository pacientes;
    private final InMemoryMedicamentoRepository medicamentos;
    private final InMemoryAtencionRepository atenciones;

    public InMemoryDashboardRepository(InMemoryCitaRepository citas, InMemoryMedicoRepository medicos,
                                       InMemoryPacienteRepository pacientes,
                                       InMemoryMedicamentoRepository medicamentos,
                                       InMemoryAtencionRepository atenciones) {
        this.citas = citas;
        this.medicos = medicos;
        this.pacientes = pacientes;
        this.medicamentos = medicamentos;
        this.atenciones = atenciones;
    }

    private boolean inScope(Cita cita, Scope scope) {
        return (scope.idMedico() == null || Objects.equals(scope.idMedico(), cita.idMedico()))
            && (scope.idPaciente() == null || Objects.equals(scope.idPaciente(), cita.idPaciente()));
    }

    private List<Cita> scoped(Scope scope) {
        return citas.snapshot().stream().filter(c -> inScope(c, scope)).collect(Collectors.toList());
    }

    @Override
    public DashboardSnapshot snapshot(Scope scope, OffsetDateTime dayStart, OffsetDateTime dayEnd,
                                      OffsetDateTime now, int limit) {
        Scope safe = scope == null ? Scope.ALL : scope;
        List<Cita> mine = scoped(safe);
        List<Cita> ofDay = mine.stream()
            .filter(c -> !c.fechaHoraProgramada().isBefore(dayStart) && c.fechaHoraProgramada().isBefore(dayEnd))
            .collect(Collectors.toList());

        long atendidas = ofDay.stream().filter(c -> c.estado() == EstadoCita.FINALIZADA).count();
        long enCurso = ofDay.stream().filter(c -> c.estado() == EstadoCita.EN_CURSO).count();
        long pendientes = ofDay.stream().filter(c -> c.estado() == EstadoCita.PROGRAMADA).count();
        long canceladas = ofDay.stream().filter(c -> c.estado() == EstadoCita.CANCELADA).count();
        EstadoCounts resumen = new EstadoCounts(ofDay.size(), atendidas, enCurso, pendientes, canceladas);

        List<Cita> finished = ofDay.stream()
            .filter(c -> c.estado() == EstadoCita.FINALIZADA && c.horaInicioReal() != null && c.horaFinReal() != null)
            .collect(Collectors.toList());
        Double duracion = finished.isEmpty() ? null : finished.stream()
            .mapToDouble(c -> Duration.between(c.horaInicioReal(), c.horaFinReal()).toSeconds() / 60.0)
            .average().orElseThrow();

        List<CitaView> proximas = mine.stream()
            .filter(c -> c.estado() == EstadoCita.PROGRAMADA && !c.fechaHoraProgramada().isBefore(now))
            .sorted(Comparator.comparing(Cita::fechaHoraProgramada).thenComparing(Cita::id))
            .limit(DashboardDao.safeLimit(limit))
            .map(c -> citas.findById(c.id()).orElseThrow())
            .collect(Collectors.toList());

        long citasPendientes = mine.stream()
            .filter(c -> c.estado() == EstadoCita.PROGRAMADA && !c.fechaHoraProgramada().isBefore(now))
            .count();

        long medicosActivos = 0;
        long pacientesActivos = 0;
        long medicamentosActivos = 0;
        if (safe.isAll()) {
            medicosActivos = medicos.findActiveForSelect().size();
            pacientesActivos = pacientes.findActiveForSelect().size();
            medicamentosActivos = medicamentos.findAllActive().size();
        }
        long pacientesAtendidos = 0;
        if (safe.idMedico() != null) {
            pacientesAtendidos = mine.stream().filter(c -> c.estado() != EstadoCita.CANCELADA)
                .map(Cita::idPaciente).distinct().count();
        }
        long citasTotales = 0;
        long recetasTotales = 0;
        long historiasTotales = 0;
        if (safe.idPaciente() != null) {
            long id = safe.idPaciente();
            citasTotales = mine.size();
            recetasTotales = atenciones.findRecetasPage(id, 1, Integer.MAX_VALUE).total();
            historiasTotales = atenciones.findHistoriasPage(new HistoriaQuery(id, null, null), 1, Integer.MAX_VALUE)
                .total();
        }
        return new DashboardSnapshot(ofDay.size(), citasPendientes, resumen, duracion, new ArrayList<>(proximas),
            medicosActivos, pacientesActivos, medicamentosActivos, pacientesAtendidos,
            citasTotales, recetasTotales, historiasTotales);
    }
}
