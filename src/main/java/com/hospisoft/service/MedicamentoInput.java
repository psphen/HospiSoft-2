package com.hospisoft.service;

/**
 * Raw medicamento form data before validation. Stock values are kept as the raw JSON values
 * (Long, Double, String or null) so that "not an integer" and "negative" become field messages
 * merged with the other errors instead of a request-level BAD_REQUEST.
 *
 * @param stockExpected stock value the client loaded into the form; required on update (optimistic
 *                      concurrency on stock), ignored on insert
 */
public record MedicamentoInput(
    Long id,
    String codigo,
    String nombre,
    String descripcion,
    String presentacion,
    Object stock,
    Object stockMinimo,
    Long stockExpected
) {
}
