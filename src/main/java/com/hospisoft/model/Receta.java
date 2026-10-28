package com.hospisoft.model;

import java.time.OffsetDateTime;

/**
 * Row of table receta.
 *
 * @param fechaHistoria not a column: the date of the historia it belongs to, filled by list queries
 */
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
