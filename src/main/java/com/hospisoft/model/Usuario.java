package com.hospisoft.model;

/**
 * Row of table usuario.
 *
 * @param idReferencia id of the linked medico or paciente row (null for ADMIN)
 * @param displayName  not a column: resolved by the DAO from medico/paciente names, may be null
 */
public record Usuario(
    Long id,
    String username,
    String passwordHash,
    String salt,
    Rol rol,
    boolean activo,
    Long idReferencia,
    String displayName
) {
}
