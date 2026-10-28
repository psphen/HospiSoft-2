package com.hospisoft.dao;

import com.hospisoft.model.Medico;
import com.hospisoft.model.Page;
import java.util.List;
import java.util.Optional;

/** Access to table medico. Implementations throw db-layer exceptions when the database fails. */
public interface MedicoRepository {

    Page<Medico> findPage(MedicoFilter filter, int page, int pageSize);

    Optional<Medico> findById(long id);

    Optional<Medico> findByDocumento(String documento);

    /** @return the generated id */
    long insert(Medico medico);

    void update(Medico medico);

    void setActivo(long id, boolean activo);

    /** Physical delete. Callers must check {@link #hasAssociatedHistory(long)} first. */
    void delete(long id);

    /** True when a cita, a historia_clinica or a usuario account references the medico. */
    boolean hasAssociatedHistory(long id);

    /** Distinct especialidad values, ordered. */
    List<String> findEspecialidades();

    /** Active medicos ordered by apellidos, nombres (select lists). */
    List<Medico> findActiveForSelect();
}
