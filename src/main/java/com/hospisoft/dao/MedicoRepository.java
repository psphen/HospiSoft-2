package com.hospisoft.dao;

import com.hospisoft.model.Medico;
import com.hospisoft.model.Page;
import java.util.List;
import java.util.Optional;

public interface MedicoRepository {

    Page<Medico> findPage(MedicoFilter filter, int page, int pageSize);

    Optional<Medico> findById(long id);

    Optional<Medico> findByDocumento(String documento);

    long insert(Medico medico);

    void update(Medico medico);

    void setActivo(long id, boolean activo);

    void delete(long id);

    boolean hasAssociatedHistory(long id);

    List<String> findEspecialidades();

    List<Medico> findActiveForSelect();
}
