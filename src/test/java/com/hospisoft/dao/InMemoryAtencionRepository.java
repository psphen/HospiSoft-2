package com.hospisoft.dao;

import com.hospisoft.model.Cita;
import com.hospisoft.model.EstadoCita;
import com.hospisoft.model.HistoriaClinica;
import com.hospisoft.model.HistoriaView;
import com.hospisoft.model.Medicamento;
import com.hospisoft.model.Medico;
import com.hospisoft.model.Page;
import com.hospisoft.model.Paciente;
import com.hospisoft.model.Receta;
import com.hospisoft.model.RecetaView;
import com.hospisoft.service.ConflictException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * In-memory fake of {@link AtencionRepository} backed by the other in-memory repositories.
 * Writes apply directly; {@link #failOnRecetaInsert} simulates a failure late in the transaction
 * so tests can assert that nothing was persisted (the fake undoes its own writes).
 */
public final class InMemoryAtencionRepository implements AtencionRepository {

    private final InMemoryCitaRepository citas;
    private final InMemoryMedicoRepository medicos;
    private final InMemoryPacienteRepository pacientes;
    private final InMemoryMedicamentoRepository medicamentos;

    private final Map<Long, HistoriaClinica> historias = new LinkedHashMap<>();
    private final Map<Long, Receta> recetas = new LinkedHashMap<>();
    private long nextHistoriaId = 1;
    private long nextRecetaId = 1;

    /** Lock calls in order, as "medico:ID" / "medicamento:ID". */
    public final List<String> locks = new ArrayList<>();
    /** When set, the insert of the receta at this index (0-based) throws. */
    public Integer failOnRecetaInsert;
    /** When true, every decrementStock reports 0 affected rows (simulates a broken invariant). */
    public boolean failDecrementStock;
    private int recetaInsertCount;

    public InMemoryAtencionRepository(InMemoryCitaRepository citas, InMemoryMedicoRepository medicos,
                                      InMemoryPacienteRepository pacientes,
                                      InMemoryMedicamentoRepository medicamentos) {
        this.citas = citas;
        this.medicos = medicos;
        this.pacientes = pacientes;
        this.medicamentos = medicamentos;
    }

    public int historiaCount() {
        return historias.size();
    }

    public int recetaCount() {
        return recetas.size();
    }

    public HistoriaClinica historiaOfCita(long idCita) {
        return historias.values().stream().filter(h -> h.idCita() == idCita).findFirst().orElse(null);
    }

    public List<Receta> recetasOf(long idHistoria) {
        return recetas.values().stream().filter(r -> r.idHistoria() == idHistoria).toList();
    }

    @Override
    public <T> T inTransaction(Function<AtencionTx, T> work) {
        // snapshot for the rollback simulation
        Map<Long, HistoriaClinica> historiasBefore = new LinkedHashMap<>(historias);
        Map<Long, Receta> recetasBefore = new LinkedHashMap<>(recetas);
        List<Medicamento> medicamentosBefore = medicamentos.allRows();
        List<Cita> citasBefore = citas.snapshot();
        recetaInsertCount = 0;
        try {
            return work.apply(new Tx());
        } catch (RuntimeException | Error ex) {
            historias.clear();
            historias.putAll(historiasBefore);
            recetas.clear();
            recetas.putAll(recetasBefore);
            medicamentosBefore.forEach(medicamentos::replace);
            citas.restore(citasBefore);
            throw ex;
        }
    }

    private final class Tx implements AtencionTx {

        @Override
        public Optional<Boolean> lockMedico(long idMedico) {
            locks.add("medico:" + idMedico);
            return medicos.findById(idMedico).map(Medico::activo);
        }

        @Override
        public Optional<MedicamentoLock> lockMedicamento(long idMedicamento) {
            locks.add("medicamento:" + idMedicamento);
            return medicamentos.findById(idMedicamento)
                .map(m -> new MedicamentoLock(idMedicamento, m.stock(), m.stockMinimo(), m.activo()));
        }

        @Override
        public boolean existsEnCurso(long idMedico, Long excludeCitaId) {
            return citas.snapshot().stream().anyMatch(c -> c.idMedico() == idMedico
                && c.estado() == EstadoCita.EN_CURSO && !c.id().equals(excludeCitaId));
        }

        @Override
        public Optional<EstadoCita> estadoOfOwnCita(long idCita, long idMedico) {
            Cita cita = citas.raw(idCita);
            return cita != null && cita.idMedico() == idMedico ? Optional.of(cita.estado()) : Optional.empty();
        }

        @Override
        public int iniciar(long idCita, long idMedico) {
            Cita cita = citas.raw(idCita);
            if (cita == null || cita.idMedico() != idMedico || cita.estado() != EstadoCita.PROGRAMADA) {
                return 0;
            }
            citas.replace(new Cita(cita.id(), cita.idMedico(), cita.idPaciente(), cita.fechaHoraProgramada(),
                cita.motivo(), EstadoCita.EN_CURSO, OffsetDateTime.now(), null, cita.observaciones()));
            return 1;
        }

        @Override
        public int finalizar(long idCita, long idMedico, String observaciones) {
            Cita cita = citas.raw(idCita);
            if (cita == null || cita.idMedico() != idMedico || cita.estado() != EstadoCita.EN_CURSO) {
                return 0;
            }
            citas.replace(new Cita(cita.id(), cita.idMedico(), cita.idPaciente(), cita.fechaHoraProgramada(),
                cita.motivo(), EstadoCita.FINALIZADA, cita.horaInicioReal(), OffsetDateTime.now(), observaciones));
            return 1;
        }

        @Override
        public long insertHistoria(long idCita, long idPaciente, long idMedico, String diagnostico,
                                   String tratamiento, String notas) {
            if (historiaOfCita(idCita) != null) {
                throw new ConflictException("duplicate historia", AtencionDao.ALREADY_CLOSED);
            }
            long id = nextHistoriaId++;
            historias.put(id, new HistoriaClinica(id, idCita, idPaciente, idMedico, OffsetDateTime.now(),
                diagnostico, tratamiento, notas));
            return id;
        }

        @Override
        public long insertReceta(long idHistoria, long idMedicamento, int cantidad, String dosis, String duracion,
                                 boolean entregado, String observacionNoDisponible) {
            if (failOnRecetaInsert != null && recetaInsertCount == failOnRecetaInsert) {
                throw new IllegalStateException("simulated failure on receta insert");
            }
            recetaInsertCount++;
            long id = nextRecetaId++;
            recetas.put(id, new Receta(id, idHistoria, idMedicamento, cantidad, dosis, duracion, entregado,
                observacionNoDisponible));
            return id;
        }

        @Override
        public int decrementStock(long idMedicamento, int cantidad) {
            if (failDecrementStock) {
                return 0;
            }
            Medicamento m = medicamentos.findById(idMedicamento).orElse(null);
            if (m == null || m.stock() < cantidad) {
                return 0;
            }
            medicamentos.replace(new Medicamento(m.id(), m.codigo(), m.nombre(), m.descripcion(), m.presentacion(),
                m.stock() - cantidad, m.stockMinimo(), m.activo()));
            return 1;
        }
    }

    // ------------------------------------------------------------ reads

    private HistoriaView view(HistoriaClinica h, boolean withRecetas) {
        Medico m = medicos.findById(h.idMedico()).orElseThrow();
        Paciente p = pacientes.findById(h.idPaciente()).orElseThrow();
        List<RecetaView> lines = withRecetas ? findRecetasByHistoria(h.id()) : List.of();
        return new HistoriaView(h, m.nombres(), m.apellidos(), m.especialidad(), p.nombres(), p.apellidos(),
            p.documento(), lines);
    }

    private RecetaView view(Receta r, OffsetDateTime fecha) {
        Medicamento m = medicamentos.findById(r.idMedicamento()).orElseThrow();
        Receta withFecha = new Receta(r.id(), r.idHistoria(), r.idMedicamento(), r.cantidad(), r.dosis(),
            r.duracion(), r.entregado(), r.observacionNoDisponible(), fecha);
        return new RecetaView(withFecha, m.codigo(), m.nombre(), m.presentacion());
    }

    @Override
    public Optional<HistoriaView> findHistoriaByCita(long idCita) {
        return Optional.ofNullable(historiaOfCita(idCita)).map(h -> view(h, true));
    }

    @Override
    public Optional<HistoriaView> findHistoriaById(long idHistoria) {
        return Optional.ofNullable(historias.get(idHistoria)).map(h -> view(h, true));
    }

    @Override
    public Page<HistoriaView> findHistoriasPage(HistoriaQuery query, int page, int pageSize) {
        List<HistoriaView> matching = historias.values().stream()
            .filter(h -> query.idPaciente() == null || java.util.Objects.equals(query.idPaciente(), h.idPaciente()))
            .filter(h -> query.idMedico() == null || java.util.Objects.equals(query.idMedico(), h.idMedico()))
            .sorted(Comparator.comparing(HistoriaClinica::fecha).thenComparing(HistoriaClinica::id).reversed())
            .map(h -> view(h, false))
            .filter(v -> query.texto() == null || matches(v, query.texto()))
            .collect(Collectors.toList());
        int from = (int) Math.min(Filters.offset(page, pageSize), matching.size());
        int to = Math.min(from + pageSize, matching.size());
        return new Page<>(new ArrayList<>(matching.subList(from, to)), matching.size(), page, pageSize);
    }

    private static boolean matches(HistoriaView v, String texto) {
        String needle = texto.toLowerCase(Locale.ROOT);
        return v.historia().diagnostico().toLowerCase(Locale.ROOT).contains(needle)
            || v.medicoNombre().toLowerCase(Locale.ROOT).contains(needle);
    }

    @Override
    public List<RecetaView> findRecetasByHistoria(long idHistoria) {
        return recetas.values().stream().filter(r -> r.idHistoria() == idHistoria)
            .sorted(Comparator.comparing(Receta::id))
            .map(r -> view(r, null))
            .collect(Collectors.toList());
    }

    @Override
    public Page<RecetaView> findRecetasPage(long idPaciente, int page, int pageSize) {
        List<RecetaView> matching = recetas.values().stream()
            .filter(r -> {
                HistoriaClinica h = historias.get(r.idHistoria());
                return h != null && h.idPaciente() == idPaciente;
            })
            .sorted(Comparator.<Receta, OffsetDateTime>comparing(r -> historias.get(r.idHistoria()).fecha()).reversed()
                .thenComparing(r -> historias.get(r.idHistoria()).id(), Comparator.reverseOrder())
                .thenComparing(Receta::id))
            .map(r -> view(r, historias.get(r.idHistoria()).fecha()))
            .collect(Collectors.toList());
        int from = (int) Math.min(Filters.offset(page, pageSize), matching.size());
        int to = Math.min(from + pageSize, matching.size());
        return new Page<>(new ArrayList<>(matching.subList(from, to)), matching.size(), page, pageSize);
    }
}
