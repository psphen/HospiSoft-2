package com.hospisoft.model;

import java.util.List;

public record HistoriaView(
    HistoriaClinica historia,
    String medicoNombres,
    String medicoApellidos,
    String especialidad,
    String pacienteNombres,
    String pacienteApellidos,
    String pacienteDocumento,
    List<RecetaView> recetas
) {
    public HistoriaView {
        recetas = List.copyOf(recetas);
    }

    public String medicoNombre() {
        return medicoNombres + " " + medicoApellidos;
    }

    public String pacienteNombre() {
        return pacienteNombres + " " + pacienteApellidos;
    }

    public HistoriaView withRecetas(List<RecetaView> loaded) {
        return new HistoriaView(historia, medicoNombres, medicoApellidos, especialidad,
            pacienteNombres, pacienteApellidos, pacienteDocumento, loaded);
    }
}
