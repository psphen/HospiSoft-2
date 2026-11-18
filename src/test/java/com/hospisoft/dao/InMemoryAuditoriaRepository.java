package com.hospisoft.dao;

import com.hospisoft.model.Auditoria;
import com.hospisoft.model.Page;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

public final class InMemoryAuditoriaRepository implements AuditoriaRepository {

    private final List<Auditoria> rows = new ArrayList<>();
    private final Clock clock;
    private Supplier<RuntimeException> insertFailure;
    private AuditoriaQuery lastQuery;
    private long nextId = 1;

    public InMemoryAuditoriaRepository(Clock clock) {
        this.clock = clock;
    }

    public void failInserts(Supplier<RuntimeException> failure) {
        this.insertFailure = failure;
    }

    public List<Auditoria> rows() {
        return List.copyOf(rows);
    }

    public AuditoriaQuery lastQuery() {
        return lastQuery;
    }

    @Override
    public void insert(Auditoria entry) {
        if (insertFailure != null) {
            throw insertFailure.get();
        }
        rows.add(new Auditoria(nextId++, OffsetDateTime.now(clock), entry.idUsuario(), entry.username(), entry.rol(),
            entry.accion(), entry.modulo(), entry.idRegistro(), entry.detalle()));
    }

    @Override
    public Page<Auditoria> findPage(AuditoriaQuery query, int page, int pageSize) {
        AuditoriaQuery q = query == null ? AuditoriaQuery.NONE : query;
        lastQuery = q;
        List<Auditoria> matching = rows.stream()
            .filter(a -> q.texto() == null || contains(a.username(), q.texto()) || contains(a.detalle(), q.texto()))
            .filter(a -> q.accion() == null || a.accion() == q.accion())
            .filter(a -> q.modulo() == null || a.modulo() == q.modulo())
            .filter(a -> q.from() == null || !a.fecha().isBefore(q.from()))
            .filter(a -> q.to() == null || a.fecha().isBefore(q.to()))
            .sorted(Comparator.comparing(Auditoria::fecha).thenComparing(Auditoria::id).reversed())
            .toList();
        long offset = Filters.offset(page, pageSize);
        List<Auditoria> items = matching.stream().skip(offset).limit(pageSize).toList();
        return new Page<>(items, matching.size(), page, pageSize);
    }

    private static boolean contains(String value, String text) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT));
    }
}
