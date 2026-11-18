package com.hospisoft.model;

public record Medico(
    Long id,
    String documento,
    String nombres,
    String apellidos,
    String especialidad,
    String telefono,
    String correo,
    boolean activo
) {
    public String nombreCompleto() {
        return nombres + " " + apellidos;
    }
}
