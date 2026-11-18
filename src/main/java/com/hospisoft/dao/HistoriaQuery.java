package com.hospisoft.dao;

public record HistoriaQuery(Long idPaciente, Long idMedico, String texto) {

    public static final HistoriaQuery NONE = new HistoriaQuery(null, null, null);

    public HistoriaQuery {
        texto = Filters.blankToNull(texto);
    }
}
