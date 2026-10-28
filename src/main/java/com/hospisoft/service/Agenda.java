package com.hospisoft.service;

import com.hospisoft.model.CitaView;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * One medico's day: every grid slot of the working hours plus extra slots for citas outside them.
 *
 * @param slots ordered by time; a grid slot lists every non-cancelled cita intersecting
 *              [slot, slot + SLOT_MINUTES), so same-start or off-grid citas are never dropped
 */
public record Agenda(long idMedico, LocalDate fecha, List<Slot> slots) {

    public record Slot(LocalTime hora, List<CitaView> citas) {
        public Slot {
            citas = List.copyOf(citas);
        }

        public boolean libre() {
            return citas.isEmpty();
        }
    }
}
