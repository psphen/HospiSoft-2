package com.hospisoft.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MedicamentoTest {

    private static Medicamento med(int stock, int minimo) {
        return new Medicamento(1L, "MED-001", "A", null, "Caja", stock, minimo, true);
    }

    @Test
    void stockStateRules() {
        assertEquals(StockState.DISPONIBLE, med(31, 30).stockState());
        assertEquals(StockState.STOCK_BAJO, med(30, 30).stockState());
        assertEquals(StockState.STOCK_BAJO, med(1, 30).stockState());
        assertEquals(StockState.AGOTADO, med(0, 30).stockState());
    }

    @Test
    void agotadoWinsWhenMinimumIsZero() {
        assertEquals(StockState.AGOTADO, med(0, 0).stockState());
        assertEquals(StockState.DISPONIBLE, med(1, 0).stockState());
        assertEquals(StockState.AGOTADO, StockState.of(0, 0));
        assertEquals(StockState.AGOTADO, StockState.of(-1, 5));
    }
}
