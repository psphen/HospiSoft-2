package com.hospisoft.dao;

import com.hospisoft.model.Medicamento;
import com.hospisoft.model.Page;
import com.hospisoft.service.ConflictException;
import com.hospisoft.service.NotFoundException;
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

/** In-memory fake mirroring the DAO semantics (filters, stock state, ordering, paging). */
public class InMemoryMedicamentoRepository implements MedicamentoRepository {

    private final Map<Long, Medicamento> rows = new LinkedHashMap<>();
    private final Set<Long> withHistory = new HashSet<>();
    private final Set<Long> failDeleteWithHistory = new HashSet<>();
    private long nextId = 1;

    public Medicamento add(Medicamento m) {
        long id = m.id() == null ? nextId++ : m.id();
        nextId = Math.max(nextId, id + 1);
        Medicamento stored = withId(m, id);
        rows.put(id, stored);
        return stored;
    }

    public void markHistory(long id) {
        withHistory.add(id);
    }

    public void failDeleteWithHistory(long id) {
        failDeleteWithHistory.add(id);
    }

    public int size() {
        return rows.size();
    }

    /** Every row (active and inactive), for transaction snapshots in tests. */
    public List<Medicamento> allRows() {
        return new ArrayList<>(rows.values());
    }

    /** Overwrites a row without the optimistic stock check. */
    public void replace(Medicamento m) {
        rows.put(m.id(), m);
    }

    @Override
    public Page<Medicamento> findPage(MedicamentoFilter filter, int page, int pageSize) {
        List<Medicamento> matching = rows.values().stream()
            .filter(m -> filter.texto() == null || matchesText(m, filter.texto()))
            .filter(m -> filter.activo() == null || filter.activo() == m.activo())
            .filter(m -> filter.stockState() == null || filter.stockState() == m.stockState())
            .sorted(Comparator.comparing(Medicamento::nombre).thenComparing(Medicamento::id))
            .collect(Collectors.toList());
        int from = (int) Math.min(Filters.offset(page, pageSize), matching.size());
        int to = Math.min(from + pageSize, matching.size());
        return new Page<>(new ArrayList<>(matching.subList(from, to)), matching.size(), page, pageSize);
    }

    private static boolean matchesText(Medicamento m, String texto) {
        String needle = texto.toLowerCase(Locale.ROOT);
        return m.codigo().toLowerCase(Locale.ROOT).contains(needle)
            || m.nombre().toLowerCase(Locale.ROOT).contains(needle)
            || (m.presentacion() != null && m.presentacion().toLowerCase(Locale.ROOT).contains(needle));
    }

    @Override
    public Optional<Medicamento> findById(long id) {
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public Optional<Medicamento> findByCodigo(String codigo) {
        return rows.values().stream().filter(m -> m.codigo().equals(codigo)).findFirst();
    }

    @Override
    public long insert(Medicamento m) {
        return add(withId(m, null)).id();
    }

    @Override
    public void update(Medicamento m, int stockExpected) {
        Medicamento stored = rows.get(m.id());
        if (stored == null) {
            throw new NotFoundException("Medicamento " + m.id() + " not found");
        }
        if (stored.stock() != stockExpected) {
            throw new ConflictException("stock changed");
        }
        rows.put(m.id(), m);
    }

    @Override
    public void setActivo(long id, boolean activo) {
        Medicamento m = rows.get(id);
        rows.put(id, new Medicamento(m.id(), m.codigo(), m.nombre(), m.descripcion(), m.presentacion(), m.stock(),
            m.stockMinimo(), activo));
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
    public long countLowStock() {
        return rows.values().stream().filter(m -> m.activo() && m.atOrBelowMinimum()).count();
    }

    @Override
    public List<Medicamento> findAllActive() {
        return rows.values().stream().filter(Medicamento::activo)
            .sorted(Comparator.comparing(Medicamento::nombre).thenComparing(Medicamento::id))
            .collect(Collectors.toList());
    }

    private static Medicamento withId(Medicamento m, Long id) {
        return new Medicamento(id, m.codigo(), m.nombre(), m.descripcion(), m.presentacion(), m.stock(),
            m.stockMinimo(), m.activo());
    }
}
