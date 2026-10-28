package com.hospisoft.model;

/** A receta joined with the display columns of its medicamento. */
public record RecetaView(
    Receta receta,
    String codigo,
    String nombre,
    String presentacion
) {
}
