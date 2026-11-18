package com.hospisoft.dao;

import com.hospisoft.model.Cita;
import com.hospisoft.model.CitaView;
import com.hospisoft.model.Page;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public interface CitaRepository {
    interface CitaTx {
        Optional<Boolean> lockMedico(long idMedico);

        Optional<Boolean> lockPaciente(long idPaciente);

        boolean existsOverlapForMedico(long idMedico, OffsetDateTime start, OffsetDateTime end, Long excludeId);

        boolean existsOverlapForPaciente(long idPaciente, OffsetDateTime start, OffsetDateTime end, Long excludeId);

        long insert(Cita cita);

        void reschedule(long id, long idMedico, long idPaciente, OffsetDateTime fechaHora, String motivo);
    }

    <T> T inTransaction(Function<CitaTx, T> work);

    Page<CitaView> findPage(CitaQuery query, int page, int pageSize);

    Optional<CitaView> findById(long id);

    void cancel(long id);

    List<CitaView> findByMedicoAndDay(long idMedico, OffsetDateTime dayStart, OffsetDateTime dayEnd);
}
