package com.hospisoft.dao;

import com.hospisoft.model.Cita;
import com.hospisoft.model.CitaView;
import com.hospisoft.model.Page;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Access to table cita. Writes that depend on an overlap check run through {@link #inTransaction}
 * so that the row locks, the checks and the insert/update share one transaction.
 */
public interface CitaRepository {

    /** Operations available inside one transaction on one connection. */
    interface CitaTx {

        /**
         * {@code SELECT activo ... FOR NO KEY UPDATE} on the medico row. Always lock the medico
         * BEFORE the paciente to keep a global lock order.
         *
         * @return the row's activo flag, empty when the row does not exist
         */
        Optional<Boolean> lockMedico(long idMedico);

        /** Same as {@link #lockMedico(long)} for the paciente row (locked second). */
        Optional<Boolean> lockPaciente(long idPaciente);

        /** Another non-cancelled cita of the medico intersecting [start, end), ignoring {@code excludeId}. */
        boolean existsOverlapForMedico(long idMedico, OffsetDateTime start, OffsetDateTime end, Long excludeId);

        boolean existsOverlapForPaciente(long idPaciente, OffsetDateTime start, OffsetDateTime end, Long excludeId);

        /** Inserts as PROGRAMADA; returns the generated id. FK violations become ValidationException. */
        long insert(Cita cita);

        /**
         * Updates medico, paciente, date and motivo only while the cita is PROGRAMADA.
         * NotFoundException when the row is gone, ConflictException when its state changed,
         * ValidationException on a FK violation.
         */
        void reschedule(long id, long idMedico, long idPaciente, OffsetDateTime fechaHora, String motivo);
    }

    <T> T inTransaction(Function<CitaTx, T> work);

    Page<CitaView> findPage(CitaQuery query, int page, int pageSize);

    Optional<CitaView> findById(long id);

    /** Sets CANCELADA only while PROGRAMADA (NotFoundException / ConflictException otherwise). */
    void cancel(long id);

    /** Citas of the medico in [dayStart, dayEnd), ordered by time (for the agenda). */
    List<CitaView> findByMedicoAndDay(long idMedico, OffsetDateTime dayStart, OffsetDateTime dayEnd);
}
