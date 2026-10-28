package com.hospisoft.model;

import java.time.LocalDate;
import java.time.Period;

/** Row of table paciente. sexo is one of M, F, OTRO. */
public record Paciente(
    Long id,
    String documento,
    String nombres,
    String apellidos,
    LocalDate fechaNacimiento,
    String sexo,
    String telefono,
    String correo,
    String direccion,
    boolean activo
) {
    public String nombreCompleto() {
        return nombres + " " + apellidos;
    }

    /** Whole years between the birth date and {@code today}; null when the birth date is unknown. */
    public Integer edad(LocalDate today) {
        if (fechaNacimiento == null || today == null || today.isBefore(fechaNacimiento)) {
            return null;
        }
        return Period.between(fechaNacimiento, today).getYears();
    }
}
