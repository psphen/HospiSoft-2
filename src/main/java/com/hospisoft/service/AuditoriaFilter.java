package com.hospisoft.service;

import com.hospisoft.model.AccionAuditoria;
import com.hospisoft.model.ModuloAuditoria;
import java.time.LocalDate;

/**
 * User-level filter of the audit log. {@code desde} and {@code hasta} are local days in the app
 * zone, both inclusive; the service turns them into the absolute bounds of {@code dao.AuditoriaQuery}.
 * Null means "no restriction".
 */
public record AuditoriaFilter(String texto, AccionAuditoria accion, ModuloAuditoria modulo, LocalDate desde,
                              LocalDate hasta) {

    public static final AuditoriaFilter NONE = new AuditoriaFilter(null, null, null, null, null);

    public AuditoriaFilter {
        texto = Validation.clean(texto);
    }
}
