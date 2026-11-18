package com.hospisoft.dao;

public record PacienteFilter(String sexo, Boolean activo, String texto) {

    public static final PacienteFilter NONE = new PacienteFilter(null, null, null);

    public PacienteFilter {
        sexo = Filters.blankToNull(sexo);
        texto = Filters.blankToNull(texto);
    }
}
