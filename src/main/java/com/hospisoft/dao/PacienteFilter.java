package com.hospisoft.dao;

/**
 * Optional filters for paciente listings. Null means "no restriction".
 *
 * @param sexo  M, F or OTRO
 * @param texto free text matched (case-insensitive) against documento, nombres and apellidos
 */
public record PacienteFilter(String sexo, Boolean activo, String texto) {

    public static final PacienteFilter NONE = new PacienteFilter(null, null, null);

    public PacienteFilter {
        sexo = Filters.blankToNull(sexo);
        texto = Filters.blankToNull(texto);
    }
}
