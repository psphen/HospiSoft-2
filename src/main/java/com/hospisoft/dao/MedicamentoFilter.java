package com.hospisoft.dao;

import com.hospisoft.model.StockState;

public record MedicamentoFilter(String texto, Boolean activo, StockState stockState) {

    public static final MedicamentoFilter NONE = new MedicamentoFilter(null, null, null);

    public MedicamentoFilter {
        texto = Filters.blankToNull(texto);
    }
}
