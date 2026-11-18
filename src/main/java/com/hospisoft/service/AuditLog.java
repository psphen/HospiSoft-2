package com.hospisoft.service;

import com.hospisoft.model.AccionAuditoria;
import com.hospisoft.model.ModuloAuditoria;

public interface AuditLog {
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

    void record(AccionAuditoria accion, ModuloAuditoria modulo, Long idRegistro, String detalle);

    void recordAs(SessionContext actor, AccionAuditoria accion, ModuloAuditoria modulo, Long idRegistro, String detalle);

    void recordFailedLogin(String username, String detalle);
}
