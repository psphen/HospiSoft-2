package com.hospisoft.dao;

/**
 * Database-level filter for historia listings. Null means "no restriction"; the service decides
 * which bounds a role may use.
 *
 * @param texto free text over diagnostico and the medico's names
 */
public record HistoriaQuery(Long idPaciente, Long idMedico, String texto) {

    public static final HistoriaQuery NONE = new HistoriaQuery(null, null, null);

    public HistoriaQuery {
        texto = Filters.blankToNull(texto);
    }
}
