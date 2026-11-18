package com.hospisoft.model;

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
    public boolean atOrBelowMinimum() {
        return stock <= stockMinimo;
    }

    public StockState stockState() {
        return StockState.of(stock, stockMinimo);
    }
}
