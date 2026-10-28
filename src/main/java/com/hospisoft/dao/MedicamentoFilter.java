package com.hospisoft.dao;

import com.hospisoft.model.StockState;

/**
 * Optional filters for medicamento listings. Null means "no restriction".
 *
 * @param texto free text matched (case-insensitive) against codigo, nombre and presentacion
 */
public record MedicamentoFilter(String texto, Boolean activo, StockState stockState) {

    public static final MedicamentoFilter NONE = new MedicamentoFilter(null, null, null);

    public MedicamentoFilter {
        texto = Filters.blankToNull(texto);
    }
}
