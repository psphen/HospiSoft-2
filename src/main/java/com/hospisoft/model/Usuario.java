package com.hospisoft.model;

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
