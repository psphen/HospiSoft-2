package com.hospisoft.service;

public record CitaInput(Long idMedico, Long idPaciente, String fecha, String hora, String motivo) {
}
