package com.hospisoft.model;

/** A cita joined with the display columns of its medico and paciente. */
public record CitaView(
    Cita cita,
    String medicoNombres,
    String medicoApellidos,
    String especialidad,
    String pacienteNombres,
    String pacienteApellidos,
    String pacienteDocumento
) {
    public String medicoNombre() {
        return medicoNombres + " " + medicoApellidos;
    }

    public String pacienteNombre() {
        return pacienteNombres + " " + pacienteApellidos;
    }
}
