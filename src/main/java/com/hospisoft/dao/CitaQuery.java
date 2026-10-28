package com.hospisoft.dao;

import com.hospisoft.model.EstadoCita;
import java.time.OffsetDateTime;

/**
 * Database-level filter for cita listings. The service converts user input (a local day, a range
 * such as "today and future") into absolute bounds here, so the DAO never computes dates itself.
 * Null means "no restriction".
 *
 * @param texto      free text over paciente/medico names and paciente documento
 * @param dayStart   inclusive lower bound of a local day (used with {@code dayEnd})
 * @param dayEnd     exclusive upper bound of that day
 * @param from       inclusive lower bound of the range filter
 * @param to         exclusive upper bound of the range filter
 */
public record CitaQuery(
    String texto,
    OffsetDateTime dayStart,
    OffsetDateTime dayEnd,
    EstadoCita estado,
    Long idMedico,
    Long idPaciente,
    OffsetDateTime from,
    OffsetDateTime to
) {
    public static final CitaQuery NONE = new CitaQuery(null, null, null, null, null, null, null, null);

    public CitaQuery {
        texto = Filters.blankToNull(texto);
    }
}
