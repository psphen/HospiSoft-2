package com.hospisoft.model;

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
