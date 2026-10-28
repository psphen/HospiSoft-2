package com.hospisoft.dao;

import com.hospisoft.model.EstadoCita;
import com.hospisoft.model.HistoriaView;
import com.hospisoft.model.Page;
import com.hospisoft.model.RecetaView;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Attention lifecycle (start / close a cita) plus reads of historias and recetas.
 *
 * <p>Every write goes through {@link #inTransaction} so the row locks, the state guard, the
 * historia, the recetas and the stock decrements are one unit.
 */
public interface AtencionRepository {

    /** Stock snapshot of a medicamento locked inside the transaction. */
    record MedicamentoLock(long id, int stock, int stockMinimo, boolean activo) {
    }

    /** Operations available inside one transaction on one connection. */
    interface AtencionTx {

        /**
         * {@code SELECT activo ... FOR NO KEY UPDATE} on the medico row. Always the FIRST lock,
         * before any medicamento, to keep a global lock order.
         */
        Optional<Boolean> lockMedico(long idMedico);

        /**
         * {@code SELECT stock, stock_minimo, activo ... FOR NO KEY UPDATE}. Callers must lock the
         * medicamentos in ASCENDING id order so concurrent closings cannot deadlock.
         */
        Optional<MedicamentoLock> lockMedicamento(long idMedicamento);

        /** True when the medico already has a cita EN_CURSO (excluding {@code excludeCitaId}). */
        boolean existsEnCurso(long idMedico, Long excludeCitaId);

        /** Estado of the cita when it belongs to the medico; empty when missing or another medico's. */
        Optional<EstadoCita> estadoOfOwnCita(long idCita, long idMedico);

        /** PROGRAMADA -> EN_CURSO with hora_inicio_real = now(); affected rows. */
        int iniciar(long idCita, long idMedico);

        /** EN_CURSO -> FINALIZADA with hora_fin_real = now() and observaciones; affected rows. */
        int finalizar(long idCita, long idMedico, String observaciones);

        /**
         * @return the generated id
         * @throws com.hospisoft.service.ConflictException when the cita already has a historia
         */
        long insertHistoria(long idCita, long idPaciente, long idMedico, String diagnostico, String tratamiento,
                            String notas);

        long insertReceta(long idHistoria, long idMedicamento, int cantidad, String dosis, String duracion,
                          boolean entregado, String observacionNoDisponible);

        /** {@code UPDATE ... SET stock = stock - ? WHERE id = ? AND stock >= ?}; affected rows. */
        int decrementStock(long idMedicamento, int cantidad);
    }

    <T> T inTransaction(Function<AtencionTx, T> work);

    /** The historia of a cita (with its recetas), empty when the cita has none yet. */
    Optional<HistoriaView> findHistoriaByCita(long idCita);

    /** Historias matching the query, newest first, without recetas. */
    Page<HistoriaView> findHistoriasPage(HistoriaQuery query, int page, int pageSize);

    Optional<HistoriaView> findHistoriaById(long idHistoria);

    List<RecetaView> findRecetasByHistoria(long idHistoria);

    /** Every receta prescribed to a patient, newest first; each carries its historia's date. */
    Page<RecetaView> findRecetasPage(long idPaciente, int page, int pageSize);
}
