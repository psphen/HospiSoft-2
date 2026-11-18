package com.hospisoft.model;

public record RecetaView(
    Receta receta,
    String codigo,
    String nombre,
    String presentacion
) {
}
