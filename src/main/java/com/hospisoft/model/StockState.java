package com.hospisoft.model;

public enum StockState {
    DISPONIBLE,
    STOCK_BAJO,
    AGOTADO;

    public static StockState of(int stock, int stockMinimo) {
        if (stock <= 0) {
            return AGOTADO;
        }
        if (stock <= stockMinimo) {
            return STOCK_BAJO;
        }
        return DISPONIBLE;
    }
}
