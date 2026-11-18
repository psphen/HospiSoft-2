package com.hospisoft.service;

import com.hospisoft.model.AccionAuditoria;
import com.hospisoft.model.ModuloAuditoria;
import java.time.LocalDate;

public record AuditoriaFilter(String texto, AccionAuditoria accion, ModuloAuditoria modulo, LocalDate desde,
                              LocalDate hasta) {

    public static final AuditoriaFilter NONE = new AuditoriaFilter(null, null, null, null, null);

    public AuditoriaFilter {
        texto = Validation.clean(texto);
    }
}
