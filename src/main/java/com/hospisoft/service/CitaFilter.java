package com.hospisoft.service;

import com.hospisoft.model.EstadoCita;
import java.time.LocalDate;

public record CitaFilter(String texto, LocalDate fecha, EstadoCita estado, Long idMedico, CitaRango rango) {

    public static final CitaFilter NONE = new CitaFilter(null, null, null, null, null);
}
