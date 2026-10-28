package com.hospisoft.model;

import java.time.OffsetDateTime;

/** Row of table historia_clinica. Immutable in the database (trigger rejects UPDATE/DELETE). */
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
