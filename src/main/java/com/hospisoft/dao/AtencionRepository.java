package com.hospisoft.dao;

import com.hospisoft.model.EstadoCita;
import com.hospisoft.model.HistoriaView;
import com.hospisoft.model.Page;
import com.hospisoft.model.RecetaView;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public interface AtencionRepository {
    record MedicamentoLock(long id, int stock, int stockMinimo, boolean activo) {
    }

    interface AtencionTx {
        Optional<Boolean> lockMedico(long idMedico);

        Optional<MedicamentoLock> lockMedicamento(long idMedicamento);

        boolean existsEnCurso(long idMedico, Long excludeCitaId);

        Optional<EstadoCita> estadoOfOwnCita(long idCita, long idMedico);

        int iniciar(long idCita, long idMedico);

        int finalizar(long idCita, long idMedico, String observaciones);

        long insertHistoria(long idCita, long idPaciente, long idMedico, String diagnostico, String tratamiento,
                            String notas);

        long insertReceta(long idHistoria, long idMedicamento, int cantidad, String dosis, String duracion,
                          boolean entregado, String observacionNoDisponible);

        int decrementStock(long idMedicamento, int cantidad);
    }

    <T> T inTransaction(Function<AtencionTx, T> work);

    Optional<HistoriaView> findHistoriaByCita(long idCita);

    Page<HistoriaView> findHistoriasPage(HistoriaQuery query, int page, int pageSize);

    Optional<HistoriaView> findHistoriaById(long idHistoria);

    Page<RecetaView> findRecetasPage(long idPaciente, int page, int pageSize);
}
