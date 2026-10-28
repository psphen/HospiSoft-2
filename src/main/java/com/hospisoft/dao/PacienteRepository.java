package com.hospisoft.dao;

import com.hospisoft.model.Paciente;
import com.hospisoft.model.Page;
import java.util.Optional;

/**
 * Access to table paciente. Implementations throw db-layer exceptions when the database fails.
 *
 * <p>Scope rule for MEDICO: a patient is "attended" by a medico when at least one cita between
 * them exists with {@code estado <> 'CANCELADA'} (scheduled, in progress or finished). Cancelled
 * appointments grant nothing.
 */
public interface PacienteRepository {

    Page<Paciente> findPage(PacienteFilter filter, int page, int pageSize);

    /** Patients attended by the medico (see the scope rule above), filtered and paged. */
    Page<Paciente> findPageAttendedBy(long idMedico, PacienteFilter filter, int page, int pageSize);

    /** The patient only when attended by the medico; empty otherwise (no existence oracle). */
    Optional<Paciente> findByIdAttendedBy(long id, long idMedico);

    Optional<Paciente> findById(long id);

    Optional<Paciente> findByDocumento(String documento);

    /** @return the generated id */
    long insert(Paciente paciente);

    void update(Paciente paciente);

    void setActivo(long id, boolean activo);

    /** Physical delete. Callers must check {@link #hasAssociatedHistory(long)} first. */
    void delete(long id);

    /** True when a cita, a historia_clinica or a usuario account references the patient. */
    boolean hasAssociatedHistory(long id);

    /** Active patients ordered by apellidos, nombres (select lists). */
    java.util.List<Paciente> findActiveForSelect();
}
