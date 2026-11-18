package com.hospisoft.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hospisoft.model.AccionAuditoria;
import com.hospisoft.model.ModuloAuditoria;
import java.util.ArrayList;
import java.util.List;

public final class RecordingAuditLog implements AuditLog {
    public record Entry(SessionContext actor, String username, AccionAuditoria accion, ModuloAuditoria modulo,
                        Long idRegistro, String detalle) {
    }

    private final List<Entry> entries = new ArrayList<>();
    private Runnable hook = () -> { };

    public synchronized void onRecord(Runnable check) {
        this.hook = check;
    }

    @Override
    public synchronized void record(AccionAuditoria accion, ModuloAuditoria modulo, Long idRegistro, String detalle) {
        hook.run();
        entries.add(new Entry(null, null, accion, modulo, idRegistro, detalle));
    }

    @Override
    public synchronized void recordAs(SessionContext actor, AccionAuditoria accion, ModuloAuditoria modulo,
                                      Long idRegistro, String detalle) {
        hook.run();
        entries.add(new Entry(actor, null, accion, modulo, idRegistro, detalle));
    }

    @Override
    public synchronized void recordFailedLogin(String username, String detalle) {
        hook.run();
        entries.add(new Entry(null, username, AccionAuditoria.LOGIN_FALLIDO, ModuloAuditoria.SESION, null, detalle));
    }

    public synchronized List<Entry> entries() {
        return List.copyOf(entries);
    }

    public synchronized Entry last() {
        return entries.get(entries.size() - 1);
    }

    public synchronized Entry only() {
        assertEquals(1, entries.size(), "expected exactly one audit entry: " + entries);
        return entries.get(0);
    }

    public synchronized void clear() {
        entries.clear();
    }
}
