package com.hospisoft.model;

import java.time.OffsetDateTime;

/** Row of table cita. */
public record Cita(
    Long id,
    Long idMedico,
    Long idPaciente,
    OffsetDateTime fechaHoraProgramada,
    String motivo,
    EstadoCita estado,
    OffsetDateTime horaInicioReal,
    OffsetDateTime horaFinReal,
    String observaciones
) {
}
