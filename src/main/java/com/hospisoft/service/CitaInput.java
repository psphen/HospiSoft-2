package com.hospisoft.service;

/**
 * Raw cita form data before validation (local wall time as typed by the user).
 *
 * @param fecha yyyy-MM-dd
 * @param hora  HH:mm, minutes in multiples of 5
 */
public record CitaInput(Long idMedico, Long idPaciente, String fecha, String hora, String motivo) {
}
