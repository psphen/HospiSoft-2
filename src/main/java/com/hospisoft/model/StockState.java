package com.hospisoft.model;

/** Derived stock status of a medicamento (never stored). */
public enum StockState {
    DISPONIBLE,
    STOCK_BAJO,
    AGOTADO;

    /** AGOTADO when stock is 0, STOCK_BAJO when stock is at or below the minimum, DISPONIBLE otherwise. */
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
