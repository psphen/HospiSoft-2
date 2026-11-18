package com.hospisoft.model;

import java.time.OffsetDateTime;

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
