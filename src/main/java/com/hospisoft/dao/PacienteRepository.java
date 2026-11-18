package com.hospisoft.dao;

import com.hospisoft.model.Paciente;
import com.hospisoft.model.Page;
import java.util.Optional;

public interface PacienteRepository {

    Page<Paciente> findPage(PacienteFilter filter, int page, int pageSize);

    Page<Paciente> findPageAttendedBy(long idMedico, PacienteFilter filter, int page, int pageSize);

    Optional<Paciente> findByIdAttendedBy(long id, long idMedico);

    Optional<Paciente> findById(long id);

    Optional<Paciente> findByDocumento(String documento);

    long insert(Paciente paciente);

    void update(Paciente paciente);

    void setActivo(long id, boolean activo);

    void delete(long id);

    boolean hasAssociatedHistory(long id);

    java.util.List<Paciente> findActiveForSelect();
}
