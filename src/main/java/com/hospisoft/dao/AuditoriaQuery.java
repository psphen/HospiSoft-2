package com.hospisoft.dao;

import com.hospisoft.model.AccionAuditoria;
import com.hospisoft.model.ModuloAuditoria;
import java.time.OffsetDateTime;

/**
 * Database-level filter for the audit log. The service converts the user's local days into the
 * absolute bounds here, so the DAO never computes dates itself. Null means "no restriction".
 *
 * @param texto free text matched (case-insensitive) against username and detalle
 * @param from  inclusive lower bound of fecha
 * @param to    exclusive upper bound of fecha
 */
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
