package com.hospisoft.dao;

public record MedicoFilter(String especialidad, Boolean activo, String texto) {

    public static final MedicoFilter NONE = new MedicoFilter(null, null, null);

    public MedicoFilter {
        especialidad = Filters.blankToNull(especialidad);
        texto = Filters.blankToNull(texto);
    }
}
