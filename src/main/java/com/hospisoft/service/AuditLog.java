package com.hospisoft.service;

import com.hospisoft.model.AccionAuditoria;
import com.hospisoft.model.ModuloAuditoria;

/**
 * Narrow port the use cases write the audit trail through. Callers invoke it only AFTER their
 * operation succeeded (for transactional use cases: after the commit).
 *
 * <p>Contract: implementations are best-effort and NEVER throw: a failure to audit must not fail
 * or roll back the user's operation. {@link AuditoriaService} is the production implementation.
 */
public interface AuditLog {

    /** Does nothing; for wiring and tests that do not care about the trail. */
    AuditLog NONE = new AuditLog() {
        @Override
        public void record(AccionAuditoria accion, ModuloAuditoria modulo, Long idRegistro, String detalle) {
        }

        @Override
        public void recordAs(SessionContext actor, AccionAuditoria accion, ModuloAuditoria modulo, Long idRegistro,
                             String detalle) {
        }

        @Override
        public void recordFailedLogin(String username, String detalle) {
        }
    };

    /** An action of the current session user. */
    void record(AccionAuditoria accion, ModuloAuditoria modulo, Long idRegistro, String detalle);

    /** An action of an explicit user: a login (no session stored yet) or a logout (session about to end). */
    void recordAs(SessionContext actor, AccionAuditoria accion, ModuloAuditoria modulo, Long idRegistro, String detalle);

    /**
     * A rejected login. Never pass the password.
     *
     * @param username the stored username of an existing account, or null when the input matched no
     *                 account (free text a user typed is never kept: it may be a mistyped password)
     */
    void recordFailedLogin(String username, String detalle);
}
