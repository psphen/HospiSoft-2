package com.hospisoft.service;

import java.util.List;

/**
 * Closing form of an attention. {@code cantidad} stays raw (Long, Double, String or null) so
 * "not an integer" becomes a field message like every other validation error.
 */
public record AtencionInput(
    Long idCita,
    String diagnostico,
    String tratamiento,
    String notas,
    String observaciones,
    List<RecetaInput> recetas
) {
    public AtencionInput {
        recetas = recetas == null ? List.of() : List.copyOf(recetas);
    }

    /** One prescribed medicamento. */
    public record RecetaInput(Long idMedicamento, Object cantidad, String dosis, String duracion,
                              String observacionNoDisponible) {
    }
}
