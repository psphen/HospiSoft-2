package com.hospisoft.model;

import java.time.OffsetDateTime;

/**
 * Row of table auditoria: who did what, when. The user columns are a snapshot taken when the row
 * was written (no foreign key), so entries survive the deletion of the user they describe.
 *
 * @param fecha      null on insert: the database stamps it with {@code DEFAULT now()}
 * @param idUsuario  null for failed logins (nobody authenticated)
 * @param username   the session username, or the attempted one for failed logins
 * @param rol        null for failed logins
 * @param idRegistro id of the affected row (paciente, medico, medicamento, cita); null when none
 */
public record Auditoria(
    Long id,
    OffsetDateTime fecha,
    Long idUsuario,
    String username,
    Rol rol,
    AccionAuditoria accion,
    ModuloAuditoria modulo,
    Long idRegistro,
    String detalle
) {
}
