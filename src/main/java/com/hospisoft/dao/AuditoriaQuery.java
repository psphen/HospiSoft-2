package com.hospisoft.dao;

import com.hospisoft.model.AccionAuditoria;
import com.hospisoft.model.ModuloAuditoria;
import java.time.OffsetDateTime;

public record AuditoriaQuery(
    String texto,
    AccionAuditoria accion,
    ModuloAuditoria modulo,
    OffsetDateTime from,
    OffsetDateTime to
) {
    public static final AuditoriaQuery NONE = new AuditoriaQuery(null, null, null, null, null);

    public AuditoriaQuery {
        texto = Filters.blankToNull(texto);
    }
}
