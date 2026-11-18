package com.hospisoft.dao;

import com.hospisoft.model.Medicamento;
import com.hospisoft.model.Page;
import java.util.List;
import java.util.Optional;

public interface MedicamentoRepository {

    Page<Medicamento> findPage(MedicamentoFilter filter, int page, int pageSize);

    Optional<Medicamento> findById(long id);

    Optional<Medicamento> findByCodigo(String codigo);

    long insert(Medicamento medicamento);

    void update(Medicamento medicamento, int stockExpected);

    void setActivo(long id, boolean activo);

    void delete(long id);

    boolean hasAssociatedHistory(long id);

    long countLowStock();

    List<Medicamento> findAllActive();
}
