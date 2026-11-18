package com.hospisoft.service;

import com.hospisoft.model.CitaView;
import com.hospisoft.model.RecetaView;
import java.util.List;

public record Atencion(long idHistoria, CitaView cita, List<RecetaView> recetas) {

    public Atencion {
        recetas = List.copyOf(recetas);
    }
}
