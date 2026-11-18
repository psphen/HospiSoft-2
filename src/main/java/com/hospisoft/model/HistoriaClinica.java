package com.hospisoft.model;

import java.time.OffsetDateTime;

public record HistoriaClinica(
    Long id,
    Long idCita,
    Long idPaciente,
    Long idMedico,
    OffsetDateTime fecha,
    String diagnostico,
    String tratamiento,
    String notas
) {
}
