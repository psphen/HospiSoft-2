package com.hospisoft.service;

public record MedicamentoInput(
    Long id,
    String codigo,
    String nombre,
    String descripcion,
    String presentacion,
    Object stock,
    Object stockMinimo,
    Long stockExpected
) {
}
