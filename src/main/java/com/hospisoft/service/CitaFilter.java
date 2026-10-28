package com.hospisoft.service;

import com.hospisoft.model.EstadoCita;
import java.time.LocalDate;

/**
 * User-level filter of the cita list (local day, range preset). The service turns it into the
 * absolute bounds of {@code dao.CitaQuery}. Null means "no restriction".
 */
public record CitaFilter(String texto, LocalDate fecha, EstadoCita estado, Long idMedico, CitaRango rango) {

    public static final CitaFilter NONE = new CitaFilter(null, null, null, null, null);
}
