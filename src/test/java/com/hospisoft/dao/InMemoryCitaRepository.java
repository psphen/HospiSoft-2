package com.hospisoft.dao;

import com.hospisoft.model.Cita;
import com.hospisoft.model.CitaView;
import com.hospisoft.model.EstadoCita;
import com.hospisoft.model.Medico;
import com.hospisoft.model.Paciente;
import com.hospisoft.model.Page;
import com.hospisoft.service.CitaService;
import com.hospisoft.service.ConflictException;
import com.hospisoft.service.NotFoundException;
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
 * In-memory fake of {@link CitaRepository}. Joins names from the medico/paciente fakes; the
 * transaction scope applies operations directly (no rollback needed: checks run before writes).
 */
public final class InMemoryCitaRepository implements CitaRepository {

    private final Map<Long, Cita> rows = new LinkedHashMap<>();
    private final MedicoRepository medicos;
    private final PacienteRepository pacientes;
    private long nextId = 1;
    /** Lock calls in order, as "medico:ID" / "paciente:ID". */
    public final List<String> locks = new ArrayList<>();
    /** Optional lock results (key "medico:ID" / "paciente:ID") simulating a row changed by another session. */
    public final Map<String, Optional<Boolean>> lockOverrides = new java.util.HashMap<>();

    public InMemoryCitaRepository(MedicoRepository medicos, PacienteRepository pacientes) {
        this.medicos = medicos;
        this.pacientes = pacientes;
    }

    public Cita add(Cita cita) {
        long id = cita.id() == null ? nextId++ : cita.id();
        nextId = Math.max(nextId, id + 1);
        Cita stored = new Cita(id, cita.idMedico(), cita.idPaciente(), cita.fechaHoraProgramada(), cita.motivo(),
            cita.estado(), cita.horaInicioReal(), cita.horaFinReal(), cita.observaciones());
        rows.put(id, stored);
        return stored;
    }

    public Cita raw(long id) {
        return rows.get(id);
    }

    /** Every row, for transaction snapshots in tests. */
    public List<Cita> snapshot() {
        return new ArrayList<>(rows.values());
    }

    /** Restores a snapshot (rollback simulation). */
    public void restore(List<Cita> snapshot) {
        rows.clear();
        snapshot.forEach(c -> rows.put(c.id(), c));
    }

    /** Overwrites a row without any state guard. */
    public void replace(Cita cita) {
        rows.put(cita.id(), cita);
    }

    private CitaView view(Cita c) {
        Medico m = medicos.findById(c.idMedico()).orElseThrow();
        Paciente p = pacientes.findById(c.idPaciente()).orElseThrow();
        return new CitaView(c, m.nombres(), m.apellidos(), m.especialidad(), p.nombres(), p.apellidos(), p.documento());
    }

    private static boolean overlaps(Cita c, OffsetDateTime start, OffsetDateTime end) {
        OffsetDateTime cEnd = c.fechaHoraProgramada().plusMinutes(CitaService.SLOT_MINUTES);
        return c.fechaHoraProgramada().isBefore(end) && cEnd.isAfter(start);
    }

    @Override
    public <T> T inTransaction(Function<CitaTx, T> work) {
        return work.apply(new CitaTx() {
            @Override
            public Optional<Boolean> lockMedico(long idMedico) {
                locks.add("medico:" + idMedico);
                if (lockOverrides.containsKey("medico:" + idMedico)) {
                    return lockOverrides.get("medico:" + idMedico);
                }
                return medicos.findById(idMedico).map(Medico::activo);
            }

            @Override
            public Optional<Boolean> lockPaciente(long idPaciente) {
                locks.add("paciente:" + idPaciente);
                if (lockOverrides.containsKey("paciente:" + idPaciente)) {
                    return lockOverrides.get("paciente:" + idPaciente);
                }
                return pacientes.findById(idPaciente).map(Paciente::activo);
            }

            @Override
            public boolean existsOverlapForMedico(long idMedico, OffsetDateTime start, OffsetDateTime end, Long excludeId) {
                return rows.values().stream().anyMatch(c -> c.idMedico() == idMedico
                    && c.estado() != EstadoCita.CANCELADA && !c.id().equals(excludeId) && overlaps(c, start, end));
            }

            @Override
            public boolean existsOverlapForPaciente(long idPaciente, OffsetDateTime start, OffsetDateTime end, Long excludeId) {
                return rows.values().stream().anyMatch(c -> c.idPaciente() == idPaciente
                    && c.estado() != EstadoCita.CANCELADA && !c.id().equals(excludeId) && overlaps(c, start, end));
            }

            @Override
            public long insert(Cita cita) {
                return add(new Cita(null, cita.idMedico(), cita.idPaciente(), cita.fechaHoraProgramada(), cita.motivo(),
                    EstadoCita.PROGRAMADA, null, null, null)).id();
            }

            @Override
            public void reschedule(long id, long idMedico, long idPaciente, OffsetDateTime fechaHora, String motivo) {
                Cita c = rows.get(id);
                if (c == null) {
                    throw new NotFoundException("Cita " + id + " not found");
                }
                if (c.estado() != EstadoCita.PROGRAMADA) {
                    throw new ConflictException("Cita " + id + " state is not PROGRAMADA");
                }
                rows.put(id, new Cita(id, idMedico, idPaciente, fechaHora, motivo, c.estado(), c.horaInicioReal(),
                    c.horaFinReal(), c.observaciones()));
            }
        });
    }

    @Override
    public Page<CitaView> findPage(CitaQuery q, int page, int pageSize) {
        List<CitaView> matching = rows.values().stream()
            .filter(c -> q.estado() == null || q.estado() == c.estado())
            .filter(c -> q.idMedico() == null || java.util.Objects.equals(q.idMedico(), c.idMedico()))
            .filter(c -> q.idPaciente() == null || java.util.Objects.equals(q.idPaciente(), c.idPaciente()))
            .filter(c -> q.dayStart() == null || !c.fechaHoraProgramada().isBefore(q.dayStart()))
            .filter(c -> q.dayEnd() == null || c.fechaHoraProgramada().isBefore(q.dayEnd()))
            .filter(c -> q.from() == null || !c.fechaHoraProgramada().isBefore(q.from()))
            .filter(c -> q.to() == null || c.fechaHoraProgramada().isBefore(q.to()))
            .sorted(Comparator.comparing(Cita::fechaHoraProgramada).thenComparing(Cita::id))
            .map(this::view)
            .filter(v -> q.texto() == null || matchesText(v, q.texto()))
            .collect(Collectors.toList());
        int from = (int) Math.min(Filters.offset(page, pageSize), matching.size());
        int to = Math.min(from + pageSize, matching.size());
        return new Page<>(new ArrayList<>(matching.subList(from, to)), matching.size(), page, pageSize);
    }

    private static boolean matchesText(CitaView v, String texto) {
        String needle = texto.toLowerCase(Locale.ROOT);
        return v.pacienteNombre().toLowerCase(Locale.ROOT).contains(needle)
            || v.medicoNombre().toLowerCase(Locale.ROOT).contains(needle)
            || v.pacienteDocumento().toLowerCase(Locale.ROOT).contains(needle);
    }

    @Override
    public Optional<CitaView> findById(long id) {
        return Optional.ofNullable(rows.get(id)).map(this::view);
    }

    @Override
    public void cancel(long id) {
        Cita c = rows.get(id);
        if (c == null) {
            throw new NotFoundException("Cita " + id + " not found");
        }
        if (c.estado() != EstadoCita.PROGRAMADA) {
            throw new ConflictException("Cita " + id + " state is not PROGRAMADA");
        }
        rows.put(id, new Cita(id, c.idMedico(), c.idPaciente(), c.fechaHoraProgramada(), c.motivo(),
            EstadoCita.CANCELADA, c.horaInicioReal(), c.horaFinReal(), c.observaciones()));
    }

    @Override
    public List<CitaView> findByMedicoAndDay(long idMedico, OffsetDateTime dayStart, OffsetDateTime dayEnd) {
        return rows.values().stream()
            .filter(c -> c.idMedico() == idMedico)
            .filter(c -> !c.fechaHoraProgramada().isBefore(dayStart) && c.fechaHoraProgramada().isBefore(dayEnd))
            .sorted(Comparator.comparing(Cita::fechaHoraProgramada).thenComparing(Cita::id))
            .map(this::view)
            .collect(Collectors.toList());
    }
}
