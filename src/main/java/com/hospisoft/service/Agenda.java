package com.hospisoft.service;

import com.hospisoft.model.CitaView;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record Agenda(long idMedico, LocalDate fecha, List<Slot> slots) {

    public record Slot(LocalTime hora, List<CitaView> citas) {
        public Slot {
            citas = List.copyOf(citas);
        }
    }
}
