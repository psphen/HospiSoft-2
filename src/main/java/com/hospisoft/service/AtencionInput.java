package com.hospisoft.service;

import java.util.List;

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

    public record RecetaInput(Long idMedicamento, Object cantidad, String dosis, String duracion,
                              String observacionNoDisponible) {
    }
}
