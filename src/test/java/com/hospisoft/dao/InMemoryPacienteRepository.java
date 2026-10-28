package com.hospisoft.dao;

import com.hospisoft.model.Paciente;
import com.hospisoft.model.Page;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * In-memory fake mirroring the DAO semantics (filters, ordering, paging, medico scoping).
 * Scope rule mirrored from the DAO: only non-cancelled citas count as "attended".
 */
public final class InMemoryPacienteRepository implements PacienteRepository {

    private final Map<Long, Paciente> rows = new LinkedHashMap<>();
    private final Set<Long> withHistory = new HashSet<>();
    /** paciente id -> medico ids with at least one non-cancelled cita. */
    private final Map<Long, Set<Long>> attendedBy = new HashMap<>();
    private final Set<Long> failDeleteWithHistory = new HashSet<>();
    private long nextId = 1;

    public Paciente add(Paciente paciente) {
        long id = paciente.id() == null ? nextId++ : paciente.id();
        nextId = Math.max(nextId, id + 1);
        Paciente stored = withId(paciente, id);
        rows.put(id, stored);
        return stored;
    }

    public void markHistory(long id) {
        withHistory.add(id);
    }

    /** A non-cancelled cita between the patient and the medico. */
    public void markAttended(long idPaciente, long idMedico) {
        markCita(idPaciente, idMedico, false);
    }

    /** A cita that counts as history but, when cancelled, grants no scope. */
    public void markCita(long idPaciente, long idMedico, boolean cancelada) {
        withHistory.add(idPaciente);
        if (!cancelada) {
            attendedBy.computeIfAbsent(idPaciente, k -> new HashSet<>()).add(idMedico);
        }
    }

    /** Simulates a FOREIGN KEY violation on the next physical delete of the row. */
    public void failDeleteWithHistory(long id) {
        failDeleteWithHistory.add(id);
    }

    public int size() {
        return rows.size();
    }

    @Override
    public Page<Paciente> findPage(PacienteFilter filter, int page, int pageSize) {
        return page(null, filter, page, pageSize);
    }

    @Override
    public Page<Paciente> findPageAttendedBy(long idMedico, PacienteFilter filter, int page, int pageSize) {
        return page(idMedico, filter, page, pageSize);
    }

    private Page<Paciente> page(Long idMedico, PacienteFilter filter, int page, int pageSize) {
        List<Paciente> matching = rows.values().stream()
            .filter(p -> idMedico == null || attended(p.id(), idMedico))
            .filter(p -> filter.sexo() == null || filter.sexo().equals(p.sexo()))
            .filter(p -> filter.activo() == null || filter.activo() == p.activo())
            .filter(p -> filter.texto() == null || matchesText(p, filter.texto()))
            .sorted(Comparator.comparing(Paciente::apellidos).thenComparing(Paciente::nombres).thenComparing(Paciente::id))
            .collect(Collectors.toList());
        int from = (int) Math.min(Filters.offset(page, pageSize), matching.size());
        int to = Math.min(from + pageSize, matching.size());
        return new Page<>(new ArrayList<>(matching.subList(from, to)), matching.size(), page, pageSize);
    }

    private static boolean matchesText(Paciente p, String texto) {
        String needle = texto.toLowerCase(Locale.ROOT);
        return p.documento().toLowerCase(Locale.ROOT).contains(needle)
            || p.nombres().toLowerCase(Locale.ROOT).contains(needle)
            || p.apellidos().toLowerCase(Locale.ROOT).contains(needle)
            || p.nombreCompleto().toLowerCase(Locale.ROOT).contains(needle);
    }

    private boolean attended(long idPaciente, long idMedico) {
        return attendedBy.getOrDefault(idPaciente, Set.of()).contains(idMedico);
    }

    @Override
    public Optional<Paciente> findByIdAttendedBy(long id, long idMedico) {
        return attended(id, idMedico) ? findById(id) : Optional.empty();
    }

    @Override
    public Optional<Paciente> findById(long id) {
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public Optional<Paciente> findByDocumento(String documento) {
        return rows.values().stream().filter(p -> p.documento().equals(documento)).findFirst();
    }

    @Override
    public long insert(Paciente paciente) {
        return add(withId(paciente, null)).id();
    }

    @Override
    public void update(Paciente paciente) {
        rows.put(paciente.id(), paciente);
    }

    @Override
    public void setActivo(long id, boolean activo) {
        Paciente p = rows.get(id);
        rows.put(id, new Paciente(p.id(), p.documento(), p.nombres(), p.apellidos(), p.fechaNacimiento(),
            p.sexo(), p.telefono(), p.correo(), p.direccion(), activo));
    }

    @Override
    public void delete(long id) {
        if (failDeleteWithHistory.remove(id)) {
            throw new HasHistoryException("fk", null);
        }
        rows.remove(id);
    }

    @Override
    public boolean hasAssociatedHistory(long id) {
        return withHistory.contains(id);
    }

    @Override
    public List<Paciente> findActiveForSelect() {
        return rows.values().stream().filter(Paciente::activo)
            .sorted(Comparator.comparing(Paciente::apellidos).thenComparing(Paciente::nombres).thenComparing(Paciente::id))
            .collect(Collectors.toList());
    }

    private static Paciente withId(Paciente p, Long id) {
        return new Paciente(id, p.documento(), p.nombres(), p.apellidos(), p.fechaNacimiento(), p.sexo(),
            p.telefono(), p.correo(), p.direccion(), p.activo());
    }
}
