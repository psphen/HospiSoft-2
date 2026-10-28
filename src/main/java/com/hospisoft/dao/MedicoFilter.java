package com.hospisoft.dao;

/**
 * Optional filters for medico listings. Null means "no restriction".
 *
 * @param texto free text matched (case-insensitive) against documento, nombres and apellidos
 */
public record MedicoFilter(String especialidad, Boolean activo, String texto) {

    public static final MedicoFilter NONE = new MedicoFilter(null, null, null);

    public MedicoFilter {
        especialidad = Filters.blankToNull(especialidad);
        texto = Filters.blankToNull(texto);
    }
}
