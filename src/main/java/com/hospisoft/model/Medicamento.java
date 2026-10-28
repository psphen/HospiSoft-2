package com.hospisoft.model;

/** Row of table medicamento. */
public record Medicamento(
    Long id,
    String codigo,
    String nombre,
    String descripcion,
    String presentacion,
    int stock,
    int stockMinimo,
    boolean activo
) {
    /**
     * True when stock is at or below the minimum, INCLUDING stock 0. This is the dashboard
     * low-stock count rule ({@code stock <= stock_minimo}); the list filter STOCK_BAJO is the
     * exclusive state (0 &lt; stock &lt;= min), see {@link #stockState()}.
     */
    public boolean atOrBelowMinimum() {
        return stock <= stockMinimo;
    }

    /** Derived from stock and stockMinimo; AGOTADO wins over STOCK_BAJO when stock is 0. */
    public StockState stockState() {
        return StockState.of(stock, stockMinimo);
    }
}
