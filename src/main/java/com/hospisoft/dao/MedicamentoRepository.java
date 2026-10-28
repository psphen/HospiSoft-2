package com.hospisoft.dao;

import com.hospisoft.model.Medicamento;
import com.hospisoft.model.Page;
import java.util.List;
import java.util.Optional;

/** Access to table medicamento. Implementations throw db-layer exceptions when the database fails. */
public interface MedicamentoRepository {

    Page<Medicamento> findPage(MedicamentoFilter filter, int page, int pageSize);

    Optional<Medicamento> findById(long id);

    Optional<Medicamento> findByCodigo(String codigo);

    /** @return the generated id */
    long insert(Medicamento medicamento);

    /**
     * Optimistic update: only applies when the stored stock still equals {@code stockExpected}.
     *
     * @throws com.hospisoft.service.NotFoundException when the row does not exist
     * @throws com.hospisoft.service.ConflictException when it exists but its stock changed
     */
    void update(Medicamento medicamento, int stockExpected);

    void setActivo(long id, boolean activo);

    /** Physical delete. Callers must check {@link #hasAssociatedHistory(long)} first. */
    void delete(long id);

    /** True when a receta references the medicamento. */
    boolean hasAssociatedHistory(long id);

    /** Active rows at or below their minimum (dashboard, phase 6). */
    long countLowStock();

    /** Every active medicamento ordered by nombre (prescription form, phase 5). */
    List<Medicamento> findAllActive();
}
