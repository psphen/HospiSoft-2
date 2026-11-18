package com.hospisoft.model;

import java.time.OffsetDateTime;

public record Receta(
    Long id,
    Long idHistoria,
    Long idMedicamento,
    int cantidad,
    String dosis,
    String duracion,
    boolean entregado,
    String observacionNoDisponible,
    OffsetDateTime fechaHistoria
) {
    public Receta(Long id, Long idHistoria, Long idMedicamento, int cantidad, String dosis, String duracion,
                  boolean entregado, String observacionNoDisponible) {
        this(id, idHistoria, idMedicamento, cantidad, dosis, duracion, entregado, observacionNoDisponible, null);
    }
}
