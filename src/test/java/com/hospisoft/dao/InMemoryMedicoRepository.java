package com.hospisoft.dao;

import com.hospisoft.model.Medico;
import com.hospisoft.model.Page;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** In-memory fake mirroring the DAO semantics (filters, ordering, paging) for service and bridge tests. */
public final class InMemoryMedicoRepository implements MedicoRepository {

    private final Map<Long, Medico> rows = new LinkedHashMap<>();
    private final Set<Long> withHistory = new HashSet<>();
    private final Set<Long> failDeleteWithHistory = new HashSet<>();
    private long nextId = 1;

    public Medico add(Medico medico) {
        long id = medico.id() == null ? nextId++ : medico.id();
        nextId = Math.max(nextId, id + 1);
        Medico stored = new Medico(id, medico.documento(), medico.nombres(), medico.apellidos(),
            medico.especialidad(), medico.telefono(), medico.correo(), medico.activo());
        rows.put(id, stored);
        return stored;
    }

    public void markHistory(long id) {
        withHistory.add(id);
    }

    /** Overwrites a row (no validation), for tests that deactivate a medico mid-flight. */
    public void replace(Medico medico) {
        rows.put(medico.id(), medico);
    }

    /** Simulates a FOREIGN KEY violation on the next physical delete of the row. */
    public void failDeleteWithHistory(long id) {
        failDeleteWithHistory.add(id);
    }

    public int size() {
        return rows.size();
    }

    @Override
    public Page<Medico> findPage(MedicoFilter filter, int page, int pageSize) {
        List<Medico> matching = rows.values().stream()
            .filter(m -> filter.especialidad() == null || filter.especialidad().equals(m.especialidad()))
            .filter(m -> filter.activo() == null || filter.activo() == m.activo())
            .filter(m -> filter.texto() == null || matchesText(m, filter.texto()))
            .sorted(Comparator.comparing(Medico::apellidos).thenComparing(Medico::nombres).thenComparing(Medico::id))
            .collect(Collectors.toList());
        int from = (int) Math.min(Filters.offset(page, pageSize), matching.size());
        int to = Math.min(from + pageSize, matching.size());
        return new Page<>(new ArrayList<>(matching.subList(from, to)), matching.size(), page, pageSize);
    }

    private static boolean matchesText(Medico m, String texto) {
        String needle = texto.toLowerCase(Locale.ROOT);
        return m.documento().toLowerCase(Locale.ROOT).contains(needle)
            || m.nombres().toLowerCase(Locale.ROOT).contains(needle)
            || m.apellidos().toLowerCase(Locale.ROOT).contains(needle)
            || m.nombreCompleto().toLowerCase(Locale.ROOT).contains(needle);
    }

    @Override
    public Optional<Medico> findById(long id) {
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public Optional<Medico> findByDocumento(String documento) {
        return rows.values().stream().filter(m -> m.documento().equals(documento)).findFirst();
    }

    @Override
    public long insert(Medico medico) {
        return add(new Medico(null, medico.documento(), medico.nombres(), medico.apellidos(),
            medico.especialidad(), medico.telefono(), medico.correo(), medico.activo())).id();
    }

    @Override
    public void update(Medico medico) {
        rows.put(medico.id(), medico);
    }

    @Override
    public void setActivo(long id, boolean activo) {
        Medico m = rows.get(id);
        rows.put(id, new Medico(m.id(), m.documento(), m.nombres(), m.apellidos(), m.especialidad(),
            m.telefono(), m.correo(), activo));
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
    public List<Medico> findActiveForSelect() {
        return rows.values().stream().filter(Medico::activo)
            .sorted(Comparator.comparing(Medico::apellidos).thenComparing(Medico::nombres).thenComparing(Medico::id))
            .collect(Collectors.toList());
    }

    @Override
    public List<String> findEspecialidades() {
        return rows.values().stream().map(Medico::especialidad).distinct().sorted().collect(Collectors.toList());
    }
}
