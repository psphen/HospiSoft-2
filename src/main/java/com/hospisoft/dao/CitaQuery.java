package com.hospisoft.dao;

import com.hospisoft.model.EstadoCita;
import java.time.OffsetDateTime;

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
